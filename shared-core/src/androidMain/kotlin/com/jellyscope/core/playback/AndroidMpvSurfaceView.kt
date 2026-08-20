// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import android.graphics.Color
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

internal interface AndroidMpvSurfaceCallbacks {
    fun onSurfaceCreated(
        owner: AndroidMpvSurfaceView,
        surface: Surface,
        width: Int,
        height: Int,
    )

    fun onSurfaceSizeChanged(
        owner: AndroidMpvSurfaceView,
        width: Int,
        height: Int,
    )

    fun onSurfaceDestroyed(owner: AndroidMpvSurfaceView)
}

/** SurfaceView host shared by mobile and TV; no Compose or mpv types cross it. */
internal class AndroidMpvSurfaceView(
    context: Context,
    private val callbacks: AndroidMpvSurfaceCallbacks,
) : FrameLayout(context),
    SurfaceHolder.Callback {
    private val videoSurface =
        SurfaceView(context).also { view ->
            view.layoutParams =
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                )
            view.holder.addCallback(this)
            addView(view)
        }

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        if (surface.isValid) {
            callbacks.onSurfaceCreated(this, surface, videoSurface.width, videoSurface.height)
        }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        if (width > 0 && height > 0) callbacks.onSurfaceSizeChanged(this, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        callbacks.onSurfaceDestroyed(this)
    }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) callbacks.onSurfaceSizeChanged(this, width, height)
    }

    fun currentSurface(): Surface? {
        val surface = videoSurface.holder.surface ?: return null
        return surface.takeIf(Surface::isValid)
    }

    fun currentSurfaceWidth(): Int = videoSurface.width

    fun currentSurfaceHeight(): Int = videoSurface.height
}
