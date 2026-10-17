// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.runtime.Composable
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.detail_play_resume
import com.jellyscope.ui.generated.resources.detail_play_start
import com.jellyscope.ui.generated.resources.detail_play_start_over
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

@Composable
internal fun playLabel(label: DetailPlayLabel): String =
    when (label) {
        DetailPlayLabel.Start -> stringResource(Res.string.detail_play_start)
        is DetailPlayLabel.Resume ->
            stringResource(
                Res.string.detail_play_resume,
                resumePositionText(label.position),
            )
        DetailPlayLabel.StartOver -> stringResource(Res.string.detail_play_start_over)
    }

// Resume position as a clock time (0:23, 2:45, or 1:05:09) so short offsets
// keep their seconds instead of collapsing to "0 min".
internal fun resumePositionText(position: Duration): String {
    val totalSeconds = position.inWholeSeconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
