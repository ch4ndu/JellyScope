// SPDX-License-Identifier: MPL-2.0

@file:OptIn(ExperimentalComposeUiApi::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.DetailTitleStyle
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.media_info_section_audio
import com.jellyscope.ui.generated.resources.media_info_section_file
import com.jellyscope.ui.generated.resources.media_info_section_subtitles
import com.jellyscope.ui.generated.resources.media_info_section_video
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun AdaptiveMediaInfoDialog(
    title: String,
    mediaInfo: MediaInfoUi,
    onDismiss: () -> Unit,
) {
    if (!isDetailDpadMode()) {
        MediaInfoSheet(
            title = title,
            mediaInfo = mediaInfo,
            onDismiss = onDismiss,
        )
        return
    }

    val panelRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollStep =
        with(LocalDensity.current) {
            DetailDimens.playerPickerRowHeight.toPx().roundToInt()
        }
    val palette = LocalJellyfinPalette.current

    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        withFrameNanos { }
        panelRequester.requestFocusSafely()
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .detailOnPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.Back -> {
                                onDismiss()
                                true
                            }
                            Key.DirectionUp -> {
                                scope.launch {
                                    scrollState.animateScrollTo(
                                        (scrollState.value - scrollStep).coerceAtLeast(0),
                                    )
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                scope.launch {
                                    scrollState.animateScrollTo(
                                        (scrollState.value + scrollStep).coerceAtMost(scrollState.maxValue),
                                    )
                                }
                                true
                            }
                            Key.DirectionLeft,
                            Key.DirectionRight,
                            -> true
                            else -> false
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(DetailDimens.mediaInfoDialogWidth)
                    .heightIn(max = DetailDimens.mediaInfoDialogMaxHeight)
                    .focusRequester(panelRequester)
                    .detailFocusGroup()
                    .detailFocusable()
                    .clip(RoundedCornerShape(DetailDimens.panelRadius))
                    .background(palette.gradientBottom.copy(alpha = 0.96f))
                    .border(
                        width = DetailDimens.playerPanelBorder,
                        color = Color.White.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(DetailDimens.panelRadius),
                    ).verticalScroll(scrollState)
                    .padding(DetailDimens.playerPickerPadding),
            verticalArrangement = Arrangement.spacedBy(DetailDimens.mediaInfoDialogSectionGap),
        ) {
            DetailText(
                text = title,
                style = DetailMediaInfoTitleStyle,
                maxLines = 2,
            )
            mediaInfo.fileLine?.let { fileLine ->
                AdaptiveMediaInfoSection(
                    title = stringResource(Res.string.media_info_section_file),
                    lines = listOf(fileLine),
                )
            }
            AdaptiveMediaInfoSection(
                title = stringResource(Res.string.media_info_section_video),
                lines = mediaInfo.videoLines,
            )
            AdaptiveMediaInfoSection(
                title = stringResource(Res.string.media_info_section_audio),
                lines = mediaInfo.audioLines,
            )
            AdaptiveMediaInfoSection(
                title = stringResource(Res.string.media_info_section_subtitles),
                lines = mediaInfo.subtitleLines,
            )
        }
    }
}

@Composable
private fun AdaptiveMediaInfoSection(
    title: String,
    lines: List<String>,
) {
    if (lines.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.mediaInfoDialogLineGap),
    ) {
        DetailText(
            text = title,
            style = DetailTitleStyle,
            maxLines = 1,
        )
        lines.forEach { line ->
            DetailText(
                text = line,
                style = DetailBodyStyle,
                color = LocalJellyfinPalette.current.textSecondary,
            )
        }
    }
}
