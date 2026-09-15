// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadPlatformWorkKind
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
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

    /** User-facing notification cancellation; stale-work cancellation stays host-local. */
    suspend fun cancelRequested(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit>
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
    wakeGate.cancelAndJoin(DownloadLifecycleRequeueCancellation())
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
