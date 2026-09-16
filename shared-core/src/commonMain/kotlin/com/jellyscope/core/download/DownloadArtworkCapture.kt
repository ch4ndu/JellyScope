// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.DOWNLOAD_PRESENTATION_TOTAL_MAX_BYTES
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadPresentationInspection
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.DownloadArtworkTransport
import com.jellyscope.core.domain.model.DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.OfflineArtworkReference
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val downloadArtworkLogger = diagnosticLogger(DiagnosticTag.DownloadExecution)

/**
 * Bounded best-effort presentation capture owned by the live download writer.
 *
 * Network reads happen before the guarded mutation. The resulting bounded bytes are staged,
 * quota-accounted, and atomically published only while the original account lease and registered
 * attempt remain current. This is deliberately not a second transfer state machine.
 */
internal class DownloadArtworkCapture(
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    private val queueCoordinator: DownloadQueueCoordinator,
    private val artifactStore: DownloadArtifactStore,
    private val transport: DownloadArtworkTransport,
) {
    suspend fun capture(
        record: DownloadRecord,
        attempt: DownloadAttemptIdentity,
        lease: AccountWorkLease,
        context: AuthenticatedRequestContext,
    ) {
        if (!record.request.snapshot.presentationCaptureEligible ||
            record.request.snapshot.artworkReferences
                .isEmpty()
        ) {
            return
        }

        var persistedBytes = record.presentationBytes
        try {
            // A storage mutation and its caller-side byte count must finish together. Restore
            // cancellation immediately afterward before any later decision or network read.
            val initialized =
                withContext(NonCancellable) {
                    val reconciled =
                        serverScopedStoreRegistry.withGuardedLease(lease) {
                            reconcileAndSynchronize(record, attempt, persistedBytes)
                        } ?: return@withContext false
                    persistedBytes = reconciled.persistedBytes
                    true
                }
            currentCoroutineContext().ensureActive()
            if (!initialized) return
            record.request.snapshot.artworkReferences.forEach { reference ->
                val existing =
                    withContext(NonCancellable) {
                        val reconciled =
                            serverScopedStoreRegistry.withGuardedLease(lease) {
                                reconcileAndSynchronize(record, attempt, persistedBytes)
                            } ?: return@withContext null
                        persistedBytes = reconciled.persistedBytes
                        reconciled
                    }
                currentCoroutineContext().ensureActive()
                if (existing == null) return
                if (reference.role in existing.inspection.roleBytes) return@forEach

                // Fetching is intentionally outside the mutation gate. The URL and token exist
                // only inside DownloadArtworkTransport and never enter records or diagnostics.
                val bytes =
                    try {
                        transport.fetch(context, reference)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        downloadArtworkLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "download-artwork",
                                event = "fetch-failed",
                                throwable = failure,
                            )
                        }
                        null
                    } ?: run {
                        downloadArtworkLogger.i { "stage=download-artwork event=omitted reason=unavailable" }
                        return@forEach
                    }

                val publicationApplied =
                    withContext(NonCancellable) {
                        when (
                            val publication =
                                serverScopedStoreRegistry.withGuardedLease(lease) {
                                    publish(record, attempt, reference, bytes, persistedBytes)
                                }
                        ) {
                            null -> false
                            is PublicationResult.Published -> {
                                persistedBytes = publication.persistedBytes
                                true
                            }
                            is PublicationResult.Omitted -> {
                                persistedBytes = publication.persistedBytes
                                true
                            }
                        }
                    }
                currentCoroutineContext().ensureActive()
                if (!publicationApplied) return
            }
        } catch (cancellation: CancellationException) {
            // A cancelled file operation can leave only the fixed temporary member. Reclaim it
            // before propagating cancellation; a lost lease is left to the account cleanup or the
            // known-row startup reconciliation rather than mutating a newer account boundary.
            try {
                withContext(NonCancellable) {
                    serverScopedStoreRegistry.withGuardedLease(lease) {
                        reconcileAndSynchronize(record, attempt, persistedBytes)
                    }
                }
            } catch (cleanupFailure: Throwable) {
                downloadArtworkLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "download-artwork",
                        event = "cancel-cleanup-failed",
                        throwable = cleanupFailure,
                    )
                }
            }
            throw cancellation
        } catch (failure: Throwable) {
            downloadArtworkLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "download-artwork",
                    event = "capture-omitted",
                    throwable = failure,
                )
            }
        }
    }

    private suspend fun reconcileAndSynchronize(
        record: DownloadRecord,
        attempt: DownloadAttemptIdentity,
        persistedBytes: Long,
    ): ReconciledPresentation {
        val inspection = artifactStore.reconcilePresentation(record.request.artifactKey)
        val actualBytes = inspection.totalBytes
        if (actualBytes == persistedBytes) return ReconciledPresentation(persistedBytes, inspection)
        if (queueCoordinator.commitRegisteredAttemptPresentationBytes(attempt, persistedBytes, actualBytes)) {
            return ReconciledPresentation(actualBytes, inspection)
        }

        // An unaccounted positive crash remnant cannot bypass quota. Discard the bounded
        // presentation area, then make a final best-effort attempt to restore the durable count.
        artifactStore.deletePresentation(record.request.artifactKey)
        val discarded = artifactStore.reconcilePresentation(record.request.artifactKey)
        val reset = queueCoordinator.commitRegisteredAttemptPresentationBytes(attempt, persistedBytes, discarded.totalBytes)
        if (!reset) {
            downloadArtworkLogger.i { "stage=download-artwork event=reconcile-omitted reason=ownership-changed" }
        }
        return ReconciledPresentation(if (reset) discarded.totalBytes else persistedBytes, discarded)
    }

    private suspend fun publish(
        record: DownloadRecord,
        attempt: DownloadAttemptIdentity,
        reference: OfflineArtworkReference,
        bytes: ByteArray,
        persistedBytes: Long,
    ): PublicationResult {
        val synchronized = reconcileAndSynchronize(record, attempt, persistedBytes)
        if (reference.role in synchronized.inspection.roleBytes) {
            return PublicationResult.Published(synchronized.persistedBytes)
        }
        val safeAvailable =
            (artifactStore.capacity().availableBytes - DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES).coerceAtLeast(0L)
        if (bytes.size.toLong() > safeAvailable) {
            downloadArtworkLogger.i { "stage=download-artwork event=omitted reason=storage-reserve" }
            return PublicationResult.Omitted(synchronized.persistedBytes)
        }
        val nextBytes = synchronized.inspection.totalBytes + bytes.size.toLong()
        if (nextBytes > DOWNLOAD_PRESENTATION_TOTAL_MAX_BYTES) {
            downloadArtworkLogger.i { "stage=download-artwork event=omitted reason=total-cap" }
            return PublicationResult.Omitted(synchronized.persistedBytes)
        }

        try {
            artifactStore.stagePresentation(record.request.artifactKey, reference.role, bytes)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            downloadArtworkLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "download-artwork",
                    event = "stage-failed",
                    throwable = failure,
                )
            }
            return PublicationResult.Omitted(synchronized.persistedBytes)
        }

        if (
            !queueCoordinator.commitRegisteredAttemptPresentationBytes(
                attempt = attempt,
                expectedPresentationBytes = synchronized.persistedBytes,
                presentationBytes = nextBytes,
            )
        ) {
            val recoveredBytes = discardTemporaryPresentation(record, attempt, synchronized.persistedBytes)
            downloadArtworkLogger.i { "stage=download-artwork event=omitted reason=quota-or-owner" }
            return PublicationResult.Omitted(recoveredBytes)
        }
        return try {
            val published = artifactStore.publishPresentation(record.request.artifactKey, reference.role)
            if (published == null) {
                PublicationResult.Omitted(discardTemporaryPresentation(record, attempt, nextBytes))
            } else if (published.totalBytes != nextBytes) {
                val repaired =
                    queueCoordinator.commitRegisteredAttemptPresentationBytes(
                        attempt = attempt,
                        expectedPresentationBytes = nextBytes,
                        presentationBytes = published.totalBytes,
                    )
                if (repaired) {
                    PublicationResult.Published(published.totalBytes)
                } else {
                    PublicationResult.Omitted(nextBytes)
                }
            } else {
                PublicationResult.Published(nextBytes)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val recoveredBytes = discardTemporaryPresentation(record, attempt, nextBytes)
            downloadArtworkLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "download-artwork",
                    event = "publish-failed",
                    throwable = failure,
                )
            }
            PublicationResult.Omitted(recoveredBytes)
        }
    }

    private suspend fun discardTemporaryPresentation(
        record: DownloadRecord,
        attempt: DownloadAttemptIdentity,
        persistedBytes: Long,
    ): Long {
        val inspection = artifactStore.reconcilePresentation(record.request.artifactKey)
        if (inspection.totalBytes == persistedBytes) return persistedBytes
        return if (
            queueCoordinator.commitRegisteredAttemptPresentationBytes(
                attempt = attempt,
                expectedPresentationBytes = persistedBytes,
                presentationBytes = inspection.totalBytes,
            )
        ) {
            inspection.totalBytes
        } else {
            persistedBytes
        }
    }

    private data class ReconciledPresentation(
        val persistedBytes: Long,
        val inspection: DownloadPresentationInspection,
    )

    private sealed interface PublicationResult {
        data class Published(
            val persistedBytes: Long,
        ) : PublicationResult

        data class Omitted(
            val persistedBytes: Long,
        ) : PublicationResult
    }
}
