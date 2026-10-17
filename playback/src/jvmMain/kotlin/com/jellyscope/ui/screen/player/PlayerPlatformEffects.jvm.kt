// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.playback.DesktopDisplaySleep

@Composable
actual fun PlayerPlatformEffects(
    controller: PlayerController,
    content: PlayerUiState.Content?,
    sourceBounds: Rect?,
    commandCallbacks: PlayerPlatformCommandCallbacks,
    isFullscreen: Boolean,
    onPictureInPictureModeChanged: (Boolean) -> Unit,
    onCloseFromPictureInPicture: () -> Unit,
    onBackgrounded: () -> Unit,
) {
    val playbackStatus = content?.playbackState?.status
    DisposableEffect(controller, playbackStatus) {
        if (playbackStatus == PlaybackStatus.Playing || playbackStatus == PlaybackStatus.Buffering) {
            DesktopDisplaySleep.acquire()
        } else {
            DesktopDisplaySleep.release()
        }
        onDispose { DesktopDisplaySleep.release() }
    }
}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController = remember { NoOpPlayerGestureController }

private object NoOpPlayerGestureController : PlayerGestureController {
    override fun beginBrightness() = Unit

    override fun updateBrightness(deltaFraction: Float): Float? = null

    override fun beginVolume() = Unit

    override fun updateVolume(deltaFraction: Float): Float? = null

    override fun endGesture() = Unit
}
