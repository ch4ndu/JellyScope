// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AutoPlaybackRecoveryCoordinatorTest {
    @Test
    fun strongestPendingRecoveryTriggerKeepsTheLockedSeverityOrder() {
        val strongestToWeakest =
            listOf(
                AutoPlaybackRecoveryTrigger.DecoderFailure,
                AutoPlaybackRecoveryTrigger.UnsupportedMedia,
                AutoPlaybackRecoveryTrigger.NoVideoOutput,
                AutoPlaybackRecoveryTrigger.CumulativeBuffering,
                AutoPlaybackRecoveryTrigger.RepeatedStalls,
                AutoPlaybackRecoveryTrigger.DroppedFrames,
            )

        assertEquals(AutoPlaybackRecoveryTrigger.entries.toSet(), strongestToWeakest.toSet())
        strongestToWeakest.forEach { candidate ->
            assertEquals(candidate, strongestPendingRecoveryTrigger(current = null, candidate = candidate))
        }
        strongestToWeakest.zipWithNext().forEach { (higher, lower) ->
            assertEquals(higher, strongestPendingRecoveryTrigger(current = lower, candidate = higher))
            assertEquals(higher, strongestPendingRecoveryTrigger(current = higher, candidate = lower))
        }
        AutoPlaybackRecoveryTrigger.entries.forEach { trigger ->
            assertSame(trigger, strongestPendingRecoveryTrigger(current = trigger, candidate = trigger))
        }
    }

    @Test
    fun clearRuntimeQualityCapPreservesOneShotRecoveryBudget() {
        val state =
            AutoPlaybackRecoveryState(
                generation = 7L,
                itemId = "item",
                backend = PlayerBackend.LibVlc,
                compatibilityAttempted = true,
                qualityAttempted = true,
                runtimeQualityCapBps = 8_000_000L,
            )

        val cleared = coordinator.clearRuntimeQualityCap(state)

        assertEquals(null, cleared.runtimeQualityCapBps)
        assertEquals(true, cleared.compatibilityAttempted)
        assertEquals(true, cleared.qualityAttempted)
        assertEquals(7L, cleared.generation)
        assertEquals("item", cleared.itemId)
        assertEquals(PlayerBackend.LibVlc, cleared.backend)
    }

    private val coordinator = AutoPlaybackRecoveryCoordinator()

    @Test
    fun originalPromptsForEveryRecoveryTriggerWithoutSpendingRecoveryBudget() {
        AutoPlaybackRecoveryTrigger.entries.forEach { trigger ->
            val result = decide(PlaybackQualityPolicy.Original, trigger)

            val prompt = assertIs<AutoPlaybackRecoveryDecision.PromptUser>(result.decision)
            assertEquals(AutoPlaybackRecoveryPromptReason.OriginalPlaybackFailed, prompt.reason)
            assertEquals(
                setOf(
                    PlaybackAction.AcceptAuto,
                    PlaybackAction.OpenPlaybackSettings,
                    PlaybackAction.Dismiss,
                    PlaybackAction.Close,
                ),
                prompt.actions,
            )
            assertEquals(false, result.state.compatibilityAttempted)
            assertEquals(false, result.state.qualityAttempted)
            assertEquals(null, result.state.runtimeQualityCapBps)
        }
    }

    @Test
    fun autoUsesCompatibilityOnceThenOneStrictLowerQualityRung() {
        val first = decide(PlaybackQualityPolicy.Auto, AutoPlaybackRecoveryTrigger.DecoderFailure)
        val compatibility = assertIs<AutoPlaybackRecoveryDecision.CompatibilityReplan>(first.decision)

        val second =
            decide(
                policy = PlaybackQualityPolicy.Auto,
                trigger = AutoPlaybackRecoveryTrigger.RepeatedStalls,
                state = compatibility.state,
            )
        val lower = assertIs<AutoPlaybackRecoveryDecision.LowerTo>(second.decision)
        assertEquals(12_000_000L, lower.maxBitrateBps)
        assertEquals(12_000_000L, lower.state.runtimeQualityCapBps)

        val third =
            decide(
                policy = PlaybackQualityPolicy.Auto,
                trigger = AutoPlaybackRecoveryTrigger.DroppedFrames,
                state = lower.state,
            )
        assertIs<AutoPlaybackRecoveryDecision.PromptUser>(third.decision)
    }

    @Test
    fun autoPoorPlaybackUsesQualityBudgetWithoutSpendingCompatibilityBudget() {
        val result = decide(PlaybackQualityPolicy.Auto, AutoPlaybackRecoveryTrigger.RepeatedStalls)

        val lower = assertIs<AutoPlaybackRecoveryDecision.LowerTo>(result.decision)
        assertEquals(12_000_000L, lower.maxBitrateBps)
        assertEquals(false, lower.state.compatibilityAttempted)
        assertEquals(true, lower.state.qualityAttempted)
    }

    @Test
    fun inheritedAutoPromptsForManualQualityChoiceWithoutLoweringOrSpendingItsBudget() {
        val result =
            decide(
                policy = PlaybackQualityPolicy.Auto,
                trigger = AutoPlaybackRecoveryTrigger.RepeatedStalls,
                qualityRecoveryAuthorization = AutoPlaybackQualityRecoveryAuthorization.NotAuthorized,
            )

        val prompt = assertIs<AutoPlaybackRecoveryDecision.PromptUser>(result.decision)
        assertEquals(AutoPlaybackRecoveryPromptReason.AutoQualityRecoveryRequiresExplicitSessionChoice, prompt.reason)
        assertEquals(
            setOf(
                PlaybackAction.ChooseLowerQuality,
                PlaybackAction.TryHigherQuality,
                PlaybackAction.TryOriginal,
                PlaybackAction.Dismiss,
            ),
            prompt.actions,
        )
        assertEquals(false, result.state.compatibilityAttempted)
        assertEquals(false, result.state.qualityAttempted)
        assertEquals(null, result.state.runtimeQualityCapBps)
    }

    @Test
    fun autoCannotInventALowerRungWhenTheCurrentBitrateIsUnknown() {
        val result =
            decide(
                policy = PlaybackQualityPolicy.Auto,
                trigger = AutoPlaybackRecoveryTrigger.CumulativeBuffering,
                sourceBitrateBps = null,
            )

        val prompt = assertIs<AutoPlaybackRecoveryDecision.PromptUser>(result.decision)
        assertEquals(AutoPlaybackRecoveryPromptReason.NoLowerQualityAvailable, prompt.reason)
        assertEquals(true, PlaybackAction.ChooseLowerQuality in prompt.actions)
        assertEquals(false, PlaybackAction.OpenPlaybackSettings in prompt.actions)
        assertEquals(false, result.state.qualityAttempted)
    }

    @Test
    fun autoDoesNotUpshiftARecoveredSessionCapAndResetsForANewGeneration() {
        val state =
            AutoPlaybackRecoveryState(
                generation = 4L,
                itemId = "item",
                backend = PlayerBackend.ExoPlayer,
                compatibilityAttempted = true,
                qualityAttempted = true,
                runtimeQualityCapBps = 8_000_000L,
            )
        val bounded =
            decide(
                policy = PlaybackQualityPolicy.Auto,
                trigger = AutoPlaybackRecoveryTrigger.CumulativeBuffering,
                state = state,
            )
        assertIs<AutoPlaybackRecoveryDecision.PromptUser>(bounded.decision)
        assertEquals(8_000_000L, bounded.state.runtimeQualityCapBps)

        val reset =
            decide(
                policy = PlaybackQualityPolicy.Auto,
                trigger = AutoPlaybackRecoveryTrigger.DecoderFailure,
                state = state,
                generation = 5L,
            )
        assertIs<AutoPlaybackRecoveryDecision.CompatibilityReplan>(reset.decision)
        assertEquals(5L, reset.state.generation)
        assertEquals(null, reset.state.runtimeQualityCapBps)
    }

    @Test
    fun fixedQualityKeepsItsExactCapAndOnlyAttemptsCompatibility() {
        val first = decide(PlaybackQualityPolicy.fixed(8_000_000L), AutoPlaybackRecoveryTrigger.UnsupportedMedia)
        val compatibility = assertIs<AutoPlaybackRecoveryDecision.CompatibilityReplan>(first.decision)
        val second =
            decide(
                policy = PlaybackQualityPolicy.fixed(8_000_000L),
                trigger = AutoPlaybackRecoveryTrigger.NoVideoOutput,
                state = compatibility.state,
            )
        assertIs<AutoPlaybackRecoveryDecision.PromptUser>(second.decision)
    }

    @Test
    fun fixedQualityDoesNotSpendCompatibilityBudgetForPoorPlayback() {
        val result =
            decide(
                PlaybackQualityPolicy.fixed(8_000_000L),
                AutoPlaybackRecoveryTrigger.DroppedFrames,
            )

        val prompt = assertIs<AutoPlaybackRecoveryDecision.PromptUser>(result.decision)
        assertEquals(AutoPlaybackRecoveryPromptReason.FixedQualityFailed, prompt.reason)
        assertEquals(
            setOf(
                PlaybackAction.ChooseLowerQuality,
                PlaybackAction.TryHigherQuality,
                PlaybackAction.TryOriginal,
                PlaybackAction.Dismiss,
            ),
            prompt.actions,
        )
        assertEquals(false, result.state.compatibilityAttempted)
    }

    private fun decide(
        policy: PlaybackQualityPolicy,
        trigger: AutoPlaybackRecoveryTrigger,
        state: AutoPlaybackRecoveryState = AutoPlaybackRecoveryState(),
        generation: Long = 4L,
        sourceBitrateBps: Long? = 20_000_000L,
        qualityRecoveryAuthorization: AutoPlaybackQualityRecoveryAuthorization =
            AutoPlaybackQualityRecoveryAuthorization.ExplicitSessionAuto,
    ): AutoPlaybackRecoveryResult =
        coordinator.decide(
            AutoPlaybackRecoveryInput(
                generation = generation,
                policy = policy,
                qualityRecoveryAuthorization = qualityRecoveryAuthorization,
                backend = PlayerBackend.ExoPlayer,
                plan = plan(),
                sourceBitrateBps = sourceBitrateBps,
                lowerQualityRungsBps = listOf(1_500_000L, 8_000_000L, 12_000_000L, 20_000_000L),
                state = state,
                trigger = trigger,
            ),
        )

    private fun plan() =
        PlaybackPlan(
            itemId = "item",
            mediaSourceId = "source",
            startPositionMs = 0L,
            streamMode = StreamMode.DirectPlay,
            streamUrl = "https://example.invalid/stream",
            progressReportingPolicy = ProgressReportingPolicy(reportIntervalMs = 10_000L),
        )
}
