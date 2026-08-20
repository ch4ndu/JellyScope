// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_brightness
import com.jellyscope.ui.generated.resources.player_percent_value
import com.jellyscope.ui.generated.resources.player_seek_backward_hud
import com.jellyscope.ui.generated.resources.player_seek_forward_hud
import com.jellyscope.ui.generated.resources.player_speed_boost
import com.jellyscope.ui.generated.resources.player_trickplay_cd
import com.jellyscope.ui.generated.resources.player_volume
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun TrickplayPreview(
    trickplay: TrickplayInfo?,
    tileUrls: List<String>,
    positionMs: Long?,
    session: Session,
    modifier: Modifier = Modifier,
) {
    if (trickplay == null || positionMs == null || tileUrls.isEmpty()) {
        return
    }

    val thumbnailIndex = trickplay.thumbnailIndexForPositionMs(positionMs)
    val tileIndex = thumbnailIndex / trickplay.thumbnailsPerTile
    val tileUrl = tileUrls.getOrNull(tileIndex) ?: return
    val indexInTile = thumbnailIndex % trickplay.thumbnailsPerTile
    val column = indexInTile % trickplay.tileWidth.coerceAtLeast(1)
    val row = indexInTile / trickplay.tileWidth.coerceAtLeast(1)
    val thumbnailAspect = trickplay.thumbnailWidth.toFloat() / trickplay.thumbnailHeight.coerceAtLeast(1).toFloat()
    val tileAspect =
        (trickplay.thumbnailWidth * trickplay.tileWidth).toFloat() /
            (trickplay.thumbnailHeight * trickplay.tileHeight).coerceAtLeast(1).toFloat()
    val density = LocalDensity.current
    val previewWidthPx = with(density) { Dimensions.playerTrickplayPreviewWidth.roundToPx() }
    val previewHeightPx = (previewWidthPx / thumbnailAspect).roundToInt()
    val trickplayContentDescription = stringResource(Res.string.player_trickplay_cd)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Box(
            modifier =
                Modifier
                    .padding(Dimensions.contentSpacing)
                    .width(Dimensions.playerTrickplayPreviewWidth)
                    .aspectRatio(thumbnailAspect)
                    .clip(MaterialTheme.shapes.small)
                    .semantics { contentDescription = trickplayContentDescription },
        ) {
            AsyncImage(
                // Sprite sheets are cropped, never downscaled.
                model = authenticatedImageRequest(tileUrl, session, decode = null),
                contentDescription = trickplayContentDescription,
                contentScale = ContentScale.FillBounds,
                modifier =
                    Modifier
                        .offset {
                            IntOffset(
                                x = -previewWidthPx * column,
                                y = -previewHeightPx * row,
                            )
                        }.width(
                            Dimensions.playerTrickplayPreviewWidth *
                                trickplay.tileWidth.coerceAtLeast(1).toFloat(),
                        ).aspectRatio(tileAspect),
            )
        }
    }
}

@Composable
internal fun GestureHud(
    hud: PlayerGestureHud?,
    modifier: Modifier = Modifier,
) {
    if (hud == null) {
        return
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        val text =
            when (hud) {
                is PlayerGestureHud.Meter ->
                    stringResource(
                        Res.string.player_percent_value,
                        stringResource(hud.type.label),
                        (hud.fraction * 100f).roundToInt(),
                    )
                is PlayerGestureHud.Message -> stringResource(hud.type.label)
            }
        Text(
            text = text,
            modifier = Modifier.padding(Dimensions.formSpacing),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun PlayerGestureLayer(
    content: PlayerUiState.Content,
    playbackState: () -> PlaybackState,
    onToggleControls: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onHud: (PlayerGestureHud?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gestureController = rememberPlayerGestureController()
    val currentContent by rememberUpdatedState(content)
    val currentPlaybackState by rememberUpdatedState(playbackState)
    val currentOnToggleControls by rememberUpdatedState(onToggleControls)
    val currentOnToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentOnSetPlaybackSpeed by rememberUpdatedState(onSetPlaybackSpeed)
    val currentOnHud by rememberUpdatedState(onHud)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val systemGestures = WindowInsets.systemGestures
    val currentSystemGestureLeft by rememberUpdatedState(systemGestures.getLeft(density, layoutDirection))
    val currentSystemGestureTop by rememberUpdatedState(systemGestures.getTop(density))
    val currentSystemGestureRight by rememberUpdatedState(systemGestures.getRight(density, layoutDirection))
    val currentSystemGestureBottom by rememberUpdatedState(systemGestures.getBottom(density))

    Box(
        modifier =
            modifier.pointerInput(Unit) {
                coroutineScope {
                    var lastTapUptimeMs = 0L
                    var lastTapZone: PlayerTapZone? = null
                    var lastMouseClickUptimeMs = 0L
                    var singleTapJob: Job? = null
                    var singleMouseClickJob: Job? = null

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val ignored =
                            down.position.isGestureExcluded(
                                width = size.width,
                                height = size.height,
                                systemGestureLeft = currentSystemGestureLeft,
                                systemGestureTop = currentSystemGestureTop,
                                systemGestureRight = currentSystemGestureRight,
                                systemGestureBottom = currentSystemGestureBottom,
                            )
                        // Delay mouse clicks so double-click can own fullscreen.
                        if (down.type == PointerType.Mouse) {
                            var mouseMoved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { pointer -> pointer.id == down.id } ?: break
                                if ((change.position - down.position).getDistance() > PLAYER_GESTURE_TAP_SLOP_PX) {
                                    mouseMoved = true
                                }
                                if (!change.pressed) {
                                    break
                                }
                            }
                            if (!mouseMoved && !ignored) {
                                val isDoubleClick = down.uptimeMillis - lastMouseClickUptimeMs <= DOUBLE_TAP_TIMEOUT_MS
                                if (isDoubleClick) {
                                    singleMouseClickJob?.cancel()
                                    lastMouseClickUptimeMs = 0L
                                    currentOnToggleFullscreen()
                                } else {
                                    lastMouseClickUptimeMs = down.uptimeMillis
                                    singleMouseClickJob?.cancel()
                                    singleMouseClickJob =
                                        launch {
                                            delay(DOUBLE_TAP_TIMEOUT_MS)
                                            currentOnToggleControls()
                                        }
                                }
                            }
                            return@awaitEachGesture
                        }
                        var moved = false
                        var verticalMode: PlayerVerticalGesture? = null
                        var speedBoostActive = false
                        val originalSpeed = currentContent.playbackSpeed
                        val longPressJob =
                            launch {
                                delay(LONG_PRESS_SPEED_DELAY_MS)
                                if (!moved && !ignored) {
                                    speedBoostActive = true
                                    currentOnSetPlaybackSpeed(TEMPORARY_LONG_PRESS_SPEED)
                                    currentOnHud(PlayerGestureHud.Message(PlayerGestureHudType.SpeedBoost))
                                }
                            }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { pointer -> pointer.id == down.id } ?: break
                                val totalOffset = change.position - down.position
                                if (change.pressed) {
                                    if (!moved && totalOffset.getDistance() > PLAYER_GESTURE_TAP_SLOP_PX) {
                                        moved = true
                                        longPressJob.cancel()
                                    }
                                    if (!ignored) {
                                        if (
                                            verticalMode == null &&
                                            abs(totalOffset.y) >= PLAYER_GESTURE_MIN_DISTANCE_PX &&
                                            abs(totalOffset.y) >= abs(totalOffset.x) * PLAYER_VERTICAL_DOMINANCE_RATIO
                                        ) {
                                            verticalMode =
                                                if (down.position.x < size.width / 2f) {
                                                    gestureController.beginBrightness()
                                                    PlayerVerticalGesture.Brightness
                                                } else {
                                                    gestureController.beginVolume()
                                                    PlayerVerticalGesture.Volume
                                                }
                                            moved = true
                                            longPressJob.cancel()
                                        }

                                        val mode = verticalMode
                                        if (mode != null) {
                                            val distanceFull = (size.height * FULL_VERTICAL_SWIPE_HEIGHT_RATIO).coerceAtLeast(1f)
                                            val deltaFraction = (down.position.y - change.position.y) / distanceFull
                                            when (mode) {
                                                PlayerVerticalGesture.Brightness ->
                                                    gestureController.updateBrightness(deltaFraction)?.let { value ->
                                                        currentOnHud(
                                                            PlayerGestureHud.Meter(
                                                                type = PlayerGestureHudType.Brightness,
                                                                fraction = value,
                                                            ),
                                                        )
                                                    }
                                                PlayerVerticalGesture.Volume ->
                                                    gestureController.updateVolume(deltaFraction)?.let { value ->
                                                        currentOnHud(
                                                            PlayerGestureHud.Meter(
                                                                type = PlayerGestureHudType.Volume,
                                                                fraction = value,
                                                            ),
                                                        )
                                                    }
                                            }
                                            change.consume()
                                        }
                                    }
                                }
                                if (!change.pressed) {
                                    break
                                }
                            }
                        } finally {
                            longPressJob.cancel()
                            if (verticalMode != null) {
                                gestureController.endGesture()
                            }
                            if (speedBoostActive) {
                                currentOnSetPlaybackSpeed(originalSpeed)
                            }
                        }

                        if (!moved && !ignored && !speedBoostActive) {
                            val zone = down.position.tapZone(width = size.width)
                            val action =
                                playerTapAction(
                                    zone = zone,
                                    lastZone = lastTapZone,
                                    msSinceLastTap =
                                        if (lastTapZone == null) {
                                            null
                                        } else {
                                            down.uptimeMillis - lastTapUptimeMs
                                        },
                                )
                            singleTapJob?.cancel()
                            when (action) {
                                PlayerTapAction.ToggleControlsNow -> {
                                    lastTapUptimeMs = down.uptimeMillis
                                    lastTapZone = zone
                                    currentOnToggleControls()
                                }
                                PlayerTapAction.ScheduleToggleControls -> {
                                    lastTapUptimeMs = down.uptimeMillis
                                    lastTapZone = zone
                                    singleTapJob =
                                        launch {
                                            delay(DOUBLE_TAP_TIMEOUT_MS)
                                            currentOnToggleControls()
                                        }
                                }
                                PlayerTapAction.SeekBackward,
                                PlayerTapAction.SeekForward,
                                -> {
                                    lastTapUptimeMs = 0L
                                    lastTapZone = null
                                    val deltaMs =
                                        if (action == PlayerTapAction.SeekForward) {
                                            DOUBLE_TAP_SEEK_MS
                                        } else {
                                            -DOUBLE_TAP_SEEK_MS
                                        }
                                    val playbackState = currentPlaybackState()
                                    val target =
                                        (playbackState.positionMs + deltaMs)
                                            .coerceAtLeast(0L)
                                            .let { position ->
                                                playbackState.durationMs?.let { duration -> position.coerceAtMost(duration) } ?: position
                                            }
                                    currentOnSeekTo(target)
                                    currentOnHud(
                                        PlayerGestureHud.Message(
                                            if (deltaMs > 0L) {
                                                PlayerGestureHudType.SeekForward
                                            } else {
                                                PlayerGestureHudType.SeekBackward
                                            },
                                        ),
                                    )
                                }
                                PlayerTapAction.Ignore -> {
                                    lastTapUptimeMs = 0L
                                    lastTapZone = null
                                }
                            }
                        }
                    }
                }
            },
    )
}

internal sealed interface PlayerGestureHud {
    data class Meter(
        val type: PlayerGestureHudType,
        val fraction: Float,
    ) : PlayerGestureHud

    data class Message(
        val type: PlayerGestureHudType,
    ) : PlayerGestureHud
}

internal enum class PlayerGestureHudType(
    val label: StringResource,
) {
    Brightness(Res.string.player_brightness),
    Volume(Res.string.player_volume),
    SeekForward(Res.string.player_seek_forward_hud),
    SeekBackward(Res.string.player_seek_backward_hud),
    SpeedBoost(Res.string.player_speed_boost),
}

internal enum class PlayerVerticalGesture {
    Brightness,
    Volume,
}

// System gesture insets may widen these minimum excluded edges.
private fun Offset.isGestureExcluded(
    width: Int,
    height: Int,
    systemGestureLeft: Int,
    systemGestureTop: Int,
    systemGestureRight: Int,
    systemGestureBottom: Int,
): Boolean {
    val topExclusion = max(height * PLAYER_GESTURE_VERTICAL_EXCLUSION_RATIO, systemGestureTop.toFloat())
    val bottomExclusion = max(height * PLAYER_GESTURE_VERTICAL_EXCLUSION_RATIO, systemGestureBottom.toFloat())
    val leftExclusion = max(width * PLAYER_GESTURE_HORIZONTAL_EXCLUSION_RATIO, systemGestureLeft.toFloat())
    val rightExclusion = max(width * PLAYER_GESTURE_HORIZONTAL_EXCLUSION_RATIO, systemGestureRight.toFloat())
    return y < topExclusion ||
        y > height - bottomExclusion ||
        x < leftExclusion ||
        x > width - rightExclusion
}

private fun Offset.tapZone(width: Int): PlayerTapZone =
    when {
        x < width * PLAYER_DOUBLE_TAP_SIDE_ZONE_RATIO -> PlayerTapZone.Left
        x > width * (1f - PLAYER_DOUBLE_TAP_SIDE_ZONE_RATIO) -> PlayerTapZone.Right
        else -> PlayerTapZone.Center
    }

private fun TrickplayInfo.thumbnailIndexForPositionMs(positionMs: Long): Int {
    if (intervalMs <= 0L || thumbnailCount <= 0) {
        return 0
    }
    return positionMs
        .coerceAtLeast(0L)
        .div(intervalMs)
        .coerceAtMost((thumbnailCount - 1).toLong())
        .toInt()
}
