// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackContentTimeline

fun resolvePlaybackDurationMs(
    contentTimeline: PlaybackContentTimeline,
    nativeDurationMs: Long?,
): Long? =
    when (contentTimeline) {
        is PlaybackContentTimeline.BoundedVod -> contentTimeline.durationMs
        PlaybackContentTimeline.UnknownOrUnbounded -> nativeDurationMs?.takeIf { durationMs -> durationMs > 0L }
    }
