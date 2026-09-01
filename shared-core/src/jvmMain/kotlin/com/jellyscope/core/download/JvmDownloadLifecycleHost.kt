// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * JVM desktop app-active execution host.  It owns no daemon, login item, or
 * helper process; transfer work exists only while the Compose application is
 * open and is checkpointed before graceful close.
 */
internal class JvmDownloadLifecycleHost(
    private val driver: DownloadExecutionDriver,
    private val recovery: DownloadExecutionRecovery,
    private val scope: CoroutineScope,
) : DownloadExecutionHost,
    DownloadLifecycleHost {
    private var started = false
    private val wakeGate = DownloadWakeJobGate(scope)

    override fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Default) {
            recovery.recoverBeforeFirstWake(this@JvmDownloadLifecycleHost)
        }
    }

    /** Performs the bounded writer checkpoint before the desktop process exits. */
    override fun stop() {
        if (!started) return
        started = false
        runBlocking(Dispatchers.Default) {
            cancelWakeAndCheckpoint()
        }
    }

    /** Returns after the retained transfer task is installed, not after it drains the file. */
    override suspend fun wakeFromUserAction(): Result<Unit> = scheduleWake()

    override suspend fun wake(): Result<Unit> = wakeIfRunnable()

    private suspend fun wakeIfRunnable(): Result<Unit> =
        if (driver.hasRunnableWork()) {
            driver.wake()
        } else {
            Result.success(Unit)
        }

    private suspend fun scheduleWake(): Result<Unit> = wakeGate.launch { recoverBeforeFirstWakeThenWake() }

    private suspend fun recoverBeforeFirstWakeThenWake(): Result<Unit> =
        recovery.recoverBeforeFirstWake(this).fold(
            onSuccess = { wakeIfRunnable() },
            onFailure = { failure -> Result.failure(failure) },
        )

    private suspend fun cancelWakeAndCheckpoint(): Result<Unit> =
        cancelWakeAndRequeue(wakeGate, driver).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    DownloadLifecycleRequeueOutcome.Requeued,
                    DownloadLifecycleRequeueOutcome.AlreadyQueued,
                    DownloadLifecycleRequeueOutcome.NoActiveAttempt,
                    -> Result.success(Unit)
                    DownloadLifecycleRequeueOutcome.StaleAttempt,
                    DownloadLifecycleRequeueOutcome.RemovalInProgress,
                    DownloadLifecycleRequeueOutcome.Finalizing,
                    -> Result.failure(IllegalStateException("Download lifecycle requeue was not admitted."))
                }
            },
            onFailure = { failure -> Result.failure(failure) },
        )

    override suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit> = driver.checkpointAndSuspend(attempt)

    override suspend fun queryActiveWork(): Result<List<DownloadExecutionWork>> = Result.success(emptyList())

    override suspend fun reassociate(work: DownloadExecutionWork): Result<Unit> =
        Result.failure(IllegalStateException("JVM has no surviving native download work to reassociate."))

    override suspend fun cancel(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> = Result.success(Unit)
}
