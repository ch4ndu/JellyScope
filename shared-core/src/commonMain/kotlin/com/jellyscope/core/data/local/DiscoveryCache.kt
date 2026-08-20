// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity

/**
 * In-memory cache for the Discover tab's top-level lists. Only successful
 * results are cached; the account-qualified kernel rejects stale leases and
 * is cleared by the existing server-scoped store lifecycle.
 */
class DiscoveryCache(
    serverScopedStoreRegistry: ServerScopedStoreRegistry? = null,
) : RefetchableServerCache,
    AccountScopedClearableStore {
    private val cache = AccountScopedSuccessCache(serverScopedStoreRegistry)

    suspend fun <T> getOrLoad(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
        key: String,
        load: suspend () -> Result<T>,
    ): Result<T> = cache.getOrLoad(accountIdentity, boundaryEpoch, key, load)

    override suspend fun clearServerScoped() = cache.clearServerScoped()

    override suspend fun clearAccount(accountIdentity: AccountIdentity) = cache.clearAccount(accountIdentity)

    override suspend fun clearServerScoped(serverId: String) = cache.clearServerScoped(serverId)
}
