// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES
import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_PART_KEY
import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY
import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactInspection
import com.jellyscope.core.data.local.DownloadArtifactPartCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactPartInspection
import com.jellyscope.core.data.local.DownloadArtifactPartKey
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadArtifactWriteMode
import com.jellyscope.core.data.local.DownloadArtifactWriter
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.LocalSubtitlePayloadTooLargeException
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.checkedArtifactLengthAfterWrite
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.OriginalDownloadFailure
import com.jellyscope.core.data.remote.OriginalDownloadPreflightResult
import com.jellyscope.core.data.remote.OriginalDownloadSource
import com.jellyscope.core.data.remote.OriginalDownloadStream
import com.jellyscope.core.data.remote.OriginalDownloadStreamResult
import com.jellyscope.core.data.repository.DownloadActiveAttemptRegistration
import com.jellyscope.core.data.repository.DownloadCheckpointFacts
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private val originalDownloadTransferLogger = diagnosticLogger(DiagnosticTag.OriginalDownload)

/**
 * Payload-free outcomes for one common transfer attempt.  Platform hosts may add their own
 * scheduling result around this value, but they never receive a URL, token, response, or path.
 */
internal sealed interface DownloadTransferResult {
    data object NoActiveAccount : DownloadTransferResult

    data object NoWork : DownloadTransferResult

    data object Completed : DownloadTransferResult

    /** Promotion outcome is unknown; durable Finalizing recovery must inspect both artifact areas. */
    data object FinalizingPending : DownloadTransferResult

    data object Paused : DownloadTransferResult

    data object BlockedByQuota : DownloadTransferResult

    data object BoundaryChanged : DownloadTransferResult

    data class Failed(
        val failure: DownloadFailure,
    ) : DownloadTransferResult
}

/**
 * The one common transfer owner. It captures a session boundary once, claims at most one row, and
 * keeps the live writer behind [DownloadActiveAttemptRegistration]. Original bytes stay in this
 * class; fixed-quality rows delegate to the bounded HLS package owner.
 */
internal class DownloadTransferCoordinator(
    private val sessionRepository: SessionRepository,
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    private val jellyfinApi: JellyfinApi,
    private val queueCoordinator: DownloadQueueCoordinator,
    private val artifactStore: DownloadArtifactStore,
    private val localSubtitleAssetStore: LocalSubtitleAssetStore,
    private val localSubtitleFileStore: LocalSubtitleFileStore,
    private val hlsTransferCoordinator: DownloadHlsTransferCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    suspend fun runOnce(platformWorkIdentity: DownloadPlatformWorkIdentity? = null): DownloadTransferResult =
        withContext(ioDispatcher) {
            runOnceOnIo(platformWorkIdentity)
        }

    private suspend fun runOnceOnIo(platformWorkIdentity: DownloadPlatformWorkIdentity?): DownloadTransferResult {
        val loggedIn =
            sessionRepository.sessionState.value as? SessionState.LoggedIn
                ?: return DownloadTransferResult.NoActiveAccount
        val session = loggedIn.session
        val accountIdentity = session.accountIdentity()
        val lease =
            serverScopedStoreRegistry.acquireWorkLease(
                accountIdentity = accountIdentity,
                boundaryEpoch = loggedIn.boundaryEpoch,
            ) ?: return DownloadTransferResult.BoundaryChanged
        val requestContext =
            AuthenticatedRequestContext(
                serverUrl = session.serverUrl,
                userId = session.userId,
                accessToken = session.accessToken,
            )

        val claim =
            serverScopedStoreRegistry.withGuardedLease(lease) {
                queueCoordinator.claimNext(accountIdentity, platformWorkIdentity)?.let(ClaimOutcome::Claimed)
                    ?: ClaimOutcome.None
            } ?: return DownloadTransferResult.BoundaryChanged
        val record =
            when (claim) {
                ClaimOutcome.None -> return DownloadTransferResult.NoWork
                is ClaimOutcome.Claimed -> claim.record
            }
        val attempt = DownloadAttemptIdentity(record.downloadId, record.attemptGeneration)

        if (
            record.request.quality is DownloadQuality.Fixed &&
            record.request.artifactKind == DownloadArtifactKind.LocalHlsPackage
        ) {
            val hls = hlsTransferCoordinator
            if (hls == null) {
                val settled =
                    serverScopedStoreRegistry.withGuardedLease(lease) {
                        failUnregisteredClaim(attempt, DownloadFailure.UnsupportedArtifact)
                    } ?: run {
                        invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                        return DownloadTransferResult.BoundaryChanged
                    }
                return if (settled) {
                    DownloadTransferResult.Failed(DownloadFailure.UnsupportedArtifact)
                } else {
                    DownloadTransferResult.BoundaryChanged
                }
            }
            val hlsResult =
                hls.runClaimed(
                    record = record,
                    attempt = attempt,
                    accountIdentity = accountIdentity,
                    lease = lease,
                    context = requestContext,
                )
            if (hlsResult == DownloadTransferResult.BoundaryChanged) {
                queueCoordinator.invalidateRegisteredAttemptAfterBoundary(attempt)
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    queueCoordinator.checkpointUnregisteredClaim(attempt, DownloadState.Queued)
                }
            }
            return hlsResult
        }

        // Original transfers keep selected text sidecars in the same private
        // artifact.
        if (!record.isSupportedOriginalRequest()) {
            val settled =
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    failUnregisteredClaim(attempt, DownloadFailure.UnsupportedArtifact)
                } ?: run {
                    invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                    return DownloadTransferResult.BoundaryChanged
                }
            return if (settled) {
                DownloadTransferResult.Failed(DownloadFailure.UnsupportedArtifact)
            } else {
                DownloadTransferResult.BoundaryChanged
            }
        }

        val preflight =
            try {
                jellyfinApi.preflightOriginalDownload(
                    context = requestContext,
                    itemId = record.businessKey.itemId,
                    mediaSourceId = record.businessKey.mediaSourceId,
                )
            } catch (cancellation: CancellationException) {
                invalidateUnregisteredClaim(attempt, cancellation.lifecycleNextState())
                throw cancellation
            } catch (failure: Throwable) {
                originalDownloadTransferLogger.w { formatSafeFailureDiagnostic("original-download", "preflight-failed", failure) }
                OriginalDownloadPreflightResult.Rejected(OriginalDownloadFailure.ServerUnavailable)
            }
        val source =
            when (preflight) {
                is OriginalDownloadPreflightResult.Ready -> preflight.source
                is OriginalDownloadPreflightResult.Rejected -> {
                    val failure = preflight.failure.toDownloadFailure()
                    val settled =
                        serverScopedStoreRegistry.withGuardedLease(lease) {
                            failUnregisteredClaim(attempt, failure)
                        } ?: run {
                            invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                            return DownloadTransferResult.BoundaryChanged
                        }
                    return if (settled) DownloadTransferResult.Failed(failure) else DownloadTransferResult.BoundaryChanged
                }
            }
        if (
            record.request.expectedSourceBytes?.let { expected -> expected != source.totalBytes } == true ||
            record.request.sourceValidator?.let { validator -> validator != source.lastModified } == true
        ) {
            val settled =
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    failUnregisteredClaim(attempt, DownloadFailure.SourceChanged)
                } ?: run {
                    invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                    return DownloadTransferResult.BoundaryChanged
                }
            return if (settled) {
                DownloadTransferResult.Failed(DownloadFailure.SourceChanged)
            } else {
                DownloadTransferResult.BoundaryChanged
            }
        }
        val selectedTrackFailure = source.selectedTrackFailure(record)
        if (selectedTrackFailure != null) {
            val settled =
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    failUnregisteredClaim(attempt, selectedTrackFailure)
                } ?: run {
                    invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                    return DownloadTransferResult.BoundaryChanged
                }
            return if (settled) {
                DownloadTransferResult.Failed(selectedTrackFailure)
            } else {
                DownloadTransferResult.BoundaryChanged
            }
        }

        val sidecarBytes =
            try {
                resolveSidecarBytes(record, requestContext)
            } catch (cancellation: CancellationException) {
                invalidateUnregisteredClaim(attempt, cancellation.lifecycleNextState())
                throw cancellation
            } catch (failure: SidecarResolutionFailure) {
                val settled =
                    serverScopedStoreRegistry.withGuardedLease(lease) {
                        failUnregisteredClaim(attempt, failure.failure)
                    } ?: run {
                        invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                        return DownloadTransferResult.BoundaryChanged
                    }
                return if (settled) {
                    DownloadTransferResult.Failed(failure.failure)
                } else {
                    DownloadTransferResult.BoundaryChanged
                }
            }

        val preparation =
            try {
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    prepareAttempt(record, attempt, accountIdentity, lease, source, sidecarBytes)
                } ?: run {
                    invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                    return DownloadTransferResult.BoundaryChanged
                }
            } catch (cancellation: CancellationException) {
                invalidateUnregisteredClaim(attempt, cancellation.lifecycleNextState())
                throw cancellation
            } catch (failure: Throwable) {
                originalDownloadTransferLogger.w { formatSafeFailureDiagnostic("original-download", "prepare-failed", failure) }
                val settled =
                    serverScopedStoreRegistry.withGuardedLease(lease) {
                        failUnregisteredClaim(attempt, DownloadFailure.ServerUnavailable)
                    } ?: run {
                        invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                        return DownloadTransferResult.BoundaryChanged
                    }
                if (settled) {
                    Preparation.Failed(DownloadFailure.ServerUnavailable)
                } else {
                    Preparation.BoundaryChanged
                }
            }
        val activeAttempt =
            when (preparation) {
                Preparation.BoundaryChanged -> {
                    invalidateUnregisteredClaim(attempt, DownloadState.Queued)
                    return DownloadTransferResult.BoundaryChanged
                }
                is Preparation.Failed -> return DownloadTransferResult.Failed(preparation.failure)
                is Preparation.Ready -> preparation.activeAttempt
            }

        val result =
            try {
                transferOne(
                    activeAttempt = activeAttempt,
                    requestContext = requestContext,
                    source = source,
                )
            } catch (cancellation: CancellationException) {
                checkpointForSuspension(activeAttempt, cancellation.lifecycleNextState())
                throw cancellation
            } catch (failure: Throwable) {
                originalDownloadTransferLogger.w { formatSafeFailureDiagnostic("original-download", "transfer-failed", failure) }
                settleFailure(activeAttempt, DownloadFailure.ServerUnavailable)
            }
        if (result == DownloadTransferResult.BoundaryChanged) {
            queueCoordinator.invalidateRegisteredAttemptAfterBoundary(activeAttempt.attempt)
        }
        return result
    }

    private suspend fun prepareAttempt(
        record: DownloadRecord,
        attempt: DownloadAttemptIdentity,
        accountIdentity: com.jellyscope.core.domain.model.AccountIdentity,
        lease: AccountWorkLease,
        source: OriginalDownloadSource,
        sidecarBytes: ByteArray?,
    ): Preparation {
        if (record.businessKey.accountIdentity != accountIdentity) {
            return Preparation.BoundaryChanged
        }
        if (
            record.request.quality != DownloadQuality.Original ||
            record.request.artifactKind != DownloadArtifactKind.OriginalFile
        ) {
            return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
        }
        if (record.physicalBytes != record.checkpointBytes) {
            // A writer can only resume from the exact durable checkpoint.  Do not guess whether
            // bytes beyond the checkpoint are complete or discard them silently.
            return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
        }
        val expectedTotalBytes =
            try {
                checkedArtifactLengthAfterWrite(source.totalBytes, sidecarBytes?.size?.toLong() ?: 0L)
            } catch (_: IllegalArgumentException) {
                return failClaimedAttempt(attempt, DownloadFailure.SizeUnavailable)
            } catch (_: IllegalStateException) {
                return failClaimedAttempt(attempt, DownloadFailure.SizeUnavailable)
            }
        if (record.checkpointBytes > expectedTotalBytes) {
            return failClaimedAttempt(attempt, DownloadFailure.SourceChanged)
        }
        if (
            record.request.expectedSourceBytes != source.totalBytes ||
            record.request.sourceValidator != source.lastModified
        ) {
            if (!queueCoordinator.updateOriginalSourceFacts(attempt, source.totalBytes, source.lastModified)) {
                return Preparation.BoundaryChanged
            }
        }

        val partKey = ORIGINAL_PART_KEY
        val expectedCheckpoint = checkpointForRecovery(record, sidecarBytes)
        if (!artifactStore.normalizeStagingCheckpoint(record.request.artifactKey, expectedCheckpoint)) {
            return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
        }
        val staging = artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Staging)
        val mainLength = staging?.partLength(partKey) ?: 0L
        val sidecarLength = staging?.partLength(SIDECAR_PART_KEY) ?: 0L
        val sidecarPartPresent = staging?.partLength(SIDECAR_PART_KEY) != null
        val expectedSidecarLength = sidecarBytes?.size?.toLong() ?: 0L
        if (staging != null && !staging.hasOnlyOriginalPartSubset(record.hasOriginalSidecar())) {
            return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
        }
        if (mainLength < 0L || sidecarLength < 0L || sidecarLength > expectedSidecarLength) {
            return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
        }
        val checkpointTotal =
            try {
                checkedArtifactLengthAfterWrite(mainLength, sidecarLength)
            } catch (_: IllegalArgumentException) {
                return failClaimedAttempt(attempt, DownloadFailure.SizeUnavailable)
            } catch (_: IllegalStateException) {
                return failClaimedAttempt(attempt, DownloadFailure.SizeUnavailable)
            }
        if (checkpointTotal != record.checkpointBytes) {
            return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
        }
        val mainPartPresent = staging?.partLength(partKey) != null
        val mode =
            when {
                !mainPartPresent && mainLength == 0L -> DownloadArtifactWriteMode.Create
                mainPartPresent -> DownloadArtifactWriteMode.Resume(expectedLengthBytes = mainLength)
                else -> return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
            }

        val writer =
            try {
                artifactStore.openStagingWriter(record.request.artifactKey, partKey, mode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return failClaimedAttempt(attempt, DownloadFailure.DeviceStorageLow)
            }
        if (writer.partKey != partKey || writer.lengthBytes != mainLength) {
            writer.close()
            return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
        }

        val sidecarWriter =
            if (sidecarBytes == null || sidecarLength == expectedSidecarLength) {
                null
            } else {
                try {
                    artifactStore.openStagingWriter(
                        record.request.artifactKey,
                        SIDECAR_PART_KEY,
                        if (sidecarPartPresent) {
                            DownloadArtifactWriteMode.Resume(sidecarLength)
                        } else {
                            DownloadArtifactWriteMode.Create
                        },
                    )
                } catch (cancellation: CancellationException) {
                    writer.close()
                    throw cancellation
                } catch (_: Throwable) {
                    writer.close()
                    return failClaimedAttempt(attempt, DownloadFailure.DeviceStorageLow)
                }
            }
        if (sidecarWriter != null && sidecarWriter.lengthBytes != sidecarLength) {
            sidecarWriter.close()
            writer.close()
            return failClaimedAttempt(attempt, DownloadFailure.UnsupportedArtifact)
        }

        val registration =
            DownloadActiveAttemptRegistration(
                accountIdentity = accountIdentity,
                lease = lease,
                attempt = attempt,
                initialFacts =
                    DownloadCheckpointFacts(
                        checkpointTotal,
                        checkpointTotal,
                    ),
                checkpointAndCloseWriter = { currentFacts ->
                    checkpointAndCloseOriginalWriters(
                        writer = writer,
                        sidecarWriter = sidecarWriter,
                        expectedSidecarLength = expectedSidecarLength,
                        currentFacts = currentFacts,
                    )
                },
            )
        if (!queueCoordinator.registerActiveAttempt(registration)) {
            sidecarWriter?.close()
            writer.close()
            return Preparation.BoundaryChanged
        }
        return Preparation.Ready(
            ActiveAttempt(
                record = record,
                attempt = attempt,
                lease = lease,
                writer = writer,
                sidecarWriter = sidecarWriter,
                sidecarBytes = sidecarBytes,
                registration = registration,
                reservationBytes = record.reservationBytes,
            ),
        )
    }

    private suspend fun failClaimedAttempt(
        attempt: DownloadAttemptIdentity,
        failure: DownloadFailure,
    ): Preparation {
        val failed = queueCoordinator.failClaimedAttempt(attempt, failure)
        return if (failed) Preparation.Failed(failure) else Preparation.BoundaryChanged
    }

    private suspend fun failUnregisteredClaim(
        attempt: DownloadAttemptIdentity,
        failure: DownloadFailure,
    ): Boolean = queueCoordinator.failClaimedAttempt(attempt, failure)

    private suspend fun transferOne(
        activeAttempt: ActiveAttempt,
        requestContext: AuthenticatedRequestContext,
        source: OriginalDownloadSource,
    ): DownloadTransferResult {
        when (copySidecarIntoWriter(activeAttempt)) {
            null,
            BodyOutcome.Complete,
            -> Unit
            BodyOutcome.BoundaryChanged -> return DownloadTransferResult.BoundaryChanged
            BodyOutcome.BlockedByQuota -> return settleBlocked(activeAttempt)
            BodyOutcome.SourceChanged -> return settleFailure(activeAttempt, DownloadFailure.SourceChanged)
            BodyOutcome.DeviceStorageLow -> return settleFailure(activeAttempt, DownloadFailure.DeviceStorageLow)
        }
        return transferMain(activeAttempt, requestContext, source)
    }

    private suspend fun transferMain(
        activeAttempt: ActiveAttempt,
        requestContext: AuthenticatedRequestContext,
        source: OriginalDownloadSource,
    ): DownloadTransferResult {
        if (activeAttempt.writer.lengthBytes > source.totalBytes) {
            return settleFailure(activeAttempt, DownloadFailure.SourceChanged)
        }
        if (activeAttempt.writer.lengthBytes == source.totalBytes) {
            return finalizeAndPromote(activeAttempt, source)
        }

        val streamResult =
            jellyfinApi.streamOriginalDownload(
                context = requestContext,
                source = source,
                startByte = activeAttempt.writer.lengthBytes,
            ) { stream ->
                val admitted =
                    serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) { true }
                        ?: false
                if (!admitted) {
                    BodyOutcome.BoundaryChanged
                } else {
                    streamIntoWriter(activeAttempt, source, stream)
                }
            }
        return when (streamResult) {
            is OriginalDownloadStreamResult.Rejected ->
                settleFailure(activeAttempt, streamResult.failure.toDownloadFailure())

            is OriginalDownloadStreamResult.Success ->
                when (streamResult.value) {
                    BodyOutcome.Complete -> finalizeAndPromote(activeAttempt, source)
                    BodyOutcome.BoundaryChanged -> DownloadTransferResult.BoundaryChanged
                    BodyOutcome.BlockedByQuota -> settleBlocked(activeAttempt)
                    BodyOutcome.SourceChanged -> settleFailure(activeAttempt, DownloadFailure.SourceChanged)
                    BodyOutcome.DeviceStorageLow -> settleFailure(activeAttempt, DownloadFailure.DeviceStorageLow)
                }
        }
    }

    /** Copies a selected text sidecar before the main media stream, using the same bounded writer. */
    private suspend fun copySidecarIntoWriter(activeAttempt: ActiveAttempt): BodyOutcome? {
        val writer = activeAttempt.sidecarWriter ?: return null
        val bytes = activeAttempt.sidecarBytes ?: return BodyOutcome.SourceChanged
        var offset = writer.lengthBytes.toInt()
        val bufferSize = DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES
        while (offset < bytes.size) {
            val length = minOf(bufferSize, bytes.size - offset)
            val currentTotal = activeAttempt.totalWrittenBytesOrNull() ?: return BodyOutcome.SourceChanged
            val requiredBytes =
                try {
                    checkedArtifactLengthAfterWrite(currentTotal, length.toLong())
                } catch (_: IllegalArgumentException) {
                    return BodyOutcome.SourceChanged
                } catch (_: IllegalStateException) {
                    return BodyOutcome.SourceChanged
                }
            val admission = ensureReservation(activeAttempt, requiredBytes)
            if (admission != null) return admission
            try {
                val capacity =
                    try {
                        artifactStore.capacity()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return BodyOutcome.DeviceStorageLow
                    }
                val safeAvailable =
                    (capacity.availableBytes - DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES).coerceAtLeast(0L)
                if (length.toLong() > safeAvailable) return BodyOutcome.BlockedByQuota
                writer.write(bytes, offset, length)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return BodyOutcome.DeviceStorageLow
            }
            offset += length
        }
        if (writer.lengthBytes != bytes.size.toLong()) return BodyOutcome.SourceChanged
        return checkpointProgress(activeAttempt) ?: BodyOutcome.Complete
    }

    private suspend fun streamIntoWriter(
        activeAttempt: ActiveAttempt,
        source: OriginalDownloadSource,
        stream: OriginalDownloadStream,
    ): BodyOutcome {
        val buffer = ByteArray(DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES)
        var bytesSinceCheckpoint = 0L
        while (activeAttempt.writer.lengthBytes < source.totalBytes) {
            val remaining = source.totalBytes - activeAttempt.writer.lengthBytes
            val readLength = minOf(buffer.size.toLong(), remaining).toInt()
            val read = stream.body.readAvailable(buffer, 0, readLength)
            if (read < 0) break
            if (read == 0) continue

            val requiredMainBytes =
                try {
                    checkedArtifactLengthAfterWrite(activeAttempt.writer.lengthBytes, read.toLong())
                } catch (_: IllegalArgumentException) {
                    return BodyOutcome.SourceChanged
                } catch (_: IllegalStateException) {
                    return BodyOutcome.SourceChanged
                }
            if (requiredMainBytes > source.totalBytes) return BodyOutcome.SourceChanged
            val currentTotal = activeAttempt.totalWrittenBytesOrNull() ?: return BodyOutcome.SourceChanged
            val requiredBytes =
                try {
                    checkedArtifactLengthAfterWrite(currentTotal, read.toLong())
                } catch (_: IllegalArgumentException) {
                    return BodyOutcome.SourceChanged
                } catch (_: IllegalStateException) {
                    return BodyOutcome.SourceChanged
                }
            ensureReservation(activeAttempt, requiredBytes)?.let { return it }

            val capacity =
                try {
                    artifactStore.capacity()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return BodyOutcome.DeviceStorageLow
                }
            val safeAvailable =
                (capacity.availableBytes - DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES).coerceAtLeast(0L)
            if (read.toLong() > safeAvailable) return BodyOutcome.BlockedByQuota

            try {
                activeAttempt.writer.write(buffer, 0, read)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return BodyOutcome.DeviceStorageLow
            }
            bytesSinceCheckpoint += read.toLong()
            activeAttempt.bytesSinceCheckpoint += read.toLong()
            if (bytesSinceCheckpoint >= PROGRESS_CHECKPOINT_BYTES) {
                checkpointProgress(activeAttempt)?.let { return it }
                bytesSinceCheckpoint = 0L
                activeAttempt.bytesSinceCheckpoint = 0L
            }
        }
        return if (activeAttempt.writer.lengthBytes == source.totalBytes) {
            BodyOutcome.Complete
        } else {
            BodyOutcome.SourceChanged
        }
    }

    private suspend fun checkpointProgress(activeAttempt: ActiveAttempt): BodyOutcome? {
        revalidateReservation(activeAttempt)?.let { return it }
        val checkpoint =
            try {
                activeAttempt.writer.checkpoint()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return BodyOutcome.DeviceStorageLow
            }
        if (checkpoint.partKey != ORIGINAL_PART_KEY) return BodyOutcome.SourceChanged
        val sidecarCheckpoint =
            try {
                activeAttempt.sidecarWriter?.checkpoint()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return BodyOutcome.DeviceStorageLow
            }
        if (
            activeAttempt.sidecarBytesLength > 0L &&
            (sidecarCheckpoint?.lengthBytes ?: activeAttempt.sidecarBytesLength) < activeAttempt.sidecarBytesLength
        ) {
            // A sidecar is an all-before-main part. Never publish a durable main checkpoint while
            // its bytes are still incomplete; crash recovery will truncate the sidecar to zero.
            return BodyOutcome.BoundaryChanged
        }
        val total =
            try {
                checkedArtifactLengthAfterWrite(
                    checkpoint.lengthBytes,
                    sidecarCheckpoint?.lengthBytes ?: activeAttempt.sidecarBytesLength,
                )
            } catch (_: IllegalArgumentException) {
                return BodyOutcome.SourceChanged
            } catch (_: IllegalStateException) {
                return BodyOutcome.SourceChanged
            }
        val durable =
            serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                queueCoordinator.updateRegisteredAttemptFacts(
                    attempt = activeAttempt.attempt,
                    physicalBytes = total,
                    checkpointBytes = total,
                )
            } ?: return BodyOutcome.BoundaryChanged
        return if (durable) null else BodyOutcome.BoundaryChanged
    }

    /** Rechecks the live quota/device admission at each durable progress boundary. */
    private suspend fun revalidateReservation(activeAttempt: ActiveAttempt): BodyOutcome? {
        val extension =
            serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                queueCoordinator.extendReservation(
                    attempt = activeAttempt.attempt,
                    requiredReservationBytes = activeAttempt.reservationBytes,
                )
            } ?: return BodyOutcome.BoundaryChanged
        activeAttempt.reservationBytes =
            when (extension) {
                is DownloadReservationExtensionResult.Extended -> extension.reservationBytes
                is DownloadReservationExtensionResult.Unchanged -> extension.reservationBytes
                is DownloadReservationExtensionResult.Rejected -> return BodyOutcome.BlockedByQuota
                DownloadReservationExtensionResult.StaleAttempt,
                DownloadReservationExtensionResult.RemovalInProgress,
                -> return BodyOutcome.BoundaryChanged
            }
        return null
    }

    private suspend fun checkpointFactsForWriters(
        writer: DownloadArtifactWriter,
        sidecarWriter: DownloadArtifactWriter?,
        expectedSidecarLength: Long,
    ): DownloadCheckpointFacts {
        val mainCheckpoint = writer.checkpoint()
        val sidecarCheckpoint = sidecarWriter?.checkpoint()
        val actualSidecarLength = sidecarCheckpoint?.lengthBytes ?: expectedSidecarLength
        check(expectedSidecarLength == 0L || actualSidecarLength >= expectedSidecarLength) {
            "Original sidecar checkpoint is incomplete."
        }
        val total = checkedArtifactLengthAfterWrite(mainCheckpoint.lengthBytes, actualSidecarLength)
        return DownloadCheckpointFacts(physicalBytes = total, checkpointBytes = total)
    }

    private suspend fun checkpointAndCloseOriginalWriters(
        writer: DownloadArtifactWriter,
        sidecarWriter: DownloadArtifactWriter?,
        expectedSidecarLength: Long,
        currentFacts: DownloadCheckpointFacts,
    ): DownloadCheckpointFacts =
        withContext(NonCancellable) {
            val durableFacts =
                try {
                    checkpointFactsForWriters(
                        writer = writer,
                        sidecarWriter = sidecarWriter,
                        expectedSidecarLength = expectedSidecarLength,
                    )
                } catch (failure: Throwable) {
                    originalDownloadTransferLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "original-download",
                            event = "checkpoint-failed",
                            throwable = failure,
                        )
                    }
                    currentFacts
                }
            closeOriginalWriter(sidecarWriter, "sidecar-close-failed")
            closeOriginalWriter(writer, "main-close-failed")
            durableFacts
        }

    private suspend fun closeOriginalWriter(
        writer: DownloadArtifactWriter?,
        event: String,
    ) {
        if (writer == null) return
        try {
            writer.close()
        } catch (failure: Throwable) {
            originalDownloadTransferLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "original-download",
                    event = event,
                    throwable = failure,
                )
            }
        }
    }

    private suspend fun ensureReservation(
        activeAttempt: ActiveAttempt,
        requiredBytes: Long,
    ): BodyOutcome? {
        if (requiredBytes <= activeAttempt.reservationBytes) return null
        val extension =
            serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                queueCoordinator.extendReservation(activeAttempt.attempt, requiredBytes)
            } ?: return BodyOutcome.BoundaryChanged
        activeAttempt.reservationBytes =
            when (extension) {
                is DownloadReservationExtensionResult.Extended -> extension.reservationBytes
                is DownloadReservationExtensionResult.Unchanged -> extension.reservationBytes
                is DownloadReservationExtensionResult.Rejected -> return BodyOutcome.BlockedByQuota
                DownloadReservationExtensionResult.StaleAttempt,
                DownloadReservationExtensionResult.RemovalInProgress,
                -> return BodyOutcome.BoundaryChanged
            }
        return if (requiredBytes > activeAttempt.reservationBytes) BodyOutcome.BlockedByQuota else null
    }

    private suspend fun finalizeAndPromote(
        activeAttempt: ActiveAttempt,
        source: OriginalDownloadSource,
    ): DownloadTransferResult {
        when (revalidateReservation(activeAttempt)) {
            null -> Unit
            BodyOutcome.BlockedByQuota -> return settleBlocked(activeAttempt)
            BodyOutcome.BoundaryChanged -> return DownloadTransferResult.BoundaryChanged
            BodyOutcome.DeviceStorageLow -> return settleFailure(activeAttempt, DownloadFailure.DeviceStorageLow)
            BodyOutcome.SourceChanged -> return settleFailure(activeAttempt, DownloadFailure.SourceChanged)
            BodyOutcome.Complete -> Unit
        }
        val finalization =
            serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                queueCoordinator.finalizeRegisteredAttempt(
                    attempt = activeAttempt.attempt,
                    validationFailure = DownloadFailure.UnsupportedArtifact,
                ) {
                    val inspection =
                        artifactStore.inspect(activeAttempt.record.request.artifactKey, DownloadArtifactArea.Staging)
                    isExactOriginalPackage(
                        inspection = inspection,
                        artifactKey = activeAttempt.record.request.artifactKey,
                        mainLengthBytes = source.totalBytes,
                        sidecarLengthBytes = activeAttempt.sidecarBytesLength,
                        area = DownloadArtifactArea.Staging,
                    ) &&
                        artifactStore.validateStagingCheckpoint(
                            artifactKey = activeAttempt.record.request.artifactKey,
                            checkpoint = activeAttempt.expectedPackageCheckpoint(source.totalBytes),
                        )
                }
            } ?: return DownloadTransferResult.BoundaryChanged
        when (finalization) {
            RegisteredAttemptFinalizationResult.InvalidArtifact ->
                return DownloadTransferResult.Failed(DownloadFailure.UnsupportedArtifact)
            RegisteredAttemptFinalizationResult.StaleAttempt ->
                return DownloadTransferResult.BoundaryChanged
            RegisteredAttemptFinalizationResult.Started -> Unit
        }

        val promoted =
            try {
                serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                    queueCoordinator.withRunnerLock {
                        artifactStore.promote(activeAttempt.record.request.artifactKey)
                    }
                } ?: return DownloadTransferResult.BoundaryChanged
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DownloadTransferResult.FinalizingPending
            }
        if (!isExactOriginalPackage(
                inspection = promoted,
                artifactKey = activeAttempt.record.request.artifactKey,
                mainLengthBytes = source.totalBytes,
                sidecarLengthBytes = activeAttempt.sidecarBytesLength,
                area = DownloadArtifactArea.Completed,
            )
        ) {
            return DownloadTransferResult.FinalizingPending
        }

        val completed =
            serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                queueCoordinator.completeFinalizing(activeAttempt.attempt)
            } ?: return DownloadTransferResult.BoundaryChanged
        return if (completed) DownloadTransferResult.Completed else DownloadTransferResult.BoundaryChanged
    }

    private suspend fun settleFailure(
        activeAttempt: ActiveAttempt,
        failure: DownloadFailure,
    ): DownloadTransferResult {
        val failed =
            serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                queueCoordinator.finishRegisteredAttempt(
                    attempt = activeAttempt.attempt,
                    nextState = DownloadState.Failed,
                    failure = failure,
                )
            } ?: return DownloadTransferResult.BoundaryChanged
        return if (failed) DownloadTransferResult.Failed(failure) else DownloadTransferResult.BoundaryChanged
    }

    private suspend fun settleBlocked(activeAttempt: ActiveAttempt): DownloadTransferResult {
        val blocked =
            serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                queueCoordinator.finishRegisteredAttempt(
                    attempt = activeAttempt.attempt,
                    nextState = DownloadState.BlockedByQuota,
                )
            } ?: return DownloadTransferResult.BoundaryChanged
        return if (blocked) DownloadTransferResult.BlockedByQuota else DownloadTransferResult.BoundaryChanged
    }

    private suspend fun checkpointForSuspension(
        activeAttempt: ActiveAttempt,
        nextState: DownloadState,
    ) {
        withContext(NonCancellable) {
            try {
                serverScopedStoreRegistry.withGuardedLease(activeAttempt.lease) {
                    if (nextState == DownloadState.Queued) {
                        queueCoordinator.checkpointAndRequeueForLifecycle(
                            accountIdentity = activeAttempt.record.businessKey.accountIdentity,
                            attempt = activeAttempt.attempt,
                        )
                    } else {
                        queueCoordinator.finishRegisteredAttempt(
                            attempt = activeAttempt.attempt,
                            nextState = DownloadState.Paused,
                        )
                    }
                }
            } catch (_: Throwable) {
                // Cancellation remains the caller's result even if a closing/checkpoint failure
                // prevents a durable pause.  The next recovery pass will inspect the staging file.
            }
        }
    }

    private sealed interface ClaimOutcome {
        data object None : ClaimOutcome

        data class Claimed(
            val record: DownloadRecord,
        ) : ClaimOutcome
    }

    private sealed interface Preparation {
        data class Ready(
            val activeAttempt: ActiveAttempt,
        ) : Preparation

        data class Failed(
            val failure: DownloadFailure,
        ) : Preparation

        data object BoundaryChanged : Preparation
    }

    private data class ActiveAttempt(
        val record: DownloadRecord,
        val attempt: DownloadAttemptIdentity,
        val lease: AccountWorkLease,
        val writer: DownloadArtifactWriter,
        val sidecarWriter: DownloadArtifactWriter?,
        val sidecarBytes: ByteArray?,
        val registration: DownloadActiveAttemptRegistration,
        var reservationBytes: Long,
        var bytesSinceCheckpoint: Long = 0L,
    ) {
        val sidecarBytesLength: Long
            get() = sidecarBytes?.size?.toLong() ?: 0L

        fun totalWrittenBytesOrNull(): Long? =
            try {
                checkedArtifactLengthAfterWrite(
                    writer.lengthBytes,
                    sidecarWriter?.lengthBytes ?: sidecarBytesLength,
                )
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }

        fun expectedPackageCheckpoint(mainLengthBytes: Long): DownloadArtifactCheckpoint =
            DownloadArtifactCheckpoint(
                parts =
                    buildList {
                        add(DownloadArtifactPartCheckpoint(ORIGINAL_PART_KEY, mainLengthBytes))
                        if (sidecarBytesLength > 0L) {
                            add(DownloadArtifactPartCheckpoint(SIDECAR_PART_KEY, sidecarBytesLength))
                        }
                    },
            )
    }

    private enum class BodyOutcome {
        Complete,
        BoundaryChanged,
        BlockedByQuota,
        SourceChanged,
        DeviceStorageLow,
    }

    private fun OriginalDownloadFailure.toDownloadFailure(): DownloadFailure =
        when (this) {
            OriginalDownloadFailure.AccountUnauthorized,
            OriginalDownloadFailure.PermissionDenied,
            -> DownloadFailure.PermissionDenied
            OriginalDownloadFailure.SourceUnavailable -> DownloadFailure.SourceChanged
            OriginalDownloadFailure.SizeUnavailable -> DownloadFailure.SizeUnavailable
            OriginalDownloadFailure.SourceChanged -> DownloadFailure.SourceChanged
            OriginalDownloadFailure.Network -> DownloadFailure.Network
            OriginalDownloadFailure.ServerUnavailable -> DownloadFailure.ServerUnavailable
        }

    private fun DownloadRecord.isSupportedOriginalRequest(): Boolean =
        request.quality == DownloadQuality.Original &&
            request.artifactKind == DownloadArtifactKind.OriginalFile

    private fun OriginalDownloadSource.selectedTrackFailure(record: DownloadRecord): DownloadFailure? {
        if (record.request.selectedAudioStreamIndex != null &&
            record.request.selectedAudioStreamIndex !in audioStreamIndices
        ) {
            return DownloadFailure.SourceChanged
        }
        return when (val selection = record.request.subtitleSelection) {
            DownloadSubtitleSelection.Off,
            is DownloadSubtitleSelection.ExternalTextSidecar,
            -> null
            is DownloadSubtitleSelection.Embedded ->
                if (selection.streamIndex in embeddedSubtitleStreamIndices) null else DownloadFailure.SourceChanged
            is DownloadSubtitleSelection.ExternalServerTextSidecar ->
                when {
                    selection.streamIndex in externalSubtitleStreamIndices -> null
                    selection.streamIndex in unsupportedExternalSubtitleStreamIndices -> DownloadFailure.UnsupportedArtifact
                    else -> DownloadFailure.SourceChanged
                }
        }
    }

    private fun isExactStaging(
        inspection: DownloadArtifactInspection?,
        artifactKey: com.jellyscope.core.domain.model.DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        expectedLengthBytes: Long,
        area: DownloadArtifactArea,
    ): Boolean =
        inspection != null &&
            inspection.artifactKey == artifactKey &&
            inspection.area == area &&
            inspection.parts.size == 1 &&
            inspection.parts.singleOrNull() == DownloadArtifactPartInspection(partKey, expectedLengthBytes)

    private fun isExactOriginalPackage(
        inspection: DownloadArtifactInspection?,
        artifactKey: com.jellyscope.core.domain.model.DownloadArtifactKey,
        mainLengthBytes: Long,
        sidecarLengthBytes: Long,
        area: DownloadArtifactArea,
    ): Boolean {
        if (inspection == null || inspection.artifactKey != artifactKey || inspection.area != area) return false
        val expected =
            buildList {
                add(DownloadArtifactPartInspection(ORIGINAL_PART_KEY, mainLengthBytes))
                if (sidecarLengthBytes > 0L) add(DownloadArtifactPartInspection(SIDECAR_PART_KEY, sidecarLengthBytes))
            }
        return inspection.parts == expected.sortedBy { part -> part.partKey.value }
    }

    private fun DownloadArtifactInspection.partLength(partKey: DownloadArtifactPartKey): Long? =
        parts.firstOrNull { part -> part.partKey == partKey }?.lengthBytes

    private fun DownloadArtifactInspection.hasOnlyOriginalPartSubset(hasSidecar: Boolean): Boolean {
        val keys = parts.map { part -> part.partKey }.toSet()
        val expected = setOf(ORIGINAL_PART_KEY) + if (hasSidecar) setOf(SIDECAR_PART_KEY) else emptySet()
        return keys.all { key -> key in expected }
    }

    private fun DownloadRecord.hasOriginalSidecar(): Boolean =
        request.subtitleSelection is DownloadSubtitleSelection.ExternalTextSidecar ||
            request.subtitleSelection is DownloadSubtitleSelection.ExternalServerTextSidecar

    private fun checkpointForRecovery(
        record: DownloadRecord,
        sidecarBytes: ByteArray?,
    ): DownloadArtifactCheckpoint {
        val sidecarLength = sidecarBytes?.size?.toLong() ?: 0L
        val mainLength =
            if (sidecarLength > 0L && record.checkpointBytes >= sidecarLength) {
                record.checkpointBytes - sidecarLength
            } else {
                0L
            }
        return DownloadArtifactCheckpoint(
            parts =
                buildList {
                    add(DownloadArtifactPartCheckpoint(ORIGINAL_PART_KEY, mainLength))
                    if (sidecarLength > 0L) {
                        add(
                            DownloadArtifactPartCheckpoint(
                                SIDECAR_PART_KEY,
                                if (record.checkpointBytes >= sidecarLength) sidecarLength else 0L,
                            ),
                        )
                    }
                },
        )
    }

    private suspend fun resolveSidecarBytes(
        record: DownloadRecord,
        context: AuthenticatedRequestContext,
    ): ByteArray? {
        val selection = record.request.subtitleSelection
        val raw =
            when (selection) {
                DownloadSubtitleSelection.Off,
                is DownloadSubtitleSelection.Embedded,
                -> return null
                is DownloadSubtitleSelection.ExternalTextSidecar -> {
                    val asset =
                        localSubtitleAssetStore
                            .get(selection.localAssetId)
                            ?.takeIf { item ->
                                item.serverId == record.businessKey.accountIdentity.serverId &&
                                    item.userId == record.businessKey.accountIdentity.userId &&
                                    item.itemId == record.businessKey.itemId &&
                                    item.mediaSourceId == record.businessKey.mediaSourceId
                            } ?: throw SidecarResolutionFailure(DownloadFailure.SourceChanged)
                    try {
                        localSubtitleFileStore.readBounded(asset.fileId, MAX_SIDECAR_BYTES)
                            ?: throw SidecarResolutionFailure(DownloadFailure.SourceChanged)
                    } catch (tooLarge: LocalSubtitlePayloadTooLargeException) {
                        throw SidecarResolutionFailure(DownloadFailure.UnsupportedArtifact, tooLarge)
                    }
                }
                is DownloadSubtitleSelection.ExternalServerTextSidecar -> {
                    try {
                        jellyfinApi.getSubtitleTextBounded(
                            context = context,
                            itemId = record.businessKey.itemId,
                            mediaSourceId = record.businessKey.mediaSourceId,
                            streamIndex = selection.streamIndex,
                            maxBytes = MAX_SIDECAR_BYTES,
                        )
                    } catch (tooLarge: com.jellyscope.core.data.remote.JellyfinApiException.PayloadTooLarge) {
                        throw SidecarResolutionFailure(DownloadFailure.UnsupportedArtifact, tooLarge)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        throw SidecarResolutionFailure(DownloadFailure.SourceChanged)
                    }
                }
            }
        if (raw.isEmpty() || raw.size > MAX_SIDECAR_BYTES) {
            throw SidecarResolutionFailure(DownloadFailure.UnsupportedArtifact)
        }
        return raw
    }

    private class SidecarResolutionFailure(
        val failure: DownloadFailure,
        cause: Throwable? = null,
    ) : RuntimeException(cause)

    private suspend fun invalidateUnregisteredClaim(
        attempt: DownloadAttemptIdentity,
        nextState: DownloadState,
    ) {
        withContext(NonCancellable) {
            runCatching { queueCoordinator.checkpointUnregisteredClaim(attempt, nextState) }
        }
    }

    private fun CancellationException.lifecycleNextState(): DownloadState =
        if (isLifecycleRequeueCancellation()) DownloadState.Queued else DownloadState.Paused

    private fun CancellationException.isLifecycleRequeueCancellation(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is DownloadLifecycleRequeueCancellation) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        val ORIGINAL_PART_KEY: DownloadArtifactPartKey = DOWNLOAD_ORIGINAL_PART_KEY
        val SIDECAR_PART_KEY: DownloadArtifactPartKey = DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY
        const val PROGRESS_CHECKPOINT_BYTES: Long = 1L * 1024L * 1024L
        const val MAX_SIDECAR_BYTES: Int = 8 * 1024 * 1024
    }
}
