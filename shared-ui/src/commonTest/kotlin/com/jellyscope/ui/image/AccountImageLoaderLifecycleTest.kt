// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.image

import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccountImageLoaderLifecycleTest {
    @Test
    fun accountSwitchRetiresOldLoaderBeforeInstallingReplacement() =
        runTest {
            val events = mutableListOf<String>()
            val lifecycle =
                AccountImageLoaderLifecycle(
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    cleanupScope = this,
                    installSingleton = { loader: FakeImageLoader -> events += "install:${loader.name}" },
                    clear = { loader -> events += "clear:${loader.name}" },
                    shutdown = { loader -> events += "shutdown:${loader.name}" },
                )
            val first = FakeImageLoader("A")
            val second = FakeImageLoader("B")

            lifecycle.activate(AccountIdentity("server-1", "user-1"), 1L, first)
            lifecycle.retireActive()
            lifecycle.activate(AccountIdentity("server-2", "user-2"), 2L, second)

            assertEquals(
                listOf("install:A", "clear:A", "shutdown:A", "install:B"),
                events,
            )
        }

    @Test
    fun sameAccountReinstallationReusesTheActiveLoader() =
        runTest {
            val events = mutableListOf<String>()
            val lifecycle = lifecycle(events)
            val accountIdentity = AccountIdentity("server-1", "user-1")
            val first = FakeImageLoader("A")

            lifecycle.activate(accountIdentity, 1L, first)
            val reinstalled = lifecycle.activeLoader(accountIdentity, 1L) ?: FakeImageLoader("replacement")
            lifecycle.activate(accountIdentity, 1L, reinstalled)

            assertSame(first, reinstalled)
            assertEquals(listOf("install:A", "install:A"), events)
        }

    @Test
    fun retiredBoundaryRejectsStaleReinstallationBeforeReplacement() =
        runTest {
            val events = mutableListOf<String>()
            val lifecycle = lifecycle(events)
            val firstAccount = AccountIdentity("server-1", "user-1")
            val replacementAccount = AccountIdentity("server-2", "user-2")
            val first = FakeImageLoader("A")
            val stale = FakeImageLoader("stale-A")
            val replacement = FakeImageLoader("B")

            lifecycle.activate(firstAccount, 1L, first)
            lifecycle.retireActive()
            assertTrue(lifecycle.isRetired(firstAccount, 1L))
            val staleInstalled = lifecycle.activate(firstAccount, 1L, stale)
            val replacementInstalled = lifecycle.activate(replacementAccount, 2L, replacement)
            advanceUntilIdle()

            assertFalse(staleInstalled)
            assertTrue(replacementInstalled)
            assertEquals(
                listOf("install:A", "clear:A", "shutdown:A", "install:B", "shutdown:stale-A"),
                events,
            )
        }

    @Test
    fun retiringAnInactiveAccountLeavesTheCurrentLoaderActive() =
        runTest {
            val events = mutableListOf<String>()
            val lifecycle = lifecycle(events)
            val activeAccount = AccountIdentity("server-1", "user-1")
            val activeLoader = FakeImageLoader("A")
            lifecycle.activate(activeAccount, 1L, activeLoader)

            lifecycle.retireActiveIf { account -> account == AccountIdentity("server-2", "user-2") }

            assertSame(activeLoader, lifecycle.activeLoader(activeAccount, 1L))
            assertEquals(listOf("install:A"), events)
        }

    @Test
    fun retiringTheActiveAccountAllowsTheNextActivation() =
        runTest {
            val events = mutableListOf<String>()
            val lifecycle = lifecycle(events)
            val activeAccount = AccountIdentity("server-1", "user-1")
            val replacementAccount = AccountIdentity("server-2", "user-2")
            lifecycle.activate(activeAccount, 1L, FakeImageLoader("A"))

            lifecycle.retireActiveIf { account -> account == activeAccount }
            lifecycle.activate(replacementAccount, 2L, FakeImageLoader("B"))

            assertEquals(
                listOf("install:A", "clear:A", "shutdown:A", "install:B"),
                events,
            )
        }

    private fun TestScope.lifecycle(events: MutableList<String>) =
        AccountImageLoaderLifecycle(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            cleanupScope = this,
            installSingleton = { loader: FakeImageLoader -> events += "install:${loader.name}" },
            clear = { loader -> events += "clear:${loader.name}" },
            shutdown = { loader -> events += "shutdown:${loader.name}" },
        )
}

private class FakeImageLoader(
    val name: String,
)
