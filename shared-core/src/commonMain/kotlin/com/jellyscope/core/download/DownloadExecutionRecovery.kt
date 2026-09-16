// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the startup recovery barrier shared by all supported execution hosts.
 *
 * The platform host only reports native identities and cancels native work. The
 * common runner owns durable state changes (reassociate, requeue, finalizing
 * reconciliation, and UIDT orphan pause) through [DownloadExecutionDriver].
 * No platform can schedule a new FIFO claim until this method succeeds.
 */
internal class DownloadExecutionRecovery(
    private val queueCoordinator: DownloadQueueCoordinator,
    private val driver: DownloadExecutionDriver,
) {
    private val logger = diagnosticLogger(DiagnosticTag.DownloadExecution)
    private val mutex = Mutex()
    private var initialRecoveryComplete = false

    /** Runs once before the first startup or explicit user-action wake. */
    suspend fun recoverBeforeFirstWake(host: DownloadExecutionHost): Result<Unit> =
        mutex.withLock {
            if (initialRecoveryComplete) return@withLock Result.success(Unit)
            recoverNow(host).also { result ->
                if (result.isSuccess) initialRecoveryComplete = true
            }
        }

    /** Runs a fresh active-transition recovery after the host checkpointed. */
    suspend fun recover(host: DownloadExecutionHost): Result<Unit> = mutex.withLock { recoverNow(host) }

    private suspend fun recoverNow(host: DownloadExecutionHost): Result<Unit> =
        try {
            logger.i { "stage=download-recovery event=started" }
            // Removal dismissal is a common cleanup operation, but the follow-up wake must use
            // the already-bound platform host (UIDT/WorkManager on Android, app-active scheduling
            // on iOS/JVM). Register it at the recovery boundary so cleanup never depends on a
            // platform scheduler or DI edge of its own.
            queueCoordinator.registerRemovalPreviewReleaseWake { host.wakeFromUserAction() }
            val records = queueCoordinator.allDownloads()
            val discoveredWork = host.queryActiveWork().getOrThrow()
            val plan = decideDownloadRecovery(records, discoveredWork)
            val applied =
                applyDownloadRecoveryPlan(host, plan) { action ->
                    when (action) {
                        DownloadActiveRecoveryAction.None -> Result.success(Unit)
                        is DownloadActiveRecoveryAction.Reassociate -> driver.reassociate(action.work)
                        is DownloadActiveRecoveryAction.ReconcileFinalizing,
                        is DownloadActiveRecoveryAction.Requeue,
                        is DownloadActiveRecoveryAction.PauseForExplicitResume,
                        -> driver.applyRecoveryAction(action)
                    }
                }
            applied.getOrThrow()
            val reassociatedAttempt =
                (plan.activeAction as? DownloadActiveRecoveryAction.Reassociate)?.work?.attempt
            queueCoordinator.allDownloads().forEach { record ->
                if (
                    reassociatedAttempt?.let { attempt ->
                        attempt.downloadId == record.downloadId && attempt.attemptGeneration == record.attemptGeneration
                    } == true
                ) {
                    return@forEach
                }
                driver.reconcilePresentation(record).getOrThrow()
            }
            logger.i { "stage=download-recovery event=completed" }
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.w { "stage=download-recovery event=failed exceptionType=${throwable.playbackExceptionType()}" }
            Result.failure(throwable)
        }
}
