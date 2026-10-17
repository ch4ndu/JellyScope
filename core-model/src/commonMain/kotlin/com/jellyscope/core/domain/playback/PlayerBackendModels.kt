// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

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
