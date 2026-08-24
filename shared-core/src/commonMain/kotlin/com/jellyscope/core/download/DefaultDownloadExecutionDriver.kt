// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
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

    override suspend fun hasRunnableWork(): Boolean {
        val account = currentAccount() ?: return false
        return queueCoordinator.hasClaimEligibleWork(account)
    }

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

    private fun currentAccount() = (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.session?.accountIdentity()

    /** Prevents a quota/block boundary from spinning the app-active wake indefinitely. */
    private fun outcomeStopsWake(outcome: DownloadTransferResult): Boolean =
        outcome is DownloadTransferResult.NoWork ||
            outcome is DownloadTransferResult.NoActiveAccount ||
            outcome is DownloadTransferResult.BlockedByQuota ||
            outcome is DownloadTransferResult.Paused
}
