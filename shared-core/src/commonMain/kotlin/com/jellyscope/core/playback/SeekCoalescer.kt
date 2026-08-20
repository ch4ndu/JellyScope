// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Commits only the newest seek after [windowMs], avoiding repeated native decoder
 * flushes during a burst.
 */
class SeekCoalescer(
    private val scope: CoroutineScope,
    private val windowMs: Long = SEEK_COALESCE_WINDOW_MS,
    private val commit: (Long) -> Unit,
) {
    private var job: Job? = null

    var pendingTargetMs: Long? = null
        private set

    fun request(targetMs: Long) {
        pendingTargetMs = targetMs
        job?.cancel()
        job =
            scope.launch {
                delay(windowMs)
                val target = pendingTargetMs ?: return@launch
                pendingTargetMs = null
                job = null
                commit(target)
            }
    }

    /** Commits before an action, such as pause, that must observe the pending seek. */
    fun flush() {
        val target = pendingTargetMs ?: return
        job?.cancel()
        job = null
        pendingTargetMs = null
        commit(target)
    }

    fun cancel() {
        job?.cancel()
        job = null
        pendingTargetMs = null
    }
}

// Absorbs repeated taps without making a single seek feel delayed.
const val SEEK_COALESCE_WINDOW_MS = 250L
