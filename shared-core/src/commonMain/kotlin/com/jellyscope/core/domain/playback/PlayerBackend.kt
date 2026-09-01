// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.DownloadArtifactKind

/** User-selectable player backends. [Auto] is resolved before playback starts. */
enum class PlayerBackend {
    Auto,
    AVPlayer,
    VlcKit,
    ExoPlayer,
    Mpv,
    LibVlc,
}

fun PlayerBackend.isVlcFamily(): Boolean = this == PlayerBackend.LibVlc || this == PlayerBackend.VlcKit

enum class PlayerBackendPlatform {
    Android,
    Apple,
    Desktop,
}

data class PlayerBackendPolicy(
    val platform: PlayerBackendPlatform,
    val defaultBackend: PlayerBackend,
    val visibleBackends: List<PlayerBackend>,
) {
    init {
        require(defaultBackend in visibleBackends) {
            "The default player backend must be visible on its platform"
        }
    }

    /** Converts nullable/legacy persisted values to a backend the platform can own. */
    fun normalizePersisted(value: PlayerBackend?): PlayerBackend = value?.takeIf { backend -> backend in visibleBackends } ?: defaultBackend

    /** Concrete backends that can be selected for an already-running session. */
    val concreteBackends: List<PlayerBackend>
        get() = visibleBackends.filterNot { backend -> backend == PlayerBackend.Auto }

    /** The platform fallback target after an explicit active-session switch. */
    val concreteDefaultBackend: PlayerBackend
        get() = defaultBackend.takeUnless { backend -> backend == PlayerBackend.Auto } ?: concreteBackends.first()
}

fun androidPlayerBackendPolicy(): PlayerBackendPolicy =
    PlayerBackendPolicy(
        platform = PlayerBackendPlatform.Android,
        defaultBackend = PlayerBackend.ExoPlayer,
        visibleBackends = listOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv, PlayerBackend.LibVlc),
    )

fun applePlayerBackendPolicy(): PlayerBackendPolicy =
    PlayerBackendPolicy(
        platform = PlayerBackendPlatform.Apple,
        defaultBackend = PlayerBackend.AVPlayer,
        visibleBackends = listOf(PlayerBackend.Auto, PlayerBackend.AVPlayer, PlayerBackend.VlcKit),
    )

fun desktopPlayerBackendPolicy(): PlayerBackendPolicy =
    PlayerBackendPolicy(
        platform = PlayerBackendPlatform.Desktop,
        defaultBackend = PlayerBackend.Mpv,
        visibleBackends = listOf(PlayerBackend.Mpv, PlayerBackend.LibVlc),
    )

/** Source metadata used to choose a player backend. */
data class BackendSourceDescriptor(
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val isHdrOrDolbyVision: Boolean,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Double? = null,
)

enum class OriginalDownloadPlaybackCompatibility {
    Compatible,
    Unknown,
    Unsupported,
}

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
 * Whether this backend can decode [codec] as an in-player DirectPlay audio switch.
 *
 * Mirrors the audio half of the DirectPlayProfile sent to the server, which is the
 * check the server performs for the *default* track but never performs for a
 * mid-session switch: selecting a native elementary stream succeeds whenever the
 * stream exists in the container, so index readback is not proof of decoding and a
 * switch to an undecodable codec plays silently.
 *
 * Fails open only where the answer is genuinely unknown: absent capabilities,
 * absent profiles, and missing/blank codec metadata all stay admissible, so a
 * backend that declares nothing is never second-guessed. A codec the profile does
 * not list IS treated as inadmissible — the resulting transcode still plays audio,
 * whereas admitting it plays silence, so that is the safe direction.
 */
fun DeviceDecodingCapabilities.admitsDirectPlayAudioCodec(codec: String?): Boolean {
    if (directPlayProfiles.isEmpty()) return true
    val family = normalizeAudioCodecFamily(codec) ?: return true
    return directPlayProfiles.any { profile ->
        profile.audioCodecs.any { value -> normalizeAudioCodecFamily(value) == family }
    }
}

private fun String?.canonicalBackendValue(): String? =
    this
        ?.trim()
        ?.lowercase()
        ?.takeIf { value -> value.isNotBlank() }
