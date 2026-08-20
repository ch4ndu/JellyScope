// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.playback.PlayerBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull

class PlaybackDiagnosticsContextTest {
    @Test
    fun accountSwitchClearsTheFailureContextThroughRefetchableCachePath() =
        runTest {
            val context = populatedContext()
            val registry = ServerScopedStoreRegistry()
            registry.register(context)

            registry.transitionToAccount(AccountIdentity("server-2", "user-2"))

            assertNull(context.snapshot())
        }

    @Test
    fun accountRemovalClearsTheFailureContextThroughAccountScopedPath() =
        runTest {
            val account = AccountIdentity("server-1", "user-1")
            val context = populatedContext()
            val registry = ServerScopedStoreRegistry()
            registry.register(context)

            registry.clearAccount(account)

            assertNull(context.snapshot())
        }
}

private fun populatedContext(): PlaybackDiagnosticsContext =
    PlaybackDiagnosticsContext().apply {
        recordFailure(
            backend = PlayerBackend.ExoPlayer,
            capabilities = null,
            source = null,
        )
    }
