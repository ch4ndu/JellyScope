// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import com.jellyscope.core.domain.playback.PlayerController
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification

@Composable
actual fun PlayerPlatformEffects(
    controller: PlayerController,
    content: PlayerUiState.Content?,
    sourceBounds: Rect?,
    commandCallbacks: PlayerPlatformCommandCallbacks,
    onPictureInPictureModeChanged: (Boolean) -> Unit,
    onCloseFromPictureInPicture: () -> Unit,
    onBackgrounded: () -> Unit,
) {
    val currentOnPictureInPictureModeChanged = rememberUpdatedState(onPictureInPictureModeChanged)
    val currentOnCloseFromPictureInPicture = rememberUpdatedState(onCloseFromPictureInPicture)
    val currentOnBackgrounded = rememberUpdatedState(onBackgrounded)
    val hasContent = content != null

    DisposableEffect(controller, hasContent) {
        if (!hasContent) {
            IosPictureInPictureCoordinator.clearCallbacks(controller)
        } else {
            IosPictureInPictureCoordinator.updateCallbacks(
                IosPictureInPictureCallbacks(
                    controller = controller,
                    onPictureInPictureModeChanged = { inPictureInPicture ->
                        currentOnPictureInPictureModeChanged.value(inPictureInPicture)
                    },
                    onCloseFromPictureInPicture = {
                        currentOnCloseFromPictureInPicture.value()
                    },
                    onBackgrounded = {
                        currentOnBackgrounded.value()
                    },
                ),
            )
        }
        onDispose {
            IosPictureInPictureCoordinator.clearCallbacks(controller)
        }
    }

    // Custom AVPlayerLayer/VLC surfaces get no AVKit idle-timer exemption, so the
    // device would auto-lock mid-playback, background the app, and close the
    // player. Keep the screen awake for the whole player-screen lifetime —
    // including the initial Loading state before content exists — mirroring
    // Android's keepScreenOn on the player surface.
    DisposableEffect(Unit) {
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose {
            UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }

    DisposableEffect(controller) {
        val notificationCenter = NSNotificationCenter.defaultCenter
        val willResignActiveObserver =
            notificationCenter.addObserverForName(
                name = UIApplicationWillResignActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                IosPictureInPictureCoordinator.onWillResignActive(controller)
            }
        val didEnterBackgroundObserver =
            notificationCenter.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                if (IosPictureInPictureCoordinator.onDidEnterBackground(controller)) {
                    currentOnBackgrounded.value()
                }
            }
        val didBecomeActiveObserver =
            notificationCenter.addObserverForName(
                name = UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                IosPictureInPictureCoordinator.onDidBecomeActive(controller)
            }
        onDispose {
            notificationCenter.removeObserver(willResignActiveObserver)
            notificationCenter.removeObserver(didEnterBackgroundObserver)
            notificationCenter.removeObserver(didBecomeActiveObserver)
        }
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
