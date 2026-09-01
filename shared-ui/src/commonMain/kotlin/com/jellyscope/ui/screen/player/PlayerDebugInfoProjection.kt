// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryState
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackCapabilityResult
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackHealthSignalKind
import com.jellyscope.core.domain.playback.PlaybackHealthThresholdClass
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackResolutionPolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleRenderInfo

internal data class PlayerDebugInfoProjectionInput(
    val plan: PlaybackPlan,
    val backend: PlayerBackend,
    val mediaStreams: List<PlaybackMediaStream>,
    val installedAudioStreamIndex: Int?,
    val subtitleRenderInfo: SubtitleRenderInfo,
    val subtitleStyleable: Boolean,
    val launchToFirstFrameMs: Long?,
    val lastHealthSignal: PlaybackHealthSignalKind?,
    val lastHealthThresholdClass: PlaybackHealthThresholdClass?,
    val playSessionId: String?,
    val selectedQualityPolicy: PlaybackQualityPolicy,
    val qualityExplicitlyChosen: Boolean,
    val autoRecoveryState: AutoPlaybackRecoveryState,
    val activePlaybackPreferences: PlaybackPreferences,
    val firstVideoOutput: PlayerFirstVideoOutputDebug,
)

internal fun projectPlayerDebugInfo(input: PlayerDebugInfoProjectionInput): PlayerDebugInfo {
    val plan = input.plan
    val videoStream = input.mediaStreams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
    val audioStream =
        input.installedAudioStreamIndex
            ?.let { index -> input.mediaStreams.firstOrNull { stream -> stream.index == index } }
            ?: input.mediaStreams.firstOrNull { stream -> stream.type.equals("Audio", ignoreCase = true) }
    return PlayerDebugInfo(
        backend = input.backend,
        playMethod = plan.streamMode.debugPlayMethod(),
        transcodeReasons = plan.transcodeReasons,
        container = plan.container,
        videoCodec = videoStream?.codec,
        videoResolution = videoStream?.height?.let { height -> "${height}p" },
        videoPresentation = plan.videoPresentation,
        videoBitrateBps = videoStream?.bitRate,
        audioCodec = audioStream?.codec,
        audioChannels = audioStream?.channelLayout,
        audioLanguage = audioStream?.language,
        sourceBitrateBps = plan.sourceBitrateBps ?: videoStream?.bitRate,
        requestCapBitrateBps = plan.maxStreamingBitrate,
        qualityCapOrigin = plan.qualityCapOrigin,
        clientTrigger = plan.clientTrigger,
        effectiveTranscodeCapBitrateBps = plan.effectiveTranscodeMaxStreamingBitrate,
        launchToFirstFrameMs = input.launchToFirstFrameMs,
        healthSignal = input.lastHealthSignal,
        healthThresholdClass = input.lastHealthThresholdClass,
        subtitleStreamIndex = input.subtitleRenderInfo.streamIndex,
        subtitleLabel = input.subtitleRenderInfo.label,
        subtitleLanguage = input.subtitleRenderInfo.language,
        subtitleRenderMode = input.subtitleRenderInfo.mode,
        subtitleRenderStatus = input.subtitleRenderInfo.status,
        subtitleStyleable = input.subtitleStyleable,
        subtitleRenderReason = input.subtitleRenderInfo.reason,
        playSessionId = plan.playSessionId ?: input.playSessionId,
        qualityPolicyMode = plan.qualityPolicy.mode.name,
        qualityPolicyOrigin =
            playbackQualityPolicyOriginForDebug(
                selectedQualityPolicy = input.selectedQualityPolicy,
                qualityExplicitlyChosen = input.qualityExplicitlyChosen,
                autoRecoveryState = input.autoRecoveryState,
            ),
        clientLimiter = plan.bitrateConstraint.debugClientLimiterLabel(),
        capabilityResult = plan.capabilityResultForDebug(),
        capabilityReason = plan.capabilityReasonForDebug(),
        configuredVlcTranscodeBudgetBps = input.activePlaybackPreferences.vlcTranscodeMaxBitrateBps,
        effectiveTranscodeCap = plan.effectiveTranscodeCapForDebug(),
        recoveryKind = plan.recoveryIntent.debugLabel(),
        recoveryReason = plan.recoveryReasonForDebug(input.lastHealthSignal),
        remainingRecoveryBudget = plan.remainingRecoveryBudgetForDebug(input.autoRecoveryState, input.qualityExplicitlyChosen),
        firstVideoOutput = input.firstVideoOutput,
    )
}

private fun StreamMode.debugPlayMethod(): String =
    when (this) {
        StreamMode.DirectPlay -> "Direct Play"
        StreamMode.DirectStream -> "Direct Stream"
        StreamMode.Transcode -> "Transcode"
        StreamMode.Offline -> "Offline"
    }

internal fun playbackQualityPolicyOriginForDebug(
    selectedQualityPolicy: PlaybackQualityPolicy,
    qualityExplicitlyChosen: Boolean,
    autoRecoveryState: AutoPlaybackRecoveryState,
): PlaybackQualityPolicyOrigin =
    when {
        selectedQualityPolicy.mode == PlaybackQualityMode.Auto &&
            autoRecoveryState.runtimeQualityCapBps != null -> PlaybackQualityPolicyOrigin.SessionAutoRecovery
        qualityExplicitlyChosen -> PlaybackQualityPolicyOrigin.SessionOverride
        else -> PlaybackQualityPolicyOrigin.SettingsDefault
    }

private fun PlaybackPlan.capabilityResultForDebug(): PlaybackCapabilityResult =
    when (clientTrigger) {
        PlaybackClientTrigger.DecodeCapabilityCap,
        PlaybackClientTrigger.UserResolutionLimit,
        -> PlaybackCapabilityResult.SourceCopyRejected

        else ->
            if (resolutionPolicy == PlaybackResolutionPolicy.NoCap) {
                PlaybackCapabilityResult.NoFiniteCapabilityCap
            } else {
                PlaybackCapabilityResult.SourceCopyAllowed
            }
    }

private fun PlaybackPlan.capabilityReasonForDebug(): String =
    when (clientTrigger) {
        PlaybackClientTrigger.DecodeCapabilityCap -> "Decoder capability profile rejected source copy"
        PlaybackClientTrigger.UserResolutionLimit -> "User video-resolution setting rejected source copy"
        else -> resolutionPolicy.debugCapabilityReason()
    }

private fun PlaybackPlan.effectiveTranscodeCapForDebug(): String =
    when {
        streamMode != StreamMode.Transcode -> "None (not transcoding)"
        else ->
            effectiveTranscodeMaxStreamingBitrate?.let { bitrate ->
                bitrate.debugMbpsLabel()
            } ?: when (bitrateConstraint) {
                PlaybackBitrateConstraint.NoClientLimit -> "Protocol maximum (no app transcode cap)"
                else -> "Disabled"
            }
    }

private fun PlaybackPlan.recoveryReasonForDebug(lastHealthSignal: PlaybackHealthSignalKind?): String =
    listOfNotNull(
        clientTrigger?.debugLabel(),
        lastHealthSignal?.debugLabel(),
    ).joinToString(" · ").ifEmpty { "None" }

private fun PlaybackPlan.remainingRecoveryBudgetForDebug(
    autoRecoveryState: AutoPlaybackRecoveryState,
    qualityExplicitlyChosen: Boolean,
): String =
    when (qualityPolicy.mode) {
        PlaybackQualityMode.Auto ->
            "Compatibility: ${if (autoRecoveryState.compatibilityAttempted) 0 else 1} remaining · " +
                if (qualityExplicitlyChosen) {
                    "Quality: ${if (autoRecoveryState.qualityAttempted) 0 else 1} remaining"
                } else {
                    "Quality: unavailable (requires explicit Auto)"
                }
        PlaybackQualityMode.Original -> "Compatibility: 0 remaining (Original) · Quality: 0 remaining (Original)"
        PlaybackQualityMode.Fixed ->
            "Compatibility: ${if (autoRecoveryState.compatibilityAttempted) 0 else 1} remaining (Fixed quality) · " +
                "Quality: 0 remaining (Fixed quality)"
    }
