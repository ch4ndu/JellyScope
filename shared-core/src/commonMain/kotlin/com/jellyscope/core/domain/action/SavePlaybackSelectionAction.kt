// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

/** Coalesces rapid changes for each source-qualified selection key. */
class SavePlaybackSelectionAction(
    private val store: PlaybackSelectionStore,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    private val writer =
        DebouncedKeyedStoreWriter<PlaybackSelectionKey, PlaybackSelection>(
            scope = scope,
            dispatcher = dispatcher,
            debounceMs = PLAYBACK_SELECTION_WRITE_DEBOUNCE_MS,
            write = { key, selection -> store.save(key, selection) },
        )

    fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ): Deferred<Unit> = writer.save(key, selection.normalized())

    /** Writes one source-qualified selection before a caller continues its workflow. */
    suspend operator fun invoke(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) {
        store.save(key, selection.normalized())
    }

    suspend fun current(key: PlaybackSelectionKey): PlaybackSelection? = store.get(key)

    fun latestWrite(): Deferred<Unit>? = writer.latestWrite()

    fun drainLatest(timeoutMs: Long = DEFAULT_PLAYBACK_WRITE_DRAIN_TIMEOUT_MS): Job = writer.drainLatest(timeoutMs)
}

private const val PLAYBACK_SELECTION_WRITE_DEBOUNCE_MS = 150L
private const val DEFAULT_PLAYBACK_WRITE_DRAIN_TIMEOUT_MS = 1_500L
