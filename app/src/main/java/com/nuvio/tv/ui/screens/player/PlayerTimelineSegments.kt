package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.ui.theme.NuvioTheme

/** The kinds of skip segment the seek bar marks, each in its own color. */
internal enum class PlayerTimelineSegmentKind { INTRO, RECAP, OUTRO, POST_CREDITS }

/** A skip segment as a band on the seek bar, in fractions of the duration; [source] is its interval's index. */
@Immutable
internal data class PlayerTimelineSegment(
    val start: Float,
    val end: Float,
    val kind: PlayerTimelineSegmentKind,
    val source: Int,
)

internal fun SkipInterval.timelineSegmentKind(): PlayerTimelineSegmentKind? =
    if (type.trim().equals("post-credits", ignoreCase = true)) {
        PlayerTimelineSegmentKind.POST_CREDITS
    } else {
        when (AutoSkipSegmentType.fromSkipIntervalType(type)) {
            AutoSkipSegmentType.INTRO -> PlayerTimelineSegmentKind.INTRO
            AutoSkipSegmentType.RECAP -> PlayerTimelineSegmentKind.RECAP
            AutoSkipSegmentType.OUTRO, AutoSkipSegmentType.MOVIE_CREDITS -> PlayerTimelineSegmentKind.OUTRO
            null -> null
        }
    }

/**
 * The bands for these intervals, sorted and clamped to the duration (an open end, like animeskip's
 * last one, runs to it); none until the duration is known. Empty or unknown ones are left out.
 */
internal fun List<SkipInterval>.timelineSegments(durationMs: Long): List<PlayerTimelineSegment> {
    if (isEmpty() || durationMs <= 0L) return emptyList()
    val durationSec = durationMs / 1000.0
    return mapIndexedNotNull { index, interval ->
        val kind = interval.timelineSegmentKind() ?: return@mapIndexedNotNull null
        if (interval.startTime.isNaN() || interval.endTime.isNaN()) return@mapIndexedNotNull null
        val start = (interval.startTime / durationSec).coerceIn(0.0, 1.0).toFloat()
        val end = (interval.endTime / durationSec).coerceIn(0.0, 1.0).toFloat()
        if (end > start) PlayerTimelineSegment(start, end, kind, index) else null
    }.withoutOverlaps()
}

/**
 * Splits overlapping bands so each point of the bar shows one, never two blended: where they
 * overlap the later-starting segment wins (a post-credits scene inside the credits), and on a tie
 * the shorter one, then the first.
 */
private fun List<PlayerTimelineSegment>.withoutOverlaps(): List<PlayerTimelineSegment> {
    if (size < 2) return sortedBy { it.start }
    val bounds = flatMap { listOf(it.start, it.end) }.distinct().sorted()
    val pieces = mutableListOf<PlayerTimelineSegment>()
    for (i in 0 until bounds.lastIndex) {
        val from = bounds[i]
        val to = bounds[i + 1]
        val top = filter { it.start <= from && it.end >= to }
            .maxWithOrNull(compareBy<PlayerTimelineSegment> { it.start }.thenByDescending { it.end })
            ?: continue
        val last = pieces.lastOrNull()
        if (last != null && last.source == top.source && last.end == from) {
            pieces[pieces.lastIndex] = last.copy(end = to)
        } else {
            pieces += top.copy(start = from, end = to)
        }
    }
    return pieces
}

@Immutable
internal class PlayerTimelineSegmentColors(
    private val intro: Color,
    private val recap: Color,
    private val outro: Color,
    private val postCredits: Color,
) {
    fun of(kind: PlayerTimelineSegmentKind): Color = when (kind) {
        PlayerTimelineSegmentKind.INTRO -> intro
        PlayerTimelineSegmentKind.RECAP -> recap
        PlayerTimelineSegmentKind.OUTRO -> outro
        PlayerTimelineSegmentKind.POST_CREDITS -> postCredits
    }
}

@Composable
internal fun rememberPlayerTimelineSegmentColors(): PlayerTimelineSegmentColors {
    val colors = NuvioTheme.colors
    return remember(colors.Secondary, colors.SecondaryVariant, colors.FocusRing) {
        PlayerTimelineSegmentColors(
            intro = colors.Secondary,
            recap = colors.SecondaryVariant,
            outro = colors.FocusRing,
            postCredits = Color.White,
        )
    }
}

private const val PlayerTimelineSegmentAlpha = 0.45f

private const val PlayerTimelineCurrentSegmentAlpha = 0.8f

/**
 * Draws [segments] across the whole track, with the one under [positionFraction] stronger. The
 * caller clips the track to its shape; draw the bands under the progress fill, so the played
 * part stays the plain fill.
 */
internal fun DrawScope.drawTimelineSegments(
    segments: List<PlayerTimelineSegment>,
    colors: PlayerTimelineSegmentColors,
    positionFraction: Float,
) {
    if (segments.isEmpty()) return
    val current = segments.firstOrNull { positionFraction >= it.start && positionFraction < it.end }?.source
    for (segment in segments) {
        val left = size.width * segment.start
        val right = size.width * segment.end
        if (right <= left) continue
        drawRect(
            color = colors.of(segment.kind),
            topLeft = Offset(left, 0f),
            size = Size(right - left, size.height),
            alpha = if (segment.source == current) PlayerTimelineCurrentSegmentAlpha else PlayerTimelineSegmentAlpha,
        )
    }
}
