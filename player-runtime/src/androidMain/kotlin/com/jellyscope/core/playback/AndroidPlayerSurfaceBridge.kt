// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import android.view.View

/** Project-owned surface seam used by Android mobile and TV without leaking engine types. */
interface AndroidPlayerSurfaceBridge {
    fun createSurfaceView(context: Context): View

    fun attachSurface(view: View)

    fun detachSurface()

    /** Releases a specific Compose-owned view without detaching a newer replacement. */
    fun detachSurface(view: View) = detachSurface()

    /** Cheap UI-owned presentation input; native work is deferred by the controller. */
    fun updatePresentation(presentation: AndroidSurfacePresentation) = Unit
}

enum class AndroidSurfaceResizeMode {
    Fit,
    Fill,
    Zoom,
}

data class AndroidSurfacePresentation(
    val resizeMode: AndroidSurfaceResizeMode,
    val subtitleBottomInsetPx: Int = 0,
)
