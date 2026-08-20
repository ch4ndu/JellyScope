// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import android.graphics.Rect as AndroidRect

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
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity }

    DisposableEffect(activity) {
        if (activity == null) {
            onDispose {}
        } else {
            val listener =
                Consumer<PictureInPictureModeChangedInfo> { info ->
                    AndroidActivePlayerRegistry.setPictureInPictureMode(info.isInPictureInPictureMode)
                    onPictureInPictureModeChanged(info.isInPictureInPictureMode)
                }
            activity.addOnPictureInPictureModeChangedListener(listener)
            onDispose {
                activity.removeOnPictureInPictureModeChangedListener(listener)
            }
        }
    }

    // Dismiss the player only when the whole ACTIVITY stops (app sent to
    // background) and we are not in PiP or a config change. Observing the
    // activity lifecycle — not the NavBackStackEntry lifecycle — means this does
    // NOT fire while the player route is being popped, so back navigation pops
    // exactly once instead of clearing the whole back stack.
    val currentOnBackgrounded by rememberUpdatedState(onBackgrounded)
    DisposableEffect(activity) {
        if (activity == null) {
            onDispose {}
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP &&
                        !AndroidActivePlayerRegistry.isInPictureInPictureMode() &&
                        !activity.isChangingConfigurations
                    ) {
                        currentOnBackgrounded()
                    }
                }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }

    // A SideEffect, not a LaunchedEffect keyed on the values it writes. The body
    // is a synchronous registry write with nothing to suspend on, and keying it
    // on `content`/`sourceBounds` cancelled and relaunched a coroutine at least
    // once a second — plus once per layout pass that moved the surface — to
    // perform that one assignment. SideEffect publishes to the non-Compose
    // registry after each successful composition instead, which is what it is
    // for; the registry's StateFlow conflates a write that changes nothing, so
    // recomposing without a real change still costs no emission downstream.
    SideEffect {
        if (content == null) {
            AndroidActivePlayerRegistry.clear(controller)
        } else {
            val playlist = content.playlist
            AndroidActivePlayerRegistry.update(
                AndroidActivePlayer(
                    controller = controller,
                    playbackState = content.playbackState,
                    metadata = content.metadata,
                    videoWidth = content.videoPresentation?.width?.takeIf { width -> width > 0 },
                    videoHeight = content.videoPresentation?.height?.takeIf { height -> height > 0 },
                    playbackItemId = content.playbackItemId,
                    sourceRect = sourceBounds?.toAndroidRect(),
                    commandCallbacks = commandCallbacks,
                    hasNext = playlist?.let { value -> value.currentIndex < value.items.lastIndex } == true,
                    hasPrevious = playlist?.let { value -> value.currentIndex > 0 } == true,
                    onCloseFromPictureInPicture = onCloseFromPictureInPicture,
                ),
            )
        }
    }

    DisposableEffect(controller) {
        onDispose {
            AndroidActivePlayerRegistry.clear(controller)
        }
    }
}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController {
    val context = LocalContext.current
    return remember(context) { AndroidPlayerGestureController(context) }
}

data class AndroidActivePlayer(
    val controller: PlayerController,
    val playbackState: PlaybackState,
    val metadata: PlayerMediaMetadata,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val playbackItemId: String?,
    val sourceRect: AndroidRect?,
    val commandCallbacks: PlayerPlatformCommandCallbacks,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val onCloseFromPictureInPicture: () -> Unit,
)

object AndroidActivePlayerRegistry {
    private val _activePlayer = MutableStateFlow<AndroidActivePlayer?>(null)
    val activePlayer: StateFlow<AndroidActivePlayer?> = _activePlayer.asStateFlow()

    private var inPictureInPictureMode = false

    fun update(activePlayer: AndroidActivePlayer) {
        _activePlayer.value = activePlayer
    }

    fun clear(controller: PlayerController) {
        if (_activePlayer.value?.controller == controller) {
            _activePlayer.value = null
        }
    }

    fun setPictureInPictureMode(inPictureInPicture: Boolean) {
        inPictureInPictureMode = inPictureInPicture
    }

    fun isInPictureInPictureMode(): Boolean = inPictureInPictureMode
}

private class AndroidPlayerGestureController(
    context: Context,
) : PlayerGestureController {
    private val activity = context.findActivity()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var initialBrightness = DEFAULT_BRIGHTNESS_FRACTION
    private var initialVolume = 0
    private var maxVolume = 1

    override fun beginBrightness() {
        initialBrightness =
            activity
                ?.window
                ?.attributes
                ?.screenBrightness
                ?.takeIf { brightness -> brightness >= 0f }
                ?: DEFAULT_BRIGHTNESS_FRACTION
    }

    override fun updateBrightness(deltaFraction: Float): Float? {
        val activity = activity ?: return null
        val brightness = (initialBrightness + deltaFraction).coerceIn(0f, 1f)
        val attributes = activity.window.attributes
        attributes.screenBrightness = brightness
        activity.window.attributes = attributes
        return brightness
    }

    override fun beginVolume() {
        val audioManager = audioManager ?: return
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
    }

    override fun updateVolume(deltaFraction: Float): Float? {
        val audioManager = audioManager ?: return null
        val volume = (initialVolume + (deltaFraction * maxVolume).roundToInt()).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        return volume.toFloat() / maxVolume.toFloat()
    }

    override fun endGesture() = Unit
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}

private fun Rect.toAndroidRect(): AndroidRect? {
    val rect =
        AndroidRect(
            left.roundToInt(),
            top.roundToInt(),
            right.roundToInt(),
            bottom.roundToInt(),
        )
    return rect.takeIf { it.width() > 0 && it.height() > 0 }
}

private const val DEFAULT_BRIGHTNESS_FRACTION = 0.5f
