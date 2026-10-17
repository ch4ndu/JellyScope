// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_seek_cd
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SeekSlider(
    positionMs: Long,
    durationMs: Long?,
    bufferedPositionMs: Long,
    chapters: List<Chapter>,
    onPositionChange: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onScrubPreviewPositionChange: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onScrubActiveChange: (Boolean) -> Unit = {},
) {
    // Preserve the track range while native re-prepare briefly clears duration.
    var lastKnownDurationMs by remember { mutableStateOf(durationMs?.takeIf { it > 0L }) }
    LaunchedEffect(durationMs) {
        durationMs?.takeIf { it > 0L }?.let { lastKnownDurationMs = it }
    }
    val maxPosition = (durationMs?.takeIf { it > 0L } ?: lastKnownDurationMs) ?: positionMs.coerceAtLeast(1L)
    val seekContentDescription = stringResource(Res.string.player_seek_cd)
    val chapterTickColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val bufferedFraction =
        durationMs
            ?.takeIf { duration -> duration > 0L && bufferedPositionMs > 0L }
            ?.let { duration -> bufferedPositionMs.coerceIn(0L, duration).toFloat() / duration.toFloat() }
    var latestScrubPositionMs by remember { mutableLongStateOf(positionMs.coerceIn(0L, maxPosition)) }
    var committedScrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    // Read the latest duration without restarting the interaction collector.
    val currentMaxPosition by rememberUpdatedState(maxPosition)

    fun commitLatestScrubPosition() {
        val targetPositionMs = latestScrubPositionMs.coerceIn(0L, currentMaxPosition)
        if (committedScrubPositionMs != targetPositionMs) {
            currentOnSeekTo(targetPositionMs)
            committedScrubPositionMs = targetPositionMs
        }
        currentOnScrubFinished()
    }

    LaunchedEffect(positionMs, maxPosition) {
        latestScrubPositionMs = positionMs.coerceIn(0L, maxPosition)
    }
    val currentOnScrubActiveChange by rememberUpdatedState(onScrubActiveChange)
    // Restarting on maxPosition can lose Release and leave scrubbing active.
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    committedScrubPositionMs = null
                    currentOnScrubActiveChange(true)
                }
                is PressInteraction.Release -> {
                    commitLatestScrubPosition()
                    currentOnScrubActiveChange(false)
                }
                is PressInteraction.Cancel -> {
                    currentOnScrubFinished()
                    currentOnScrubActiveChange(false)
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                // Cache chapter geometry; dense assets can contain hundreds of ticks.
                .drawWithCache {
                    val tickPoints =
                        chapterTickPoints(
                            chapters = chapters,
                            durationMs = durationMs?.takeIf { value -> value > 0L } ?: 0L,
                            width = size.width,
                            top = size.height * PLAYER_CHAPTER_TICK_TOP_RATIO,
                            bottom = size.height * PLAYER_CHAPTER_TICK_BOTTOM_RATIO,
                        )
                    val tickStrokeWidth = Dimensions.progressIndicatorStroke.toPx()
                    onDrawWithContent {
                        drawContent()
                        if (tickPoints.isNotEmpty()) {
                            drawPoints(
                                points = tickPoints,
                                pointMode = PointMode.Lines,
                                color = chapterTickColor,
                                strokeWidth = tickStrokeWidth,
                            )
                        }
                    }
                },
    ) {
        Slider(
            value = positionMs.coerceIn(0L, maxPosition).toFloat(),
            onValueChange = { value ->
                val scrubPosition = value.toLong().coerceIn(0L, maxPosition)
                latestScrubPositionMs = scrubPosition
                committedScrubPositionMs = null
                onPositionChange(scrubPosition)
                onScrubPreviewPositionChange(scrubPosition)
            },
            onValueChangeFinished = {
                commitLatestScrubPosition()
            },
            interactionSource = interactionSource,
            valueRange = 0f..maxPosition.toFloat(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = seekContentDescription },
            thumb = {
                ThinSliderThumb(
                    interactionSource = interactionSource,
                    restingSize = Dimensions.playerSeekThumbSize,
                    activeSize = Dimensions.playerSeekThumbActiveSize,
                )
            },
            track = { sliderState ->
                ThinSliderTrack(
                    progressFraction = sliderState.value / maxPosition.toFloat(),
                    bufferedFraction = bufferedFraction,
                )
            },
        )
    }
}

/** Circular handle animated by the owning Slider's [interactionSource]. */
@Composable
internal fun ThinSliderThumb(
    interactionSource: InteractionSource,
    restingSize: Dp,
    activeSize: Dp,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val thumbSize by animateDpAsState(
        targetValue =
            if (pressed || dragged) {
                activeSize
            } else {
                restingSize
            },
        label = "player-slider-thumb-size",
    )
    Box(
        modifier =
            Modifier
                .height(Dimensions.minTouchTarget)
                .width(thumbSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(thumbSize)
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
        )
    }
}

/** Thin shared seek/volume track; null [bufferedFraction] omits buffered progress. */
@Composable
internal fun ThinSliderTrack(
    progressFraction: Float,
    bufferedFraction: Float?,
) {
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    val bufferedColor = Color.White.copy(alpha = 0.35f)
    val activeColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(Dimensions.progressBarHeight),
    ) {
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val start = if (isRtl) Offset(size.width, center.y) else Offset(0f, center.y)
        val end = if (isRtl) Offset(0f, center.y) else Offset(size.width, center.y)
        val strokeWidth = size.height

        drawLine(
            color = inactiveColor,
            start = start,
            end = end,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        bufferedFraction?.takeIf { fraction -> fraction > 0f }?.let { fraction ->
            drawLine(
                color = bufferedColor,
                start = start,
                end = start.trackOffset(end, fraction),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        drawLine(
            color = activeColor,
            start = start,
            end = start.trackOffset(end, progressFraction),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

internal fun Offset.trackOffset(
    end: Offset,
    fraction: Float,
): Offset =
    Offset(
        x = x + (end.x - x) * fraction.coerceIn(0f, 1f),
        y = y + (end.y - y) * fraction.coerceIn(0f, 1f),
    )
