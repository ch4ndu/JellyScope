// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.wholeGibDownloadQuotaBytes
import com.jellyscope.core.domain.playback.QualityRung
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.formatBitrateMbps
import com.jellyscope.ui.component.SecondaryActionButton
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.download_action_play
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_cancel
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_confirm
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_gib
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_invalid
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_title
import com.jellyscope.ui.generated.resources.downloads_already_exists
import com.jellyscope.ui.generated.resources.downloads_download_title
import com.jellyscope.ui.generated.resources.downloads_enqueue_rejected
import com.jellyscope.ui.generated.resources.downloads_fixed_burn_in_confirm
import com.jellyscope.ui.generated.resources.downloads_fixed_burn_in_message
import com.jellyscope.ui.generated.resources.downloads_fixed_burn_in_title
import com.jellyscope.ui.generated.resources.downloads_fixed_estimate
import com.jellyscope.ui.generated.resources.downloads_fixed_subtitle_external
import com.jellyscope.ui.generated.resources.downloads_fixed_subtitle_title
import com.jellyscope.ui.generated.resources.downloads_fixed_subtitle_unsupported
import com.jellyscope.ui.generated.resources.downloads_fixed_unavailable
import com.jellyscope.ui.generated.resources.downloads_manage
import com.jellyscope.ui.generated.resources.downloads_original_audio_title
import com.jellyscope.ui.generated.resources.downloads_original_confirm
import com.jellyscope.ui.generated.resources.downloads_original_continue_without_subtitles
import com.jellyscope.ui.generated.resources.downloads_original_estimate
import com.jellyscope.ui.generated.resources.downloads_original_subtitle_bitmap
import com.jellyscope.ui.generated.resources.downloads_original_subtitle_off
import com.jellyscope.ui.generated.resources.downloads_original_subtitle_title
import com.jellyscope.ui.generated.resources.downloads_original_title
import com.jellyscope.ui.generated.resources.downloads_original_unavailable
import com.jellyscope.ui.generated.resources.downloads_quality_choice_fixed
import com.jellyscope.ui.generated.resources.downloads_quality_choice_original
import com.jellyscope.ui.generated.resources.downloads_quality_fixed_label
import com.jellyscope.ui.generated.resources.downloads_quality_fixed_none
import com.jellyscope.ui.generated.resources.downloads_quality_section
import com.jellyscope.ui.generated.resources.downloads_quality_up_to
import com.jellyscope.ui.generated.resources.downloads_queued
import com.jellyscope.ui.generated.resources.downloads_removal_in_progress
import com.jellyscope.ui.generated.resources.downloads_schedule_rejected
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OriginalDownloadEntryButton(
    state: DetailDownloadEntryState,
    enabled: Boolean,
    fixedAvailable: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (label, icon) =
        when (state) {
            DetailDownloadEntryState.Add ->
                (if (fixedAvailable) Res.string.downloads_download_title else Res.string.downloads_original_title) to Icons.Filled.Download
            is DetailDownloadEntryState.Manage ->
                Res.string.downloads_manage to Icons.Filled.Download
            is DetailDownloadEntryState.PlayOffline ->
                Res.string.download_action_play to Icons.Filled.PlayArrow
        }
    SecondaryActionButton(
        label = stringResource(label),
        icon = icon,
        active = false,
        enabled = enabled,
        onClick = onClick,
        contentDescription = stringResource(label),
        modifier = modifier,
    )
}

@Composable
internal fun OriginalDownloadDialog(
    detail: DetailUi,
    initialLocalAssetId: String?,
    state: DetailDownloadState,
    fixedAvailable: Boolean,
    onDismiss: () -> Unit,
    onPreview: (Int?, SubtitleSelectionIntent) -> Unit,
    onConfirm: () -> Unit,
    onSaveQuotaAndConfirm: (Long?) -> Unit,
    onPreviewFixed: (DownloadQuality.Fixed, Int?, SubtitleSelectionIntent) -> Unit,
    onConfirmFixedBurnIn: () -> Unit,
    onCancelFixedBurnIn: () -> Unit,
    onConfirmFixed: () -> Unit,
    onSaveQuotaAndConfirmFixed: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fixedMode by remember(detail.itemId, detail.selectedMediaSourceId, fixedAvailable) {
        mutableStateOf(false)
    }
    var fixedQuality by remember(detail.itemId, detail.selectedMediaSourceId) {
        mutableStateOf<DownloadQuality.Fixed?>(null)
    }
    var audioIndex by remember(detail.itemId, detail.selectedMediaSourceId) {
        mutableStateOf(detail.trackSelection.defaultAudioStreamIndex)
    }
    var subtitleSelection by remember(detail.itemId, detail.selectedMediaSourceId, initialLocalAssetId) {
        mutableStateOf(initialOriginalSubtitleSelection(detail, initialLocalAssetId))
    }
    var fixedSubtitleSelection by remember(detail.itemId, detail.selectedMediaSourceId) {
        mutableStateOf<SubtitleSelectionIntent>(SubtitleSelectionIntent.Off)
    }
    var quotaText by remember { mutableStateOf("") }
    val selectedVersion = detail.versions.selectedMediaVersion(detail.selectedMediaSourceId)
    val fixedChoices = fixedDownloadQualityChoices(selectedVersion)
    val selectedFixedQuality =
        fixedQuality ?: fixedChoices.firstOrNull()?.rung?.let { rung -> DownloadQuality.Fixed(rung.maxBitrateBps) }
    val isFixedState =
        state is DetailDownloadState.FixedBurnInConfirmation ||
            state is DetailDownloadState.FixedReady ||
            state is DetailDownloadState.FixedQuotaRequired ||
            state is DetailDownloadState.FixedRejected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isFixedState || fixedMode) {
                        Res.string.downloads_download_title
                    } else {
                        Res.string.downloads_original_title
                    },
                ),
            )
        },
        text = {
            when (state) {
                DetailDownloadState.Idle,
                DetailDownloadState.Previewing,
                -> {
                    Column(
                        modifier = modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
                    ) {
                        Text(text = selectedVersion?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                        if (fixedAvailable) {
                            Text(stringResource(Res.string.downloads_quality_section))
                            RadioChoice(
                                label = stringResource(Res.string.downloads_quality_choice_original),
                                selected = !fixedMode,
                                onClick = {
                                    fixedMode = false
                                    fixedQuality = null
                                },
                            )
                            RadioChoice(
                                label = stringResource(Res.string.downloads_quality_choice_fixed),
                                selected = fixedMode,
                                onClick = {
                                    fixedMode = true
                                    if (fixedQuality == null) fixedQuality = selectedFixedQuality
                                    fixedSubtitleSelection = SubtitleSelectionIntent.Off
                                },
                            )
                        }
                        if (fixedMode) {
                            Text(stringResource(Res.string.downloads_quality_section))
                            if (fixedChoices.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.downloads_quality_fixed_none),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                fixedChoices.forEach { choice ->
                                    RadioChoice(
                                        label = choice.rung.downloadLabel(choice.sourceBitrateKnown),
                                        selected = selectedFixedQuality?.maxBitrateBps == choice.rung.maxBitrateBps,
                                        onClick = { fixedQuality = DownloadQuality.Fixed(choice.rung.maxBitrateBps) },
                                    )
                                }
                            }
                            if (selectedVersion?.trackSelection?.audioOptions?.isNotEmpty() == true) {
                                Text(stringResource(Res.string.downloads_original_audio_title))
                                selectedVersion.trackSelection.audioOptions.forEach { option ->
                                    RadioChoice(
                                        label = option.displayName ?: option.language ?: "Audio",
                                        selected = audioIndex == option.streamIndex,
                                        onClick = { audioIndex = option.streamIndex },
                                    )
                                }
                            }
                            Text(stringResource(Res.string.downloads_fixed_subtitle_title))
                            RadioChoice(
                                label = stringResource(Res.string.downloads_original_subtitle_off),
                                selected = fixedSubtitleSelection == SubtitleSelectionIntent.Off,
                                onClick = { fixedSubtitleSelection = SubtitleSelectionIntent.Off },
                            )
                            selectedVersion?.fixedSubtitleOptions()?.forEach { choice ->
                                val streamIndex = choice.streamIndex
                                val stream = choice.stream
                                RadioChoice(
                                    label = stream.displayTitle ?: stream.title ?: stream.language ?: "Subtitle",
                                    selected = fixedSubtitleSelection == SubtitleSelectionIntent.Track(streamIndex),
                                    onClick = {
                                        fixedSubtitleSelection = SubtitleSelectionIntent.Track(streamIndex)
                                    },
                                )
                            }
                            val hasUnsupported =
                                selectedVersion?.hasUnsupportedFixedSubtitle() == true ||
                                    selectedVersion?.trackSelection?.localSubtitleOptions?.isNotEmpty() == true
                            if (hasUnsupported) {
                                Text(
                                    text = stringResource(Res.string.downloads_fixed_subtitle_unsupported),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(Res.string.downloads_fixed_subtitle_external),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(stringResource(Res.string.downloads_original_subtitle_title))
                            RadioChoice(
                                label = stringResource(Res.string.downloads_original_subtitle_off),
                                selected = subtitleSelection == SubtitleSelectionIntent.Off,
                                onClick = { subtitleSelection = SubtitleSelectionIntent.Off },
                            )
                            selectedVersion?.trackSelection?.subtitleOptions?.forEach { option ->
                                RadioChoice(
                                    label = option.displayName ?: option.language ?: "Subtitle",
                                    selected = subtitleSelection == SubtitleSelectionIntent.Track(option.streamIndex),
                                    onClick = {
                                        subtitleSelection = SubtitleSelectionIntent.Track(option.streamIndex)
                                    },
                                )
                            }
                            selectedVersion?.trackSelection?.localSubtitleOptions?.forEach { asset ->
                                RadioChoice(
                                    label = asset.label,
                                    selected = subtitleSelection == SubtitleSelectionIntent.LocalAsset(asset.id),
                                    onClick = {
                                        subtitleSelection = SubtitleSelectionIntent.LocalAsset(asset.id)
                                    },
                                )
                            }
                            if (selectedVersion?.trackSelection?.audioOptions?.isNotEmpty() == true) {
                                Text(stringResource(Res.string.downloads_original_audio_title))
                                selectedVersion.trackSelection.audioOptions.forEach { option ->
                                    RadioChoice(
                                        label = option.displayName ?: option.language ?: "Audio",
                                        selected = audioIndex == option.streamIndex,
                                        onClick = { audioIndex = option.streamIndex },
                                    )
                                }
                            }
                        }
                        if (state == DetailDownloadState.Previewing) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                }
                is DetailDownloadState.BitmapSubtitleConfirmation ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                        Text(stringResource(Res.string.downloads_original_subtitle_bitmap))
                        Text(
                            text = stringResource(Res.string.downloads_original_continue_without_subtitles),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                is DetailDownloadState.Ready ->
                    OriginalDownloadReadyText(state.request)
                is DetailDownloadState.QuotaRequired ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                        OriginalDownloadReadyText(state.request)
                        Text(stringResource(Res.string.downloads_allocation_dialog_title))
                        OutlinedTextField(
                            value = quotaText,
                            onValueChange = { next -> quotaText = next.filter(Char::isDigit) },
                            label = { Text(stringResource(Res.string.downloads_allocation_dialog_gib)) },
                            singleLine = true,
                        )
                        val bytes = quotaText.trim().toLongOrNull()?.let(::wholeGibDownloadQuotaBytes)
                        if (quotaText.isNotBlank() && bytes == null) {
                            Text(
                                text = stringResource(Res.string.downloads_allocation_dialog_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                is DetailDownloadState.FixedBurnInConfirmation ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                        Text(stringResource(Res.string.downloads_fixed_burn_in_title))
                        Text(
                            stringResource(
                                Res.string.downloads_fixed_burn_in_message,
                                state.subtitleLabel,
                            ),
                        )
                    }
                is DetailDownloadState.FixedReady ->
                    FixedDownloadReadyText(state.request)
                is DetailDownloadState.FixedQuotaRequired ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                        FixedDownloadReadyText(state.request)
                        Text(stringResource(Res.string.downloads_allocation_dialog_title))
                        OutlinedTextField(
                            value = quotaText,
                            onValueChange = { next -> quotaText = next.filter(Char::isDigit) },
                            label = { Text(stringResource(Res.string.downloads_allocation_dialog_gib)) },
                            singleLine = true,
                        )
                        val bytes = quotaText.trim().toLongOrNull()?.let(::wholeGibDownloadQuotaBytes)
                        if (quotaText.isNotBlank() && bytes == null) {
                            Text(
                                text = stringResource(Res.string.downloads_allocation_dialog_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                is DetailDownloadState.FixedRejected ->
                    Text(stringResource(Res.string.downloads_fixed_unavailable))
                is DetailDownloadState.Rejected ->
                    Text(stringResource(Res.string.downloads_original_unavailable))
                is DetailDownloadState.Created ->
                    Text(stringResource(Res.string.downloads_queued))
                is DetailDownloadState.Existing ->
                    Text(stringResource(Res.string.downloads_already_exists))
                is DetailDownloadState.SchedulingRejected ->
                    Text(stringResource(Res.string.downloads_schedule_rejected))
                is DetailDownloadState.EnqueueRejected ->
                    Text(stringResource(Res.string.downloads_enqueue_rejected))
                DetailDownloadState.RemovalInProgress ->
                    Text(stringResource(Res.string.downloads_removal_in_progress))
            }
        },
        confirmButton = {
            when (state) {
                DetailDownloadState.Idle ->
                    TextButton(
                        enabled = !fixedMode || selectedFixedQuality != null,
                        onClick = {
                            if (fixedMode) {
                                selectedFixedQuality?.let { quality ->
                                    onPreviewFixed(quality, audioIndex, fixedSubtitleSelection)
                                }
                            } else {
                                onPreview(audioIndex, subtitleSelection)
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.downloads_original_confirm))
                    }
                DetailDownloadState.Previewing -> Unit
                is DetailDownloadState.BitmapSubtitleConfirmation ->
                    TextButton(onClick = { onPreview(audioIndex, SubtitleSelectionIntent.Off) }) {
                        Text(stringResource(Res.string.downloads_original_continue_without_subtitles))
                    }
                is DetailDownloadState.Ready ->
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(Res.string.downloads_original_confirm))
                    }
                is DetailDownloadState.QuotaRequired -> {
                    val bytes = quotaText.trim().toLongOrNull()?.let(::wholeGibDownloadQuotaBytes)
                    TextButton(
                        enabled =
                            bytes != null &&
                                (state.maximumQuotaBytes == null || bytes <= state.maximumQuotaBytes),
                        onClick = { onSaveQuotaAndConfirm(bytes) },
                    ) {
                        Text(stringResource(Res.string.downloads_allocation_dialog_confirm))
                    }
                }
                is DetailDownloadState.FixedBurnInConfirmation ->
                    TextButton(onClick = onConfirmFixedBurnIn) {
                        Text(stringResource(Res.string.downloads_fixed_burn_in_confirm))
                    }
                is DetailDownloadState.FixedReady ->
                    TextButton(onClick = onConfirmFixed) {
                        Text(stringResource(Res.string.downloads_original_confirm))
                    }
                is DetailDownloadState.FixedQuotaRequired -> {
                    val bytes = quotaText.trim().toLongOrNull()?.let(::wholeGibDownloadQuotaBytes)
                    TextButton(
                        enabled =
                            bytes != null &&
                                (state.maximumQuotaBytes == null || bytes <= state.maximumQuotaBytes),
                        onClick = { onSaveQuotaAndConfirmFixed(bytes) },
                    ) {
                        Text(stringResource(Res.string.downloads_allocation_dialog_confirm))
                    }
                }
                is DetailDownloadState.FixedRejected -> Unit
                is DetailDownloadState.Rejected,
                is DetailDownloadState.Created,
                is DetailDownloadState.Existing,
                is DetailDownloadState.SchedulingRejected,
                is DetailDownloadState.EnqueueRejected,
                DetailDownloadState.RemovalInProgress,
                -> Unit
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (state is DetailDownloadState.FixedBurnInConfirmation) {
                        onCancelFixedBurnIn()
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(stringResource(Res.string.downloads_allocation_dialog_cancel))
            }
        },
    )
}

@Composable
private fun OriginalDownloadReadyText(request: DownloadRequest) {
    Text(stringResource(Res.string.downloads_original_estimate, formatDownloadBytes(request.admissionEstimateBytes)))
}

@Composable
private fun FixedDownloadReadyText(request: DownloadRequest) {
    Text(stringResource(Res.string.downloads_fixed_estimate, formatDownloadBytes(request.admissionEstimateBytes)))
}

@Composable
private fun QualityRung.downloadLabel(sourceBitrateKnown: Boolean): String =
    if (sourceBitrateKnown) {
        stringResource(
            Res.string.downloads_quality_fixed_label,
            height,
            formatBitrateMbps(maxBitrateBps),
        )
    } else {
        stringResource(
            Res.string.downloads_quality_up_to,
            height,
            formatBitrateMbps(maxBitrateBps),
        )
    }

@Composable
private fun RadioChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = Dimensions.inlineSpacing))
    }
}

private fun initialOriginalSubtitleSelection(
    detail: DetailUi,
    localAssetId: String?,
): SubtitleSelectionIntent {
    if (localAssetId != null) return SubtitleSelectionIntent.LocalAsset(localAssetId)
    return when (val selection = detail.trackSelection.initialSubtitleSelection) {
        SubtitleSelectionIntent.Unspecified ->
            detail.trackSelection.defaultSubtitleStreamIndex?.let(SubtitleSelectionIntent::Track)
                ?: SubtitleSelectionIntent.Off
        else -> selection
    }
}

private fun formatDownloadBytes(bytes: Long): String =
    when {
        bytes >= 1_073_741_824L -> "${(bytes / 1_073_741_824.0).toTenths()} GiB"
        bytes >= 1_048_576L -> "${(bytes / 1_048_576.0).toTenths()} MiB"
        else -> "$bytes B"
    }

private fun Double.toTenths(): String {
    val tenths = kotlin.math.round(this * 10.0).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
