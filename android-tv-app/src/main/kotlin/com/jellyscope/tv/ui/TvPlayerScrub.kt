// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.rememberAsyncImagePainter
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.safeDiagnosticType
import com.jellyscope.tv.R
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.screen.player.HoldSeekDirection
import com.jellyscope.ui.screen.player.chapterTickPoints
import kotlinx.coroutines.flow.StateFlow
import com.jellyscope.ui.component.AdaptiveProgressBar as TvProgressBar
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

private val tvTrickplayLogger = diagnosticLogger(DiagnosticTag.TrickplayPreview)
private const val TRICKPLAY_PREVIEW_TRANSITION_MS = 140

@Composable
internal fun ColumnScope.TvPlayerScrubSection(
    session: Session,
    currentItemId: String,
    trickplay: TrickplayInfo?,
    chapters: List<Chapter>,
    playbackStateFlow: StateFlow<PlaybackState>,
    seekRequester: FocusRequester,
    playRequester: FocusRequester,
    // A provider, not a value: repeated hold targets invalidate only this
    // section. The provider also retains the final target through rebuffering.
    seekPreviewTargetMs: () -> Long?,
    onSeekKeyDown: (HoldSeekDirection) -> Unit,
) {
    val playbackState by playbackStateFlow.collectAsStateWithLifecycle()
    val previewTargetMs = seekPreviewTargetMs()
    // An active or buffering-retained target owns the presentation: the bar,
    // thumb, time row, and thumbnail all follow it instead of live position.
    val displayPositionMs = previewTargetMs ?: playbackState.positionMs
    Column {
        key(currentItemId) {
            AnimatedContent(
                targetState = previewTargetMs,
                transitionSpec = {
                    (
                        fadeIn(tween(TRICKPLAY_PREVIEW_TRANSITION_MS)) togetherWith
                            fadeOut(tween(TRICKPLAY_PREVIEW_TRANSITION_MS))
                    ).using(
                        SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ -> tween(TRICKPLAY_PREVIEW_TRANSITION_MS) },
                        ),
                    )
                },
                contentAlignment = Alignment.BottomCenter,
                contentKey = { target -> target != null },
                label = "tv-trickplay-preview",
            ) { animatedPreviewTargetMs ->
                if (animatedPreviewTargetMs != null) {
                    val activeTrickplay = trickplay
                    if (activeTrickplay == null) {
                        LaunchedEffect(currentItemId) {
                            tvTrickplayLogger.i {
                                "stage=trickplay event=availability platform=android surface=tv " +
                                    "result=metadata-missing"
                            }
                        }
                    } else {
                        TvTrickplayThumbnail(
                            session = session,
                            itemId = currentItemId,
                            trickplay = activeTrickplay,
                            positionMs = animatedPreviewTargetMs,
                            positionFraction =
                                playbackState.durationMs
                                    ?.takeIf { duration -> duration > 0L }
                                    ?.let { duration ->
                                        animatedPreviewTargetMs.coerceIn(0L, duration).toFloat() / duration.toFloat()
                                    } ?: 0.5f,
                            modifier = Modifier.padding(bottom = TvDimens.playerOverlayGap),
                        )
                    }
                }
            }
        }
        TvPlayerSeekBar(
            playbackState = playbackState,
            displayPositionMs = displayPositionMs,
            chapters = chapters,
            seekRequester = seekRequester,
            playRequester = playRequester,
            onSeekKeyDown = onSeekKeyDown,
        )
    }
    TvPlayerTimeRow(
        playbackState = playbackState,
        displayPositionMs = displayPositionMs,
    )
}

@Composable
internal fun TvPlayerSeekBar(
    playbackState: PlaybackState,
    displayPositionMs: Long,
    chapters: List<Chapter>,
    seekRequester: FocusRequester,
    playRequester: FocusRequester,
    onSeekKeyDown: (HoldSeekDirection) -> Unit,
) {
    val durationMs = playbackState.durationMs
    val progressRangeEnd = durationMs?.takeIf { duration -> duration > 0L } ?: 1L
    val progressPosition = displayPositionMs.coerceIn(0L, progressRangeEnd)
    val progress =
        durationMs
            ?.takeIf { duration -> duration > 0L }
            ?.let { duration -> progressPosition.toFloat() / duration.toFloat() }
            ?: 0f
    val seekBarContentDescription = stringResource(R.string.tv_player_seek_cd)
    var seekFocused by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TvDimens.playerSeekFocusHeight)
                .focusRequester(seekRequester)
                .focusProperties { down = playRequester }
                .onFocusChanged { state ->
                    seekFocused = state.isFocused
                }.focusable()
                .semantics {
                    contentDescription = seekBarContentDescription
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            current = progressPosition.toFloat(),
                            range = 0f..progressRangeEnd.toFloat(),
                        )
                }.onPreviewKeyEvent { event ->
                    // Key-up commits are owned by the player root handler so a
                    // hold survives focus moves; only key-downs start/advance
                    // the pending target here.
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onSeekKeyDown(HoldSeekDirection.Backward)
                            true
                        }
                        Key.DirectionRight -> {
                            onSeekKeyDown(HoldSeekDirection.Forward)
                            true
                        }
                        else -> false
                    }
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        TvProgressBar(
            progress = progress,
            height = if (seekFocused) TvDimens.playerSeekFocusedTrack else TvDimens.progressHeight,
            secondaryProgress =
                durationMs
                    ?.takeIf { duration -> duration > 0L }
                    ?.let { duration ->
                        playbackState.bufferedPositionMs.toFloat() / duration.toFloat()
                    },
        )
        if (durationMs != null && durationMs > 0L && chapters.isNotEmpty()) {
            // Same treatment as the shared seek bar: geometry built once per
            // change, and one draw op for the whole tick set instead of one per
            // chapter. See chapterTickPoints.
            Spacer(
                modifier =
                    Modifier.fillMaxSize().drawWithCache {
                        val tickHeight = TvDimens.playerChapterTickHeight.toPx()
                        val tickPoints =
                            chapterTickPoints(
                                chapters = chapters,
                                durationMs = durationMs,
                                width = size.width,
                                top = (size.height - tickHeight) / 2f,
                                bottom = (size.height + tickHeight) / 2f,
                            )
                        val tickStrokeWidth = TvDimens.playerChapterTickWidth.toPx()
                        onDrawBehind {
                            if (tickPoints.isNotEmpty()) {
                                drawPoints(
                                    points = tickPoints,
                                    pointMode = PointMode.Lines,
                                    color = Color.White.copy(alpha = 0.72f),
                                    strokeWidth = tickStrokeWidth,
                                )
                            }
                        }
                    },
            )
        }
        if (seekFocused) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White,
                    radius = TvDimens.playerSeekThumbRadius.toPx(),
                    center =
                        Offset(
                            size.width * progress.coerceIn(0f, 1f),
                            size.height / 2f,
                        ),
                )
            }
        }
    }
}

@Composable
internal fun TvPlayerTimeRow(
    playbackState: PlaybackState,
    displayPositionMs: Long = playbackState.positionMs,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText(
            text = formatDuration(displayPositionMs),
            style = TvSecondaryStyle,
            maxLines = 1,
        )
        TvText(
            text =
                "-${formatDuration(
                    playbackState.durationMs?.let { duration -> (duration - displayPositionMs).coerceAtLeast(0L) },
                )}",
            style = TvSecondaryStyle,
            maxLines = 1,
        )
    }
}

@Composable
internal fun TvTrickplayThumbnail(
    session: Session,
    itemId: String,
    trickplay: TrickplayInfo,
    positionMs: Long,
    positionFraction: Float,
    modifier: Modifier = Modifier,
) {
    val frame = trickplay.frameForPositionMs(positionMs)
    if (itemId.isBlank() || frame == null) {
        LaunchedEffect(itemId.isBlank(), trickplay) {
            tvTrickplayLogger.w {
                "stage=trickplay event=availability platform=android surface=tv result=invalid-input"
            }
        }
        return
    }
    val tileUrl =
        remember(session.serverUrl, itemId, trickplay.mediaSourceId, trickplay.width, frame.tileIndex) {
            JellyfinImageUrlBuilder().trickplayTileUrl(
                serverUrl = session.serverUrl,
                itemId = itemId,
                mediaSourceId = trickplay.mediaSourceId,
                width = trickplay.width,
                index = frame.tileIndex,
            )
        }
    val tileRequest = authenticatedImageRequest(tileUrl, session, decode = null)
    var tileLoaded by remember(tileRequest) { mutableStateOf(false) }
    val tilePainter =
        rememberAsyncImagePainter(
            model = tileRequest,
            onLoading = {
                tileLoaded = false
                tvTrickplayLogger.i {
                    "stage=trickplay event=request platform=android surface=tv result=loading " +
                        "tileIndex=${frame.tileIndex} thumbnailWidth=${trickplay.thumbnailWidth} " +
                        "thumbnailHeight=${trickplay.thumbnailHeight} tileColumns=${trickplay.tileWidth} " +
                        "tileRows=${trickplay.tileHeight} cropColumn=${frame.column} cropRow=${frame.row}"
                }
            },
            onSuccess = { state ->
                val result = state.result
                val image = result.image
                tvTrickplayLogger.i {
                    "stage=trickplay event=request platform=android surface=tv result=success " +
                        "tileIndex=${frame.tileIndex} thumbnailWidth=${trickplay.thumbnailWidth} " +
                        "thumbnailHeight=${trickplay.thumbnailHeight} tileColumns=${trickplay.tileWidth} " +
                        "tileRows=${trickplay.tileHeight} cropColumn=${frame.column} cropRow=${frame.row} " +
                        "decodedWidth=${image.width} decodedHeight=${image.height} " +
                        "dataSource=${result.dataSource.name} sampled=${result.isSampled}"
                }
                tileLoaded = true
            },
            onError = { state ->
                tileLoaded = false
                tvTrickplayLogger.w {
                    "stage=trickplay event=request platform=android surface=tv result=failure " +
                        "tileIndex=${frame.tileIndex} thumbnailWidth=${trickplay.thumbnailWidth} " +
                        "thumbnailHeight=${trickplay.thumbnailHeight} tileColumns=${trickplay.tileWidth} " +
                        "tileRows=${trickplay.tileHeight} cropColumn=${frame.column} cropRow=${frame.row} " +
                        "exceptionType=${state.result.throwable.safeDiagnosticType()}"
                }
            },
            contentScale = ContentScale.FillBounds,
        )
    if (!tileLoaded) {
        return
    }

    val shape = RoundedCornerShape(TvDimens.panelRadius)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val previewWidth = minOf(maxWidth, TvDimens.playerTrickplayThumbnailWidth)
        val targetX = maxWidth * positionFraction.coerceIn(0f, 1f)
        val previewLeft =
            (targetX - previewWidth * 0.5f).coerceIn(
                minimumValue = 0.dp,
                maximumValue = maxWidth - previewWidth,
            )
        val maximumPointerHalfWidth =
            minOf(
                TvDimens.playerTrickplayPointerWidth * 0.5f,
                previewWidth * 0.5f,
            )
        val previewRight = previewLeft + previewWidth
        val targetEdgeInset =
            minOf(
                targetX - previewLeft,
                previewRight - targetX,
            ).coerceAtLeast(0.dp)
        val minimumPointerHalfWidth =
            minOf(
                maximumPointerHalfWidth,
                TvDimens.playerTrickplayPointerHeight * 0.5f,
            )
        val pointerHalfWidth =
            minOf(
                maximumPointerHalfWidth,
                targetEdgeInset,
            ).coerceAtLeast(minimumPointerHalfWidth)
        val pointerBaseCenter =
            targetX.coerceIn(
                minimumValue = previewLeft + pointerHalfWidth,
                maximumValue = previewRight - pointerHalfWidth,
            )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        TvDimens.playerTrickplayThumbnailHeight +
                            TvDimens.playerTrickplayPointerHeight,
                    ),
        ) {
            Box(
                modifier =
                    Modifier
                        .offset(x = previewLeft)
                        .size(
                            width = previewWidth,
                            height = TvDimens.playerTrickplayThumbnailHeight,
                        ).clip(shape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .border(
                            width = TvDimens.playerPanelBorder,
                            color = Color.White.copy(alpha = 0.24f),
                            shape = shape,
                        ),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sourceFrameWidth =
                        trickplay.thumbnailWidth
                            .takeIf { width -> width > 0 }
                            ?.toFloat()
                            ?: size.width
                    val sourceFrameHeight =
                        trickplay.thumbnailHeight
                            .takeIf { height -> height > 0 }
                            ?.toFloat()
                            ?: size.height
                    val scale = minOf(size.width / sourceFrameWidth, size.height / sourceFrameHeight)
                    val frameWidth = sourceFrameWidth * scale
                    val frameHeight = sourceFrameHeight * scale
                    val frameLeft = (size.width - frameWidth) / 2f
                    val frameTop = (size.height - frameHeight) / 2f
                    val sheetSize =
                        Size(
                            width = frameWidth * trickplay.tileWidth.coerceAtLeast(1),
                            height = frameHeight * trickplay.tileHeight.coerceAtLeast(1),
                        )
                    clipRect(
                        left = frameLeft,
                        top = frameTop,
                        right = frameLeft + frameWidth,
                        bottom = frameTop + frameHeight,
                    ) {
                        translate(
                            left = frameLeft - frameWidth * frame.column,
                            top = frameTop - frameHeight * frame.row,
                        ) {
                            with(tilePainter) { draw(size = sheetSize) }
                        }
                    }
                }
            }
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(TvDimens.playerTrickplayPointerHeight)
                        .align(Alignment.BottomStart),
            ) {
                val baseCenterX = pointerBaseCenter.toPx()
                val baseHalfWidth = pointerHalfWidth.toPx()
                val baseLeftX = baseCenterX - baseHalfWidth
                val baseRightX = baseCenterX + baseHalfWidth
                val tipX = targetX.toPx()
                val bendY = size.height * 0.55f
                val pointer =
                    Path().apply {
                        moveTo(baseLeftX, 0f)
                        quadraticTo(
                            (baseLeftX + tipX) * 0.5f,
                            bendY,
                            tipX,
                            size.height,
                        )
                        quadraticTo(
                            (tipX + baseRightX) * 0.5f,
                            bendY,
                            baseRightX,
                            0f,
                        )
                        close()
                    }
                drawPath(
                    path = pointer,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}
