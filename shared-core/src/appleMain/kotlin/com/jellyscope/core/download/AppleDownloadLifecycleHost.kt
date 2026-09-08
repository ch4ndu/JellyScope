// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification

/**
 * Apple app-active execution host. Downloads use the app-owned Ktor writer
 * only while the process is active; this host installs no background session,
 * delegate bridge, or OS-managed transfer package.
 */
internal class AppleDownloadLifecycleHost(
    private val driver: DownloadExecutionDriver,
    private val recovery: DownloadExecutionRecovery,
    private val scope: CoroutineScope,
) : DownloadExecutionHost,
    DownloadLifecycleHost {
    private var started = false
    private val observers = mutableListOf<Any>()
    private val wakeGate = DownloadWakeJobGate(scope)

    override fun start() {
        if (started) return
        started = true
        val center = NSNotificationCenter.defaultCenter
        observers +=
            center.addObserverForName(
                name = UIApplicationWillResignActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                runBlocking(Dispatchers.Default) { cancelWakeAndCheckpoint() }
            }
        observers +=
            center.addObserverForName(
                name = UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                scope.launch(Dispatchers.Default) {
                    recovery.recover(this@AppleDownloadLifecycleHost)
                }
            }
        observers +=
            center.addObserverForName(
                name = UIApplicationWillTerminateNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                // Termination does not guarantee that a launched coroutine
                // gets CPU time. Keep this bounded checkpoint synchronous at
                // the notification boundary.
                runBlocking(Dispatchers.Default) { cancelWakeAndCheckpoint() }
            }
        scope.launch(Dispatchers.Default) {
            recovery.recoverBeforeFirstWake(this@AppleDownloadLifecycleHost)
        }
    }

    /** Removes notification observers and performs the bounded exit checkpoint. */
    override fun stop() {
        if (!started) return
        started = false
        val center = NSNotificationCenter.defaultCenter
        observers.forEach(center::removeObserver)
        observers.clear()
        runBlocking(Dispatchers.Default) {
            cancelWakeAndCheckpoint()
        }
    }

    /**
     * Returns when the retained recovery/transfer task has been installed. A
     * user action must not stay in-flight for the duration of the download.
     */
    override suspend fun wakeFromUserAction(): Result<Unit> = scheduleWake(recoverBeforeFirstWake = true)

    override suspend fun wake(): Result<Unit> = wakeIfRunnable()

    private suspend fun wakeIfRunnable(): Result<Unit> =
        if (driver.hasRunnableWork()) {
            driver.wake()
        } else {
            Result.success(Unit)
        }

    private suspend fun scheduleWake(recoverBeforeFirstWake: Boolean): Result<Unit> =
        wakeGate.launch {
            if (recoverBeforeFirstWake) {
                recoverBeforeFirstWakeThenWake()
            } else {
                recoverThenWake()
            }
        }

    private suspend fun recoverThenWake(): Result<Unit> =
        recovery.recover(this).fold(
            onSuccess = { wakeIfRunnable() },
            onFailure = { failure -> Result.failure(failure) },
        )

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
        Result.failure(IllegalStateException("Apple platforms have no surviving native download work to reassociate."))

    override suspend fun cancel(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> = Result.success(Unit)
}
