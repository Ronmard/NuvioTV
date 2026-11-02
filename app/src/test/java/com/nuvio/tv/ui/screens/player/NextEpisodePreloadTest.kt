package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEpisodePreloadTest {

    private val setup = NextEpisodePreloadSetup(
        dolbyVision = DolbyVisionConversionConfig(active = false),
        stripDvRpu = false,
        stripHdr10PlusSei = false,
        useLibass = false,
        autoSyncEnabled = false,
        stockLoadControl = true,
        vodCacheEnabled = false,
        parallelConnections = false,
        startupSubtitles = false,
    )

    private val key = NextEpisodePreloadKey(
        url = "https://cdn.example/d/XYZ/Show.S01E05.mkv",
        headers = emptyMap(),
        mimeType = "video/x-matroska",
        filename = "Show.S01E05.mkv",
        cacheKey = null,
        responseHeaders = emptyMap(),
    )

    private val preloaded = NextEpisodePreloadFingerprint(key, setup)

    private fun mismatch(actual: NextEpisodePreloadFingerprint, resumeMs: Long = 0L) =
        nextEpisodePreloadMismatch(preloaded, actual, resumeMs)

    // ---- unsupported setups ----

    @Test
    fun `the plain ExoPlayer setup can be preloaded`() {
        assertNull(setup.unsupportedReason())
    }

    @Test
    fun `libass can be preloaded`() {
        assertNull(setup.copy(useLibass = true).unsupportedReason())
    }

    @Test
    fun `autosync, custom buffers, vod cache, parallel and startup subtitles cannot`() {
        assertEquals("subtitle autosync on", setup.copy(autoSyncEnabled = true).unsupportedReason())
        assertEquals("custom buffer engine", setup.copy(stockLoadControl = false).unsupportedReason())
        assertEquals("vod cache on", setup.copy(vodCacheEnabled = true).unsupportedReason())
        assertEquals("parallel connections on", setup.copy(parallelConnections = true).unsupportedReason())
        assertEquals("startup subtitles attached", setup.copy(startupSubtitles = true).unsupportedReason())
    }

    @Test
    fun `autosync is reported first when several apply`() {
        assertEquals("subtitle autosync on", setup.copy(autoSyncEnabled = true, vodCacheEnabled = true).unsupportedReason())
    }

    // ---- match at the switch ----

    @Test
    fun `the same stream and setup at the beginning is used`() {
        assertNull(mismatch(preloaded))
        assertNull(mismatch(preloaded.copy()))
    }

    @Test
    fun `a change in the stream rules the preload out`() {
        assertEquals("link changed", mismatch(preloaded.copy(key = key.copy(url = "https://cdn.example/other.mkv"))))
        assertEquals("headers changed", mismatch(preloaded.copy(key = key.copy(headers = mapOf("Referer" to "r")))))
        assertEquals(
            "container type changed (video/x-matroska -> video/mp4)",
            mismatch(preloaded.copy(key = key.copy(mimeType = "video/mp4"))),
        )
        assertEquals("file name changed", mismatch(preloaded.copy(key = key.copy(filename = "other.mkv"))))
        assertEquals("cache key changed", mismatch(preloaded.copy(key = key.copy(cacheKey = "abc:1"))))
        assertEquals(
            "response headers changed",
            mismatch(preloaded.copy(key = key.copy(responseHeaders = mapOf("Content-Type" to "video/mp4")))),
        )
    }

    @Test
    fun `a change in the Dolby Vision or HDR handling rules it out`() {
        val dv = setup.copy(dolbyVision = DolbyVisionConversionConfig(active = true, forcedMode = 1))
        assertEquals("dolby vision setup changed", mismatch(preloaded.copy(setup = dv)))
        assertEquals("hdr stripping changed", mismatch(preloaded.copy(setup = setup.copy(stripDvRpu = true))))
        assertEquals("hdr stripping changed", mismatch(preloaded.copy(setup = setup.copy(stripHdr10PlusSei = true))))
    }

    @Test
    fun `an unsupported setup at the switch is reported as such`() {
        assertEquals("vod cache on", mismatch(preloaded.copy(setup = setup.copy(vodCacheEnabled = true))))
    }

    @Test
    fun `libass turned on or off between the preload and the switch rules it out`() {
        assertEquals("libass turned on", mismatch(preloaded.copy(setup = setup.copy(useLibass = true))))
        val libassPreload = NextEpisodePreloadFingerprint(key, setup.copy(useLibass = true))
        assertEquals("libass turned off", nextEpisodePreloadMismatch(libassPreload, preloaded, 0L))
        assertNull(nextEpisodePreloadMismatch(libassPreload, libassPreload.copy(), 0L))
    }

    @Test
    fun `a resume past the beginning rules it out`() {
        assertEquals("starts at 95s, not the beginning", mismatch(preloaded, resumeMs = 95_400L))
    }

    @Test
    fun `the stream is checked before the resume position`() {
        assertEquals("link changed", mismatch(preloaded.copy(key = key.copy(url = "https://x/y.mkv")), resumeMs = 5_000L))
    }

    // ---- renderer stand-ins ----

    @Test
    fun `a stand-in claims its own track type only`() {
        val video = nextEpisodePreloadStandInRenderer(androidx.media3.common.C.TRACK_TYPE_VIDEO)
        val hevc = androidx.media3.common.Format.Builder().setSampleMimeType("video/hevc").build()
        val opus = androidx.media3.common.Format.Builder().setSampleMimeType("audio/opus").build()
        assertEquals(androidx.media3.common.C.TRACK_TYPE_VIDEO, video.trackType)
        assertEquals(
            androidx.media3.common.C.FORMAT_HANDLED,
            androidx.media3.exoplayer.RendererCapabilities.getFormatSupport(video.supportsFormat(hevc)),
        )
        assertEquals(
            androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE,
            androidx.media3.exoplayer.RendererCapabilities.getFormatSupport(video.supportsFormat(opus)),
        )
    }

    // ---- memory guard ----

    private val mb = 1_048_576L

    @Test
    fun `a low-RAM device skips the preload`() {
        assertEquals("low-RAM device", nextEpisodePreloadMemorySkipReason(10 * mb, 384 * mb, isLowRamDevice = true))
    }

    @Test
    fun `memory over 60 percent of the limit skips the preload`() {
        assertEquals(
            "memory at 62% of the app's limit (over 60%)",
            nextEpisodePreloadMemorySkipReason(240 * mb, 384 * mb, isLowRamDevice = false),
        )
    }

    @Test
    fun `memory at or under 60 percent lets the preload run`() {
        // The box at its busiest (4K Dolby Vision, about 215 MB of 384 MB).
        assertNull(nextEpisodePreloadMemorySkipReason(215 * mb, 384 * mb, isLowRamDevice = false))
        assertNull(nextEpisodePreloadMemorySkipReason((384 * mb * 6) / 10, 384 * mb, isLowRamDevice = false))
    }

    @Test
    fun `an unknown limit does not skip the preload`() {
        assertNull(nextEpisodePreloadMemorySkipReason(900 * mb, Long.MAX_VALUE, isLowRamDevice = false))
        assertNull(nextEpisodePreloadMemorySkipReason(900 * mb, 0L, isLowRamDevice = false))
    }
}

