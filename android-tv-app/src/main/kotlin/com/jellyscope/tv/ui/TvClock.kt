// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Date
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailText as TvText

/**
 * Compact clock pinned to the top-right of the Home screen. Uses the
 * device's 12/24-hour preference and refreshes on the minute.
 */
@Composable
fun TvClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(timeFormat.format(Date())) }

    LaunchedEffect(timeFormat) {
        while (true) {
            now = timeFormat.format(Date())
            val msIntoMinute = System.currentTimeMillis() % MILLIS_PER_MINUTE
            delay(MILLIS_PER_MINUTE - msIntoMinute + MINUTE_TICK_GUARD_MS)
        }
    }

    TvText(
        text = now,
        style =
            TvBodyStyle.copy(
                fontWeight = FontWeight.SemiBold,
                // Legible over bright backdrop imagery.
                shadow =
                    Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(0f, 2f),
                        blurRadius = 6f,
                    ),
            ),
        modifier = modifier.padding(top = 2.dp),
        maxLines = 1,
    )
}

private const val MINUTE_TICK_GUARD_MS = 50L
