// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the one app-active wake job used by the iOS/JVM lifecycle hosts.
 *
 * The mutex protects only installation and removal of the [Deferred]. The
 * transfer itself runs outside the mutex so resign/stop can obtain the job,
 * cancel it, join it, and then checkpoint the durable attempt promptly.
 */
internal class DownloadWakeJobGate(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger = diagnosticLogger(DiagnosticTag.DownloadExecution)
    private val mutex = Mutex()
    private var wakeJob: Deferred<Result<Unit>>? = null

    /**
     * Installs and starts one retained wake job, returning once the job is
     * safely owned by this gate.  Callers use this for foreground actions:
     * admission is acknowledged when the recovery/wake coroutine is launched,
     * not after it drains the whole download.
     */
    suspend fun launch(block: suspend () -> Result<Unit>): Result<Unit> {
        if (!scope.isActive) {
            logger.w { "stage=download-wake event=rejected result=InactiveScope" }
            return Result.failure(IllegalStateException("The download wake scope is no longer active."))
        }
        return try {
            mutex.withLock {
                if (wakeJob?.isActive == true) {
                    return@withLock Result.success(Unit)
                }
                val created =
                    scope.async(dispatcher, start = CoroutineStart.LAZY) {
                        logger.i { "stage=download-wake event=started" }
                        try {
                            block().also { result ->
                                result.fold(
                                    onSuccess = { logger.i { "stage=download-wake event=completed" } },
                                    onFailure = { failure ->
                                        logger.w { "stage=download-wake event=failed exceptionType=${failure.playbackExceptionType()}" }
                                    },
                                )
                            }
                        } catch (cancellation: CancellationException) {
                            logger.i { "stage=download-wake event=cancelled" }
                            throw cancellation
                        } catch (throwable: Throwable) {
                            logger.w { "stage=download-wake event=failed exceptionType=${throwable.playbackExceptionType()}" }
                            Result.failure(throwable)
                        }
                    }
                wakeJob = created
                created.start()
                Result.success(Unit)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.w { "stage=download-wake event=failed exceptionType=${throwable.playbackExceptionType()}" }
            Result.failure(throwable)
        }
    }

    suspend fun run(block: suspend () -> Result<Unit>): Result<Unit> {
        val job =
            mutex.withLock {
                wakeJob?.takeIf { candidate -> candidate.isActive }
                    ?: scope.async(dispatcher) { block() }.also { created -> wakeJob = created }
            }
        return try {
            job.await()
        } finally {
            mutex.withLock {
                if (wakeJob === job) {
                    wakeJob = null
                }
            }
        }
    }

    suspend fun cancelAndJoin(cancellation: CancellationException = CancellationException("Download wake cancellation requested.")) {
        val job =
            mutex.withLock {
                val current = wakeJob
                wakeJob = null
                current
            }
        job?.cancel(cancellation)
        job?.join()
    }
}
