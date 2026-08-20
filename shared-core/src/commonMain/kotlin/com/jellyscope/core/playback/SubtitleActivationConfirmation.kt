// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.SubtitleActivationFailureReason
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class SubtitleActivationConfirmation(
    private val scope: CoroutineScope,
    private val currentState: () -> SubtitleActivationState,
    private val publish: (SubtitleActivationState) -> Unit,
    private val platform: PlaybackDiagnosticPlatform = PlaybackDiagnosticPlatform.Shared,
    private val timeoutMs: Long = SUBTITLE_ACTIVATION_CONFIRMATION_TIMEOUT_MS,
) {
    private var timeoutJob: Job? = null

    fun begin(target: SubtitleActivationTarget) {
        cancelTimeout()
        publish(SubtitleActivationState.Pending(target))
    }

    /**
     * Begins the plan's subtitle activation and preserves the existing
     * ExternalText-without-an-asset failure behavior.
     */
    fun beginForPlan(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        val target = plan.subtitleActivationTarget
        if (target == null) {
            clear()
            return
        }
        begin(target)
        if (target.kind == LocalSubtitleKind.ExternalText && subtitleAsset == null) {
            fail(target, reason = SubtitleActivationFailureReason.MissingExternalAsset)
        }
    }

    fun armTimeout(
        target: SubtitleActivationTarget,
        candidateCount: Int? = null,
        timeoutMsOverride: Long? = null,
    ) {
        if (currentState() != SubtitleActivationState.Pending(target) || timeoutJob?.isActive == true) {
            return
        }
        timeoutJob =
            scope.launch {
                delay(timeoutMsOverride ?: timeoutMs)
                if (currentState() == SubtitleActivationState.Pending(target)) {
                    subtitleActivationLogger.w {
                        mappingDiagnostic(
                            NativeTrackMappingResult.Timeout,
                            PlaybackDiagnosticEvent.Timeout,
                            candidateCount,
                        )
                    }
                    publish(SubtitleActivationState.Unavailable(target))
                }
                timeoutJob = null
            }
    }

    fun confirm(target: SubtitleActivationTarget) {
        if (currentState().targetOrNull() != target) {
            return
        }
        cancelTimeout()
        publish(SubtitleActivationState.Active(target))
    }

    fun fail(
        target: SubtitleActivationTarget,
        reason: SubtitleActivationFailureReason = SubtitleActivationFailureReason.ControllerRejected,
        result: NativeTrackMappingResult = NativeTrackMappingResult.Unsupported,
        candidateCount: Int? = null,
    ) {
        if (currentState().targetOrNull() != target) {
            return
        }
        cancelTimeout()
        subtitleActivationLogger.w {
            mappingDiagnostic(result, PlaybackDiagnosticEvent.Failed, candidateCount, reason)
        }
        publish(SubtitleActivationState.Unavailable(target))
    }

    fun clear() {
        cancelTimeout()
        publish(SubtitleActivationState.None)
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun mappingDiagnostic(
        result: NativeTrackMappingResult,
        event: PlaybackDiagnosticEvent,
        candidateCount: Int? = null,
        reason: SubtitleActivationFailureReason? = null,
    ): String =
        formatPlaybackDiagnostic(
            PlaybackDiagnostic(
                stage = PlaybackDiagnosticStage.Mapping,
                event = event,
                platform = platform,
                trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                candidateCount = candidateCount,
                mappingResult = result,
                reason = reason,
            ),
        )
}

private fun SubtitleActivationState.targetOrNull(): SubtitleActivationTarget? =
    when (this) {
        is SubtitleActivationState.Pending -> target
        is SubtitleActivationState.Active -> target
        is SubtitleActivationState.Unavailable -> target
        SubtitleActivationState.None -> null
    }

internal const val SUBTITLE_ACTIVATION_CONFIRMATION_TIMEOUT_MS = 3_000L

private val subtitleActivationLogger = diagnosticLogger(DiagnosticTag.SubtitleActivation)
