// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadLifecycleRequeueTest {
    @Test
    fun lifecycleStopCapturesAttemptBeforeWakeCancellationAndLeavesNextWakeEligible() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val wakeGate = DownloadWakeJobGate(scope)
                val attempt = DownloadAttemptIdentity(DownloadId("download_a"), attemptGeneration = 4L)
                val driver = FakeLifecycleDriver(attempt)
                val started = CompletableDeferred<Unit>()

                assertTrue(
                    wakeGate
                        .launch {
                            started.complete(Unit)
                            awaitCancellation()
                        }.isSuccess,
                )
                withTimeout(2_000L) { started.await() }

                val outcome = cancelWakeAndRequeue(wakeGate, driver)

                assertEquals(DownloadLifecycleRequeueOutcome.Requeued, outcome.getOrNull())
                assertEquals(attempt, driver.requeueAttempt)
                assertEquals(DownloadState.Queued, driver.state)
                assertTrue(driver.wake().isSuccess)
                assertEquals(1, driver.wakeCalls)
            } finally {
                scope.cancel()
            }
        }
}

private class FakeLifecycleDriver(
    private val liveAttempt: DownloadAttemptIdentity,
) : DownloadExecutionDriver {
    var state = DownloadState.Downloading
    var requeueAttempt: DownloadAttemptIdentity? = null
    var wakeCalls = 0

    override suspend fun wake(): Result<Unit> {
        wakeCalls += 1
        return if (state == DownloadState.Queued) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("The lifecycle requeue did not make the row runnable."))
        }
    }

    override suspend fun hasRunnableWork(): Boolean = state == DownloadState.Queued

    override suspend fun execute(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> = Result.success(Unit)

    override suspend fun activeAttempt(): DownloadAttemptIdentity? = liveAttempt

    override suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit> = Result.success(Unit)

    override suspend fun checkpointAndRequeue(attempt: DownloadAttemptIdentity?): Result<DownloadLifecycleRequeueOutcome> {
        requeueAttempt = attempt
        state = DownloadState.Queued
        return Result.success(DownloadLifecycleRequeueOutcome.Requeued)
    }

    override suspend fun reassociate(work: DownloadExecutionWork): Result<Unit> = Result.success(Unit)

    override suspend fun applyRecoveryAction(action: DownloadActiveRecoveryAction): Result<Unit> = Result.success(Unit)

    override suspend fun cancelRequested(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> = Result.success(Unit)
}
