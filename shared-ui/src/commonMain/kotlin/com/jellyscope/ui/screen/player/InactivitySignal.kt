// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

internal class InactivitySignal {
    private val events = Channel<Unit>(capacity = Channel.CONFLATED)

    fun signal() {
        events.trySend(Unit)
    }

    suspend fun awaitInactivity(timeoutMillis: Long) {
        events.receive()
        while (withTimeoutOrNull(timeoutMillis) { events.receive() } != null) {
            // Conflation restarts the timeout without per-move coroutines.
        }
    }
}
