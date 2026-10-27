// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

/** Screen transitions only; controller commands and projection remain ViewModel-owned. */
internal sealed interface PlayerChange {
    data object Loading : PlayerChange

    data class Failed(
        val state: PlayerUiState.Error,
    ) : PlayerChange

    data class ContentReady(
        val content: PlayerUiState.Content,
    ) : PlayerChange

    data object SeekUnavailable : PlayerChange

    data object QueueSwitchStarted : PlayerChange
}

internal fun reducePlayer(
    current: PlayerUiState,
    change: PlayerChange,
): PlayerUiState =
    when (change) {
        PlayerChange.Loading -> PlayerUiState.Loading
        is PlayerChange.Failed -> change.state
        is PlayerChange.ContentReady -> change.content
        PlayerChange.SeekUnavailable ->
            (current as? PlayerUiState.Content)?.copy(isSeekable = false) ?: current
        PlayerChange.QueueSwitchStarted ->
            (current as? PlayerUiState.Content)?.copy(isSeekable = false, playbackItemId = null) ?: current
    }
