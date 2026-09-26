// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.PlaybackTimingStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingOffset
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

/** Coalesces rapid timing changes before they reach Room. */
class SavePlaybackTimingOffsetAction(
    private val store: PlaybackTimingStore,
    private val sessionRepository: SessionRepository,
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    private val writer =
        DebouncedKeyedStoreWriter<PlaybackTimingKey, PendingTimingOffset>(
            scope = scope,
            dispatcher = dispatcher,
            debounceMs = PLAYBACK_TIMING_WRITE_DEBOUNCE_MS,
            write = { _, pending ->
                val lease =
                    serverScopedStoreRegistry.acquireWorkLease(
                        accountIdentity = pending.accountIdentity,
                        boundaryEpoch = pending.boundaryEpoch,
                    ) ?: throw staleTimingWrite()
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    store.save(pending.offset)
                } ?: throw staleTimingWrite()
            },
        )

    fun save(offset: PlaybackTimingOffset): Deferred<Unit> {
        val normalized = offset.normalized()
        val pending = capture(normalized) ?: return cancelledTimingWrite()
        return writer.save(normalized.key, pending)
    }

    suspend fun current(key: PlaybackTimingKey): PlaybackTimingOffset? = store.get(key)

    fun latestWrite(): Deferred<Unit>? = writer.latestWrite()

    fun drainLatest(timeoutMs: Long = DEFAULT_PLAYBACK_WRITE_DRAIN_TIMEOUT_MS): Job = writer.drainLatest(timeoutMs)

    private fun capture(offset: PlaybackTimingOffset): PendingTimingOffset? {
        val loggedIn = sessionRepository.sessionState.value as? SessionState.LoggedIn ?: return null
        val accountIdentity = loggedIn.session.accountIdentity()
        val key = offset.key
        if (key.serverId != accountIdentity.serverId || key.userId != accountIdentity.userId) return null
        return PendingTimingOffset(offset, accountIdentity, loggedIn.boundaryEpoch)
    }

    private data class PendingTimingOffset(
        val offset: PlaybackTimingOffset,
        val accountIdentity: AccountIdentity,
        val boundaryEpoch: Long,
    )
}

private fun cancelledTimingWrite(): Deferred<Unit> = CompletableDeferred<Unit>().apply { cancel(staleTimingWrite()) }

private fun staleTimingWrite(): CancellationException = CancellationException("Playback timing account boundary changed.")

private const val PLAYBACK_TIMING_WRITE_DEBOUNCE_MS = 150L
private const val DEFAULT_PLAYBACK_WRITE_DRAIN_TIMEOUT_MS = 1_500L
