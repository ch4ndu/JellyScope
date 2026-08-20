// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The durable command owns the row and attempt generation. Native cancellation
 * follows it even when the durable command is rejected, so a scheduler item
 * cannot outlive an app-owned cancel request.
 */
internal suspend fun cancelDurableThenNative(
    cancelRequested: suspend () -> Result<Unit>,
    cancelNative: suspend () -> Result<Unit>,
): Result<Unit> {
    val durableResult =
        try {
            cancelRequested()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    val nativeResult =
        try {
            cancelNative()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    return if (durableResult.isFailure) durableResult else nativeResult
}

/**
 * App-owned notification action receiver.
 *
 * A UIDT [android.app.job.JobService] is protected by BIND_JOB_SERVICE and is
 * not an app command service.  Notification cancellation therefore arrives
 * here, where the common runner first quiesces and deletes the exact durable
 * identity, followed by native cancellation.  The durable command must win
 * the race: cancelling native work first can checkpoint the row to Paused and
 * clear its platform identity before the delete command gets to it.
 */
class AndroidDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != AndroidDownloadScheduler.ACTION_CANCEL) return
        val pendingResult = goAsync()
        val scheduler = runCatching { GlobalContext.get().get<AndroidDownloadScheduler>() }.getOrNull()
        val identity = AndroidDownloadScheduler.identityFrom(intent)
        if (scheduler == null || identity == null) {
            pendingResult.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                cancelDurableThenNative(
                    cancelRequested = { scheduler.cancelRequested(identity) },
                    cancelNative = { scheduler.cancel(identity) },
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
