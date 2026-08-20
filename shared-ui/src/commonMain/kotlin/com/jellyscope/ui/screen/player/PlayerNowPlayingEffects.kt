// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import com.jellyscope.core.domain.playback.PlayerController

/** Platform Now Playing hook; commands must remain routed through the ViewModel. */
@Composable
expect fun PlayerNowPlayingEffects(
    controller: PlayerController,
    content: PlayerUiState.Content?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
)
