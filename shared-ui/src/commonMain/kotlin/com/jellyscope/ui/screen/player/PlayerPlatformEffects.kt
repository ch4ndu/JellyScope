// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import com.jellyscope.core.domain.playback.PlayerController

@Composable
expect fun PlayerPlatformEffects(
    controller: PlayerController,
    content: PlayerUiState.Content?,
    sourceBounds: Rect?,
    commandCallbacks: PlayerPlatformCommandCallbacks,
    onPictureInPictureModeChanged: (Boolean) -> Unit,
    onCloseFromPictureInPicture: () -> Unit,
    onBackgrounded: () -> Unit,
)

enum class PlayerPlatformCommand {
    Play,
    Pause,
    Toggle,
    SeekTo,
    Next,
    Previous,
    Stop,
}

data class PlayerPlatformCommandCallbacks(
    val play: () -> Unit,
    val pause: () -> Unit,
    val toggle: () -> Unit,
    val seekTo: (Long) -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val stop: () -> Unit,
    val setPlaybackSpeed: (Float) -> Unit = {},
) {
    fun dispatch(
        command: PlayerPlatformCommand,
        seekPositionMs: Long? = null,
    ) {
        when (command) {
            PlayerPlatformCommand.Play -> play()
            PlayerPlatformCommand.Pause -> pause()
            PlayerPlatformCommand.Toggle -> toggle()
            PlayerPlatformCommand.SeekTo -> seekPositionMs?.let(seekTo)
            PlayerPlatformCommand.Next -> next()
            PlayerPlatformCommand.Previous -> previous()
            PlayerPlatformCommand.Stop -> stop()
        }
    }
}

interface PlayerGestureController {
    fun beginBrightness()

    fun updateBrightness(deltaFraction: Float): Float?

    fun beginVolume()

    fun updateVolume(deltaFraction: Float): Float?

    fun endGesture()
}

@Composable
expect fun rememberPlayerGestureController(): PlayerGestureController
