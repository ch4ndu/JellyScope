// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.currentChapterIndex
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.TvFocusTrapEffect
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.player.PlayerBackendSwitchChoice
import com.jellyscope.ui.screen.player.PlayerPicker
import com.jellyscope.ui.screen.player.PlayerResizeMode
import com.jellyscope.ui.screen.player.PlayerUiState
import com.jellyscope.ui.screen.player.chapterIndexFlow
import com.jellyscope.ui.screen.player.parentPicker
import com.jellyscope.ui.screen.player.playbackSpeeds
import com.jellyscope.ui.screen.player.speedLabel
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.flow.StateFlow
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvPickerOverlay(
    content: PlayerUiState.Content,
    onHidePicker: () -> Unit,
    onSelectBackend: (PlayerBackend) -> Unit = {},
    onSelectAudio: (Int) -> Unit,
    onAdjustAudioTiming: (Long) -> Unit = {},
    onResetAudioTiming: () -> Unit = {},
    onSelectSubtitle: (Int?) -> Unit,
    onSelectLocalSubtitle: (String) -> Unit = {},
    onSelectOfflineSidecar: () -> Unit = {},
    onAdjustSubtitleTiming: (Long) -> Unit = {},
    onResetSubtitleTiming: () -> Unit = {},
    onSelectQuality: (Long?) -> Unit,
    onSelectQualityPolicy: (PlaybackQualityPolicy) -> Unit = { policy -> onSelectQuality(policy.maxBitrateBps) },
    onClearQualityOverride: () -> Unit = {},
    onShowPicker: (PlayerPicker) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstRowRequester = remember(content.pickerVisible) { FocusRequester() }
    TvFocusTrapEffect()

    LaunchedEffect(content.pickerVisible, content.backendSwitchInProgress) {
        firstRowRequester.requestFocusSafely()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        // An offset panel goes back to its track picker rather than
                        // dismissing to the player.
                        val parent = content.pickerVisible.parentPicker()
                        if (parent != PlayerPicker.None) onShowPicker(parent) else onHidePicker()
                        true
                    } else {
                        false
                    }
                },
    ) {
        // The picker panel rises from the bottom edge of the
        // screen as a wide, dark sheet with a header divider and flat rows.
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(TvDimens.playerPickerWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = TvDimens.panelRadius,
                            topEnd = TvDimens.panelRadius,
                        ),
                    ).background(LocalJellyfinPalette.current.gradientBottom.copy(alpha = 0.95f))
                    .padding(
                        start = TvDimens.playerPickerPadding,
                        end = TvDimens.playerPickerPadding,
                        top = TvDimens.playerPickerPadding,
                        bottom = TvDimens.playerPickerPadding / 2,
                    ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TvText(
                text = pickerTitle(content.pickerVisible),
                style = TvPlayerSectionTitleStyle,
                maxLines = 1,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = TvDimens.playerPickerDividerVerticalPadding)
                        .height(TvDimens.playerPickerDividerHeight)
                        .background(Color.White.copy(alpha = 0.12f)),
            )
            when (content.pickerVisible) {
                PlayerPicker.Backend ->
                    BackendPickerRows(
                        choices = content.backendChoices,
                        activeBackend = content.activeBackend,
                        switchInProgress = content.backendSwitchInProgress,
                        firstRowRequester = firstRowRequester,
                        onHidePicker = onHidePicker,
                        onSelectBackend = onSelectBackend,
                    )
                PlayerPicker.Subtitles ->
                    SubtitlePickerRows(
                        options = content.subtitleOptions,
                        localOptions = content.localSubtitleOptions,
                        offlineSidecarOption = content.offlineSidecarOption,
                        selectedStreamIndex = content.selectedSubtitleStreamIndex,
                        selectedAssetId = content.selectedSubtitleAssetId,
                        offlineSidecarSelected = content.offlineSidecarSelected,
                        subtitlesOff = content.subtitleRenderInfo.status == SubtitleRenderStatus.Off,
                        firstRowRequester = firstRowRequester,
                        onSelectSubtitle = onSelectSubtitle,
                        onSelectLocalSubtitle = onSelectLocalSubtitle,
                        onSelectOfflineSidecar = onSelectOfflineSidecar,
                        timing = content.timingState.subtitle,
                        onOpenOffset = { onShowPicker(PlayerPicker.SubtitleOffset) },
                    )
                PlayerPicker.SubtitleOffset ->
                    TvTimingOffsetPanelRows(
                        timing = content.timingState.subtitle,
                        firstRowRequester = firstRowRequester,
                        onAdjust = onAdjustSubtitleTiming,
                        onReset = onResetSubtitleTiming,
                    )
                PlayerPicker.Audio ->
                    AudioPickerRows(
                        options = content.audioOptions,
                        selectedStreamIndex = content.selectedAudioStreamIndex,
                        firstRowRequester = firstRowRequester,
                        onSelectAudio = onSelectAudio,
                        timing = content.timingState.audio,
                        onOpenOffset = { onShowPicker(PlayerPicker.AudioOffset) },
                    )
                PlayerPicker.AudioOffset ->
                    TvTimingOffsetPanelRows(
                        timing = content.timingState.audio,
                        firstRowRequester = firstRowRequester,
                        onAdjust = onAdjustAudioTiming,
                        onReset = onResetAudioTiming,
                    )
                PlayerPicker.Quality ->
                    QualityPickerRows(
                        options = content.qualityOptions,
                        selectedPolicy = content.selectedQualityPolicy,
                        qualityOverrideExplicit = content.qualityOverrideExplicit,
                        inheritedQualityPolicy = content.inheritedQualityPolicy,
                        inheritedQualityUsesVlcSetting = content.inheritedQualityUsesVlcSetting,
                        firstRowRequester = firstRowRequester,
                        onClearQualityOverride = {
                            onClearQualityOverride()
                            onHidePicker()
                        },
                        onSelectQuality = { policy ->
                            onSelectQualityPolicy(policy)
                            // Close on select: the replan is not visible for a
                            // moment, so a sheet that stays open reads as a no-op.
                            onHidePicker()
                        },
                    )
                PlayerPicker.Chapters,
                PlayerPicker.None,
                PlayerPicker.Speed,
                PlayerPicker.SubtitleStyle,
                PlayerPicker.Queue,
                PlayerPicker.Resize,
                -> Unit
            }
        }
    }
}

@Composable
private fun BackendPickerRows(
    choices: List<PlayerBackendSwitchChoice>,
    activeBackend: PlayerBackend,
    switchInProgress: Boolean,
    firstRowRequester: FocusRequester,
    onHidePicker: () -> Unit,
    onSelectBackend: (PlayerBackend) -> Unit,
) {
    val firstSelectableIndex =
        choices.indexOfFirst { choice ->
            choice.available && choice.backend != activeBackend && !switchInProgress
        }
    LazyColumn(
        modifier = Modifier.heightIn(max = TvDimens.playerMenuMaxHeight),
        verticalArrangement = Arrangement.spacedBy(TvDimens.playerBadgeVerticalPadding),
    ) {
        if (firstSelectableIndex < 0) {
            item(key = "backend-dismiss") {
                TvPickerRow(
                    title = stringResource(R.string.tv_player_action_close),
                    selected = false,
                    focusRequester = firstRowRequester,
                    onClick = onHidePicker,
                )
            }
        }
        itemsIndexed(
            items = choices,
            key = { _, choice -> "backend-${choice.backend.name}" },
            contentType = { _, _ -> "backend-row" },
        ) { index, choice ->
            val current = choice.backend == activeBackend
            val selectable = choice.available && !current && !switchInProgress
            if (selectable) {
                TvPickerRow(
                    title = playerBackendLabel(choice.backend),
                    selected = false,
                    focusRequester =
                        firstRowRequester.takeIf { index == firstSelectableIndex },
                    onClick = { onSelectBackend(choice.backend) },
                )
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = TvDimens.playerPickerPadding,
                                vertical = TvDimens.formGap,
                            ),
                    verticalArrangement = Arrangement.spacedBy(TvDimens.playerBadgeVerticalPadding),
                ) {
                    TvText(
                        text = playerBackendLabel(choice.backend),
                        style = TvBodyStyle,
                        color = LocalJellyfinPalette.current.textSecondary,
                        maxLines = 1,
                    )
                    TvText(
                        text =
                            stringResource(
                                if (current) {
                                    R.string.tv_player_backend_current
                                } else {
                                    R.string.tv_settings_player_backend_unavailable
                                },
                            ),
                        style = TvSecondaryStyle,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TvPlayerLocalMenuOverlay(
    content: PlayerUiState.Content,
    playbackStateFlow: StateFlow<PlaybackState>,
    menu: TvPlayerLocalMenu,
    onHideMenu: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetSubtitleStyle: (SubtitleStyle) -> Unit,
    onSelectResizeMode: (PlayerResizeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstRowRequester = remember(menu) { FocusRequester() }
    val menuListState = rememberLazyListState()
    val currentChapterIndex =
        if (menu == TvPlayerLocalMenu.Chapters) {
            val initialIndex = content.chapters.currentChapterIndex(playbackStateFlow.value.positionMs)
            playbackStateFlow.chapterIndexFlow(content.chapters).collectAsState(initial = initialIndex).value
        } else {
            -1
        }
    val initialFocusIndex =
        remember(menu) {
            when (menu) {
                TvPlayerLocalMenu.Chapters -> currentChapterIndex.coerceAtLeast(0)
                TvPlayerLocalMenu.Speed -> playbackSpeeds().indexOf(content.playbackSpeed).coerceAtLeast(0)
                TvPlayerLocalMenu.SubtitleStyle -> 0
                TvPlayerLocalMenu.Resize -> PlayerResizeMode.entries.indexOf(content.resizeMode).coerceAtLeast(0)
                TvPlayerLocalMenu.None -> 0
            }
        }
    val fallbackRowRequester =
        remember(menu, initialFocusIndex) {
            if (initialFocusIndex == 0) firstRowRequester else FocusRequester()
        }

    LaunchedEffect(menu) {
        if (menu == TvPlayerLocalMenu.None) return@LaunchedEffect
        menuListState.scrollToItem(initialFocusIndex)
        if (!requestTvFocusWithRetry { firstRowRequester.requestFocusSafely() }) {
            menuListState.scrollToItem(0)
            requestTvFocusWithRetry { fallbackRowRequester.requestFocusSafely() }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        onHideMenu()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(TvDimens.playerPickerWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = TvDimens.panelRadius,
                            topEnd = TvDimens.panelRadius,
                        ),
                    ).background(LocalJellyfinPalette.current.gradientBottom.copy(alpha = 0.95f))
                    .padding(
                        start = TvDimens.playerPickerPadding,
                        end = TvDimens.playerPickerPadding,
                        top = TvDimens.playerPickerPadding,
                        bottom = TvDimens.playerPickerPadding / 2,
                    ),
            verticalArrangement = Arrangement.spacedBy(TvDimens.playerBadgeVerticalPadding),
        ) {
            TvText(
                text = localMenuTitle(menu),
                style = TvPlayerSectionTitleStyle,
                maxLines = 1,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = TvDimens.playerPickerDividerVerticalPadding)
                        .height(TvDimens.playerPickerDividerHeight)
                        .background(Color.White.copy(alpha = 0.12f)),
            )
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = TvDimens.playerMenuMaxHeight),
                state = menuListState,
                verticalArrangement = Arrangement.spacedBy(TvDimens.playerBadgeVerticalPadding),
            ) {
                when (menu) {
                    TvPlayerLocalMenu.Chapters -> {
                        if (content.chapters.isEmpty()) {
                            item {
                                TvPickerRow(
                                    title = stringResource(R.string.tv_chapters_none),
                                    selected = false,
                                    focusRequester = firstRowRequester,
                                    onClick = onHideMenu,
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = content.chapters,
                                key = { index, chapter -> "${chapter.startMs}-$index" },
                                contentType = { _, _ -> "chapter-row" },
                            ) { index, chapter ->
                                TvPickerRow(
                                    title = chapter.displayTitle(index),
                                    secondary = formatDuration(chapter.startMs),
                                    selected = index == currentChapterIndex,
                                    focusRequester =
                                        when {
                                            index == initialFocusIndex -> firstRowRequester
                                            index == 0 -> fallbackRowRequester
                                            else -> null
                                        },
                                    onClick = { onSeekTo(chapter.startMs) },
                                )
                            }
                        }
                    }
                    TvPlayerLocalMenu.Speed -> {
                        val speeds = playbackSpeeds()
                        itemsIndexed(
                            items = speeds,
                            key = { index, speed -> "speed-$speed-$index" },
                            contentType = { _, _ -> "speed-row" },
                        ) { index, speed ->
                            TvPickerRow(
                                title = speed.speedLabel(),
                                selected = content.playbackSpeed == speed,
                                focusRequester =
                                    when {
                                        index == initialFocusIndex -> firstRowRequester
                                        index == 0 -> fallbackRowRequester
                                        else -> null
                                    },
                                onClick = { onSetPlaybackSpeed(speed) },
                            )
                        }
                    }
                    TvPlayerLocalMenu.SubtitleStyle -> {
                        item { TvMenuSectionTitle(text = stringResource(R.string.tv_subtitle_style_size)) }
                        subtitleSizeChoices().forEachIndexed { index, choice ->
                            item(key = "size-${choice.scale}") {
                                TvPickerRow(
                                    title = stringResource(choice.labelRes),
                                    selected = content.subtitleStyle.fontScale == choice.scale,
                                    focusRequester =
                                        when {
                                            index == initialFocusIndex -> firstRowRequester
                                            index == 0 -> fallbackRowRequester
                                            else -> null
                                        },
                                    onClick = {
                                        onSetSubtitleStyle(content.subtitleStyle.copy(fontScale = choice.scale))
                                    },
                                )
                            }
                        }
                        item { TvMenuSectionTitle(text = stringResource(R.string.tv_subtitle_style_color)) }
                        subtitleColorChoices().forEach { choice ->
                            item(key = "foreground-${choice.value}") {
                                TvPickerRow(
                                    title = stringResource(choice.labelRes),
                                    selected = content.subtitleStyle.foregroundColor == choice.value,
                                    onClick = {
                                        onSetSubtitleStyle(content.subtitleStyle.copy(foregroundColor = choice.value))
                                    },
                                )
                            }
                        }
                        item { TvMenuSectionTitle(text = stringResource(R.string.tv_subtitle_style_background)) }
                        subtitleBackgroundChoices().forEach { choice ->
                            item(key = "background-${choice.value}") {
                                TvPickerRow(
                                    title = stringResource(choice.labelRes),
                                    selected = content.subtitleStyle.backgroundColor == choice.value,
                                    onClick = {
                                        onSetSubtitleStyle(content.subtitleStyle.copy(backgroundColor = choice.value))
                                    },
                                )
                            }
                        }
                        item { TvMenuSectionTitle(text = stringResource(R.string.tv_subtitle_style_edge)) }
                        subtitleEdgeChoices().forEach { choice ->
                            item(key = "edge-${choice.edgeStyle}") {
                                TvPickerRow(
                                    title = stringResource(choice.labelRes),
                                    selected = content.subtitleStyle.edgeStyle == choice.edgeStyle,
                                    onClick = {
                                        onSetSubtitleStyle(content.subtitleStyle.copy(edgeStyle = choice.edgeStyle))
                                    },
                                )
                            }
                        }
                    }
                    TvPlayerLocalMenu.Resize -> {
                        itemsIndexed(
                            items = PlayerResizeMode.entries,
                            key = { _, mode -> "resize-$mode" },
                            contentType = { _, _ -> "resize-row" },
                        ) { index, mode ->
                            TvPickerRow(
                                title = mode.label(),
                                selected = content.resizeMode == mode,
                                focusRequester =
                                    when {
                                        index == initialFocusIndex -> firstRowRequester
                                        index == 0 -> fallbackRowRequester
                                        else -> null
                                    },
                                onClick = { onSelectResizeMode(mode) },
                            )
                        }
                    }
                    TvPlayerLocalMenu.None -> Unit
                }
            }
        }
    }
}
