// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.playback

import org.videolan.vlckit.VLCDrawableProtocol
import org.videolan.vlckit.VLCPictureInPictureDrawableProtocol
import org.videolan.vlckit.VLCPictureInPictureMediaControllingProtocol
import org.videolan.vlckit.VLCPictureInPictureWindowControllingProtocol
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Cached transport facts and owner-lane commands used by VLCKit's public PiP adapter. */
internal interface VlcKitPictureInPictureTransport {
    fun currentPictureInPictureGeneration(): Long

    fun hasPictureInPictureContent(generation: Long): Boolean

    fun isPictureInPicturePlaying(generation: Long): Boolean

    fun isPictureInPictureSeekable(generation: Long): Boolean

    fun pictureInPictureDurationMs(generation: Long): Long?

    fun pictureInPictureTimeMs(generation: Long): Long

    fun playFromPictureInPicture(generation: Long)

    fun pauseFromPictureInPicture(generation: Long)

    fun seekByFromPictureInPicture(
        generation: Long,
        offsetMs: Long,
        completion: () -> Unit,
    )

    fun reportPictureInPictureUnavailable(generation: Long)
}

/**
 * Retained VLCKit drawable backed only by VLCKit 4's public PiP protocols.
 * Native callbacks capture primitive facts and cross an asynchronous Main hop
 * before identity validation or project state changes.
 */
internal class VlcKitPictureInPictureSurface(
    private val transport: VlcKitPictureInPictureTransport,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)),
    VLCDrawableProtocol,
    VLCPictureInPictureDrawableProtocol {
    private val mediaController = VlcKitPictureInPictureMediaController(transport, ::boundMediaGeneration)
    private var callbacks: IosPictureInPictureSurfaceCallbacks? = null
    private var enabled = false
    private var boundGeneration = NO_PICTURE_IN_PICTURE_GENERATION
    private var windowControllerGeneration = NO_PICTURE_IN_PICTURE_GENERATION
    private var windowController: VLCPictureInPictureWindowControllingProtocol? = null
    private var windowControllerStopRequired = false

    init {
        clipsToBounds = true
    }

    fun bindNativeSession(generation: Long) {
        if (boundGeneration == generation) return
        invalidateWindowController(notifySourceInvalidated = true)
        boundGeneration = generation
    }

    fun configure(
        enabled: Boolean,
        callbacks: IosPictureInPictureSurfaceCallbacks,
    ) {
        val disabledCurrentSource = this.enabled && !enabled
        this.callbacks = callbacks
        this.enabled = enabled
        if (disabledCurrentSource) {
            val sourceIdentity = boundGeneration
            if (sourceIdentity >= 0L) callbacks.onSourceInvalidated(sourceIdentity)
            enqueueStop(sourceIdentity)
        }
        invalidatePlaybackState()
    }

    fun clearCallbacks() {
        val sourceIdentity = boundGeneration
        enabled = false
        callbacks = null
        enqueueStop(sourceIdentity)
    }

    fun requestPictureInPictureStart(): IosPictureInPictureStartAdmission {
        val sourceIdentity = boundGeneration
        val callbackController = windowController
        if (
            !enabled ||
            sourceIdentity < 0L ||
            sourceIdentity != transport.currentPictureInPictureGeneration() ||
            sourceIdentity != windowControllerGeneration ||
            callbackController == null ||
            !transport.hasPictureInPictureContent(sourceIdentity)
        ) {
            if (enabled && sourceIdentity >= 0L) {
                transport.reportPictureInPictureUnavailable(sourceIdentity)
            }
            return IosPictureInPictureStartAdmission.Rejected
        }

        // The caller records the returned admission before this block executes,
        // so even a synchronously delivered native start fact has an owner.
        dispatch_async(dispatch_get_main_queue()) {
            if (!isCurrentWindowController(sourceIdentity, callbackController) || !enabled) {
                callbacks?.onSourceInvalidated?.invoke(sourceIdentity)
                return@dispatch_async
            }
            windowControllerStopRequired = true
            callbackController.startPictureInPicture()
        }
        return IosPictureInPictureStartAdmission.ExplicitStartRequested(sourceIdentity)
    }

    fun requestPictureInPictureStop(sourceIdentity: Long) {
        enqueueStop(sourceIdentity)
    }

    fun invalidatePlaybackState() {
        val sourceIdentity = boundGeneration
        val callbackController = windowController ?: return
        if (!isCurrentWindowController(sourceIdentity, callbackController)) return
        callbackController.invalidatePlaybackState()
    }

    @Suppress("UNUSED_PARAMETER")
    fun detachNativeSession(expectReplacement: Boolean) {
        invalidateWindowController(notifySourceInvalidated = true)
        boundGeneration = NO_PICTURE_IN_PICTURE_GENERATION
    }

    fun releasePictureInPicture() {
        invalidateWindowController(notifySourceInvalidated = true)
        boundGeneration = NO_PICTURE_IN_PICTURE_GENERATION
        callbacks = null
        enabled = false
    }

    override fun mediaController(): VLCPictureInPictureMediaControllingProtocol = mediaController

    override fun pictureInPictureReady(): ((VLCPictureInPictureWindowControllingProtocol?) -> Unit) {
        val callbackGeneration = boundGeneration
        return { controller ->
            dispatch_async(dispatch_get_main_queue()) {
                installWindowController(callbackGeneration, controller)
            }
        }
    }

    private fun installWindowController(
        callbackGeneration: Long,
        controller: VLCPictureInPictureWindowControllingProtocol?,
    ) {
        if (
            controller == null ||
            callbackGeneration < 0L ||
            callbackGeneration != boundGeneration ||
            callbackGeneration != transport.currentPictureInPictureGeneration()
        ) {
            controller?.stateChangeEventHandler = null
            controller?.stopPictureInPicture()
            return
        }
        if (controller === windowController && callbackGeneration == windowControllerGeneration) {
            controller.invalidatePlaybackState()
            return
        }

        invalidateWindowController(notifySourceInvalidated = true)
        windowController = controller
        windowControllerGeneration = callbackGeneration
        val callbackController = controller
        controller.stateChangeEventHandler = { started ->
            val state =
                if (started) {
                    IosPictureInPictureWindowState.Started
                } else {
                    IosPictureInPictureWindowState.Stopped
                }
            dispatch_async(dispatch_get_main_queue()) {
                if (!isCurrentWindowController(callbackGeneration, callbackController)) return@dispatch_async
                windowControllerStopRequired = started
                callbacks?.onWindowStateChanged?.invoke(callbackGeneration, state)
            }
        }
        controller.invalidatePlaybackState()
    }

    private fun enqueueStop(sourceIdentity: Long) {
        if (sourceIdentity < 0L) return
        val callbackController = windowController ?: return
        if (!windowControllerStopRequired) return
        dispatch_async(dispatch_get_main_queue()) {
            if (!isCurrentWindowController(sourceIdentity, callbackController)) return@dispatch_async
            windowControllerStopRequired = false
            callbackController.stopPictureInPicture()
        }
    }

    private fun invalidateWindowController(notifySourceInvalidated: Boolean) {
        val sourceIdentity = windowControllerGeneration
        val controller = windowController
        val stopRequired = windowControllerStopRequired
        windowController = null
        windowControllerGeneration = NO_PICTURE_IN_PICTURE_GENERATION
        windowControllerStopRequired = false
        controller?.stateChangeEventHandler = null
        if (stopRequired) controller?.stopPictureInPicture()
        if (notifySourceInvalidated && sourceIdentity >= 0L) {
            callbacks?.onSourceInvalidated?.invoke(sourceIdentity)
        }
    }

    private fun isCurrentWindowController(
        sourceIdentity: Long,
        controller: VLCPictureInPictureWindowControllingProtocol,
    ): Boolean =
        sourceIdentity >= 0L &&
            sourceIdentity == boundGeneration &&
            sourceIdentity == windowControllerGeneration &&
            sourceIdentity == transport.currentPictureInPictureGeneration() &&
            controller === windowController

    private fun boundMediaGeneration(): Long =
        boundGeneration.takeIf { generation -> generation == transport.currentPictureInPictureGeneration() }
            ?: NO_PICTURE_IN_PICTURE_GENERATION
}

private class VlcKitPictureInPictureMediaController(
    private val transport: VlcKitPictureInPictureTransport,
    private val generation: () -> Long,
) : NSObject(),
    VLCPictureInPictureMediaControllingProtocol {
    override fun play() {
        generation().takeIf { value -> value >= 0L }?.let(transport::playFromPictureInPicture)
    }

    override fun pause() {
        generation().takeIf { value -> value >= 0L }?.let(transport::pauseFromPictureInPicture)
    }

    override fun seekBy(
        offset: Long,
        completion: (() -> Unit)?,
    ) {
        val callback = completion ?: {}
        val currentGeneration = generation()
        if (currentGeneration < 0L) {
            callback()
            return
        }
        transport.seekByFromPictureInPicture(currentGeneration, offset, callback)
    }

    override fun mediaLength(): Long =
        generation()
            .takeIf { value -> value >= 0L }
            ?.let(transport::pictureInPictureDurationMs)
            ?.coerceAtLeast(0L)
            ?: 0L

    override fun mediaTime(): Long =
        generation()
            .takeIf { value -> value >= 0L }
            ?.let(transport::pictureInPictureTimeMs)
            ?.coerceAtLeast(0L)
            ?: 0L

    override fun isMediaSeekable(): Boolean =
        generation()
            .takeIf { value -> value >= 0L }
            ?.let(transport::isPictureInPictureSeekable)
            ?: false

    override fun isMediaPlaying(): Boolean =
        generation()
            .takeIf { value -> value >= 0L }
            ?.let(transport::isPictureInPicturePlaying)
            ?: false
}

private const val NO_PICTURE_IN_PICTURE_GENERATION = -1L
