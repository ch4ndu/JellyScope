// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class AudioActivationConfirmation(
    private val scope: CoroutineScope,
    private val platform: PlaybackDiagnosticPlatform,
    private val currentState: () -> AudioActivationState,
    private val publish: (AudioActivationState) -> Unit,
    private val onActivated: () -> Unit = {},
) {
    private var timeoutJob: Job? = null

    fun begin(target: AudioActivationTarget) {
        timeoutJob?.cancel()
        timeoutJob = null
        publish(AudioActivationState.Pending(target))
    }

    fun armTimeout(
        target: AudioActivationTarget,
        candidateCount: Int? = null,
        timeoutMs: Long? = null,
    ) {
        if (currentState() != AudioActivationState.Pending(target) || timeoutJob?.isActive == true) return
        timeoutJob =
            scope.launch {
                delay(timeoutMs ?: AUDIO_ACTIVATION_TIMEOUT_MS)
                if (currentState() == AudioActivationState.Pending(target)) {
                    logMapping(NativeTrackMappingResult.Timeout, candidateCount)
                    publish(AudioActivationState.Unavailable(target))
                }
            }
    }

    fun confirm(target: AudioActivationTarget) {
        val state = currentState()
        if (state.targetOrNull() != target || state is AudioActivationState.Active) return
        timeoutJob?.cancel()
        timeoutJob = null
        publish(AudioActivationState.Active(target))
        onActivated()
    }

    fun fail(
        target: AudioActivationTarget,
        result: NativeTrackMappingResult,
        candidateCount: Int? = null,
    ) {
        if (currentState().targetOrNull() != target) return
        timeoutJob?.cancel()
        timeoutJob = null
        logMapping(result, candidateCount)
        publish(AudioActivationState.Unavailable(target))
    }

    fun clear() {
        timeoutJob?.cancel()
        timeoutJob = null
        publish(AudioActivationState.None)
    }

    private fun logMapping(
        result: NativeTrackMappingResult,
        candidateCount: Int?,
    ) {
        audioActivationLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Mapping,
                    event =
                        if (result == NativeTrackMappingResult.Timeout) {
                            PlaybackDiagnosticEvent.Timeout
                        } else {
                            PlaybackDiagnosticEvent.Failed
                        },
                    platform = platform,
                    trackKind = PlaybackDiagnosticTrackKind.Audio,
                    candidateCount = candidateCount,
                    mappingResult = result,
                ),
            )
        }
    }
}

private fun AudioActivationState.targetOrNull(): AudioActivationTarget? =
    when (this) {
        is AudioActivationState.Pending -> target
        is AudioActivationState.Active -> target
        is AudioActivationState.Unavailable -> target
        AudioActivationState.None -> null
    }

internal const val AUDIO_ACTIVATION_TIMEOUT_MS = 3_000L

private val audioActivationLogger = diagnosticLogger(DiagnosticTag.AudioActivation)
