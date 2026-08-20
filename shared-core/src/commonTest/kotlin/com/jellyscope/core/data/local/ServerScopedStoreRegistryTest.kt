// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServerScopedStoreRegistryTest {
    @Test
    fun clearAllClearsEveryRegisteredStore() =
        runTest {
            val first = FakeClearableStore()
            val second = FakeClearableStore()
            val registry = ServerScopedStoreRegistry()
            registry.register(first)
            registry.register(second)

            registry.clearAll()

            assertTrue(first.wasCleared)
            assertTrue(second.wasCleared)
        }

    @Test
    fun clearAllAttemptsEveryStoreAndAggregatesCancellation() =
        runTest {
            val first = ThrowingClearableStore(IllegalStateException("first failure"))
            val second = FakeClearableStore()
            val third = ThrowingClearableStore(CancellationException("cancelled store"))
            val registry = ServerScopedStoreRegistry()
            registry.register(first)
            registry.register(second)
            registry.register(third)

            val failure =
                kotlin.test.assertFailsWith<StoreCleanupException> {
                    registry.clearAll()
                }

            assertTrue(second.wasCleared)
            assertEquals(2, failure.failures.size)
        }

    @Test
    fun clearServerClearsOnlyServerScopedStores() =
        runTest {
            val global = FakeClearableStore()
            val serverScoped = FakeServerScopedClearableStore()
            val registry = ServerScopedStoreRegistry()
            registry.register(global)
            registry.register(serverScoped)

            registry.clearServer("server-1")

            assertFalse(global.wasCleared)
            assertEquals(listOf("server-1"), serverScoped.clearedServerIds)
        }

    @Test
    fun clearServerContinuesPastFailureAndAggregatesEveryServerStore() =
        runTest {
            val first = FakeServerScopedClearableStore()
            val failing = ThrowingServerScopedClearableStore(IllegalStateException("server failure"))
            val last = FakeServerScopedClearableStore()
            val registry = ServerScopedStoreRegistry()
            registry.register(first)
            registry.register(failing)
            registry.register(last)

            val failure =
                kotlin.test.assertFailsWith<StoreCleanupException> {
                    registry.clearServer("server-1")
                }

            assertEquals(listOf("server-1"), first.clearedServerIds)
            assertEquals(listOf("server-1"), last.clearedServerIds)
            assertEquals(1, failure.failures.size)
        }

    @Test
    fun clearRefetchableCachesClearsOnlyRefetchableCaches() =
        runTest {
            val global = FakeClearableStore()
            val serverScoped = FakeServerScopedClearableStore()
            val refetchable = FakeRefetchableServerCache()
            val registry = ServerScopedStoreRegistry()
            registry.register(global)
            registry.register(serverScoped)
            registry.register(refetchable)

            registry.clearRefetchableCaches()

            assertFalse(global.wasCleared)
            assertFalse(serverScoped.wasClearedAll)
            assertTrue(refetchable.wasCleared)
        }

    @Test
    fun accountClearDispatchesOnlyToAccountScopedStores() =
        runTest {
            val account = AccountIdentity("server-1", "user-1")
            val serverScoped = FakeServerScopedClearableStore()
            val accountScoped = FakeAccountScopedClearableStore()
            val registry = ServerScopedStoreRegistry()
            registry.register(serverScoped)
            registry.register(accountScoped)

            registry.clearAccount(account)

            assertEquals(listOf(account), accountScoped.clearedAccounts)
            assertEquals(emptyList(), serverScoped.clearedServerIds)
            assertFalse(serverScoped.wasClearedAll)
        }

    @Test
    fun accountClearContinuesPastFailureAndAggregatesEveryAccountStore() =
        runTest {
            val account = AccountIdentity("server-1", "user-1")
            val first = FakeAccountScopedClearableStore()
            val failing = ThrowingAccountScopedClearableStore(IllegalStateException("account failure"))
            val last = FakeAccountScopedClearableStore()
            val registry = ServerScopedStoreRegistry()
            registry.register(first)
            registry.register(failing)
            registry.register(last)

            val failure =
                kotlin.test.assertFailsWith<StoreCleanupException> {
                    registry.clearAccount(account)
                }

            assertEquals(listOf(account), first.clearedAccounts)
            assertEquals(listOf(account), last.clearedAccounts)
            assertEquals(1, failure.failures.size)
        }

    @Test
    fun clearRefetchableCachesContinuesPastFailureAndClearsLaterCaches() =
        runTest {
            val first = FakeRefetchableServerCache()
            val failing = ConfigurableRefetchableServerCache(IllegalStateException("cache failure"))
            val last = FakeRefetchableServerCache()
            val registry = ServerScopedStoreRegistry()
            registry.register(first)
            registry.register(failing)
            registry.register(last)

            val failure =
                kotlin.test.assertFailsWith<StoreCleanupException> {
                    registry.clearRefetchableCaches()
                }

            assertTrue(first.wasCleared)
            assertTrue(failing.wasCleared)
            assertTrue(last.wasCleared)
            assertEquals(1, failure.failures.size)
        }

    @Test
    fun leaseAcceptsMatchingBoundaryAndRejectsLateCommit() =
        runTest {
            val account = AccountIdentity("server-1", "user-1")
            val replacement = AccountIdentity("server-2", "user-2")
            val registry = ServerScopedStoreRegistry()
            registry.transitionToAccount(account, boundaryEpoch = 7L)
            val lease = registry.acquireWorkLease(account, boundaryEpoch = 7L)

            assertTrue(lease != null)
            assertEquals("accepted", registry.withGuardedLease(lease) { "accepted" })

            registry.transitionToAccount(replacement, boundaryEpoch = 8L)

            assertNull(registry.acquireWorkLease(account, boundaryEpoch = 7L))
            assertNull(registry.withGuardedLease(lease) { "late" })
        }

    @Test
    fun leaseDoesNotSelfInitializeAnUnrestoredBoundary() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            val account = AccountIdentity("server-1", "user-1")

            assertNull(registry.acquireWorkLease(account, boundaryEpoch = 1L))

            registry.transitionToAccount(account, boundaryEpoch = 1L)

            assertTrue(registry.acquireWorkLease(account, boundaryEpoch = 1L) != null)
        }

    @Test
    fun gateHeldCommitValidatesLeaseWithoutReacquiringMutationGate() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            val account = AccountIdentity("server-1", "user-1")
            registry.transitionToAccount(account, boundaryEpoch = 4L)
            val lease = registry.acquireWorkLease(account, boundaryEpoch = 4L)

            assertTrue(lease != null)
            withTimeout(1.seconds) {
                registry.withBoundaryMutation {
                    assertTrue(isCurrentLease(lease))
                    invalidateBoundary()
                    assertFalse(isCurrentLease(lease))
                }
            }
        }
}

private class FakeClearableStore : ClearableStore {
    var wasCleared = false
        private set

    override suspend fun clearServerScoped() {
        wasCleared = true
    }
}

private class ThrowingClearableStore(
    private val failure: Throwable,
) : ClearableStore {
    override suspend fun clearServerScoped(): Unit = throw failure
}

private class FakeServerScopedClearableStore : ServerScopedClearableStore {
    var wasClearedAll = false
        private set
    val clearedServerIds = mutableListOf<String>()

    override suspend fun clearServerScoped() {
        wasClearedAll = true
    }

    override suspend fun clearServerScoped(serverId: String) {
        clearedServerIds += serverId
    }
}

private class ThrowingServerScopedClearableStore(
    private val failure: Throwable,
) : ServerScopedClearableStore {
    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String): Unit = throw failure
}

private class FakeRefetchableServerCache : RefetchableServerCache {
    var wasCleared = false
        private set

    override suspend fun clearServerScoped() {
        wasCleared = true
    }
}

private class ConfigurableRefetchableServerCache(
    private val failure: Throwable,
) : RefetchableServerCache {
    var wasCleared = false
        private set

    override suspend fun clearServerScoped() {
        wasCleared = true
        throw failure
    }
}

private class FakeAccountScopedClearableStore : AccountScopedClearableStore {
    val clearedAccounts = mutableListOf<AccountIdentity>()

    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        clearedAccounts += accountIdentity
    }
}

private class ThrowingAccountScopedClearableStore(
    private val failure: Throwable,
) : AccountScopedClearableStore {
    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearAccount(accountIdentity: AccountIdentity): Unit = throw failure
}
