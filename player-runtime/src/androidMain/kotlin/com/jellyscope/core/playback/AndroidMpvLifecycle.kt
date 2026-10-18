// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackError

/**
 * Pure generation/terminal-state kernel for the Android mpv controller.
 *
 * libmpv 1.0 drops the structured END_FILE reason.  Completion therefore
 * requires both current-generation FILE_LOADED and eof-reached=true.  A
 * generation is deliberately not enough: the reused context can report the
 * outgoing item's latched EOF before the replacement has loaded.
 */
internal data class AndroidMpvLifecycleState(
    val generation: Long = 0L,
    val loaded: Boolean = false,
    val eofReached: Boolean = false,
    val stopped: Boolean = false,
    val released: Boolean = false,
    val outcome: AndroidMpvLifecycleOutcome = AndroidMpvLifecycleOutcome.Active,
)

internal enum class AndroidMpvLifecycleOutcome {
    Active,
    Completed,
    Failed,
    Stopped,
    Released,
}

internal sealed interface AndroidMpvLifecycleEvent {
    data class Prepare(
        val generation: Long,
    ) : AndroidMpvLifecycleEvent

    data class FileLoaded(
        val generation: Long,
    ) : AndroidMpvLifecycleEvent

    data class EofReached(
        val generation: Long,
        val reached: Boolean,
    ) : AndroidMpvLifecycleEvent

    data class EndFile(
        val generation: Long,
    ) : AndroidMpvLifecycleEvent

    data class NativeFailure(
        val generation: Long,
        val error: PlaybackError,
    ) : AndroidMpvLifecycleEvent

    data class StartupTimeout(
        val generation: Long,
        val error: PlaybackError,
    ) : AndroidMpvLifecycleEvent

    data class Stop(
        val generation: Long,
    ) : AndroidMpvLifecycleEvent

    data class Release(
        val generation: Long,
    ) : AndroidMpvLifecycleEvent
}

internal sealed interface AndroidMpvLifecycleDecision {
    data object None : AndroidMpvLifecycleDecision

    data object Ready : AndroidMpvLifecycleDecision

    data object Completed : AndroidMpvLifecycleDecision

    data class Failed(
        val error: PlaybackError,
    ) : AndroidMpvLifecycleDecision

    data object Ignored : AndroidMpvLifecycleDecision
}

internal data class AndroidMpvLifecycleTransition(
    val state: AndroidMpvLifecycleState,
    val decision: AndroidMpvLifecycleDecision,
)

internal fun reduceAndroidMpvLifecycle(
    current: AndroidMpvLifecycleState,
    event: AndroidMpvLifecycleEvent,
): AndroidMpvLifecycleTransition {
    val eventGeneration =
        when (event) {
            is AndroidMpvLifecycleEvent.Prepare -> event.generation
            is AndroidMpvLifecycleEvent.FileLoaded -> event.generation
            is AndroidMpvLifecycleEvent.EofReached -> event.generation
            is AndroidMpvLifecycleEvent.EndFile -> event.generation
            is AndroidMpvLifecycleEvent.NativeFailure -> event.generation
            is AndroidMpvLifecycleEvent.StartupTimeout -> event.generation
            is AndroidMpvLifecycleEvent.Stop -> event.generation
            is AndroidMpvLifecycleEvent.Release -> event.generation
        }
    if (current.released) {
        return AndroidMpvLifecycleTransition(current, AndroidMpvLifecycleDecision.Ignored)
    }

    if (eventGeneration != current.generation && event !is AndroidMpvLifecycleEvent.Prepare) {
        return AndroidMpvLifecycleTransition(current, AndroidMpvLifecycleDecision.Ignored)
    }

    return when (event) {
        is AndroidMpvLifecycleEvent.Prepare ->
            AndroidMpvLifecycleTransition(
                state =
                    AndroidMpvLifecycleState(
                        generation = event.generation,
                        outcome = AndroidMpvLifecycleOutcome.Active,
                    ),
                decision = AndroidMpvLifecycleDecision.None,
            )

        is AndroidMpvLifecycleEvent.FileLoaded ->
            if (current.stopped || current.outcome != AndroidMpvLifecycleOutcome.Active) {
                AndroidMpvLifecycleTransition(current, AndroidMpvLifecycleDecision.Ignored)
            } else {
                AndroidMpvLifecycleTransition(
                    current.copy(loaded = true),
                    AndroidMpvLifecycleDecision.Ready,
                )
            }

        is AndroidMpvLifecycleEvent.EofReached ->
            if (!current.loaded || current.stopped || current.outcome != AndroidMpvLifecycleOutcome.Active) {
                // Do not latch EOF observed before FILE_LOADED.  It can belong
                // to the outgoing item on a reused mpv context.
                AndroidMpvLifecycleTransition(current, AndroidMpvLifecycleDecision.Ignored)
            } else if (!event.reached) {
                AndroidMpvLifecycleTransition(
                    current.copy(eofReached = false),
                    AndroidMpvLifecycleDecision.None,
                )
            } else {
                AndroidMpvLifecycleTransition(
                    current.copy(eofReached = true, outcome = AndroidMpvLifecycleOutcome.Completed),
                    AndroidMpvLifecycleDecision.Completed,
                )
            }

        is AndroidMpvLifecycleEvent.EndFile ->
            if (!current.loaded || current.stopped || current.outcome != AndroidMpvLifecycleOutcome.Active) {
                AndroidMpvLifecycleTransition(current, AndroidMpvLifecycleDecision.Ignored)
            } else if (current.eofReached) {
                AndroidMpvLifecycleTransition(
                    current.copy(outcome = AndroidMpvLifecycleOutcome.Completed),
                    AndroidMpvLifecycleDecision.Completed,
                )
            } else {
                AndroidMpvLifecycleTransition(
                    current.copy(outcome = AndroidMpvLifecycleOutcome.Failed),
                    AndroidMpvLifecycleDecision.Failed(PlaybackError.Unknown),
                )
            }

        is AndroidMpvLifecycleEvent.NativeFailure ->
            if (current.stopped || current.outcome != AndroidMpvLifecycleOutcome.Active) {
                AndroidMpvLifecycleTransition(current, AndroidMpvLifecycleDecision.Ignored)
            } else {
                AndroidMpvLifecycleTransition(
                    current.copy(outcome = AndroidMpvLifecycleOutcome.Failed),
                    AndroidMpvLifecycleDecision.Failed(event.error),
                )
            }

        is AndroidMpvLifecycleEvent.StartupTimeout ->
            if (current.loaded || current.stopped || current.outcome != AndroidMpvLifecycleOutcome.Active) {
                AndroidMpvLifecycleTransition(current, AndroidMpvLifecycleDecision.Ignored)
            } else {
                AndroidMpvLifecycleTransition(
                    current.copy(outcome = AndroidMpvLifecycleOutcome.Failed),
                    AndroidMpvLifecycleDecision.Failed(event.error),
                )
            }

        is AndroidMpvLifecycleEvent.Stop ->
            AndroidMpvLifecycleTransition(
                current.copy(stopped = true, outcome = AndroidMpvLifecycleOutcome.Stopped),
                AndroidMpvLifecycleDecision.Ignored,
            )

        is AndroidMpvLifecycleEvent.Release ->
            AndroidMpvLifecycleTransition(
                current.copy(stopped = true, released = true, outcome = AndroidMpvLifecycleOutcome.Released),
                AndroidMpvLifecycleDecision.Ignored,
            )
    }
}

internal class AndroidMpvLifecycleKernel {
    var state: AndroidMpvLifecycleState = AndroidMpvLifecycleState()
        private set

    fun prepare(generation: Long): AndroidMpvLifecycleTransition = transition(AndroidMpvLifecycleEvent.Prepare(generation))

    fun transition(event: AndroidMpvLifecycleEvent): AndroidMpvLifecycleTransition {
        val result = reduceAndroidMpvLifecycle(state, event)
        state = result.state
        return result
    }
}
