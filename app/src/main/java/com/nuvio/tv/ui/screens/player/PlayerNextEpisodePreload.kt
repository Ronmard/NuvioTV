package com.nuvio.tv.ui.screens.player

import android.os.Debug
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.libass.media.AssHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Next-episode preload: once the 70% preparation has picked (and prewarmed) the next episode's
 * link, its media source is built the way an episode switch builds it and prepared on a shared
 * playback thread, reading the header (tracks, seek index) and nothing more. At the switch,
 * initializePlayer hands it to the new ExoPlayer when nothing that shapes the media source
 * changed, so the switch skips the header read. With libass, the preload reads the file into a
 * handler of its own, which the new player takes over.
 *
 * These keep today's path (the preload is skipped or not used, with the reason logged): mpv, the
 * custom buffer engines, the disk cache, parallel connections, subtitle autosync, subtitles
 * attached at start, a start that resumes past the beginning, a low-RAM device, and an app
 * already above [NEXT_EPISODE_PRELOAD_MAX_HEAP_FRACTION] of its memory limit.
 */

/** How long a preload may take to read the header before it is given up. */
internal const val NEXT_EPISODE_PRELOAD_PREPARE_TIMEOUT_MS = 45_000L

/**
 * A preload holds the next episode's header (and, with libass, its fonts): about 10 to 30 MB on a
 * box. It is skipped when the app already uses more than this share of its memory limit.
 */
internal const val NEXT_EPISODE_PRELOAD_MAX_HEAP_FRACTION = 0.60

/**
 * Why a preload should not start for memory reasons, or null when it can. [isLowRamDevice] is
 * Android's low-RAM flag (Android Go class devices); the app's own memory tiers are not used here,
 * since they count 384 MB boxes, where the preload was tested, as low.
 */
internal fun nextEpisodePreloadMemorySkipReason(
    javaUsedBytes: Long,
    javaMaxBytes: Long,
    isLowRamDevice: Boolean,
    maxHeapFraction: Double = NEXT_EPISODE_PRELOAD_MAX_HEAP_FRACTION,
): String? {
    if (isLowRamDevice) return "low-RAM device"
    if (javaMaxBytes <= 0L || javaMaxBytes == Long.MAX_VALUE) return null
    val usedPercent = (javaUsedBytes * 100L / javaMaxBytes).toInt()
    return if (javaUsedBytes > javaMaxBytes * maxHeapFraction) {
        "memory at $usedPercent% of the app's limit (over ${(maxHeapFraction * 100).toInt()}%)"
    } else {
        null
    }
}

/** The setup a media source is built with, apart from the stream itself. */
internal data class NextEpisodePreloadSetup(
    val dolbyVision: DolbyVisionConversionConfig,
    val stripDvRpu: Boolean,
    val stripHdr10PlusSei: Boolean,
    val useLibass: Boolean,
    val autoSyncEnabled: Boolean,
    /** The stock load control (no performance mode, no custom buffer engine). */
    val stockLoadControl: Boolean,
    val vodCacheEnabled: Boolean,
    val parallelConnections: Boolean,
    /** Subtitles attached to the media item at start (addon subtitles loaded with the stream). */
    val startupSubtitles: Boolean,
)

/** The stream a media source opens, as the player sees it. */
internal data class NextEpisodePreloadKey(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val filename: String?,
    val cacheKey: String?,
    val responseHeaders: Map<String, String>,
)

internal data class NextEpisodePreloadFingerprint(
    val key: NextEpisodePreloadKey,
    val setup: NextEpisodePreloadSetup,
)

/** Why a media source with this setup is not preloaded; null when it can be. */
internal fun NextEpisodePreloadSetup.unsupportedReason(): String? = when {
    autoSyncEnabled -> "subtitle autosync on"
    !stockLoadControl -> "custom buffer engine"
    vodCacheEnabled -> "vod cache on"
    parallelConnections -> "parallel connections on"
    startupSubtitles -> "startup subtitles attached"
    else -> null
}

/**
 * The first difference between what was preloaded ([expected]) and what the player is about to
 * open ([actual]) that rules the preload out; null when it can be used. A start past the beginning
 * (a resume) would not reuse the preloaded period either.
 */
internal fun nextEpisodePreloadMismatch(
    expected: NextEpisodePreloadFingerprint,
    actual: NextEpisodePreloadFingerprint,
    resumePositionMs: Long,
): String? {
    actual.setup.unsupportedReason()?.let { return it }
    val e = expected.key
    val a = actual.key
    return when {
        e.url != a.url -> "link changed"
        e.headers != a.headers -> "headers changed"
        e.mimeType != a.mimeType -> "container type changed (${e.mimeType} -> ${a.mimeType})"
        e.filename != a.filename -> "file name changed"
        e.cacheKey != a.cacheKey -> "cache key changed"
        e.responseHeaders != a.responseHeaders -> "response headers changed"
        expected.setup.useLibass != actual.setup.useLibass -> "libass ${if (actual.setup.useLibass) "turned on" else "turned off"}"
        expected.setup.dolbyVision != actual.setup.dolbyVision -> "dolby vision setup changed"
        expected.setup.stripDvRpu != actual.setup.stripDvRpu ||
            expected.setup.stripHdr10PlusSei != actual.setup.stripHdr10PlusSei -> "hdr stripping changed"
        expected.setup != actual.setup -> "setup changed"
        resumePositionMs > 0L -> "starts at ${resumePositionMs / 1000}s, not the beginning"
        else -> null
    }
}

/**
 * The key the player will build for [stream], the same way an episode switch does
 * (applySelectedStreamState): normalized link and headers, file name, container type, cache key.
 */
internal fun nextEpisodePreloadKey(stream: Stream): NextEpisodePreloadKey? {
    val rawUrl = stream.getStreamUrl()
    if (stream.isTorrent() || !isNextEpisodePrewarmableUrl(rawUrl)) return null
    val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
        rawUrl!!,
        PlayerMediaSourceFactory.sanitizeHeaders(stream.behaviorHints?.proxyHeaders?.request),
    )
    val filename = stream.behaviorHints?.filename ?: streamFilenameFromUrl(request.url)
    val responseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty()
    val fileIdx = stream.getEffectiveFileIdx()
    return NextEpisodePreloadKey(
        url = request.url,
        headers = request.headers,
        mimeType = PlayerMediaSourceFactory.inferMimeType(request.url, filename, responseHeaders),
        filename = filename,
        cacheKey = stream.getEffectiveInfoHash()?.lowercase()?.takeIf { fileIdx != null }?.let { "$it:$fileIdx" },
        responseHeaders = responseHeaders,
    )
}

internal data class NextEpisodePreloadMemory(val javaUsedBytes: Long, val nativeAllocatedBytes: Long) {
    companion object {
        fun now(): NextEpisodePreloadMemory {
            val runtime = Runtime.getRuntime()
            return NextEpisodePreloadMemory(
                javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            )
        }
    }
}

internal enum class NextEpisodePreloadStatus { LOADING, PREPARED, FAILED }

/** One preloaded media source and what is known about it. */
@OptIn(UnstableApi::class)
internal class NextEpisodePreload(
    val fingerprint: NextEpisodePreloadFingerprint,
    val allocator: DefaultAllocator,
    val memoryBefore: NextEpisodePreloadMemory,
    /** Renderers the preload picked tracks for; the player that takes it must have as many. */
    val rendererCount: Int,
    /** With libass: the handler the preload's extractor feeds fonts and subtitle tracks to. */
    val assHandler: AssHandler?,
) {
    /** Set once the player owns [assHandler] (it then releases it with itself). */
    @Volatile var assHandlerHandedOver: Boolean = false

    val startedAtMs: Long = SystemClock.elapsedRealtime()
    lateinit var source: PreloadMediaSource

    @Volatile var status: NextEpisodePreloadStatus = NextEpisodePreloadStatus.LOADING
    @Volatile var preparedAtMs: Long = 0L
    @Volatile var memoryAtPrepared: NextEpisodePreloadMemory? = null
    @Volatile var error: String? = null
    @Volatile var usedByPlayer: Boolean = false
    @Volatile var released: Boolean = false
    val prepared = CompletableDeferred<Boolean>()

    val headerMs: Long get() = if (preparedAtMs > 0L) preparedAtMs - startedAtMs else -1L

    fun release() {
        if (released) return
        released = true
        if (!prepared.isCompleted) prepared.complete(false)
        if (this::source.isInitialized) runCatching { source.releasePreloadMediaSource() }
        if (!assHandlerHandedOver) assHandler?.let { runCatching { it.release() } }
    }
}

/** What the switch takes from a matching preload. */
@OptIn(UnstableApi::class)
internal class NextEpisodePreloadHit(
    val preload: NextEpisodePreload,
    /** The stock load control on the preload's allocator, so the buffer limit counts its samples. */
    val loadControl: DefaultLoadControl,
    val looper: Looper,
)

/** The preload bookkeeping for one player. */
internal class NextEpisodePreloadState {
    /** Waiting for the switch. */
    var pending: NextEpisodePreload? = null

    /** Handed to the current player; released with it. */
    var inUse: NextEpisodePreload? = null

    /** The setup of the last ExoPlayer start, which the next episode is expected to share. */
    var lastSetup: NextEpisodePreloadSetup? = null

    /** The last decision at a switch, for diagnostics: "used ..." or "not used: <reason>". */
    @Volatile var lastDecision: String? = null

    /** Why the last preload was released or not started, for diagnostics. */
    @Volatile var lastReleaseReason: String? = null
}

/**
 * The playback thread preloads load on. A preloaded source must play on the thread it was prepared
 * on, so the player that takes one over is built on it too (ExoPlayer.Builder.setPlaybackLooper);
 * every other player keeps its own thread. It is started on first use and lives for the rest of
 * the app process: players and sources on it are released as usual, the thread itself is never
 * quit (quitting it would break a player built on it), and an idle looper costs nothing.
 */
internal object NextEpisodePreloadThread {
    val looper: Looper by lazy {
        HandlerThread("NuvioPreloadPlayback", Process.THREAD_PRIORITY_AUDIO).apply { start() }.looper
    }
}

/**
 * Prepares [stream]'s media source (its header: tracks and seek index) on the shared playback
 * thread, then stops loading. Returns null when it is ready (or still loading at the timeout's
 * end, given up), or the reason it was not started. The caller holds the debrid lock.
 */
@OptIn(UnstableApi::class)
internal suspend fun PlayerRuntimeController.preloadNextEpisode(stream: Stream): String? {
    releasePendingNextEpisodePreload("replaced")
    if (!nextEpisodePreloadEnabledSetting) return "off"
    if (currentInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER || isUsingMpvEngine()) return "mpv"
    val setup = nextEpisodePreload.lastSetup ?: return "no ExoPlayer start yet"
    setup.unsupportedReason()?.let { return it }
    val key = nextEpisodePreloadKey(stream) ?: return "not a web link"
    val runtime = Runtime.getRuntime()
    nextEpisodePreloadMemorySkipReason(
        javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
        javaMaxBytes = runtime.maxMemory(),
        isLowRamDevice = (context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager)?.isLowRamDevice == true,
    )?.let { return it }
    // ExoPlayer requires the preload's track pick to cover as many renderers as the player that
    // takes it; the current player is built the same way, so its renderer types stand in. The
    // pick runs on the preload thread, so it must not touch the live renderers (their audio sink
    // allows one thread only): stand-ins with the same types answer instead. What they pick does
    // not matter, the player picks again when its own choice differs.
    val currentPlayer = _exoPlayer ?: return "no ExoPlayer to copy renderers from"
    val rendererCapabilities = Array(currentPlayer.rendererCount) { index ->
        nextEpisodePreloadStandInRenderer(currentPlayer.getRendererType(index))
    }

    val baseExtractorsFactory = DolbyVisionExtractorsFactory(
        delegate = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE),
        config = setup.dolbyVision,
        stripDvRpu = setup.stripDvRpu,
        stripHdr10PlusSei = setup.stripHdr10PlusSei,
    )
    // With libass, the file is read the way a libass player reads it, into a handler of its own
    // that the player takes over at the switch.
    val assHandler = if (setup.useLibass) AssHandler() else null
    val (extractorsFactory, subtitleParserFactory) = if (assHandler != null) {
        libassPreloadParsingCompat(baseExtractorsFactory, assHandler)
    } else {
        baseExtractorsFactory to null
    }
    val factory = PlayerMediaSourceFactory(context.applicationContext).apply {
        bufferEngineEnabled = false
        vodCacheEnabled = false
        useParallelConnections = false
        nuvioPerformanceModeEnabled = mediaSourceFactory.nuvioPerformanceModeEnabled
        nativeEngineEnabled = mediaSourceFactory.nativeEngineEnabled
        configureSubtitleParsing(
            extractorsFactory = extractorsFactory,
            subtitleParserFactory = subtitleParserFactory,
        )
    }
    val inner = factory.createMediaSource(
        context = context,
        url = key.url,
        headers = key.headers,
        filename = key.filename,
        responseHeaders = key.responseHeaders,
        mimeTypeOverride = key.mimeType,
        audioDelayUsProvider = audioDelayUs::get,
        cacheKey = key.cacheKey,
    )

    val preload = NextEpisodePreload(
        fingerprint = NextEpisodePreloadFingerprint(key, setup),
        allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        memoryBefore = NextEpisodePreloadMemory.now(),
        rendererCount = rendererCapabilities.size,
        assHandler = assHandler,
    )
    val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context)
    val trackSelector = DefaultTrackSelector(context).apply { init({}, bandwidthMeter) }
    val control = object : PreloadMediaSource.PreloadControl {
        // The source is prepared once its timeline is known, before anything is read: go on to
        // prepare the period, which reads the header (tracks, seek index).
        override fun onSourcePrepared(mediaSource: PreloadMediaSource): Boolean = true

        // The period is prepared (header read) and tracks were picked for preloading: stop here,
        // no buffering until the player takes it.
        override fun onTracksSelected(mediaSource: PreloadMediaSource): Boolean {
            preload.preparedAtMs = SystemClock.elapsedRealtime()
            preload.memoryAtPrepared = NextEpisodePreloadMemory.now()
            preload.status = NextEpisodePreloadStatus.PREPARED
            preload.prepared.complete(true)
            return false
        }

        override fun onContinueLoadingRequested(mediaSource: PreloadMediaSource, bufferedPositionUs: Long): Boolean = false

        override fun onUsedByPlayer(mediaSource: PreloadMediaSource) {
            preload.usedByPlayer = true
        }

        override fun onPreloadError(error: PreloadException, mediaSource: PreloadMediaSource) {
            preload.error = error.message ?: error.javaClass.simpleName
            preload.status = NextEpisodePreloadStatus.FAILED
            preload.prepared.complete(false)
        }
    }
    preload.source = PreloadMediaSource.Factory(
        DefaultMediaSourceFactory(context),
        control,
        trackSelector,
        bandwidthMeter,
        rendererCapabilities,
        preload.allocator,
        NextEpisodePreloadThread.looper,
    ).createMediaSource(inner)
    nextEpisodePreload.pending = preload
    preload.source.preload(0L)

    try {
        val ok = withTimeoutOrNull(NEXT_EPISODE_PRELOAD_PREPARE_TIMEOUT_MS) { preload.prepared.await() }
        if (ok == null) {
            releasePendingNextEpisodePreload("timed out reading the header")
            android.util.Log.i(PlayerRuntimeController.TAG, "NEXT_EPISODE_PRELOAD: dropped (timed out reading the header)")
        } else if (!ok && nextEpisodePreload.pending === preload) {
            releasePendingNextEpisodePreload("error: ${preload.error}")
            android.util.Log.i(PlayerRuntimeController.TAG, "NEXT_EPISODE_PRELOAD: dropped (error: ${preload.error})")
        } else if (ok) {
            val runtime = Runtime.getRuntime()
            val heapPercent = (runtime.totalMemory() - runtime.freeMemory()) * 100L / runtime.maxMemory()
            android.util.Log.i(
                PlayerRuntimeController.TAG,
                "NEXT_EPISODE_PRELOAD: header read in ${preload.headerMs}ms (heap $heapPercent% of the app's limit)"
            )
        }
    } catch (e: CancellationException) {
        if (nextEpisodePreload.pending === preload) releasePendingNextEpisodePreload("prepare cancelled")
        throw e
    }
    return null
}

/** Drops a preload that is waiting for the switch. */
internal fun PlayerRuntimeController.releasePendingNextEpisodePreload(reason: String) {
    val preload = nextEpisodePreload.pending ?: return
    nextEpisodePreload.pending = null
    nextEpisodePreload.lastReleaseReason = reason
    preload.release()
}

/** Drops the preload the current player plays from, once that player is gone. */
internal fun PlayerRuntimeController.releaseInUseNextEpisodePreload() {
    val preload = nextEpisodePreload.inUse ?: return
    nextEpisodePreload.inUse = null
    preload.release()
}

/**
 * At an ExoPlayer start: hands over the waiting preload when it matches [actual] and the start is
 * at the beginning, else drops it. Records the decision either way.
 */
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.takeNextEpisodePreload(
    actual: NextEpisodePreloadFingerprint,
    resumePositionMs: Long,
): NextEpisodePreloadHit? {
    nextEpisodePreload.lastSetup = actual.setup
    val preload = nextEpisodePreload.pending
    if (preload == null) {
        nextEpisodePreload.lastDecision = "not used: " + (
            if (!nextEpisodePreloadEnabledSetting) "off"
            else nextEpisodePreload.lastReleaseReason?.let { "released ($it)" } ?: "none prepared"
        )
        nextEpisodePreload.lastReleaseReason = null
        return null
    }
    val mismatch = when {
        preload.status == NextEpisodePreloadStatus.FAILED -> "preload failed: ${preload.error}"
        else -> nextEpisodePreloadMismatch(preload.fingerprint, actual, resumePositionMs)
    }
    if (mismatch != null) {
        releasePendingNextEpisodePreload(mismatch)
        nextEpisodePreload.lastDecision = "not used: $mismatch"
        android.util.Log.i(PlayerRuntimeController.TAG, "NEXT_EPISODE_PRELOAD: not used ($mismatch)")
        nextEpisodePreload.lastReleaseReason = null
        return null
    }
    nextEpisodePreload.pending = null
    nextEpisodePreload.inUse = preload
    // The player about to be built takes the handler; it releases it with itself from here on.
    preload.assHandlerHandedOver = preload.assHandler != null
    nextEpisodePreload.lastDecision = "used (${preload.status.name.lowercase()})"
    android.util.Log.i(PlayerRuntimeController.TAG, "NEXT_EPISODE_PRELOAD: used (${preload.status.name.lowercase()})")
    return NextEpisodePreloadHit(
        preload = preload,
        loadControl = DefaultLoadControl.Builder()
            .setAllocator(preload.allocator)
            .setBackBuffer(1_500, /* retainBackBufferFromKeyframe = */ true)
            .build(),
        looper = NextEpisodePreloadThread.looper,
    )
}

/**
 * Checks a handed-over preload against the player just built for it: the renderer count must
 * match its track pick. Returns the source to play, or null after releasing it (the player then
 * opens the stream the usual way, on the preload's thread and allocator, which is harmless).
 */
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.confirmNextEpisodePreload(
    hit: NextEpisodePreloadHit?,
    playerRendererCount: Int,
): PreloadMediaSource? {
    hit ?: return null
    if (playerRendererCount == hit.preload.rendererCount) return hit.preload.source
    nextEpisodePreload.lastDecision =
        "not used: renderer count changed (${hit.preload.rendererCount} -> $playerRendererCount)"
    android.util.Log.i(PlayerRuntimeController.TAG, "NEXT_EPISODE_PRELOAD: ${nextEpisodePreload.lastDecision}")
    // The player already owns the preload's handler (it was built with it); it stays with the player.
    releaseInUseNextEpisodePreload()
    return null
}

/**
 * A renderer stand-in for the preload's track pick: it claims every format of its track type and
 * nothing else, and never touches a real renderer.
 */
@OptIn(UnstableApi::class)
internal fun nextEpisodePreloadStandInRenderer(trackType: Int): RendererCapabilities =
    object : RendererCapabilities {
        override fun getName(): String = "NextEpisodePreloadStandIn"

        override fun getTrackType(): Int = trackType

        override fun supportsFormat(format: Format): Int =
            if (MimeTypes.getTrackType(format.sampleMimeType) == trackType) {
                RendererCapabilities.create(C.FORMAT_HANDLED)
            } else {
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }

        override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }
