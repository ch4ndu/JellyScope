// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.tv.R
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.screen.player.HoldSeekDirection
import com.jellyscope.ui.screen.player.chapterTickPoints
import kotlinx.coroutines.flow.StateFlow
import com.jellyscope.ui.component.AdaptiveProgressBar as TvProgressBar
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun ColumnScope.TvPlayerScrubSection(
    session: Session,
    currentItemId: String,
    trickplay: TrickplayInfo?,
    chapters: List<Chapter>,
    playbackStateFlow: StateFlow<PlaybackState>,
    seekRequester: FocusRequester,
    playRequester: FocusRequester,
    seekFocused: Boolean,
    onSeekFocusChanged: (Boolean) -> Unit,
    // A provider, not a value: read here, the hold target invalidates only this
    // section. Read at TvPlayerContent scope it invalidated the whole player
    // overlay on every hold-to-seek key repeat — the same reason the live playback
    // state is collected here rather than above.
    pendingSeekTargetMs: () -> Long?,
    onSeekKeyDown: (HoldSeekDirection) -> Unit,
) {
    val playbackState by playbackStateFlow.collectAsStateWithLifecycle()
    val pendingTargetMs = pendingSeekTargetMs()
    // A pending hold target owns the presentation: the bar, thumb, time row,
    // and trickplay thumbnail all follow it instead of the live position.
    val displayPositionMs = pendingTargetMs ?: playbackState.positionMs
    if ((seekFocused || pendingTargetMs != null) && trickplay != null) {
        TvTrickplayThumbnail(
            session = session,
            itemId = currentItemId,
            trickplay = trickplay,
            positionMs = displayPositionMs,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
    TvPlayerSeekBar(
        playbackState = playbackState,
        displayPositionMs = displayPositionMs,
        chapters = chapters,
        seekRequester = seekRequester,
        playRequester = playRequester,
        onFocusChanged = onSeekFocusChanged,
        onSeekKeyDown = onSeekKeyDown,
    )
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
    onFocusChanged: (Boolean) -> Unit,
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
                    onFocusChanged(state.isFocused)
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
    modifier: Modifier = Modifier,
) {
    val frame = trickplay.frameForPositionMs(positionMs)
    if (itemId.isBlank() || frame == null) {
        return
    }
    val tileUrl =
        remember(session.serverUrl, itemId, trickplay.width, frame.tileIndex) {
            JellyfinImageUrlBuilder().trickplayTileUrl(
                serverUrl = session.serverUrl,
                itemId = itemId,
                width = trickplay.width,
                index = frame.tileIndex,
            )
        }

    Box(
        modifier =
            modifier
                .padding(bottom = TvDimens.playerTrickplayBottomPadding)
                .size(
                    width = TvDimens.playerTrickplayThumbnailWidth,
                    height = TvDimens.playerTrickplayThumbnailHeight,
                ).clip(RoundedCornerShape(TvDimens.panelRadius))
                .background(Color.Black.copy(alpha = 0.72f))
                .border(
                    width = TvDimens.playerPanelBorder,
                    color = Color.White.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(TvDimens.panelRadius),
                ),
    ) {
        AsyncImage(
            // decode = null: don't downscale the sprite sheet (frames are region-
            // cropped by layout offset); default Poster decode would distort it.
            model = authenticatedImageRequest(tileUrl, session, decode = null),
            contentDescription = null,
            modifier =
                Modifier
                    .width(TvDimens.playerTrickplayThumbnailWidth * trickplay.tileWidth.toFloat())
                    .height(TvDimens.playerTrickplayThumbnailHeight * trickplay.tileHeight.toFloat())
                    .offset(
                        x = -(TvDimens.playerTrickplayThumbnailWidth * frame.column.toFloat()),
                        y = -(TvDimens.playerTrickplayThumbnailHeight * frame.row.toFloat()),
                    ),
            contentScale = ContentScale.FillBounds,
        )
    }
}
