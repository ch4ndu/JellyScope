// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

/** Serializes main-thread iOS PiP seek completions. */
class VlcSingleFlightCompletionGate {
    private data class Pending(
        val token: Long,
        val generation: Long,
        val completion: () -> Unit,
    )

    private var nextToken = 0L
    private var pending: Pending? = null

    /** Completes overlapping requests without admitting them. */
    fun admit(
        generation: Long,
        completion: () -> Unit,
    ): Long? {
        if (pending != null) {
            completion()
            return null
        }
        val token = ++nextToken
        pending = Pending(token, generation, completion)
        return token
    }

    fun complete(
        token: Long,
        generation: Long,
        beforeCompletion: () -> Unit = {},
    ): Boolean {
        val current = pending
        if (current?.token != token || current.generation != generation) return false
        pending = null
        beforeCompletion()
        current.completion()
        return true
    }

    fun cancel() {
        val current = pending ?: return
        pending = null
        current.completion()
    }
}
