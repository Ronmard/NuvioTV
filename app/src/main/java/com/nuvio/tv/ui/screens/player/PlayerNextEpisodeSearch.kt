package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Upper bound on any wait for the next episode's sources, past the auto-play timeout included. */
internal const val NEXT_EPISODE_HARD_TIMEOUT_MS = 120_000L

/** How often a search waiting past its timeout re-checks whether it should keep waiting. */
internal const val NEXT_EPISODE_KEEP_WAITING_POLL_MS = 250L

internal sealed interface NextEpisodeSearchResult {
    /** [stream] is playable (debrid-resolved); [sourceStream] is the selector's pick, for re-resolving. */
    data class Selected(val stream: Stream, val sourceStream: Stream) : NextEpisodeSearchResult

    /**
     * Nothing to play on its own: the picker opens instead. [forceRefresh] is the picker's refresh
     * (an addon failed, the pick failed to resolve, or auto-select is off).
     */
    data class NoSelection(val reason: String, val forceRefresh: Boolean) : NextEpisodeSearchResult
}

/**
 * Waits for the next episode's source search to settle, in the auto-play timeout's terms:
 * - a bounded [timeoutSeconds] waits up to it, then picks from what has arrived (the caller's
 *   [selectFromArrived]). With nothing picked, addons still answering and [keepWaitingAfterTimeout]
 *   true, it keeps waiting for them (up to [hardTimeoutMs]) instead of giving up, and picks again
 *   once they have answered; without that it waits one more timeout when nothing at all arrived;
 * - 0 (instant) and unlimited wait for the search to settle, up to [hardTimeoutMs].
 *
 * [settled] completes when a stream is picked or every addon has answered. [onTimeoutElapsed] tells
 * the collector that from now on any matching stream is taken.
 */
internal suspend fun awaitNextEpisodeSelection(
    timeoutSeconds: Int,
    settled: CompletableDeferred<Unit>,
    isSelected: () -> Boolean,
    hasArrived: () -> Boolean,
    onTimeoutElapsed: () -> Unit,
    selectFromArrived: () -> Unit,
    keepWaitingAfterTimeout: () -> Boolean,
    hardTimeoutMs: Long = NEXT_EPISODE_HARD_TIMEOUT_MS,
    pollMs: Long = NEXT_EPISODE_KEEP_WAITING_POLL_MS,
) {
    if (PlayerSettings.isBoundedTimeout(timeoutSeconds)) {
        val timeoutMs = timeoutSeconds * 1_000L
        withTimeoutOrNull(timeoutMs) { settled.await() }
        onTimeoutElapsed()
        if (!isSelected()) selectFromArrived()
        if (!isSelected() && !settled.isCompleted && keepWaitingAfterTimeout()) {
            // Addons still answering and the episode not over: wait for them instead of opening the
            // picker. The wait ends when a stream is picked, every addon has answered, or the
            // episode ends.
            withTimeoutOrNull(hardTimeoutMs) {
                while (!settled.isCompleted && keepWaitingAfterTimeout()) {
                    withTimeoutOrNull(pollMs) { settled.await() }
                }
            }
            if (!isSelected()) selectFromArrived()
        } else if (!isSelected() && !hasArrived()) {
            withTimeoutOrNull(timeoutMs) { settled.await() }
            if (!isSelected()) selectFromArrived()
        }
    } else {
        if (timeoutSeconds == 0) onTimeoutElapsed()
        withTimeoutOrNull(hardTimeoutMs) { settled.await() }
        if (!isSelected()) selectFromArrived()
    }
}

/**
 * Searches the next episode's sources and applies the auto-play rules (binge group, filename match,
 * the selector), then resolves the pick to a playable stream.
 *
 * [keepWaitingAfterTimeout] lets the search wait past the auto-play timeout while addons are still
 * answering. [background] is a search that runs while the current episode plays: it records no
 * loading diagnostics for the current stream.
 */
internal suspend fun PlayerRuntimeController.searchNextEpisodeStream(
    nextVideo: Video,
    type: String,
    playerSettings: PlayerSettings,
    keepWaitingAfterTimeout: () -> Boolean,
    background: Boolean,
): NextEpisodeSearchResult = coroutineScope {
    val shouldAutoSelectInManualMode =
        playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (
                playerSettings.streamAutoPlayNextEpisodeEnabled ||
                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
                )
    val bingeGroupOnlyManualMode =
        shouldAutoSelectInManualMode &&
            (!playerSettings.streamAutoPlayNextEpisodeEnabled ||
                !playerSettings.streamAutoPlayNextEpisodeFallbackEnabled) &&
            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
    if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) {
        return@coroutineScope NextEpisodeSearchResult.NoSelection(
            reason = "manual mode (no auto-select rule enabled)",
            forceRefresh = true,
        )
    }

    val installedAddons = streamAddonsFor(nextVideo.id)
    val installedAddonOrder = installedAddons.map { it.displayName }
    val preferredServerNames = serverStreams.preferredSourceNames(type, nextVideo.id)
    val effectiveMode = if (shouldAutoSelectInManualMode) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        playerSettings.streamAutoPlayMode
    }
    val effectiveSource = if (shouldAutoSelectInManualMode) {
        StreamAutoPlaySource.ALL_SOURCES
    } else {
        playerSettings.streamAutoPlaySource
    }
    val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        playerSettings.streamAutoPlaySelectedAddons
    }
    val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        playerSettings.streamAutoPlaySelectedPlugins
    }
    val effectiveRegex = if (shouldAutoSelectInManualMode) {
        ""
    } else {
        playerSettings.streamAutoPlayRegex
    }
    val bingeGroup = currentStreamBingeGroup
    val filename = currentFilename
    val currentSeasonNumber = _uiState.value.currentSeason
    val currentEpisodeNumber = _uiState.value.currentEpisode ?: 0
    var selectedStream: Stream? = null
    var lastSuccessData: List<AddonStreams>? = null
    var autoSelectTriggered = false
    var timeoutElapsed = false
    var lastError: NetworkResult.Error? = null
    // Completed as soon as a stream is selected or the addon search
    // finishes, so the waiting code resumes without polling.
    val searchSettled = CompletableDeferred<Unit>()

    fun trySelectStream(data: List<AddonStreams>): Stream? {
        val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder, preferredServerNames)
        val allStreams = orderedStreams.flatMap { it.streams }
        return StreamAutoPlaySelector.selectAutoPlayStream(
            streams = allStreams,
            mode = effectiveMode,
            regexPattern = effectiveRegex,
            source = effectiveSource,
            installedAddonNames = installedAddonOrder.toSet(),
            selectedAddons = effectiveSelectedAddons,
            selectedPlugins = effectiveSelectedPlugins,
            preferredBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) {
                bingeGroup
            } else {
                null
            },
            preferBingeGroupInSelection = playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode,
            bingeGroupOnly = bingeGroupOnlyManualMode
        )
    }

    fun nextFilename(): String? =
        com.nuvio.tv.core.player.FilenameEpisodeMatcher.buildNextFilename(
            filename = filename.orEmpty(),
            currentSeason = currentSeasonNumber,
            currentEpisode = currentEpisodeNumber,
            nextSeason = nextVideo.season,
            nextEpisode = nextVideo.episode ?: 0
        ) ?: filename?.takeIf { it.isNotBlank() }

    fun tryBingeGroupOnly(data: List<AddonStreams>): Stream? {
        if (bingeGroup == null || !playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) return null
        val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder, preferredServerNames)
        val allStreams = orderedStreams.flatMap { it.streams }
        // Early: binge group + filename match (most precise)
        val expectedFilename = nextFilename()
        if (expectedFilename != null) {
            val bingeAndFilename = allStreams.firstOrNull { stream ->
                !stream.isExternal() &&
                    stream.behaviorHints?.bingeGroup == bingeGroup &&
                    com.nuvio.tv.core.player.FilenameEpisodeMatcher.matches(
                        stream.behaviorHints?.filename, expectedFilename
                    )
            }
            if (bingeAndFilename != null) return bingeAndFilename
        }
        // Early fallback: binge group alone
        return StreamAutoPlaySelector.selectAutoPlayStream(
            streams = allStreams,
            mode = effectiveMode,
            regexPattern = effectiveRegex,
            source = effectiveSource,
            installedAddonNames = installedAddonOrder.toSet(),
            selectedAddons = effectiveSelectedAddons,
            selectedPlugins = effectiveSelectedPlugins,
            preferredBingeGroup = bingeGroup,
            preferBingeGroupInSelection = true,
            bingeGroupOnly = true
        )
    }

    fun tryFilenameOnly(data: List<AddonStreams>): Stream? {
        val expectedFilename = nextFilename() ?: return null
        val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder, preferredServerNames)
        return orderedStreams.flatMap { it.streams }.firstOrNull { stream ->
            !stream.isExternal() &&
                com.nuvio.tv.core.player.FilenameEpisodeMatcher.matches(
                    stream.behaviorHints?.filename, expectedFilename
                )
        }
    }

    fun recordSelection(candidate: Stream) {
        autoSelectTriggered = true
        selectedStream = candidate
        searchSettled.complete(Unit)
    }

    val innerJob = launch {
        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = nextVideo.id,
            season = nextVideo.season,
            episode = nextVideo.episode
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    lastSuccessData = result.data
                    if (!autoSelectTriggered) {
                        val candidate = when {
                            timeoutElapsed -> trySelectStream(result.data)
                            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode ->
                                tryBingeGroupOnly(result.data)
                            else -> null
                        }
                        if (candidate != null) recordSelection(candidate)
                    }
                }
                is NetworkResult.Error -> lastError = result
                NetworkResult.Loading -> Unit
            }
        }
        // Every addon has responded: take whatever matched, then settle so
        // the waiting code resumes even if nothing was selected.
        if (!autoSelectTriggered) {
            lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
        }
        searchSettled.complete(Unit)
    }

    awaitNextEpisodeSelection(
        timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds,
        settled = searchSettled,
        isSelected = { autoSelectTriggered },
        hasArrived = { lastSuccessData != null },
        onTimeoutElapsed = { timeoutElapsed = true },
        selectFromArrived = {
            // Filename match before full select: same release, any binge group.
            lastSuccessData?.let { data ->
                (tryFilenameOnly(data) ?: trySelectStream(data))?.let { recordSelection(it) }
            }
        },
        keepWaitingAfterTimeout = keepWaitingAfterTimeout,
    )
    innerJob.cancel()

    val picked = selectedStream
    val playable = picked?.let {
        if (background) {
            // A background search must not touch the current stream's loading diagnostics.
            (directDebridResolver.resolveToPlayableStream(it, nextVideo.season, nextVideo.episode)
                as? DirectDebridPlayableResult.Success)?.stream
        } else {
            resolveDirectDebridStreamIfNeeded(it, nextVideo.season, nextVideo.episode)
        }
    }
    if (picked != null && playable != null) {
        NextEpisodeSearchResult.Selected(stream = playable, sourceStream = picked)
    } else {
        NextEpisodeSearchResult.NoSelection(
            reason = when {
                picked != null -> "debrid resolve failed"
                lastSuccessData == null -> "no sources"
                else -> "nothing passed the auto-play filters"
            },
            forceRefresh = lastError != null || picked != null,
        )
    }
}
