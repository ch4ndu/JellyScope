// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import platform.UIKit.UIView

/** Provides the retained native view used by an Apple player backend. */
public interface ApplePlaybackSurfaceProvider {
    public fun createSurfaceView(): UIView
}
