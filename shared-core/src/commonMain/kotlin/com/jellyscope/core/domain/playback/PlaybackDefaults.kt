// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Upper bound for the optional user-configured VLC-family Fixed default. */
const val MAX_VLC_TRANSCODE_BITRATE_BPS = Int.MAX_VALUE.toLong()

/** VLC-family Fixed-default choices; canonical rungs inherit their resolution semantics. */
val vlcTranscodeBudgetOptions =
    listOf<Long?>(
        null,
        2_000_000L,
        4_000_000L,
        8_000_000L,
        12_000_000L,
        20_000_000L,
        40_000_000L,
        80_000_000L,
    )

fun normalizeVlcTranscodeBitrate(bitrateBps: Long?): Long? = bitrateBps?.takeIf { bitrate -> bitrate in 1L..MAX_VLC_TRANSCODE_BITRATE_BPS }
