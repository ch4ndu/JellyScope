// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadEnqueueResult

internal sealed interface TvDownloadRequestChange {
    data class QualitySelected(
        val qualityId: String,
        val fixed: Boolean,
    ) : TvDownloadRequestChange

    data class SubtitleOffChanged(
        val off: Boolean,
    ) : TvDownloadRequestChange

    data object ConfirmationDismissed : TvDownloadRequestChange

    data object EnqueueStarted : TvDownloadRequestChange

    data class EnqueueFinished(
        val result: DownloadEnqueueResult,
    ) : TvDownloadRequestChange

    data object SubtitleUnavailable : TvDownloadRequestChange

    data class OriginalConfirmation(
        val subtitleLabel: String,
    ) : TvDownloadRequestChange

    data class BurnInConfirmation(
        val subtitleLabel: String,
    ) : TvDownloadRequestChange

    data object SourceChanged : TvDownloadRequestChange

    data object PreviewStarted : TvDownloadRequestChange

    data class Ready(
        val estimatedBytes: Long,
    ) : TvDownloadRequestChange

    data class PreviewRejected(
        val error: TvDownloadRequestError,
    ) : TvDownloadRequestChange

    data class LoadPublished(
        val state: TvDownloadRequestState,
    ) : TvDownloadRequestChange
}

internal fun reduceTvDownloadRequest(
    current: TvDownloadRequestState,
    change: TvDownloadRequestChange,
): TvDownloadRequestState =
    when (change) {
        is TvDownloadRequestChange.QualitySelected ->
            with(change) {
                current.copy(
                    stage = TvDownloadRequestStage.Choosing,
                    selectedQualityId = qualityId,
                    fixedSubtitleOff = if (fixed) current.fixedSubtitleOff else false,
                    estimatedBytes = null,
                    warningSubtitleLabel = null,
                    error = null,
                    outcome = null,
                )
            }
        is TvDownloadRequestChange.SubtitleOffChanged ->
            with(change) {
                current.copy(
                    stage = TvDownloadRequestStage.Choosing,
                    fixedSubtitleOff = off,
                    estimatedBytes = null,
                    warningSubtitleLabel = null,
                    error = null,
                    outcome = null,
                )
            }
        TvDownloadRequestChange.ConfirmationDismissed ->
            run {
                current.copy(
                    stage = TvDownloadRequestStage.Choosing,
                    warningSubtitleLabel = null,
                    error = null,
                )
            }
        TvDownloadRequestChange.EnqueueStarted ->
            run {
                current.copy(stage = TvDownloadRequestStage.Submitting, error = null)
            }
        is TvDownloadRequestChange.EnqueueFinished ->
            with(change) {
                result.applyTo(current)
            }
        TvDownloadRequestChange.SubtitleUnavailable ->
            run {
                current.copy(stage = TvDownloadRequestStage.Choosing, error = TvDownloadRequestError.SubtitleUnavailable)
            }
        is TvDownloadRequestChange.OriginalConfirmation ->
            with(change) {
                current.copy(
                    stage = TvDownloadRequestStage.ConfirmOriginalWithoutSubtitle,
                    warningSubtitleLabel = subtitleLabel,
                    error = null,
                )
            }
        is TvDownloadRequestChange.BurnInConfirmation ->
            with(change) {
                current.copy(
                    stage = TvDownloadRequestStage.ConfirmFixedBurnIn,
                    warningSubtitleLabel = subtitleLabel,
                    error = null,
                )
            }
        TvDownloadRequestChange.SourceChanged ->
            run {
                current.copy(stage = TvDownloadRequestStage.Failed, error = TvDownloadRequestError.SourceChanged)
            }
        TvDownloadRequestChange.PreviewStarted ->
            run {
                current.copy(
                    stage = TvDownloadRequestStage.Previewing,
                    estimatedBytes = null,
                    warningSubtitleLabel = null,
                    error = null,
                    outcome = null,
                )
            }
        is TvDownloadRequestChange.Ready ->
            with(change) {
                current.copy(stage = TvDownloadRequestStage.Ready, estimatedBytes = estimatedBytes)
            }
        is TvDownloadRequestChange.PreviewRejected ->
            with(change) {
                current.copy(
                    stage = TvDownloadRequestStage.Choosing,
                    estimatedBytes = null,
                    error = error,
                )
            }
        is TvDownloadRequestChange.LoadPublished -> change.state
    }

private fun DownloadEnqueueResult.applyTo(state: TvDownloadRequestState): TvDownloadRequestState =
    when (this) {
        is DownloadEnqueueResult.Created ->
            state.copy(stage = TvDownloadRequestStage.Finished, outcome = TvDownloadRequestOutcome.Queued, error = null)
        is DownloadEnqueueResult.Existing ->
            state.copy(stage = TvDownloadRequestStage.Finished, outcome = TvDownloadRequestOutcome.Existing, error = null)
        is DownloadEnqueueResult.SchedulingRejected ->
            state.copy(stage = TvDownloadRequestStage.Finished, outcome = TvDownloadRequestOutcome.SchedulingDelayed, error = null)
        is DownloadEnqueueResult.Rejected ->
            state.copy(
                stage = TvDownloadRequestStage.Choosing,
                estimatedBytes = null,
                outcome = null,
                error = decision.toTvDownloadRequestError(),
            )
        DownloadEnqueueResult.RemovalInProgress ->
            state.copy(
                stage = TvDownloadRequestStage.Choosing,
                estimatedBytes = null,
                outcome = null,
                error = TvDownloadRequestError.RemovalInProgress,
            )
    }

internal fun DownloadAdmissionDecision.toTvDownloadRequestError(): TvDownloadRequestError =
    when (this) {
        DownloadAdmissionDecision.PermissionDenied -> TvDownloadRequestError.PermissionDenied
        DownloadAdmissionDecision.QuotaUnconfigured -> TvDownloadRequestError.QuotaUnconfigured
        DownloadAdmissionDecision.QuotaExceeded -> TvDownloadRequestError.QuotaExceeded
        DownloadAdmissionDecision.DeviceStorageLow -> TvDownloadRequestError.DeviceStorageLow
        DownloadAdmissionDecision.SizeUnavailable -> TvDownloadRequestError.SizeUnavailable
        DownloadAdmissionDecision.SourceChanged -> TvDownloadRequestError.SourceChanged
        DownloadAdmissionDecision.NetworkUnavailable -> TvDownloadRequestError.NetworkUnavailable
        DownloadAdmissionDecision.UnsupportedArtifact -> TvDownloadRequestError.UnsupportedArtifact
        DownloadAdmissionDecision.PlaybackUnsupported -> TvDownloadRequestError.PlaybackUnsupported
        DownloadAdmissionDecision.Allowed -> TvDownloadRequestError.Unknown
    }
