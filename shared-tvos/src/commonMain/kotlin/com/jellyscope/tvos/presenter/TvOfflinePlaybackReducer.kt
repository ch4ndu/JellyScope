// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.PlaybackError

internal sealed interface TvOfflinePlaybackChange {
    data object Reset : TvOfflinePlaybackChange

    data object DiagnosticsHidden : TvOfflinePlaybackChange

    data class DiagnosticsLoaded(
        val rows: List<TvPlaybackDiagnosticRow>,
    ) : TvOfflinePlaybackChange

    data object Loading : TvOfflinePlaybackChange

    data class Projected(
        val state: TvOfflinePlaybackUiState,
    ) : TvOfflinePlaybackChange

    data class Failed(
        val playerInstalled: Boolean,
        val playerIdentity: Long,
        val error: PlaybackError,
    ) : TvOfflinePlaybackChange
}

internal fun reduceTvOfflinePlayback(
    current: TvOfflinePlaybackUiState,
    change: TvOfflinePlaybackChange,
): TvOfflinePlaybackUiState =
    when (change) {
        TvOfflinePlaybackChange.Reset -> TvOfflinePlaybackUiState()
        TvOfflinePlaybackChange.DiagnosticsHidden -> current.copy(diagnostics = emptyList())
        is TvOfflinePlaybackChange.DiagnosticsLoaded -> current.copy(diagnostics = change.rows)
        TvOfflinePlaybackChange.Loading -> current.copy(phase = TvPlaybackPhase.Loading)
        is TvOfflinePlaybackChange.Projected -> change.state.copy(diagnostics = current.diagnostics)
        is TvOfflinePlaybackChange.Failed ->
            current.copy(
                playerInstalled = change.playerInstalled,
                playerIdentity = change.playerIdentity,
                phase = TvPlaybackPhase.Failed,
                error = change.error,
            )
    }
