// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackPlan

internal fun PlaybackPlan.clampedStartPositionMs(): Long = startPositionMs.coerceAtLeast(0L)

/** Zero is a valid clamped start but means no explicit resume seek. */
internal fun PlaybackPlan.resumeSeekPositionMs(): Long? = clampedStartPositionMs().takeIf { positionMs -> positionMs > 0L }
