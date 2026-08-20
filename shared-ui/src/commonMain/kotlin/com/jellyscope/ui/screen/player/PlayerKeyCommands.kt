// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.ui.platform.FullscreenToggleState
import com.jellyscope.ui.platform.PlayerKeyCommandBridge
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun PlayerKeyCommandRegistration(
    bridge: PlayerKeyCommandBridge?,
    enabled: Boolean,
    content: PlayerUiState.Content?,
    playbackStateFlow: StateFlow<PlaybackState>,
    fullscreenToggle: FullscreenToggleState,
    holdSeek: HoldSeekController,
    pointerScrubActive: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onShowControls: () -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val currentPlaybackStateFlow by rememberUpdatedState(playbackStateFlow)
    val currentFullscreenToggle by rememberUpdatedState(fullscreenToggle)
    val currentPointerScrubActive by rememberUpdatedState(pointerScrubActive)
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnPause by rememberUpdatedState(onPause)
    val currentOnSetVolume by rememberUpdatedState(onSetVolume)
    val currentOnToggleMute by rememberUpdatedState(onToggleMute)
    val currentOnShowControls by rememberUpdatedState(onShowControls)

    DisposableEffect(bridge, enabled) {
        if (bridge == null || !enabled) {
            onDispose {}
        } else {
            val handler: (KeyEvent) -> Boolean = { event ->
                val consumed =
                    handlePlayerShortcutKey(
                        event = event,
                        content = currentContent,
                        playbackState = currentPlaybackStateFlow.value,
                        fullscreenToggle = currentFullscreenToggle,
                        holdSeek = holdSeek,
                        pointerScrubActive = currentPointerScrubActive,
                        onBack = currentOnBack,
                        onPlay = currentOnPlay,
                        onPause = currentOnPause,
                        onSetVolume = currentOnSetVolume,
                        onToggleMute = currentOnToggleMute,
                    )
                // Reveal the pending seek from the first key-down.
                if (consumed &&
                    (
                        event.key == Key.DirectionLeft ||
                            event.key == Key.DirectionRight ||
                            event.key == Key.Spacebar ||
                            event.key == Key.DirectionUp ||
                            event.key == Key.DirectionDown ||
                            event.key == Key.M
                    ) &&
                    (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp)
                ) {
                    currentOnShowControls()
                }
                consumed
            }
            bridge.handler = handler

            onDispose {
                if (bridge.handler === handler) {
                    bridge.handler = null
                }
            }
        }
    }
}
