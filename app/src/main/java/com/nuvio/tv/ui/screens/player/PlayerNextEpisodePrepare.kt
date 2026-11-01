package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A sample this close after the previous one (and not before it) is normal playback, not a seek. */
internal const val PREPARE_CONTINUITY_MS = 3_000L

/** After a resume that starts past the mark, this much uninterrupted playback also prepares. */
internal const val PREPARE_SETTLE_MS = 10_000L

/** Debrid links are cached for 15 minutes; a prepared pick older than that is resolved again. */
internal const val PREPARED_STREAM_MAX_AGE_MS = 15L * 60L * 1_000L

/** The next episode's source, picked with the usual auto-play rules while the current one plays. */
internal class PreparedNextEpisode(
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    /** Playable (debrid-resolved) stream. */
    val stream: Stream,
    /** The selector's pick, kept for resolving an expired link again. */
    val sourceStream: Stream,
    /** The link was opened ahead of time, null when it was not. */
    val prewarm: NextEpisodePrewarmResult? = null,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val preparedAtMs = now()

    /**
     * The prepared stream (on the prewarmed link when the player can use it), resolved again from
     * [sourceStream] once it may have expired; null when that fails (the run then searches as
     * usual). A link resolved again has not been prewarmed, so it is used as is.
     */
    suspend fun playableStream(resolve: suspend (Stream) -> Stream?): Stream? =
        if (now() - preparedAtMs < PREPARED_STREAM_MAX_AGE_MS) {
            stream.withPrewarmedUrl(prewarm?.usableFinalUrl)
        } else {
            resolve(sourceStream)
        }
}

/** Where the "prepare at" trigger stands for the current episode. */
internal data class PrepareTriggerState(
    val lastPositionMs: Long? = null,
    val settleAnchorMs: Long? = null,
    val fired: Boolean = false,
)

internal sealed interface PrepareTriggerAction {
    data object None : PrepareTriggerAction

    /** Playback went back below the mark after it fired: drop the preparation, fire again later. */
    data object Rearm : PrepareTriggerAction

    data class Fire(val cause: String) : PrepareTriggerAction
}

/**
 * The "prepare at" trigger, one position sample at a time. It fires when normal playback crosses
 * [triggerMs] and when a seek lands past it; a resume that starts past the mark fires once playback
 * has run on for [PREPARE_SETTLE_MS]. The first sample only records the position.
 */
internal fun evaluatePrepareTrigger(
    state: PrepareTriggerState,
    positionMs: Long,
    triggerMs: Long,
): Pair<PrepareTriggerState, PrepareTriggerAction> {
    val lastMs = state.lastPositionMs
    val continuous = lastMs != null && positionMs - lastMs in 0..PREPARE_CONTINUITY_MS
    val anchor = if (continuous) state.settleAnchorMs else positionMs
    val next = state.copy(lastPositionMs = positionMs, settleAnchorMs = anchor)

    if (positionMs < triggerMs) {
        return if (state.fired) next.copy(fired = false) to PrepareTriggerAction.Rearm
        else next to PrepareTriggerAction.None
    }
    if (state.fired || lastMs == null) return next to PrepareTriggerAction.None

    val crossed = continuous && lastMs < triggerMs
    val seekedPast = !continuous
    val settled = continuous && positionMs - maxOf(anchor ?: positionMs, triggerMs) >= PREPARE_SETTLE_MS
    val cause = when {
        crossed -> "playback crossed"
        seekedPast -> "seek landed past the mark"
        settled -> "settled after resume"
        else -> return next to PrepareTriggerAction.None
    }
    return next.copy(fired = true) to PrepareTriggerAction.Fire(cause)
}

/** Prepare-next-episode bookkeeping for one player: the episode and source it tracks, and its result. */
internal class NextEpisodePrepareState {
    var episodeVideoId: String? = null
    var streamUrl: String? = null
    var trigger = PrepareTriggerState()
    var job: Job? = null
    var prepared: PreparedNextEpisode? = null
}

/**
 * Follows playback (called from the 500 ms position check): once it reaches the "prepare at"
 * percent of the episode, the next episode's source is searched for and resolved in the
 * background, so the switch at the outro or the end does not wait for addons. A change of episode
 * or source, or a seek back below the mark, drops the preparation.
 */
internal fun PlayerRuntimeController.onNextEpisodePrepareTick(positionMs: Long, durationMs: Long) {
    val state = nextEpisodePrepare
    val videoId = currentVideoId
    if (state.episodeVideoId != videoId) {
        discardPreparedNextEpisode()
        state.episodeVideoId = videoId
        state.streamUrl = currentStreamUrl
        state.trigger = PrepareTriggerState()
    } else if (state.streamUrl != currentStreamUrl) {
        discardPreparedNextEpisode()
        state.streamUrl = currentStreamUrl
        state.trigger = PrepareTriggerState()
    }

    val next = _uiState.value.nextEpisode
    val nextVideo = nextEpisodeVideo
    if (!prepareNextEpisodeEnabledSetting || !streamAutoPlayNextEpisodeEnabledSetting ||
        next == null || !next.hasAired || nextVideo == null || nextVideo.id != next.videoId ||
        contentType.equals("cloud", ignoreCase = true) || isPartyControlLocked
    ) {
        if (state.trigger.fired) {
            discardPreparedNextEpisode()
            state.trigger = state.trigger.copy(fired = false)
        }
        return
    }
    if (durationMs <= 0L || isShortPlaceholderDuration(durationMs)) return

    val percent = prepareNextEpisodePercentSetting.coerceIn(1f, 99f)
    val triggerMs = (durationMs * percent / 100f).toLong()
    val (trigger, action) = evaluatePrepareTrigger(state.trigger, positionMs, triggerMs)
    state.trigger = trigger
    when (action) {
        PrepareTriggerAction.None -> Unit
        PrepareTriggerAction.Rearm -> discardPreparedNextEpisode()
        is PrepareTriggerAction.Fire -> startNextEpisodePrepare()
    }
}

private fun PlayerRuntimeController.startNextEpisodePrepare() {
    val state = nextEpisodePrepare
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return
    val episodeAtStart = state.episodeVideoId
    state.job?.cancel()
    state.prepared = null
    state.job = scope.launch {
        val playerSettings = playerSettingsDataStore.playerSettings.first()
        val result = searchNextEpisodeStream(
            nextVideo = nextVideo,
            type = type,
            playerSettings = playerSettings,
            // In the background there is no picker to fall back to: wait for every addon.
            keepWaitingAfterTimeout = { true },
            background = true,
        )
        if (result is NextEpisodeSearchResult.Selected && state.episodeVideoId == episodeAtStart) {
            // Opening the link now has the debrid service unlock the file before the switch.
            val prewarm = if (prewarmNextEpisodeEnabledSetting) {
                prewarmNextEpisodeStream(result.stream, readTail = prewarmNextEpisodeTailEnabledSetting)
            } else {
                null
            }
            if (state.episodeVideoId != episodeAtStart) return@launch
            state.prepared = PreparedNextEpisode(
                videoId = nextVideo.id,
                season = nextVideo.season,
                episode = nextVideo.episode,
                stream = result.stream,
                sourceStream = result.sourceStream,
                prewarm = prewarm,
            )
        }
    }
}

/**
 * Hands over the prepared source for [nextVideoId], if one is ready. Anything else is dropped,
 * a prepare still searching included: the run searches on its own.
 */
internal fun PlayerRuntimeController.takePreparedNextEpisode(nextVideoId: String): PreparedNextEpisode? {
    val state = nextEpisodePrepare
    val prepared = state.prepared?.takeIf { it.videoId == nextVideoId }
    if (prepared == null) {
        discardPreparedNextEpisode()
        return null
    }
    state.prepared = null
    state.job = null
    return prepared
}

/**
 * Gives a preparation back when the run that took it was cancelled before switching (a seek back,
 * Back on the card), so the next run (the outro, the end, a Next press) still has it.
 */
internal fun PlayerRuntimeController.restorePreparedNextEpisode(prepared: PreparedNextEpisode) {
    val state = nextEpisodePrepare
    if (state.prepared == null && state.job?.isActive != true &&
        state.episodeVideoId == currentVideoId && nextEpisodeVideo?.id == prepared.videoId
    ) {
        state.prepared = prepared
    }
}

internal fun PlayerRuntimeController.discardPreparedNextEpisode() {
    val state = nextEpisodePrepare
    state.job?.cancel()
    state.job = null
    state.prepared = null
}
