// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlayerBackend

internal sealed interface TvPlaybackSessionChange {
    data class GuidanceChanged(
        val guidance: PlaybackHealthGuidance?,
    ) : TvPlaybackSessionChange

    data object StartLoading : TvPlaybackSessionChange

    data class Installed(
        val playerIdentity: Long,
        val backend: PlayerBackend,
    ) : TvPlaybackSessionChange

    data class StartFailed(
        val playerInstalled: Boolean,
    ) : TvPlaybackSessionChange

    data object DiagnosticsHidden : TvPlaybackSessionChange

    data class DiagnosticsLoaded(
        val rows: List<TvPlaybackDiagnosticRow>,
    ) : TvPlaybackSessionChange

    data class LocalSubtitleChanged(
        val selected: Boolean,
    ) : TvPlaybackSessionChange

    data class ItemLoading(
        val itemId: String,
        val mediaSourceId: String?,
        val planEpoch: Long,
        val nextEpisode: TvMediaCard?,
        val queue: TvPlaybackQueueState,
    ) : TvPlaybackSessionChange

    data class QueueChanged(
        val nextEpisode: TvMediaCard?,
        val queue: TvPlaybackQueueState,
    ) : TvPlaybackSessionChange

    data object Loading : TvPlaybackSessionChange

    data class Projected(
        val state: TvPlaybackUiState,
    ) : TvPlaybackSessionChange

    data class Failed(
        val error: PlaybackError,
    ) : TvPlaybackSessionChange
}

internal fun reduceTvPlaybackSession(
    current: TvPlaybackUiState,
    change: TvPlaybackSessionChange,
): TvPlaybackUiState =
    when (change) {
        is TvPlaybackSessionChange.GuidanceChanged ->
            with(change) {
                current.copy(playbackGuidance = guidance)
            }
        TvPlaybackSessionChange.StartLoading ->
            run {
                current.copy(
                    phase = TvPlaybackPhase.Loading,
                    error = null,
                )
            }
        is TvPlaybackSessionChange.Installed ->
            with(change) {
                current.copy(
                    playerInstalled = true,
                    playerIdentity = playerIdentity,
                    backend = backend,
                )
            }
        is TvPlaybackSessionChange.StartFailed ->
            with(change) {
                current.copy(
                    playerInstalled = playerInstalled,
                    phase = TvPlaybackPhase.Failed,
                    error = if (current.phase == TvPlaybackPhase.Failed) current.error else PlaybackError.Unknown,
                )
            }
        TvPlaybackSessionChange.DiagnosticsHidden ->
            run {
                current.copy(diagnostics = emptyList())
            }
        is TvPlaybackSessionChange.DiagnosticsLoaded ->
            with(change) {
                current.copy(diagnostics = rows)
            }
        is TvPlaybackSessionChange.LocalSubtitleChanged ->
            with(change) {
                current.copy(localSubtitleSelected = selected)
            }
        is TvPlaybackSessionChange.ItemLoading ->
            with(change) {
                current.copy(
                    phase = TvPlaybackPhase.Loading,
                    error = null,
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    planEpoch = planEpoch,
                    activeSegment = null,
                    nextEpisode = nextEpisode,
                    upNextVisible = false,
                    queue = queue,
                    audioTracks = emptyList(),
                    subtitleTracks = emptyList(),
                    localSubtitleSelected = false,
                    qualityChoices = emptyList(),
                    chapters = emptyList(),
                )
            }
        is TvPlaybackSessionChange.QueueChanged ->
            with(change) {
                current.copy(
                    nextEpisode = nextEpisode,
                    queue = queue,
                )
            }
        TvPlaybackSessionChange.Loading ->
            run {
                current.copy(
                    phase = TvPlaybackPhase.Loading,
                    error = null,
                )
            }
        is TvPlaybackSessionChange.Projected ->
            with(change) {
                // A published failure owns the screen until a new attempt
                // resets it; native fields keep updating underneath.
                if (current.phase == TvPlaybackPhase.Failed) {
                    state.copy(
                        diagnostics = current.diagnostics,
                        phase = current.phase,
                        error = current.error,
                    )
                } else {
                    state.copy(diagnostics = current.diagnostics)
                }
            }
        is TvPlaybackSessionChange.Failed ->
            with(change) {
                current.copy(
                    phase = TvPlaybackPhase.Failed,
                    error = if (current.phase == TvPlaybackPhase.Failed) current.error else error,
                )
            }
    }
