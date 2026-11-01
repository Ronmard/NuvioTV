package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerTimelineSegmentKind.INTRO
import com.nuvio.tv.ui.screens.player.PlayerTimelineSegmentKind.OUTRO
import com.nuvio.tv.ui.screens.player.PlayerTimelineSegmentKind.POST_CREDITS
import com.nuvio.tv.ui.screens.player.PlayerTimelineSegmentKind.RECAP
import com.nuvio.tv.data.repository.SkipInterval
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PlayerTimelineSegmentsTest {

    private fun interval(start: Double, end: Double, type: String) = SkipInterval(start, end, type, "introdb")

    private fun List<PlayerTimelineSegment>.ranges() = map { Triple(it.start, it.end, it.kind) }

    @Test
    fun mapsEachKindToFractionsOfTheDuration() {
        val segments = listOf(
            interval(0.0, 10.0, "recap"),
            interval(10.0, 30.0, "op"),
            interval(80.0, 100.0, "outro"),
        ).timelineSegments(durationMs = 100_000L)

        assertEquals(
            listOf(Triple(0f, 0.1f, RECAP), Triple(0.1f, 0.3f, INTRO), Triple(0.8f, 1f, OUTRO)),
            segments.ranges(),
        )
    }

    @Test
    fun clampsSegmentsThatRunPastTheDuration() {
        val segments = listOf(
            interval(90.0, 150.0, "ed"),
            interval(-5.0, 10.0, "intro"),
            interval(50.0, Double.MAX_VALUE, "recap"),
        ).timelineSegments(durationMs = 100_000L)

        assertEquals(
            listOf(Triple(0f, 0.1f, INTRO), Triple(0.5f, 0.9f, RECAP), Triple(0.9f, 1f, OUTRO)),
            segments.ranges(),
        )
    }

    @Test
    fun dropsEmptyUnknownAndOutOfRangeSegments() {
        val segments = listOf(
            interval(20.0, 20.0, "intro"),
            interval(30.0, 25.0, "intro"),
            interval(Double.NaN, 40.0, "recap"),
            interval(10.0, 20.0, "preview-of-something"),
            interval(120.0, 130.0, "outro"),
        ).timelineSegments(durationMs = 100_000L)

        assertTrue(segments.isEmpty())
    }

    @Test
    fun drawsNothingUntilTheDurationIsKnown() {
        assertTrue(listOf(interval(0.0, 10.0, "intro")).timelineSegments(durationMs = 0L).isEmpty())
    }

    @Test
    fun theLaterStartingSegmentWinsWhereTheyOverlap() {
        val segments = listOf(
            interval(60.0, 100.0, "movie-credits"),
            interval(80.0, 90.0, "post-credits"),
        ).timelineSegments(durationMs = 100_000L)

        assertEquals(
            listOf(Triple(0.6f, 0.8f, OUTRO), Triple(0.8f, 0.9f, POST_CREDITS), Triple(0.9f, 1f, OUTRO)),
            segments.ranges(),
        )
        assertEquals(segments[0].source, segments[2].source)
    }

    @Test
    fun overlappingSegmentsNeverStack() {
        val segments = listOf(
            interval(0.0, 40.0, "intro"),
            interval(20.0, 60.0, "recap"),
            interval(20.0, 60.0, "recap"),
        ).timelineSegments(durationMs = 100_000L)

        assertEquals(listOf(Triple(0f, 0.2f, INTRO), Triple(0.2f, 0.6f, RECAP)), segments.ranges())
        segments.zipWithNext { a, b -> assertTrue(a.end <= b.start) }
    }
}
