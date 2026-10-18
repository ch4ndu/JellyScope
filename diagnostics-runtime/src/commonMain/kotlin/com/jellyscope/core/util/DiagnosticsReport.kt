// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.CapabilityEvidenceSource
import com.jellyscope.core.domain.playback.CodecCapabilityEvidence
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DiagnosticsCodecAllowlist
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.VideoCodecConstraints
import com.jellyscope.core.domain.playback.VideoCodecResolution
import com.jellyscope.core.domain.playback.VideoRangeCapabilities
import com.jellyscope.core.playback.DiagnosticsSourceDescriptor

object DiagnosticsReport {
    /**
     * Builds the upload header from closed technical fields only.
     *
     * This boundary is deliberately non-throwing: diagnostics must remain
     * uploadable when a platform probe or environment value is unavailable.
     */
    fun build(
        environment: DiagnosticsEnvironment,
        backend: PlayerBackend,
        capabilities: DeviceDecodingCapabilities?,
        source: DiagnosticsSourceDescriptor?,
    ): String =
        try {
            buildReport(environment, backend, capabilities, source)
        } catch (_: Throwable) {
            fallbackReport(backend)
        }

    private fun buildReport(
        environment: DiagnosticsEnvironment,
        backend: PlayerBackend,
        capabilities: DeviceDecodingCapabilities?,
        source: DiagnosticsSourceDescriptor?,
    ): String =
        buildString {
            appendLine("=== JellyScope Diagnostics Snapshot ===")
            appendLine("environment.platform=${safeEnvironmentValue(environment.platform)}")
            appendLine("environment.osVersion=${safeEnvironmentValue(environment.osVersion)}")
            appendLine("environment.appVersion=${safeEnvironmentValue(environment.appVersion)}")
            appendLine("environment.deviceModel=${safeEnvironmentValue(environment.deviceModel)}")
            appendLine("capabilities.backend=${backend.name.lowercase()}")
            appendCapabilities(capabilities)
            appendSource(source)
            append("=== End JellyScope Diagnostics Snapshot ===")
        }

    private fun StringBuilder.appendCapabilities(capabilities: DeviceDecodingCapabilities?) {
        if (capabilities == null) {
            appendLine("capabilities.status=capabilities unavailable")
            return
        }
        appendLine("capabilities.status=available")
        appendLine("capabilities.supportsHdr=${capabilities.supportsHdr}")
        appendLine("capabilities.supportsDolbyVision=${capabilities.supportsDolbyVision}")
        appendLine("capabilities.maxAudioChannels=${capabilities.maxAudioChannels.renderNullable()}")
        appendLine("capabilities.audioCodecs=${capabilities.audioCodecs.renderCodecs()}")
        appendLine("capabilities.audioPassthroughCodecs=${capabilities.audioPassthroughCodecs.renderCodecs()}")
        val audioCodecValues =
            capabilities.audioCodecs +
                capabilities.audioPassthroughCodecs +
                capabilities.audioCodecEvidence.keys
        audioCodecValues
            .map(DiagnosticsCodecAllowlist::renderCodec)
            .distinct()
            .sorted()
            .forEach { renderedCodec ->
                val sourceCodec = audioCodecValues.firstOrNull { codec -> DiagnosticsCodecAllowlist.renderCodec(codec) == renderedCodec }
                appendCodecEvidence("audio", renderedCodec, sourceCodec?.let(capabilities.audioCodecEvidence::get))
            }
        appendLine(
            "capabilities.containers=" +
                capabilities.directPlayProfiles
                    .flatMap { profile -> profile.containers }
                    .renderContainers(),
        )

        val videoCodecKeys =
            (
                capabilities.videoCodecs +
                    capabilities.videoResolutionsByCodec.keys +
                    capabilities.videoConstraintsByCodec.keys +
                    capabilities.videoRangeCapabilitiesByCodec.keys +
                    capabilities.videoCodecEvidence.keys
            ).map(DiagnosticsCodecAllowlist::renderCodec)
                .distinct()
                .sorted()
        appendLine("capabilities.videoCodecs=${videoCodecKeys.renderList()}")
        videoCodecKeys.forEach { renderedCodec ->
            val sourceCodec =
                (
                    capabilities.videoCodecs +
                        capabilities.videoResolutionsByCodec.keys +
                        capabilities.videoConstraintsByCodec.keys +
                        capabilities.videoRangeCapabilitiesByCodec.keys +
                        capabilities.videoCodecEvidence.keys
                ).firstOrNull { codec -> DiagnosticsCodecAllowlist.renderCodec(codec) == renderedCodec }
            val resolution = sourceCodec?.let(capabilities.videoResolutionsByCodec::get)
            val constraints = sourceCodec?.let(capabilities.videoConstraintsByCodec::get)
            val ranges = sourceCodec?.let(capabilities.videoRangeCapabilitiesByCodec::get)
            val evidence = sourceCodec?.let(capabilities.videoCodecEvidence::get)
            appendVideoCodec(renderedCodec, resolution, constraints, ranges, evidence)
        }

        if (capabilities.droppedVideoCodecs.isEmpty()) {
            appendLine("capabilities.droppedVideoCodecs=none")
        } else {
            capabilities.droppedVideoCodecs
                .sortedBy { dropped -> DiagnosticsCodecAllowlist.renderCodec(dropped.codec) }
                .forEach { dropped ->
                    appendLine(
                        "capabilities.droppedVideoCodec=" +
                            "${DiagnosticsCodecAllowlist.renderCodec(dropped.codec)} " +
                            "reason=${dropped.reason.name}",
                    )
                }
        }
    }

    private fun StringBuilder.appendVideoCodec(
        codec: String,
        resolution: VideoCodecResolution?,
        constraints: VideoCodecConstraints?,
        ranges: VideoRangeCapabilities?,
        evidence: CodecCapabilityEvidence?,
    ) {
        appendCodecEvidence("video", codec, evidence)
        appendLine("capabilities.video.$codec.maxWidth=${resolution?.maxWidth.renderNullable()}")
        appendLine("capabilities.video.$codec.maxHeight=${resolution?.maxHeight.renderNullable()}")
        appendLine("capabilities.video.$codec.maxFrameArea=${resolution?.maxFrameArea.renderNullable()}")
        appendLine("capabilities.video.$codec.maxFrameAreaPerSecond=${resolution?.maxFrameAreaPerSecond.renderNullable()}")
        appendLine("capabilities.video.$codec.maxLevel=${constraints?.maxLevel.renderNullable()}")
        appendLine("capabilities.video.$codec.maxBitDepth=${constraints?.maxBitDepth.renderNullable()}")
        appendLine(
            "capabilities.video.$codec.supportedProfiles=" +
                constraints
                    ?.supportedProfiles
                    .orEmpty()
                    .map(::renderVideoProfile)
                    .distinct()
                    .sorted()
                    .renderList(),
        )
        appendLine("capabilities.video.$codec.supportsHdr10=${ranges?.supportsHdr10 ?: false}")
        appendLine("capabilities.video.$codec.supportsHdr10Plus=${ranges?.supportsHdr10Plus ?: false}")
        appendLine("capabilities.video.$codec.supportsHlg=${ranges?.supportsHlg ?: false}")
        appendLine("capabilities.video.$codec.supportsDolbyVision=${ranges?.supportsDolbyVision ?: false}")
        appendLine("capabilities.video.$codec.supportsDolbyVisionWithEL=${ranges?.supportsDolbyVisionWithEL ?: false}")
    }

    private fun StringBuilder.appendCodecEvidence(
        kind: String,
        codec: String,
        evidence: CodecCapabilityEvidence?,
    ) {
        appendLine("capabilities.$kind.$codec.decodeEvidence=${evidence?.decodeSources.renderEvidence()}")
        appendLine("capabilities.$kind.$codec.finiteLimitEvidence=${evidence?.finiteLimitSources.renderEvidence()}")
    }

    private fun StringBuilder.appendSource(source: DiagnosticsSourceDescriptor?) {
        if (source == null) {
            appendLine("playbackFailure.status=no recent playback failure")
            return
        }
        appendLine("playbackFailure.status=available")
        appendLine("playbackFailure.videoCodec=${DiagnosticsCodecAllowlist.renderCodec(source.videoCodec)}")
        appendLine("playbackFailure.width=${source.width.renderNullable()}")
        appendLine("playbackFailure.height=${source.height.renderNullable()}")
        appendLine("playbackFailure.frameRate=${source.frameRate.renderNullableDouble()}")
        appendLine("playbackFailure.bitRate=${source.bitRate.renderNullable()}")
        appendLine("playbackFailure.bitDepth=${source.bitDepth.renderNullable()}")
        appendLine("playbackFailure.videoRangeType=${renderVideoRangeType(source.videoRangeType)}")
        appendLine("playbackFailure.container=${DiagnosticsCodecAllowlist.renderContainer(source.container)}")
        appendLine("playbackFailure.audioCodec=${DiagnosticsCodecAllowlist.renderCodec(source.audioCodec)}")
        appendLine("playbackFailure.channelLayout=${DiagnosticsCodecAllowlist.renderChannelLayout(source.channelLayout)}")
    }

    private fun fallbackReport(backend: PlayerBackend): String =
        buildString {
            appendLine("=== JellyScope Diagnostics Snapshot ===")
            appendLine("environment.status=environment unavailable")
            appendLine("capabilities.backend=${backend.name.lowercase()}")
            appendLine("capabilities.status=capabilities unavailable")
            appendLine("playbackFailure.status=no recent playback failure")
            append("=== End JellyScope Diagnostics Snapshot ===")
        }
}

private val allowedVideoProfiles =
    setOf("baseline", "constrained baseline", "main", "main 10", "high", "high 10")

private val allowedVideoRangeTypes =
    setOf(
        "sdr",
        "hdr10",
        "hdr10plus",
        "hlg",
        "dovi",
        "doviwithhdr10",
        "doviwithhdr10plus",
        "doviwithhlg",
        "doviwithsdr",
        "doviwithel",
        "doviwithelhdr10plus",
    )

private val allowedEnvironmentPunctuation = setOf('.', '_', '-', '+', '(', ')', ',')

private fun safeEnvironmentValue(value: String): String {
    val normalized = value.trim()
    return normalized.takeIf { candidate ->
        candidate.length in 1..120 &&
            candidate.all { character ->
                character.isLetterOrDigit() ||
                    character == ' ' ||
                    character in allowedEnvironmentPunctuation
            } &&
            !candidate.contains("token", ignoreCase = true) &&
            !candidate.contains("apikey", ignoreCase = true) &&
            !candidate.contains("secret", ignoreCase = true) &&
            !candidate.contains("authorization", ignoreCase = true) &&
            !candidate.contains("bearer", ignoreCase = true)
    } ?: "unavailable"
}

private fun renderVideoProfile(value: String): String =
    value
        .trim()
        .lowercase()
        .takeIf { profile -> profile in allowedVideoProfiles }
        ?: DiagnosticsCodecAllowlist.UNRECOGNIZED

private fun renderVideoRangeType(value: String?): String =
    value
        ?.trim()
        ?.lowercase()
        ?.replace("_", "")
        ?.replace("-", "")
        ?.takeIf { range -> range in allowedVideoRangeTypes }
        ?: DiagnosticsCodecAllowlist.UNRECOGNIZED

private fun List<String>.renderCodecs(): String =
    map(DiagnosticsCodecAllowlist::renderCodec)
        .distinct()
        .sorted()
        .renderList()

private fun List<String>.renderContainers(): String =
    map(DiagnosticsCodecAllowlist::renderContainer)
        .distinct()
        .sorted()
        .renderList()

private fun List<String>.renderList(): String = ifEmpty { listOf("none") }.joinToString(",")

private fun Set<CapabilityEvidenceSource>?.renderEvidence(): String =
    orEmpty()
        .ifEmpty { setOf(CapabilityEvidenceSource.Unknown) }
        .map(CapabilityEvidenceSource::name)
        .sorted()
        .joinToString(",")

private fun Int?.renderNullable(): String = this?.toString() ?: "unavailable"

private fun Long?.renderNullable(): String = this?.toString() ?: "unavailable"

private fun Double?.renderNullableDouble(): String =
    this
        ?.takeIf(Double::isFinite)
        ?.toString()
        ?: "unavailable"
