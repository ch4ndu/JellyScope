// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small account-qualified cache kernel for successful refetchable results.
 * Failed results are deliberately never retained, and an optional registry
 * rejects stale account leases before a load or commit can cross a boundary.
 */
internal class AccountScopedSuccessCache(
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry? = null,
) : RefetchableServerCache,
    AccountScopedClearableStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<CacheKey, Result<*>>()

    suspend fun <T> getOrLoad(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
        key: String,
        load: suspend () -> Result<T>,
    ): Result<T> {
        val registry = serverScopedStoreRegistry
        if (registry == null) {
            return getOrLoadWithoutBoundaryGuard(accountIdentity, key, load)
        }
        val lease =
            registry.acquireWorkLease(accountIdentity, boundaryEpoch)
                ?: return Result.failure(JellyfinApiException.Unauthorized)
        val cacheKey = CacheKey(accountIdentity, key)
        cached<T>(cacheKey)?.let { cached -> return cached }
        val result = load()
        if (result.isSuccess) {
            registry.withGuardedLease(lease) {
                mutex.withLock { entries[cacheKey] = result }
            }
        }
        return result
    }

    private suspend fun <T> getOrLoadWithoutBoundaryGuard(
        accountIdentity: AccountIdentity,
        key: String,
        load: suspend () -> Result<T>,
    ): Result<T> {
        val cacheKey = CacheKey(accountIdentity, key)
        cached<T>(cacheKey)?.let { cached -> return cached }
        val result = load()
        if (result.isSuccess) {
            mutex.withLock { entries[cacheKey] = result }
        }
        return result
    }

    private suspend fun <T> cached(cacheKey: CacheKey): Result<T>? =
        mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            entries[cacheKey] as? Result<T>
        }

    override suspend fun clearServerScoped() {
        mutex.withLock { entries.clear() }
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        mutex.withLock {
            entries.keys.removeAll { cacheKey -> cacheKey.accountIdentity == accountIdentity }
        }
    }

    override suspend fun clearServerScoped(serverId: String) {
        mutex.withLock {
            entries.keys.removeAll { cacheKey -> cacheKey.accountIdentity.serverId == serverId }
        }
    }

    private data class CacheKey(
        val accountIdentity: AccountIdentity,
        val logicalKey: String,
    )
}
