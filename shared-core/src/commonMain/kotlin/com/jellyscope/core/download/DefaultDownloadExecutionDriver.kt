// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import kotlinx.coroutines.CancellationException

/**
 * Application-scoped adapter between native lifecycle hosts and the common queue/transfer owner.
 * It deliberately exposes only payload-free scheduling results; account credentials, response
 * bodies, and artifact paths remain inside the common session/transport/store boundaries.
 */
internal class DefaultDownloadExecutionDriver(
    private val sessionRepository: SessionRepository,
    private val queueCoordinator: DownloadQueueCoordinator,
    private val transferCoordinator: DownloadTransferCoordinator,
    private val artifactStore: DownloadArtifactStore? = null,
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry? = null,
) : DownloadExecutionDriver {
    override suspend fun wake(): Result<Unit> {
        return try {
            var boundaryRetries = 0
            while (hasRunnableWork()) {
                val outcome = transferCoordinator.runOnce()
                if (outcome == DownloadTransferResult.BoundaryChanged) {
                    // A pause/cancel or account-boundary callback may have released the active
                    // slot while this run was unwinding. Re-probe the current account once so a
                    // following FIFO row is not stranded, but avoid spinning on a permanently
                    // stale boundary.
                    boundaryRetries += 1
                    if (boundaryRetries > 1 || !hasRunnableWork()) return Result.success(Unit)
                    continue
                }
                boundaryRetries = 0
                when (outcome) {
                    DownloadTransferResult.Completed,
                    is DownloadTransferResult.Failed,
                    DownloadTransferResult.BlockedByQuota,
                    DownloadTransferResult.Paused,
                    DownloadTransferResult.NoWork,
                    DownloadTransferResult.NoActiveAccount,
                    -> Unit
                    DownloadTransferResult.FinalizingPending -> {
                        return Result.failure(IllegalStateException("Download finalization requires recovery."))
                    }
                }
                if (outcomeStopsWake(outcome)) return Result.success(Unit)
            }
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    override suspend fun prepareContinuedWorkEnrollment(enrollment: DownloadExplicitWorkEnrollment): DownloadContinuedWorkEnrollment? {
        val boundary = currentBoundary() ?: return null
        if (boundary.accountIdentity != enrollment.accountIdentity) return null
        return DownloadContinuedWorkEnrollment(
            boundary = boundary,
            initialEnrollment = enrollment,
        )
    }

    override suspend fun drainContinuedWork(enrollment: DownloadContinuedWorkEnrollment?): DownloadContinuedDrainOutcome {
        val boundary =
            currentBoundary() ?: return recordedContinuedOutcome(
                enrollment = enrollment,
                outcome = DownloadContinuedDrainOutcome.NoWork,
            )
        if (enrollment?.bindBoundary(boundary) == false) {
            return recordedContinuedOutcome(
                enrollment = enrollment,
                outcome = DownloadContinuedDrainOutcome.BoundaryInvalidated,
            )
        }
        var completedWork = false
        try {
            while (true) {
                if (currentBoundary() != boundary) {
                    return recordedContinuedOutcome(
                        enrollment = enrollment,
                        outcome = DownloadContinuedDrainOutcome.BoundaryInvalidated,
                    )
                }
                if (!hasRunnableWork(boundary)) {
                    return completedContinuedOutcome(
                        boundary = boundary,
                        enrollment = enrollment,
                        completedWork = completedWork,
                    )
                }
                when (val outcome = transferCoordinator.runOnce(expectedBoundary = boundary)) {
                    DownloadTransferResult.Completed -> completedWork = true
                    DownloadTransferResult.NoWork ->
                        return completedContinuedOutcome(
                            boundary = boundary,
                            enrollment = enrollment,
                            completedWork = completedWork,
                        )
                    DownloadTransferResult.Paused ->
                        return recordedContinuedOutcome(
                            enrollment = enrollment,
                            outcome = DownloadContinuedDrainOutcome.Paused,
                        )
                    DownloadTransferResult.BlockedByQuota ->
                        return recordedContinuedOutcome(
                            enrollment = enrollment,
                            outcome = DownloadContinuedDrainOutcome.QuotaBlocked,
                        )
                    is DownloadTransferResult.Failed -> {
                        // A late explicit enrollment must be compared with the
                        // version seen before this queue probe. Otherwise a
                        // Retry that joins while this failed wake is ending can
                        // be recorded as though it had already been considered.
                        val failureProbeVersion = enrollment?.snapshotVersion()
                        val continuedOutcome =
                            if (hasRunnableEnrolledWorkAfterPredecessorFailure(boundary, enrollment)) {
                                DownloadContinuedDrainOutcome.EnrolledWorkRunnableAfterPredecessorFailure
                            } else {
                                DownloadContinuedDrainOutcome.Failed
                            }
                        return recordedContinuedOutcome(
                            enrollment = enrollment,
                            outcome = continuedOutcome,
                            observedEnrollmentVersion = failureProbeVersion,
                        )
                    }
                    DownloadTransferResult.FinalizingPending ->
                        return recordedContinuedOutcome(
                            enrollment = enrollment,
                            outcome = DownloadContinuedDrainOutcome.Failed,
                        )
                    DownloadTransferResult.BoundaryChanged,
                    DownloadTransferResult.NoActiveAccount,
                    ->
                        return recordedContinuedOutcome(
                            enrollment = enrollment,
                            outcome = DownloadContinuedDrainOutcome.BoundaryInvalidated,
                        )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return recordedContinuedOutcome(
                enrollment = enrollment,
                outcome = DownloadContinuedDrainOutcome.Failed,
            )
        }
    }

    private suspend fun completedContinuedOutcome(
        boundary: DownloadExecutionBoundary,
        enrollment: DownloadContinuedWorkEnrollment?,
        completedWork: Boolean,
    ): DownloadContinuedDrainOutcome {
        enrollment?.observeCompleted(boundary, queueCoordinator.allDownloads())
        val outcome =
            if (enrollment?.isSatisfied() == true) {
                DownloadContinuedDrainOutcome.EnrolledWorkCompleted
            } else if (completedWork) {
                DownloadContinuedDrainOutcome.Completed
            } else {
                DownloadContinuedDrainOutcome.NoWork
            }
        return recordedContinuedOutcome(enrollment, outcome)
    }

    private fun recordedContinuedOutcome(
        enrollment: DownloadContinuedWorkEnrollment?,
        outcome: DownloadContinuedDrainOutcome,
        observedEnrollmentVersion: Long? = enrollment?.snapshotVersion(),
    ): DownloadContinuedDrainOutcome {
        enrollment?.recordTerminal(outcome, observedEnrollmentVersion)
        return outcome
    }

    override suspend fun hasRunnableWork(): Boolean {
        val account = currentAccount() ?: return false
        return queueCoordinator.hasClaimEligibleWork(account)
    }

    private suspend fun hasRunnableWork(boundary: DownloadExecutionBoundary): Boolean =
        currentBoundary() == boundary && queueCoordinator.hasClaimEligibleWork(boundary.accountIdentity)

    /**
     * A predecessor failure may stop the continued drain, but it must not
     * strand an exact joined row that the queue can still claim. The queue's
     * admission probe keeps quota, removal-preview, and active-attempt guards
     * authoritative.
     */
    private suspend fun hasRunnableEnrolledWorkAfterPredecessorFailure(
        boundary: DownloadExecutionBoundary,
        enrollment: DownloadContinuedWorkEnrollment?,
    ): Boolean =
        enrollment?.hasRunnableUncompletedTarget(boundary, queueCoordinator.allDownloads()) == true &&
            hasRunnableWork(boundary)

    override suspend fun execute(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> =
        try {
            when (val outcome = transferCoordinator.runOnce(platformWorkIdentity)) {
                DownloadTransferResult.FinalizingPending ->
                    Result.failure(IllegalStateException("Download finalization requires recovery."))
                else -> Result.success(Unit)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override suspend fun activeAttempt(): DownloadAttemptIdentity? {
        val account = currentAccount() ?: return null
        return queueCoordinator
            .allDownloads()
            .firstOrNull { record ->
                record.businessKey.accountIdentity == account && record.state == DownloadState.Downloading
            }?.let { record -> DownloadAttemptIdentity(record.downloadId, record.attemptGeneration) }
    }

    override suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit> =
        try {
            val account = currentAccount() ?: return Result.success(Unit)
            val target =
                attempt ?: queueCoordinator
                    .allDownloads()
                    .firstOrNull { record ->
                        record.businessKey.accountIdentity == account && record.state == DownloadState.Downloading
                    }?.let { record -> DownloadAttemptIdentity(record.downloadId, record.attemptGeneration) }
            if (target == null) return Result.success(Unit)
            val commandResult = queueCoordinator.pause(account, target.downloadId)
            if (commandResult == com.jellyscope.core.domain.model.DownloadCommandResult.ActiveAttemptUnavailable) {
                return when (queueCoordinator.checkpointPersistedAttempt(target, DownloadState.Paused)) {
                    is com.jellyscope.core.data.local.DownloadAttemptInvalidationResult.Invalidated,
                    -> Result.success(Unit)
                    com.jellyscope.core.data.local.DownloadAttemptInvalidationResult.StaleAttempt,
                    com.jellyscope.core.data.local.DownloadAttemptInvalidationResult.RemovalInProgress,
                    is com.jellyscope.core.data.local.DownloadAttemptInvalidationResult.Finalizing,
                    -> Result.failure(IllegalStateException("Download could not be suspended."))
                }
            }
            when (commandResult) {
                com.jellyscope.core.domain.model.DownloadCommandResult.Applied,
                com.jellyscope.core.domain.model.DownloadCommandResult.NotFound,
                com.jellyscope.core.domain.model.DownloadCommandResult.InvalidState,
                -> Result.success(Unit)
                com.jellyscope.core.domain.model.DownloadCommandResult.AccountNotOwned,
                com.jellyscope.core.domain.model.DownloadCommandResult.RemovalInProgress,
                com.jellyscope.core.domain.model.DownloadCommandResult.ActiveAttemptUnavailable,
                com.jellyscope.core.domain.model.DownloadCommandResult.SchedulingRejected,
                -> Result.failure(IllegalStateException("Download could not be suspended."))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override suspend fun checkpointAndRequeue(attempt: DownloadAttemptIdentity?): Result<DownloadLifecycleRequeueOutcome> =
        try {
            val account = currentAccount() ?: return Result.success(DownloadLifecycleRequeueOutcome.NoActiveAttempt)
            Result.success(queueCoordinator.checkpointAndRequeueForLifecycle(account, attempt))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override suspend fun reassociate(work: DownloadExecutionWork): Result<Unit> =
        try {
            val attempt = work.attempt ?: return Result.failure(IllegalStateException("Missing persisted download attempt."))
            val account = currentAccount() ?: return Result.failure(IllegalStateException("No active account."))
            val record =
                queueCoordinator.allDownloads().firstOrNull { candidate ->
                    candidate.downloadId == attempt.downloadId &&
                        candidate.attemptGeneration == attempt.attemptGeneration &&
                        candidate.businessKey.accountIdentity == account &&
                        candidate.state == DownloadState.Downloading &&
                        candidate.platformWorkIdentity == work.platformWorkIdentity
                }
            if (record == null) {
                Result.failure(IllegalStateException("Persisted download work is stale."))
            } else {
                Result.success(Unit)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override suspend fun applyRecoveryAction(action: DownloadActiveRecoveryAction): Result<Unit> =
        try {
            when (action) {
                DownloadActiveRecoveryAction.None -> Result.success(Unit)
                is DownloadActiveRecoveryAction.Reassociate -> reassociate(action.work)
                is DownloadActiveRecoveryAction.Requeue -> {
                    if (queueCoordinator.requeueRecovered(action.attempt)) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("Persisted download could not be requeued."))
                    }
                }
                is DownloadActiveRecoveryAction.PauseForExplicitResume -> {
                    if (queueCoordinator.pauseRecoveredUidt(action.attempt)) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("Persisted UIDT download could not be paused."))
                    }
                }
                is DownloadActiveRecoveryAction.ReconcileFinalizing -> {
                    if (queueCoordinator.reconcileFinalizing(action.attempt)) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("Persisted finalizing download remains active."))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override suspend fun reconcilePresentation(record: DownloadRecord): Result<Unit> {
        val artifactStore = artifactStore ?: return Result.success(Unit)
        val registry = serverScopedStoreRegistry ?: return Result.success(Unit)
        val loggedIn = sessionRepository.sessionState.value as? SessionState.LoggedIn ?: return Result.success(Unit)
        val accountIdentity = loggedIn.session.accountIdentity()
        if (record.businessKey.accountIdentity != accountIdentity) return Result.success(Unit)
        val lease =
            registry.acquireWorkLease(
                accountIdentity = accountIdentity,
                boundaryEpoch = loggedIn.boundaryEpoch,
            ) ?: return Result.failure(IllegalStateException("Download account boundary changed during presentation recovery."))
        return try {
            registry.withGuardedLease(lease) {
                val inspection = artifactStore.reconcilePresentation(record.request.artifactKey)
                if (queueCoordinator.reconcilePresentationBytes(record, inspection.totalBytes)) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Download presentation recovery ownership changed."))
                }
            } ?: Result.failure(IllegalStateException("Download account boundary changed during presentation recovery."))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    override suspend fun cancelRequested(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> =
        try {
            val account = currentAccount() ?: return Result.success(Unit)
            val record =
                queueCoordinator.allDownloads().firstOrNull { candidate ->
                    candidate.platformWorkIdentity == platformWorkIdentity &&
                        candidate.businessKey.accountIdentity == account
                } ?: return Result.success(Unit)
            when (queueCoordinator.cancel(account, record.downloadId)) {
                com.jellyscope.core.domain.model.DownloadDeletionResult.Deleted,
                com.jellyscope.core.domain.model.DownloadDeletionResult.NotFound,
                -> Result.success(Unit)
                else -> Result.failure(IllegalStateException("Download cancellation was not admitted."))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    private fun currentAccount() = currentBoundary()?.accountIdentity

    private fun currentBoundary(): DownloadExecutionBoundary? =
        (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.let { loggedIn ->
            DownloadExecutionBoundary(
                accountIdentity = loggedIn.session.accountIdentity(),
                boundaryEpoch = loggedIn.boundaryEpoch,
            )
        }

    /** Prevents a quota/block boundary from spinning the app-active wake indefinitely. */
    private fun outcomeStopsWake(outcome: DownloadTransferResult): Boolean =
        outcome is DownloadTransferResult.NoWork ||
            outcome is DownloadTransferResult.NoActiveAccount ||
            outcome is DownloadTransferResult.BlockedByQuota ||
            outcome is DownloadTransferResult.Paused
}
