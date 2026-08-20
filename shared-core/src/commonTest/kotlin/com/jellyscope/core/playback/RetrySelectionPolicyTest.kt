// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class RetrySelectionPolicyTest {
    @Test
    fun unspecifiedSubtitleRestoresPlanAndRetainsAudio() {
        val audio = audioSelection()

        val decision =
            retrySelectionDecision(
                audioSelection = audio,
                subtitleIntent = RetrySubtitleIntent.Unspecified,
                planSubtitleTarget = subtitleTarget(requestId = 1L),
            )

        assertIs<RetrySelectionDecision.RestorePlan>(decision)
        assertSame(audio, decision.audioSelection)
    }

    @Test
    fun explicitOffIsReassertedEvenWhenPlanHasNoTarget() {
        val decision =
            retrySelectionDecision(
                audioSelection = null,
                subtitleIntent = RetrySubtitleIntent.ExplicitOff,
                planSubtitleTarget = null,
            )

        assertIs<RetrySelectionDecision.ReassertSubtitleOff>(decision)
        assertEquals(null, decision.audioSelection)
    }

    @Test
    fun exactSubtitleTargetIsSelectedAndRetainsAudio() {
        val target = subtitleTarget(requestId = 7L)
        val subtitle = subtitleSelection(target)
        val audio = audioSelection()

        val decision =
            retrySelectionDecision(
                audioSelection = audio,
                subtitleIntent = RetrySubtitleIntent.Selection(subtitle),
                planSubtitleTarget = target,
            )

        val selected = assertIs<RetrySelectionDecision.SelectSubtitle>(decision)
        assertSame(audio, selected.audioSelection)
        assertSame(subtitle, selected.selection)
    }

    @Test
    fun staleRequestIdentityRestoresPreparedPlan() {
        val retained = subtitleSelection(subtitleTarget(requestId = 7L))

        val decision =
            retrySelectionDecision(
                audioSelection = null,
                subtitleIntent = RetrySubtitleIntent.Selection(retained),
                planSubtitleTarget = subtitleTarget(requestId = 8L),
            )

        assertIs<RetrySelectionDecision.RestorePlan>(decision)
    }

    @Test
    fun concreteSelectionRestoresPreparedPlanWhenPlanHasNoSubtitleTarget() {
        val decision =
            retrySelectionDecision(
                audioSelection = null,
                subtitleIntent = RetrySubtitleIntent.Selection(subtitleSelection(subtitleTarget(requestId = 1L))),
                planSubtitleTarget = null,
            )

        assertIs<RetrySelectionDecision.RestorePlan>(decision)
    }
}

private fun audioSelection(): EmbeddedAudioSelection =
    EmbeddedAudioSelection(
        target = AudioActivationTarget(requestId = 1L, itemId = "item-1", streamIndex = 1),
        descriptor = embeddedTrack(streamIndex = 1),
    )

private fun subtitleSelection(target: SubtitleActivationTarget): EmbeddedSubtitleSelection =
    EmbeddedSubtitleSelection(
        target = target,
        descriptor = embeddedTrack(streamIndex = 2),
    )

private fun subtitleTarget(requestId: Long): SubtitleActivationTarget =
    SubtitleActivationTarget(
        requestId = requestId,
        itemId = "item-1",
        identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex = 2),
        kind = LocalSubtitleKind.EmbeddedText,
    )

private fun embeddedTrack(streamIndex: Int): PlannedEmbeddedTrack =
    PlannedEmbeddedTrack(
        jellyfinStreamIndex = streamIndex,
        filteredContainerOrdinal = 0,
        codec = "srt",
        normalizedLanguage = "eng",
        label = "English",
    )
