// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

const val JELLYFIN_TICKS_PER_MILLISECOND = 10_000L

fun Long?.toPlaybackDuration(): Duration =
    this
        ?.takeIf { ticks -> ticks > 0L }
        ?.let { ticks -> (ticks / JELLYFIN_TICKS_PER_MILLISECOND).milliseconds }
        ?: Duration.ZERO

fun ticksToMilliseconds(ticks: Long): Long =
    ticks
        .coerceAtLeast(0L)
        .div(JELLYFIN_TICKS_PER_MILLISECOND)

fun millisecondsToTicks(milliseconds: Long): Long =
    milliseconds
        .coerceAtLeast(0L)
        .times(JELLYFIN_TICKS_PER_MILLISECOND)
