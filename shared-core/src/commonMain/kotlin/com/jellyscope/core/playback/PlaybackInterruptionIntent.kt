// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

/** Apple interruption intent scoped so late callbacks cannot resume a replacement item. */
internal class PlaybackInterruptionIntent {
    private var resumeIntent = false

    fun onInterruptionBegan(playWhenReady: Boolean) {
        resumeIntent = playWhenReady
    }

    fun onInterruptionEnded(shouldResume: Boolean): Boolean = (shouldResume && resumeIntent).also { resumeIntent = false }

    fun reset() {
        resumeIntent = false
    }
}
