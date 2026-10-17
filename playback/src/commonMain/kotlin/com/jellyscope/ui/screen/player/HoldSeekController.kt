// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.CoroutineScope

/** Binds a held seek to one item so queue switches drop stale commits. */
class HoldSeekController(
    scope: CoroutineScope,
    private val itemId: () -> String?,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long?,
    private val commit: (Long) -> Unit,
    private val onFinalCommit: (Long) -> Unit = {},
) {
    var pendingTargetMs: Long? by mutableStateOf(null)
        private set

    val sessionActive: Boolean get() = pendingTargetMs != null

    private var sessionItemId: String? = null
    private val aggregator = HoldSeekAggregator()
    private val timers =
        HoldSeekTimers(
            scope = scope,
            onWatchdogExpired = { finishSession(commitTarget = true) },
            onCadenceTick = {
                if (sessionItemId != null && itemId() == sessionItemId) {
                    aggregator.takeCadenceCommit()?.let(commit)
                } else {
                    finishSession(commitTarget = false)
                }
            },
        )

    fun onSeekKeyDown(direction: HoldSeekDirection) {
        val starting = !aggregator.sessionActive
        if (starting) {
            sessionItemId = itemId() ?: return
        }
        pendingTargetMs = aggregator.onSeekKey(direction, positionMs(), durationMs())
        if (starting) {
            timers.onSessionStarted()
        } else {
            timers.onRepeatEvent()
        }
    }

    fun onSeekKeyUp() {
        finishSession(commitTarget = true)
    }

    fun commitBeforeAction() {
        finishSession(commitTarget = true)
    }

    fun cancel() {
        finishSession(commitTarget = false)
    }

    private fun finishSession(commitTarget: Boolean) {
        timers.onSessionEnded()
        val target = aggregator.takeFinalCommit()
        val boundItemId = sessionItemId
        sessionItemId = null
        pendingTargetMs = null
        if (commitTarget && boundItemId != null && itemId() == boundItemId) {
            target?.let { finalTarget ->
                onFinalCommit(finalTarget)
                commit(finalTarget)
            }
        }
    }
}

fun holdSeekDirectionForKey(
    key: Key,
    includeDpadHorizontal: Boolean,
): HoldSeekDirection? =
    when (key) {
        Key.MediaRewind, Key.MediaSkipBackward -> HoldSeekDirection.Backward
        Key.MediaFastForward, Key.MediaSkipForward -> HoldSeekDirection.Forward
        Key.DirectionLeft -> HoldSeekDirection.Backward.takeIf { includeDpadHorizontal }
        Key.DirectionRight -> HoldSeekDirection.Forward.takeIf { includeDpadHorizontal }
        else -> null
    }
