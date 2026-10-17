// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Current wall-clock time in the platform's short time style, refreshed at each minute boundary. */
@Composable
fun rememberPlayerClockText(): String {
    val formatWallClock = rememberWallClockFormatter()
    val text by produceState(formatWallClock(nowEpochMs()), formatWallClock) {
        while (true) {
            val now = nowEpochMs()
            value = formatWallClock(now)
            delay(MINUTE_MS - now % MINUTE_MS)
        }
    }
    return text
}

@Composable
internal expect fun rememberWallClockFormatter(): (Long) -> String

private fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

private const val MINUTE_MS = 60_000L
