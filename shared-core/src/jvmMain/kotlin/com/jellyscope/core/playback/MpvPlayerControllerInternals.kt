// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.security.CredentialOriginGuard
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.sun.jna.Memory
import com.sun.jna.Pointer

internal data class ActiveMpv(
    val lib: LibMpv,
    val ctx: Pointer,
)

internal data class MpvCompletionObservation(
    val generation: Long,
    val completed: Boolean,
    val unresolvedEntryFallbackArmed: Boolean = false,
)

/**
 * Correlates mpv's sticky EOF property with the prepare generation and
 * replacement playlist entry that owns it. The controller serializes every
 * mutation under its lifecycle lock.
 */
internal class MpvCompletionReadiness {
    private var generation: Long? = null
    private var expectedPlaylistEntryId: Long? = null
    private var matchingStartObserved = false
    private var awaitingNonEofFallback = false
    private var armed = false
    private var bound = false

    fun beginPrepare(generation: Long) {
        this.generation = generation
        expectedPlaylistEntryId = null
        matchingStartObserved = false
        awaitingNonEofFallback = false
        armed = false
        bound = false
    }

    fun bindPlaylistEntry(
        generation: Long,
        playlistEntryId: Long?,
    ) {
        if (generation != this.generation) return
        expectedPlaylistEntryId = playlistEntryId
        matchingStartObserved = false
        awaitingNonEofFallback = playlistEntryId == null
        armed = false
        bound = true
    }

    fun onStartFile(
        generation: Long,
        playlistEntryId: Long,
    ) {
        if (generation != this.generation) return
        matchingStartObserved = playlistEntryId == expectedPlaylistEntryId
    }

    fun onFileLoaded(
        generation: Long,
        currentPlaylistEntryId: Long?,
    ) {
        if (generation != this.generation) return
        val expected = expectedPlaylistEntryId ?: return
        if (matchingStartObserved && (currentPlaylistEntryId == null || currentPlaylistEntryId == expected)) {
            armed = true
            awaitingNonEofFallback = false
        }
    }

    fun observeEof(
        generation: Long,
        eofReached: Boolean?,
    ): MpvCompletionObservation? {
        if (generation != this.generation || !bound) return null
        if (armed) {
            return MpvCompletionObservation(
                generation = generation,
                completed = eofReached == true,
            )
        }
        if (awaitingNonEofFallback && eofReached == false) {
            awaitingNonEofFallback = false
            armed = true
            return MpvCompletionObservation(
                generation = generation,
                completed = false,
                unresolvedEntryFallbackArmed = true,
            )
        }
        return MpvCompletionObservation(
            generation = generation,
            completed = false,
        )
    }

    fun isCurrent(observation: MpvCompletionObservation): Boolean = observation.generation == generation

    fun isCurrentGeneration(generation: Long): Boolean = generation == this.generation

    fun isArmed(generation: Long): Boolean = generation == this.generation && armed

    fun invalidate(generation: Long? = null) {
        if (generation != null && generation != this.generation) return
        this.generation = null
        expectedPlaylistEntryId = null
        matchingStartObserved = false
        awaitingNonEofFallback = false
        armed = false
        bound = false
    }
}

internal fun classifyMpvPlaybackError(
    message: String,
    detail: String? = null,
    nativeCode: Long? = null,
): PlaybackError {
    when (nativeCode) {
        LibMpv.MPV_ERROR_LOADING_FAILED.toLong() -> return PlaybackError.Network
        LibMpv.MPV_ERROR_NOTHING_TO_PLAY.toLong(),
        LibMpv.MPV_ERROR_UNKNOWN_FORMAT.toLong(),
        LibMpv.MPV_ERROR_UNSUPPORTED.toLong(),
        -> return PlaybackError.UnsupportedMedia
    }
    val text = listOfNotNull(message, detail).joinToString(" ").lowercase()
    return when {
        text.containsAny(
            "audio output",
            "audio device",
            "ao ",
            "alsa",
            "coreaudio",
            "wasapi",
            "pulseaudio",
        ) -> PlaybackError.AudioOutput
        text.containsAny(
            "network",
            "http",
            "connection",
            "timeout",
            "timed out",
            "resolve",
            "dns",
            "server",
            "unauthorized",
            "forbidden",
            "not found",
            "io error",
        ) -> PlaybackError.Network
        text.containsAny("decode", "decoder", "hwdec") -> PlaybackError.Decoder
        text.containsAny(
            "unsupported",
            "format",
            "demuxer",
            "codec",
            "invalid data",
            "unrecognized",
        ) -> PlaybackError.UnsupportedMedia
        else -> PlaybackError.Unknown
    }
}

private fun String.containsAny(vararg needles: String): Boolean = needles.any { needle -> contains(needle) }

internal enum class MpvHardwareDecodePolicy(
    val optionValue: String,
) {
    AutoSafe("auto-safe"),
    VideoToolboxCopy("videotoolbox-copy"),
}

/** The shipped default for the macOS mpv IOSurface presentation route. */
internal val MPV_PRODUCTION_HARDWARE_DECODE_POLICY = MpvHardwareDecodePolicy.VideoToolboxCopy

internal fun resolveMpvHardwareDecodePolicy(
    osName: String,
    surfaceKind: MpvOpenGlSurfaceKind?,
): MpvHardwareDecodePolicy {
    val isMacOs =
        osName.contains("mac", ignoreCase = true) ||
            osName.contains("darwin", ignoreCase = true)
    if (!isMacOs || surfaceKind != MpvOpenGlSurfaceKind.IOSurface) {
        return MpvHardwareDecodePolicy.AutoSafe
    }
    return MPV_PRODUCTION_HARDWARE_DECODE_POLICY
}

@Suppress("UNCHECKED_CAST")
internal fun renderParams(vararg entries: Pair<Int, Pointer?>): Array<MpvRenderParam> {
    val params = MpvRenderParam().toArray(entries.size + 1) as Array<MpvRenderParam>
    entries.forEachIndexed { index, entry ->
        params[index].type = entry.first
        params[index].data = entry.second
    }
    params[entries.size].type = 0
    params[entries.size].data = null
    params.forEach { param -> param.write() }
    return params
}

internal fun nativeString(value: String): Memory =
    Memory(value.toByteArray(Charsets.UTF_8).size.toLong() + 1L).apply {
        setString(0, value, Charsets.UTF_8.name())
    }

internal fun String.stripAuthQueryParams(): String = CredentialOriginGuard.stripAuthQueryParams(this)

internal fun String.isUnderServer(serverUrl: String): Boolean = CredentialOriginGuard(serverUrl).mayAttachCredentials(this)

internal fun applyMpvStartPosition(
    active: ActiveMpv,
    startPositionMs: Long,
): Boolean =
    active.lib.mpv_set_option_string(
        active.ctx,
        "start",
        if (startPositionMs > 0L) {
            "+${startPositionMs / MILLISECONDS_PER_SECOND}"
        } else {
            "none"
        },
    ) == 0

internal fun Double.toPlaybackMillis(): Long? =
    takeIf { value -> value.isFinite() && value >= 0.0 }
        ?.let { value -> (value * MILLISECONDS_PER_SECOND).toLong().coerceAtLeast(0L) }

internal fun MpvRenderTarget.isValidFor(
    expectedWidth: Int,
    expectedHeight: Int,
    expectedGeneration: Long,
): Boolean {
    if (pixelAddress == 0L || width <= 0 || height <= 0 || stride <= 0) return false
    if (width != expectedWidth || height != expectedHeight || generation != expectedGeneration) return false
    val minimumStride = width.toLong() * BYTES_PER_PIXEL
    if (minimumStride > Int.MAX_VALUE || stride.toLong() < minimumStride) return false
    return stride.toLong() <= Long.MAX_VALUE / height.toLong()
}

internal const val STATE_POLL_INTERVAL_MS = 250L
internal const val RUNTIME_DIAGNOSTICS_POLL_TICKS = 3
internal const val BITS_PER_BYTE = 8L
internal const val MILLISECONDS_PER_SECOND = 1_000.0
internal const val BYTES_PER_PIXEL = 4
internal const val DOUBLE_BYTES = 8L
internal const val FLAG_BYTES = 4L
internal const val SW_SIZE_BYTES = 8L
internal const val SW_RENDER_API = "sw"
internal const val SW_FRAME_FORMAT = "bgr0"
internal const val JELLYFIN_AUTHORIZATION_HEADER_NAME = "Authorization"
internal const val OPENGL_SURFACE_FALLBACK_TIMEOUT_MS = 2_000L
internal const val OPENGL_RENDER_SHUTDOWN_TIMEOUT_MS = 2_000L

/**
 * Bounded wait for outstanding engine-use leases before the native destroy,
 * mirroring [OPENGL_RENDER_SHUTDOWN_TIMEOUT_MS]. Expiring here defers the
 * destroy to the last lease release; it never quarantines the engine.
 */
internal const val ENGINE_LEASE_DRAIN_TIMEOUT_MS = 2_000L
internal const val ENGINE_LEASE_DRAIN_POLL_MS = 5L
internal const val OPENGL_RENDER_API = "opengl"
internal const val MPV_PRESENTATION_OPENGL = "OpenGL Render API"
internal const val MPV_PRESENTATION_SOFTWARE = "Software"
internal val MPV_COMMON_INIT_OPTIONS =
    listOf(
        "hwdec" to "auto-safe",
        "keep-open" to "yes",
        "idle" to "yes",
        "terminal" to "no",
        "track-auto-selection" to "no",
    )

internal val MPV_VIDEO_OUTPUT_INIT_OPTIONS =
    listOf(
        "vo" to "libmpv",
    )

internal val MPV_BEST_EFFORT_OPTIONS =
    listOf(
        "tone-mapping" to "bt.2390",
        "target-peak" to "auto",
    )

internal val controllerLogger = diagnosticLogger(DiagnosticTag.MpvPlayerController)
