package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Each prewarm request, redirects included, gives up after this long. */
internal const val NEXT_EPISODE_PREWARM_TIMEOUT_MS = 20_000L

/**
 * How much of the end of the file the end-of-file prewarm reads: Matroska files usually keep their
 * seek index (cues) there, and the player reads it before the first frame.
 */
internal const val NEXT_EPISODE_PREWARM_TAIL_BYTES = 3L * 1024L * 1024L

/**
 * Debrid requests made for the next episode (the resolve and the prewarm) take turns: a debrid
 * service sees one of them at a time from this app.
 */
internal val nextEpisodeDebridGate = Mutex()

internal sealed interface NextEpisodePrewarmOutcome {
    /** The link answered with a 2xx: the debrid service has unlocked the file. */
    data class Warmed(
        val statusCode: Int,
        val finalUrl: String,
        val redirects: Int,
        /** The file size from the reply, null when the server did not say. */
        val totalSize: Long? = null,
    ) : NextEpisodePrewarmOutcome

    data class HttpError(val statusCode: Int, val redirects: Int) : NextEpisodePrewarmOutcome

    data class Failed(val message: String) : NextEpisodePrewarmOutcome

    data object TimedOut : NextEpisodePrewarmOutcome

    data class Skipped(val reason: String) : NextEpisodePrewarmOutcome
}

/** The end-of-file read that follows the first-byte request. */
internal sealed interface NextEpisodeTailOutcome {
    data class Read(val statusCode: Int, val range: String, val bytes: Long) : NextEpisodeTailOutcome

    data class HttpError(val statusCode: Int, val range: String) : NextEpisodeTailOutcome

    data class Failed(val message: String) : NextEpisodeTailOutcome

    data object TimedOut : NextEpisodeTailOutcome

    data class Skipped(val reason: String) : NextEpisodeTailOutcome
}

internal data class NextEpisodePrewarmResult(
    val outcome: NextEpisodePrewarmOutcome,
    /** The whole prewarm, both requests and the wait for the lock included. */
    val elapsedMs: Long,
    /** The redirected link, when the player can use it in place of the original. */
    val usableFinalUrl: String?,
    /** Why the original link is kept (null when [usableFinalUrl] is set or nothing redirected). */
    val keptOriginalReason: String?,
    val firstByteMs: Long = elapsedMs,
    /** Null when the end-of-file read is switched off. */
    val tail: NextEpisodeTailOutcome? = null,
    val tailMs: Long = 0L,
)

/**
 * Headers for the prewarm request: the player's own headers, with the user agent the player falls
 * back to, no compression (as the player asks), and a range for the first byte only.
 */
internal fun nextEpisodePrewarmHeaders(
    playerHeaders: Map<String, String>,
    defaultUserAgent: String,
    range: String = "bytes=0-0",
): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    playerHeaders.forEach { (key, value) ->
        if (!key.equals("Range", ignoreCase = true) && !key.equals("Accept-Encoding", ignoreCase = true)) {
            headers[key] = value
        }
    }
    if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
        headers["User-Agent"] = defaultUserAgent
    }
    headers["Accept-Encoding"] = "identity"
    headers["Range"] = range
    return headers
}

/**
 * The file size from the first-byte reply: the total in a 206's Content-Range ("bytes 0-0/1234"),
 * or the length of a 200 (a server that ignored the range sends the whole file). Null when the
 * server did not say (an asterisk for the total, no length) or the reply is something else.
 */
internal fun parsePrewarmTotalSize(statusCode: Int, contentRange: String?, contentLength: Long?): Long? =
    when (statusCode) {
        206 -> contentRange?.trim()?.substringAfterLast('/', "")?.toLongOrNull()?.takeIf { it > 0L }
        200 -> contentLength?.takeIf { it > 0L }
        else -> null
    }

/**
 * The range for the end-of-file read: the last [tailBytes] of a [totalSize]-byte file. The first
 * byte was already read, so a file smaller than that is read from its second byte. Null when the
 * size is unknown or there is nothing left to read.
 */
internal fun nextEpisodeTailRange(totalSize: Long?, tailBytes: Long = NEXT_EPISODE_PREWARM_TAIL_BYTES): String? {
    if (totalSize == null || totalSize <= 1L || tailBytes <= 0L) return null
    val start = maxOf(1L, totalSize - tailBytes)
    return "bytes=$start-${totalSize - 1}"
}

/** Only plain web links are prewarmed: no local servers, torrents or other schemes. */
internal fun isNextEpisodePrewarmableUrl(url: String?): Boolean {
    val parsed = url?.toHttpUrlOrNull() ?: return false
    return !PlayerMediaSourceFactory.isLoopbackUrl(url)
        && (parsed.scheme == "http" || parsed.scheme == "https")
}

internal sealed interface PrewarmedUrlDecision {
    data class UseFinal(val url: String) : PrewarmedUrlDecision

    data class KeepOriginal(val reason: String) : PrewarmedUrlDecision
}

/**
 * Whether the player may open [finalUrl] (where the prewarm's redirects ended) instead of
 * [originalUrl]. Anything the player decides from the link must come out the same, so the final
 * link is used only when:
 * - it answered with a 2xx;
 * - it is a plain web link on a remote host, and not http where the original was https;
 * - the stream sends no headers of its own (they were meant for the original host);
 * - the file name taken from the link (when the stream gives none) is unchanged;
 * - the detected container type is unchanged.
 * Otherwise the player opens the original link, which the prewarm has already unlocked.
 */
internal fun decidePrewarmedUrl(
    originalUrl: String,
    finalUrl: String?,
    statusCode: Int,
    playerHeaders: Map<String, String>,
    filenameHint: String?,
    mimeTypeOf: (url: String, filename: String?) -> String?,
): PrewarmedUrlDecision {
    if (finalUrl == null || finalUrl == originalUrl) return PrewarmedUrlDecision.KeepOriginal("no redirect")
    if (statusCode !in 200..299) return PrewarmedUrlDecision.KeepOriginal("status $statusCode")
    val original = originalUrl.toHttpUrlOrNull()
        ?: return PrewarmedUrlDecision.KeepOriginal("original is not a web link")
    val final = finalUrl.toHttpUrlOrNull()
        ?: return PrewarmedUrlDecision.KeepOriginal("final is not a web link")
    if (PlayerMediaSourceFactory.isLoopbackUrl(finalUrl)) return PrewarmedUrlDecision.KeepOriginal("final is local")
    if (original.scheme == "https" && final.scheme != "https") {
        return PrewarmedUrlDecision.KeepOriginal("https to http")
    }
    if (playerHeaders.keys.any { !it.equals("User-Agent", ignoreCase = true) }) {
        return PrewarmedUrlDecision.KeepOriginal("stream sends its own headers")
    }
    if (filenameHint == null && streamFilenameFromUrl(finalUrl) != streamFilenameFromUrl(originalUrl)) {
        return PrewarmedUrlDecision.KeepOriginal("file name differs")
    }
    if (mimeTypeOf(finalUrl, filenameHint) != mimeTypeOf(originalUrl, filenameHint)) {
        return PrewarmedUrlDecision.KeepOriginal("container type differs")
    }
    return PrewarmedUrlDecision.UseFinal(finalUrl)
}

/** The prepared stream with the prewarmed link in place of its own. */
internal fun Stream.withPrewarmedUrl(url: String?): Stream =
    if (url == null || url == this.url) this else copy(url = url)

/**
 * Opens the next episode's link once, the way the player will (same client, headers and link), so
 * the debrid service unlocks the file now rather than when the episode starts. It asks for the
 * first byte only, follows the redirects, and closes. With [readTail], it then reads the last
 * [NEXT_EPISODE_PREWARM_TAIL_BYTES] of the file (where the player looks for the seek index) from
 * the link the player will open, and closes. The requests run one after the other while holding
 * [nextEpisodeDebridGate], and stop when the coroutine is cancelled.
 */
internal suspend fun prewarmNextEpisodeStream(stream: Stream, readTail: Boolean): NextEpisodePrewarmResult {
    val startedAt = SystemClock.elapsedRealtime()
    fun now() = SystemClock.elapsedRealtime()

    val rawUrl = stream.getStreamUrl()
    if (stream.isTorrent() || !isNextEpisodePrewarmableUrl(rawUrl)) {
        return NextEpisodePrewarmResult(NextEpisodePrewarmOutcome.Skipped("not a web link"), 0L, null, null)
    }
    val playback = PlayerMediaSourceFactory.normalizePlaybackRequest(
        rawUrl!!,
        PlayerMediaSourceFactory.sanitizeHeaders(stream.behaviorHints?.proxyHeaders?.request),
    )
    val client = PlayerPlaybackNetworking.createHttpClient(playback.headers)
    fun request(url: String, range: String) = Request.Builder().url(url).get().apply {
        nextEpisodePrewarmHeaders(playback.headers, PlayerMediaSourceFactory.DEFAULT_USER_AGENT, range)
            .forEach { (key, value) -> header(key, value) }
    }.build()

    return nextEpisodeDebridGate.withLock {
        val firstStartedAt = now()
        val first = withTimeoutOrNull(NEXT_EPISODE_PREWARM_TIMEOUT_MS) {
            executePrewarmCall(client.newCall(request(playback.url, "bytes=0-0")), readBody = false)
        }
        val firstByteMs = now() - firstStartedAt
        val outcome = when (first) {
            null -> NextEpisodePrewarmOutcome.TimedOut
            is PrewarmCallResult.Failure -> NextEpisodePrewarmOutcome.Failed(first.message)
            is PrewarmCallResult.Answered -> if (first.statusCode in 200..299) {
                NextEpisodePrewarmOutcome.Warmed(
                    statusCode = first.statusCode,
                    finalUrl = first.finalUrl,
                    redirects = first.redirects,
                    totalSize = parsePrewarmTotalSize(first.statusCode, first.contentRange, first.contentLength),
                )
            } else {
                NextEpisodePrewarmOutcome.HttpError(first.statusCode, first.redirects)
            }
        }

        val warmed = outcome as? NextEpisodePrewarmOutcome.Warmed
        val decision = decidePrewarmedUrl(
            originalUrl = playback.url,
            finalUrl = warmed?.finalUrl,
            statusCode = warmed?.statusCode ?: -1,
            playerHeaders = playback.headers,
            filenameHint = stream.behaviorHints?.filename,
            mimeTypeOf = { url, filename -> PlayerMediaSourceFactory.inferMimeType(url, filename) },
        )
        val usableFinalUrl = (decision as? PrewarmedUrlDecision.UseFinal)?.url

        val tailStartedAt = now()
        val tail = if (!readTail) {
            null
        } else if (warmed == null) {
            NextEpisodeTailOutcome.Skipped("first request did not succeed")
        } else {
            val range = nextEpisodeTailRange(warmed.totalSize)
            if (range == null) {
                NextEpisodeTailOutcome.Skipped("file size unknown")
            } else {
                // From the link the player will open, so the same server warms up.
                val tailUrl = usableFinalUrl ?: playback.url
                when (val read = withTimeoutOrNull(NEXT_EPISODE_PREWARM_TIMEOUT_MS) {
                    executePrewarmCall(client.newCall(request(tailUrl, range)), readBody = true)
                }) {
                    null -> NextEpisodeTailOutcome.TimedOut
                    is PrewarmCallResult.Failure -> NextEpisodeTailOutcome.Failed(read.message)
                    is PrewarmCallResult.Answered -> if (read.statusCode in 200..299) {
                        NextEpisodeTailOutcome.Read(read.statusCode, range, read.bytesRead)
                    } else {
                        NextEpisodeTailOutcome.HttpError(read.statusCode, range)
                    }
                }
            }
        }
        val tailMs = if (tail == null) 0L else now() - tailStartedAt

        NextEpisodePrewarmResult(
            outcome = outcome,
            elapsedMs = now() - startedAt,
            usableFinalUrl = usableFinalUrl,
            keptOriginalReason = (decision as? PrewarmedUrlDecision.KeepOriginal)?.reason,
            firstByteMs = firstByteMs,
            tail = tail,
            tailMs = tailMs,
        )
    }
}

private sealed interface PrewarmCallResult {
    data class Answered(
        val statusCode: Int,
        val finalUrl: String,
        val redirects: Int,
        val contentRange: String?,
        val contentLength: Long?,
        val bytesRead: Long,
    ) : PrewarmCallResult

    data class Failure(val message: String) : PrewarmCallResult
}

/**
 * Runs one prewarm request. Without [readBody] only the first byte of the body is read; with it,
 * the whole body of a 206 (the asked range) is read and dropped. A 200 carries the whole file, so
 * it is always cut off after the first byte.
 */
private suspend fun executePrewarmCall(call: Call, readBody: Boolean): PrewarmCallResult =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resume(PrewarmCallResult.Failure(e.message ?: e.javaClass.simpleName))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    val redirects = generateSequence(it.priorResponse) { prior -> prior.priorResponse }.count()
                    var bytesRead = 0L
                    if (it.isSuccessful) {
                        val readOutcome = runCatching {
                            val body = it.body?.byteStream() ?: return@runCatching
                            if (!readBody || it.code != 206) {
                                if (body.read() >= 0) bytesRead = 1L
                            } else {
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = body.read(buffer)
                                    if (n < 0) break
                                    bytesRead += n
                                }
                            }
                        }
                        readOutcome.exceptionOrNull()?.let { e ->
                            if (readBody) return@use PrewarmCallResult.Failure(e.message ?: e.javaClass.simpleName)
                        }
                    }
                    PrewarmCallResult.Answered(
                        statusCode = it.code,
                        finalUrl = it.request.url.toString(),
                        redirects = redirects,
                        contentRange = it.header("Content-Range"),
                        contentLength = it.body?.contentLength()?.takeIf { length -> length >= 0L },
                        bytesRead = bytesRead,
                    )
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
