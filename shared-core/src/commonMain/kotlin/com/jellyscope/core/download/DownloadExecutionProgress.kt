// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

/** Terminal state for in-memory native execution progress; it never changes durable download state. */
internal enum class DownloadExecutionProgressTerminal {
    Completed,
    Paused,
    QuotaBlocked,
    Failed,
    BoundaryInvalidated,
}

/** Identity-free numeric progress fact emitted only after a successful writer mutation. */
internal data class DownloadExecutionProgressUpdate(
    val attempt: DownloadAttemptIdentity,
    val transferredBytes: Long,
    val expectedBytes: Long?,
    val terminal: DownloadExecutionProgressTerminal? = null,
)

/** Platform observer for bounded in-memory execution progress. */
internal fun interface DownloadExecutionProgressSink {
    fun report(update: DownloadExecutionProgressUpdate)
}

/**
 * Coalesces truthful writer facts for native task UI without changing checkpoint
 * cadence. Attempts are exact-generation qualified so an obsolete callback can
 * never clear or overwrite later work.
 */
internal class DownloadExecutionProgress(
    private val sink: DownloadExecutionProgressSink? = null,
) {
    private val lock = ReentrantLock()
    private var activeAttempt: DownloadAttemptIdentity? = null
    private var lastPublishedBytes = 0L

    fun started(
        attempt: DownloadAttemptIdentity,
        transferredBytes: Long,
        expectedBytes: Long?,
    ) = publish(
        attempt = attempt,
        transferredBytes = transferredBytes,
        expectedBytes = expectedBytes,
        terminal = null,
        force = true,
    )

    fun wrote(
        attempt: DownloadAttemptIdentity,
        transferredBytes: Long,
        expectedBytes: Long?,
    ) = publish(
        attempt = attempt,
        transferredBytes = transferredBytes,
        expectedBytes = expectedBytes,
        terminal = null,
        force = false,
    )

    fun finished(
        attempt: DownloadAttemptIdentity,
        transferredBytes: Long,
        expectedBytes: Long?,
        terminal: DownloadExecutionProgressTerminal,
    ) = publish(
        attempt = attempt,
        transferredBytes = transferredBytes,
        expectedBytes = expectedBytes,
        terminal = terminal,
        force = true,
    )

    private fun publish(
        attempt: DownloadAttemptIdentity,
        transferredBytes: Long,
        expectedBytes: Long?,
        terminal: DownloadExecutionProgressTerminal?,
        force: Boolean,
    ) {
        require(transferredBytes >= 0L) { "Transferred bytes must be non-negative." }
        val normalizedExpected = expectedBytes?.coerceAtLeast(transferredBytes)
        val update =
            lock.withLock {
                if (terminal != null && activeAttempt != null && activeAttempt != attempt) {
                    // A terminal callback from an obsolete writer must not erase
                    // the state already published for the next queue attempt.
                    return@withLock null
                }
                val replacingAttempt = activeAttempt != attempt
                if (replacingAttempt) {
                    activeAttempt = attempt
                    lastPublishedBytes = 0L
                }
                val advanced = transferredBytes - lastPublishedBytes >= MIN_PROGRESS_DELTA_BYTES
                if (!force && !replacingAttempt && !advanced) {
                    return@withLock null
                }
                // Native task progress must not claim 100 percent until the writer has reached a
                // durable completed disposition. Estimated HLS totals may grow as bytes arrive.
                val visibleTransferred =
                    if (terminal == DownloadExecutionProgressTerminal.Completed) {
                        transferredBytes
                    } else {
                        normalizedExpected?.let { expected ->
                            minOf(transferredBytes, (expected - 1L).coerceAtLeast(0L))
                        }
                            ?: transferredBytes
                    }
                lastPublishedBytes = transferredBytes
                if (terminal != null && activeAttempt == attempt) {
                    activeAttempt = null
                    lastPublishedBytes = 0L
                }
                DownloadExecutionProgressUpdate(
                    attempt = attempt,
                    transferredBytes = visibleTransferred,
                    expectedBytes = normalizedExpected,
                    terminal = terminal,
                )
            }
        if (update != null) sink?.report(update)
    }

    private companion object {
        const val MIN_PROGRESS_DELTA_BYTES: Long = 256L * 1024L
    }
}
