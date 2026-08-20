// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.PlaybackTimingStore
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

/** Coalesces rapid timing changes before they reach Room. */
class SavePlaybackTimingOffsetAction(
    private val store: PlaybackTimingStore,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    private val writer =
        DebouncedKeyedStoreWriter<PlaybackTimingKey, PlaybackTimingOffset>(
            scope = scope,
            dispatcher = dispatcher,
            debounceMs = PLAYBACK_TIMING_WRITE_DEBOUNCE_MS,
            write = { _, offset -> store.save(offset) },
        )

    fun save(offset: PlaybackTimingOffset): Deferred<Unit> {
        val normalized = offset.normalized()
        return writer.save(normalized.key, normalized)
    }

    suspend fun current(key: PlaybackTimingKey): PlaybackTimingOffset? = store.get(key)

    fun latestWrite(): Deferred<Unit>? = writer.latestWrite()

    fun drainLatest(timeoutMs: Long = DEFAULT_PLAYBACK_WRITE_DRAIN_TIMEOUT_MS): Job = writer.drainLatest(timeoutMs)
}

private const val PLAYBACK_TIMING_WRITE_DEBOUNCE_MS = 150L
private const val DEFAULT_PLAYBACK_WRITE_DRAIN_TIMEOUT_MS = 1_500L
