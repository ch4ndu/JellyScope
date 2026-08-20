// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Handle for an active state subscription. Swift owners call [close] when the
 * observing view model deinitializes; closing is idempotent.
 */
fun interface WatchHandle {
    fun close()
}

/**
 * Bridges a [StateFlow] to a Swift callback: the current value is delivered
 * immediately, then every change, on the presenter's (main) dispatcher. This
 * is the entire Kotlin-to-Swift observation mechanism for the tvOS shell.
 */
internal fun <T> StateFlow<T>.watchIn(
    scope: CoroutineScope,
    onChange: (T) -> Unit,
): WatchHandle {
    val job =
        scope.launch {
            collect { value -> onChange(value) }
        }
    return WatchHandle { job.cancel() }
}
