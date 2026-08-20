// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.tv_play_resume
import com.jellyscope.ui.generated.resources.tv_play_start
import com.jellyscope.ui.generated.resources.tv_play_start_over
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

@Composable
internal fun detailTvPlayLabel(label: DetailPlayLabel): String =
    when (label) {
        DetailPlayLabel.Start -> stringResource(Res.string.tv_play_start)
        is DetailPlayLabel.Resume ->
            stringResource(
                Res.string.tv_play_resume,
                detailTvResumePositionText(label.position),
            )
        DetailPlayLabel.StartOver -> stringResource(Res.string.tv_play_start_over)
    }

// Clock-time resume position (0:23, 2:45, 1:05:09) so short offsets keep
// their seconds instead of collapsing to "0 min".
internal fun detailTvResumePositionText(position: Duration): String {
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
