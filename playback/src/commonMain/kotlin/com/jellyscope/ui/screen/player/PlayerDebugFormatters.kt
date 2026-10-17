// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlannedVideoPresentation
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackCapabilityResult
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackFirstVideoOutputState
import com.jellyscope.core.domain.playback.PlaybackHealthSignalKind
import com.jellyscope.core.domain.playback.PlaybackHealthThresholdClass
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackRecoveryIntent
import com.jellyscope.core.domain.playback.PlaybackResolutionPolicy
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.SubtitleRenderMode
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.VideoOutputEvidence
import kotlin.math.round

// Public formatters are shared with the Android TV module.

fun PlayerDebugInfo.transcodeReasonSummary(): String =
    when {
        transcodeReasons.isNotEmpty() -> transcodeReasons.joinToString(", ")
        playMethod == "Transcode" -> "Transcode active; server returned no reasons"
        else -> "None"
    }

fun PlayerDebugInfo.appTriggerSummary(): String =
    listOfNotNull(
        clientTrigger?.debugLabel(),
        qualityCapOrigin?.debugLabel(),
    ).joinToString(" · ").ifEmpty { "None" }

fun PlannedVideoPresentation.debugLabel(): String =
    listOfNotNull(
        listOfNotNull(
            when {
                width != null && height != null -> "$width×$height"
                height != null -> "${height}p"
                else -> null
            },
            frameRate?.let { rate -> "${rate.debugFrameRate()} fps" },
        ).joinToString(" @ ").ifEmpty { null },
        videoRangeType?.takeIf(String::isNotBlank),
    ).joinToString(" · ").ifEmpty { "—" }

fun PlaybackRuntimeDiagnostics.runtimeFormatSummary(): String =
    listOfNotNull(
        if (videoWidth != null && videoHeight != null) "$videoWidth×$videoHeight" else null,
        videoFrameRate?.let { rate -> "${rate.debugFrameRate()} fps" },
    ).joinToString(" @ ").ifEmpty { "—" }

fun PlaybackRuntimeDiagnostics.bufferAllocationSummary(): String =
    listOfNotNull(
        targetBufferBytes?.toMiBLabel(),
        allocatedBufferBytes?.toMiBLabel(),
        peakAllocatedBufferBytes?.toMiBLabel()?.let { "peak $it" },
    ).joinToString(" / ").ifEmpty { "—" }

fun PlaybackRuntimeDiagnostics.rebufferSummary(): String =
    rebufferCount?.let { count ->
        "$count · ${totalRebufferMs ?: 0L} ms total · ${maxRebufferMs ?: 0L} ms max"
    } ?: "—"

fun PlaybackRuntimeDiagnostics.underrunSummary(): String =
    audioUnderrunCount?.let { count ->
        "$count · ${maxAudioFeedGapMs ?: 0L} ms max gap"
    } ?: "—"

fun PlaybackQualityCapOrigin.debugLabel(): String =
    when (this) {
        PlaybackQualityCapOrigin.ExplicitSessionChoice -> "Explicit session choice"
        PlaybackQualityCapOrigin.SettingsDefault -> "Settings default"
        PlaybackQualityCapOrigin.AutoSessionRecovery -> "Auto session recovery"
    }

fun PlaybackQualityPolicyOrigin.debugLabel(): String =
    when (this) {
        PlaybackQualityPolicyOrigin.SettingsDefault -> "Playback settings default"
        PlaybackQualityPolicyOrigin.SessionOverride -> "Current playback choice"
        PlaybackQualityPolicyOrigin.SessionAutoRecovery -> "Session-only Auto recovery"
    }

fun PlaybackCapabilityResult.debugLabel(): String =
    when (this) {
        PlaybackCapabilityResult.SourceCopyAllowed -> "Source copy allowed"
        PlaybackCapabilityResult.SourceCopyRejected -> "Source copy rejected"
        PlaybackCapabilityResult.NoFiniteCapabilityCap -> "No finite capability cap"
    }

fun PlaybackResolutionPolicy.debugCapabilityReason(): String =
    when (this) {
        PlaybackResolutionPolicy.NoCap -> "The profile supplied no finite decoder resolution bound"
        PlaybackResolutionPolicy.VerifiedDeviceCap -> "Verified decoder capability bound"
        PlaybackResolutionPolicy.QualityRung -> "Selected quality rung"
        PlaybackResolutionPolicy.UserSetting -> "User video-resolution setting"
        PlaybackResolutionPolicy.VerifiedDeviceAndQualityRung ->
            "Verified decoder capability bound and selected quality rung"
        PlaybackResolutionPolicy.VerifiedDeviceAndUserSetting ->
            "Verified decoder capability bound and user video-resolution setting"
        PlaybackResolutionPolicy.QualityRungAndUserSetting ->
            "Selected quality rung and user video-resolution setting"
        PlaybackResolutionPolicy.VerifiedDeviceQualityRungAndUserSetting ->
            "Verified decoder capability bound, selected quality rung, and user video-resolution setting"
    }

fun PlaybackBitrateConstraint.debugClientLimiterLabel(): String =
    when (this) {
        PlaybackBitrateConstraint.NoClientLimit -> "No client limit"
        is PlaybackBitrateConstraint.ExactUserLimit -> "Exact user limit: $bitrateBps bps"
        is PlaybackBitrateConstraint.AutoSessionLimit -> "Auto session limit: $bitrateBps bps"
    }

fun PlaybackRecoveryIntent.debugLabel(): String =
    when (this) {
        PlaybackRecoveryIntent.Initial -> "Initial request"
        PlaybackRecoveryIntent.Compatibility -> "Compatibility recovery"
        PlaybackRecoveryIntent.Quality -> "Auto quality recovery"
    }

fun PlayerFirstVideoOutputDebug.debugLabel(): String = "${state.debugLabel()} · ${evidence.debugLabel()}"

private fun PlaybackFirstVideoOutputState.debugLabel(): String =
    when (this) {
        PlaybackFirstVideoOutputState.Unsupported -> "Unsupported"
        PlaybackFirstVideoOutputState.Awaiting -> "Awaiting"
        PlaybackFirstVideoOutputState.Observed -> "Observed"
        PlaybackFirstVideoOutputState.TimedOut -> "Timed out"
    }

private fun VideoOutputEvidence.debugLabel(): String =
    when (this) {
        VideoOutputEvidence.Unsupported -> "No reliable evidence"
        VideoOutputEvidence.NativeFirstOutput -> "Native first-output callback"
        VideoOutputEvidence.DisplayedPictureCounter -> "Displayed-picture counter"
        VideoOutputEvidence.ReadyForDisplayBridge -> "Ready-for-display bridge"
    }

fun PlayerDebugInfo.configuredVlcTranscodeBudgetSummary(): String =
    configuredVlcTranscodeBudgetBps
        ?.takeIf { bitrate -> bitrate > 0L }
        ?.let { bitrate -> "${bitrate.debugMbpsLabel()} · Playback settings" }
        ?: "Use playback default"

fun PlaybackClientTrigger.debugLabel(): String =
    when (this) {
        PlaybackClientTrigger.NetworkRetry -> "Network retry"
        PlaybackClientTrigger.PlayerFailureFallback -> "Player failure fallback"
        PlaybackClientTrigger.BackendFallback -> "Player backend fallback"
        PlaybackClientTrigger.AudioActivationFallback -> "Audio activation fallback"
        PlaybackClientTrigger.SubtitleActivationFallback -> "Subtitle activation fallback"
        PlaybackClientTrigger.DecodeCapabilityCap -> "Decoder profile incompatibility"
        PlaybackClientTrigger.UserResolutionLimit -> "User resolution limit"
    }

fun PlaybackHealthSignalKind.debugLabel(): String =
    when (this) {
        PlaybackHealthSignalKind.SlowStartup -> "Slow startup"
        PlaybackHealthSignalKind.LongBuffering -> "Long buffering"
        PlaybackHealthSignalKind.CumulativeBuffering -> "Cumulative buffering"
        PlaybackHealthSignalKind.RepeatedStalls -> "Repeated stalls"
        PlaybackHealthSignalKind.DroppedFrames -> "Dropped frames"
        PlaybackHealthSignalKind.NoVideoOutput -> "No video output"
    }

fun PlaybackHealthThresholdClass.debugLabel(): String =
    when (this) {
        PlaybackHealthThresholdClass.SlowStartup10Seconds -> "10s startup"
        PlaybackHealthThresholdClass.NoVideoOutput5Seconds -> "5s without video output"
        PlaybackHealthThresholdClass.NoVideoOutput20Seconds -> "20s without video output"
        PlaybackHealthThresholdClass.LongBuffering5Seconds -> "5s buffering"
        PlaybackHealthThresholdClass.CumulativeBuffering10SecondsIn60Seconds -> "10s in 60s buffering"
        PlaybackHealthThresholdClass.RepeatedStalls3In60Seconds -> "3 stalls in 60s"
        PlaybackHealthThresholdClass.DroppedFrames2PerSecondFor20SecondsIn60Seconds -> "2 fps for 20s in 60s"
    }

fun Long.toMiBLabel(): String = "${this / (1024.0 * 1024.0)} MiB"

fun Double.debugFrameRate(): String = (round(this * 1_000.0) / 1_000.0).toString()

fun Long.debugMbpsLabel(): String {
    val mbps = this.toDouble() / 1_000_000.0
    val rounded = (mbps * 10).toLong() / 10.0
    return "$rounded Mbps"
}

fun Long.debugSecondsLabel(): String {
    val seconds = this.toDouble() / 1_000.0
    val rounded = (seconds * 10).toLong() / 10.0
    return "$rounded s"
}

internal fun Double.debugMilliseconds(): String = "${(round(this * 100.0) / 100.0)} ms"

internal fun Double.debugPresentedRate(): String = "${(round(this * 1_000.0) / 1_000.0)} fps"

fun PlayerDebugInfo.subtitleRenderSummary(): String {
    val track =
        listOfNotNull(
            subtitleLabel,
            subtitleLanguage,
            subtitleStreamIndex?.let { index -> "stream $index" },
        ).joinToString(" · ")
    val mode = subtitleRenderMode.debugLabel()
    return listOf(subtitleRenderStatus.debugLabel(), mode, track, subtitleRenderReason)
        .filter { value -> value.isNotBlank() }
        .joinToString(" · ")
}

fun SubtitleRenderMode.debugLabel(): String =
    when (this) {
        SubtitleRenderMode.None -> "Off"
        SubtitleRenderMode.LocalExternalText -> "Local external text"
        SubtitleRenderMode.LocalEmbeddedText -> "Local embedded text"
        SubtitleRenderMode.LocalEmbeddedBitmap -> "Local embedded bitmap"
        SubtitleRenderMode.LocalHlsText -> "Local HLS text"
        SubtitleRenderMode.ServerBurnedIn -> "Server burned-in"
        SubtitleRenderMode.Unknown -> "Unknown"
    }

fun SubtitleRenderStatus.debugLabel(): String =
    when (this) {
        SubtitleRenderStatus.Off -> "Off"
        SubtitleRenderStatus.Pending -> "Pending"
        SubtitleRenderStatus.Active -> "Active"
        SubtitleRenderStatus.Unavailable -> "Unavailable"
    }
