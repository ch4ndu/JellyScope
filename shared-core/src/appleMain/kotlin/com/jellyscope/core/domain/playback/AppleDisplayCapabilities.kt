// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import platform.UIKit.UIScreen

/**
 * Reads the Apple display HDR signal once at the UIKit app entry point.
 * Callers own the launch-thread contract; the returned Boolean is immutable
 * input to the device-profile provider for the lifetime of the Koin graph.
 */
fun detectDisplaySupportsHdr(): Boolean =
    runCatching {
        UIScreen.mainScreen.potentialEDRHeadroom > 1.0
    }.getOrDefault(false)
