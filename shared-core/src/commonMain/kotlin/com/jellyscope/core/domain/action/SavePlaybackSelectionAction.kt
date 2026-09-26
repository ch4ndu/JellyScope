// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

/** Coalesces rapid changes for each source-qualified selection key. */
class SavePlaybackSelectionAction(
    private val store: PlaybackSelectionStore,
    private val sessionRepository: SessionRepository,
    private val serverScopedStoreRegistry: ServerScopedStoreRegistry,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    private val writer =
        DebouncedKeyedStoreWriter<PlaybackSelectionKey, PendingSelection>(
            scope = scope,
            dispatcher = dispatcher,
            debounceMs = PLAYBACK_SELECTION_WRITE_DEBOUNCE_MS,
            write = { key, pending ->
                val lease =
                    serverScopedStoreRegistry.acquireWorkLease(
                        accountIdentity = pending.accountIdentity,
                        boundaryEpoch = pending.boundaryEpoch,
                    ) ?: throw stalePlaybackWrite()
                serverScopedStoreRegistry.withGuardedLease(lease) {
                    store.save(key, pending.selection)
                } ?: throw stalePlaybackWrite()
            },
        )

    fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ): Deferred<Unit> {
        val pending = capture(key, selection.normalized()) ?: return cancelledPlaybackWrite()
        return writer.save(key, pending)
    }

    /** Writes one source-qualified selection before a caller continues its workflow. */
    suspend operator fun invoke(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) {
        val pending = capture(key, selection.normalized()) ?: throw stalePlaybackWrite()
        val lease =
            serverScopedStoreRegistry.acquireWorkLease(
                accountIdentity = pending.accountIdentity,
                boundaryEpoch = pending.boundaryEpoch,
            ) ?: throw stalePlaybackWrite()
        serverScopedStoreRegistry.withGuardedLease(lease) {
            store.save(key, pending.selection)
        } ?: throw stalePlaybackWrite()
    }

    suspend fun current(key: PlaybackSelectionKey): PlaybackSelection? = store.get(key)

    fun latestWrite(): Deferred<Unit>? = writer.latestWrite()

    fun drainLatest(timeoutMs: Long = DEFAULT_PLAYBACK_WRITE_DRAIN_TIMEOUT_MS): Job = writer.drainLatest(timeoutMs)

    private fun capture(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ): PendingSelection? {
        val loggedIn = sessionRepository.sessionState.value as? SessionState.LoggedIn ?: return null
        val accountIdentity = loggedIn.session.accountIdentity()
        if (key.serverId != accountIdentity.serverId || key.userId != accountIdentity.userId) return null
        return PendingSelection(selection, accountIdentity, loggedIn.boundaryEpoch)
    }

    private data class PendingSelection(
        val selection: PlaybackSelection,
        val accountIdentity: AccountIdentity,
        val boundaryEpoch: Long,
    )
}

private fun cancelledPlaybackWrite(): Deferred<Unit> = CompletableDeferred<Unit>().apply { cancel(stalePlaybackWrite()) }

private fun stalePlaybackWrite(): CancellationException = CancellationException("Playback persistence account boundary changed.")

private const val PLAYBACK_SELECTION_WRITE_DEBOUNCE_MS = 150L
private const val DEFAULT_PLAYBACK_WRITE_DRAIN_TIMEOUT_MS = 1_500L
