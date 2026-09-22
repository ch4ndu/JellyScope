// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadPlatformWorkKind
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException

/** Exact durable attempt identity carried by every platform callback. */
internal data class DownloadAttemptIdentity(
    val downloadId: DownloadId,
    val attemptGeneration: Long,
) {
    init {
        require(attemptGeneration >= 0L) { "Attempt generation must be non-negative." }
    }
}

/** A live platform work item discovered during recovery. */
internal data class DownloadExecutionWork(
    /** Null means the native identity is stale/orphaned and has no durable row to reassociate. */
    val attempt: DownloadAttemptIdentity?,
    val platformWorkIdentity: DownloadPlatformWorkIdentity,
)

/** Cancellation cause used only while an app-active lifecycle boundary is requeueing a wake. */
internal class DownloadLifecycleRequeueCancellation :
    CancellationException(
        "Download lifecycle suspension requested.",
    )

/**
 * Narrow callback owned by the common transfer runner and consumed by a supported-platform host.
 *
 * Native schedulers must not claim queue rows, select FIFO order, or implement transfer policy.
 * They only provide a durable platform wake/stop boundary and hand the exact persisted work
 * identity back to the common runner.  The runner owns the account lease, attempt generation,
 * checkpoint writer, and terminal state settlement.
 */
internal interface DownloadExecutionDriver {
    /** App-active wake for iOS/JVM; Android invokes [execute] from native work. */
    suspend fun wake(): Result<Unit>

    /**
     * Drains an iOS continued-processing generation after capturing one exact
     * account and lifecycle epoch. Non-iOS implementations retain [wake].
     */
    suspend fun drainContinuedWork(enrollment: DownloadContinuedWorkEnrollment? = null): DownloadContinuedDrainOutcome =
        DownloadContinuedDrainOutcome.Unsupported

    /** Captures the current account epoch for one explicit iOS continuation intent. */
    suspend fun prepareContinuedWorkEnrollment(enrollment: DownloadExplicitWorkEnrollment): DownloadContinuedWorkEnrollment? = null

    /**
     * Reports whether the currently logged-in account has a runnable FIFO
     * head.  Hosts use this only as a scheduling admission probe; claim and
     * state transitions remain coordinator-owned.
     */
    suspend fun hasRunnableWork(): Boolean

    suspend fun execute(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit>

    /** Captures the live attempt before an app-active host cancels its retained wake job. */
    suspend fun activeAttempt(): DownloadAttemptIdentity?

    suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit>

    /**
     * Lifecycle-only quiescence for app suspension/exit.  This is deliberately distinct from a
     * user Pause: a live attempt is checkpointed back to the runnable FIFO so the next active wake
     * may resume it automatically.
     */
    suspend fun checkpointAndRequeue(attempt: DownloadAttemptIdentity?): Result<DownloadLifecycleRequeueOutcome>

    suspend fun reassociate(work: DownloadExecutionWork): Result<Unit>

    /** Applies a durable recovery action after native duplicate cancellation has completed. */
    suspend fun applyRecoveryAction(action: DownloadActiveRecoveryAction): Result<Unit>

    /** Repairs only this known row's sibling presentation area after the cancellation barrier. */
    suspend fun reconcilePresentation(record: DownloadRecord): Result<Unit> = Result.success(Unit)

    /** User-facing notification cancellation; stale-work cancellation stays host-local. */
    suspend fun cancelRequested(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit>
}

/** Immutable account boundary that a continued native grant is allowed to drain. */
internal data class DownloadExecutionBoundary(
    val accountIdentity: AccountIdentity,
    val boundaryEpoch: Long,
)

/**
 * In-memory, account-epoch-bound ledger for explicit work admitted to one
 * iOS continued-processing generation. It never reaches Swift and is kept
 * only long enough to decide the exact native completion result.
 */
internal class DownloadContinuedWorkEnrollment(
    private var boundary: DownloadExecutionBoundary?,
    initialEnrollment: DownloadExplicitWorkEnrollment? = null,
) {
    private val lock = ReentrantLock()
    private val minimumCompletionGeneration = linkedMapOf<DownloadId, Long>()
    private val completedIds = mutableSetOf<DownloadId>()
    private var enrollmentVersion = 0L
    private var terminalObservation: TerminalObservation? = null
    private var closed = false

    init {
        initialEnrollment?.let(::merge)
    }

    fun bindBoundary(candidate: DownloadExecutionBoundary): Boolean =
        lock.withLock {
            val current = boundary
            if (current == null) {
                boundary = candidate
                true
            } else {
                current == candidate
            }
        }

    fun merge(enrollment: DownloadExplicitWorkEnrollment): Boolean =
        lock.withLock {
            if (closed) return@withLock false
            val current = boundary
            if (current != null && current.accountIdentity != enrollment.accountIdentity) {
                return@withLock false
            }
            var changed = false
            enrollment.work.forEach { target ->
                val current = minimumCompletionGeneration[target.downloadId]
                val merged =
                    current?.let { minimum -> maxOf(minimum, target.minimumCompletionAttemptGeneration) }
                        ?: target.minimumCompletionAttemptGeneration
                if (current != merged) {
                    minimumCompletionGeneration[target.downloadId] = merged
                    // A completion that satisfied an earlier explicit action
                    // cannot acknowledge a later Retry that requires a newer
                    // durable attempt.
                    completedIds.remove(target.downloadId)
                    changed = true
                }
            }
            if (changed) {
                enrollmentVersion += 1L
            }
            true
        }

    fun absorb(other: DownloadContinuedWorkEnrollment): Boolean {
        val incoming = other.snapshot() ?: return false
        return lock.withLock {
            if (closed) return@withLock false
            val current = boundary
            if (current == null) {
                boundary = incoming.boundary
            } else if (current != incoming.boundary) {
                return@withLock false
            }
            var changed = false
            incoming.minimumCompletionGeneration.forEach { (downloadId, minimumGeneration) ->
                val currentMinimum = minimumCompletionGeneration[downloadId]
                val merged =
                    currentMinimum?.let { current -> maxOf(current, minimumGeneration) }
                        ?: minimumGeneration
                if (currentMinimum != merged) {
                    minimumCompletionGeneration[downloadId] = merged
                    completedIds.remove(downloadId)
                    changed = true
                }
            }
            if (changed) {
                enrollmentVersion += 1L
            }
            true
        }
    }

    fun observeCompleted(
        currentBoundary: DownloadExecutionBoundary,
        records: List<DownloadRecord>,
    ) {
        lock.withLock {
            if (boundary != currentBoundary || minimumCompletionGeneration.isEmpty()) return
            records.forEach { record ->
                val minimumGeneration = minimumCompletionGeneration[record.downloadId] ?: return@forEach
                if (
                    record.businessKey.accountIdentity == currentBoundary.accountIdentity &&
                    record.state == DownloadState.Completed &&
                    record.attemptGeneration >= minimumGeneration
                ) {
                    completedIds += record.downloadId
                }
            }
        }
    }

    /** Captures the exact enrollment state observed before a drain outcome is decided. */
    fun snapshotVersion(): Long = lock.withLock { enrollmentVersion }

    fun recordTerminal(
        outcome: DownloadContinuedDrainOutcome,
        observedEnrollmentVersion: Long? = null,
    ) {
        lock.withLock {
            // The driver can decide success just before another explicit action
            // is absorbed. That older decision cannot acknowledge the newly
            // added targets, even when it records after the merge.
            val currentOutcome =
                if (outcome == DownloadContinuedDrainOutcome.EnrolledWorkCompleted && !isSatisfiedLocked()) {
                    DownloadContinuedDrainOutcome.Completed
                } else {
                    outcome
                }
            terminalObservation =
                TerminalObservation(
                    outcome = currentOutcome,
                    enrollmentVersion = observedEnrollmentVersion ?: enrollmentVersion,
                )
        }
    }

    fun isSatisfied(): Boolean =
        lock.withLock {
            isSatisfiedLocked()
        }

    /**
     * Identifies an exact enrolled row that remains queued after a different
     * FIFO predecessor failed. Admission still belongs to the queue owner; this
     * only distinguishes the joined intent from a failed enrolled row.
     */
    fun hasRunnableUncompletedTarget(
        currentBoundary: DownloadExecutionBoundary,
        records: List<DownloadRecord>,
    ): Boolean =
        lock.withLock {
            if (boundary != currentBoundary || minimumCompletionGeneration.isEmpty()) {
                return@withLock false
            }
            records.any { record ->
                val minimumGeneration = minimumCompletionGeneration[record.downloadId]
                minimumGeneration != null &&
                    record.businessKey.accountIdentity == currentBoundary.accountIdentity &&
                    record.state == DownloadState.Queued &&
                    record.attemptGeneration < minimumGeneration
            }
        }

    /** Seals the generation so a late join becomes a separately observed follow-up. */
    fun closeWith(outcome: DownloadContinuedDrainOutcome): Boolean =
        lock.withLock {
            closed = true
            outcome == DownloadContinuedDrainOutcome.EnrolledWorkCompleted && isSatisfiedLocked()
        }

    /** A joining explicit intent needs one post-teardown re-probe only at a completion edge. */
    fun requiresFollowUpAfterJoin(): Boolean =
        lock.withLock {
            val terminal = terminalObservation
            val enrollmentAdvancedAfterTerminal =
                terminal?.enrollmentVersion?.let { version -> version < enrollmentVersion } == true
            val safeCompletionEdge =
                terminal == null ||
                    terminal.outcome == DownloadContinuedDrainOutcome.Completed ||
                    terminal.outcome == DownloadContinuedDrainOutcome.NoWork ||
                    terminal.outcome == DownloadContinuedDrainOutcome.EnrolledWorkRunnableAfterPredecessorFailure ||
                    (
                        terminal.outcome == DownloadContinuedDrainOutcome.EnrolledWorkCompleted &&
                            enrollmentAdvancedAfterTerminal
                    ) ||
                    // An original failed enrolled attempt never retries itself.
                    // This edge exists only when a later explicit action changed
                    // the enrollment after the failed drain made its decision.
                    (
                        terminal.outcome == DownloadContinuedDrainOutcome.Failed &&
                            enrollmentAdvancedAfterTerminal
                    )
            if (
                minimumCompletionGeneration.isEmpty() ||
                isSatisfiedLocked() ||
                !safeCompletionEdge
            ) {
                false
            } else {
                true
            }
        }

    private fun isSatisfiedLocked(): Boolean =
        minimumCompletionGeneration.isNotEmpty() &&
            completedIds.containsAll(minimumCompletionGeneration.keys)

    private fun snapshot(): EnrollmentSnapshot? =
        lock.withLock {
            val currentBoundary = boundary ?: return@withLock null
            minimumCompletionGeneration
                .takeIf { it.isNotEmpty() }
                ?.let { targets ->
                    EnrollmentSnapshot(
                        boundary = currentBoundary,
                        minimumCompletionGeneration = targets.toMap(),
                    )
                }
        }

    private data class EnrollmentSnapshot(
        val boundary: DownloadExecutionBoundary,
        val minimumCompletionGeneration: Map<DownloadId, Long>,
    )

    private data class TerminalObservation(
        val outcome: DownloadContinuedDrainOutcome,
        val enrollmentVersion: Long,
    )
}

/** Closed result reported when a generation-bound continued drain reaches a safe boundary. */
internal sealed interface DownloadContinuedDrainOutcome {
    data object Unsupported : DownloadContinuedDrainOutcome

    data object Completed : DownloadContinuedDrainOutcome

    /** Every exact row enrolled by the explicit action completed at its intended generation. */
    data object EnrolledWorkCompleted : DownloadContinuedDrainOutcome

    /**
     * A different FIFO row failed while an exact joined enrollment remained
     * runnable. One serialized follow-up may observe that enrollment; this is
     * not a retry of the failed row.
     */
    data object EnrolledWorkRunnableAfterPredecessorFailure : DownloadContinuedDrainOutcome

    data object Paused : DownloadContinuedDrainOutcome

    data object QuotaBlocked : DownloadContinuedDrainOutcome

    data object Failed : DownloadContinuedDrainOutcome

    data object BoundaryInvalidated : DownloadContinuedDrainOutcome

    data object NoWork : DownloadContinuedDrainOutcome
}

internal sealed interface DownloadLifecycleRequeueOutcome {
    data object Requeued : DownloadLifecycleRequeueOutcome

    /** The transfer cancellation already persisted the runnable state before the host joined it. */
    data object AlreadyQueued : DownloadLifecycleRequeueOutcome

    /** No live attempt was present when the lifecycle boundary was observed. */
    data object NoActiveAttempt : DownloadLifecycleRequeueOutcome

    data object StaleAttempt : DownloadLifecycleRequeueOutcome

    data object RemovalInProgress : DownloadLifecycleRequeueOutcome

    data object Finalizing : DownloadLifecycleRequeueOutcome
}

/**
 * Cancels the app-active wake only after capturing its exact live attempt, then performs the
 * lifecycle-specific requeue commit. User Pause and Android UIDT orphan recovery do not use this
 * helper and therefore retain their explicit-resume Paused semantics.
 */
internal suspend fun cancelWakeAndRequeue(
    wakeGate: DownloadWakeJobGate,
    driver: DownloadExecutionDriver,
): Result<DownloadLifecycleRequeueOutcome> {
    val attempt = driver.activeAttempt()
    wakeGate.cancelAndJoin(cancellation = DownloadLifecycleRequeueCancellation())
    val logger = diagnosticLogger(DiagnosticTag.DownloadExecution)
    return driver.checkpointAndRequeue(attempt).also { result ->
        result.fold(
            onSuccess = { outcome ->
                val outcomeName =
                    when (outcome) {
                        DownloadLifecycleRequeueOutcome.Requeued -> "Requeued"
                        DownloadLifecycleRequeueOutcome.AlreadyQueued -> "AlreadyQueued"
                        DownloadLifecycleRequeueOutcome.NoActiveAttempt -> "NoActiveAttempt"
                        DownloadLifecycleRequeueOutcome.StaleAttempt -> "StaleAttempt"
                        DownloadLifecycleRequeueOutcome.RemovalInProgress -> "RemovalInProgress"
                        DownloadLifecycleRequeueOutcome.Finalizing -> "Finalizing"
                    }
                logger.i { "stage=download-lifecycle event=checkpoint result=$outcomeName" }
            },
            onFailure = { failure -> logger.w { formatSafeFailureDiagnostic("download-lifecycle", "checkpoint-failed", failure) } },
        )
    }
}

/**
 * Minimal supported-platform lifecycle seam. Concrete Android, iOS, and JVM
 * hosts provide their execution paths behind this contract.
 */
internal interface DownloadExecutionHost {
    suspend fun wake(): Result<Unit>

    /**
     * User-action-equivalent wake used after a destructive preview is dismissed. Platform hosts
     * that expose the public lifecycle surface already provide this method; the default keeps
     * focused common hosts source-compatible and falls back to their ordinary wake.
     */
    suspend fun wakeFromUserAction(): Result<Unit> = wake()

    /** Passive equivalent of [wakeFromUserAction] that must not imply native admission. */
    suspend fun wakeFromPassiveEvent(): Result<Unit> = wake()

    suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit>

    suspend fun queryActiveWork(): Result<List<DownloadExecutionWork>>

    suspend fun reassociate(work: DownloadExecutionWork): Result<Unit>

    suspend fun cancel(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit>
}

internal sealed interface DownloadActiveRecoveryAction {
    data object None : DownloadActiveRecoveryAction

    data class Reassociate(
        val work: DownloadExecutionWork,
    ) : DownloadActiveRecoveryAction

    data class ReconcileFinalizing(
        val attempt: DownloadAttemptIdentity,
    ) : DownloadActiveRecoveryAction

    data class Requeue(
        val attempt: DownloadAttemptIdentity,
    ) : DownloadActiveRecoveryAction

    /** API-34+ UIDT may disappear after Task Manager Stop without delivering a callback. */
    data class PauseForExplicitResume(
        val attempt: DownloadAttemptIdentity,
    ) : DownloadActiveRecoveryAction
}

/** All listed work must be cancelled before [activeAction] or any new FIFO claim is attempted. */
internal data class DownloadRecoveryPlan(
    val cancelBeforeClaim: List<DownloadPlatformWorkIdentity>,
    val activeAction: DownloadActiveRecoveryAction,
)

/** Pure recovery policy; it performs no database or platform work. */
internal fun decideDownloadRecovery(
    records: List<DownloadRecord>,
    discoveredWork: List<DownloadExecutionWork>,
): DownloadRecoveryPlan {
    val activeRecords =
        records.filter { record ->
            record.state == DownloadState.Downloading || record.state == DownloadState.Finalizing
        }
    check(activeRecords.size <= 1) { "Durable download state contains more than one active record." }
    val active = activeRecords.singleOrNull()
    val expectedAttempt = active?.let { record -> DownloadAttemptIdentity(record.downloadId, record.attemptGeneration) }
    val matchingWork = discoveredWork.filter { work -> work.attempt == expectedAttempt }
    val expectedPlatformWork =
        active
            ?.takeIf { record -> record.state != DownloadState.Finalizing }
            ?.platformWorkIdentity
    val retainedWork =
        expectedPlatformWork?.let { expected ->
            matchingWork.firstOrNull { work -> work.platformWorkIdentity == expected }
        } ?: if (active == null && records.any { record -> record.state == DownloadState.Queued }) {
            // A pending native wake can exist before the worker claims the
            // durable head. Keep one such wake so process restart does not
            // strand a queued item; there is no attempt identity to invent.
            discoveredWork.firstOrNull { work -> work.attempt == null }
        } else {
            null
        }
    val cancelBeforeClaim =
        discoveredWork
            .filter { work -> work !== retainedWork }
            .map(DownloadExecutionWork::platformWorkIdentity)
            .distinct()

    val activeAction =
        when {
            active == null -> DownloadActiveRecoveryAction.None
            active.state == DownloadState.Finalizing ->
                DownloadActiveRecoveryAction.ReconcileFinalizing(requireNotNull(expectedAttempt))
            retainedWork != null -> DownloadActiveRecoveryAction.Reassociate(retainedWork)
            active.platformWorkIdentity?.kind == DownloadPlatformWorkKind.AndroidUserInitiatedJob ->
                DownloadActiveRecoveryAction.PauseForExplicitResume(requireNotNull(expectedAttempt))
            else -> DownloadActiveRecoveryAction.Requeue(requireNotNull(expectedAttempt))
        }
    return DownloadRecoveryPlan(cancelBeforeClaim, activeAction)
}

/**
 * Applies the pure recovery decision's cancellation barrier. Duplicate/stale native work is
 * cancelled before reassociation, durable action, or a new FIFO claim is allowed. The callback
 * remains the coordinator-owned action seam and receives the action only after that barrier; this
 * helper does not create a scheduler or bind a platform implementation.
 */
internal suspend fun applyDownloadRecoveryPlan(
    host: DownloadExecutionHost,
    plan: DownloadRecoveryPlan,
    applyActiveAction: suspend (DownloadActiveRecoveryAction) -> Result<Unit>,
): Result<Unit> =
    try {
        plan.cancelBeforeClaim.forEach { platformWorkIdentity ->
            host.cancel(platformWorkIdentity).getOrThrow()
        }
        applyActiveAction(plan.activeAction)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
