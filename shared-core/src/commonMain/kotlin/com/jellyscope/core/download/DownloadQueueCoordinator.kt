// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.local.DownloadAttemptInvalidationResult
import com.jellyscope.core.data.local.GateHeldBoundaryCommit
import com.jellyscope.core.data.repository.DownloadActiveAttemptRegistration
import com.jellyscope.core.data.repository.DownloadCheckpointFacts
import com.jellyscope.core.data.repository.DownloadCommandCoordinator
import com.jellyscope.core.data.repository.DownloadQueueRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.nextDownloadAttemptGeneration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface RegisteredAttemptFinalizationResult {
    data object Started : RegisteredAttemptFinalizationResult

    data object InvalidArtifact : RegisteredAttemptFinalizationResult

    data object StaleAttempt : RegisteredAttemptFinalizationResult
}

/**
 * One application-scoped serialization point for FIFO claims and generation-bound callbacks.
 * Platform hosts may wake it, but they do not select queue order or create another runner.
 */
internal class DownloadQueueCoordinator(
    private val repository: DownloadQueueRepository,
) : DownloadCommandCoordinator {
    private val runnerMutex = Mutex()
    private var registeredAttempt: DownloadActiveAttemptRegistration? = null
    private var claimedAttempt: DownloadAttemptIdentity? = null
    private val removalPreviewAccounts = mutableSetOf<AccountIdentity>()
    private var removalPreviewReleaseWake: (suspend () -> Result<Unit>)? = null

    /**
     * Prevents a newly queued row from being claimed while a destructive removal preview is
     * visible. The preview has already quiesced the live writer; keeping the account blocked here
     * is what makes the displayed byte/count snapshot stable until confirmation or dismissal.
     */
    suspend fun beginRemovalPreview(accounts: Set<AccountIdentity>): Boolean =
        runnerMutex.withLock {
            if (accounts.any { account -> account in removalPreviewAccounts }) return@withLock false
            removalPreviewAccounts += accounts
            true
        }

    suspend fun endRemovalPreview(accounts: Set<AccountIdentity>) {
        runnerMutex.withLock {
            removalPreviewAccounts.removeAll(accounts)
        }
    }

    /**
     * Installs the current platform wake used when a dismissed removal preview releases the
     * queue guard.  Registration lives with recovery rather than DI so the cleanup participant
     * can remain common and does not retain a platform scheduler directly.
     */
    suspend fun registerRemovalPreviewReleaseWake(wake: suspend () -> Result<Unit>) {
        runnerMutex.withLock {
            removalPreviewReleaseWake = wake
        }
    }

    /** Runs the registered host wake after the guard has been removed. */
    suspend fun wakeAfterRemovalPreviewRelease(): Result<Unit> {
        val wake = runnerMutex.withLock { removalPreviewReleaseWake }
        return wake?.invoke() ?: Result.success(Unit)
    }

    suspend fun allDownloads(): List<DownloadRecord> = runnerMutex.withLock { repository.allDownloads() }

    suspend fun claimNext(
        activeAccount: AccountIdentity,
        platformWorkIdentity: DownloadPlatformWorkIdentity? = null,
    ): DownloadRecord? =
        runnerMutex.withLock {
            if (!canClaimNewWorkLocked(activeAccount)) return@withLock null
            val records = repository.allDownloads()
            val reassociated =
                platformWorkIdentity?.let { identity ->
                    records.firstOrNull { record ->
                        record.platformWorkIdentity == identity &&
                            record.state == DownloadState.Downloading &&
                            record.businessKey.accountIdentity == activeAccount
                    }
                }
            if (reassociated != null) {
                claimedAttempt = DownloadAttemptIdentity(reassociated.downloadId, reassociated.attemptGeneration)
                return@withLock reassociated
            }
            if (hasDurableActiveLocked(records)) return@withLock null
            val fifoHead = fifoHeadLocked(records, activeAccount)
            if (fifoHead?.state == DownloadState.BlockedByQuota) return@withLock null
            repository.claimOldest(activeAccount, platformWorkIdentity)?.also { claimed ->
                claimedAttempt = DownloadAttemptIdentity(claimed.downloadId, claimed.attemptGeneration)
            }
        }

    /**
     * The scheduling admission probe shares the exact new-claim guard and FIFO-head predicate
     * used by [claimNext].  In particular, a queued row under a removal preview is not runnable
     * merely because it is durable and visible in the record store.
     */
    suspend fun hasClaimEligibleWork(activeAccount: AccountIdentity): Boolean =
        runnerMutex.withLock {
            if (!canClaimNewWorkLocked(activeAccount)) return@withLock false
            val records = repository.allDownloads()
            if (hasDurableActiveLocked(records)) return@withLock false
            fifoHeadLocked(records, activeAccount)?.state == DownloadState.Queued
        }

    suspend fun hasBlockedFifoHead(accountIdentity: AccountIdentity): Boolean =
        runnerMutex.withLock {
            fifoHeadLocked(repository.allDownloads(), accountIdentity)?.state == DownloadState.BlockedByQuota
        }

    private fun canClaimNewWorkLocked(accountIdentity: AccountIdentity): Boolean =
        registeredAttempt == null &&
            claimedAttempt == null &&
            accountIdentity !in removalPreviewAccounts

    private fun fifoHeadLocked(
        records: List<DownloadRecord>,
        accountIdentity: AccountIdentity,
    ): DownloadRecord? =
        records
            .filter { record ->
                record.businessKey.accountIdentity == accountIdentity &&
                    (record.state == DownloadState.Queued || record.state == DownloadState.BlockedByQuota)
            }.minByOrNull { record -> record.fifoSequence }

    private fun hasDurableActiveLocked(records: List<DownloadRecord>): Boolean =
        records.any { record ->
            record.state == DownloadState.Downloading || record.state == DownloadState.Finalizing
        }

    /**
     * Associates the real account lease and writer checkpoint callback with one claimed attempt.
     * The coordinator intentionally keeps only one registration and never ages it out.
     */
    suspend fun registerActiveAttempt(registration: DownloadActiveAttemptRegistration): Boolean =
        runnerMutex.withLock {
            if (registeredAttempt != null) return@withLock false
            if (!repository.registerActiveAttempt(registration)) return@withLock false
            registeredAttempt = registration
            if (claimedAttempt == registration.attempt) claimedAttempt = null
            true
        }

    suspend fun updateRegisteredAttemptFacts(
        attempt: DownloadAttemptIdentity,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean =
        runnerMutex.withLock {
            val registration = registeredAttempt ?: return@withLock false
            if (registration.attempt != attempt) return@withLock false
            if (!repository.updateRegisteredAttemptFacts(attempt, physicalBytes, checkpointBytes)) {
                return@withLock false
            }
            registration.updateFacts(DownloadCheckpointFacts(physicalBytes, checkpointBytes))
            true
        }

    suspend fun updateOriginalSourceFacts(
        attempt: DownloadAttemptIdentity,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean =
        runnerMutex.withLock {
            repository.updateOriginalSourceFacts(
                downloadId = attempt.downloadId,
                expectedAttemptGeneration = attempt.attemptGeneration,
                expectedSourceBytes = expectedSourceBytes,
                sourceValidator = sourceValidator,
            )
        }

    /**
     * Closes the one registered writer, persists its final bounded facts, and settles a failure
     * or suspension state for the exact generation.  A missing/stale generation is update-only:
     * the writer registration is discarded and no newer row is touched.
     */
    suspend fun finishRegisteredAttempt(
        attempt: DownloadAttemptIdentity,
        nextState: DownloadState,
        failure: DownloadFailure? = null,
    ): Boolean =
        runnerMutex.withLock {
            val registration =
                registeredAttempt
                    ?: return@withLock false
            if (registration.attempt != attempt) return@withLock false
            val facts = registration.checkpointAndClose()
            if (!repository.updateRegisteredAttemptFacts(attempt, facts.physicalBytes, facts.checkpointBytes)) {
                registeredAttempt = null
                if (claimedAttempt == attempt) claimedAttempt = null
                return@withLock false
            }
            val transitioned =
                repository.transitionAttempt(
                    downloadId = attempt.downloadId,
                    expectedAttemptGeneration = attempt.attemptGeneration,
                    nextState = nextState,
                    failure = failure,
                )
            registeredAttempt = null
            if (claimedAttempt == attempt) claimedAttempt = null
            transitioned
        }

    /**
     * Closes a live writer after its lease has already gone stale without writing any durable
     * facts.  Boundary participants normally checkpoint first; this is the fail-closed cleanup
     * for a response callback that observes the boundary replacement after admission.
     */
    suspend fun closeRegisteredAttemptWithoutCommit(attempt: DownloadAttemptIdentity): Boolean =
        runnerMutex.withLock {
            val registration =
                registeredAttempt
                    ?: return@withLock false
            if (registration.attempt != attempt) return@withLock false
            registration.checkpointAndClose()
            registeredAttempt = null
            if (claimedAttempt == attempt) claimedAttempt = null
            true
        }

    /** Fail-closed boundary cleanup when the live lease became stale during response admission. */
    suspend fun invalidateRegisteredAttemptAfterBoundary(attempt: DownloadAttemptIdentity): DownloadAttemptInvalidationResult =
        runnerMutex.withLock {
            val registration =
                registeredAttempt
                    ?: return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            if (registration.attempt != attempt) return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            val result =
                repository.checkpointAndInvalidateActiveAttempt(
                    accountIdentity = registration.accountIdentity,
                    registration = registration,
                    nextState = DownloadState.Queued,
                )
            registeredAttempt = null
            claimedAttempt = null
            result
        }

    /**
     * Closes and checkpoints the writer, validates the exact staging shape, then publishes
     * Finalizing for the same attempt generation.  Validation is deliberately bounded local
     * artifact work and happens while the runner lock is held.
     */
    suspend fun finalizeRegisteredAttempt(
        attempt: DownloadAttemptIdentity,
        validationFailure: DownloadFailure,
        validate: suspend () -> Boolean,
    ): RegisteredAttemptFinalizationResult =
        runnerMutex.withLock {
            val registration =
                registeredAttempt
                    ?: return@withLock RegisteredAttemptFinalizationResult.StaleAttempt
            if (registration.attempt != attempt) {
                return@withLock RegisteredAttemptFinalizationResult.StaleAttempt
            }
            val facts = registration.checkpointAndClose()
            if (!repository.updateRegisteredAttemptFacts(attempt, facts.physicalBytes, facts.checkpointBytes)) {
                registeredAttempt = null
                if (claimedAttempt == attempt) claimedAttempt = null
                return@withLock RegisteredAttemptFinalizationResult.StaleAttempt
            }
            if (!validate()) {
                val failed =
                    repository.transitionAttempt(
                        downloadId = attempt.downloadId,
                        expectedAttemptGeneration = attempt.attemptGeneration,
                        nextState = DownloadState.Failed,
                        failure = validationFailure,
                    )
                registeredAttempt = null
                if (claimedAttempt == attempt) claimedAttempt = null
                return@withLock if (failed) {
                    RegisteredAttemptFinalizationResult.InvalidArtifact
                } else {
                    RegisteredAttemptFinalizationResult.StaleAttempt
                }
            }
            val transitioned =
                repository.transitionAttempt(
                    downloadId = attempt.downloadId,
                    expectedAttemptGeneration = attempt.attemptGeneration,
                    nextState = DownloadState.Finalizing,
                )
            registeredAttempt = null
            if (claimedAttempt == attempt) claimedAttempt = null
            if (transitioned) {
                RegisteredAttemptFinalizationResult.Started
            } else {
                RegisteredAttemptFinalizationResult.StaleAttempt
            }
        }

    /** Runs one bounded local artifact operation without racing writer registration changes. */
    suspend fun <T> withRunnerLock(block: suspend () -> T): T = runnerMutex.withLock { block() }

    suspend fun clearRegisteredAttempt(attempt: DownloadAttemptIdentity): Boolean =
        runnerMutex.withLock {
            val registration = registeredAttempt ?: return@withLock false
            if (registration.attempt != attempt) return@withLock false
            registeredAttempt = null
            if (claimedAttempt == attempt) claimedAttempt = null
            repository.clearRegisteredAttempt(attempt)
        }

    /** Called by a gate-held session participant; this does not reacquire the session gate. */
    suspend fun checkpointAndRequeueForBoundary(
        accountIdentity: AccountIdentity,
        gateHeldBoundaryCommit: GateHeldBoundaryCommit,
    ): DownloadAttemptInvalidationResult =
        runnerMutex.withLock {
            val registration = registeredAttempt
            if (registration == null) {
                val claim = claimedAttempt
                if (claim != null) {
                    val result =
                        try {
                            repository.checkpointAndInvalidateUnregisteredAttempt(
                                accountIdentity = accountIdentity,
                                attempt = claim,
                                nextState = DownloadState.Queued,
                            )
                        } finally {
                            // A generation/delete race makes the durable result stale, but the
                            // exact in-memory claim is still obsolete and must not block FIFO work.
                            if (claimedAttempt == claim) claimedAttempt = null
                        }
                    return@withLock result
                }
                return@withLock repository.reconcileFinalizingForBoundary(accountIdentity = accountIdentity)
            }
            if (registration.accountIdentity != accountIdentity) {
                return@withLock repository.reconcileFinalizingForBoundary(accountIdentity = accountIdentity)
            }
            val result =
                repository.checkpointAndRequeueForBoundary(
                    accountIdentity = accountIdentity,
                    registration = registration,
                    gateHeldBoundaryCommit = gateHeldBoundaryCommit,
                )
            when (result) {
                is DownloadAttemptInvalidationResult.Invalidated,
                is DownloadAttemptInvalidationResult.Finalizing,
                DownloadAttemptInvalidationResult.RemovalInProgress,
                DownloadAttemptInvalidationResult.StaleAttempt,
                -> registeredAttempt = null
            }
            if (result !is DownloadAttemptInvalidationResult.StaleAttempt && claimedAttempt == registration.attempt) {
                claimedAttempt = null
            }
            result
        }

    /**
     * Quiesces an app-active transfer back into the runnable FIFO. This is a lifecycle-only path:
     * user Pause continues through [pause], and an Android UIDT orphan continues through the
     * explicit-resume recovery action. The exact attempt captured before wake cancellation is
     * required so a pre-existing user-paused row is never resumed by a later lifecycle event.
     */
    suspend fun checkpointAndRequeueForLifecycle(
        accountIdentity: AccountIdentity,
        attempt: DownloadAttemptIdentity?,
    ): DownloadLifecycleRequeueOutcome =
        runnerMutex.withLock {
            val target =
                attempt
                    ?: repository
                        .allDownloads()
                        .firstOrNull { record ->
                            record.businessKey.accountIdentity == accountIdentity &&
                                record.state == DownloadState.Downloading
                        }?.let { record -> DownloadAttemptIdentity(record.downloadId, record.attemptGeneration) }
                    ?: return@withLock DownloadLifecycleRequeueOutcome.NoActiveAttempt

            val registration = registeredAttempt
            if (registration != null) {
                if (
                    registration.accountIdentity != accountIdentity ||
                    registration.attempt != target
                ) {
                    return@withLock DownloadLifecycleRequeueOutcome.StaleAttempt
                }
                return@withLock when (
                    val result =
                        repository.checkpointAndInvalidateActiveAttempt(
                            accountIdentity = accountIdentity,
                            registration = registration,
                            nextState = DownloadState.Queued,
                        )
                ) {
                    is DownloadAttemptInvalidationResult.Invalidated -> {
                        registeredAttempt = null
                        if (claimedAttempt == target) claimedAttempt = null
                        DownloadLifecycleRequeueOutcome.Requeued
                    }
                    DownloadAttemptInvalidationResult.RemovalInProgress ->
                        DownloadLifecycleRequeueOutcome.RemovalInProgress
                    is DownloadAttemptInvalidationResult.Finalizing ->
                        DownloadLifecycleRequeueOutcome.Finalizing
                    DownloadAttemptInvalidationResult.StaleAttempt ->
                        DownloadLifecycleRequeueOutcome.StaleAttempt
                }
            }

            val claimed = claimedAttempt
            if (claimed == target) {
                val result =
                    try {
                        repository.checkpointAndInvalidateUnregisteredAttempt(
                            accountIdentity = accountIdentity,
                            attempt = target,
                            nextState = DownloadState.Queued,
                        )
                    } finally {
                        if (claimedAttempt == target) claimedAttempt = null
                    }
                return@withLock result.toLifecycleRequeueOutcome()
            }

            val record =
                repository.allDownloads().firstOrNull { candidate ->
                    candidate.downloadId == target.downloadId
                } ?: return@withLock DownloadLifecycleRequeueOutcome.StaleAttempt
            if (record.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadLifecycleRequeueOutcome.StaleAttempt
            }
            when {
                record.state == DownloadState.Queued &&
                    record.attemptGeneration == nextDownloadAttemptGeneration(target.attemptGeneration) ->
                    DownloadLifecycleRequeueOutcome.AlreadyQueued
                record.state == DownloadState.Downloading ->
                    repository
                        .checkpointAndInvalidateUnregisteredAttempt(
                            accountIdentity = accountIdentity,
                            attempt = target,
                            nextState = DownloadState.Queued,
                        ).toLifecycleRequeueOutcome()
                record.state == DownloadState.Finalizing -> DownloadLifecycleRequeueOutcome.Finalizing
                // A generic coroutine cancellation can close a registered writer through the
                // legacy Paused settlement without revoking its generation. Requeue only when
                // the row is the exact attempt captured before this lifecycle cancellation;
                // user Pause/UIDT recovery revokes the generation and therefore cannot match.
                record.state == DownloadState.Paused &&
                    record.attemptGeneration == target.attemptGeneration ->
                    if (
                        repository.transitionAttempt(
                            downloadId = target.downloadId,
                            expectedAttemptGeneration = target.attemptGeneration,
                            nextState = DownloadState.Queued,
                        )
                    ) {
                        DownloadLifecycleRequeueOutcome.Requeued
                    } else {
                        DownloadLifecycleRequeueOutcome.StaleAttempt
                    }
                else -> DownloadLifecycleRequeueOutcome.NoActiveAttempt
            }
        }

    /** Invalidates a claim that has not registered a writer yet. */
    suspend fun checkpointUnregisteredClaim(
        attempt: DownloadAttemptIdentity,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult =
        runnerMutex.withLock {
            if (claimedAttempt != attempt || registeredAttempt != null) {
                return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            }
            val record =
                repository.allDownloads().firstOrNull { candidate ->
                    candidate.downloadId == attempt.downloadId && candidate.attemptGeneration == attempt.attemptGeneration
                }
            if (record == null) {
                if (claimedAttempt == attempt) claimedAttempt = null
                return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            }
            try {
                repository.checkpointAndInvalidateUnregisteredAttempt(
                    accountIdentity = record.businessKey.accountIdentity,
                    attempt = attempt,
                    nextState = nextState,
                )
            } finally {
                // The exact claim is no longer runnable even when another actor won the
                // generation/delete race. Never retain an obsolete in-memory FIFO lock.
                if (claimedAttempt == attempt) claimedAttempt = null
            }
        }

    override suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult =
        runnerMutex.withLock {
            val record =
                repository.allDownloads().firstOrNull { candidate -> candidate.downloadId == downloadId }
                    ?: return@withLock DownloadCommandResult.NotFound
            if (record.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadCommandResult.AccountNotOwned
            }
            if (record.state != DownloadState.Downloading) {
                return@withLock repository.pause(accountIdentity, downloadId)
            }

            val registration = registeredAttempt
            if (
                registration == null ||
                registration.accountIdentity != accountIdentity ||
                registration.attempt.downloadId != downloadId ||
                registration.attempt.attemptGeneration != record.attemptGeneration
            ) {
                val claimed = claimedAttempt
                if (
                    registration == null &&
                    claimed?.downloadId == downloadId &&
                    claimed.attemptGeneration == record.attemptGeneration
                ) {
                    val invalidated =
                        try {
                            repository.checkpointAndInvalidateUnregisteredAttempt(
                                accountIdentity = accountIdentity,
                                attempt = claimed,
                                nextState = DownloadState.Paused,
                            )
                        } finally {
                            if (claimedAttempt == claimed) claimedAttempt = null
                        }
                    if (invalidated is DownloadAttemptInvalidationResult.Invalidated) {
                        return@withLock DownloadCommandResult.Applied
                    }
                    if (invalidated == DownloadAttemptInvalidationResult.RemovalInProgress) {
                        return@withLock DownloadCommandResult.RemovalInProgress
                    }
                }
                return@withLock DownloadCommandResult.ActiveAttemptUnavailable
            }
            val result =
                repository.checkpointAndInvalidateActiveAttempt(
                    accountIdentity = accountIdentity,
                    registration = registration,
                    nextState = DownloadState.Paused,
                )
            registeredAttempt = null
            result.toCommandResult()
        }

    override suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult =
        runnerMutex.withLock {
            val record =
                repository.allDownloads().firstOrNull { candidate -> candidate.downloadId == downloadId }
                    ?: return@withLock DownloadDeletionResult.NotFound
            if (record.businessKey.accountIdentity != accountIdentity) {
                return@withLock DownloadDeletionResult.AccountNotOwned
            }
            if (record.state != DownloadState.Downloading) {
                return@withLock repository.cancel(accountIdentity, downloadId)
            }

            val registration = registeredAttempt
            if (
                registration == null ||
                registration.accountIdentity != accountIdentity ||
                registration.attempt.downloadId != downloadId ||
                registration.attempt.attemptGeneration != record.attemptGeneration
            ) {
                val claimed = claimedAttempt
                if (
                    registration == null &&
                    claimed?.downloadId == downloadId &&
                    claimed.attemptGeneration == record.attemptGeneration
                ) {
                    val invalidated =
                        try {
                            repository.checkpointAndInvalidateUnregisteredAttempt(
                                accountIdentity = accountIdentity,
                                attempt = claimed,
                                nextState = DownloadState.Paused,
                            )
                        } finally {
                            if (claimedAttempt == claimed) claimedAttempt = null
                        }
                    when (invalidated) {
                        is DownloadAttemptInvalidationResult.Invalidated -> {
                            return@withLock repository.cancel(accountIdentity, downloadId)
                        }
                        DownloadAttemptInvalidationResult.RemovalInProgress -> {
                            return@withLock DownloadDeletionResult.RemovalInProgress
                        }
                        is DownloadAttemptInvalidationResult.Finalizing,
                        DownloadAttemptInvalidationResult.StaleAttempt,
                        -> Unit
                    }
                }
                return@withLock DownloadDeletionResult.ActiveAttemptUnavailable
            }
            when (
                val result =
                    repository.checkpointAndInvalidateActiveAttempt(
                        accountIdentity = accountIdentity,
                        registration = registration,
                        nextState = DownloadState.Paused,
                    )
            ) {
                is DownloadAttemptInvalidationResult.Invalidated -> {
                    registeredAttempt = null
                    repository.cancel(accountIdentity, downloadId)
                }
                DownloadAttemptInvalidationResult.RemovalInProgress -> {
                    registeredAttempt = null
                    DownloadDeletionResult.RemovalInProgress
                }
                is DownloadAttemptInvalidationResult.Finalizing,
                DownloadAttemptInvalidationResult.StaleAttempt,
                -> {
                    registeredAttempt = null
                    DownloadDeletionResult.InvalidState
                }
            }
        }

    suspend fun resume(downloadId: DownloadId): Boolean = requeue(downloadId, setOf(DownloadState.Paused, DownloadState.BlockedByQuota))

    suspend fun retry(downloadId: DownloadId): Boolean = requeue(downloadId, setOf(DownloadState.Failed))

    suspend fun recordProgress(
        attempt: DownloadAttemptIdentity,
        physicalBytes: Long,
        checkpointBytes: Long,
    ): Boolean =
        runnerMutex.withLock {
            repository.updateAttemptProgress(
                downloadId = attempt.downloadId,
                expectedAttemptGeneration = attempt.attemptGeneration,
                physicalBytes = physicalBytes,
                checkpointBytes = checkpointBytes,
            )
        }

    suspend fun extendReservation(
        attempt: DownloadAttemptIdentity,
        requiredReservationBytes: Long,
    ): DownloadReservationExtensionResult =
        runnerMutex.withLock {
            repository.extendAttemptReservation(
                downloadId = attempt.downloadId,
                expectedAttemptGeneration = attempt.attemptGeneration,
                requiredReservationBytes = requiredReservationBytes,
            )
        }

    suspend fun beginFinalizing(attempt: DownloadAttemptIdentity): Boolean = transition(attempt, DownloadState.Finalizing)

    suspend fun completeFinalizing(attempt: DownloadAttemptIdentity): Boolean =
        runnerMutex.withLock {
            repository.completeFinalizing(attempt.downloadId, attempt.attemptGeneration)
        }

    suspend fun fail(
        attempt: DownloadAttemptIdentity,
        failure: DownloadFailure,
    ): Boolean = transition(attempt, DownloadState.Failed, failure)

    /** Settles only a claim that has not registered a writer, releasing the in-memory FIFO lock. */
    suspend fun failClaimedAttempt(
        attempt: DownloadAttemptIdentity,
        failure: DownloadFailure,
    ): Boolean =
        runnerMutex.withLock {
            if (registeredAttempt != null || claimedAttempt != attempt) return@withLock false
            try {
                repository.transitionAttempt(
                    downloadId = attempt.downloadId,
                    expectedAttemptGeneration = attempt.attemptGeneration,
                    nextState = DownloadState.Failed,
                    failure = failure,
                )
            } finally {
                if (claimedAttempt == attempt) claimedAttempt = null
            }
        }

    suspend fun requeueRecovered(attempt: DownloadAttemptIdentity): Boolean = transition(attempt, DownloadState.Queued)

    suspend fun pauseRecoveredUidt(attempt: DownloadAttemptIdentity): Boolean = transition(attempt, DownloadState.Paused)

    suspend fun reconcileFinalizingForBoundary(accountIdentity: AccountIdentity): DownloadAttemptInvalidationResult =
        runnerMutex.withLock {
            repository.reconcileFinalizingForBoundary(accountIdentity)
        }

    /** Reconciles the exact generation selected by startup recovery and proves it is terminal. */
    suspend fun reconcileFinalizing(attempt: DownloadAttemptIdentity): Boolean =
        runnerMutex.withLock {
            val before =
                repository.allDownloads().firstOrNull { record ->
                    record.downloadId == attempt.downloadId && record.attemptGeneration == attempt.attemptGeneration
                } ?: return@withLock true
            if (before.state != DownloadState.Finalizing) return@withLock false
            repository.reconcileFinalizingForBoundary(before.businessKey.accountIdentity)
            repository
                .allDownloads()
                .firstOrNull { record ->
                    record.downloadId == attempt.downloadId && record.attemptGeneration == attempt.attemptGeneration
                }?.state != DownloadState.Finalizing
        }

    /** Quiesces an exact persisted active row when no live registration survived process death. */
    suspend fun checkpointPersistedAttempt(
        attempt: DownloadAttemptIdentity,
        nextState: DownloadState,
    ): DownloadAttemptInvalidationResult =
        runnerMutex.withLock {
            val record =
                repository.allDownloads().firstOrNull { candidate ->
                    candidate.downloadId == attempt.downloadId && candidate.attemptGeneration == attempt.attemptGeneration
                } ?: return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            if (record.state != DownloadState.Downloading) {
                return@withLock DownloadAttemptInvalidationResult.StaleAttempt
            }
            repository.checkpointAndInvalidateUnregisteredAttempt(
                accountIdentity = record.businessKey.accountIdentity,
                attempt = attempt,
                nextState = nextState,
            )
        }

    private suspend fun requeue(
        downloadId: DownloadId,
        eligibleStates: Set<DownloadState>,
    ): Boolean =
        runnerMutex.withLock {
            val record =
                repository.allDownloads().firstOrNull { candidate -> candidate.downloadId == downloadId }
                    ?: return@withLock false
            if (record.state !in eligibleStates) return@withLock false
            repository.transitionAttempt(
                downloadId = downloadId,
                expectedAttemptGeneration = record.attemptGeneration,
                nextState = DownloadState.Queued,
            )
        }

    private suspend fun transition(
        attempt: DownloadAttemptIdentity,
        nextState: DownloadState,
        failure: DownloadFailure? = null,
    ): Boolean =
        runnerMutex.withLock {
            repository
                .transitionAttempt(
                    downloadId = attempt.downloadId,
                    expectedAttemptGeneration = attempt.attemptGeneration,
                    nextState = nextState,
                    failure = failure,
                ).also { transitioned ->
                    if (transitioned && claimedAttempt == attempt) claimedAttempt = null
                }
        }

    private fun DownloadAttemptInvalidationResult.toCommandResult(): DownloadCommandResult =
        when (this) {
            is DownloadAttemptInvalidationResult.Invalidated -> DownloadCommandResult.Applied
            DownloadAttemptInvalidationResult.RemovalInProgress -> DownloadCommandResult.RemovalInProgress
            is DownloadAttemptInvalidationResult.Finalizing,
            DownloadAttemptInvalidationResult.StaleAttempt,
            -> DownloadCommandResult.InvalidState
        }

    private fun DownloadAttemptInvalidationResult.toLifecycleRequeueOutcome(): DownloadLifecycleRequeueOutcome =
        when (this) {
            is DownloadAttemptInvalidationResult.Invalidated -> DownloadLifecycleRequeueOutcome.Requeued
            DownloadAttemptInvalidationResult.RemovalInProgress -> DownloadLifecycleRequeueOutcome.RemovalInProgress
            is DownloadAttemptInvalidationResult.Finalizing -> DownloadLifecycleRequeueOutcome.Finalizing
            DownloadAttemptInvalidationResult.StaleAttempt -> DownloadLifecycleRequeueOutcome.StaleAttempt
        }
}
