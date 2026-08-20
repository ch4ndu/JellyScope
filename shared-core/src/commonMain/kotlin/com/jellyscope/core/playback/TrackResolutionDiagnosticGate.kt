// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.NativeTrackMappingReason
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Atomically suppresses unchanged track-resolution diagnostics. [reset] is safe
 * only with no admission in flight; mpv therefore retains outcomes across sessions.
 * [target] participates in equality only and must never be logged.
 */
internal class TrackResolutionDiagnosticGate {
    private data class Key(
        val kind: PlaybackDiagnosticTrackKind,
        val target: Any?,
        val candidateCount: Int,
        val result: NativeTrackMappingResult,
        val reason: NativeTrackMappingReason,
    )

    private val lock = ReentrantLock()
    private val lastKeys = mutableMapOf<PlaybackDiagnosticTrackKind, Key>()

    /** Returns true when this outcome differs from the last one admitted for [kind]. */
    fun admit(
        kind: PlaybackDiagnosticTrackKind,
        target: Any?,
        candidateCount: Int,
        result: NativeTrackMappingResult,
        reason: NativeTrackMappingReason,
    ): Boolean =
        lock.withLock {
            val key = Key(kind, target, candidateCount, result, reason)
            if (key == lastKeys[kind]) {
                false
            } else {
                lastKeys[kind] = key
                true
            }
        }

    /** Drops recorded outcomes for a controller that can guarantee no admission is in flight. */
    fun reset() {
        lock.withLock {
            lastKeys.clear()
        }
    }
}
