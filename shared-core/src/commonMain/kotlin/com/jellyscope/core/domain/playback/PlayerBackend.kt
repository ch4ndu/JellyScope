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
)

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
 * Original files keep the user's normal selected backend on every supported platform. The only
 * special case is a converted local-HLS package on Apple, which must use VLCKit; this helper never
 * authorizes AVPlayer for an app-authored HLS package and never consults or mutates persisted
 * preferences.
 */
fun resolveOfflinePlaybackBackend(
    platform: PlayerBackendPlatform,
    artifactKind: DownloadArtifactKind,
    resolvedBackend: PlayerBackend,
): PlayerBackend {
    require(resolvedBackend != PlayerBackend.Auto) { "Offline backend must already be resolved." }
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
 * Original-file playback keeps the normal controller replacement fallback ladder. Apple
 * LocalHlsPackage playback has exact VLCKit ownership and must never hand an app-authored HLS
 * package to another native backend. Android and desktop keep their normal fallback behavior.
 */
fun offlineControllerFallbackAllowed(
    artifactKind: DownloadArtifactKind,
    resolvedBackend: PlayerBackend,
): Boolean = artifactKind != DownloadArtifactKind.LocalHlsPackage || resolvedBackend != PlayerBackend.VlcKit

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
