// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.repository.LocalSubtitleMutationCoordinator
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SaveSubtitleSelectionAction(
    private val coordinator: LocalSubtitleMutationCoordinator,
    private val scope: CoroutineScope,
) {
    private val latestWrite = atomic<Deferred<Unit>?>(null)

    fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ): Deferred<Unit> {
        val completion = coordinator.submitSelection(key, selection)
        latestWrite.value = completion
        return completion
    }

    fun delete(key: SubtitleSelectionKey): Deferred<Unit> = save(key, SubtitleSelectionIntent.Unspecified)

    suspend fun current(key: SubtitleSelectionKey): SubtitleSelectionIntent? = coordinator.currentSelection(key)

    fun latestWrite(): Deferred<Unit>? = latestWrite.value

    fun drainLatest(timeoutMs: Long = DEFAULT_SUBTITLE_WRITE_DRAIN_TIMEOUT_MS): Job =
        scope.launch {
            val write = latestWrite.value ?: return@launch
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
}

private const val DEFAULT_SUBTITLE_WRITE_DRAIN_TIMEOUT_MS = 1_500L
