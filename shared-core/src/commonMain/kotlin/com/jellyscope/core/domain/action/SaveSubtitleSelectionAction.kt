// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.SubtitleSelectionKey
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SaveSubtitleSelectionAction(
    private val store: SubtitleSelectionStore,
    private val scope: CoroutineScope,
) {
    private val requests =
        Channel<WriteRequest>(
            capacity = Channel.UNLIMITED,
            onUndeliveredElement = { request -> request.completion.cancel() },
        )
    private var latestWrite: Deferred<Unit>? = null

    init {
        scope.launch {
            try {
                for (request in requests) {
                    try {
                        when (request.selection) {
                            SubtitleSelectionIntent.Unspecified -> store.delete(request.key)
                            SubtitleSelectionIntent.Off,
                            is SubtitleSelectionIntent.Track,
                            is SubtitleSelectionIntent.LocalAsset,
                            -> store.save(request.key, request.selection)
                        }
                        request.completion.complete(Unit)
                    } catch (cancellation: CancellationException) {
                        request.completion.cancel(cancellation)
                        throw cancellation
                    } catch (throwable: Throwable) {
                        request.completion.completeExceptionally(throwable)
                    }
                }
            } finally {
                requests.cancel()
            }
        }
    }

    fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ): Deferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        latestWrite = completion
        if (requests.trySend(WriteRequest(key, selection, completion)).isFailure) {
            completion.completeExceptionally(IllegalStateException("Subtitle selection writer is unavailable."))
        }
        return completion
    }

    fun delete(key: SubtitleSelectionKey): Deferred<Unit> = save(key, SubtitleSelectionIntent.Unspecified)

    suspend fun current(key: SubtitleSelectionKey): SubtitleSelectionIntent? = store.get(key)

    fun latestWrite(): Deferred<Unit>? = latestWrite

    fun drainLatest(timeoutMs: Long = DEFAULT_SUBTITLE_WRITE_DRAIN_TIMEOUT_MS): Job =
        scope.launch {
            val write = latestWrite ?: return@launch
            withTimeoutOrNull(timeoutMs) {
                try {
                    write.await()
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Throwable) {
                    // Teardown is best-effort; persistence failures are reported
                    // through the Deferred returned by save/delete.
                }
            }
        }

    private data class WriteRequest(
        val key: SubtitleSelectionKey,
        val selection: SubtitleSelectionIntent,
        val completion: CompletableDeferred<Unit>,
    )
}

private const val DEFAULT_SUBTITLE_WRITE_DRAIN_TIMEOUT_MS = 1_500L
