package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PreparedNextEpisodeTest {

    private val playable = mockk<Stream>(relaxed = true)
    private val source = mockk<Stream>(relaxed = true)
    private val reResolved = mockk<Stream>(relaxed = true)

    private var clockMs = 1_000_000L

    private fun prepared() = PreparedNextEpisode(
        videoId = "tt1:1:2",
        season = 1,
        episode = 2,
        stream = playable,
        sourceStream = source,
        now = { clockMs },
    )

    @Test
    fun `a fresh preparation is used as is`() = runTest {
        val prepared = prepared()
        clockMs += PREPARED_STREAM_MAX_AGE_MS - 1
        var resolveCalls = 0
        val stream = prepared.playableStream { resolveCalls++; reResolved }
        assertSame(playable, stream)
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `a preparation older than 15 minutes is resolved again from the selector's pick`() = runTest {
        val prepared = prepared()
        clockMs += PREPARED_STREAM_MAX_AGE_MS
        var resolvedFrom: Stream? = null
        val stream = prepared.playableStream { resolvedFrom = it; reResolved }
        assertSame(source, resolvedFrom)
        assertSame(reResolved, stream)
    }

    @Test
    fun `a failed re-resolve gives no stream`() = runTest {
        val prepared = prepared()
        clockMs += PREPARED_STREAM_MAX_AGE_MS + 60_000L
        assertNull(prepared.playableStream { null })
    }
}
