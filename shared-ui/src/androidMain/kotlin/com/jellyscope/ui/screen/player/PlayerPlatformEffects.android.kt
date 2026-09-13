// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    isFullscreen: Boolean,
    onPictureInPictureModeChanged: (Boolean) -> Unit,
    onCloseFromPictureInPicture: () -> Unit,
    onBackgrounded: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    val activePlayerRegistration = remember { AndroidActivePlayerRegistry.newRegistration() }

    val currentIsFullscreen by rememberUpdatedState(isFullscreen)
    val originalSystemBars = remember(activity) { activity?.let(AndroidSystemBarsSnapshot::capture) }

    fun applySystemBars(fullscreen: Boolean) {
        originalSystemBars?.apply(fullscreen)
    }

    SideEffect {
        applySystemBars(isFullscreen)
    }

    DisposableEffect(activity, originalSystemBars) {
        if (activity == null || originalSystemBars == null) {
            onDispose {}
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        applySystemBars(currentIsFullscreen)
                    }
                }
            activity.lifecycle.addObserver(observer)
            onDispose {
                activity.lifecycle.removeObserver(observer)
                originalSystemBars.restore()
            }
        }
    }

    DisposableEffect(activity, originalSystemBars) {
        if (activity == null) {
            onDispose {}
        } else {
            val listener =
                Consumer<PictureInPictureModeChangedInfo> { info ->
                    AndroidActivePlayerRegistry.setPictureInPictureMode(info.isInPictureInPictureMode)
                    onPictureInPictureModeChanged(info.isInPictureInPictureMode)
                    if (!info.isInPictureInPictureMode) {
                        applySystemBars(currentIsFullscreen)
                    }
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
        val playlist = content?.playlist
        AndroidActivePlayerRegistry.publish(
            registration = activePlayerRegistration,
            activePlayer =
                content?.let { current ->
                    AndroidActivePlayer(
                        controller = controller,
                        playbackState = current.playbackState,
                        metadata = current.metadata,
                        videoWidth = current.videoPresentation?.width?.takeIf { width -> width > 0 },
                        videoHeight = current.videoPresentation?.height?.takeIf { height -> height > 0 },
                        isSeekable = current.isSeekable,
                        playbackItemId = current.playbackItemId,
                        sourceRect = sourceBounds?.toAndroidRect(),
                        commandCallbacks = commandCallbacks,
                        hasNext = playlist?.let { value -> value.currentIndex < value.items.lastIndex } == true,
                        hasPrevious = playlist?.let { value -> value.currentIndex > 0 } == true,
                        onCloseFromPictureInPicture = onCloseFromPictureInPicture,
                    )
                },
        )
    }

    DisposableEffect(activePlayerRegistration) {
        onDispose {
            AndroidActivePlayerRegistry.release(activePlayerRegistration)
        }
    }
}

private class AndroidSystemBarsSnapshot private constructor(
    private val activity: ComponentActivity,
    private val statusBarsVisible: Boolean,
    private val navigationBarsVisible: Boolean,
    private val systemBarsBehavior: Int,
) {
    fun apply(fullscreen: Boolean) {
        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            restore()
        }
    }

    fun restore() {
        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insetsController.systemBarsBehavior = systemBarsBehavior
        if (statusBarsVisible) {
            insetsController.show(WindowInsetsCompat.Type.statusBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
        }
        if (navigationBarsVisible) {
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    companion object {
        fun capture(activity: ComponentActivity): AndroidSystemBarsSnapshot {
            val decorView = activity.window.decorView
            val insetsController = WindowCompat.getInsetsController(activity.window, decorView)
            val rootInsets = ViewCompat.getRootWindowInsets(decorView)
            return AndroidSystemBarsSnapshot(
                activity = activity,
                statusBarsVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true,
                navigationBarsVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true,
                systemBarsBehavior = insetsController.systemBarsBehavior,
            )
        }
    }
}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController {
    val context = LocalContext.current
    val controller = remember(context) { AndroidPlayerGestureController(context) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    return controller
}

data class AndroidActivePlayer(
    val controller: PlayerController,
    val playbackState: PlaybackState,
    val metadata: PlayerMediaMetadata,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val isSeekable: Boolean,
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
    private val registrations = AndroidActivePlayerRegistrationOwner()

    private var inPictureInPictureMode = false

    internal fun newRegistration(): AndroidActivePlayerRegistration = registrations.newRegistration()

    internal fun publish(
        registration: AndroidActivePlayerRegistration,
        activePlayer: AndroidActivePlayer?,
    ) {
        if (registrations.acceptPublication(registration)) {
            _activePlayer.value = activePlayer
        }
    }

    internal fun release(registration: AndroidActivePlayerRegistration) {
        if (registrations.release(registration)) {
            _activePlayer.value = null
        }
    }

    fun setPictureInPictureMode(inPictureInPicture: Boolean) {
        inPictureInPictureMode = inPictureInPicture
    }

    fun isInPictureInPictureMode(): Boolean = inPictureInPictureMode
}

internal class AndroidActivePlayerRegistrationOwner {
    private var nextSequence = 0L
    private var currentSequence = 0L
    private var currentReleased = true

    fun newRegistration(): AndroidActivePlayerRegistration = AndroidActivePlayerRegistration(owner = this, sequence = ++nextSequence)

    fun acceptPublication(registration: AndroidActivePlayerRegistration): Boolean {
        if (registration.owner !== this || registration.sequence < currentSequence) return false
        if (registration.sequence > currentSequence) {
            currentSequence = registration.sequence
            currentReleased = false
            return true
        }
        return !currentReleased
    }

    fun release(registration: AndroidActivePlayerRegistration): Boolean {
        if (
            registration.owner !== this ||
            registration.sequence != currentSequence ||
            currentReleased
        ) {
            return false
        }
        currentReleased = true
        return true
    }
}

internal class AndroidActivePlayerRegistration internal constructor(
    internal val owner: AndroidActivePlayerRegistrationOwner,
    val sequence: Long,
)

private class AndroidPlayerGestureController(
    context: Context,
) : PlayerGestureController {
    private val activity = context.findActivity()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val brightnessBaseline = activity?.window?.attributes?.screenBrightness
    private var initialBrightness = DEFAULT_BRIGHTNESS_FRACTION
    private var initialVolume = 0
    private var maxVolume = 1

    override fun beginBrightness() {
        val windowBrightness = activity?.window?.attributes?.screenBrightness
        initialBrightness =
            windowBrightness
                ?.takeIf { brightness -> brightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                ?: systemBrightness()
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

    override fun dispose() {
        val activity = activity ?: return
        val brightnessBaseline = brightnessBaseline ?: return
        val attributes = activity.window.attributes
        attributes.screenBrightness = brightnessBaseline
        activity.window.attributes = attributes
    }

    private fun systemBrightness(): Float {
        val resolver = activity?.contentResolver ?: return DEFAULT_BRIGHTNESS_FRACTION
        return try {
            Settings.System
                .getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
                .coerceIn(0, SYSTEM_BRIGHTNESS_MAX)
                .toFloat() / SYSTEM_BRIGHTNESS_MAX.toFloat()
        } catch (_: Exception) {
            DEFAULT_BRIGHTNESS_FRACTION
        }
    }
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
private const val SYSTEM_BRIGHTNESS_MAX = 255
