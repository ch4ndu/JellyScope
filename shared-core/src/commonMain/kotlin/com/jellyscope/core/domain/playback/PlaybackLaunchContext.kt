// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelection

/** Closed status for one launch-context persistence read. */
enum class PlaybackLaunchReadOutcome {
    Present,
    Missing,
    Failed,
    Unavailable,
}

/**
 * Raw durable playback inputs resolved for one exact item/media-source pair.
 *
 * Screen and player owners retain precedence, option validation, process
 * memory, and local-subtitle reconciliation. Outcomes carry no keys,
 * exception text, paths, or other media identity.
 */
data class PlaybackLaunchContext(
    val playbackPreferences: PlaybackPreferences,
    val playbackSelection: PlaybackSelection?,
    val subtitleSelection: SubtitleSelectionIntent?,
    val playbackPreferencesOutcome: PlaybackLaunchReadOutcome,
    val playbackSelectionOutcome: PlaybackLaunchReadOutcome,
    val subtitleSelectionOutcome: PlaybackLaunchReadOutcome,
)
