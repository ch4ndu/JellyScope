// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.FixedDownloadFailure
import com.jellyscope.core.data.remote.FixedDownloadPreflightResult
import com.jellyscope.core.data.remote.FixedDownloadRequest
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
            onRejected = { decision -> FixedDownloadAdmissionResult.Rejected(decision) },
            onReady = { request, _ -> FixedDownloadAdmissionResult.Ready(request) },
        )

    override suspend fun admitAndEnqueue(
        draft: FixedDownloadDraft,
        enqueue: suspend (DownloadRequest) -> DownloadEnqueueResult,
    ): DownloadEnqueueResult =
        admitTrusted(
            draft = draft,
            onRejected = { decision -> DownloadEnqueueResult.Rejected(decision) },
            onReady = { request, lease ->
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    enqueue(request)
                } ?: DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.SourceChanged)
            },
        )

    private suspend fun <T> admitTrusted(
        draft: FixedDownloadDraft,
        onRejected: (DownloadAdmissionDecision) -> T,
        onReady: suspend (DownloadRequest, AccountWorkLease) -> T,
    ): T {
        val loggedIn =
            sessionRepository.sessionState.value as? SessionState.LoggedIn
                ?: return onRejected(DownloadAdmissionDecision.PermissionDenied)
        val session = loggedIn.session
        if (!session.enableContentDownloading) {
            return onRejected(DownloadAdmissionDecision.PermissionDenied)
        }
        val account = session.accountIdentity()
        if (draft.businessKey.accountIdentity != account) {
            return onRejected(DownloadAdmissionDecision.PermissionDenied)
        }
        val lease =
            serverScopedStoreRegistry.acquireWorkLease(account, loggedIn.boundaryEpoch)
                ?: return onRejected(DownloadAdmissionDecision.PermissionDenied)
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
                                ),
                        )
                ) {
                    is FixedDownloadPreflightResult.Ready -> result.source
                    is FixedDownloadPreflightResult.Rejected ->
                        return onRejected(result.failure.toDecision())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return onRejected(DownloadAdmissionDecision.NetworkUnavailable)
            }
        return withFixedDownloadEncodingCleanup(
            cleanup = { jellyfinApi.stopFixedDownloadEncoding(context, source) },
        ) {
            if (source.itemId != draft.businessKey.itemId ||
                source.mediaSourceId != draft.businessKey.mediaSourceId
            ) {
                return@withFixedDownloadEncodingCleanup onRejected(
                    DownloadAdmissionDecision.SourceChanged,
                )
            }
            if (source.quality != draft.quality.rung ||
                source.audioStreamIndex != draft.selectedAudioStreamIndex &&
                draft.selectedAudioStreamIndex != null ||
                source.subtitleStreamIndex !=
                (draft.subtitleSelection as? DownloadSubtitleSelection.Embedded)?.streamIndex
            ) {
                return@withFixedDownloadEncodingCleanup onRejected(
                    DownloadAdmissionDecision.SourceChanged,
                )
            }
            val fixedSnapshot =
                draft.snapshot
                    .toFixedDownloadSnapshot(source.audioStreamIndex)
                    ?.copy(durationMs = source.durationMs)
                    ?: return@withFixedDownloadEncodingCleanup onRejected(
                        DownloadAdmissionDecision.SourceChanged,
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
