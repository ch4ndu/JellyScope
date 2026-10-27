// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadUsage

internal sealed interface DownloadsChange {
    data object RecordsFailed : DownloadsChange

    data class RecordsUpdated(
        val records: List<DownloadRecord>,
        val sections: List<DownloadsSection>,
        val detailsByDownloadId: Map<DownloadId, DownloadDetailUi>,
    ) : DownloadsChange

    data object QuotaStarted : DownloadsChange

    data object QuotaFailed : DownloadsChange

    data object BulkResumeStarted : DownloadsChange

    data class BulkResumeFinished(
        val result: DownloadCommandResult?,
    ) : DownloadsChange

    data class CommandStarted(
        val downloadId: String,
    ) : DownloadsChange

    data class CommandFinished(
        val result: DownloadCommandResult?,
    ) : DownloadsChange

    data class DeletionFinished(
        val result: DownloadDeletionResult?,
    ) : DownloadsChange

    data class LeasesUpdated(
        val leased: Set<String>,
    ) : DownloadsChange

    data object UsageStarted : DownloadsChange

    data object UsageFailed : DownloadsChange

    data class UsageUpdated(
        val usage: DownloadUsage,
        val settings: DownloadSettings,
    ) : DownloadsChange
}

internal fun reduceDownloads(
    current: DownloadsUiState,
    change: DownloadsChange,
): DownloadsUiState =
    when (change) {
        DownloadsChange.RecordsFailed -> current.copy(isLoading = false, error = DownloadsUiError.LoadFailed)
        is DownloadsChange.RecordsUpdated ->
            with(change) {
                current.copy(
                    records = records,
                    sections = sections,
                    detailsByDownloadId = detailsByDownloadId,
                    isLoading = false,
                )
            }
        DownloadsChange.QuotaStarted -> current.copy(isRefreshingUsage = true, error = null)
        DownloadsChange.QuotaFailed -> current.copy(isRefreshingUsage = false, error = DownloadsUiError.QuotaRejected)
        DownloadsChange.BulkResumeStarted -> current.copy(isBulkResumeInFlight = true, error = null)
        is DownloadsChange.BulkResumeFinished ->
            with(change) {
                current.copy(
                    isBulkResumeInFlight = false,
                    error = if (result == DownloadCommandResult.Applied) null else DownloadsUiError.CommandRejected,
                )
            }
        is DownloadsChange.CommandStarted -> with(change) { current.copy(inFlightDownloadId = downloadId, error = null) }
        is DownloadsChange.CommandFinished ->
            with(change) {
                current.copy(
                    inFlightDownloadId = null,
                    error = if (result == DownloadCommandResult.Applied) null else DownloadsUiError.CommandRejected,
                )
            }
        is DownloadsChange.DeletionFinished ->
            with(change) {
                current.copy(
                    inFlightDownloadId = null,
                    error =
                        when (result) {
                            DownloadDeletionResult.Deleted -> null
                            DownloadDeletionResult.ArtifactInUse -> DownloadsUiError.ArtifactInUse
                            else -> DownloadsUiError.CommandRejected
                        },
                )
            }
        is DownloadsChange.LeasesUpdated -> with(change) { current.copy(leasedDownloadIds = leased) }
        DownloadsChange.UsageStarted -> current.copy(isRefreshingUsage = true)
        DownloadsChange.UsageFailed -> current.copy(isRefreshingUsage = false, error = DownloadsUiError.LoadFailed)
        is DownloadsChange.UsageUpdated -> with(change) { current.copy(usage = usage, settings = settings, isRefreshingUsage = false) }
    }
