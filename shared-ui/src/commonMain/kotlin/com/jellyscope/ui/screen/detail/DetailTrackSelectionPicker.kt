// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.hasSelectableSubtitleChoice
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_audio_tracks_picker_cd
import com.jellyscope.ui.generated.resources.detail_subtitle_tracks_picker_cd
import com.jellyscope.ui.generated.resources.detail_track_none
import com.jellyscope.ui.generated.resources.detail_version
import com.jellyscope.ui.generated.resources.detail_version_picker_cd
import com.jellyscope.ui.generated.resources.player_audio
import com.jellyscope.ui.generated.resources.player_audio_track
import com.jellyscope.ui.generated.resources.player_subtitle_off
import com.jellyscope.ui.generated.resources.player_subtitles
import com.jellyscope.ui.generated.resources.subtitles_delete_selected
import com.jellyscope.ui.generated.resources.subtitles_retry_upload
import com.jellyscope.ui.generated.resources.subtitles_search_action
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

internal enum class DetailTrackPickerType {
    Version,
    Audio,
    Subtitles,
}

@Composable
internal fun DpadTrackSelectionButtons(
    trackSelection: DetailTrackSelectionUi,
    selectionState: DetailTrackSelectionState,
    versions: List<MediaVersionUi> = emptyList(),
    selectedMediaSourceId: String? = null,
    subtitleActions: DetailSubtitlePickerActions? = null,
    onOpenPicker: (DetailTrackPickerType) -> Unit,
    modifier: Modifier = Modifier,
    versionRequester: FocusRequester? = null,
    audioRequester: FocusRequester? = null,
    subtitleRequester: FocusRequester? = null,
) {
    if (!isDetailDpadMode()) {
        return
    }

    val versionPolicy = mediaVersionPickerPolicy(versions, selectedMediaSourceId)
    if (versionPolicy.visible) {
        val label = stringResource(Res.string.detail_version)
        val selectedLabel = versionPolicy.selectedVersion?.name.orEmpty()
        TrackPickerActionButton(
            label = label,
            selectedLabel = selectedLabel,
            icon = DetailActionIcon.Version,
            onClick = { onOpenPicker(DetailTrackPickerType.Version) },
            modifier = modifier.detailFocusRequester(versionRequester),
            contentDescription = stringResource(Res.string.detail_version_picker_cd, selectedLabel),
        )
    }
    if (trackSelection.audioOptions.size > 1) {
        val label = stringResource(Res.string.player_audio)
        val selectedLabel =
            selectedAudioLabel(
                options = trackSelection.audioOptions,
                selectedStreamIndex = selectionState.selectedAudioStreamIndex,
            )
        TrackPickerActionButton(
            label = label,
            selectedLabel = selectedLabel,
            icon = DetailActionIcon.Audio,
            onClick = { onOpenPicker(DetailTrackPickerType.Audio) },
            modifier = modifier.detailFocusRequester(audioRequester),
            contentDescription = stringResource(Res.string.detail_audio_tracks_picker_cd, selectedLabel),
        )
    }
    if (trackSelection.subtitleOptions.hasSelectableSubtitleChoice() ||
        trackSelection.localSubtitleOptions.isNotEmpty() ||
        subtitleActions?.onSearchSubtitles != null
    ) {
        val label = stringResource(Res.string.player_subtitles)
        val selectedLocalAsset =
            trackSelection.localSubtitleOptions.firstOrNull { asset ->
                asset.id == subtitleActions?.selectedLocalAssetId
            }
        val selectedLabel =
            selectedLocalAsset?.label
                ?: selectedSubtitleLabel(
                    options = trackSelection.subtitleOptions,
                    selectedStreamIndex = selectionState.selectedSubtitleStreamIndex,
                )
        TrackPickerActionButton(
            label = label,
            selectedLabel = selectedLabel,
            icon = DetailActionIcon.Subtitles,
            onClick = { onOpenPicker(DetailTrackPickerType.Subtitles) },
            modifier = modifier.detailFocusRequester(subtitleRequester),
            contentDescription = stringResource(Res.string.detail_subtitle_tracks_picker_cd, selectedLabel),
        )
    }
}

@Composable
internal fun DpadTrackSelectionPickerOverlay(
    picker: DetailTrackPickerType,
    trackSelection: DetailTrackSelectionUi,
    selectionState: DetailTrackSelectionState,
    versions: List<MediaVersionUi> = emptyList(),
    selectedMediaSourceId: String? = null,
    onSelectMediaVersion: (String) -> Unit = {},
    subtitleActions: DetailSubtitlePickerActions? = null,
    onDismiss: () -> Unit,
) {
    val palette = LocalJellyfinPalette.current
    val firstRowRequester = remember(picker) { FocusRequester() }
    val versionPolicy = mediaVersionPickerPolicy(versions, selectedMediaSourceId)
    val initialFocusIndex =
        when (picker) {
            DetailTrackPickerType.Version -> versionPolicy.selectedIndex.coerceAtLeast(0)
            DetailTrackPickerType.Audio ->
                trackSelection.audioOptions
                    .indexOfFirst { option -> option.streamIndex == selectionState.selectedAudioStreamIndex }
                    .coerceAtLeast(0)
            DetailTrackPickerType.Subtitles -> {
                val localIndex =
                    trackSelection.localSubtitleOptions.indexOfFirst { asset ->
                        asset.id == subtitleActions?.selectedLocalAssetId
                    }
                val embeddedIndex =
                    trackSelection.subtitleOptions.indexOfFirst { option ->
                        option.streamIndex == selectionState.selectedSubtitleStreamIndex
                    }
                when {
                    localIndex >= 0 -> 1 + trackSelection.subtitleOptions.size + localIndex
                    embeddedIndex >= 0 -> 1 + embeddedIndex
                    else -> 0
                }
            }
        }
    val listState = key(picker) { rememberLazyListState(initialFirstVisibleItemIndex = initialFocusIndex) }
    val title =
        when (picker) {
            DetailTrackPickerType.Version -> stringResource(Res.string.detail_version)
            DetailTrackPickerType.Audio -> stringResource(Res.string.player_audio)
            DetailTrackPickerType.Subtitles -> stringResource(Res.string.player_subtitles)
        }

    LaunchedEffect(picker, initialFocusIndex) {
        listState.scrollToItem(initialFocusIndex)
        repeat(DPAD_PICKER_FOCUS_RETRIES) {
            if (firstRowRequester.requestFocusSafely()) {
                return@LaunchedEffect
            }
            delay(DPAD_PICKER_FOCUS_RETRY_MS)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
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
                                Key.DirectionLeft,
                                Key.DirectionRight,
                                -> true
                                else -> false
                            }
                        }
                    },
        ) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(DetailDimens.detailTrackPickerWidth)
                        .heightIn(max = DetailDimens.detailTrackPickerMaxHeight)
                        .detailFocusRestorer(firstRowRequester)
                        .detailFocusGroup()
                        .clip(
                            RoundedCornerShape(
                                topStart = DetailDimens.panelRadius,
                                topEnd = DetailDimens.panelRadius,
                            ),
                        ).background(palette.gradientBottom.copy(alpha = 0.95f))
                        .border(
                            width = DetailDimens.playerPanelBorder,
                            color = Color.White.copy(alpha = 0.16f),
                            shape =
                                RoundedCornerShape(
                                    topStart = DetailDimens.panelRadius,
                                    topEnd = DetailDimens.panelRadius,
                                ),
                        ).padding(
                            start = DetailDimens.playerPickerPadding,
                            end = DetailDimens.playerPickerPadding,
                            top = DetailDimens.playerPickerPadding,
                            bottom = DetailDimens.playerPickerPadding / 2,
                        ),
                verticalArrangement = Arrangement.spacedBy(DetailDimens.progressHeight),
            ) {
                DetailText(
                    text = title,
                    style = DetailPickerTitleStyle,
                    maxLines = 1,
                )
                DpadTrackPickerDivider()
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = DetailDimens.detailTrackPickerListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(DetailDimens.progressHeight),
                ) {
                    when (picker) {
                        DetailTrackPickerType.Version -> {
                            itemsIndexed(
                                items = versionPolicy.versions,
                                key = { _, version -> "version-${version.id}" },
                                contentType = { _, _ -> "media-version-row" },
                            ) { index, version ->
                                DpadTrackPickerRow(
                                    title = version.name,
                                    selected = version.id == versionPolicy.selectedVersion?.id,
                                    focusRequester = firstRowRequester.takeIf { index == versionPolicy.selectedIndex },
                                    onClick = {
                                        onSelectMediaVersion(version.id)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                        DetailTrackPickerType.Audio -> {
                            val selectedAudioIndex =
                                trackSelection.audioOptions
                                    .indexOfFirst { option ->
                                        option.streamIndex == selectionState.selectedAudioStreamIndex
                                    }.coerceAtLeast(0)
                            itemsIndexed(
                                items = trackSelection.audioOptions,
                                key = { _, option -> "audio-${option.streamIndex}" },
                                contentType = { _, _ -> "audio-track-row" },
                            ) { index, option ->
                                DpadTrackPickerRow(
                                    title = audioLabel(option, index),
                                    selected = selectionState.selectedAudioStreamIndex == option.streamIndex,
                                    focusRequester = firstRowRequester.takeIf { index == selectedAudioIndex },
                                    onClick = { selectionState.selectAudio(option.streamIndex) },
                                )
                            }
                        }
                        DetailTrackPickerType.Subtitles -> {
                            val selectedLocalAssetId = subtitleActions?.selectedLocalAssetId
                            val selectedLocalAsset =
                                trackSelection.localSubtitleOptions.firstOrNull { asset ->
                                    asset.id == selectedLocalAssetId
                                }
                            val selectedSubtitleIndex =
                                trackSelection.subtitleOptions.indexOfFirst { option ->
                                    option.streamIndex == selectionState.selectedSubtitleStreamIndex
                                }
                            item(key = "subtitle-off", contentType = "subtitle-track-row") {
                                DpadTrackPickerRow(
                                    title = stringResource(Res.string.player_subtitle_off),
                                    selected = selectedLocalAssetId == null && selectionState.selectedSubtitleStreamIndex == null,
                                    focusRequester =
                                        firstRowRequester.takeIf {
                                            selectedLocalAssetId == null &&
                                                (selectionState.selectedSubtitleStreamIndex == null || selectedSubtitleIndex < 0)
                                        },
                                    onClick = {
                                        applySubtitleSelection(
                                            selectionState = selectionState,
                                            streamIndex = null,
                                            subtitleActions = subtitleActions,
                                        )
                                    },
                                )
                            }
                            itemsIndexed(
                                items = trackSelection.subtitleOptions,
                                key = { _, option -> "subtitle-${option.streamIndex}" },
                                contentType = { _, _ -> "subtitle-track-row" },
                            ) { index, option ->
                                DpadTrackPickerRow(
                                    title = subtitleLabel(option, index),
                                    selected =
                                        selectedLocalAssetId == null &&
                                            selectionState.selectedSubtitleStreamIndex == option.streamIndex,
                                    focusRequester =
                                        firstRowRequester.takeIf {
                                            selectedLocalAssetId == null &&
                                                selectionState.selectedSubtitleStreamIndex != null &&
                                                index == selectedSubtitleIndex
                                        },
                                    onClick = {
                                        applySubtitleSelection(
                                            selectionState = selectionState,
                                            streamIndex = option.streamIndex,
                                            subtitleActions = subtitleActions,
                                        )
                                    },
                                )
                            }
                            itemsIndexed(
                                items = trackSelection.localSubtitleOptions,
                                key = { _, asset -> "local-subtitle-${asset.id}" },
                                contentType = { _, _ -> "local-subtitle-row" },
                            ) { _, asset ->
                                DpadTrackPickerRow(
                                    title = asset.label,
                                    selected = asset.id == selectedLocalAssetId,
                                    focusRequester = firstRowRequester.takeIf { asset.id == selectedLocalAssetId },
                                    onClick = {
                                        applyLocalSubtitleSelection(
                                            selectionState = selectionState,
                                            assetId = asset.id,
                                            subtitleActions = subtitleActions,
                                        )
                                    },
                                )
                            }
                            subtitleActions?.onSearchSubtitles?.let { onSearch ->
                                item(key = "subtitle-search", contentType = "subtitle-action-row") {
                                    DpadTrackPickerRow(
                                        title = stringResource(Res.string.subtitles_search_action),
                                        selected = false,
                                        focusRequester = null,
                                        onClick = {
                                            onDismiss()
                                            onSearch()
                                        },
                                    )
                                }
                            }
                            if (selectedLocalAsset?.syncState ==
                                com.jellyscope.core.domain.model.LocalSubtitleSyncState.UploadedUnconfirmed
                            ) {
                                subtitleActions?.onRetryLocalAssetSync?.let { onRetry ->
                                    item(key = "subtitle-retry-sync", contentType = "subtitle-action-row") {
                                        DpadTrackPickerRow(
                                            title = stringResource(Res.string.subtitles_retry_upload),
                                            selected = false,
                                            focusRequester = null,
                                            onClick = { onRetry(selectedLocalAsset.id) },
                                        )
                                    }
                                }
                            }
                            if (selectedLocalAsset != null) {
                                subtitleActions?.onDeleteLocalAsset?.let { onDelete ->
                                    item(key = "subtitle-delete", contentType = "subtitle-action-row") {
                                        DpadTrackPickerRow(
                                            title = stringResource(Res.string.subtitles_delete_selected),
                                            selected = false,
                                            focusRequester = null,
                                            onClick = {
                                                onDismiss()
                                                onDelete(selectedLocalAsset.id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val DPAD_PICKER_FOCUS_RETRIES = 6
private const val DPAD_PICKER_FOCUS_RETRY_MS = 16L

@Composable
internal fun DpadTrackPickerDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = DetailDimens.detailTrackPickerDividerVerticalPadding)
                .height(DetailDimens.detailTrackPickerDividerHeight)
                .background(Color.White.copy(alpha = 0.12f)),
    )
}

@Composable
internal fun DpadTrackPickerRow(
    title: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(DetailDimens.panelRadius)
    val palette = LocalJellyfinPalette.current

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = DetailDimens.playerPickerRowHeight)
                .clip(shape)
                .background(
                    if (focused) {
                        Color.White.copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                ).then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { state -> focused = state.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).focusable()
                .semantics {
                    contentDescription = title
                    role = Role.Button
                }.padding(
                    horizontal = DetailDimens.itemGap,
                    vertical = DetailDimens.detailTrackPickerRowVerticalPadding,
                ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailText(
            text = title,
            modifier = Modifier.weight(1f),
            style = DetailBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color = palette.textPrimary,
            maxLines = 2,
        )
        if (selected) {
            Icon(
                imageVector = DetailIcons.CheckBold,
                contentDescription = null,
                tint = palette.cyan,
                modifier = Modifier.size(DetailDimens.detailTrackPickerCheckSize),
            )
        }
    }
}

@Composable
internal fun selectedAudioLabel(
    options: List<AudioTrackOption>,
    selectedStreamIndex: Int?,
): String =
    selectedStreamIndex
        ?.let { streamIndex -> options.firstOrNull { option -> option.streamIndex == streamIndex } }
        ?.let { option -> audioLabel(option, options.indexOf(option)) }
        ?: options.singleOrNull()?.let { option -> audioLabel(option, 0) }
        ?: stringResource(Res.string.detail_track_none)

@Composable
internal fun selectedSubtitleLabel(
    options: List<SubtitleTrackOption>,
    selectedStreamIndex: Int?,
): String =
    selectedStreamIndex
        ?.let { streamIndex -> options.firstOrNull { option -> option.streamIndex == streamIndex } }
        ?.let { option -> subtitleLabel(option, options.indexOf(option)) }
        ?: stringResource(Res.string.player_subtitle_off)

@Composable
internal fun audioLabel(
    option: AudioTrackOption,
    index: Int,
): String = option.displayName ?: stringResource(Res.string.player_audio_track, index + 1)

@Composable
internal fun subtitleLabel(
    option: SubtitleTrackOption,
    index: Int,
): String = option.displayName ?: stringResource(Res.string.player_audio_track, index + 1)
