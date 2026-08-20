// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Bounded reasons that can enter the automatic recovery state machine. */
enum class AutoPlaybackRecoveryTrigger {
    DecoderFailure,
    UnsupportedMedia,
    NoVideoOutput,
    CumulativeBuffering,
    RepeatedStalls,
    DroppedFrames,
}

enum class AutoPlaybackRecoveryPromptReason {
    OriginalPlaybackFailed,
    FixedQualityFailed,
    CompatibilityRecoveryExhausted,
    QualityRecoveryExhausted,
    NoLowerQualityAvailable,
    AutoQualityRecoveryRequiresExplicitSessionChoice,
}

/**
 * Explicit in-player Auto is the only authorization for an automatic quality
 * downgrade. A settings-derived Auto policy may still collect health evidence,
 * but must leave the next stream choice to the player UI.
 */
enum class AutoPlaybackQualityRecoveryAuthorization {
    ExplicitSessionAuto,
    NotAuthorized,
}

/** Stable actions shared by Compose, Android TV, and the tvOS presenter. */
enum class PlaybackAction {
    AcceptAuto,
    ClearQualityOverride,
    KeepCurrentQuality,
    ChooseLowerQuality,
    TryHigherQuality,
    TryOriginal,
    OpenPlaybackSettings,
    Dismiss,
    Retry,
    Close,
}

enum class PlaybackActionNoticeReason {
    OriginalPlaybackFailed,
    FixedQualityFailed,
    CompatibilityRecoveryExhausted,
    QualityRecoveryApplied,
    NoLowerQualityAvailable,
}

data class PlaybackActionNotice(
    val reason: PlaybackActionNoticeReason,
    val actions: Set<PlaybackAction>,
    val runtimeQualityCapBps: Long? = null,
    val canDismiss: Boolean = true,
)

/**
 * Session-only state. The runtime cap is intentionally not a persisted
 * selection; it exists only to prevent repeated automatic retries.
 */
data class AutoPlaybackRecoveryState(
    val generation: Long = 0L,
    val itemId: String? = null,
    val backend: PlayerBackend = PlayerBackend.Auto,
    val compatibilityAttempted: Boolean = false,
    val qualityAttempted: Boolean = false,
    val runtimeQualityCapBps: Long? = null,
)

data class AutoPlaybackRecoveryInput(
    val generation: Long,
    val policy: PlaybackQualityPolicy,
    val qualityRecoveryAuthorization: AutoPlaybackQualityRecoveryAuthorization,
    val backend: PlayerBackend,
    val plan: PlaybackPlan,
    val sourceBitrateBps: Long?,
    val lowerQualityRungsBps: List<Long>,
    val state: AutoPlaybackRecoveryState,
    val trigger: AutoPlaybackRecoveryTrigger,
)

sealed interface AutoPlaybackRecoveryDecision {
    data object NoAction : AutoPlaybackRecoveryDecision

    data class CompatibilityReplan(
        val state: AutoPlaybackRecoveryState,
    ) : AutoPlaybackRecoveryDecision

    data class LowerTo(
        val maxBitrateBps: Long,
        val state: AutoPlaybackRecoveryState,
    ) : AutoPlaybackRecoveryDecision

    data class PromptUser(
        val reason: AutoPlaybackRecoveryPromptReason,
        val actions: Set<PlaybackAction>,
        val state: AutoPlaybackRecoveryState,
    ) : AutoPlaybackRecoveryDecision
}

data class AutoPlaybackRecoveryResult(
    val decision: AutoPlaybackRecoveryDecision,
    val state: AutoPlaybackRecoveryState,
)

/**
 * Pure, bounded Auto recovery. It does not launch work, touch a controller,
 * persist a choice, or produce user-facing wording.
 */
class AutoPlaybackRecoveryCoordinator {
    /**
     * Removes only Auto's session limiter after an explicit "Try higher" action.
     * The one-shot attempt flags remain set so a bad stream cannot immediately
     * enter the same automatic downgrade loop again.
     */
    fun clearRuntimeQualityCap(state: AutoPlaybackRecoveryState): AutoPlaybackRecoveryState = state.copy(runtimeQualityCapBps = null)

    fun decide(input: AutoPlaybackRecoveryInput): AutoPlaybackRecoveryResult {
        val policy = input.policy.normalized()
        val state = sessionState(input)

        if (policy.mode == PlaybackQualityMode.Original) {
            return prompt(
                reason = AutoPlaybackRecoveryPromptReason.OriginalPlaybackFailed,
                actions =
                    setOf(
                        PlaybackAction.AcceptAuto,
                        PlaybackAction.OpenPlaybackSettings,
                        PlaybackAction.Dismiss,
                        PlaybackAction.Close,
                    ),
                state = state,
            )
        }

        if (
            isCompatibilityRecoveryTrigger(input.trigger) &&
            !state.compatibilityAttempted &&
            canAttemptCompatibility(input.plan)
        ) {
            val nextState = state.copy(compatibilityAttempted = true)
            return AutoPlaybackRecoveryResult(
                decision = AutoPlaybackRecoveryDecision.CompatibilityReplan(nextState),
                state = nextState,
            )
        }

        if (policy.mode == PlaybackQualityMode.Fixed) {
            return prompt(
                reason = AutoPlaybackRecoveryPromptReason.FixedQualityFailed,
                actions = fixedPromptActions(),
                state = state,
            )
        }

        if (!isQualityRecoveryTrigger(input.trigger)) {
            return prompt(
                reason = AutoPlaybackRecoveryPromptReason.CompatibilityRecoveryExhausted,
                actions = autoPromptActions(),
                state = state,
            )
        }

        if (input.qualityRecoveryAuthorization != AutoPlaybackQualityRecoveryAuthorization.ExplicitSessionAuto) {
            return prompt(
                reason = AutoPlaybackRecoveryPromptReason.AutoQualityRecoveryRequiresExplicitSessionChoice,
                actions = autoPromptActions(),
                state = state,
            )
        }

        if (state.qualityAttempted) {
            return prompt(
                reason = AutoPlaybackRecoveryPromptReason.QualityRecoveryExhausted,
                actions = autoPromptActions(),
                state = state,
            )
        }

        val currentLimit = state.runtimeQualityCapBps ?: input.plan.maxStreamingBitrate ?: input.sourceBitrateBps
        if (currentLimit == null) {
            return prompt(
                reason = AutoPlaybackRecoveryPromptReason.NoLowerQualityAvailable,
                actions = autoPromptActions(),
                state = state,
            )
        }
        val nextRung =
            input.lowerQualityRungsBps
                .asSequence()
                .filter { rung -> rung > 0L }
                .filter { rung -> rung < currentLimit }
                .maxOrNull()
        if (nextRung == null) {
            return prompt(
                reason = AutoPlaybackRecoveryPromptReason.NoLowerQualityAvailable,
                actions = autoPromptActions(),
                state = state,
            )
        }

        val nextState = state.copy(qualityAttempted = true, runtimeQualityCapBps = nextRung)
        return AutoPlaybackRecoveryResult(
            decision = AutoPlaybackRecoveryDecision.LowerTo(nextRung, nextState),
            state = nextState,
        )
    }

    fun reset(
        generation: Long,
        itemId: String?,
        backend: PlayerBackend,
    ): AutoPlaybackRecoveryState = AutoPlaybackRecoveryState(generation = generation, itemId = itemId, backend = backend)

    private fun sessionState(input: AutoPlaybackRecoveryInput): AutoPlaybackRecoveryState =
        input.state.takeIf { state ->
            state.generation == input.generation &&
                state.itemId == input.plan.itemId &&
                state.backend == input.backend
        } ?: input.state.copy(
            generation = input.generation,
            itemId = input.plan.itemId,
            backend = input.backend,
            compatibilityAttempted = false,
            qualityAttempted = false,
            runtimeQualityCapBps = null,
        )

    private fun canAttemptCompatibility(plan: PlaybackPlan): Boolean =
        plan.streamMode != StreamMode.Transcode && plan.streamMode != StreamMode.Offline

    private fun isCompatibilityRecoveryTrigger(trigger: AutoPlaybackRecoveryTrigger): Boolean =
        trigger == AutoPlaybackRecoveryTrigger.DecoderFailure ||
            trigger == AutoPlaybackRecoveryTrigger.UnsupportedMedia ||
            trigger == AutoPlaybackRecoveryTrigger.NoVideoOutput

    private fun isQualityRecoveryTrigger(trigger: AutoPlaybackRecoveryTrigger): Boolean =
        trigger == AutoPlaybackRecoveryTrigger.CumulativeBuffering ||
            trigger == AutoPlaybackRecoveryTrigger.RepeatedStalls ||
            trigger == AutoPlaybackRecoveryTrigger.DroppedFrames

    private fun prompt(
        reason: AutoPlaybackRecoveryPromptReason,
        actions: Set<PlaybackAction>,
        state: AutoPlaybackRecoveryState,
    ): AutoPlaybackRecoveryResult {
        val decision = AutoPlaybackRecoveryDecision.PromptUser(reason, actions, state)
        return AutoPlaybackRecoveryResult(decision, state)
    }

    private fun autoPromptActions(): Set<PlaybackAction> =
        setOf(
            PlaybackAction.ChooseLowerQuality,
            PlaybackAction.TryHigherQuality,
            PlaybackAction.TryOriginal,
            PlaybackAction.Dismiss,
        )

    private fun fixedPromptActions(): Set<PlaybackAction> =
        setOf(
            PlaybackAction.ChooseLowerQuality,
            PlaybackAction.TryHigherQuality,
            PlaybackAction.TryOriginal,
            PlaybackAction.Dismiss,
        )
}
