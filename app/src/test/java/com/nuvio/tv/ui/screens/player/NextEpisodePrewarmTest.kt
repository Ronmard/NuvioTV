package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePrewarmTest {

    private val ua = "TestAgent/1"

    // ---- headers ----

    @Test
    fun `prewarm asks for the first byte only, uncompressed, with the default user agent`() {
        val headers = nextEpisodePrewarmHeaders(emptyMap(), ua)
        assertEquals("bytes=0-0", headers["Range"])
        assertEquals("identity", headers["Accept-Encoding"])
        assertEquals(ua, headers["User-Agent"])
    }

    @Test
    fun `the stream's own headers are kept and its user agent wins`() {
        val headers = nextEpisodePrewarmHeaders(
            mapOf("user-agent" to "StreamAgent", "Referer" to "https://example.org/"),
            ua,
        )
        assertEquals("StreamAgent", headers["user-agent"])
        assertFalse(headers.containsKey("User-Agent"))
        assertEquals("https://example.org/", headers["Referer"])
    }

    @Test
    fun `a range or encoding from the stream is replaced`() {
        val headers = nextEpisodePrewarmHeaders(mapOf("range" to "bytes=100-", "accept-encoding" to "gzip"), ua)
        assertFalse(headers.containsKey("range"))
        assertFalse(headers.containsKey("accept-encoding"))
        assertEquals("bytes=0-0", headers["Range"])
        assertEquals("identity", headers["Accept-Encoding"])
    }

    // ---- which links are prewarmed ----

    @Test
    fun `only remote web links are prewarmed`() {
        assertTrue(isNextEpisodePrewarmableUrl("https://debrid.example/dl/abc/file.mkv"))
        assertTrue(isNextEpisodePrewarmableUrl("http://debrid.example/dl/abc/file.mkv"))
        assertFalse(isNextEpisodePrewarmableUrl(null))
        assertFalse(isNextEpisodePrewarmableUrl("magnet:?xt=urn:btih:abc"))
        assertFalse(isNextEpisodePrewarmableUrl("http://127.0.0.1:8090/stream/1"))
        assertFalse(isNextEpisodePrewarmableUrl("http://localhost:8090/stream/1"))
        assertFalse(isNextEpisodePrewarmableUrl("file:///sdcard/a.mkv"))
    }

    // ---- final link decision ----

    private val original = "https://aio.example/api/v1/debrid/playback/token/Show%20-%2002.mkv"
    private val redirected = "https://cdn.debrid.example/d/XYZ/Show%20-%2002.mkv"
    private val mkvByExtension: (String, String?) -> String? = { url, filename ->
        val name = filename ?: url.substringBefore('?')
        when {
            name.endsWith(".mkv") -> "video/x-matroska"
            name.endsWith(".mp4") -> "video/mp4"
            else -> null
        }
    }

    private fun decide(
        finalUrl: String? = redirected,
        status: Int = 206,
        headers: Map<String, String> = emptyMap(),
        filename: String? = null,
        originalUrl: String = original,
    ) = decidePrewarmedUrl(originalUrl, finalUrl, status, headers, filename, mkvByExtension)

    @Test
    fun `a clean redirect to the same file is used`() {
        assertEquals(PrewarmedUrlDecision.UseFinal(redirected), decide())
    }

    @Test
    fun `no redirect keeps the original`() {
        assertEquals(PrewarmedUrlDecision.KeepOriginal("no redirect"), decide(finalUrl = original))
        assertEquals(PrewarmedUrlDecision.KeepOriginal("no redirect"), decide(finalUrl = null))
    }

    @Test
    fun `an error status keeps the original`() {
        assertEquals(PrewarmedUrlDecision.KeepOriginal("status 403"), decide(status = 403))
    }

    @Test
    fun `a downgrade to http keeps the original`() {
        val decision = decide(finalUrl = "http://cdn.debrid.example/d/XYZ/Show%20-%2002.mkv")
        assertEquals(PrewarmedUrlDecision.KeepOriginal("https to http"), decision)
    }

    @Test
    fun `http to https is fine`() {
        val decision = decide(originalUrl = "http://aio.example/p/Show%20-%2002.mkv")
        assertEquals(PrewarmedUrlDecision.UseFinal(redirected), decision)
    }

    @Test
    fun `a redirect to a local server keeps the original`() {
        val decision = decide(finalUrl = "http://127.0.0.1:9000/Show%20-%2002.mkv")
        assertTrue(decision is PrewarmedUrlDecision.KeepOriginal)
    }

    @Test
    fun `a stream with its own headers keeps the original, a user agent alone does not`() {
        assertEquals(
            PrewarmedUrlDecision.KeepOriginal("stream sends its own headers"),
            decide(headers = mapOf("Authorization" to "Basic abc")),
        )
        assertEquals(PrewarmedUrlDecision.UseFinal(redirected), decide(headers = mapOf("User-Agent" to "X")))
    }

    @Test
    fun `a different file name in the link keeps the original unless the stream names the file`() {
        val other = "https://cdn.debrid.example/d/XYZ/download.mkv"
        assertEquals(PrewarmedUrlDecision.KeepOriginal("file name differs"), decide(finalUrl = other))
        assertEquals(PrewarmedUrlDecision.UseFinal(other), decide(finalUrl = other, filename = "Show - 02.mkv"))
    }

    @Test
    fun `a different container type keeps the original`() {
        val original = "https://aio.example/p/token"
        val final = "https://cdn.debrid.example/d/XYZ/Show.mp4"
        // No file name in either link, so only the container type can tell them apart.
        val decision = decidePrewarmedUrl(original, final, 200, emptyMap(), "Show - 02") { url, _ ->
            if (url.endsWith(".mp4")) "video/mp4" else null
        }
        assertEquals(PrewarmedUrlDecision.KeepOriginal("container type differs"), decision)
    }

    // ---- prepared stream ----

    private fun stream(url: String) = Stream(
        name = "1080p",
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "AIO",
        addonLogo = null,
    )

    @Test
    fun `withPrewarmedUrl swaps the link only when there is one`() {
        val s = stream(original)
        assertSame(s, s.withPrewarmedUrl(null))
        assertSame(s, s.withPrewarmedUrl(original))
        assertEquals(redirected, s.withPrewarmedUrl(redirected).url)
        assertEquals("1080p", s.withPrewarmedUrl(redirected).name)
    }

    private var clockMs = 1_000_000L

    private fun prepared(prewarm: NextEpisodePrewarmResult?) = PreparedNextEpisode(
        videoId = "tt1:1:2",
        season = 1,
        episode = 2,
        stream = stream(original),
        sourceStream = stream("https://aio.example/source"),
        prewarm = prewarm,
        now = { clockMs },
    )

    private fun warmed(usable: String?) = NextEpisodePrewarmResult(
        outcome = NextEpisodePrewarmOutcome.Warmed(206, redirected, 1),
        elapsedMs = 1200L,
        usableFinalUrl = usable,
        keptOriginalReason = if (usable == null) "stream sends its own headers" else null,
    )

    @Test
    fun `a fresh preparation plays on the usable prewarmed link`() = runTest {
        val result = prepared(warmed(redirected)).playableStream { error("not expired") }
        assertEquals(redirected, result?.url)
    }

    @Test
    fun `a fresh preparation without a usable link plays on the original`() = runTest {
        assertEquals(original, prepared(warmed(null)).playableStream { error("not expired") }?.url)
        assertEquals(original, prepared(null).playableStream { error("not expired") }?.url)
    }

    @Test
    fun `an expired preparation drops the prewarmed link and resolves again`() = runTest {
        val p = prepared(warmed(redirected))
        clockMs += PREPARED_STREAM_MAX_AGE_MS
        val reResolved = stream("https://aio.example/new")
        var resolvedFrom: Stream? = null
        val result = p.playableStream { resolvedFrom = it; reResolved }
        assertEquals("https://aio.example/source", resolvedFrom?.url)
        assertSame(reResolved, result)
        assertNull(result?.url?.takeIf { it == redirected })
    }
}
