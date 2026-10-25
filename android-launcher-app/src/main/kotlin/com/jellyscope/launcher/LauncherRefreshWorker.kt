// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

class LauncherRefreshWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        try {
            Log.i("Launcher", "stage=background-refresh event=started")
            val koin = GlobalContext.get()
            val sessions = koin.get<SessionRepository>()
            val restored =
                withTimeoutOrNull(10_000L) {
                    sessions.sessionState.first { it != SessionState.Restoring }
                } ?: run {
                    Log.i("Launcher", "stage=background-refresh event=retry reason=restore-timeout")
                    return Result.retry()
                }
            val loggedIn = restored as? SessionState.LoggedIn
            if (loggedIn == null) {
                Log.i("Launcher", "stage=background-refresh event=completed outcome=NoSession")
                return Result.success()
            }
            val expected = LauncherBoundary(loggedIn.session.accountIdentity(), loggedIn.boundaryEpoch)
            val outcome = koin.get<LauncherMediaRepository>().refreshAndAwait(expected)
            Log.i("Launcher", "stage=background-refresh event=completed outcome=${outcome.name}")
            return when (outcome) {
                LauncherRefreshOutcome.Success,
                LauncherRefreshOutcome.NoSession,
                LauncherRefreshOutcome.BoundaryChanged,
                LauncherRefreshOutcome.Invalid,
                -> Result.success()
                LauncherRefreshOutcome.Retry -> Result.retry()
            }
        } catch (cancelled: CancellationException) {
            Log.i("Launcher", "stage=background-refresh event=cancelled")
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("background-refresh", failure)
            return Result.retry()
        }
    }
}
