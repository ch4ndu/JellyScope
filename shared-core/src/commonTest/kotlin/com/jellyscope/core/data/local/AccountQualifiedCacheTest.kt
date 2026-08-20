// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AccountQualifiedCacheTest {
    @Test
    fun failedResultsAreNotCachedAndRetryLoadsAgain() =
        runTest {
            val cache = DiscoveryCache()
            val account = AccountIdentity("server-1", "user-1")
            var loads = 0

            assertFalse(
                cache
                    .getOrLoad<String>(account, 1L, "upcoming") {
                        loads += 1
                        Result.failure(IllegalStateException("first"))
                    }.isSuccess,
            )
            assertEquals(
                "fresh",
                cache
                    .getOrLoad<String>(account, 1L, "upcoming") {
                        loads += 1
                        Result.success("fresh")
                    }.getOrThrow(),
            )
            assertEquals(2, loads)
        }

    @Test
    fun sameLogicalKeyIsIsolatedAcrossAccounts() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            val cache = DiscoveryCache(registry)
            val first = AccountIdentity("server-1", "user-1")
            val second = AccountIdentity("server-1", "user-2")
            registry.transitionToAccount(first, boundaryEpoch = 1L)
            assertEquals("first", cache.getOrLoad(first, 1L, "upcoming") { Result.success("first") }.getOrThrow())
            registry.transitionToAccount(second, boundaryEpoch = 2L)

            assertEquals("second", cache.getOrLoad(second, 2L, "upcoming") { Result.success("second") }.getOrThrow())
        }

    @Test
    fun oldLoadCannotRepopulateAfterBoundaryTransition() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            val cache = DiscoveryCache(registry)
            val first = AccountIdentity("server-1", "user-1")
            val second = AccountIdentity("server-2", "user-2")
            registry.transitionToAccount(first, boundaryEpoch = 1L)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldLoad =
                async {
                    cache.getOrLoad(first, 1L, "upcoming") {
                        started.complete(Unit)
                        release.await()
                        Result.success("old")
                    }
                }
            started.await()
            registry.transitionToAccount(second, boundaryEpoch = 2L)
            assertEquals("new", cache.getOrLoad(second, 2L, "upcoming") { Result.success("new") }.getOrThrow())
            release.complete(Unit)
            assertEquals("old", oldLoad.await().getOrThrow())
            registry.transitionToAccount(first, boundaryEpoch = 3L)

            var reloads = 0
            val current =
                cache
                    .getOrLoad(first, 3L, "upcoming") {
                        reloads += 1
                        Result.success("fresh")
                    }.getOrThrow()
            assertEquals("fresh", current)
            assertEquals(1, reloads)
        }

    @Test
    fun retiredSnapshotCannotStartANewCachedLoad() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            val cache = DiscoveryCache(registry)
            val first = AccountIdentity("server-1", "user-1")
            val second = AccountIdentity("server-2", "user-2")
            registry.transitionToAccount(first, boundaryEpoch = 1L)
            registry.transitionToAccount(second, boundaryEpoch = 2L)
            var loaded = false

            val result =
                cache.getOrLoad(first, 1L, "upcoming") {
                    loaded = true
                    Result.success("retired")
                }

            assertFalse(loaded)
            assertFalse(result.isSuccess)
        }
}
