// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.DOWNLOAD_BYTES_PER_GB
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.OfflineArtworkRole
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.wholeGbDownloadQuotaBytes
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.downloads_allocation
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_cancel
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_confirm
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_gb
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_invalid
import com.jellyscope.ui.generated.resources.downloads_allocation_dialog_title
import com.jellyscope.ui.generated.resources.downloads_artifact_in_use
import com.jellyscope.ui.generated.resources.downloads_background_available
import com.jellyscope.ui.generated.resources.downloads_background_requires_ios26
import com.jellyscope.ui.generated.resources.downloads_current_account_stored
import com.jellyscope.ui.generated.resources.downloads_device_free
import com.jellyscope.ui.generated.resources.downloads_empty
import com.jellyscope.ui.generated.resources.downloads_error
import com.jellyscope.ui.generated.resources.downloads_interrupted
import com.jellyscope.ui.generated.resources.downloads_manage_allocation
import com.jellyscope.ui.generated.resources.downloads_other_accounts_stored
import com.jellyscope.ui.generated.resources.downloads_over_allocation
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
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DownloadsScreen(
    session: Session,
    onBack: () -> Unit,
    onOpenDownloadDetail: (DownloadId) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
    viewModel: DownloadsViewModel =
        koinViewModel(
            parameters = { parametersOf(session) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var allocationDialogVisible by remember { mutableStateOf(false) }
    var lastSelectedDownloadId by rememberSaveable(session.serverId, session.userId) { mutableStateOf<String?>(null) }
    var lastSelectedSemanticIndex by rememberSaveable(session.serverId, session.userId) { mutableStateOf<Int?>(null) }
    var pendingRestoreDownloadId by rememberSaveable(session.serverId, session.userId) { mutableStateOf<String?>(null) }
    OnResumeEffect {
        viewModel.refresh()
        viewModel.wakeQueueOnScreenResume()
        pendingRestoreDownloadId = lastSelectedDownloadId
    }

    DownloadsContent(
        session = session,
        state = state,
        onBack = onBack,
        onResumePausedDownloads = viewModel::resumePausedDownloads,
        onOpenDownloadDetail = { downloadId ->
            lastSelectedDownloadId = downloadId.value
            lastSelectedSemanticIndex = state.downloadGridSemanticIndex(downloadId)
            onOpenDownloadDetail(downloadId)
        },
        onOpenAllocation = { allocationDialogVisible = true },
        bottomContentPadding = bottomContentPadding,
        viewModel = viewModel,
        restoreDownloadId = pendingRestoreDownloadId?.let(::DownloadId),
        restoreSemanticIndex = lastSelectedSemanticIndex,
        onRestoreConsumed = { pendingRestoreDownloadId = null },
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
}

@Composable
internal fun DownloadsContent(
    session: Session,
    state: DownloadsUiState,
    onBack: () -> Unit,
    onResumePausedDownloads: () -> Unit,
    onOpenDownloadDetail: (DownloadId) -> Unit,
    onOpenAllocation: () -> Unit,
    bottomContentPadding: Dp = Dimensions.zero,
    viewModel: DownloadsViewModel,
    restoreDownloadId: DownloadId? = null,
    restoreSemanticIndex: Int? = null,
    onRestoreConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listBottomContentPadding =
        maxOf(
            appNavigationBarContentPadding(),
            Dimensions.screenPadding + bottomContentPadding,
        )
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val gridState =
        androidx.compose.foundation.lazy.grid
            .rememberLazyGridState()
    LaunchedEffect(restoreDownloadId, restoreSemanticIndex, state.sections) {
        val downloadId = restoreDownloadId ?: return@LaunchedEffect
        state.downloadGridRestoreSlot(downloadId, restoreSemanticIndex)?.let { slot ->
            gridState.scrollToItem(slot)
        }
        onRestoreConsumed()
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(Dimensions.gridMinCellWidth.tileScaled()),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = horizontalContentPadding.start,
                    top = appTopBarContentPadding(),
                    end = horizontalContentPadding.end,
                    bottom = listBottomContentPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.detailSectionSpacing),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            item(
                key = "usage",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                DownloadsUsageCard(
                    state = state,
                    onOpenAllocation = onOpenAllocation,
                )
            }
            if (state.records.any { record -> record.state == DownloadState.Paused }) {
                item(
                    key = "interrupted-downloads",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    DownloadsInterruptedCard(
                        busy = state.isBulkResumeInFlight,
                        onResume = onResumePausedDownloads,
                    )
                }
            }
            if (state.isLoading && state.records.isEmpty()) {
                item(
                    key = "loading",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(Dimensions.formSpacing))
                }
            } else if (state.records.isEmpty()) {
                item(
                    key = "empty",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        text = stringResource(Res.string.downloads_empty),
                        modifier = Modifier.padding(Dimensions.formSpacing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                state.sections.forEach { section ->
                    item(
                        key = "downloads:section:${section.kind.name}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
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
                        DownloadGridCard(
                            session = session,
                            record = record,
                            card = state.detailsByDownloadId.getValue(record.downloadId).card,
                            viewModel = viewModel,
                            onClick = { onOpenDownloadDetail(record.downloadId) },
                        )
                    }
                }
            }
            state.error?.let { error ->
                item(
                    key = "error",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
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
    val backgroundDownloadsAvailable = LocalPlatformCapabilities.current.backgroundDownloadsAvailable
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
            backgroundDownloadsAvailable?.let { available ->
                Text(
                    text =
                        stringResource(
                            if (available) {
                                Res.string.downloads_background_available
                            } else {
                                Res.string.downloads_background_requires_ios26
                            },
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
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
private fun DownloadGridCard(
    session: Session,
    record: DownloadRecord,
    card: com.jellyscope.ui.component.MediaCardUi,
    viewModel: DownloadsViewModel,
    onClick: () -> Unit,
) {
    com.jellyscope.ui.component.MediaCard(
        item = card,
        session = session,
        onClick = onClick,
        fillWidth = true,
        artwork = {
            DownloadArtworkImage(
                session = session,
                record = record,
                role = OfflineArtworkRole.Poster,
                viewModel = viewModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
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
