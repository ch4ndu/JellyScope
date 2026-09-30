// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

@Composable
internal actual fun rememberWallClockFormatter(): (Long) -> String =
    remember {
        val formatter =
            NSDateFormatter().apply {
                dateStyle = NSDateFormatterNoStyle
                timeStyle = NSDateFormatterShortStyle
            }
        val format: (Long) -> String = { epochMs ->
            formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0))
        }
        format
    }
