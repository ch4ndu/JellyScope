// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlinx.coroutines.flow.StateFlow

enum class MpvPresentationPreference {
    MacOsOpenGl,
    Software,
}

sealed interface MpvPresentationState {
    data object WaitingForOpenGlSurface : MpvPresentationState

    data class InitializingOpenGl(
        val surfaceGeneration: Long,
    ) : MpvPresentationState

    data class OpenGlActive(
        val surfaceGeneration: Long,
    ) : MpvPresentationState

    data class SoftwareActive(
        val fallbackReason: MpvOpenGlSurfaceUnavailableReason? = null,
    ) : MpvPresentationState

    data object Failed : MpvPresentationState

    data object Released : MpvPresentationState
}

enum class MpvOpenGlSurfaceUnavailableReason {
    UnsupportedOperatingSystem,
    ComponentNotDisplayable,
    UnsupportedJdkLayout,
    InaccessibleJdkInternals,
    UnexpectedPeer,
    OpenGlUnavailable,
    StaleGeneration,
    SurfaceTimeout,
    OpenGlInitializationFailed,
}

data class MpvOpenGlFramebuffer(
    val widthPx: Int,
    val heightPx: Int,
    val fboId: Int = 0,
    val flipY: Boolean = true,
)

/** The concrete app-owned surface receiving libmpv's OpenGL render output. */
enum class MpvOpenGlSurfaceKind {
    IOSurface,
    OpenGL,
}

/**
 * Opaque JVM-only capability for an app-owned macOS OpenGL surface.
 *
 * Implementations serialize resize, context-current, presentation, and close
 * operations. No AppKit address is exposed to libmpv as a window handle.
 */
interface MpvOpenGlRenderSurface {
    val generation: Long

    /**
     * Identifies the native surface implementation without parsing
     * [presentationLabel]. This type remains JVM-only and never enters common
     * playback state or product policy.
     */
    val surfaceKind: MpvOpenGlSurfaceKind
        get() = MpvOpenGlSurfaceKind.OpenGL

    val presentationLabel: String
        get() = MPV_PRESENTATION_OPENGL

    fun resolveGlSymbol(name: String): Long

    fun withCurrentContext(render: (MpvOpenGlFramebuffer) -> Boolean): Boolean

    fun close()
}

/** Opaque JVM-only software-render target owned by the desktop mpv surface. */
data class MpvRenderTarget(
    val pixelAddress: Long,
    val width: Int,
    val height: Int,
    val stride: Int,
    val generation: Long,
)

/** One successful libmpv render into the supplied target. */
data class MpvRenderResult(
    val generation: Long,
    val renderDurationNanos: Long,
)

enum class MpvOpenGlDetachDisposition {
    Terminated,
    Quarantined,
}

class MpvOpenGlDetachCallback(
    private val callback: (MpvOpenGlDetachDisposition) -> Unit,
) {
    operator fun invoke(disposition: MpvOpenGlDetachDisposition = MpvOpenGlDetachDisposition.Terminated) {
        callback(disposition)
    }
}

/** Implemented by the JVM PlayerController; consumed by PlayerSurface.jvm. */
interface MpvVideoOutput {
    val presentationState: StateFlow<MpvPresentationState>

    fun attachOpenGlSurface(surface: MpvOpenGlRenderSurface)

    fun openGlSurfaceUnavailable(
        generation: Long,
        reason: MpvOpenGlSurfaceUnavailableReason,
    )

    /**
     * Stops OpenGL presentation for [generation] before acknowledging that the
     * app-owned view/context may be closed. The callback may run off-thread.
     */
    fun detachOpenGlSurface(
        generation: Long,
        onDetached: MpvOpenGlDetachCallback,
    )

    /** Request a generation-safe redraw after an AppKit resize or scale change. */
    fun requestOpenGlRender(generation: Long)

    /** Resize the render target (surface px) and return its controller-owned generation. */
    fun resize(
        widthPx: Int,
        heightPx: Int,
    ): Long

    /** Toggle mpv pan-and-scan crop. `false` keeps fit/letterbox behavior. */
    fun setFillCrop(crop: Boolean)

    /** Retain and apply the shared UI's transient bottom subtitle-clearance state. */
    fun setSubtitleClearanceActive(active: Boolean)

    /** Render one frame into [target] if mpv has a new frame; else null. */
    fun renderFrameIfNeeded(target: MpvRenderTarget): MpvRenderResult?

    /** Record a completed front-buffer publication using a monotonic timestamp. */
    fun recordFramePublished(publishedAtNanos: Long)

    /** Set/clear a listener fired (off-thread) when mpv signals a new frame. */
    fun setOnFrameAvailable(listener: (() -> Unit)?)
}
