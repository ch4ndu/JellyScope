// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

/** Keeps focus denial from reaching a native player's start call. */
internal object PlaybackFocusAdmission {
    fun admit(
        requestFocus: () -> Boolean,
        nativeStart: () -> Unit,
    ): Boolean {
        if (!requestFocus()) return false
        nativeStart()
        return true
    }
}
