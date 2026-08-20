// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.usecase.ObserveSessionStateUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

class WatchNextSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        if (!WatchNextSupport.isSupported(applicationContext)) {
            return Result.failure()
        }

        val koin =
            runCatching { GlobalContext.get() }
                .getOrElse { return Result.retry() }
        val loggedIn =
            awaitLoggedInSession(observeSessionStateUseCase = koin.get())
                ?: return Result.retry()
        val registry = koin.get<ServerScopedStoreRegistry>()
        val lease = registry.acquireWorkLease(loggedIn.session.accountIdentity(), loggedIn.boundaryEpoch) ?: return Result.retry()

        return runCatchingCancellable {
            WatchNextProviderSync(
                context = applicationContext,
                getContinueWatchingUseCase = koin.get(),
                getNextUpUseCase = koin.get(),
                syncStore = koin.get(),
                imageUrlBuilder = koin.get(),
                httpClient = koin.get(),
                authHeaderProvider = koin.get(),
                serverScopedStoreRegistry = registry,
            ).sync(loggedIn.session, lease)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { throwable ->
                logWatchNextSyncFailure(throwable)
                Result.retry()
            },
        )
    }

    private suspend fun awaitLoggedInSession(observeSessionStateUseCase: ObserveSessionStateUseCase): SessionState.LoggedIn? {
        val stateFlow = observeSessionStateUseCase()
        val restoredState =
            withTimeoutOrNull(SESSION_RESTORE_TIMEOUT) {
                stateFlow.first { state -> state !is SessionState.Restoring }
            } ?: stateFlow.value
        return restoredState as? SessionState.LoggedIn
    }
}

internal fun logWatchNextSyncFailure(throwable: Throwable) {
    watchNextLogger.w {
        formatSafeFailureDiagnostic(
            stage = "sync",
            event = "failed",
            operation = DiagnosticOperation.WatchNextSync,
            throwable = throwable,
        )
    }
}
