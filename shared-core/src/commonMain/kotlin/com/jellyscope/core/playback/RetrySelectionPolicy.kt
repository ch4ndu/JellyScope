// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.SubtitleActivationTarget

internal sealed interface RetrySubtitleIntent {
    /** No distinct Off state exists; preparing the plan owns subtitle restoration. */
    data object Unspecified : RetrySubtitleIntent

    /** The controller already distinguishes an explicit in-player Off request. */
    data object ExplicitOff : RetrySubtitleIntent

    data class Selection(
        val selection: EmbeddedSubtitleSelection,
    ) : RetrySubtitleIntent
}

internal sealed interface RetrySelectionDecision {
    val audioSelection: EmbeddedAudioSelection?

    data class RestorePlan(
        override val audioSelection: EmbeddedAudioSelection?,
    ) : RetrySelectionDecision

    data class ReassertSubtitleOff(
        override val audioSelection: EmbeddedAudioSelection?,
    ) : RetrySelectionDecision

    data class SelectSubtitle(
        override val audioSelection: EmbeddedAudioSelection?,
        val selection: EmbeddedSubtitleSelection,
    ) : RetrySelectionDecision
}

/** Validates retained identities; only controllers with an explicit Off state may supply one. */
internal fun retrySelectionDecision(
    audioSelection: EmbeddedAudioSelection?,
    subtitleIntent: RetrySubtitleIntent,
    planSubtitleTarget: SubtitleActivationTarget?,
): RetrySelectionDecision =
    when (subtitleIntent) {
        RetrySubtitleIntent.Unspecified -> RetrySelectionDecision.RestorePlan(audioSelection)
        RetrySubtitleIntent.ExplicitOff -> RetrySelectionDecision.ReassertSubtitleOff(audioSelection)
        is RetrySubtitleIntent.Selection ->
            if (subtitleIntent.selection.target == planSubtitleTarget) {
                RetrySelectionDecision.SelectSubtitle(audioSelection, subtitleIntent.selection)
            } else {
                RetrySelectionDecision.RestorePlan(audioSelection)
            }
    }
