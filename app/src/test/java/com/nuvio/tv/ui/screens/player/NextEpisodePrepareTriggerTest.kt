package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePrepareTriggerTest {

    private val trigger = 70_000L

    /** Feeds [positions] one sample at a time; returns the actions and the final state. */
    private fun run(
        vararg positions: Long,
        start: PrepareTriggerState = PrepareTriggerState(),
    ): Pair<List<PrepareTriggerAction>, PrepareTriggerState> {
        var state = start
        val actions = positions.map { position ->
            val (next, action) = evaluatePrepareTrigger(state, position, trigger)
            state = next
            action
        }
        return actions to state
    }

    private fun List<PrepareTriggerAction>.fires() = filterIsInstance<PrepareTriggerAction.Fire>()

    @Test
    fun `fires once when playback crosses the mark`() {
        val (actions, state) = run(69_000, 69_500, 70_000, 70_500, 71_000)
        assertEquals(listOf("playback crossed"), actions.fires().map { it.cause })
        assertEquals(PrepareTriggerAction.Fire("playback crossed"), actions[2])
        assertTrue(state.fired)
    }

    @Test
    fun `does not fire before the mark`() {
        val (actions, state) = run(10_000, 10_500, 69_500)
        assertTrue(actions.fires().isEmpty())
        assertFalse(state.fired)
    }

    @Test
    fun `fires when a seek lands past the mark`() {
        val (actions, _) = run(20_000, 20_500, 80_000)
        assertEquals(listOf("seek landed past the mark"), actions.fires().map { it.cause })
    }

    @Test
    fun `a resume past the mark waits for playback to settle`() {
        // The first sample (the resume position) only records; then 10 s of playback.
        val samples = (0..22).map { 80_000L + it * 500L }.toLongArray()
        val (actions, _) = run(*samples)
        val fireIndex = actions.indexOfFirst { it is PrepareTriggerAction.Fire }
        assertEquals(PrepareTriggerAction.Fire("settled after resume"), actions[fireIndex])
        assertEquals(80_000L + PREPARE_SETTLE_MS, samples[fireIndex])
    }

    @Test
    fun `a seek back below the mark rearms it`() {
        val (actions, state) = run(69_500, 70_000, 30_000)
        assertEquals(PrepareTriggerAction.Rearm, actions.last())
        assertFalse(state.fired)
        // Crossing again fires again.
        val (again, _) = run(69_500, 70_000, start = state)
        assertEquals(1, again.fires().size)
    }

    @Test
    fun `a seek back below the mark before it fired changes nothing`() {
        val (actions, _) = run(50_000, 20_000)
        assertTrue(actions.all { it == PrepareTriggerAction.None })
    }

    @Test
    fun `a pause does not count as a seek`() {
        // Same position repeated (paused), then playback crosses.
        val (actions, _) = run(69_900, 69_900, 69_900, 70_100)
        assertEquals(listOf("playback crossed"), actions.fires().map { it.cause })
    }

    @Test
    fun `a jump forward within the mark region after firing does not fire again`() {
        val (actions, _) = run(69_500, 70_000, 90_000, 95_000)
        assertEquals(1, actions.fires().size)
    }
}
