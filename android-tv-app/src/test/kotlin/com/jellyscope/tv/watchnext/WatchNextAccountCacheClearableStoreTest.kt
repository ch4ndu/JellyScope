// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.domain.model.AccountIdentity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WatchNextAccountCacheClearableStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val first = AccountIdentity("server-1", "user-1")
    private val sibling = AccountIdentity("server-1", "user-2")

    @Test
    fun accountCleanupRemovesOnlyTheTargetPosterNamespace() =
        kotlinx.coroutines.test.runTest {
            val firstFile = WatchNextContract.posterFile(context, first, "item-1")
            val siblingFile = WatchNextContract.posterFile(context, sibling, "item-1")
            firstFile.parentFile?.mkdirs()
            siblingFile.parentFile?.mkdirs()
            firstFile.writeBytes(byteArrayOf(1))
            siblingFile.writeBytes(byteArrayOf(2))

            try {
                WatchNextAccountCacheClearableStore(context).clearAccount(first)

                assertFalse(firstFile.exists())
                assertTrue(siblingFile.exists())
            } finally {
                WatchNextContract.posterRootDirectory(context).deleteRecursively()
            }
        }

    @Test
    fun accountSwitchThroughTheSessionRegistryClearsWatchNextPosterCache() =
        kotlinx.coroutines.test.runTest {
            val replacement = AccountIdentity("server-2", "user-2")
            val firstFile = WatchNextContract.posterFile(context, first, "item-1")
            val siblingFile = WatchNextContract.posterFile(context, sibling, "item-1")
            firstFile.parentFile?.mkdirs()
            siblingFile.parentFile?.mkdirs()
            firstFile.writeBytes(byteArrayOf(1))
            siblingFile.writeBytes(byteArrayOf(2))

            try {
                val registry = ServerScopedStoreRegistry()
                registry.register(WatchNextAccountCacheClearableStore(context))
                registry.transitionToAccount(first, boundaryEpoch = 1L)
                registry.transitionToAccount(replacement, boundaryEpoch = 2L)

                assertFalse(firstFile.exists())
                assertFalse(siblingFile.exists())
            } finally {
                WatchNextContract.posterRootDirectory(context).deleteRecursively()
            }
        }
}
