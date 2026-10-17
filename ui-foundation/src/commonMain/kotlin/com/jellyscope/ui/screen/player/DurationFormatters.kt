// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

fun formatDuration(milliseconds: Long?): String {
    if (milliseconds == null) {
        return "--:--"
    }

    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE

    return if (hours > 0L) {
        "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
    } else {
        "$minutes:${seconds.twoDigits()}"
    }
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')

internal const val SECONDS_PER_MINUTE = 60L
internal const val SECONDS_PER_HOUR = 3_600L
