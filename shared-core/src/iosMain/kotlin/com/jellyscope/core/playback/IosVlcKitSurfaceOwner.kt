// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.playback

import org.videolan.vlckit.VLCDrawableProtocol
import platform.UIKit.UIView

/** Keeps VLCKit's iOS PiP protocols outside the shared Apple controller. */
internal class IosVlcKitSurfaceOwner(
    private val transport: VlcKitSurfaceTransport,
) : VlcKitSurfaceOwner,
    VlcKitPictureInPictureTransport {
    private var surface: VlcKitPictureInPictureSurface? = null
    private var boundGeneration = transport.currentGeneration()

    override val view: UIView
        get() = getOrCreateSurface()

    override val drawable: VLCDrawableProtocol?
        get() = surface

    override fun bindNativeSession(generation: Long) {
        boundGeneration = generation
        surface?.bindNativeSession(generation)
    }

    override fun invalidatePlaybackState() {
        surface?.invalidatePlaybackState()
    }

    override fun detachNativeSession(expectReplacement: Boolean) {
        surface?.detachNativeSession(expectReplacement)
        boundGeneration = NO_BOUND_VLC_GENERATION
    }

    override fun release() {
        surface?.releasePictureInPicture()
        boundGeneration = NO_BOUND_VLC_GENERATION
    }

    fun configurePictureInPicture(
        enabled: Boolean,
        callbacks: IosPictureInPictureSurfaceCallbacks,
    ) {
        getOrCreateSurface().configure(enabled, callbacks)
    }

    fun clearPictureInPictureCallbacks() {
        surface?.clearCallbacks()
    }

    fun requestPictureInPictureStart(): IosPictureInPictureStartAdmission =
        surface?.requestPictureInPictureStart()
            ?: IosPictureInPictureStartAdmission.Rejected

    fun requestPictureInPictureStop(sourceIdentity: Long) {
        surface?.requestPictureInPictureStop(sourceIdentity)
    }

    override fun currentPictureInPictureGeneration(): Long = transport.currentGeneration()

    override fun hasPictureInPictureContent(generation: Long): Boolean = transport.hasContent(generation)

    override fun isPictureInPicturePlaying(generation: Long): Boolean = transport.isPlaying(generation)

    override fun isPictureInPictureSeekable(generation: Long): Boolean = transport.isSeekable(generation)

    override fun pictureInPictureDurationMs(generation: Long): Long? = transport.durationMs(generation)

    override fun pictureInPictureTimeMs(generation: Long): Long = transport.timeMs(generation)

    override fun playFromPictureInPicture(generation: Long) {
        transport.play(generation)
    }

    override fun pauseFromPictureInPicture(generation: Long) {
        transport.pause(generation)
    }

    override fun seekByFromPictureInPicture(
        generation: Long,
        offsetMs: Long,
        completion: () -> Unit,
    ) {
        transport.seekBy(generation, offsetMs, completion)
    }

    override fun reportPictureInPictureUnavailable(generation: Long) {
        transport.reportUnavailable(generation)
    }

    private fun getOrCreateSurface(): VlcKitPictureInPictureSurface =
        surface
            ?: VlcKitPictureInPictureSurface(this).also { created ->
                if (boundGeneration >= 0L) created.bindNativeSession(boundGeneration)
                surface = created
            }
}

private const val NO_BOUND_VLC_GENERATION = -1L
