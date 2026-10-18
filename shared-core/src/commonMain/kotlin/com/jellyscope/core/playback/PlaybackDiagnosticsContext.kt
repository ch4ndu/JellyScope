// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.data.local.RefetchableServerCache
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.PlayerBackend
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

class PlaybackDiagnosticsContext :
    RefetchableServerCache,
    AccountScopedClearableStore {
    private val lock = ReentrantLock()
    private var latestFailure: PlaybackDiagnosticsSnapshot? = null

    fun recordFailure(
        backend: PlayerBackend,
        capabilities: DeviceDecodingCapabilities?,
        source: DiagnosticsSourceDescriptor?,
    ) {
        lock.withLock {
            latestFailure = PlaybackDiagnosticsSnapshot(backend, capabilities, source)
        }
    }

    fun snapshot(): PlaybackDiagnosticsSnapshot? = lock.withLock { latestFailure }

    override suspend fun clearServerScoped() {
        clear()
    }

    override suspend fun clearServerScoped(serverId: String) {
        clear()
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        clear()
    }

    private fun clear() {
        lock.withLock {
            latestFailure = null
        }
    }
}
