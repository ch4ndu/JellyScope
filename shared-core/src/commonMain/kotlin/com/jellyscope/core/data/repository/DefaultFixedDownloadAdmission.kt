// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.FixedDownloadFailure
import com.jellyscope.core.data.remote.FixedDownloadPreflightResult
import com.jellyscope.core.data.remote.FixedDownloadRequest
import com.jellyscope.core.data.remote.FixedDownloadRequestKind
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.model.toFixedDownloadSnapshot
import com.jellyscope.core.domain.usecase.FixedDownloadAdmission
import com.jellyscope.core.domain.usecase.FixedDownloadAdmissionResult
import com.jellyscope.core.download.withFixedDownloadEncodingCleanup
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.safeDiagnosticType
import kotlinx.coroutines.CancellationException

/** Data-layer owner of the exact-source fixed-quality admission boundary. */
internal class DefaultFixedDownloadAdmission(
    private val sessionRepository: SessionRepository,
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    private val jellyfinApi: JellyfinApi,
) : FixedDownloadAdmission {
    override suspend fun admit(draft: FixedDownloadDraft): FixedDownloadAdmissionResult =
        admitTrusted(
            draft = draft,
            requestKind = FixedDownloadRequestKind.AdmissionPreview,
            onRejected = { decision -> FixedDownloadAdmissionResult.Rejected(decision) },
            onReady = { request, _ -> FixedDownloadAdmissionResult.Ready(request) },
        )

    override suspend fun admitAndEnqueue(
        draft: FixedDownloadDraft,
        enqueue: suspend (DownloadRequest) -> DownloadEnqueueResult,
    ): DownloadEnqueueResult =
        admitTrusted(
            draft = draft,
            requestKind = FixedDownloadRequestKind.AdmissionEnqueue,
            onRejected = { decision -> DownloadEnqueueResult.Rejected(decision) },
            onReady = { request, lease ->
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    enqueue(request)
                } ?: DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.SourceChanged)
            },
        )

    private suspend fun <T> admitTrusted(
        draft: FixedDownloadDraft,
        requestKind: FixedDownloadRequestKind,
        onRejected: (DownloadAdmissionDecision) -> T,
        onReady: suspend (DownloadRequest, AccountWorkLease) -> T,
    ): T {
        fun rejected(
            decision: DownloadAdmissionDecision,
            reason: FixedDownloadAdmissionDiagnosticReason,
            throwable: Throwable? = null,
        ): T {
            fixedDownloadAdmissionLogger.w {
                buildString {
                    append("stage=fixed-download event=admission-rejected")
                    append(" reason=${reason.name}")
                    append(" requestKind=${requestKind.name}")
                    append(" result=${decision.name}")
                    throwable?.let { cause ->
                        append(" exceptionType=${cause.safeDiagnosticType()}")
                        cause.cause?.let { nested -> append(" causeType=${nested.safeDiagnosticType()}") }
                    }
                }
            }
            return onRejected(decision)
        }

        val loggedIn =
            sessionRepository.sessionState.value as? SessionState.LoggedIn
                ?: return rejected(
                    DownloadAdmissionDecision.PermissionDenied,
                    FixedDownloadAdmissionDiagnosticReason.SessionUnavailable,
                )
        val session = loggedIn.session
        if (!session.enableContentDownloading) {
            return rejected(
                DownloadAdmissionDecision.PermissionDenied,
                FixedDownloadAdmissionDiagnosticReason.SessionPermissionDenied,
            )
        }
        val account = session.accountIdentity()
        if (draft.businessKey.accountIdentity != account) {
            return rejected(
                DownloadAdmissionDecision.PermissionDenied,
                FixedDownloadAdmissionDiagnosticReason.AccountMismatch,
            )
        }
        val lease =
            serverScopedStoreRegistry.acquireWorkLease(account, loggedIn.boundaryEpoch)
                ?: return rejected(
                    DownloadAdmissionDecision.PermissionDenied,
                    FixedDownloadAdmissionDiagnosticReason.WorkLeaseUnavailable,
                )
        val context = AuthenticatedRequestContext(session.serverUrl, session.userId, session.accessToken)
        val source =
            try {
                when (
                    val result =
                        jellyfinApi.preflightFixedDownload(
                            context = context,
                            request =
                                FixedDownloadRequest(
                                    itemId = draft.businessKey.itemId,
                                    mediaSourceId = draft.businessKey.mediaSourceId,
                                    quality = draft.quality.rung,
                                    audioStreamIndex = draft.selectedAudioStreamIndex,
                                    subtitleSelection = draft.subtitleSelection,
                                    requestKind = requestKind,
                                ),
                        )
                ) {
                    is FixedDownloadPreflightResult.Ready -> result.source
                    is FixedDownloadPreflightResult.Rejected ->
                        return rejected(
                            result.failure.toDecision(),
                            FixedDownloadAdmissionDiagnosticReason.RemotePreflightRejected,
                        )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return rejected(
                    DownloadAdmissionDecision.NetworkUnavailable,
                    FixedDownloadAdmissionDiagnosticReason.RemotePreflightFailed,
                    failure,
                )
            }
        return withFixedDownloadEncodingCleanup(
            cleanup = { jellyfinApi.stopFixedDownloadEncoding(context, source) },
        ) {
            if (source.itemId != draft.businessKey.itemId ||
                source.mediaSourceId != draft.businessKey.mediaSourceId
            ) {
                return@withFixedDownloadEncodingCleanup rejected(
                    DownloadAdmissionDecision.SourceChanged,
                    FixedDownloadAdmissionDiagnosticReason.SourceIdentityChanged,
                )
            }
            if (source.quality != draft.quality.rung ||
                source.audioStreamIndex != draft.selectedAudioStreamIndex &&
                draft.selectedAudioStreamIndex != null ||
                source.subtitleStreamIndex !=
                (draft.subtitleSelection as? DownloadSubtitleSelection.Embedded)?.streamIndex
            ) {
                return@withFixedDownloadEncodingCleanup rejected(
                    DownloadAdmissionDecision.SourceChanged,
                    FixedDownloadAdmissionDiagnosticReason.SelectionChanged,
                )
            }
            val fixedSnapshot =
                draft.snapshot
                    .toFixedDownloadSnapshot(source.audioStreamIndex)
                    ?.copy(durationMs = source.durationMs)
                    ?: return@withFixedDownloadEncodingCleanup rejected(
                        DownloadAdmissionDecision.SourceChanged,
                        FixedDownloadAdmissionDiagnosticReason.SnapshotUnavailable,
                    )
            val request =
                DownloadRequest(
                    downloadId = draft.downloadId,
                    businessKey = draft.businessKey,
                    quality = draft.quality,
                    artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    selectedAudioStreamIndex = source.audioStreamIndex,
                    subtitleSelection = draft.subtitleSelection,
                    admissionEstimateBytes = source.estimatedBytes,
                    initialReservationBytes = source.estimatedBytes,
                    artifactKey = draft.artifactKey,
                    snapshot = fixedSnapshot,
                    createdAtEpochMs = draft.createdAtEpochMs,
                )
            fixedDownloadAdmissionLogger.i {
                "stage=fixed-download event=admission-ready requestKind=${requestKind.name} result=Ready"
            }
            onReady(request, lease)
        }
    }

    private fun FixedDownloadFailure.toDecision(): DownloadAdmissionDecision =
        when (this) {
            FixedDownloadFailure.AccountUnauthorized,
            FixedDownloadFailure.PermissionDenied,
            -> DownloadAdmissionDecision.PermissionDenied
            FixedDownloadFailure.SourceUnavailable,
            FixedDownloadFailure.SourceChanged,
            -> DownloadAdmissionDecision.SourceChanged
            FixedDownloadFailure.SizeUnavailable -> DownloadAdmissionDecision.SizeUnavailable
            FixedDownloadFailure.UnsupportedArtifact,
            FixedDownloadFailure.PayloadTooLarge,
            -> DownloadAdmissionDecision.UnsupportedArtifact
            FixedDownloadFailure.Network,
            FixedDownloadFailure.ServerUnavailable,
            -> DownloadAdmissionDecision.NetworkUnavailable
        }
}

private enum class FixedDownloadAdmissionDiagnosticReason {
    SessionUnavailable,
    SessionPermissionDenied,
    AccountMismatch,
    WorkLeaseUnavailable,
    RemotePreflightRejected,
    RemotePreflightFailed,
    SourceIdentityChanged,
    SelectionChanged,
    SnapshotUnavailable,
}

private val fixedDownloadAdmissionLogger = diagnosticLogger(DiagnosticTag.FixedDownload)
