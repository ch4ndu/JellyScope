// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Date

@Composable
internal actual fun rememberWallClockFormatter(): (Long) -> String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(context, locale, is24Hour) {
        val timeFormat = DateFormat.getTimeFormat(context)
        val format: (Long) -> String = { epochMs -> timeFormat.format(Date(epochMs)) }
        format
    }
}
