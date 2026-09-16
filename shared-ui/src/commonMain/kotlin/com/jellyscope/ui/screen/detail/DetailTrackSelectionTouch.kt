// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.hasSelectableSubtitleChoice
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_audio_tracks
import com.jellyscope.ui.generated.resources.detail_subtitle_tracks
import com.jellyscope.ui.generated.resources.detail_version
import com.jellyscope.ui.generated.resources.player_audio
import com.jellyscope.ui.generated.resources.player_subtitle_off
import com.jellyscope.ui.generated.resources.player_subtitles
import com.jellyscope.ui.generated.resources.subtitles_delete_selected
import com.jellyscope.ui.generated.resources.subtitles_retry_upload
import com.jellyscope.ui.generated.resources.subtitles_search_action
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TouchTrackSelectionControls(
    trackSelection: DetailTrackSelectionUi,
    selectionState: DetailTrackSelectionState,
    versions: List<MediaVersionUi> = emptyList(),
    selectedMediaSourceId: String? = null,
    onSelectMediaVersion: (String) -> Unit = {},
    subtitleActions: DetailSubtitlePickerActions? = null,
    showStaticAudioText: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        TouchVersionControl(
            versions = versions,
            selectedMediaSourceId = selectedMediaSourceId,
            onSelected = onSelectMediaVersion,
        )
        TouchAudioControl(
            options = trackSelection.audioOptions,
            selectedStreamIndex = selectionState.selectedAudioStreamIndex,
            onSelected = selectionState::selectAudio,
            showStaticAudioText = showStaticAudioText,
        )
        TouchSubtitleControl(
            trackSelection = trackSelection,
            selectedStreamIndex = selectionState.selectedSubtitleStreamIndex,
            subtitleActions = subtitleActions,
            onSelected = { streamIndex ->
                applySubtitleSelection(
                    selectionState = selectionState,
                    streamIndex = streamIndex,
                    subtitleActions = subtitleActions,
                )
            },
            onLocalAssetSelected = { assetId ->
                applyLocalSubtitleSelection(
                    selectionState = selectionState,
                    assetId = assetId,
                    subtitleActions = subtitleActions,
                )
            },
        )
    }
}

@Composable
internal fun TouchVersionControl(
    versions: List<MediaVersionUi>,
    selectedMediaSourceId: String?,
    onSelected: (String) -> Unit,
) {
    val policy = mediaVersionPickerPolicy(versions, selectedMediaSourceId)
    if (!policy.visible) {
        return
    }
    TouchTrackDropdown(
        label = stringResource(Res.string.detail_version),
        selectedLabel = policy.selectedVersion?.name.orEmpty(),
        options = policy.versions.map { version -> version.id to version.name },
        onSelected = onSelected,
        expandedStateKey = policy.selectedVersion?.id,
    )
}

@Composable
internal fun TouchAudioControl(
    options: List<AudioTrackOption>,
    selectedStreamIndex: Int?,
    onSelected: (Int) -> Unit,
    showStaticAudioText: Boolean = true,
) {
    val selectedLabel = selectedAudioLabel(options = options, selectedStreamIndex = selectedStreamIndex)
    if (options.size <= 1) {
        if (showStaticAudioText) {
            StaticTouchTrackText(text = stringResource(Res.string.detail_audio_tracks, selectedLabel))
        }
    } else {
        TouchTrackDropdown(
            label = stringResource(Res.string.player_audio),
            selectedLabel = selectedLabel,
            options = options.mapIndexed { index, option -> option.streamIndex to audioLabel(option, index) },
            onSelected = onSelected,
        )
    }
}

@Composable
internal fun TouchSubtitleControl(
    trackSelection: DetailTrackSelectionUi,
    selectedStreamIndex: Int?,
    subtitleActions: DetailSubtitlePickerActions?,
    onSelected: (Int?) -> Unit,
    onLocalAssetSelected: (String) -> Unit,
) {
    val options = trackSelection.subtitleOptions
    val selectedLocalAsset =
        trackSelection.localSubtitleOptions.firstOrNull { asset ->
            asset.id == subtitleActions?.selectedLocalAssetId
        }
    val selectedLabel =
        selectedLocalAsset?.label
            ?: selectedSubtitleLabel(options = options, selectedStreamIndex = selectedStreamIndex)
    val hasPickerChoices =
        options.hasSelectableSubtitleChoice() ||
            trackSelection.localSubtitleOptions.isNotEmpty() ||
            subtitleActions?.onSearchSubtitles != null
    if (!hasPickerChoices) {
        StaticTouchTrackText(text = stringResource(Res.string.detail_subtitle_tracks, selectedLabel))
    } else {
        TouchTrackDropdown(
            label = stringResource(Res.string.player_subtitles),
            selectedLabel = selectedLabel,
            options =
                buildList {
                    add(TouchSubtitleChoice.Off to stringResource(Res.string.player_subtitle_off))
                    options.forEachIndexed { index, option ->
                        add(TouchSubtitleChoice.Stream(option.streamIndex) to subtitleLabel(option, index))
                    }
                    trackSelection.localSubtitleOptions.forEach { asset ->
                        add(TouchSubtitleChoice.LocalAsset(asset.id) to asset.label)
                    }
                    if (subtitleActions?.onSearchSubtitles != null) {
                        add(TouchSubtitleChoice.Search to stringResource(Res.string.subtitles_search_action))
                    }
                    if (selectedLocalAsset?.syncState == com.jellyscope.core.domain.model.LocalSubtitleSyncState.UploadedUnconfirmed &&
                        subtitleActions?.onRetryLocalAssetSync != null
                    ) {
                        add(TouchSubtitleChoice.RetrySync to stringResource(Res.string.subtitles_retry_upload))
                    }
                    if (selectedLocalAsset != null && subtitleActions?.onDeleteLocalAsset != null) {
                        add(TouchSubtitleChoice.DeleteLocalAsset to stringResource(Res.string.subtitles_delete_selected))
                    }
                },
            onSelected = { choice ->
                when (choice) {
                    TouchSubtitleChoice.Off -> onSelected(null)
                    is TouchSubtitleChoice.Stream -> onSelected(choice.streamIndex)
                    is TouchSubtitleChoice.LocalAsset -> {
                        onLocalAssetSelected(choice.assetId)
                    }
                    TouchSubtitleChoice.Search -> subtitleActions?.onSearchSubtitles?.invoke()
                    TouchSubtitleChoice.RetrySync -> {
                        selectedLocalAsset?.id?.let { assetId -> subtitleActions?.onRetryLocalAssetSync?.invoke(assetId) }
                    }
                    TouchSubtitleChoice.DeleteLocalAsset -> {
                        selectedLocalAsset?.id?.let { assetId -> subtitleActions?.onDeleteLocalAsset?.invoke(assetId) }
                    }
                }
            },
        )
    }
}

private sealed interface TouchSubtitleChoice {
    data object Off : TouchSubtitleChoice

    data class Stream(
        val streamIndex: Int,
    ) : TouchSubtitleChoice

    data class LocalAsset(
        val assetId: String,
    ) : TouchSubtitleChoice

    data object Search : TouchSubtitleChoice

    data object RetrySync : TouchSubtitleChoice

    data object DeleteLocalAsset : TouchSubtitleChoice
}

@Composable
internal fun StaticTouchTrackText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun <T> TouchTrackDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
    expandedStateKey: Any? = Unit,
) {
    var expanded by remember(expandedStateKey) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { nextExpanded -> expanded = nextExpanded },
            modifier =
                Modifier
                    .widthIn(max = Dimensions.formControlMaxWidth)
                    .fillMaxWidth(),
        ) {
            // Deliberately NOT a read-only OutlinedTextField. `readOnly` blocks
            // editing but leaves real text-input semantics attached, so iOS gives
            // the anchor its native edit menu ("Select All") and selection loupe,
            // and tearing that machinery down on selection delays the dismissal.
            // This renders the same outlined label/value/chevron with no text field.
            Surface(
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .focusable()
                        .semantics(mergeDescendants = true) {}
                        .fillMaxWidth(),
                shape = OutlinedTextFieldDefaults.shape,
                color = Color.Transparent,
                border = BorderStroke(Dimensions.dropdownAnchorBorder, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier =
                        Modifier.padding(
                            horizontal = Dimensions.formSpacing,
                            vertical = Dimensions.contentSpacing,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            text = selectedLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (streamIndex, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onSelected(streamIndex)
                        },
                    )
                }
            }
        }
    }
}
