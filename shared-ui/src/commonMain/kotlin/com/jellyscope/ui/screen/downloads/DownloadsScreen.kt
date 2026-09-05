// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.DOWNLOAD_BYTES_PER_GB
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.wholeGbDownloadQuotaBytes
import com.jellyscope.core.domain.playback.formatBitrateMbps
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.download_action_cancel
import com.jellyscope.ui.generated.resources.download_action_delete
import com.jellyscope.ui.generated.resources.download_action_pause
import com.jellyscope.ui.generated.resources.download_action_play
import com.jellyscope.ui.generated.resources.download_action_resume
import com.jellyscope.ui.generated.resources.download_action_retry
import com.jellyscope.ui.generated.resources.downloads_allocation
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_cancel
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_confirm
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_gb
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_invalid
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_title
import com.jellyscope.ui.generated.resources.downloads_artifact_in_use
import com.jellyscope.ui.generated.resources.downloads_current_account_stored
import com.jellyscope.ui.generated.resources.downloads_delete_confirm
import com.jellyscope.ui.generated.resources.downloads_delete_message
import com.jellyscope.ui.generated.resources.downloads_delete_title
import com.jellyscope.ui.generated.resources.downloads_device_free
import com.jellyscope.ui.generated.resources.downloads_empty
import com.jellyscope.ui.generated.resources.downloads_error
import com.jellyscope.ui.generated.resources.downloads_failure_missing
import com.jellyscope.ui.generated.resources.downloads_failure_network
import com.jellyscope.ui.generated.resources.downloads_failure_permission
import com.jellyscope.ui.generated.resources.downloads_failure_quota
import com.jellyscope.ui.generated.resources.downloads_failure_size
import com.jellyscope.ui.generated.resources.downloads_failure_source
import com.jellyscope.ui.generated.resources.downloads_failure_storage
import com.jellyscope.ui.generated.resources.downloads_failure_unsupported
import com.jellyscope.ui.generated.resources.downloads_interrupted
import com.jellyscope.ui.generated.resources.downloads_item_details
import com.jellyscope.ui.generated.resources.downloads_item_duration
import com.jellyscope.ui.generated.resources.downloads_item_episode
import com.jellyscope.ui.generated.resources.downloads_item_movie
import com.jellyscope.ui.generated.resources.downloads_manage_allocation
import com.jellyscope.ui.generated.resources.downloads_other_accounts_stored
import com.jellyscope.ui.generated.resources.downloads_over_allocation
import com.jellyscope.ui.generated.resources.downloads_quality_fixed
import com.jellyscope.ui.generated.resources.downloads_quality_original
import com.jellyscope.ui.generated.resources.downloads_remaining_quota
import com.jellyscope.ui.generated.resources.downloads_reservations
import com.jellyscope.ui.generated.resources.downloads_resume_all
import com.jellyscope.ui.generated.resources.downloads_section_completed
import com.jellyscope.ui.generated.resources.downloads_section_downloading
import com.jellyscope.ui.generated.resources.downloads_section_failed
import com.jellyscope.ui.generated.resources.downloads_section_paused
import com.jellyscope.ui.generated.resources.downloads_section_queued
import com.jellyscope.ui.generated.resources.downloads_title
import com.jellyscope.ui.generated.resources.downloads_total_stored
import com.jellyscope.ui.generated.resources.downloads_usage
import com.jellyscope.ui.generated.resources.downloads_usage_error
import com.jellyscope.ui.generated.resources.downloads_usage_loading
import com.jellyscope.ui.generated.resources.downloads_version
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DownloadsScreen(
    session: Session,
    onBack: () -> Unit,
    onPlayOffline: (DownloadRecord) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
    viewModel: DownloadsViewModel =
        koinViewModel(
            parameters = { parametersOf(session) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var allocationDialogVisible by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    OnResumeEffect {
        viewModel.refresh()
        viewModel.wakeQueueOnScreenResume()
    }

    DownloadsContent(
        state = state,
        onBack = onBack,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onResumePausedDownloads = viewModel::resumePausedDownloads,
        onRetry = viewModel::retry,
        onCancel = viewModel::cancel,
        onDelete = { downloadId ->
            pendingDelete = state.records.firstOrNull { record -> record.downloadId.value == downloadId }
        },
        onPlayOffline = onPlayOffline,
        onOpenAllocation = { allocationDialogVisible = true },
        bottomContentPadding = bottomContentPadding,
        modifier = modifier,
    )
    if (allocationDialogVisible) {
        DownloadAllocationDialog(
            initialQuotaBytes = state.settings?.quotaBytes,
            onDismiss = { allocationDialogVisible = false },
            onConfirm = { quotaBytes ->
                allocationDialogVisible = false
                viewModel.configureQuota(quotaBytes)
            },
            maximumQuotaBytes = state.usage?.maximumConfigurableQuotaBytes,
        )
    }
    pendingDelete?.let { record ->
        DeleteDownloadDialog(
            record = record,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                viewModel.delete(record.downloadId.value)
            },
        )
    }
}

@Composable
internal fun DownloadsContent(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onResumePausedDownloads: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPlayOffline: (DownloadRecord) -> Unit,
    onOpenAllocation: () -> Unit,
    bottomContentPadding: Dp = Dimensions.zero,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val listBottomContentPadding =
        maxOf(
            appNavigationBarContentPadding(),
            Dimensions.screenPadding + bottomContentPadding,
        )
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = horizontalContentPadding.start,
                    top = appTopBarContentPadding(),
                    end = horizontalContentPadding.end,
                    bottom = listBottomContentPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.detailSectionSpacing),
        ) {
            item(key = "usage") {
                DownloadsUsageCard(
                    state = state,
                    onOpenAllocation = onOpenAllocation,
                )
            }
            if (state.records.any { record -> record.state == DownloadState.Paused }) {
                item(key = "interrupted-downloads") {
                    DownloadsInterruptedCard(
                        busy = state.isBulkResumeInFlight,
                        onResume = onResumePausedDownloads,
                    )
                }
            }
            if (state.isLoading && state.records.isEmpty()) {
                item(key = "loading") {
                    CircularProgressIndicator(modifier = Modifier.padding(Dimensions.formSpacing))
                }
            } else if (state.records.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.downloads_empty),
                        modifier = Modifier.padding(Dimensions.formSpacing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                state.sections.forEach { section ->
                    item(key = "downloads:section:${section.kind.name}") {
                        Text(
                            text = stringResource(downloadSectionTitle(section.kind)),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = Dimensions.contentSpacing),
                        )
                    }
                    items(
                        items = section.records,
                        key = { record -> record.downloadId.value },
                    ) { record ->
                        DownloadRecordCard(
                            record = record,
                            busy = state.isBulkResumeInFlight || state.inFlightDownloadId == record.downloadId.value,
                            artifactLeased = state.leasedDownloadIds.contains(record.downloadId.value),
                            onPause = { onPause(record.downloadId.value) },
                            onResume = { onResume(record.downloadId.value) },
                            onRetry = { onRetry(record.downloadId.value) },
                            onCancel = { onCancel(record.downloadId.value) },
                            onDelete = { onDelete(record.downloadId.value) },
                            onPlayOffline = { onPlayOffline(record) },
                        )
                    }
                }
            }
            state.error?.let { error ->
                item(key = "error") {
                    Text(
                        text =
                            stringResource(
                                when (error) {
                                    DownloadsUiError.LoadFailed -> Res.string.downloads_error
                                    DownloadsUiError.CommandRejected -> Res.string.downloads_usage_error
                                    DownloadsUiError.QuotaRejected -> Res.string.downloads_usage_error
                                    DownloadsUiError.ArtifactInUse -> Res.string.downloads_artifact_in_use
                                },
                            ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Dimensions.formSpacing),
                    )
                }
            }
        }
        AppTopBar(
            title = stringResource(Res.string.downloads_title),
            visible = true,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DownloadsInterruptedCard(
    busy: Boolean,
    onResume: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimensions.formSpacing),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.downloads_interrupted),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(Dimensions.controlButtonIconSize))
            } else {
                OutlinedButton(onClick = onResume) {
                    Text(stringResource(Res.string.downloads_resume_all))
                }
            }
        }
    }
}

@Composable
private fun DownloadsUsageCard(
    state: DownloadsUiState,
    onOpenAllocation: () -> Unit,
) {
    val usage = state.usage
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.formSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.downloads_usage),
                    modifier = Modifier.weight(1f).padding(start = Dimensions.inlineSpacing),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onOpenAllocation) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = stringResource(Res.string.downloads_manage_allocation),
                    )
                }
            }
            if (usage == null) {
                Text(
                    text = stringResource(Res.string.downloads_usage_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DownloadUsageRow(
                    label = stringResource(Res.string.downloads_total_stored),
                    value = formatIntegerBytes(usage.physicalBytes),
                )
                DownloadUsageRow(
                    label = stringResource(Res.string.downloads_current_account_stored),
                    value = formatIntegerBytes(usage.currentAccountPhysicalBytes),
                )
                DownloadUsageRow(
                    label = stringResource(Res.string.downloads_other_accounts_stored),
                    value = formatIntegerBytes(usage.otherAccountsPhysicalBytes),
                )
                DownloadUsageRow(
                    label = stringResource(Res.string.downloads_reservations),
                    value = formatIntegerBytes(usage.outstandingReservationBytes),
                )
                DownloadUsageRow(
                    label = stringResource(Res.string.downloads_remaining_quota),
                    value =
                        usage.remainingQuotaBytes?.let { bytes -> formatIntegerBytes(bytes) }
                            ?: stringResource(Res.string.downloads_allocation),
                )
                DownloadUsageRow(
                    label = stringResource(Res.string.downloads_device_free),
                    value = formatIntegerBytes(usage.deviceAvailableBytes),
                )
                if (usage.overAllocation) {
                    Text(
                        text = stringResource(Res.string.downloads_over_allocation),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedButton(
                onClick = onOpenAllocation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.downloads_manage_allocation))
            }
        }
    }
}

@Composable
private fun DownloadUsageRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DownloadRecordCard(
    record: DownloadRecord,
    busy: Boolean,
    artifactLeased: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPlayOffline: () -> Unit,
) {
    val snapshot = record.request.snapshot
    val expectedBytes =
        maxOf(
            record.request.expectedSourceBytes ?: 0L,
            record.reservationBytes,
            record.request.admissionEstimateBytes,
        )
    val progress =
        if (expectedBytes > 0L) {
            (record.physicalBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Dimensions.formSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f).padding(start = Dimensions.inlineSpacing)) {
                    Text(snapshot.title, style = MaterialTheme.typography.titleMedium)
                    snapshot.sourcePresentation?.takeIf(String::isNotBlank)?.let { presentation ->
                        Text(
                            text = stringResource(Res.string.downloads_version, presentation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(Dimensions.controlButtonIconSize))
                }
            }
            Text(
                text = snapshotDetails(record),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(record.state.labelResource()),
                color = if (record.state == DownloadState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            if (record.state == DownloadState.Downloading || record.state == DownloadState.Finalizing) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "${formatIntegerBytes(record.physicalBytes)} / ${formatIntegerBytes(expectedBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (record.state == DownloadState.Completed) {
                Text(
                    text = formatIntegerBytes(record.physicalBytes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            record.failure?.let { failure ->
                Text(
                    text = stringResource(failureGuidance(failure)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (record.state == DownloadState.Completed && artifactLeased) {
                Text(
                    text = stringResource(Res.string.downloads_artifact_in_use),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (record.state) {
                    DownloadState.Queued -> Unit
                    DownloadState.Downloading ->
                        DownloadActionButton(
                            icon = Icons.Filled.Pause,
                            label = stringResource(Res.string.download_action_pause),
                            enabled = !busy,
                            onClick = onPause,
                        )
                    DownloadState.Paused,
                    DownloadState.BlockedByQuota,
                    ->
                        DownloadActionButton(
                            icon = Icons.Filled.PlayArrow,
                            label = stringResource(Res.string.download_action_resume),
                            enabled = !busy,
                            onClick = onResume,
                        )
                    DownloadState.Failed ->
                        DownloadActionButton(
                            icon = Icons.Filled.Replay,
                            label = stringResource(Res.string.download_action_retry),
                            enabled = !busy,
                            onClick = onRetry,
                        )
                    else -> Unit
                }
                if (record.state == DownloadState.Completed) {
                    DownloadActionButton(
                        icon = Icons.Filled.PlayArrow,
                        label =
                            stringResource(
                                if (record.localResumePositionMs > 0L) {
                                    Res.string.download_action_resume
                                } else {
                                    Res.string.download_action_play
                                },
                            ),
                        enabled = !busy,
                        onClick = onPlayOffline,
                    )
                    DownloadActionButton(
                        icon = Icons.Filled.Delete,
                        label = stringResource(Res.string.download_action_delete),
                        enabled = !busy && !artifactLeased,
                        onClick = onDelete,
                    )
                } else {
                    DownloadActionButton(
                        icon = Icons.Filled.Delete,
                        label = stringResource(Res.string.download_action_cancel),
                        enabled = !busy,
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = Dimensions.contentSpacing),
        modifier = Modifier.height(Dimensions.minTouchTarget),
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(Dimensions.controlButtonIconSize))
        Spacer(modifier = Modifier.size(Dimensions.inlineSpacing))
        Text(label)
    }
}

@Composable
private fun DownloadAllocationDialog(
    initialQuotaBytes: Long?,
    maximumQuotaBytes: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit,
) {
    var value by remember(initialQuotaBytes) {
        mutableStateOf(initialQuotaBytes?.let { bytes -> (bytes / DOWNLOAD_BYTES_PER_GB).toString() }.orEmpty())
    }
    val parsedGb = value.trim().toLongOrNull()
    val parsedBytes = parsedGb?.let(::wholeGbDownloadQuotaBytes)
    val allocationValid = parsedBytes == null || maximumQuotaBytes == null || parsedBytes <= maximumQuotaBytes
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.downloads_allocation_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { next -> value = next.filter(Char::isDigit) },
                    label = { Text(stringResource(Res.string.downloads_allocation_dialog_gb)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (value.isNotBlank() && (parsedBytes == null || !allocationValid)) {
                    Text(
                        text = stringResource(Res.string.downloads_allocation_dialog_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsedBytes) },
                enabled = value.isBlank() || (parsedBytes != null && allocationValid),
            ) {
                Text(stringResource(Res.string.downloads_allocation_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.downloads_allocation_dialog_cancel))
            }
        },
    )
}

private fun downloadSectionTitle(kind: DownloadsSectionKind): org.jetbrains.compose.resources.StringResource =
    when (kind) {
        DownloadsSectionKind.Completed -> Res.string.downloads_section_completed
        DownloadsSectionKind.Active -> Res.string.downloads_section_downloading
        DownloadsSectionKind.Queued -> Res.string.downloads_section_queued
        DownloadsSectionKind.Paused -> Res.string.downloads_section_paused
        DownloadsSectionKind.Failed -> Res.string.downloads_section_failed
    }

@Composable
private fun snapshotDetails(record: DownloadRecord): String {
    val snapshot = record.request.snapshot
    val downloadQuality = record.request.quality
    val type =
        when (snapshot.itemKind) {
            MediaKind.Movie -> stringResource(Res.string.downloads_item_movie)
            MediaKind.Episode -> stringResource(Res.string.downloads_item_episode)
            else -> snapshot.itemKind.name
        }
    val duration =
        snapshot.durationMs?.let { durationMs ->
            stringResource(Res.string.downloads_item_duration, durationMs / 60_000L)
        } ?: "—"
    val quality =
        when (downloadQuality) {
            DownloadQuality.Original -> stringResource(Res.string.downloads_quality_original)
            is DownloadQuality.Fixed ->
                stringResource(
                    Res.string.downloads_quality_fixed,
                    formatBitrateMbps(downloadQuality.maxBitrateBps),
                )
        }
    return stringResource(Res.string.downloads_item_details, type, duration, quality)
}

private fun failureGuidance(failure: DownloadFailure): org.jetbrains.compose.resources.StringResource =
    when (failure) {
        DownloadFailure.PermissionDenied -> Res.string.downloads_failure_permission
        DownloadFailure.SizeUnavailable -> Res.string.downloads_failure_size
        DownloadFailure.Network,
        DownloadFailure.ServerUnavailable,
        -> Res.string.downloads_failure_network
        DownloadFailure.SourceChanged -> Res.string.downloads_failure_source
        DownloadFailure.QuotaExceeded -> Res.string.downloads_failure_quota
        DownloadFailure.DeviceStorageLow -> Res.string.downloads_failure_storage
        DownloadFailure.MissingArtifact -> Res.string.downloads_failure_missing
        DownloadFailure.UnsupportedArtifact -> Res.string.downloads_failure_unsupported
        DownloadFailure.ArtifactInUse -> Res.string.downloads_artifact_in_use
    }

@Composable
private fun DeleteDownloadDialog(
    record: DownloadRecord,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.downloads_delete_title)) },
        text = { Text(stringResource(Res.string.downloads_delete_message, record.request.snapshot.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.downloads_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.downloads_allocation_dialog_cancel))
            }
        },
    )
}
