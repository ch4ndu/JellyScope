// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.CancellationException

interface ClearableStore {
    suspend fun clearServerScoped()
}

interface RefetchableServerCache : ClearableStore

interface ServerScopedClearableStore : ClearableStore {
    suspend fun clearServerScoped(serverId: String)
}

interface AccountScopedClearableStore : ServerScopedClearableStore {
    suspend fun clearAccount(accountIdentity: AccountIdentity)
}

class StoreCleanupException(
    val failures: List<Throwable>,
) : IllegalStateException("Failed to clear ${failures.size} persistent store operation(s).")

class LogoutCleanupCancellationException(
    val failures: List<Throwable>,
) : CancellationException("Logout cleanup completed after cancellation.")

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
fun appendCleanupFailures(
    failures: MutableList<Throwable>,
    throwable: Throwable,
) {
    when (throwable) {
        is StoreCleanupException -> failures += throwable.failures
        is LogoutCleanupCancellationException -> failures += throwable.failures
        else -> failures += throwable
    }
}

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
fun throwIfCleanupFailed(failures: List<Throwable>) {
    if (failures.isNotEmpty()) {
        throw StoreCleanupException(failures.toList())
    }
}
