// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import kotlin.coroutines.cancellation.CancellationException

/** API-25..33 foreground dataSync fallback. */
class AndroidDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private var progressJob: Job? = null

    override suspend fun getForegroundInfo() =
        schedulerOrNull()?.foregroundInfo(id)
            ?: throw IllegalStateException("Download scheduler is unavailable.")

    override suspend fun doWork(): Result {
        val scheduler = schedulerOrNull() ?: return Result.failure()
        return coroutineScope {
            // Foreground promotion is an execution precondition on API 33
            // and lower. Await the initial notification before the transfer
            // runner can claim/open a writer; the periodic updater below only
            // refreshes progress after that admission boundary.
            setForeground(scheduler.foregroundInfo(id))
            progressJob?.cancel()
            progressJob =
                launch {
                    while (isActive) {
                        setForeground(scheduler.foregroundInfo(id))
                        delay(PROGRESS_REFRESH_MILLIS)
                    }
                }
            try {
                scheduler.executeWorkManager(id).fold(
                    onSuccess = { Result.success() },
                    onFailure = { Result.failure() },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                progressJob?.cancel()
            }
        }
    }

    private fun schedulerOrNull(): AndroidDownloadScheduler? =
        runCatching { GlobalContext.get().get<AndroidDownloadScheduler>() }.getOrNull()

    private companion object {
        const val PROGRESS_REFRESH_MILLIS = 1_000L
    }
}
