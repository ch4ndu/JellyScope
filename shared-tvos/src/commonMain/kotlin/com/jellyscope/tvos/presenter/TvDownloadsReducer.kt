// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult

internal sealed interface TvDownloadsChange {
    data object QuotaRejected : TvDownloadsChange

    data object QuotaStarted : TvDownloadsChange

    data object QuotaFailed : TvDownloadsChange

    data object ResumeAllStarted : TvDownloadsChange

    data class ResumeAllFinished(
        val result: DownloadCommandResult?,
    ) : TvDownloadsChange

    data object WakeStarted : TvDownloadsChange

    data class WakeFinished(
        val failed: Boolean,
    ) : TvDownloadsChange

    data object ArtifactInUse : TvDownloadsChange

    data object LoadFailed : TvDownloadsChange

    data class RowsLoaded(
        val projection: TvDownloadsProjection,
    ) : TvDownloadsChange

    data object StorageStarted : TvDownloadsChange

    data object StorageFailed : TvDownloadsChange

    data class StorageLoaded(
        val storage: TvDownloadStorageState,
    ) : TvDownloadsChange

    data class CommandStarted(
        val id: String,
    ) : TvDownloadsChange

    data class CommandFinished(
        val result: DownloadCommandResult?,
    ) : TvDownloadsChange

    data class DeletionFinished(
        val result: DownloadDeletionResult?,
    ) : TvDownloadsChange

    data object StaleConfirmation : TvDownloadsChange
}

internal fun reduceTvDownloads(
    current: TvDownloadsState,
    change: TvDownloadsChange,
): TvDownloadsState =
    when (change) {
        TvDownloadsChange.QuotaRejected ->
            run {
                current.copy(error = TvDownloadsError.QuotaRejected)
            }
        TvDownloadsChange.QuotaStarted ->
            run {
                current.copy(isRefreshingStorage = true, error = null)
            }
        TvDownloadsChange.QuotaFailed ->
            run {
                current.copy(isRefreshingStorage = false, error = TvDownloadsError.QuotaRejected)
            }
        TvDownloadsChange.ResumeAllStarted ->
            run {
                current.copy(isBulkResumeInFlight = true, error = null)
            }
        is TvDownloadsChange.ResumeAllFinished ->
            with(change) {
                current.copy(
                    isBulkResumeInFlight = false,
                    error = if (result == DownloadCommandResult.Applied) null else TvDownloadsError.CommandRejected,
                )
            }
        TvDownloadsChange.WakeStarted ->
            run {
                current.copy(isQueueWakeInFlight = true, error = null)
            }
        is TvDownloadsChange.WakeFinished ->
            with(change) {
                current.copy(
                    isQueueWakeInFlight = false,
                    error = if (!failed) null else TvDownloadsError.QueueWakeRejected,
                )
            }
        TvDownloadsChange.ArtifactInUse ->
            run {
                current.copy(error = TvDownloadsError.ArtifactInUse)
            }
        TvDownloadsChange.LoadFailed ->
            run {
                current.copy(isLoading = false, error = TvDownloadsError.LoadFailed)
            }
        is TvDownloadsChange.RowsLoaded ->
            with(change) {
                current.copy(
                    sections = projection.sections,
                    isLoading = false,
                    hasPausedDownloads = projection.hasPausedDownloads,
                    hasQueuedDownloads = projection.hasQueuedDownloads,
                    error = current.error.takeUnless { error -> error == TvDownloadsError.LoadFailed },
                )
            }
        TvDownloadsChange.StorageStarted ->
            run {
                current.copy(isRefreshingStorage = true)
            }
        TvDownloadsChange.StorageFailed ->
            run {
                current.copy(isRefreshingStorage = false, isLoading = false, error = TvDownloadsError.LoadFailed)
            }
        is TvDownloadsChange.StorageLoaded ->
            with(change) {
                current.copy(storage = storage, isRefreshingStorage = false)
            }
        is TvDownloadsChange.CommandStarted ->
            with(change) {
                current.copy(inFlightDownloadId = id, error = null)
            }
        is TvDownloadsChange.CommandFinished ->
            with(change) {
                current.copy(
                    inFlightDownloadId = null,
                    error = if (result == DownloadCommandResult.Applied) null else TvDownloadsError.CommandRejected,
                )
            }
        is TvDownloadsChange.DeletionFinished ->
            with(change) {
                current.copy(
                    inFlightDownloadId = null,
                    error =
                        when (result) {
                            DownloadDeletionResult.Deleted -> null
                            DownloadDeletionResult.ArtifactInUse -> TvDownloadsError.ArtifactInUse
                            else -> TvDownloadsError.CommandRejected
                        },
                )
            }
        TvDownloadsChange.StaleConfirmation ->
            run {
                current.copy(error = TvDownloadsError.StaleConfirmation)
            }
    }
