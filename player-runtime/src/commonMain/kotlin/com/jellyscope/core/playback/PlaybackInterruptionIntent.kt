// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

/** Apple interruption intent scoped so late callbacks cannot resume a replacement item. */
internal class PlaybackInterruptionIntent {
    private var resumeIntent: Boolean? = null

    fun onInterruptionBegan(playWhenReady: Boolean) {
        if (resumeIntent == null) {
            resumeIntent = playWhenReady
        }
    }

    fun revoke() {
        if (resumeIntent != null) {
            resumeIntent = false
        }
    }

    fun onInterruptionEnded(shouldResume: Boolean): Boolean {
        val shouldResumePlayback = shouldResume && resumeIntent == true
        resumeIntent = null
        return shouldResumePlayback
    }

    fun reset() {
        resumeIntent = null
    }
}
