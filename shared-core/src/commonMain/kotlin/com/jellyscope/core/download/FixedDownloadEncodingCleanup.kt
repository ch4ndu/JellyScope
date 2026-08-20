// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs the post-admission fixed-download work and always attempts to stop its ephemeral server
 * encoding. Cleanup is deliberately non-cancellable and best effort: it must not replace the
 * transfer result or cancellation that caused the scope to leave.
 */
internal suspend fun <T> withFixedDownloadEncodingCleanup(
    cleanup: suspend () -> Unit,
    block: suspend () -> T,
): T =
    try {
        block()
    } finally {
        withContext(NonCancellable) {
            try {
                cleanup()
            } catch (_: Throwable) {
                // The transfer outcome remains authoritative when server cleanup is unavailable.
            }
        }
    }
