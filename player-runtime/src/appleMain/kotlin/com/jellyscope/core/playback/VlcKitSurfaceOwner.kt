// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.playback

import org.videolan.vlckit.VLCDrawableProtocol
import platform.UIKit.UIView

/** Retains the native drawable across VLC media generations. */
internal interface VlcKitSurfaceOwner {
    val view: UIView
    val drawable: VLCDrawableProtocol?

    fun bindNativeSession(generation: Long)

    fun invalidatePlaybackState()

    fun detachNativeSession(expectReplacement: Boolean)

    fun release()
}

/** Generation-qualified commands and cached facts required by the retained surface. */
internal interface VlcKitSurfaceTransport {
    fun currentGeneration(): Long

    fun hasContent(generation: Long): Boolean

    fun isPlaying(generation: Long): Boolean

    fun isSeekable(generation: Long): Boolean

    fun durationMs(generation: Long): Long?

    fun timeMs(generation: Long): Long

    fun play(generation: Long)

    fun pause(generation: Long)

    fun seekBy(
        generation: Long,
        offsetMs: Long,
        completion: () -> Unit,
    )

    fun reportUnavailable(generation: Long)
}
