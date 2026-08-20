// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

internal fun appendCleanupFailures(
    failures: MutableList<Throwable>,
    throwable: Throwable,
) {
    when (throwable) {
        is StoreCleanupException -> failures += throwable.failures
        is LogoutCleanupCancellationException -> failures += throwable.failures
        else -> failures += throwable
    }
}

internal fun throwIfCleanupFailed(failures: List<Throwable>) {
    if (failures.isNotEmpty()) {
        throw StoreCleanupException(failures.toList())
    }
}

class AccountWorkLease internal constructor(
    internal val accountIdentity: AccountIdentity,
    internal val boundaryEpoch: Long,
    internal val generation: Long,
)

/**
 * Lease validation available only to code already running under the boundary
 * mutation gate. It deliberately exposes no way to reacquire that non-reentrant
 * gate.
 */
interface GateHeldBoundaryCommit {
    fun isCurrentLease(lease: AccountWorkLease): Boolean
}

/**
 * Operations that are valid only while the registry mutation gate is held.
 * The session transition coordinator uses this narrow surface to publish
 * state and invalidate runtime stores as one serialized boundary operation.
 */
interface BoundaryMutation : GateHeldBoundaryCommit {
    fun installRestoredAccount(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    )

    fun installLoggedOut()

    suspend fun transitionToAccount(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    )

    fun invalidateBoundary()

    suspend fun clearAll()

    suspend fun clearServer(serverId: String)

    suspend fun clearAccount(accountIdentity: AccountIdentity)
}

class ServerScopedStoreRegistry {
    private val stores = mutableListOf<ClearableStore>()
    private val mutationGate = Mutex()
    private var boundaryGeneration = 0L
    private var activeBoundary: ActiveBoundary? = null

    fun register(store: ClearableStore) {
        stores += store
    }

    /**
     * Installs a new account boundary and invalidates refetchable runtime
     * state while holding the same gate used by guarded cache commits.
     */
    suspend fun transitionToAccount(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long = 0L,
    ): Long =
        withBoundaryMutation {
            transitionToAccount(accountIdentity, boundaryEpoch)
            boundaryGeneration
        }

    suspend fun <T> withBoundaryMutation(block: suspend BoundaryMutation.() -> T): T =
        mutationGate.withLock {
            LockedBoundaryMutation().block()
        }

    /**
     * Acquires an opaque proof that this account is still the active boundary.
     * A boundary is installed by cold restoration or a serialized transition;
     * a request can only lease work after that installation succeeds.
     */
    suspend fun acquireWorkLease(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long = 0L,
    ): AccountWorkLease? =
        mutationGate.withLock {
            val active = activeBoundary ?: return@withLock null
            if (active.accountIdentity != accountIdentity || active.boundaryEpoch != boundaryEpoch) {
                return@withLock null
            }

            AccountWorkLease(
                accountIdentity = accountIdentity,
                boundaryEpoch = boundaryEpoch,
                generation = boundaryGeneration,
            )
        }

    /**
     * Runs a cache/file/provider mutation only while the lease remains valid.
     * Callers must acquire this gate before taking a cache-specific mutex.
     */
    suspend fun <T> withGuardedLease(
        lease: AccountWorkLease,
        block: suspend () -> T,
    ): T? =
        mutationGate.withLock {
            if (!isCurrentLeaseLocked(lease)) {
                null
            } else {
                block()
            }
        }

    suspend fun clearAll() {
        withBoundaryMutation {
            clearAll()
        }
    }

    suspend fun clearServer(serverId: String) {
        withBoundaryMutation {
            clearServer(serverId)
        }
    }

    suspend fun clearAccount(accountIdentity: AccountIdentity) {
        withBoundaryMutation {
            clearAccount(accountIdentity)
        }
    }

    suspend fun clearRefetchableCaches() {
        withBoundaryMutation {
            invalidateBoundary()
            clearRefetchableCachesLocked()
        }
    }

    private fun invalidateBoundaryLocked() {
        boundaryGeneration += 1
        activeBoundary = null
    }

    private suspend fun clearRefetchableCachesLocked() {
        val failures = mutableListOf<Throwable>()
        stores
            .filterIsInstance<RefetchableServerCache>()
            .forEach { store ->
                try {
                    store.clearServerScoped()
                } catch (throwable: Throwable) {
                    failures += throwable
                }
            }
        if (failures.any { throwable -> throwable is CancellationException }) {
            throw LogoutCleanupCancellationException(failures)
        }
        throwIfCleanupFailed(failures)
    }

    private inner class LockedBoundaryMutation : BoundaryMutation {
        override fun isCurrentLease(lease: AccountWorkLease): Boolean = isCurrentLeaseLocked(lease)

        override fun installRestoredAccount(
            accountIdentity: AccountIdentity,
            boundaryEpoch: Long,
        ) {
            boundaryGeneration += 1
            activeBoundary = ActiveBoundary(accountIdentity, boundaryEpoch)
        }

        override fun installLoggedOut() {
            invalidateBoundaryLocked()
        }

        override suspend fun transitionToAccount(
            accountIdentity: AccountIdentity,
            boundaryEpoch: Long,
        ) {
            boundaryGeneration += 1
            activeBoundary = ActiveBoundary(accountIdentity, boundaryEpoch)
            clearRefetchableCachesLocked()
        }

        override fun invalidateBoundary() {
            invalidateBoundaryLocked()
        }

        override suspend fun clearAll() {
            invalidateBoundaryLocked()
            val failures = mutableListOf<Throwable>()
            stores.forEach { store ->
                try {
                    store.clearServerScoped()
                } catch (throwable: Throwable) {
                    failures += throwable
                }
            }
            throwIfCleanupFailed(failures)
        }

        override suspend fun clearServer(serverId: String) {
            boundaryGeneration += 1
            if (activeBoundary?.accountIdentity?.serverId == serverId) {
                activeBoundary = null
            }
            val failures = mutableListOf<Throwable>()
            stores
                .filterIsInstance<ServerScopedClearableStore>()
                .forEach { store ->
                    try {
                        store.clearServerScoped(serverId)
                    } catch (throwable: Throwable) {
                        failures += throwable
                    }
                }
            throwIfCleanupFailed(failures)
        }

        override suspend fun clearAccount(accountIdentity: AccountIdentity) {
            boundaryGeneration += 1
            if (activeBoundary?.accountIdentity == accountIdentity) {
                activeBoundary = null
            }
            val failures = mutableListOf<Throwable>()
            stores
                .filterIsInstance<AccountScopedClearableStore>()
                .forEach { store ->
                    try {
                        store.clearAccount(accountIdentity)
                    } catch (throwable: Throwable) {
                        failures += throwable
                    }
                }
            throwIfCleanupFailed(failures)
        }
    }

    private fun isCurrentLeaseLocked(lease: AccountWorkLease): Boolean {
        val active = activeBoundary ?: return false
        return active.accountIdentity == lease.accountIdentity &&
            active.boundaryEpoch == lease.boundaryEpoch &&
            boundaryGeneration == lease.generation
    }

    private data class ActiveBoundary(
        val accountIdentity: AccountIdentity,
        val boundaryEpoch: Long,
    )
}
