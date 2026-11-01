package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The next-episode source search's waiting, on virtual time. [FakeSearch] stands in for the addon
 * collector: streams "arrive" at set times; once the timeout has elapsed any arrival is picked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextEpisodeSearchWaitTest {

    private class FakeSearch {
        val settled = CompletableDeferred<Unit>()
        var arrived = false
        var selected = false
        var timeoutElapsed = false
        var keepWaiting = true

        /** A stream that passes the filters arrives (the collector picks it once the timeout is over). */
        fun arrive(pickNow: Boolean = timeoutElapsed) {
            arrived = true
            if (pickNow) pick()
        }

        /** Every addon has answered. */
        fun allAnswered() {
            if (arrived) selected = true
            settled.complete(Unit)
        }

        fun pick() {
            selected = true
            settled.complete(Unit)
        }

        suspend fun await(timeoutSeconds: Int, hardTimeoutMs: Long = NEXT_EPISODE_HARD_TIMEOUT_MS) =
            awaitNextEpisodeSelection(
                timeoutSeconds = timeoutSeconds,
                settled = settled,
                isSelected = { selected },
                hasArrived = { arrived },
                onTimeoutElapsed = { timeoutElapsed = true },
                selectFromArrived = { if (arrived) pick() },
                keepWaitingAfterTimeout = { keepWaiting },
                hardTimeoutMs = hardTimeoutMs,
            )
    }

    private fun TestScope.at(ms: Long, block: () -> Unit) {
        launch {
            delay(ms)
            block()
        }
    }

    @Test
    fun `a stream picked before the timeout ends the wait at once`() = runTest {
        val search = FakeSearch()
        at(2_000) { search.pick() }
        search.await(timeoutSeconds = 5)
        assertTrue(search.selected)
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun `at the timeout it picks from the streams that have arrived`() = runTest {
        val search = FakeSearch()
        at(1_000) { search.arrive(pickNow = false) }
        search.await(timeoutSeconds = 5)
        assertTrue(search.selected)
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `with nothing at the timeout it waits for the addons still answering`() = runTest {
        val search = FakeSearch()
        at(12_000) { search.arrive() }
        search.await(timeoutSeconds = 3)
        assertTrue(search.selected)
        assertEquals(12_000L, currentTime)
    }

    @Test
    fun `a wait past the timeout ends when every addon has answered with nothing`() = runTest {
        val search = FakeSearch()
        at(9_000) { search.allAnswered() }
        search.await(timeoutSeconds = 3)
        assertFalse(search.selected)
        assertEquals(9_000L, currentTime)
    }

    @Test
    fun `the wait ends when the episode ends`() = runTest {
        val search = FakeSearch()
        at(20_000) { search.keepWaiting = false }
        search.await(timeoutSeconds = 3)
        assertFalse(search.selected)
        assertTrue(currentTime in 20_000L..20_000L + NEXT_EPISODE_KEEP_WAITING_POLL_MS)
    }

    @Test
    fun `the wait past the timeout stops at the hard cap`() = runTest {
        val search = FakeSearch()
        search.await(timeoutSeconds = 3, hardTimeoutMs = 60_000L)
        assertFalse(search.selected)
        assertEquals(3_000L + 60_000L, currentTime)
    }

    @Test
    fun `without waiting past the timeout it gives nothing one more timeout as before`() = runTest {
        val search = FakeSearch()
        search.keepWaiting = false
        search.await(timeoutSeconds = 5)
        assertFalse(search.selected)
        assertEquals(10_000L, currentTime)
    }

    @Test
    fun `without waiting past the timeout an arrival in the extra timeout is picked`() = runTest {
        val search = FakeSearch()
        search.keepWaiting = false
        at(7_000) { search.arrive() }
        search.await(timeoutSeconds = 5)
        assertTrue(search.selected)
        assertEquals(7_000L, currentTime)
    }

    @Test
    fun `an instant timeout takes the first arrival`() = runTest {
        val search = FakeSearch()
        at(1_500) { search.arrive() }
        search.await(timeoutSeconds = 0)
        assertTrue(search.timeoutElapsed)
        assertTrue(search.selected)
        assertEquals(1_500L, currentTime)
    }

    @Test
    fun `an unlimited timeout waits for every addon`() = runTest {
        val search = FakeSearch()
        at(1_000) { search.arrived = true }
        at(40_000) { search.allAnswered() }
        search.await(timeoutSeconds = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED)
        assertTrue(search.selected)
        assertEquals(40_000L, currentTime)
    }
}
