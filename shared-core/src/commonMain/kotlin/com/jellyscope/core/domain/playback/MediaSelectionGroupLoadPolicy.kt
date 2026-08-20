// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

enum class MediaSelectionGroupLoadState {
    NotLoaded,
    Loading,
    LoadedPresent,
    LoadedAbsent,
}

enum class MediaSelectionGroupLoadAction {
    Wait,
    Map,
    Recover,
}

fun resolveMediaSelectionGroupLoadAction(state: MediaSelectionGroupLoadState): MediaSelectionGroupLoadAction =
    when (state) {
        MediaSelectionGroupLoadState.NotLoaded,
        MediaSelectionGroupLoadState.Loading,
        -> MediaSelectionGroupLoadAction.Wait
        MediaSelectionGroupLoadState.LoadedPresent -> MediaSelectionGroupLoadAction.Map
        MediaSelectionGroupLoadState.LoadedAbsent -> MediaSelectionGroupLoadAction.Recover
    }
