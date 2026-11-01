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

/** The whole prewarm, redirects included, gives up after this long. */
internal const val NEXT_EPISODE_PREWARM_TIMEOUT_MS = 20_000L

/**
 * Debrid requests made for the next episode (the resolve and the prewarm) take turns: a debrid
 * service sees one of them at a time from this app.
 */
internal val nextEpisodeDebridGate = Mutex()

internal sealed interface NextEpisodePrewarmOutcome {
    /** The link answered with a 2xx: the debrid service has unlocked the file. */
    data class Warmed(val statusCode: Int, val finalUrl: String, val redirects: Int) : NextEpisodePrewarmOutcome

    data class HttpError(val statusCode: Int, val redirects: Int) : NextEpisodePrewarmOutcome

    data class Failed(val message: String) : NextEpisodePrewarmOutcome

    data object TimedOut : NextEpisodePrewarmOutcome

    data class Skipped(val reason: String) : NextEpisodePrewarmOutcome
}

internal data class NextEpisodePrewarmResult(
    val outcome: NextEpisodePrewarmOutcome,
    val elapsedMs: Long,
    /** The redirected link, when the player can use it in place of the original. */
    val usableFinalUrl: String?,
    /** Why the original link is kept (null when [usableFinalUrl] is set or nothing redirected). */
    val keptOriginalReason: String?,
)

/**
 * Headers for the prewarm request: the player's own headers, with the user agent the player falls
 * back to, no compression (as the player asks), and a range for the first byte only.
 */
internal fun nextEpisodePrewarmHeaders(
    playerHeaders: Map<String, String>,
    defaultUserAgent: String,
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
    headers["Range"] = "bytes=0-0"
    return headers
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
 * first byte only, follows the redirects, and closes. It holds [nextEpisodeDebridGate] throughout
 * and stops when its coroutine is cancelled.
 */
internal suspend fun prewarmNextEpisodeStream(stream: Stream): NextEpisodePrewarmResult {
    val startedAt = SystemClock.elapsedRealtime()
    fun elapsed() = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

    val rawUrl = stream.getStreamUrl()
    if (stream.isTorrent() || !isNextEpisodePrewarmableUrl(rawUrl)) {
        return NextEpisodePrewarmResult(NextEpisodePrewarmOutcome.Skipped("not a web link"), 0L, null, null)
    }
    val playback = PlayerMediaSourceFactory.normalizePlaybackRequest(
        rawUrl!!,
        PlayerMediaSourceFactory.sanitizeHeaders(stream.behaviorHints?.proxyHeaders?.request),
    )
    val requestHeaders = nextEpisodePrewarmHeaders(playback.headers, PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
    val request = Request.Builder().url(playback.url).get().apply {
        requestHeaders.forEach { (key, value) -> header(key, value) }
    }.build()
    val client = PlayerPlaybackNetworking.createHttpClient(playback.headers)

    val outcome = nextEpisodeDebridGate.withLock {
        withTimeoutOrNull(NEXT_EPISODE_PREWARM_TIMEOUT_MS) { executePrewarm(client.newCall(request)) }
            ?: NextEpisodePrewarmOutcome.TimedOut
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
    return NextEpisodePrewarmResult(
        outcome = outcome,
        elapsedMs = elapsed(),
        usableFinalUrl = (decision as? PrewarmedUrlDecision.UseFinal)?.url,
        keptOriginalReason = (decision as? PrewarmedUrlDecision.KeepOriginal)?.reason,
    )
}

private suspend fun executePrewarm(call: Call): NextEpisodePrewarmOutcome =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resume(NextEpisodePrewarmOutcome.Failed(e.message ?: e.javaClass.simpleName))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = response.use {
                    val redirects = generateSequence(it.priorResponse) { prior -> prior.priorResponse }.count()
                    if (it.isSuccessful) {
                        // The first byte is all that was asked for; reading it completes the request.
                        runCatching { it.body?.byteStream()?.read() }
                        NextEpisodePrewarmOutcome.Warmed(it.code, it.request.url.toString(), redirects)
                    } else {
                        NextEpisodePrewarmOutcome.HttpError(it.code, redirects)
                    }
                }
                if (continuation.isActive) continuation.resume(outcome)
            }
        })
    }
