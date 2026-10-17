// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.playback.IosPictureInPictureStartAdmission

internal const val IOS_PIP_START_CONFIRMATION_TIMEOUT_MS = 2_000L

/** Pure, source-identity-bound PiP admission and settlement state. */
internal class IosPictureInPictureLifecycle {
    private var state: IosPictureInPictureLifecycleState = IosPictureInPictureLifecycleState.Inactive

    val sourceIdentity: Long?
        get() = state.sourceIdentity

    fun canAdmitStart(): Boolean = state == IosPictureInPictureLifecycleState.Inactive

    fun beginStart(
        admission: IosPictureInPictureStartAdmission,
        requestedAtMs: Long,
    ): Boolean {
        if (!canAdmitStart() || requestedAtMs < 0L) return false
        val deadlineAtMs = requestedAtMs + IOS_PIP_START_CONFIRMATION_TIMEOUT_MS
        state =
            when (admission) {
                IosPictureInPictureStartAdmission.Rejected -> return false
                is IosPictureInPictureStartAdmission.ExplicitStartRequested ->
                    IosPictureInPictureLifecycleState.ExplicitStartRequested(
                        sourceIdentity = admission.sourceIdentity,
                        deadlineAtMs = deadlineAtMs,
                    )
                is IosPictureInPictureStartAdmission.AutomaticStartPending ->
                    IosPictureInPictureLifecycleState.AutomaticStartPending(
                        sourceIdentity = admission.sourceIdentity,
                        deadlineAtMs = deadlineAtMs,
                    )
            }
        return true
    }

    fun onDidEnterBackground(): IosPictureInPictureBackgroundResult =
        when (val current = state) {
            IosPictureInPictureLifecycleState.Inactive -> IosPictureInPictureBackgroundResult.Close
            is IosPictureInPictureLifecycleState.ExplicitStartRequested -> {
                state = current.copy(backgrounded = true)
                IosPictureInPictureBackgroundResult.AwaitConfirmation(current.deadlineAtMs)
            }
            is IosPictureInPictureLifecycleState.AutomaticStartPending -> {
                state = current.copy(backgrounded = true)
                IosPictureInPictureBackgroundResult.AwaitConfirmation(current.deadlineAtMs)
            }
            is IosPictureInPictureLifecycleState.Active -> {
                state = current.copy(backgrounded = true)
                IosPictureInPictureBackgroundResult.KeepOpen
            }
            is IosPictureInPictureLifecycleState.StopPending -> {
                state = current.copy(backgrounded = true)
                IosPictureInPictureBackgroundResult.AwaitConfirmation(current.deadlineAtMs)
            }
            is IosPictureInPictureLifecycleState.Restoring -> {
                state = current.copy(backgrounded = true)
                IosPictureInPictureBackgroundResult.AwaitConfirmation(current.deadlineAtMs)
            }
        }

    fun onDidBecomeActive(observedAtMs: Long): IosPictureInPictureForegroundResult =
        when (val current = state) {
            IosPictureInPictureLifecycleState.Inactive -> IosPictureInPictureForegroundResult.None
            is IosPictureInPictureLifecycleState.ExplicitStartRequested -> {
                val deadlineAtMs = observedAtMs.coerceAtLeast(0L) + IOS_PIP_START_CONFIRMATION_TIMEOUT_MS
                state = IosPictureInPictureLifecycleState.Restoring(current.sourceIdentity, deadlineAtMs)
                IosPictureInPictureForegroundResult.RequestStopAndRestore(current.sourceIdentity, deadlineAtMs)
            }
            is IosPictureInPictureLifecycleState.AutomaticStartPending -> {
                val deadlineAtMs = observedAtMs.coerceAtLeast(0L) + IOS_PIP_START_CONFIRMATION_TIMEOUT_MS
                state = IosPictureInPictureLifecycleState.Restoring(current.sourceIdentity, deadlineAtMs)
                IosPictureInPictureForegroundResult.RequestStopAndRestore(current.sourceIdentity, deadlineAtMs)
            }
            is IosPictureInPictureLifecycleState.Active ->
                if (current.backgrounded) {
                    state = current.copy(backgrounded = false)
                    IosPictureInPictureForegroundResult.None
                } else {
                    val deadlineAtMs = observedAtMs.coerceAtLeast(0L) + IOS_PIP_START_CONFIRMATION_TIMEOUT_MS
                    state = IosPictureInPictureLifecycleState.Restoring(current.sourceIdentity, deadlineAtMs)
                    IosPictureInPictureForegroundResult.RequestStopAndRestore(current.sourceIdentity, deadlineAtMs)
                }
            is IosPictureInPictureLifecycleState.Restoring -> {
                if (current.backgrounded) state = current.copy(backgrounded = false)
                IosPictureInPictureForegroundResult.None
            }
            is IosPictureInPictureLifecycleState.StopPending -> {
                state = IosPictureInPictureLifecycleState.Inactive
                IosPictureInPictureForegroundResult.Restored
            }
        }

    fun onPictureInPictureStarted(
        sourceIdentity: Long,
        observedAtMs: Long,
    ): IosPictureInPictureStartResult =
        when (val current = state) {
            is IosPictureInPictureLifecycleState.ExplicitStartRequested ->
                current.resolveStarted(sourceIdentity, observedAtMs)
            is IosPictureInPictureLifecycleState.AutomaticStartPending ->
                current.resolveStarted(sourceIdentity, observedAtMs)
            is IosPictureInPictureLifecycleState.Active ->
                if (current.sourceIdentity == sourceIdentity) {
                    IosPictureInPictureStartResult.AlreadyActive
                } else {
                    IosPictureInPictureStartResult.Ignored
                }
            is IosPictureInPictureLifecycleState.Restoring ->
                if (current.sourceIdentity == sourceIdentity) {
                    IosPictureInPictureStartResult.RequestStop
                } else {
                    IosPictureInPictureStartResult.Ignored
                }
            IosPictureInPictureLifecycleState.Inactive,
            is IosPictureInPictureLifecycleState.StopPending,
            -> IosPictureInPictureStartResult.Ignored
        }

    /** Preserves AVKit's explicit in-app PiP control without admitting a stale background start. */
    fun acceptForegroundNativeStart(sourceIdentity: Long): Boolean {
        if (state != IosPictureInPictureLifecycleState.Inactive || sourceIdentity < 0L) return false
        state = IosPictureInPictureLifecycleState.Active(sourceIdentity, backgrounded = false)
        return true
    }

    fun onPictureInPictureStartFailed(sourceIdentity: Long): IosPictureInPictureStopResult {
        val current = state
        if (current.sourceIdentity != sourceIdentity) return IosPictureInPictureStopResult.Inactive
        val result =
            when (current) {
                is IosPictureInPictureLifecycleState.ExplicitStartRequested ->
                    if (current.backgrounded) IosPictureInPictureStopResult.Closed else IosPictureInPictureStopResult.Reset
                is IosPictureInPictureLifecycleState.AutomaticStartPending ->
                    if (current.backgrounded) IosPictureInPictureStopResult.Closed else IosPictureInPictureStopResult.Reset
                is IosPictureInPictureLifecycleState.Restoring -> IosPictureInPictureStopResult.Restored
                else -> return IosPictureInPictureStopResult.Inactive
            }
        state = IosPictureInPictureLifecycleState.Inactive
        return result
    }

    fun onRestoreUserInterface(
        sourceIdentity: Long,
        observedAtMs: Long,
        isAppBackgrounded: Boolean,
    ): Long? {
        if (state.sourceIdentity != sourceIdentity) return null
        if (
            state !is IosPictureInPictureLifecycleState.Active &&
            state !is IosPictureInPictureLifecycleState.ExplicitStartRequested &&
            state !is IosPictureInPictureLifecycleState.AutomaticStartPending &&
            state !is IosPictureInPictureLifecycleState.StopPending
        ) {
            return null
        }
        val deadlineAtMs = observedAtMs.coerceAtLeast(0L) + IOS_PIP_START_CONFIRMATION_TIMEOUT_MS
        state = IosPictureInPictureLifecycleState.Restoring(sourceIdentity, deadlineAtMs, isAppBackgrounded)
        return deadlineAtMs
    }

    fun onPictureInPictureStopped(
        sourceIdentity: Long,
        observedAtMs: Long,
    ): IosPictureInPictureStopResult {
        val current = state
        if (current.sourceIdentity != sourceIdentity) return IosPictureInPictureStopResult.Inactive
        return when (current) {
            is IosPictureInPictureLifecycleState.Restoring -> {
                state = IosPictureInPictureLifecycleState.Inactive
                IosPictureInPictureStopResult.Restored
            }
            is IosPictureInPictureLifecycleState.Active -> current.settleStopped(observedAtMs)
            is IosPictureInPictureLifecycleState.ExplicitStartRequested -> current.settleStopped(observedAtMs)
            is IosPictureInPictureLifecycleState.AutomaticStartPending -> current.settleStopped(observedAtMs)
            IosPictureInPictureLifecycleState.Inactive,
            is IosPictureInPictureLifecycleState.StopPending,
            -> IosPictureInPictureStopResult.Inactive
        }
    }

    fun onConfirmationDeadline(
        sourceIdentity: Long,
        observedAtMs: Long,
    ): IosPictureInPictureDeadlineResult {
        val current = state
        val result =
            when (current) {
                is IosPictureInPictureLifecycleState.ExplicitStartRequested ->
                    current.deadlineResult(sourceIdentity, observedAtMs)
                is IosPictureInPictureLifecycleState.AutomaticStartPending ->
                    current.deadlineResult(sourceIdentity, observedAtMs)
                is IosPictureInPictureLifecycleState.StopPending ->
                    current.deadlineResult(sourceIdentity, observedAtMs)
                is IosPictureInPictureLifecycleState.Restoring ->
                    when {
                        current.sourceIdentity != sourceIdentity || observedAtMs < current.deadlineAtMs ->
                            IosPictureInPictureDeadlineResult.None
                        current.backgrounded -> IosPictureInPictureDeadlineResult.Closed
                        else -> IosPictureInPictureDeadlineResult.Restored
                    }
                else -> IosPictureInPictureDeadlineResult.None
            }
        if (result != IosPictureInPictureDeadlineResult.None) {
            state = IosPictureInPictureLifecycleState.Inactive
        }
        return result
    }

    fun onSourceInvalidated(
        sourceIdentity: Long,
        isAppBackgrounded: Boolean,
    ): IosPictureInPictureSourceInvalidationResult {
        if (state.sourceIdentity != sourceIdentity) return IosPictureInPictureSourceInvalidationResult.Ignored
        val shouldClose = isAppBackgrounded || state.isBackgrounded
        state = IosPictureInPictureLifecycleState.Inactive
        return if (shouldClose) {
            IosPictureInPictureSourceInvalidationResult.Closed
        } else {
            IosPictureInPictureSourceInvalidationResult.Reset
        }
    }

    fun reset() {
        state = IosPictureInPictureLifecycleState.Inactive
    }

    private fun IosPictureInPictureLifecycleState.Pending.resolveStarted(
        sourceIdentity: Long,
        observedAtMs: Long,
    ): IosPictureInPictureStartResult {
        if (this.sourceIdentity != sourceIdentity) return IosPictureInPictureStartResult.Ignored
        if (observedAtMs > deadlineAtMs) {
            state =
                IosPictureInPictureLifecycleState.Restoring(
                    sourceIdentity = sourceIdentity,
                    deadlineAtMs = observedAtMs + IOS_PIP_START_CONFIRMATION_TIMEOUT_MS,
                    backgrounded = backgrounded,
                )
            return IosPictureInPictureStartResult.RequestStop
        }
        state = IosPictureInPictureLifecycleState.Active(sourceIdentity, backgrounded)
        return IosPictureInPictureStartResult.Started
    }

    private fun IosPictureInPictureLifecycleState.Active.settleStopped(observedAtMs: Long): IosPictureInPictureStopResult =
        settleStopPending(sourceIdentity, backgrounded, observedAtMs)

    private fun IosPictureInPictureLifecycleState.Pending.settleStopped(observedAtMs: Long): IosPictureInPictureStopResult =
        settleStopPending(sourceIdentity, backgrounded, observedAtMs)

    private fun settleStopPending(
        sourceIdentity: Long,
        backgrounded: Boolean,
        observedAtMs: Long,
    ): IosPictureInPictureStopResult {
        val deadlineAtMs = observedAtMs.coerceAtLeast(0L) + IOS_PIP_START_CONFIRMATION_TIMEOUT_MS
        state =
            IosPictureInPictureLifecycleState.StopPending(
                sourceIdentity = sourceIdentity,
                deadlineAtMs = deadlineAtMs,
                backgrounded = backgrounded,
            )
        return IosPictureInPictureStopResult.Pending(deadlineAtMs, backgrounded)
    }

    private fun IosPictureInPictureLifecycleState.Pending.deadlineResult(
        sourceIdentity: Long,
        observedAtMs: Long,
    ): IosPictureInPictureDeadlineResult =
        if (this.sourceIdentity == sourceIdentity && backgrounded && observedAtMs >= deadlineAtMs) {
            IosPictureInPictureDeadlineResult.Closed
        } else {
            IosPictureInPictureDeadlineResult.None
        }

    private fun IosPictureInPictureLifecycleState.StopPending.deadlineResult(
        sourceIdentity: Long,
        observedAtMs: Long,
    ): IosPictureInPictureDeadlineResult =
        if (this.sourceIdentity != sourceIdentity || observedAtMs < deadlineAtMs) {
            IosPictureInPictureDeadlineResult.None
        } else if (backgrounded) {
            IosPictureInPictureDeadlineResult.Closed
        } else {
            IosPictureInPictureDeadlineResult.Restored
        }
}

private sealed interface IosPictureInPictureLifecycleState {
    val sourceIdentity: Long?
        get() = null

    val isBackgrounded: Boolean
        get() = false

    data object Inactive : IosPictureInPictureLifecycleState

    sealed interface Pending : IosPictureInPictureLifecycleState {
        override val sourceIdentity: Long
        val deadlineAtMs: Long
        val backgrounded: Boolean

        override val isBackgrounded: Boolean
            get() = backgrounded
    }

    data class ExplicitStartRequested(
        override val sourceIdentity: Long,
        override val deadlineAtMs: Long,
        override val backgrounded: Boolean = false,
    ) : Pending

    data class AutomaticStartPending(
        override val sourceIdentity: Long,
        override val deadlineAtMs: Long,
        override val backgrounded: Boolean = false,
    ) : Pending

    data class Active(
        override val sourceIdentity: Long,
        val backgrounded: Boolean,
    ) : IosPictureInPictureLifecycleState {
        override val isBackgrounded: Boolean
            get() = backgrounded
    }

    data class Restoring(
        override val sourceIdentity: Long,
        val deadlineAtMs: Long,
        val backgrounded: Boolean = false,
    ) : IosPictureInPictureLifecycleState {
        override val isBackgrounded: Boolean
            get() = backgrounded
    }

    data class StopPending(
        override val sourceIdentity: Long,
        val deadlineAtMs: Long,
        val backgrounded: Boolean,
    ) : IosPictureInPictureLifecycleState {
        override val isBackgrounded: Boolean
            get() = backgrounded
    }
}

internal sealed interface IosPictureInPictureBackgroundResult {
    data object KeepOpen : IosPictureInPictureBackgroundResult

    data class AwaitConfirmation(
        val deadlineAtMs: Long,
    ) : IosPictureInPictureBackgroundResult

    data object Close : IosPictureInPictureBackgroundResult
}

internal sealed interface IosPictureInPictureForegroundResult {
    data object None : IosPictureInPictureForegroundResult

    data class RequestStopAndRestore(
        val sourceIdentity: Long,
        val deadlineAtMs: Long,
    ) : IosPictureInPictureForegroundResult

    data object Restored : IosPictureInPictureForegroundResult
}

internal enum class IosPictureInPictureDeadlineResult {
    None,
    Restored,
    Closed,
}

internal enum class IosPictureInPictureSourceInvalidationResult {
    Ignored,
    Reset,
    Closed,
}

internal enum class IosPictureInPictureStartResult {
    Started,
    AlreadyActive,
    RequestStop,
    Ignored,
}

internal sealed interface IosPictureInPictureStopResult {
    data object Reset : IosPictureInPictureStopResult

    data object Restored : IosPictureInPictureStopResult

    data object Closed : IosPictureInPictureStopResult

    data object Inactive : IosPictureInPictureStopResult

    data class Pending(
        val deadlineAtMs: Long,
        val backgrounded: Boolean,
    ) : IosPictureInPictureStopResult
}
