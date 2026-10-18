// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Opaque handle to the app-owned macOS NSView used by the desktop LibVLC probe.
 *
 * The native address remains JVM-only and is never exposed through the shared
 * player contract or diagnostics.
 */
class DesktopVlcSurfaceHandle private constructor(
    internal val value: Long,
    val generation: Long,
) {
    companion object {
        fun create(
            value: Long,
            generation: Long,
        ): DesktopVlcSurfaceHandle? =
            if (value > 0L && generation > 0L) {
                DesktopVlcSurfaceHandle(value, generation)
            } else {
                null
            }
    }
}

/** JVM-only surface capability consumed by the desktop Compose player host. */
interface DesktopVlcVideoOutput {
    /**
     * False while LibVLC may still expose a stale pre-seek frame. The macOS
     * host remains transparent until the controller confirms a current frame.
     */
    val videoPresentationReady: StateFlow<Boolean>

    fun attachVideoSurface(surface: DesktopVlcSurfaceHandle)

    fun videoSurfaceUnavailable(generation: Long)

    fun resizeVideoSurface(
        generation: Long,
        width: Int,
        height: Int,
    )

    fun detachVideoSurface(
        generation: Long,
        onDetached: () -> Unit,
    )

    fun setFillCrop(crop: Boolean)
}
