package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.Video
import java.time.Clock
import java.time.LocalDate

object PlayerNextEpisodeRules {
    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int?,
        currentEpisode: Int
    ): Video? {
        // Absolute-numbered content (e.g. some anime via Kitsu) carries no season; order by episode
        // number alone and advance to the next one.
        if (currentSeason == null) {
            val sorted = videos
                .filter { it.episode != null }
                .sortedWith(compareBy<Video>({ it.season ?: 0 }, { it.episode ?: 0 }))
            val index = sorted.indexOfFirst { it.episode == currentEpisode }
            return if (index < 0) null else sorted.getOrNull(index + 1)
        }

        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null

        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        // A duration below the current position is not a valid end-of-video signal.
        if (durationMs > 0L && positionMs > durationMs + END_OF_VIDEO_EPSILON_MS) return false

        val outroSegments = skipIntervals.filter { it.type in OUTRO_SEGMENT_TYPES }

        if (outroSegments.isNotEmpty()) {
            if (durationMs <= 0L) return false

            // Use the same post-credits detection as the skip-credits button so
            // the next-episode card never appears over a post-credits scene.
            val latestOutro = outroSegments.maxByOrNull { it.endTime }
            val postCreditsScene = latestOutro?.followingPostCreditsScene(skipIntervals, durationMs)

            if (postCreditsScene != null) {
                val sceneEndMs = (postCreditsScene.endTime * 1_000.0).toLong()
                    .coerceAtMost(durationMs)
                val userTriggerMs = userThresholdPositionMs(
                    durationMs, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
                )
                val isHeuristic = postCreditsScene.provider == "heuristic"
                val sceneDurationMs = sceneEndMs - (postCreditsScene.startTime * 1_000.0).toLong()
                val userThresholdFromEndMs = durationMs - userTriggerMs
                return if (isHeuristic && sceneDurationMs < userThresholdFromEndMs) {
                    // Short heuristic scene (shorter than user threshold window):
                    // card is not needed - autoplay will handle it at playback end.
                    false
                } else if (isHeuristic) {
                    positionMs >= userTriggerMs
                } else {
                    positionMs >= maxOf(sceneEndMs, userTriggerMs)
                }
            }

            val latestOutroEndMs = (outroSegments.maxOf { it.endTime } * 1_000.0).toLong()
            val postOutroGapMs = durationMs - latestOutroEndMs

            // Calculate the user's configured threshold as milliseconds from end.
            val userThresholdMs = when (thresholdMode) {
                NextEpisodeThresholdMode.PERCENTAGE -> {
                    val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                    ((1.0 - clampedPercent / 100.0) * durationMs).toLong()
                }
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                    val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                    (clampedMinutes * 60_000f).toLong()
                }
            }

            return if (postOutroGapMs > userThresholdMs) {
                when (thresholdMode) {
                    NextEpisodeThresholdMode.PERCENTAGE -> {
                        val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                        (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
                    }
                    NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                        val remainingMs = durationMs - positionMs
                        remainingMs <= (clampedMinutes * 60_000f).toLong()
                    }
                }
            } else {
                // Outro ends close to the file end — fire at earliest outro start.
                positionMs / 1_000.0 >= outroSegments.minOf { it.startTime }
            }
        }

        // Fallback to the settings threshold when no outro data exists.
        if (durationMs <= 0L) return false
        return when (thresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> {
                val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
            }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                val remainingMs = durationMs - positionMs
                remainingMs <= (clampedMinutes * 60_000f).toLong()
            }
        }
    }

    /**
     * Whether the next episode may start on its own at [positionMs]: at the end of the episode, or
     * from the earliest outro segment's start on where the card rule agrees (the card rule holds
     * back over a post-credits scene). Without outro data only the end qualifies, so the card's
     * threshold just shows the card, for a manual Next.
     */
    fun isNextEpisodeAutoStartPoint(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        if (durationMs <= 0L) return false
        if (positionMs >= durationMs - END_AUTO_START_WINDOW_MS) return true
        val outroStartMs = skipIntervals
            .filter { it.type in OUTRO_SEGMENT_TYPES }
            .minOfOrNull { (it.startTime * 1_000.0).toLong() }
            ?: return false
        return positionMs >= outroStartMs &&
            shouldShowNextEpisodeCard(
                positionMs = positionMs,
                durationMs = durationMs,
                skipIntervals = skipIntervals,
                thresholdMode = thresholdMode,
                thresholdPercent = thresholdPercent,
                thresholdMinutesBeforeEnd = thresholdMinutesBeforeEnd
            )
    }

    /**
     * The first position where [isNextEpisodeAutoStartPoint] holds: the outro start (or, when the
     * card rule holds back over a post-credits scene, the first position past it where the rule
     * agrees), else the end window. The card rule only ever asks "position >= some point", so a
     * binary search finds it. Null while the duration is unknown.
     */
    fun nextEpisodeAutoStartPositionMs(
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Long? {
        if (durationMs <= 0L) return null
        val endStartMs = (durationMs - END_AUTO_START_WINDOW_MS).coerceAtLeast(0L)
        val outroStartMs = skipIntervals
            .filter { it.type in OUTRO_SEGMENT_TYPES }
            .minOfOrNull { (it.startTime * 1_000.0).toLong() }
            ?.coerceIn(0L, endStartMs)
            ?: return endStartMs
        fun cardRuleAt(positionMs: Long) = shouldShowNextEpisodeCard(
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = thresholdMode,
            thresholdPercent = thresholdPercent,
            thresholdMinutesBeforeEnd = thresholdMinutesBeforeEnd
        )
        var low = outroStartMs
        if (cardRuleAt(low)) return low
        var high = endStartMs
        if (!cardRuleAt(high)) return endStartMs
        while (high - low > AUTO_START_SEARCH_PRECISION_MS) {
            val mid = (low + high) / 2
            if (cardRuleAt(mid)) high = mid else low = mid
        }
        return high
    }

    /**
     * Whether skipping an outro should start the next episode instead of jumping: the skip lands
     * within [OUTRO_SKIP_TO_NEXT_EPISODE_WINDOW_MS] of [autoStartMs] (or past it), so the jump and
     * its rebuffer would only lead to the switch a moment later. Content after the outro counts
     * only through the auto-start point: a post-credits scene or preview it waits for leaves more
     * than the window to watch, and keeps the jump.
     */
    fun outroSkipStartsNextEpisode(
        intervalType: String,
        skipTargetMs: Long,
        autoStartMs: Long?,
    ): Boolean {
        if (intervalType !in OUTRO_SEGMENT_TYPES || autoStartMs == null) return false
        return skipTargetMs >= autoStartMs - OUTRO_SKIP_TO_NEXT_EPISODE_WINDOW_MS
    }

    /**
     * The countdown on the card before a prepared next episode starts: [totalMs] long, ending at
     * [autoStartMs]. Null outside that window, or when the countdown is off.
     */
    fun leadCountdown(positionMs: Long, autoStartMs: Long, totalMs: Long): NextEpisodeLeadCountdown? {
        if (totalMs <= 0L) return null
        val remainingMs = autoStartMs - positionMs
        if (remainingMs <= 0L || remainingMs > totalMs) return null
        return NextEpisodeLeadCountdown(remainingMs = remainingMs, totalMs = totalMs)
    }

    /** True when a reading is clearly before the end and outside the next-episode window. */
    fun isAwayFromEnd(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean =
        durationMs > 0L &&
            positionMs < durationMs - NEAR_END_MS &&
            !shouldShowNextEpisodeCard(
                positionMs = positionMs,
                durationMs = durationMs,
                skipIntervals = skipIntervals,
                thresholdMode = thresholdMode,
                thresholdPercent = thresholdPercent,
                thresholdMinutesBeforeEnd = thresholdMinutesBeforeEnd
            )

    fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
        return parseEpisodeReleaseLocalDate(raw)
    }

    fun hasEpisodeAired(raw: String?, clock: Clock = Clock.systemDefaultZone()): Boolean {
        return isEpisodeReleaseAired(raw, clock) ?: true
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")

    const val POST_OUTRO_AUTOPLAY_GAP_MS = 5_000L

    const val END_OF_VIDEO_EPSILON_MS = 1_000L

    /** How close to the duration counts as the end for starting the next episode on its own. */
    const val END_AUTO_START_WINDOW_MS = 1_000L

    /** An outro skip landing this close before the auto-start point starts the next episode. */
    const val OUTRO_SKIP_TO_NEXT_EPISODE_WINDOW_MS = 10_000L

    /** Precision of the auto-start position search; finer than one position update. */
    const val AUTO_START_SEARCH_PRECISION_MS = 100L

    /** How close to the duration MPV treats as the end of the file. */
    const val NEAR_END_MS = 500L

    private fun userThresholdPositionMs(
        durationMs: Long,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Long = when (thresholdMode) {
        NextEpisodeThresholdMode.PERCENTAGE -> {
            val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
            kotlin.math.ceil(durationMs * (clampedPercent / 100.0)).toLong()
        }
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
            val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
            durationMs - (clampedMinutes * 60_000f).toLong()
        }
    }
}

/** See [PlayerNextEpisodeRules.leadCountdown]. */
data class NextEpisodeLeadCountdown(val remainingMs: Long, val totalMs: Long) {
    val remainingSeconds: Int get() = ((remainingMs + 999) / 1_000).toInt()
    val remainingFraction: Float get() = (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
}

/** Longest source name the next-episode card shows before shortening it. */
internal const val NEXT_EPISODE_SOURCE_LABEL_MAX_CHARS = 24

/**
 * The source name for the next-episode card ("Playing via <name> in Ns"): addon names often span
 * several lines and carry invisible formatting characters, and the card shows one line, so a long
 * name would push the seconds out of view. This keeps the first non-blank line, drops formatting
 * characters, collapses spaces and shortens it; falls back to [addonName].
 */
internal fun nextEpisodeSourceLabel(streamName: String?, addonName: String): String {
    fun clean(raw: String?): String? = raw
        ?.lineSequence()
        ?.map { line ->
            line.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        ?.firstOrNull { it.isNotEmpty() }
    val label = clean(streamName) ?: clean(addonName) ?: addonName.trim()
    return if (label.length <= NEXT_EPISODE_SOURCE_LABEL_MAX_CHARS) label
    else label.take(NEXT_EPISODE_SOURCE_LABEL_MAX_CHARS - 1).trimEnd() + "\u2026"
}

