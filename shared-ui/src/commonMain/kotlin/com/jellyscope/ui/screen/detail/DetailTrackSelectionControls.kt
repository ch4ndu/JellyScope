// SPDX-License-Identifier: MPL-2.0
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)

package com.jellyscope.ui.screen.detail

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent

internal class DetailTrackSelectionState(
    audioStreamIndex: Int?,
    subtitleStreamIndex: Int?,
    mediaSourceId: String? = null,
    initialSubtitleExplicitlySelected: Boolean = false,
) {
    var selectedAudioStreamIndex by mutableStateOf(audioStreamIndex)
    var audioExplicitlySelected by mutableStateOf(false)
    var selectedSubtitleStreamIndex by mutableStateOf(subtitleStreamIndex)
    var subtitleExplicitlySelected by mutableStateOf(initialSubtitleExplicitlySelected)
    private var resolvedMediaSourceId = mediaSourceId

    fun audioStreamIndexForPlay(trackSelection: DetailTrackSelectionUi): Int? =
        selectedAudioStreamIndex
            ?.takeIf { audioExplicitlySelected }
            ?.takeIf { streamIndex ->
                trackSelection.audioOptions.any { option -> option.streamIndex == streamIndex }
            }

    fun selectAudio(streamIndex: Int) {
        selectedAudioStreamIndex = streamIndex
        audioExplicitlySelected = true
    }

    fun selectSubtitle(streamIndex: Int?) {
        selectedSubtitleStreamIndex = streamIndex
        subtitleExplicitlySelected = true
    }

    fun subtitleSelectionForPlay(trackSelection: DetailTrackSelectionUi): SubtitleSelectionIntent {
        val resolvedStreamIndex =
            selectedSubtitleStreamIndex?.takeIf { streamIndex ->
                trackSelection.subtitleOptions.any { option -> option.streamIndex == streamIndex }
            }
        return when {
            resolvedStreamIndex != null -> SubtitleSelectionIntent.Track(resolvedStreamIndex)
            subtitleExplicitlySelected && selectedSubtitleStreamIndex == null -> SubtitleSelectionIntent.Off
            else -> SubtitleSelectionIntent.Unspecified
        }
    }

    fun selectLocalSubtitle() {
        selectedSubtitleStreamIndex = null
        subtitleExplicitlySelected = false
    }

    fun reconcile(trackSelection: DetailTrackSelectionUi) {
        if (resolvedMediaSourceId != trackSelection.mediaSourceId) {
            resolvedMediaSourceId = trackSelection.mediaSourceId
            selectedAudioStreamIndex = trackSelection.defaultAudioStreamIndex
            audioExplicitlySelected = false
            selectedSubtitleStreamIndex = trackSelection.defaultSubtitleStreamIndex
            subtitleExplicitlySelected = false
        }
        val audioStreamIndexes = trackSelection.audioOptions.map { option -> option.streamIndex }
        val retainedAudioStreamIndex =
            selectedAudioStreamIndex
                ?.takeIf { streamIndex -> streamIndex in audioStreamIndexes }
        selectedAudioStreamIndex = retainedAudioStreamIndex ?: trackSelection.defaultAudioStreamIndex
        if (retainedAudioStreamIndex == null) {
            audioExplicitlySelected = false
        }

        val subtitleStreamIndexes = trackSelection.subtitleOptions.map { option -> option.streamIndex }
        val retainedSubtitleStreamIndex =
            selectedSubtitleStreamIndex
                ?.takeIf { streamIndex -> streamIndex in subtitleStreamIndexes }
        if (!(subtitleExplicitlySelected && selectedSubtitleStreamIndex == null)) {
            selectedSubtitleStreamIndex = retainedSubtitleStreamIndex ?: trackSelection.defaultSubtitleStreamIndex
            if (retainedSubtitleStreamIndex == null) {
                subtitleExplicitlySelected = false
            }
        }
    }
}

data class DetailSubtitlePickerActions(
    val selectedLocalAssetId: String? = null,
    val onSelectSubtitle: (Int?) -> Unit = {},
    val onSelectLocalAsset: (String) -> Unit = {},
    val onSearchSubtitles: (() -> Unit)? = null,
    val onDeleteLocalAsset: ((String) -> Unit)? = null,
    val onRetryLocalAssetSync: ((String) -> Unit)? = null,
    val returnFocusRequest: Int = 0,
)

internal enum class MediaVersionPickerRestoreTarget {
    Version,
    Play,
}

internal data class MediaVersionPickerPolicy(
    val versions: List<MediaVersionUi>,
    val visible: Boolean,
    val selectedIndex: Int,
    val selectedVersion: MediaVersionUi?,
    val restoreTarget: MediaVersionPickerRestoreTarget,
)

internal fun mediaVersionPickerPolicy(
    versions: List<MediaVersionUi>,
    selectedMediaSourceId: String?,
): MediaVersionPickerPolicy {
    val validVersions =
        versions
            .filter { version -> version.id.isNotBlank() }
            .distinctBy { version -> version.id }
    val selectedIndex =
        validVersions
            .indexOfFirst { version -> version.id == selectedMediaSourceId }
            .takeIf { index -> index >= 0 }
            ?: 0
    val visible = validVersions.size > 1
    return MediaVersionPickerPolicy(
        versions = validVersions,
        visible = visible,
        selectedIndex = selectedIndex,
        selectedVersion = validVersions.getOrNull(selectedIndex),
        restoreTarget =
            if (visible) {
                MediaVersionPickerRestoreTarget.Version
            } else {
                MediaVersionPickerRestoreTarget.Play
            },
    )
}

internal fun shouldDismissVersionPicker(
    openedMediaSourceId: String?,
    versions: List<MediaVersionUi>,
    selectedMediaSourceId: String?,
): Boolean {
    val policy = mediaVersionPickerPolicy(versions, selectedMediaSourceId)
    return !policy.visible || policy.selectedVersion?.id != openedMediaSourceId
}

internal fun applySubtitleSelection(
    selectionState: DetailTrackSelectionState,
    streamIndex: Int?,
    subtitleActions: DetailSubtitlePickerActions?,
) {
    selectionState.selectSubtitle(streamIndex)
    subtitleActions?.onSelectSubtitle?.invoke(streamIndex)
}

internal fun applyLocalSubtitleSelection(
    selectionState: DetailTrackSelectionState,
    assetId: String,
    subtitleActions: DetailSubtitlePickerActions?,
) {
    selectionState.selectLocalSubtitle()
    subtitleActions?.onSelectLocalAsset?.invoke(assetId)
}

@Composable
internal fun rememberDetailTrackSelectionState(
    itemId: String,
    trackSelection: DetailTrackSelectionUi,
): DetailTrackSelectionState {
    val state =
        remember(itemId, trackSelection.mediaSourceId) {
            DetailTrackSelectionState(
                audioStreamIndex = trackSelection.defaultAudioStreamIndex,
                subtitleStreamIndex = trackSelection.defaultSubtitleStreamIndex,
                mediaSourceId = trackSelection.mediaSourceId,
                initialSubtitleExplicitlySelected = trackSelection.initialSubtitleSelection == SubtitleSelectionIntent.Off,
            )
        }
    LaunchedEffect(itemId, trackSelection.mediaSourceId, trackSelection) {
        state.reconcile(trackSelection)
    }
    return state
}

@Composable
internal fun DetailTrackSelectionControls(
    trackSelection: DetailTrackSelectionUi,
    selectionState: DetailTrackSelectionState,
    versions: List<MediaVersionUi> = emptyList(),
    selectedMediaSourceId: String? = null,
    onSelectMediaVersion: (String) -> Unit = {},
    subtitleActions: DetailSubtitlePickerActions? = null,
    modifier: Modifier = Modifier,
) {
    if (!mediaVersionPickerPolicy(versions, selectedMediaSourceId).visible &&
        trackSelection.audioOptions.isEmpty() &&
        trackSelection.subtitleOptions.isEmpty() &&
        trackSelection.localSubtitleOptions.isEmpty() &&
        subtitleActions?.onSearchSubtitles == null
    ) {
        return
    }

    if (isDetailDpadMode()) {
        DpadTrackSelectionControls(
            trackSelection = trackSelection,
            selectionState = selectionState,
            modifier = modifier,
        )
    } else {
        TouchTrackSelectionControls(
            trackSelection = trackSelection,
            selectionState = selectionState,
            versions = versions,
            selectedMediaSourceId = selectedMediaSourceId,
            onSelectMediaVersion = onSelectMediaVersion,
            subtitleActions = subtitleActions,
            modifier = modifier,
        )
    }
}
