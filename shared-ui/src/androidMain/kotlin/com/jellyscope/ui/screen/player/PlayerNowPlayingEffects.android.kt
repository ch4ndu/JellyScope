// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import com.jellyscope.core.domain.playback.PlayerController

@Composable
actual fun PlayerNowPlayingEffects(
    controller: PlayerController,
    content: PlayerUiState.Content?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    // Intentionally empty: Android MediaSession + Now Playing / PiP transport
    // controls are owned by MobilePlayerPlatformOwner (android-app) via the
    // platform-owner registry, not by this shared-ui hook.
}
