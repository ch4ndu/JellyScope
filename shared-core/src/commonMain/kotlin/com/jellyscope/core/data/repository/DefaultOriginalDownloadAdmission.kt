// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.LocalSubtitlePayloadTooLargeException
import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.PlayerBackendOverrideStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.checkedArtifactLengthAfterWrite
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.data.remote.OriginalDownloadFailure
import com.jellyscope.core.data.remote.OriginalDownloadPreflightResult
import com.jellyscope.core.data.remote.OriginalDownloadSource
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.model.offlineArtworkReferences
import com.jellyscope.core.domain.model.toDomainMediaItemDetail
import com.jellyscope.core.domain.model.toOfflineDetailSnapshot
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.OriginalDownloadPlaybackCompatibility
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.evaluateOriginalDownloadPlaybackCompatibility
import com.jellyscope.core.domain.playback.resolvePlayerBackend
import com.jellyscope.core.domain.usecase.OriginalDownloadAdmission
import com.jellyscope.core.domain.usecase.OriginalDownloadAdmissionResult
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException

/** Data-layer owner of authenticated Original admission and bounded sidecar localization. */
internal class DefaultOriginalDownloadAdmission(
    private val sessionRepository: SessionRepository,
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    private val jellyfinApi: JellyfinApi,
    private val localSubtitleAssetStore: LocalSubtitleAssetStore,
    private val localSubtitleFileStore: LocalSubtitleFileStore,
    private val playbackPreferencesStore: PlaybackPreferencesStore? = null,
    private val playerBackendOverrideStore: PlayerBackendOverrideStore? = null,
    private val deviceProfileProvider: DeviceProfileProvider? = null,
) : OriginalDownloadAdmission {
    override suspend fun admit(draft: OriginalDownloadDraft): OriginalDownloadAdmissionResult =
        when (val result = admitTrusted(draft, capturePresentation = false)) {
            is TrustedAdmission.Ready -> OriginalDownloadAdmissionResult.Ready(result.request)
            is TrustedAdmission.Rejected -> OriginalDownloadAdmissionResult.Rejected(result.decision)
        }

    override suspend fun admitAndEnqueue(
        draft: OriginalDownloadDraft,
        enqueue: suspend (DownloadRequest) -> DownloadEnqueueResult,
    ): DownloadEnqueueResult =
        when (val result = admitTrusted(draft, capturePresentation = true)) {
            is TrustedAdmission.Rejected -> DownloadEnqueueResult.Rejected(result.decision)
            is TrustedAdmission.Ready ->
                serverScopedStoreRegistry.withGuardedLease(result.lease) {
                    enqueue(result.request)
                } ?: DownloadEnqueueResult.Rejected(DownloadAdmissionDecision.SourceChanged)
        }

    private suspend fun admitTrusted(
        draft: OriginalDownloadDraft,
        capturePresentation: Boolean,
    ): TrustedAdmission {
        val loggedIn =
            sessionRepository.sessionState.value as? SessionState.LoggedIn
                ?: return TrustedAdmission.Rejected(DownloadAdmissionDecision.PermissionDenied)
        val session = loggedIn.session
        if (!session.enableContentDownloading) {
            return TrustedAdmission.Rejected(DownloadAdmissionDecision.PermissionDenied)
        }
        val account = session.accountIdentity()
        if (draft.businessKey.accountIdentity != account) {
            return TrustedAdmission.Rejected(DownloadAdmissionDecision.PermissionDenied)
        }
        val lease =
            serverScopedStoreRegistry.acquireWorkLease(account, loggedIn.boundaryEpoch)
                ?: return TrustedAdmission.Rejected(DownloadAdmissionDecision.PermissionDenied)
        val context = AuthenticatedRequestContext(session.serverUrl, session.userId, session.accessToken)
        val source =
            when (
                val preflight =
                    try {
                        jellyfinApi.preflightOriginalDownload(
                            context = context,
                            itemId = draft.businessKey.itemId,
                            mediaSourceId = draft.businessKey.mediaSourceId,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.ServerUnavailable)
                    }
            ) {
                is OriginalDownloadPreflightResult.Ready -> preflight.source
                is OriginalDownloadPreflightResult.Rejected ->
                    return TrustedAdmission.Rejected(preflight.failure.toDecision())
            }
        if (source.itemId != draft.businessKey.itemId || source.mediaSourceId != draft.businessKey.mediaSourceId) {
            return TrustedAdmission.Rejected(DownloadAdmissionDecision.SourceChanged)
        }
        source.selectionDecision(draft)?.let { decision -> return TrustedAdmission.Rejected(decision) }
        val trustedBackendSource = source.backendSource ?: draft.snapshot.backendSource
        val playbackBackend = resolveOfflinePlaybackBackend(session.accountIdentity(), draft.businessKey.itemId, trustedBackendSource)
        val compatibility =
            if (playbackBackend != null && deviceProfileProvider != null) {
                try {
                    evaluateOriginalDownloadPlaybackCompatibility(
                        source = trustedBackendSource,
                        capabilities = deviceProfileProvider.capabilities(playbackBackend),
                    )
                } catch (_: Throwable) {
                    OriginalDownloadPlaybackCompatibility.Unknown
                }
            } else {
                OriginalDownloadPlaybackCompatibility.Unknown
            }
        if (
            playbackBackend != null &&
            compatibility == OriginalDownloadPlaybackCompatibility.Unsupported
        ) {
            originalDownloadLogger.w {
                "stage=original-download event=admission-rejected reason=PlaybackUnsupported " +
                    "backend=${playbackBackend.name} result=PlaybackUnsupported"
            }
            return TrustedAdmission.Rejected(DownloadAdmissionDecision.PlaybackUnsupported)
        }

        val sidecarBytes =
            try {
                resolveSidecarBytes(draft, account, context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: SidecarAdmissionFailure) {
                return TrustedAdmission.Rejected(failure.decision)
            }
        val packageBytes =
            try {
                checkedArtifactLengthAfterWrite(source.totalBytes, sidecarBytes?.size?.toLong() ?: 0L)
            } catch (_: IllegalArgumentException) {
                return TrustedAdmission.Rejected(DownloadAdmissionDecision.SizeUnavailable)
            } catch (_: IllegalStateException) {
                return TrustedAdmission.Rejected(DownloadAdmissionDecision.SizeUnavailable)
            }
        val snapshot =
            if (capturePresentation) {
                try {
                    capturePresentationSnapshot(draft, context)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    originalDownloadLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "original-download",
                            event = "presentation-detail-failed",
                            operation = DiagnosticOperation.GetItemDetail,
                            throwable = failure,
                        ) + " reason=DetailFetchFailed result=NetworkUnavailable"
                    }
                    return TrustedAdmission.Rejected(DownloadAdmissionDecision.NetworkUnavailable)
                } ?: run {
                    originalDownloadLogger.i {
                        "stage=original-download event=presentation-detail-rejected reason=DetailSnapshotUnavailable result=SourceChanged"
                    }
                    return TrustedAdmission.Rejected(DownloadAdmissionDecision.SourceChanged)
                }
            } else {
                draft.snapshot
            }
        val request =
            DownloadRequest(
                downloadId = draft.downloadId,
                businessKey = draft.businessKey,
                quality = DownloadQuality.Original,
                artifactKind = DownloadArtifactKind.OriginalFile,
                selectedAudioStreamIndex = draft.selectedAudioStreamIndex,
                subtitleSelection = draft.subtitleSelection,
                admissionEstimateBytes = packageBytes,
                initialReservationBytes = packageBytes,
                expectedSourceBytes = source.totalBytes,
                sourceValidator = source.lastModified,
                artifactKey = draft.artifactKey,
                snapshot = snapshot.copy(backendSource = trustedBackendSource),
                createdAtEpochMs = draft.createdAtEpochMs,
            )
        return TrustedAdmission.Ready(request, lease)
    }

    private suspend fun capturePresentationSnapshot(
        draft: OriginalDownloadDraft,
        context: AuthenticatedRequestContext,
    ) = jellyfinApi
        .getItemDetail(context, draft.businessKey.itemId)
        .toDomainMediaItemDetail()
        ?.takeIf { detail -> detail.item.id == draft.businessKey.itemId && detail.item.kind == draft.snapshot.itemKind }
        ?.let { detail ->
            val seriesDetail =
                detail.item.seriesId
                    ?.takeIf(String::isNotBlank)
                    ?.let { seriesId ->
                        try {
                            jellyfinApi.getItemDetail(context, seriesId).toDomainMediaItemDetail()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            originalDownloadLogger.w {
                                formatSafeFailureDiagnostic(
                                    stage = "original-download",
                                    event = "presentation-series-fallback",
                                    operation = DiagnosticOperation.GetItemDetail,
                                    throwable = failure,
                                ) + " reason=ParentDetailUnavailable"
                            }
                            null
                        }
                    }
            draft.snapshot.copy(
                detail = detail.toOfflineDetailSnapshot(),
                artworkReferences = detail.offlineArtworkReferences(seriesDetail),
                presentationCaptureEligible = true,
            )
        }

    private suspend fun resolveOfflinePlaybackBackend(
        accountIdentity: AccountIdentity,
        itemId: String,
        source: BackendSourceDescriptor,
    ): PlayerBackend? {
        val provider = deviceProfileProvider ?: return null
        provider.requiredOfflineBackend?.let { backend -> return backend }
        val preferencesStore = playbackPreferencesStore ?: return null
        return try {
            val preferences = preferencesStore.get(accountIdentity).normalized()
            val itemOverride = playerBackendOverrideStore?.get(accountIdentity.serverId, itemId)
            val requested =
                resolvePlayerBackend(
                    defaultBackend = preferences.defaultPlayerBackend,
                    itemOverride = itemOverride,
                    source = source,
                    avPlayerCapabilities = provider.capabilities(PlayerBackend.AVPlayer),
                    backendPolicy = provider.backendPolicy,
                )
            requested.takeIf { backend -> backend in provider.availableBackends } ?: provider.backendPolicy.defaultBackend
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun resolveSidecarBytes(
        draft: OriginalDownloadDraft,
        account: AccountIdentity,
        context: AuthenticatedRequestContext,
    ): ByteArray? =
        when (val selection = draft.subtitleSelection) {
            DownloadSubtitleSelection.Off,
            is DownloadSubtitleSelection.Embedded,
            -> null
            is DownloadSubtitleSelection.ExternalTextSidecar -> {
                val asset =
                    localSubtitleAssetStore
                        .get(selection.localAssetId)
                        ?.takeIf { item ->
                            item.serverId == account.serverId &&
                                item.userId == account.userId &&
                                item.itemId == draft.businessKey.itemId &&
                                item.mediaSourceId == draft.businessKey.mediaSourceId
                        } ?: throw SidecarAdmissionFailure(DownloadAdmissionDecision.SourceChanged)
                val bytes =
                    try {
                        localSubtitleFileStore.readBounded(asset.fileId, MAX_SIDECAR_BYTES)
                    } catch (tooLarge: LocalSubtitlePayloadTooLargeException) {
                        throw SidecarAdmissionFailure(DownloadAdmissionDecision.UnsupportedArtifact, tooLarge)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        throw SidecarAdmissionFailure(DownloadAdmissionDecision.SourceChanged, failure)
                    } ?: throw SidecarAdmissionFailure(DownloadAdmissionDecision.SourceChanged)
                if (bytes.isEmpty()) throw SidecarAdmissionFailure(DownloadAdmissionDecision.UnsupportedArtifact)
                bytes
            }
            is DownloadSubtitleSelection.ExternalServerTextSidecar -> {
                val bytes =
                    try {
                        jellyfinApi.getSubtitleTextBounded(
                            context = context,
                            itemId = draft.businessKey.itemId,
                            mediaSourceId = draft.businessKey.mediaSourceId,
                            streamIndex = selection.streamIndex,
                            maxBytes = MAX_SIDECAR_BYTES,
                        )
                    } catch (tooLarge: JellyfinApiException.PayloadTooLarge) {
                        throw SidecarAdmissionFailure(DownloadAdmissionDecision.UnsupportedArtifact, tooLarge)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        throw SidecarAdmissionFailure(DownloadAdmissionDecision.SourceChanged, failure)
                    }
                if (bytes.isEmpty()) throw SidecarAdmissionFailure(DownloadAdmissionDecision.UnsupportedArtifact)
                bytes
            }
        }

    private fun OriginalDownloadSource.selectionDecision(draft: OriginalDownloadDraft): DownloadAdmissionDecision? {
        if (draft.selectedAudioStreamIndex != null && draft.selectedAudioStreamIndex !in audioStreamIndices) {
            return DownloadAdmissionDecision.SourceChanged
        }
        return when (val selection = draft.subtitleSelection) {
            DownloadSubtitleSelection.Off,
            is DownloadSubtitleSelection.ExternalTextSidecar,
            -> null
            is DownloadSubtitleSelection.Embedded ->
                if (selection.streamIndex in embeddedSubtitleStreamIndices) null else DownloadAdmissionDecision.SourceChanged
            is DownloadSubtitleSelection.ExternalServerTextSidecar ->
                when {
                    selection.streamIndex in externalSubtitleStreamIndices -> null
                    selection.streamIndex in unsupportedExternalSubtitleStreamIndices ->
                        DownloadAdmissionDecision.UnsupportedArtifact
                    else -> DownloadAdmissionDecision.SourceChanged
                }
        }
    }

    private fun OriginalDownloadFailure.toDecision(): DownloadAdmissionDecision =
        when (this) {
            OriginalDownloadFailure.AccountUnauthorized,
            OriginalDownloadFailure.PermissionDenied,
            -> DownloadAdmissionDecision.PermissionDenied
            OriginalDownloadFailure.SizeUnavailable -> DownloadAdmissionDecision.SizeUnavailable
            OriginalDownloadFailure.SourceUnavailable,
            OriginalDownloadFailure.SourceChanged,
            -> DownloadAdmissionDecision.SourceChanged
            OriginalDownloadFailure.Network,
            OriginalDownloadFailure.ServerUnavailable,
            -> DownloadAdmissionDecision.NetworkUnavailable
        }

    private sealed interface TrustedAdmission {
        data class Ready(
            val request: DownloadRequest,
            val lease: AccountWorkLease,
        ) : TrustedAdmission

        data class Rejected(
            val decision: DownloadAdmissionDecision,
        ) : TrustedAdmission
    }

    private class SidecarAdmissionFailure(
        val decision: DownloadAdmissionDecision,
        cause: Throwable? = null,
    ) : RuntimeException(cause)

    private val originalDownloadLogger = diagnosticLogger(DiagnosticTag.OriginalDownload)

    private companion object {
        const val MAX_SIDECAR_BYTES = 8 * 1024 * 1024
    }
}
