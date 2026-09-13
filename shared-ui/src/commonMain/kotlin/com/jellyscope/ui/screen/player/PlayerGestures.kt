// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.rememberAsyncImagePainter
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.safeDiagnosticType
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

private val trickplayPreviewLogger = diagnosticLogger(DiagnosticTag.TrickplayPreview)

@Composable
internal fun TrickplayPreview(
    trickplay: TrickplayInfo?,
    tileUrls: List<String>,
    positionMs: Long?,
    session: Session,
    modifier: Modifier = Modifier,
) {
    if (positionMs == null) {
        return
    }
    if (trickplay == null || tileUrls.isEmpty()) {
        val result = if (trickplay == null) "metadata-missing" else "tiles-missing"
        LaunchedEffect(result) {
            trickplayPreviewLogger.i {
                "stage=trickplay event=availability platform=shared surface=player result=$result"
            }
        }
        return
    }

    val thumbnailIndex = trickplay.thumbnailIndexForPositionMs(positionMs)
    val tileIndex = thumbnailIndex / trickplay.thumbnailsPerTile
    val tileUrl = tileUrls.getOrNull(tileIndex)
    if (tileUrl == null) {
        LaunchedEffect(tileIndex) {
            trickplayPreviewLogger.w {
                "stage=trickplay event=availability platform=shared surface=player result=tile-missing " +
                    "tileIndex=$tileIndex"
            }
        }
        return
    }
    val indexInTile = thumbnailIndex % trickplay.thumbnailsPerTile
    val column = indexInTile % trickplay.tileWidth.coerceAtLeast(1)
    val row = indexInTile / trickplay.tileWidth.coerceAtLeast(1)
    val thumbnailAspect = trickplay.thumbnailWidth.toFloat() / trickplay.thumbnailHeight.coerceAtLeast(1).toFloat()
    val tileRequest = authenticatedImageRequest(tileUrl, session, decode = null)
    var tileLoaded by remember(tileRequest) { mutableStateOf(false) }
    val tilePainter =
        rememberAsyncImagePainter(
            model = tileRequest,
            onLoading = {
                tileLoaded = false
                trickplayPreviewLogger.i {
                    "stage=trickplay event=request platform=shared surface=player result=loading " +
                        "tileIndex=$tileIndex thumbnailWidth=${trickplay.thumbnailWidth} " +
                        "thumbnailHeight=${trickplay.thumbnailHeight} tileColumns=${trickplay.tileWidth} " +
                        "tileRows=${trickplay.tileHeight} cropColumn=$column cropRow=$row"
                }
            },
            onSuccess = { state ->
                val result = state.result
                val image = result.image
                trickplayPreviewLogger.i {
                    "stage=trickplay event=request platform=shared surface=player result=success " +
                        "tileIndex=$tileIndex thumbnailWidth=${trickplay.thumbnailWidth} " +
                        "thumbnailHeight=${trickplay.thumbnailHeight} tileColumns=${trickplay.tileWidth} " +
                        "tileRows=${trickplay.tileHeight} cropColumn=$column cropRow=$row " +
                        "decodedWidth=${image.width} decodedHeight=${image.height} " +
                        "dataSource=${result.dataSource.name} sampled=${result.isSampled}"
                }
                tileLoaded = true
            },
            onError = { state ->
                tileLoaded = false
                trickplayPreviewLogger.w {
                    "stage=trickplay event=request platform=shared surface=player result=failure " +
                        "tileIndex=$tileIndex thumbnailWidth=${trickplay.thumbnailWidth} " +
                        "thumbnailHeight=${trickplay.thumbnailHeight} tileColumns=${trickplay.tileWidth} " +
                        "tileRows=${trickplay.tileHeight} cropColumn=$column cropRow=$row " +
                        "exceptionType=${state.result.throwable.safeDiagnosticType()}"
                }
            },
            contentScale = ContentScale.FillBounds,
        )
    if (!tileLoaded) {
        return
    }

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
            Canvas(modifier = Modifier.fillMaxSize()) {
                val frameWidth = size.width
                val frameHeight = size.height
                val sheetSize =
                    Size(
                        width = frameWidth * trickplay.tileWidth.coerceAtLeast(1),
                        height = frameHeight * trickplay.tileHeight.coerceAtLeast(1),
                    )
                translate(
                    left = -frameWidth * column,
                    top = -frameHeight * row,
                ) {
                    with(tilePainter) { draw(size = sheetSize) }
                }
            }
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
    volumeAndBrightnessEnabled: Boolean = true,
    playbackGesturesEnabled: Boolean = true,
    fullscreenDragDirection: PlayerFullscreenDragDirection = PlayerFullscreenDragDirection.Down,
    onFullscreenDrag: ((distancePx: Float, videoHeightPx: Int) -> Unit)? = null,
    onFullscreenDragEnd: (cancelled: Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val gestureController = if (volumeAndBrightnessEnabled) rememberPlayerGestureController() else null
    val currentContent by rememberUpdatedState(content)
    val currentPlaybackState by rememberUpdatedState(playbackState)
    val currentOnToggleControls by rememberUpdatedState(onToggleControls)
    val currentOnToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentOnSetPlaybackSpeed by rememberUpdatedState(onSetPlaybackSpeed)
    val currentOnHud by rememberUpdatedState(onHud)
    val currentVolumeAndBrightnessEnabled by rememberUpdatedState(volumeAndBrightnessEnabled)
    val currentPlaybackGesturesEnabled by rememberUpdatedState(playbackGesturesEnabled)
    val currentOnFullscreenDrag by rememberUpdatedState(onFullscreenDrag)
    val currentOnFullscreenDragEnd by rememberUpdatedState(onFullscreenDragEnd)
    var gestureCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val systemGestures = WindowInsets.systemGestures
    val currentSystemGestureLeft by rememberUpdatedState(systemGestures.getLeft(density, layoutDirection))
    val currentSystemGestureTop by rememberUpdatedState(systemGestures.getTop(density))
    val currentSystemGestureRight by rememberUpdatedState(systemGestures.getRight(density, layoutDirection))
    val currentSystemGestureBottom by rememberUpdatedState(systemGestures.getBottom(density))

    Box(
        modifier =
            modifier
                .onGloballyPositioned {
                    gestureCoordinates = it
                }.pointerInput(volumeAndBrightnessEnabled, playbackGesturesEnabled, fullscreenDragDirection) {
                    coroutineScope {
                        var lastTapUptimeMs = 0L
                        var lastTapZone: PlayerTapZone? = null
                        var lastMouseClickUptimeMs = 0L
                        var singleTapJob: Job? = null
                        var singleMouseClickJob: Job? = null

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val initialVideoHeightPx = size.height
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
                                    if (!currentPlaybackGesturesEnabled) {
                                        currentOnToggleControls()
                                        return@awaitEachGesture
                                    }
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
                            val downInRoot = gestureCoordinates?.takeIf { it.isAttached }?.localToRoot(down.position) ?: down.position
                            var fullscreenDragActive = false
                            var released = false
                            var moved = false
                            var verticalMode: PlayerVerticalGesture? = null
                            var speedBoostActive = false
                            val originalSpeed = currentContent.playbackSpeed
                            val longPressJob =
                                launch {
                                    delay(LONG_PRESS_SPEED_DELAY_MS)
                                    if (currentPlaybackGesturesEnabled && !moved && !ignored) {
                                        speedBoostActive = true
                                        currentOnSetPlaybackSpeed(TEMPORARY_LONG_PRESS_SPEED)
                                        currentOnHud(PlayerGestureHud.Message(PlayerGestureHudType.SpeedBoost))
                                    }
                                }

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { pointer -> pointer.id == down.id } ?: break
                                    val totalOffset =
                                        if (currentOnFullscreenDrag != null) {
                                            val rootPosition = gestureCoordinates?.takeIf { it.isAttached }?.localToRoot(change.position)
                                            (rootPosition ?: change.position) - downInRoot
                                        } else {
                                            change.position - down.position
                                        }
                                    if (currentOnFullscreenDrag != null && (change.isConsumed || event.changes.count { it.pressed } > 1)) {
                                        moved = true
                                        break
                                    }
                                    if (change.pressed) {
                                        if (!moved && totalOffset.getDistance() > PLAYER_GESTURE_TAP_SLOP_PX) {
                                            moved = true
                                            longPressJob.cancel()
                                        }
                                        if (!ignored) {
                                            if (
                                                verticalMode == null &&
                                                !fullscreenDragActive &&
                                                abs(totalOffset.y) >= PLAYER_GESTURE_MIN_DISTANCE_PX &&
                                                abs(totalOffset.y) >= abs(totalOffset.x) * PLAYER_VERTICAL_DOMINANCE_RATIO
                                            ) {
                                                if (currentOnFullscreenDrag != null && totalOffset.y * fullscreenDragDirection.sign > 0f) {
                                                    fullscreenDragActive = true
                                                } else if (currentVolumeAndBrightnessEnabled) {
                                                    verticalMode =
                                                        if (down.position.x < size.width / 2f) {
                                                            gestureController?.beginBrightness()
                                                            PlayerVerticalGesture.Brightness
                                                        } else {
                                                            gestureController?.beginVolume()
                                                            PlayerVerticalGesture.Volume
                                                        }
                                                }
                                                moved = true
                                                longPressJob.cancel()
                                                singleTapJob?.cancel()
                                            }

                                            if (fullscreenDragActive) {
                                                currentOnFullscreenDrag?.invoke(totalOffset.y, initialVideoHeightPx)
                                                change.consume()
                                            }
                                            val mode = verticalMode
                                            if (mode != null) {
                                                val distanceFull = (size.height * FULL_VERTICAL_SWIPE_HEIGHT_RATIO).coerceAtLeast(1f)
                                                val deltaFraction = (down.position.y - change.position.y) / distanceFull
                                                when (mode) {
                                                    PlayerVerticalGesture.Brightness ->
                                                        gestureController?.updateBrightness(deltaFraction)?.let { value ->
                                                            currentOnHud(
                                                                PlayerGestureHud.Meter(
                                                                    type = PlayerGestureHudType.Brightness,
                                                                    fraction = value,
                                                                ),
                                                            )
                                                        }
                                                    PlayerVerticalGesture.Volume ->
                                                        gestureController?.updateVolume(deltaFraction)?.let { value ->
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
                                        if (fullscreenDragActive) currentOnFullscreenDrag?.invoke(totalOffset.y, initialVideoHeightPx)
                                        released = true
                                        break
                                    }
                                }
                            } finally {
                                if (fullscreenDragActive) currentOnFullscreenDragEnd(!released)
                                longPressJob.cancel()
                                if (verticalMode != null) {
                                    gestureController?.endGesture()
                                }
                                if (speedBoostActive) {
                                    currentOnSetPlaybackSpeed(originalSpeed)
                                }
                            }

                            if (!moved && !ignored && !speedBoostActive) {
                                if (!currentPlaybackGesturesEnabled) {
                                    currentOnToggleControls()
                                    return@awaitEachGesture
                                }
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
                                                    playbackState.durationMs?.let { duration -> position.coerceAtMost(duration) }
                                                        ?: position
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

internal enum class PlayerFullscreenDragDirection(
    val sign: Float,
) {
    Up(-1f),
    Down(1f),
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
