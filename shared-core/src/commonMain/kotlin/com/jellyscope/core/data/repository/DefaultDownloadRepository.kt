// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES
import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadAttemptInvalidationResult
import com.jellyscope.core.data.local.DownloadRecordStore
import com.jellyscope.core.data.local.DownloadRemovalStore
import com.jellyscope.core.data.local.DownloadSettingsStore
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.data.local.isCompleteOriginalArtifact
import com.jellyscope.core.data.repository.DownloadActiveAttemptRegistration
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.DownloadUsageEntry
import com.jellyscope.core.domain.model.OfflineArtworkRole
import com.jellyscope.core.domain.model.calculateDownloadUsage
import com.jellyscope.core.domain.model.saturatingAddNonNegative
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.download.DownloadAttemptIdentity
import com.jellyscope.core.download.isCanonicalLocalHlsArtifact
import com.jellyscope.core.playback.OfflineArtifactDeletionGuardResult
import com.jellyscope.core.playback.OfflineArtifactLeaseIdentity
import com.jellyscope.core.playback.OfflineArtifactLeaseRegistry
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

internal class DefaultDownloadRepository(
    private val settingsStore: DownloadSettingsStore,
    private val recordStore: DownloadRecordStore,
    private val removalStore: DownloadRemovalStore,
    private val artifactStore: DownloadArtifactStore,
    private val artifactLeaseRegistry: OfflineArtifactLeaseRegistry,
    private val removalMutex: DownloadRemovalMutex,
    private val nowEpochMilliseconds: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : DownloadRepository,
    DownloadQueueRepository {
    private val logger = diagnosticLogger(DiagnosticTag.DownloadExecution)

    override fun observeDownloads(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> =
        recordStore.observeAccount(accountIdentity)

    override suspend fun getDownload(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord? = recordStore.get(downloadId)?.takeIf { record -> record.businessKey.accountIdentity == accountIdentity }

    override suspend fun hasCompletedArtifact(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        attemptGeneration: Long,
    ): Boolean {
        val record = recordStore.get(downloadId, attemptGeneration) ?: return false
        if (
            record.businessKey.accountIdentity != accountIdentity ||
            record.state != DownloadState.Completed ||
            hasPendingRemoval(accountIdentity)
        ) {
            return false
        }
        val inspection =
            artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Completed)
                ?: return false
        return when (record.request.artifactKind) {
            DownloadArtifactKind.OriginalFile -> inspection.isCompleteOriginalArtifact(record)
            DownloadArtifactKind.LocalHlsPackage ->
                artifactStore.isCanonicalLocalHlsArtifact(
                    record = record,
                    inspection = inspection,
                    area = DownloadArtifactArea.Completed,
                )
        }
    }

    override suspend fun readPresentationArtwork(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        role: OfflineArtworkRole,
        maxBytes: Int,
    ): ByteArray? {
        if (maxBytes !in 1..DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES) return null
        val record = recordStore.get(downloadId) ?: return null
        if (
            record.businessKey.accountIdentity != accountIdentity ||
            !record.request.snapshot.presentationCaptureEligible ||
            hasPendingRemoval(accountIdentity)
        ) {
            return null
        }
        return artifactStore.readPresentation(
            artifactKey = record.request.artifactKey,
            role = role,
            maxBytes = maxBytes,
        )
    }

    override suspend fun isArtifactLeased(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): Boolean {
        val record = recordStore.get(downloadId) ?: return false
        if (record.businessKey.accountIdentity != accountIdentity || record.state != DownloadState.Completed) {
            return false
        }
        return artifactLeaseRegistry.isLeased(
            OfflineArtifactLeaseIdentity(record.downloadId, record.attemptGeneration),
        )
    }

    override suspend fun getDownloadSettings(): DownloadSettings = settingsStore.get()

    override suspend fun getDownloadUsage(accountIdentity: AccountIdentity): DownloadUsage = loadDownloadUsage(accountIdentity)

    private suspend fun loadDownloadUsage(accountIdentity: AccountIdentity?): DownloadUsage {
        val settings = settingsStore.get()
        val capacity = artifactStore.capacity()
        val records = recordStore.all()
        val currentAccountPhysicalBytes =
            records
                .asSequence()
                .filter { record -> record.businessKey.accountIdentity == accountIdentity }
                .fold(0L) { total, record ->
                    saturatingAddNonNegative(
                        total,
                        saturatingAddNonNegative(record.physicalBytes, record.presentationBytes),
                    )
                }
        return calculateDownloadUsage(
            entries = records.map(DownloadRecord::usageEntry),
            quotaBytes = settings.quotaBytes,
            deviceAvailableBytes = capacity.availableBytes,
            currentAccountPhysicalBytes = currentAccountPhysicalBytes,
        )
    }

    override suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings =
        removalMutex.withLock {
            // Reuse the model invariant without manufacturing a second validation policy.
            DownloadSettings(quotaBytes = quotaBytes, nextFifoSequence = 1L, membershipRevision = 0L)
            val current = settingsStore.get()
            if (quotaBytes != null && (current.quotaBytes == null || quotaBytes > current.quotaBytes)) {
                require(quotaBytes <= loadDownloadUsage(accountIdentity = null).maximumConfigurableQuotaBytes) {
                    "Download allocation exceeds currently safe device capacity."
                }
            }
            settingsStore.setQuotaBytes(quotaBytes)
        }

    override suspend fun enqueue(request: DownloadRequest): DownloadEnqueueResult =
        removalMutex.withLock {
            recordStore.enqueue(request, artifactStore.capacity().availableBytes)
        }

    override suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult =
        removalMutex.withLock {
            val record =
                recordStore.get(downloadId)
                    ?: return@withLock DownloadCommandResult.NotFound
            if (record.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadCommandResult.AccountNotOwned
            }
            if (hasPendingRemoval(accountIdentity)) {
                return@withLock DownloadCommandResult.RemovalInProgress
            }
            // A live writer must be closed and its generation revoked by the queue coordinator;
            // this repository-only surface deliberately cannot perform that quiescence.
            if (record.state == DownloadState.Downloading) {
                return@withLock DownloadCommandResult.ActiveAttemptUnavailable
            }
            DownloadCommandResult.InvalidState
        }

    override suspend fun resume(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult =
        transitionUserState(
            accountIdentity = accountIdentity,
            downloadId = downloadId,
            expectedStates = setOf(DownloadState.Paused, DownloadState.BlockedByQuota),
            nextState = DownloadState.Queued,
        )

    override suspend fun retry(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult =
        removalMutex.withLock {
            val record =
                recordStore.get(downloadId)
                    ?: return@withLock DownloadCommandResult.NotFound
            if (record.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadCommandResult.AccountNotOwned
            }
            if (hasPendingRemoval(accountIdentity)) {
                return@withLock DownloadCommandResult.RemovalInProgress
            }
            if (record.state != DownloadState.Failed) return@withLock DownloadCommandResult.InvalidState

            // A deterministic corrupt completed package must not strand the same directory on
            // every retry. Retry is the explicit user confirmation to remove only that unusable
            // completed area; all staging remains resumable except an unsupported HLS package.
            val completed =
                if (record.failure == DownloadFailure.MissingArtifact) {
                    artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Completed)
                } else {
                    null
                }
            if (record.failure == DownloadFailure.MissingArtifact) {
                return@withLock when (
                    val guarded =
                        artifactLeaseRegistry.withDeletionGuard(
                            OfflineArtifactLeaseIdentity(record.downloadId, record.attemptGeneration),
                        ) {
                            if (completed != null) {
                                artifactStore.delete(record.request.artifactKey, DownloadArtifactArea.Completed)
                            }
                            artifactStore.deletePresentation(record.request.artifactKey)
                            recordStore.retryAfterPresentationDelete(
                                downloadId = downloadId,
                                expectedAttemptGeneration = record.attemptGeneration,
                                updatedAtEpochMs = now(),
                            )
                        }
                ) {
                    OfflineArtifactDeletionGuardResult.ArtifactInUse -> DownloadCommandResult.InvalidState
                    is OfflineArtifactDeletionGuardResult.Granted ->
                        if (guarded.value) DownloadCommandResult.Applied else DownloadCommandResult.InvalidState
                }
            }
            if (
                record.request.artifactKind == DownloadArtifactKind.LocalHlsPackage &&
                record.failure == DownloadFailure.UnsupportedArtifact
            ) {
                artifactStore.delete(record.request.artifactKey, DownloadArtifactArea.Staging)
                artifactStore.deletePresentation(record.request.artifactKey)
                return@withLock if (
                    recordStore.retryAfterPresentationDelete(
                        downloadId = downloadId,
                        expectedAttemptGeneration = record.attemptGeneration,
                        updatedAtEpochMs = now(),
                    )
                ) {
                    DownloadCommandResult.Applied
                } else {
                    DownloadCommandResult.InvalidState
                }
            }
            if (
                recordStore.transition(
                    downloadId = downloadId,
                    expectedAttemptGeneration = record.attemptGeneration,
                    nextState = DownloadState.Queued,
                    updatedAtEpochMs = now(),
                )
            ) {
                DownloadCommandResult.Applied
            } else {
                DownloadCommandResult.InvalidState
            }
        }

    override suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult =
        deleteMatching(
            accountIdentity = accountIdentity,
            downloadId = downloadId,
            activeResult = DownloadDeletionResult.ActiveAttemptUnavailable,
        ) { state -> state != DownloadState.Completed }

    override suspend fun delete(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult =
        deleteMatching(
            accountIdentity = accountIdentity,
            downloadId = downloadId,
            activeResult = DownloadDeletionResult.InvalidState,
        ) { state -> state == DownloadState.Completed }

    override suspend fun updateLocalPlayback(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean?,
    ): Boolean =
        removalMutex.withLock {
            val record = recordStore.get(downloadId) ?: return@withLock false
            if (record.businessKey.accountIdentity != accountIdentity || hasPendingRemoval(accountIdentity)) {
                return@withLock false
            }
            recordStore.updateLocalPlayback(
                downloadId = downloadId,
                expectedAttemptGeneration = expectedAttemptGeneration,
                resumePositionMs = resumePositionMs,
                watched = watched,
                updatedAtEpochMs = now(),
            )
        }

    override suspend fun allDownloads(): List<DownloadRecord> = recordStore.all()

    override suspend fun claimOldest(
        activeAccount: AccountIdentity,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
    ): DownloadRecord? =
        removalMutex.withLock {
            recordStore
                .claimOldest(
                    accountIdentity = activeAccount,
                    platformWorkIdentity = platformWorkIdentity,
                    deviceAvailableBytes = artifactStore.capacity().availableBytes,
                    updatedAtEpochMs = now(),
                ).also { claimed ->
                    if (claimed != null) {
                        logger.i {
                            "stage=download-claim event=started generation=${claimed.attemptGeneration} state=${claimed.state.name}"
                        }
                    }
                }
        }

    override suspend fun updateAttemptProgress(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean =
        recordStore.updateProgress(
            downloadId = downloadId,
            expectedAttemptGeneration = expectedAttemptGeneration,
            physicalBytes = physicalBytes,
            checkpointBytes = checkpointBytes,
            updatedAtEpochMs = now(),
        )

    override suspend fun updateOriginalSourceFacts(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean =
        removalMutex.withLock {
            recordStore.updateOriginalSourceFacts(
                downloadId = downloadId,
                expectedAttemptGeneration = expectedAttemptGeneration,
                expectedSourceBytes = expectedSourceBytes,
                sourceValidator = sourceValidator,
            )
        }

    override suspend fun extendAttemptReservation(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        requiredReservationBytes: Long,
    ): DownloadReservationExtensionResult =
        removalMutex.withLock {
            recordStore.extendReservation(
                downloadId = downloadId,
                expectedAttemptGeneration = expectedAttemptGeneration,
                requiredReservationBytes = requiredReservationBytes,
                deviceAvailableBytes = artifactStore.capacity().availableBytes,
            )
        }

    override suspend fun transitionAttempt(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        nextState: DownloadState,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
        failure: DownloadFailure?,
    ): Boolean =
        try {
            recordStore
                .transition(
                    downloadId = downloadId,
                    expectedAttemptGeneration = expectedAttemptGeneration,
                    nextState = nextState,
                    platformWorkIdentity = platformWorkIdentity,
                    failure = failure,
                    updatedAtEpochMs = now(),
                ).also { applied ->
                    logger.i {
                        "stage=download-state event=transition generation=$expectedAttemptGeneration " +
                            "state=${nextState.name} failure=${failure?.name ?: "None"} result=$applied"
                    }
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.w {
                "stage=download-state event=failed generation=$expectedAttemptGeneration " +
                    "state=${nextState.name} failure=${failure?.name ?: "None"} exceptionType=${throwable.playbackExceptionType()}"
            }
            throw throwable
        }

    override suspend fun completeFinalizing(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
    ): Boolean =
        try {
            recordStore.completeFinalizing(downloadId, expectedAttemptGeneration, now()).also { applied ->
                logger.i {
                    "stage=download-finalize event=completed generation=$expectedAttemptGeneration result=$applied"
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.w {
                "stage=download-finalize event=failed generation=$expectedAttemptGeneration " +
                    "exceptionType=${throwable.playbackExceptionType()}"
            }
            throw throwable
        }

    override suspend fun checkpointAndRequeueForBoundary(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): DownloadAttemptInvalidationResult {
        require(registration.accountIdentity == accountIdentity) {
            "Boundary registration account does not match the requested account."
        }
        return removalMutex.withLock {
            val active =
                recordStore.get(registration.attempt.downloadId)
                    ?: return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            if (active.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            }
            if (hasPendingRemoval(accountIdentity)) {
                return@withLock DownloadAttemptInvalidationResult.RemovalInProgress
            }
            if (active.state == DownloadState.Finalizing) {
                // Finalization is a bounded local promotion, not a live writer. Finish it before
                // allowing the boundary to replace the account. No new attempt is invalidated.
                reconcileFinalizing(active)
                return@withLock DownloadAttemptInvalidationResult.Finalizing(active)
            }
            if (active.state != DownloadState.Downloading ||
                active.attemptGeneration != registration.attempt.attemptGeneration
            ) {
                return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            }
            checkpointAndInvalidateActiveAttemptLocked(
                accountIdentity = accountIdentity,
                registration = registration,
                nextState = DownloadState.Queued,
                gateHeldBoundaryCommit = gateHeldBoundaryCommit,
            )
        }
    }

    override suspend fun checkpointAndInvalidateActiveAttempt(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult {
        require(nextState == DownloadState.Queued || nextState == DownloadState.Paused) {
            "An active writer may only be invalidated to a resumable state."
        }
        require(registration.accountIdentity == accountIdentity) {
            "Active-attempt registration account does not match the requested account."
        }
        return removalMutex.withLock {
            val active =
                recordStore.get(registration.attempt.downloadId)
                    ?: return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            if (active.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            }
            if (hasPendingRemoval(accountIdentity)) {
                return@withLock DownloadAttemptInvalidationResult.RemovalInProgress
            }
            if (active.state != DownloadState.Downloading ||
                active.attemptGeneration != registration.attempt.attemptGeneration
            ) {
                return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            }
            checkpointAndInvalidateActiveAttemptLocked(
                accountIdentity = accountIdentity,
                registration = registration,
                nextState = nextState,
                gateHeldBoundaryCommit = null,
            )
        }
    }

    override suspend fun reconcileFinalizingForBoundary(accountIdentity: AccountIdentity): DownloadAttemptInvalidationResult =
        removalMutex.withLock {
            if (hasPendingRemoval(accountIdentity)) {
                return@withLock DownloadAttemptInvalidationResult.RemovalInProgress
            }
            val active =
                recordStore.all().singleOrNull { record ->
                    record.businessKey.accountIdentity == accountIdentity &&
                        record.state == DownloadState.Finalizing
                } ?: return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            reconcileFinalizing(active)
            DownloadAttemptInvalidationResult.Finalizing(active)
        }

    override suspend fun registerActiveAttempt(registration: DownloadActiveAttemptRegistration): Boolean =
        removalMutex.withLock {
            if (hasPendingRemoval(registration.accountIdentity)) return@withLock false
            val record =
                recordStore.get(registration.attempt.downloadId, registration.attempt.attemptGeneration)
                    ?: return@withLock false
            record.businessKey.accountIdentity == registration.accountIdentity &&
                record.state == DownloadState.Downloading &&
                record.physicalBytes == registration.currentFacts().physicalBytes &&
                record.checkpointBytes == registration.currentFacts().checkpointBytes
        }

    override suspend fun updateRegisteredAttemptFacts(
        attempt: DownloadAttemptIdentity,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean {
        require(physicalBytes >= 0L)
        require(checkpointBytes in 0L..physicalBytes)
        return removalMutex.withLock {
            recordStore.updateProgress(
                downloadId = attempt.downloadId,
                expectedAttemptGeneration = attempt.attemptGeneration,
                physicalBytes = physicalBytes,
                checkpointBytes = checkpointBytes,
                updatedAtEpochMs = now(),
            )
        }
    }

    override suspend fun commitRegisteredAttemptPresentationBytes(
        accountIdentity: AccountIdentity,
        attempt: DownloadAttemptIdentity,
        expectedPresentationBytes: Long,
        presentationBytes: Long,
    ): Boolean =
        removalMutex.withLock {
            if (hasPendingRemoval(accountIdentity)) return@withLock false
            recordStore.commitPresentationBytes(
                accountIdentity = accountIdentity,
                downloadId = attempt.downloadId,
                expectedAttemptGeneration = attempt.attemptGeneration,
                expectedPresentationBytes = expectedPresentationBytes,
                presentationBytes = presentationBytes,
                deviceAvailableBytes = artifactStore.capacity().availableBytes,
                updatedAtEpochMs = now(),
            )
        }

    override suspend fun reconcilePresentationBytes(
        record: DownloadRecord,
        presentationBytes: Long,
    ): Boolean =
        removalMutex.withLock {
            if (hasPendingRemoval(record.businessKey.accountIdentity)) return@withLock false
            recordStore.reconcilePresentationBytes(
                accountIdentity = record.businessKey.accountIdentity,
                downloadId = record.downloadId,
                expectedAttemptGeneration = record.attemptGeneration,
                presentationBytes = presentationBytes,
                updatedAtEpochMs = now(),
            )
        }

    override suspend fun clearRegisteredAttempt(attempt: DownloadAttemptIdentity): Boolean =
        recordStore.get(attempt.downloadId, attempt.attemptGeneration) != null

    override suspend fun checkpointAndInvalidateUnregisteredAttempt(
        accountIdentity: AccountIdentity,
        attempt: DownloadAttemptIdentity,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult {
        require(nextState == DownloadState.Queued || nextState == DownloadState.Paused) {
            "An unregistered claim may only be invalidated to a resumable state."
        }
        return removalMutex.withLock {
            val active =
                recordStore.get(attempt.downloadId, attempt.attemptGeneration)
                    ?: return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            if (active.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            }
            recordStore.checkpointAndInvalidateAttempt(
                accountIdentity = accountIdentity,
                downloadId = attempt.downloadId,
                expectedAttemptGeneration = attempt.attemptGeneration,
                physicalBytes = active.physicalBytes,
                checkpointBytes = active.checkpointBytes,
                nextState = nextState,
                updatedAtEpochMs = now(),
            )
        }
    }

    private suspend fun checkpointAndInvalidateActiveAttemptLocked(
        accountIdentity: AccountIdentity,
        registration: DownloadActiveAttemptRegistration,
        nextState: DownloadState,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit?,
    ): DownloadAttemptInvalidationResult {
        val facts = registration.checkpointAndClose()
        // The boundary path checks the actual lease immediately before the atomic store call.
        // Account identity and a still-held registration are deliberately insufficient proof.
        if (gateHeldBoundaryCommit != null && !gateHeldBoundaryCommit.isCurrentLease(registration.lease)) {
            return DownloadAttemptInvalidationResult.StaleAttempt
        }
        return recordStore.checkpointAndInvalidateAttempt(
            accountIdentity = accountIdentity,
            downloadId = registration.attempt.downloadId,
            expectedAttemptGeneration = registration.attempt.attemptGeneration,
            physicalBytes = facts.physicalBytes,
            checkpointBytes = facts.checkpointBytes,
            nextState = nextState,
            updatedAtEpochMs = now(),
        )
    }

    private suspend fun transitionUserState(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedStates: Set<DownloadState>,
        nextState: DownloadState,
    ): DownloadCommandResult =
        removalMutex.withLock {
            val record =
                recordStore.get(downloadId)
                    ?: return@withLock DownloadCommandResult.NotFound
            if (record.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadCommandResult.AccountNotOwned
            }
            if (hasPendingRemoval(accountIdentity)) {
                return@withLock DownloadCommandResult.RemovalInProgress
            }
            if (record.state !in expectedStates) return@withLock DownloadCommandResult.InvalidState
            if (recordStore.transition(
                    downloadId = downloadId,
                    expectedAttemptGeneration = record.attemptGeneration,
                    nextState = nextState,
                    updatedAtEpochMs = now(),
                )
            ) {
                DownloadCommandResult.Applied
            } else {
                DownloadCommandResult.InvalidState
            }
        }

    private suspend fun deleteMatching(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        activeResult: DownloadDeletionResult,
        acceptsState: (DownloadState) -> Boolean,
    ): DownloadDeletionResult =
        removalMutex.withLock {
            val record = recordStore.get(downloadId) ?: return@withLock DownloadDeletionResult.NotFound
            if (record.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadDeletionResult.AccountNotOwned
            }
            if (hasPendingRemoval(accountIdentity)) {
                return@withLock DownloadDeletionResult.RemovalInProgress
            }
            if (record.state == DownloadState.Downloading) {
                return@withLock activeResult
            }
            if (!acceptsState(record.state)) return@withLock DownloadDeletionResult.InvalidState
            val guarded =
                artifactLeaseRegistry.withDeletionGuard(
                    OfflineArtifactLeaseIdentity(record.downloadId, record.attemptGeneration),
                ) {
                    artifactStore.delete(record.request.artifactKey, DownloadArtifactArea.Staging)
                    artifactStore.delete(record.request.artifactKey, DownloadArtifactArea.Completed)
                    artifactStore.deletePresentation(record.request.artifactKey)
                    recordStore.delete(record.downloadId)
                }
            when (guarded) {
                OfflineArtifactDeletionGuardResult.ArtifactInUse -> DownloadDeletionResult.ArtifactInUse
                is OfflineArtifactDeletionGuardResult.Granted ->
                    if (guarded.value) DownloadDeletionResult.Deleted else DownloadDeletionResult.NotFound
            }
        }

    private suspend fun reconcileFinalizing(record: DownloadRecord): Boolean {
        val completed =
            try {
                artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Completed)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Visibility/inspection itself is uncertain; retain Finalizing for deterministic
                // recovery rather than manufacturing a terminal failure.
                return false
            }
        if (completed != null) {
            return when (validateFinalizingArtifact(record, completed, DownloadArtifactArea.Completed)) {
                true -> recordStore.completeFinalizing(record.downloadId, record.attemptGeneration, now())
                false -> failFinalizing(record)
                null -> false
            }
        }
        val staging =
            try {
                artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Staging)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return false
            }
        if (staging == null) return failFinalizing(record)
        when (validateFinalizingArtifact(record, staging, DownloadArtifactArea.Staging)) {
            null -> return false
            false -> return failFinalizing(record)
            true -> Unit
        }
        try {
            artifactStore.promote(record.request.artifactKey)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return false
        }
        val promoted =
            try {
                artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Completed)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return false
            }
        if (promoted == null) return false
        return when (validateFinalizingArtifact(record, promoted, DownloadArtifactArea.Completed)) {
            true -> recordStore.completeFinalizing(record.downloadId, record.attemptGeneration, now())
            false -> failFinalizing(record)
            null -> false
        }
    }

    private suspend fun validateFinalizingArtifact(
        record: DownloadRecord,
        inspection: com.jellyscope.core.data.local.DownloadArtifactInspection,
        area: DownloadArtifactArea,
    ): Boolean? =
        try {
            when (record.request.artifactKind) {
                DownloadArtifactKind.OriginalFile ->
                    inspection.isCompleteOriginalFinalizingArtifact(record, area)
                DownloadArtifactKind.LocalHlsPackage ->
                    artifactStore.isCanonicalLocalHlsArtifact(record, inspection, area)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A read/parse failure is not enough evidence to destroy a package while recovering a
            // durable Finalizing row. Keep the row Finalizing so the next recovery can retry.
            null
        }

    private suspend fun failFinalizing(record: DownloadRecord): Boolean =
        recordStore.transition(
            downloadId = record.downloadId,
            expectedAttemptGeneration = record.attemptGeneration,
            nextState = DownloadState.Failed,
            failure = DownloadFailure.MissingArtifact,
            updatedAtEpochMs = now(),
        )

    private fun com.jellyscope.core.data.local.DownloadArtifactInspection.isCompleteOriginalFinalizingArtifact(
        record: DownloadRecord,
        area: DownloadArtifactArea,
    ): Boolean {
        if (area == DownloadArtifactArea.Completed) return isCompleteOriginalArtifact(record)
        if (area != DownloadArtifactArea.Staging) return false
        if (artifactKey != record.request.artifactKey || this.area != area || totalBytes != record.physicalBytes) {
            return false
        }
        val expectedMain = record.request.expectedSourceBytes ?: return false
        val main =
            parts.singleOrNull { part ->
                part.partKey == com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_PART_KEY
            } ?: return false
        if (main.lengthBytes != expectedMain || main.lengthBytes <= 0L) return false
        val expectedSidecar =
            record.request.subtitleSelection is com.jellyscope.core.domain.model.DownloadSubtitleSelection.ExternalTextSidecar ||
                record.request.subtitleSelection is com.jellyscope.core.domain.model.DownloadSubtitleSelection.ExternalServerTextSidecar
        val sidecars =
            parts.filter { part ->
                part.partKey == com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY
            }
        return if (expectedSidecar) {
            sidecars.size == 1 && sidecars.single().lengthBytes > 0L && parts.size == 2
        } else {
            sidecars.isEmpty() && parts.size == 1
        }
    }

    private fun now(): Long = nowEpochMilliseconds().also { value -> require(value >= 0L) }

    private suspend fun hasPendingRemoval(accountIdentity: AccountIdentity): Boolean =
        removalStore.pending().any { operation ->
            operation.targets.any { target -> target.accountIdentity == accountIdentity }
        }
}

private fun DownloadRecord.usageEntry(): DownloadUsageEntry =
    DownloadUsageEntry(
        state = state,
        physicalBytes = physicalBytes,
        reservationBytes = reservationBytes,
        presentationBytes = presentationBytes,
    )
