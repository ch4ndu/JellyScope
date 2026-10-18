// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.StreamMode

/** Shared initial-audio decision; controllers still own native activation. */
internal sealed interface InitialAudioActivation {
    /** No audio target in the plan: nothing to confirm. */
    data object None : InitialAudioActivation

    /** DirectPlay remains pending until native track mapping confirms it. */
    data class AwaitNativeMapping(
        val target: AudioActivationTarget,
    ) : InitialAudioActivation

    /** Non-DirectPlay modes, including Offline, already have a fixed choice. */
    data class AlreadyActive(
        val target: AudioActivationTarget,
    ) : InitialAudioActivation
}

internal fun initialAudioActivationFor(plan: PlaybackPlan): InitialAudioActivation =
    plan.audioActivationTarget?.let { target ->
        if (plan.streamMode == StreamMode.DirectPlay) {
            InitialAudioActivation.AwaitNativeMapping(target)
        } else {
            InitialAudioActivation.AlreadyActive(target)
        }
    } ?: InitialAudioActivation.None

internal fun InitialAudioActivation.toAudioActivationState(): AudioActivationState =
    when (this) {
        InitialAudioActivation.None -> AudioActivationState.None
        is InitialAudioActivation.AwaitNativeMapping -> AudioActivationState.Pending(target)
        is InitialAudioActivation.AlreadyActive -> AudioActivationState.Active(target)
    }

/** [begin] must precede [confirm] to preserve pending-state and timeout behavior. */
internal fun AudioActivationConfirmation.applyInitial(decision: InitialAudioActivation) {
    when (decision) {
        InitialAudioActivation.None -> clear()
        is InitialAudioActivation.AwaitNativeMapping -> begin(decision.target)
        is InitialAudioActivation.AlreadyActive -> {
            begin(decision.target)
            confirm(decision.target)
        }
    }
}

/** Android LibVLC leaves None unchanged because its controller already projected it. */
internal fun AudioActivationConfirmation.applyInitialWithoutClearing(decision: InitialAudioActivation) {
    when (decision) {
        InitialAudioActivation.None -> Unit
        is InitialAudioActivation.AwaitNativeMapping -> begin(decision.target)
        is InitialAudioActivation.AlreadyActive -> {
            begin(decision.target)
            confirm(decision.target)
        }
    }
}
