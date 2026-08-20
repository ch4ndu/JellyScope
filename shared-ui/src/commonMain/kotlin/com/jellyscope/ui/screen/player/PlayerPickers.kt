// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.currentChapterIndex
import com.jellyscope.ui.component.ChromeDimens
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_back
import com.jellyscope.ui.generated.resources.player_audio_track
import com.jellyscope.ui.generated.resources.player_chapter_cd
import com.jellyscope.ui.generated.resources.player_no_audio_tracks
import com.jellyscope.ui.generated.resources.player_no_chapters
import com.jellyscope.ui.generated.resources.player_no_subtitle_tracks
import com.jellyscope.ui.generated.resources.player_picker_close
import com.jellyscope.ui.generated.resources.player_subtitle_off
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerPickerSheet(
    content: PlayerUiState.Content,
    playbackStateFlow: StateFlow<PlaybackState>,
    onHidePicker: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onAdjustAudioTiming: (Long) -> Unit = {},
    onResetAudioTiming: () -> Unit = {},
    onSelectSubtitle: (Int?) -> Unit,
    onSelectLocalSubtitle: (String) -> Unit = {},
    onAdjustSubtitleTiming: (Long) -> Unit = {},
    onResetSubtitleTiming: () -> Unit = {},
    onSelectQuality: (Long?) -> Unit,
    onSelectQualityPolicy: (PlaybackQualityPolicy) -> Unit = { policy -> onSelectQuality(policy.maxBitrateBps) },
    onClearQualityOverride: () -> Unit = {},
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetSubtitleStyle: (SubtitleStyle) -> Unit,
    onSetResizeMode: (PlayerResizeMode) -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    onShuffleQueue: () -> Unit,
    onKeepControlsAlive: () -> Unit,
    onShowPicker: (PlayerPicker) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        val requestedPanelWidth = maxWidth * PLAYER_PICKER_PANEL_WIDTH_FRACTION
        val panelWidth =
            if (requestedPanelWidth < Dimensions.playerPickerMaxWidth) {
                requestedPanelWidth
            } else {
                Dimensions.playerPickerMaxWidth
            }

        Surface(
            modifier = Modifier.width(panelWidth),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = PLAYER_PICKER_SURFACE_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        start = ChromeDimens.playerPickerPadding,
                        end = ChromeDimens.playerPickerPadding,
                        top = ChromeDimens.playerPickerPadding,
                        bottom = ChromeDimens.playerPickerPadding / 2,
                    ),
                verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pickerTitle(content.pickerVisible),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val parentPicker = content.pickerVisible.parentPicker()
                    IconButton(
                        onClick = {
                            onKeepControlsAlive()
                            // Offset panels return to their track picker.
                            if (parentPicker != PlayerPicker.None) {
                                onShowPicker(parentPicker)
                            } else {
                                onHidePicker()
                            }
                        },
                        modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                    ) {
                        Icon(
                            imageVector =
                                if (parentPicker != PlayerPicker.None) {
                                    Icons.AutoMirrored.Filled.ArrowBack
                                } else {
                                    Icons.Filled.Close
                                },
                            contentDescription =
                                if (parentPicker != PlayerPicker.None) {
                                    stringResource(Res.string.detail_back)
                                } else {
                                    stringResource(Res.string.player_picker_close)
                                },
                        )
                    }
                }
                PlayerPickerDivider()
                when (content.pickerVisible) {
                    PlayerPicker.None -> Unit
                    PlayerPicker.Chapters ->
                        ChapterPicker(
                            chapters = content.chapters,
                            playbackStateFlow = playbackStateFlow,
                            onSeekTo = { positionMs ->
                                onKeepControlsAlive()
                                onSeekTo(positionMs)
                                onHidePicker()
                            },
                        )
                    PlayerPicker.Audio ->
                        AudioPicker(
                            options = content.audioOptions,
                            selectedAudioStreamIndex = content.selectedAudioStreamIndex,
                            onSelectAudio = { streamIndex ->
                                onKeepControlsAlive()
                                onSelectAudio(streamIndex)
                            },
                            timing = content.timingState.audio,
                            onOpenOffset = {
                                onKeepControlsAlive()
                                onShowPicker(PlayerPicker.AudioOffset)
                            },
                        )
                    PlayerPicker.AudioOffset ->
                        PlayerTimingOffsetPanel(
                            timing = content.timingState.audio,
                            onAdjust = { deltaMs ->
                                onKeepControlsAlive()
                                onAdjustAudioTiming(deltaMs)
                            },
                            onReset = {
                                onKeepControlsAlive()
                                onResetAudioTiming()
                            },
                        )
                    PlayerPicker.Subtitles ->
                        SubtitlePicker(
                            options = content.subtitleOptions,
                            localOptions = content.localSubtitleOptions,
                            selectedSubtitleStreamIndex = content.selectedSubtitleStreamIndex,
                            selectedSubtitleAssetId = content.selectedSubtitleAssetId,
                            subtitlesOff = content.subtitleRenderInfo.status == SubtitleRenderStatus.Off,
                            onSelectSubtitle = { streamIndex ->
                                onKeepControlsAlive()
                                onSelectSubtitle(streamIndex)
                            },
                            onSelectLocalSubtitle = { assetId ->
                                onKeepControlsAlive()
                                onSelectLocalSubtitle(assetId)
                            },
                            timing = content.timingState.subtitle,
                            onOpenOffset = {
                                onKeepControlsAlive()
                                onShowPicker(PlayerPicker.SubtitleOffset)
                            },
                        )
                    PlayerPicker.SubtitleOffset ->
                        PlayerTimingOffsetPanel(
                            timing = content.timingState.subtitle,
                            onAdjust = { deltaMs ->
                                onKeepControlsAlive()
                                onAdjustSubtitleTiming(deltaMs)
                            },
                            onReset = {
                                onKeepControlsAlive()
                                onResetSubtitleTiming()
                            },
                        )
                    PlayerPicker.Quality ->
                        QualityPicker(
                            options = content.qualityOptions,
                            selectedQualityPolicy = content.selectedQualityPolicy,
                            qualityOverrideExplicit = content.qualityOverrideExplicit,
                            inheritedQualityPolicy = content.inheritedQualityPolicy,
                            inheritedQualityUsesVlcSetting = content.inheritedQualityUsesVlcSetting,
                            onClearQualityOverride = {
                                onKeepControlsAlive()
                                onClearQualityOverride()
                                onHidePicker()
                            },
                            onSelectQuality = { policy ->
                                onKeepControlsAlive()
                                onSelectQualityPolicy(policy)
                                // Dismiss while the replan fetches fresh PlaybackInfo.
                                onHidePicker()
                            },
                        )
                    PlayerPicker.Speed ->
                        SpeedControls(
                            selectedSpeed = content.playbackSpeed,
                            onSetPlaybackSpeed = { speed ->
                                onKeepControlsAlive()
                                onSetPlaybackSpeed(speed)
                            },
                        )
                    PlayerPicker.SubtitleStyle ->
                        SubtitleStyleControls(
                            style = content.subtitleStyle,
                            onSetSubtitleStyle = { style ->
                                onKeepControlsAlive()
                                onSetSubtitleStyle(style)
                            },
                        )
                    PlayerPicker.Resize ->
                        ResizePicker(
                            selectedResizeMode = content.resizeMode,
                            desktopResizeModes = LocalPlatformCapabilities.current.desktopResizeModes,
                            onSetResizeMode = { mode ->
                                onKeepControlsAlive()
                                onSetResizeMode(mode)
                                onHidePicker()
                            },
                        )
                    PlayerPicker.Queue -> {
                        content.playlist?.let { playlist ->
                            QueueSection(
                                playlist = playlist,
                                onPlayQueueItem = { index ->
                                    onKeepControlsAlive()
                                    onPlayQueueItem(index)
                                },
                                onShuffleQueue = {
                                    onKeepControlsAlive()
                                    onShuffleQueue()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerPickerDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = ChromeDimens.detailTrackPickerDividerVerticalPadding)
                .height(ChromeDimens.detailTrackPickerDividerHeight)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = PLAYER_PICKER_DIVIDER_ALPHA)),
    )
}

@Composable
private fun ChapterPicker(
    chapters: List<Chapter>,
    playbackStateFlow: StateFlow<PlaybackState>,
    onSeekTo: (Long) -> Unit,
) {
    if (chapters.isEmpty()) {
        Text(
            text = stringResource(Res.string.player_no_chapters),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    val currentChapterIndex by
        playbackStateFlow
            .chapterIndexFlow(chapters)
            .collectAsState(initial = chapters.currentChapterIndex(playbackStateFlow.value.positionMs))
    LazyColumn(
        modifier = Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        itemsIndexed(
            items = chapters,
            key = { _, chapter -> "${chapter.startMs}:${chapter.name}" },
        ) { index, chapter ->
            PickerRow(
                selected = index == currentChapterIndex,
                title = chapter.name.ifBlank { stringResource(Res.string.player_chapter_cd, index + 1) },
                secondary = formatDuration(chapter.startMs),
                onClick = { onSeekTo(chapter.startMs) },
            )
        }
    }
}

@Composable
private fun AudioPicker(
    options: List<AudioTrackOption>,
    selectedAudioStreamIndex: Int?,
    onSelectAudio: (Int) -> Unit,
    timing: com.jellyscope.core.domain.playback.PlayerTimingValue,
    onOpenOffset: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        if (options.isEmpty()) {
            item(key = "audio-empty") {
                Text(
                    text = stringResource(Res.string.player_no_audio_tracks),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        itemsIndexed(
            options,
            key = { _, option -> "audio-${option.streamIndex}" },
        ) { index, option ->
            val title = option.displayName ?: stringResource(Res.string.player_audio_track, index + 1)
            PickerRow(
                selected = selectedAudioStreamIndex == option.streamIndex,
                title = title,
                secondary = option.language?.takeIf { language -> !title.equals(language, ignoreCase = true) },
                onClick = { onSelectAudio(option.streamIndex) },
            )
        }
        item(key = "audio-timing") {
            PlayerTimingOffsetRow(timing = timing, onOpen = onOpenOffset)
        }
    }
}

@Composable
private fun SubtitlePicker(
    options: List<SubtitleTrackOption>,
    localOptions: List<LocalSubtitleAsset>,
    selectedSubtitleStreamIndex: Int?,
    selectedSubtitleAssetId: String?,
    subtitlesOff: Boolean,
    onSelectSubtitle: (Int?) -> Unit,
    onSelectLocalSubtitle: (String) -> Unit,
    timing: com.jellyscope.core.domain.playback.PlayerTimingValue,
    onOpenOffset: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        item(key = "off") {
            PickerRow(
                selected = subtitlesOff,
                title = stringResource(Res.string.player_subtitle_off),
                onClick = { onSelectSubtitle(null) },
            )
        }
        if (options.isEmpty() && localOptions.isEmpty()) {
            item(key = "subtitle-empty") {
                Text(
                    text = stringResource(Res.string.player_no_subtitle_tracks),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        itemsIndexed(
            options,
            key = { _, option -> "subtitle-${option.streamIndex}" },
        ) { index, option ->
            val title = option.displayName ?: stringResource(Res.string.player_audio_track, index + 1)
            PickerRow(
                selected = selectedSubtitleStreamIndex == option.streamIndex,
                title = title,
                secondary = option.language?.takeIf { language -> !title.equals(language, ignoreCase = true) },
                onClick = { onSelectSubtitle(option.streamIndex) },
            )
        }
        itemsIndexed(
            localOptions,
            key = { _, option -> "local-subtitle-${option.id}" },
        ) { _, option ->
            PickerRow(
                selected = selectedSubtitleAssetId == option.id,
                title = option.label,
                secondary = option.language.takeIf { language -> !option.label.equals(language, ignoreCase = true) },
                onClick = { onSelectLocalSubtitle(option.id) },
            )
        }
        item(key = "subtitle-timing") {
            PlayerTimingOffsetRow(timing = timing, onOpen = onOpenOffset)
        }
    }
}

@Composable
private fun QualityPicker(
    options: List<QualityOption>,
    selectedQualityPolicy: PlaybackQualityPolicy,
    qualityOverrideExplicit: Boolean,
    inheritedQualityPolicy: PlaybackQualityPolicy,
    inheritedQualityUsesVlcSetting: Boolean,
    onClearQualityOverride: () -> Unit,
    onSelectQuality: (PlaybackQualityPolicy) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = Dimensions.playerPickerListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        itemsIndexed(
            options,
            key = { _, option -> playerQualityOptionIdentity(option) },
        ) { _, option ->
            PickerRow(
                selected =
                    if (option.inheritsDefault) {
                        !qualityOverrideExplicit
                    } else {
                        qualityOverrideExplicit &&
                            selectedQualityPolicy.mode == option.mode &&
                            selectedQualityPolicy.maxBitrateBps == option.maxBitrateBps
                    },
                title = qualityLabel(option),
                secondary =
                    inheritedQualityDescription(
                        policy = inheritedQualityPolicy,
                        usesVlcSetting = inheritedQualityUsesVlcSetting,
                    ).takeIf { option.inheritsDefault },
                onClick = {
                    if (option.inheritsDefault) {
                        onClearQualityOverride()
                    } else {
                        onSelectQuality(
                            when (option.mode) {
                                PlaybackQualityMode.Auto -> PlaybackQualityPolicy.Auto
                                PlaybackQualityMode.Original -> PlaybackQualityPolicy.Original
                                PlaybackQualityMode.Fixed ->
                                    option.maxBitrateBps?.let(PlaybackQualityPolicy::fixed)
                                        ?: PlaybackQualityPolicy.Auto
                            },
                        )
                    }
                },
            )
        }
    }
}

/** Keeps inherited-default and explicit Auto rows distinct. */
internal fun playerQualityOptionIdentity(option: QualityOption): String =
    if (option.inheritsDefault) {
        "quality-inherited-default"
    } else {
        "quality-${option.mode}-${option.maxBitrateBps}"
    }
