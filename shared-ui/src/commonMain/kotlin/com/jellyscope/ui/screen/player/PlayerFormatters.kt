// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthGuidanceReason
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_skip_credits
import com.jellyscope.ui.generated.resources.player_skip_intro
import com.jellyscope.ui.generated.resources.player_skip_segment
import com.jellyscope.ui.platform.FullscreenToggleState
import com.jellyscope.ui.screen.detail.requestFocusSafely
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

internal enum class PlayerBackAction {
    ClosePicker,

    ReturnToParentPicker,
    HideControls,
    ExitPlayer,
}

internal fun playerBackAction(
    picker: PlayerPicker,
    controlsVisible: Boolean,
): PlayerBackAction =
    when {
        picker.parentPicker() != PlayerPicker.None -> PlayerBackAction.ReturnToParentPicker
        picker != PlayerPicker.None -> PlayerBackAction.ClosePicker
        controlsVisible -> PlayerBackAction.HideControls
        else -> PlayerBackAction.ExitPlayer
    }

internal data class PlayerPlaybackGuidanceActions(
    val dismiss: Boolean,
    val navigateToSettings: Boolean,
    val reduceQuality: Boolean,
)

internal fun playbackGuidanceActions(guidance: PlaybackHealthGuidance): PlayerPlaybackGuidanceActions =
    PlayerPlaybackGuidanceActions(
        dismiss = guidance.canDismiss,
        navigateToSettings = guidance.canOpenPlaybackSettings,
        reduceQuality = guidance.canReduceQuality && guidance.nextLowerQualityRungBps != null,
    )

internal enum class PlayerPlaybackGuidanceWording {
    SlowStartup,
    DirectPlayCapacity,
    StreamingPressure,
    PlaybackTakingLonger,
}

internal fun PlaybackHealthGuidance.wordingPolicy(): PlayerPlaybackGuidanceWording =
    when {
        reason == PlaybackHealthGuidanceReason.SlowStartup -> PlayerPlaybackGuidanceWording.SlowStartup
        streamMode == StreamMode.DirectPlay -> PlayerPlaybackGuidanceWording.DirectPlayCapacity
        streamMode == StreamMode.DirectStream || streamMode == StreamMode.Transcode ->
            PlayerPlaybackGuidanceWording.StreamingPressure
        else -> PlayerPlaybackGuidanceWording.PlaybackTakingLonger
    }

internal enum class PlayerTapZone {
    Left,
    Center,
    Right,
}

internal sealed interface PlayerTapAction {
    data object ToggleControlsNow : PlayerTapAction

    data object ScheduleToggleControls : PlayerTapAction

    data object SeekBackward : PlayerTapAction

    data object SeekForward : PlayerTapAction

    data object Ignore : PlayerTapAction
}

/** Center taps toggle immediately; side taps wait for a possible seek gesture. */
internal fun playerTapAction(
    zone: PlayerTapZone,
    lastZone: PlayerTapZone?,
    msSinceLastTap: Long?,
): PlayerTapAction {
    val isDoubleTap =
        lastZone == zone &&
            msSinceLastTap != null &&
            msSinceLastTap <= DOUBLE_TAP_TIMEOUT_MS
    return when {
        isDoubleTap && zone == PlayerTapZone.Left -> PlayerTapAction.SeekBackward
        isDoubleTap && zone == PlayerTapZone.Right -> PlayerTapAction.SeekForward
        isDoubleTap -> PlayerTapAction.Ignore
        zone == PlayerTapZone.Center -> PlayerTapAction.ToggleControlsNow
        else -> PlayerTapAction.ScheduleToggleControls
    }
}

internal fun shouldAutoHidePlayerCursor(
    desktopPlayerControls: Boolean,
    fullscreen: Boolean,
    picker: PlayerPicker,
    pictureInPicture: Boolean,
): Boolean =
    desktopPlayerControls &&
        fullscreen &&
        picker == PlayerPicker.None &&
        !pictureInPicture

internal fun isSubtitleClearanceActive(
    controlsVisible: Boolean,
    picker: PlayerPicker,
    pictureInPicture: Boolean,
): Boolean =
    !pictureInPicture &&
        (controlsVisible || picker != PlayerPicker.None)

// Quality details belong in the debug overlay, not the title.
internal fun playerTitle(metadata: PlayerMediaMetadata): String =
    listOfNotNull(
        metadata.title.takeIf { title -> title.isNotBlank() },
        metadata.productionYear?.toString(),
    ).joinToString(" · ")

internal fun playerSubtitle(metadata: PlayerMediaMetadata): String? {
    val seriesName = metadata.seriesName?.takeIf { name -> name.isNotBlank() }
    val episodeLabel = metadata.episodeLabel?.takeIf { label -> label.isNotBlank() }
    return when {
        seriesName != null && episodeLabel != null -> "$seriesName · $episodeLabel"
        seriesName != null -> seriesName
        else -> episodeLabel
    }
}

@Composable
internal fun Modifier.playerKeyboardShortcuts(
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
): Modifier {
    if (!enabled) {
        return this
    }

    val focusRequester = remember { FocusRequester() }
    val currentContent by rememberUpdatedState(content)
    val currentPlaybackStateFlow by rememberUpdatedState(playbackStateFlow)
    val currentFullscreenToggle by rememberUpdatedState(fullscreenToggle)
    val currentPointerScrubActive by rememberUpdatedState(pointerScrubActive)
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnPause by rememberUpdatedState(onPause)
    val currentOnSetVolume by rememberUpdatedState(onSetVolume)
    val currentOnToggleMute by rememberUpdatedState(onToggleMute)

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocusSafely()
    }

    return this
        .focusRequester(focusRequester)
        .onPreviewKeyEvent { event ->
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
        }.focusable()
}

internal fun handlePlayerShortcutKey(
    event: KeyEvent,
    content: PlayerUiState.Content?,
    playbackState: PlaybackState,
    fullscreenToggle: FullscreenToggleState,
    holdSeek: HoldSeekController,
    pointerScrubActive: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
): Boolean {
    val volumeKey =
        event.key == Key.DirectionUp ||
            event.key == Key.DirectionDown ||
            event.key == Key.M
    if (volumeKey && content?.volumeControl == null) {
        return false
    }
    val shortcut =
        event.key == Key.DirectionLeft ||
            event.key == Key.DirectionRight ||
            event.key == Key.Spacebar ||
            event.key == Key.Escape ||
            volumeKey
    if (!shortcut) {
        return false
    }
    // Key-down accumulates a seek; key-up commits it to the same playback item.
    if (event.type == KeyEventType.KeyDown) {
        when (event.key) {
            Key.DirectionUp -> {
                onSetVolume((content?.volumeControl?.volumePercent ?: 0) + 5)
                return true
            }
            Key.DirectionDown -> {
                onSetVolume((content?.volumeControl?.volumePercent ?: 0) - 5)
                return true
            }
            Key.M -> {
                onToggleMute()
                return true
            }
            else -> Unit
        }
        val direction = holdSeekDirectionForKey(event.key, includeDpadHorizontal = true)
        if (direction != null && content?.playbackItemId != null && !pointerScrubActive) {
            holdSeek.onSeekKeyDown(direction)
        }
        return true
    }
    if (event.type != KeyEventType.KeyUp) {
        return true
    }

    when (event.key) {
        Key.DirectionLeft,
        Key.DirectionRight,
        -> holdSeek.onSeekKeyUp()
        Key.Spacebar -> {
            holdSeek.commitBeforeAction()
            if (playbackState.status == PlaybackStatus.Playing) {
                onPause()
            } else if (content != null) {
                onPlay()
            }
        }
        Key.Escape -> {
            holdSeek.cancel()
            if (fullscreenToggle.isFullscreen) {
                fullscreenToggle.exitFullscreen()
            } else {
                onBack()
            }
        }
        Key.DirectionUp,
        Key.DirectionDown,
        Key.M,
        -> Unit
        else -> Unit
    }
    return true
}

internal fun WindowWidthTier.isDesktopPlayerWide(): Boolean = this == WindowWidthTier.Expanded || this == WindowWidthTier.XLarge

internal fun skipSegmentLabel(type: MediaSegmentType): StringResource =
    when (type) {
        MediaSegmentType.Intro -> Res.string.player_skip_intro
        MediaSegmentType.Outro -> Res.string.player_skip_credits
        MediaSegmentType.Recap,
        MediaSegmentType.Preview,
        MediaSegmentType.Commercial,
        MediaSegmentType.Unknown,
        -> Res.string.player_skip_segment
    }

/** Queue badge label; absent when the episode number is unknown. */
fun episodeNumberLabel(
    seasonNumber: Int?,
    episodeNumber: Int?,
): String? {
    val episode = episodeNumber ?: return null
    return seasonNumber?.let { season -> "S$season · E$episode" } ?: "E$episode"
}

internal fun formatDuration(milliseconds: Long?): String {
    if (milliseconds == null) {
        return "--:--"
    }

    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE

    return if (hours > 0L) {
        "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
    } else {
        "$minutes:${seconds.twoDigits()}"
    }
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')

internal const val PLAYER_CONTROLS_AUTO_HIDE_MS = 4_000L
internal const val PLAYER_CURSOR_AUTO_HIDE_MS = 2_500L
internal const val GESTURE_HUD_AUTO_HIDE_MS = 1_000L
internal const val AUDIO_UNAVAILABLE_NOTICE_MS = 6_000L
internal const val SUBTITLE_UNAVAILABLE_NOTICE_MS = 6_000L
const val BACKEND_FALLBACK_NOTICE_MS = 6_000L
internal const val KEYBOARD_SEEK_STEP_MS = 10_000L
internal const val DOUBLE_TAP_SEEK_MS = 10_000L
internal const val DOUBLE_TAP_TIMEOUT_MS = 300L
internal const val LONG_PRESS_SPEED_DELAY_MS = 500L
internal const val TEMPORARY_LONG_PRESS_SPEED = 2f
internal const val PLAYER_GESTURE_TAP_SLOP_PX = 10f
internal const val PLAYER_GESTURE_MIN_DISTANCE_PX = 50f
internal const val FULL_VERTICAL_SWIPE_HEIGHT_RATIO = 0.66f
internal const val PLAYER_VERTICAL_DOMINANCE_RATIO = 2f
internal const val PLAYER_GESTURE_VERTICAL_EXCLUSION_RATIO = 0.15f
internal const val PLAYER_GESTURE_HORIZONTAL_EXCLUSION_RATIO = 0.04f
internal const val PLAYER_DOUBLE_TAP_SIDE_ZONE_RATIO = 0.4f
internal const val PLAYER_CHAPTER_TICK_TOP_RATIO = 0.35f
internal const val PLAYER_CHAPTER_TICK_BOTTOM_RATIO = 0.65f
internal const val PLAYER_TOP_SCRIM_ALPHA = 0.6f
internal const val PLAYER_BOTTOM_SCRIM_ALPHA = 0.85f
internal const val PLAYER_CONTROL_BACKGROUND_ALPHA = 0.1f
internal const val PLAYER_PRIMARY_CONTROL_BACKGROUND_ALPHA = 0.24f
internal const val PLAYER_PICKER_PANEL_WIDTH_FRACTION = 0.92f
internal const val PLAYER_PICKER_SURFACE_ALPHA = 0.95f
internal const val PLAYER_PICKER_DIVIDER_ALPHA = 0.12f
internal const val PLAYER_PICKER_SELECTED_ROW_ALPHA = 0.16f
internal const val PLAYER_NOTICE_SURFACE_ALPHA = 0.92f
internal const val PLAYER_DEBUG_OVERLAY_ALPHA = 0.72f

internal const val PLAYER_DEBUG_OVERLAY_WIDTH_FRACTION = 0.6f
internal const val PLAYER_DEBUG_OVERLAY_HEIGHT_FRACTION = 0.6f
internal const val PLAYER_DEBUG_LABEL_ALPHA = 0.6f
internal const val PLAYER_DEBUG_LABEL_WEIGHT = 0.42f
internal const val PLAYER_DEBUG_VALUE_WEIGHT = 0.58f
internal const val SECONDS_PER_MINUTE = 60L
internal const val SECONDS_PER_HOUR = 3_600L
