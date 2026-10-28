// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.DownloadArtifactKind
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Resolves a user preference and source description to one concrete backend.
 *
 * [PlayerBackend.Auto] is never returned. Explicit concrete preferences take
 * precedence over the automatic AVPlayer-first policy.
 */
fun resolvePlayerBackend(
    defaultBackend: PlayerBackend,
    itemOverride: PlayerBackend?,
    source: BackendSourceDescriptor,
    avPlayerCapabilities: DeviceDecodingCapabilities,
    backendPolicy: PlayerBackendPolicy = applePlayerBackendPolicy(),
): PlayerBackend {
    itemOverride
        ?.takeUnless { backend -> backend == PlayerBackend.Auto }
        ?.let { backend -> return backendPolicy.normalizePersisted(backend) }

    val normalizedDefault = backendPolicy.normalizePersisted(defaultBackend)
    if (normalizedDefault != PlayerBackend.Auto) {
        return normalizedDefault
    }

    // The Auto heuristic below names Apple backends, so it only applies to the
    // Apple platform. Anywhere else, Auto is the platform's own engine and must
    // not resolve to a backend that platform cannot construct.
    if (backendPolicy.platform != PlayerBackendPlatform.Apple) {
        return normalizedDefault
    }

    if (source.isHdrOrDolbyVision) {
        return PlayerBackend.AVPlayer
    }

    return if (avPlayerCapabilities.canDirectPlay(source)) {
        PlayerBackend.AVPlayer
    } else {
        PlayerBackend.VlcKit
    }
}

/**
 * Resolves the backend policy for one already-resolved Offline plan.
 *
 * A platform-required backend takes ownership of every offline artifact. Without that requirement,
 * original files keep the resolved backend and Apple local-HLS packages use VLCKit. This helper
 * never consults or mutates persisted preferences.
 */
fun resolveOfflinePlaybackBackend(
    platform: PlayerBackendPlatform,
    artifactKind: DownloadArtifactKind,
    resolvedBackend: PlayerBackend,
    requiredBackend: PlayerBackend? = null,
): PlayerBackend {
    require(resolvedBackend != PlayerBackend.Auto) { "Offline backend must already be resolved." }
    require(requiredBackend != PlayerBackend.Auto) { "Required offline backend must be concrete." }
    if (requiredBackend != null) return requiredBackend
    return if (
        platform == PlayerBackendPlatform.Apple &&
        artifactKind == DownloadArtifactKind.LocalHlsPackage
    ) {
        PlayerBackend.VlcKit
    } else {
        resolvedBackend
    }
}

/**
 * A platform-required offline backend has exact controller ownership and cannot fall back. Without
 * that requirement, local-HLS playback resolved to VLCKit also stays on VLCKit while original-file,
 * Android, and desktop playback retain the normal fallback behavior.
 */
fun offlineControllerFallbackAllowed(
    artifactKind: DownloadArtifactKind,
    resolvedBackend: PlayerBackend,
    requiredBackend: PlayerBackend? = null,
): Boolean =
    requiredBackend == null &&
        (artifactKind != DownloadArtifactKind.LocalHlsPackage || resolvedBackend != PlayerBackend.VlcKit)

fun evaluateOriginalDownloadPlaybackCompatibility(
    source: BackendSourceDescriptor,
    capabilities: DeviceDecodingCapabilities,
): OriginalDownloadPlaybackCompatibility {
    val codec = canonicalVideoCodec(source.videoCodec) ?: return OriginalDownloadPlaybackCompatibility.Unknown
    val declaredCodecs = capabilities.videoCodecs.mapNotNull(::canonicalVideoCodec).toSet()
    if (declaredCodecs.isNotEmpty() && codec !in declaredCodecs) {
        return OriginalDownloadPlaybackCompatibility.Unsupported
    }
    val bound =
        capabilities.videoResolutionsByCodec.entries
            .firstOrNull { (candidate, _) -> canonicalVideoCodec(candidate) == codec }
            ?.value
            ?: return OriginalDownloadPlaybackCompatibility.Unknown
    if (
        bound.maxWidth == null &&
        bound.maxHeight == null &&
        bound.maxFrameArea == null &&
        bound.maxFrameAreaPerSecond == null
    ) {
        return OriginalDownloadPlaybackCompatibility.Unknown
    }
    val width =
        source.videoWidth?.takeIf { value -> value > 0 }
            ?: return OriginalDownloadPlaybackCompatibility.Unknown
    val height =
        source.videoHeight?.takeIf { value -> value > 0 }
            ?: return OriginalDownloadPlaybackCompatibility.Unknown
    val frameArea = blockPaddedArea(width, height)
    if (
        bound.maxWidth?.let { maximum -> width > maximum } == true ||
        bound.maxHeight?.let { maximum -> height > maximum } == true ||
        bound.maxFrameArea?.let { maximum -> frameArea > maximum } == true
    ) {
        return OriginalDownloadPlaybackCompatibility.Unsupported
    }
    val throughput = bound.maxFrameAreaPerSecond
    if (throughput != null) {
        val frameRate =
            source.videoFrameRate?.takeIf { value -> value.isFinite() && value > 0.0 }
                ?: return OriginalDownloadPlaybackCompatibility.Unknown
        if (frameArea.toDouble() * frameRate > throughput.toDouble()) {
            return OriginalDownloadPlaybackCompatibility.Unsupported
        }
    }
    return OriginalDownloadPlaybackCompatibility.Compatible
}

private fun DeviceDecodingCapabilities.canDirectPlay(source: BackendSourceDescriptor): Boolean {
    val container = source.container.canonicalBackendValue() ?: return false
    val videoCodec = source.videoCodec.canonicalBackendValue() ?: return false
    val audioCodec = source.audioCodec.canonicalBackendValue() ?: return false

    return directPlayProfiles.any { profile ->
        profile.containers.any { value -> value.canonicalBackendValue() == container } &&
            profile.videoCodecs.any { value -> value.canonicalBackendValue() == videoCodec } &&
            profile.audioCodecs.any { value -> value.canonicalBackendValue() == audioCodec }
    }
}

/**
 * The real Video DirectPlay declarations the device profile sends for this policy: each
 * declared profile intersected exactly with the effective video and audio codec lists,
 * dropping profiles whose container or video list ends up blank. The synthetic fallback
 * profile and the Audio-type profile are wire-only and deliberately not produced here.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun EffectivePlayerDevicePolicy.effectiveVideoDirectPlayProfiles(): List<DeviceDirectPlayProfile> {
    val videoCodecs = capabilities.videoCodecs.ifEmpty { directPlayBaselineVideoCodecs }
    val effectiveAudioCodecs = audioCodecs.ifEmpty { directPlayBaselineAudioCodecs }
    return capabilities.directPlayProfiles
        .map { profile ->
            DeviceDirectPlayProfile(
                containers = profile.containers,
                videoCodecs = profile.videoCodecs.filter { codec -> codec in videoCodecs },
                audioCodecs = profile.audioCodecs.filter { codec -> codec in effectiveAudioCodecs },
            )
        }.filter { profile ->
            profile.containers.joinToString(",").isNotBlank() &&
                profile.videoCodecs.joinToString(",").isNotBlank()
        }
}

/**
 * Whether this effective policy admits an in-player DirectPlay switch to an audio stream.
 *
 * Mirrors the audio half of the DirectPlay profile and audio codec conditions sent to the
 * server, which is the check the server performs for the *default* track but never for a
 * mid-session switch: selecting a native elementary stream succeeds whenever the stream
 * exists in the container, so index readback is not proof of decoding and a switch the
 * negotiated policy excludes plays silently or bypasses the user's audio setting.
 *
 * Fails open only where the answer is genuinely unknown: absent declared profiles,
 * missing/blank codec metadata and missing or non-positive channel counts stay admissible
 * in every audio mode, and an absent container does not narrow the profiles. A known codec
 * no applicable filtered profile lists, a known container no profile declares, or known
 * channels above the effective ceiling are inadmissible — the resulting transcode still
 * honours the policy, whereas admitting the switch does not.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun EffectivePlayerDevicePolicy.admitsDirectPlayAudioSwitch(
    codec: String?,
    channels: Int? = null,
    container: String? = null,
): Boolean {
    if (capabilities.directPlayProfiles.isEmpty()) return true
    val family = normalizeAudioCodecFamily(codec) ?: return true
    val sourceContainers = container.containerTokens()
    val codecAdmitted =
        effectiveVideoDirectPlayProfiles()
            .filter { profile ->
                sourceContainers.isEmpty() ||
                    profile.containers.any { declared -> declared.containerTokens().any { token -> token in sourceContainers } }
            }.any { profile ->
                profile.audioCodecs.any { declared -> normalizeAudioCodecFamily(declared) == family }
            }
    if (!codecAdmitted) return false
    val knownChannels = channels?.takeIf { value -> value > 0 } ?: return true
    val ceiling = audioChannelCeiling(codec.normalizedTrackMetadata(), family) ?: return true
    return knownChannels <= ceiling
}

/** Exact codec spelling's ceiling first; otherwise the strictest ceiling across its family. */
private fun EffectivePlayerDevicePolicy.audioChannelCeiling(
    codec: String?,
    family: String,
): Int? {
    val positiveCeilings =
        maxAudioChannelsByCodec.entries.filter { (_, channels) -> channels > 0 }
    return positiveCeilings
        .firstOrNull { (declared, _) -> declared.normalizedTrackMetadata() == codec }
        ?.value
        ?: positiveCeilings
            .filter { (declared, _) -> normalizeAudioCodecFamily(declared) == family }
            .minOfOrNull { (_, channels) -> channels }
}

private fun String?.containerTokens(): Set<String> =
    this
        ?.split(',')
        ?.mapNotNull { token -> token.canonicalBackendValue() }
        ?.toSet()
        .orEmpty()

private val directPlayBaselineVideoCodecs = listOf("h264")
private val directPlayBaselineAudioCodecs = listOf("aac", "mp3")

private fun String?.canonicalBackendValue(): String? =
    this
        ?.trim()
        ?.lowercase()
        ?.takeIf { value -> value.isNotBlank() }
