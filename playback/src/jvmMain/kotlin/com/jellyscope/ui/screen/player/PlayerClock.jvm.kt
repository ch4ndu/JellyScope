// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The JVM locale default decides 12/24-hour; macOS's separate 24-hour toggle is not visible here.
@Composable
internal actual fun rememberWallClockFormatter(): (Long) -> String =
    remember {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val format: (Long) -> String = { epochMs ->
            formatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
        }
        format
    }
