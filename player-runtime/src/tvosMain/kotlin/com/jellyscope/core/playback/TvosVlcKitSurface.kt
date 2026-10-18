// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.playback

import org.videolan.vlckit.VLCDrawableProtocol
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView

/** Retained tvOS drawable for one VLC controller across media generations. */
internal class TvosVlcKitSurface(
    initialGeneration: Long,
) : VlcKitSurfaceOwner {
    private val nativeView by lazy { TvosVlcKitDrawableView() }
    private var boundGeneration = initialGeneration

    override val view: UIView
        get() = nativeView

    override val drawable: VLCDrawableProtocol?
        get() = nativeView.takeIf { boundGeneration >= 0L }

    override fun bindNativeSession(generation: Long) {
        boundGeneration = generation
    }

    override fun invalidatePlaybackState() = Unit

    @Suppress("UNUSED_PARAMETER")
    override fun detachNativeSession(expectReplacement: Boolean) {
        boundGeneration = NO_BOUND_VLC_GENERATION
    }

    override fun release() {
        boundGeneration = NO_BOUND_VLC_GENERATION
    }
}

private class TvosVlcKitDrawableView :
    UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)),
    VLCDrawableProtocol {
    init {
        clipsToBounds = true
    }
}

private const val NO_BOUND_VLC_GENERATION = -1L
