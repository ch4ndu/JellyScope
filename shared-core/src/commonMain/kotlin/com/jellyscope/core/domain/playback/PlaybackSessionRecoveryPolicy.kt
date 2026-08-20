// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Item/session-scoped one-shot facts for playback failure recovery. */
data class PlaybackSessionRecoveryState(
    val generation: Long = 0L,
    val itemId: String? = null,
    val networkRetryAttempted: Boolean = false,
    val lastAudioRecoveryTarget: AudioActivationTarget? = null,
    val lastSubtitleRecoveryTarget: SubtitleActivationTarget? = null,
)

data class PlaybackSessionRecoveryInput(
    val generation: Long,
    val itemId: String,
    val plan: PlaybackPlan?,
    val playbackState: PlaybackState,
    val state: PlaybackSessionRecoveryState,
)

sealed interface PlaybackSessionRecoveryDecision {
    data object NoAction : PlaybackSessionRecoveryDecision

    data class NetworkRetry(
        val requestPolicy: PlaybackInfoRequestPolicy =
            PlaybackInfoRequestPolicy(clientTrigger = PlaybackClientTrigger.NetworkRetry),
    ) : PlaybackSessionRecoveryDecision

    data class AudioActivationRecovery(
        val target: AudioActivationTarget,
        val requestPolicy: PlaybackInfoRequestPolicy =
            PlaybackInfoRequestPolicy(
                enableDirectPlay = false,
                clientTrigger = PlaybackClientTrigger.AudioActivationFallback,
            ),
    ) : PlaybackSessionRecoveryDecision

    data class SubtitleEncodeRecovery(
        val target: SubtitleActivationTarget,
        val requestPolicy: PlaybackInfoRequestPolicy,
    ) : PlaybackSessionRecoveryDecision

    data class SubtitleUnavailable(
        val target: SubtitleActivationTarget,
    ) : PlaybackSessionRecoveryDecision

    data class AutomaticRecovery(
        val trigger: AutoPlaybackRecoveryTrigger,
    ) : PlaybackSessionRecoveryDecision
}

data class PlaybackSessionRecoveryResult(
    val decision: PlaybackSessionRecoveryDecision,
    val state: PlaybackSessionRecoveryState,
)

/**
 * Pure recovery precedence and one-shot policy shared by Compose and tvOS.
 * Shells retain replan execution, controller commands, notices, and stale-work
 * cancellation.
 */
class PlaybackSessionRecoveryPolicy {
    fun decide(input: PlaybackSessionRecoveryInput): PlaybackSessionRecoveryResult {
        val state = sessionState(input)
        val plan = input.plan
        if (plan == null || plan.itemId != input.itemId) {
            return noAction(state)
        }

        // Explicit offline sessions have no legal server recovery path. Keep this ahead of
        // audio/subtitle/failure precedence so native errors cannot trigger a backend fallback,
        // PlaybackInfo replan, or server-rendered subtitle request.
        if (plan.streamMode == StreamMode.Offline) {
            return noAction(state)
        }

        audioRecovery(input, plan, state)?.let { return it }
        subtitleRecovery(input, plan, state)?.let { return it }

        if (input.playbackState.status != PlaybackStatus.Failed) {
            return noAction(state)
        }

        input.playbackState.error.toPlaybackSessionRecoveryTrigger()?.let { trigger ->
            return PlaybackSessionRecoveryResult(
                decision = PlaybackSessionRecoveryDecision.AutomaticRecovery(trigger),
                state = state,
            )
        }

        if (
            input.playbackState.error == PlaybackError.Network &&
            plan.streamMode != StreamMode.Offline &&
            !state.networkRetryAttempted
        ) {
            val nextState = state.copy(networkRetryAttempted = true)
            return PlaybackSessionRecoveryResult(
                decision = PlaybackSessionRecoveryDecision.NetworkRetry(),
                state = nextState,
            )
        }

        return noAction(state)
    }

    fun reset(
        generation: Long,
        itemId: String?,
    ): PlaybackSessionRecoveryState =
        PlaybackSessionRecoveryState(
            generation = generation,
            itemId = itemId,
        )

    private fun audioRecovery(
        input: PlaybackSessionRecoveryInput,
        plan: PlaybackPlan,
        state: PlaybackSessionRecoveryState,
    ): PlaybackSessionRecoveryResult? {
        val target =
            (input.playbackState.audioActivation as? AudioActivationState.Unavailable)?.target
                ?: return null
        if (
            plan.streamMode != StreamMode.DirectPlay ||
            plan.audioActivationTarget != target ||
            target.itemId != input.itemId ||
            state.lastAudioRecoveryTarget == target
        ) {
            return null
        }
        val nextState = state.copy(lastAudioRecoveryTarget = target)
        return PlaybackSessionRecoveryResult(
            decision = PlaybackSessionRecoveryDecision.AudioActivationRecovery(target),
            state = nextState,
        )
    }

    private fun subtitleRecovery(
        input: PlaybackSessionRecoveryInput,
        plan: PlaybackPlan,
        state: PlaybackSessionRecoveryState,
    ): PlaybackSessionRecoveryResult? {
        val target =
            (input.playbackState.subtitleActivation as? SubtitleActivationState.Unavailable)?.target
                ?: return null
        val plannedTarget = plan.plannedSubtitle.activationTarget()
        if (
            plan.subtitleActivationTarget != target ||
            plannedTarget != target ||
            target.itemId != input.itemId ||
            state.lastSubtitleRecoveryTarget == target
        ) {
            return null
        }

        val plannedSubtitle = plan.plannedSubtitle
        if (plannedSubtitle is PlannedSubtitle.Unavailable && !plannedSubtitle.allowEncodeFallback) {
            return null
        }
        val nextState = state.copy(lastSubtitleRecoveryTarget = target)
        if (plannedSubtitle is PlannedSubtitle.LocalAsset) {
            return PlaybackSessionRecoveryResult(
                decision = PlaybackSessionRecoveryDecision.SubtitleUnavailable(target),
                state = nextState,
            )
        }

        val streamIndex = target.streamIndex
        val format = plannedSubtitle.normalizedFormat()
        if (streamIndex == null || format == null) {
            return PlaybackSessionRecoveryResult(
                decision = PlaybackSessionRecoveryDecision.SubtitleUnavailable(target),
                state = nextState,
            )
        }

        return PlaybackSessionRecoveryResult(
            decision =
                PlaybackSessionRecoveryDecision.SubtitleEncodeRecovery(
                    target = target,
                    requestPolicy =
                        PlaybackInfoRequestPolicy(
                            enableDirectPlay = false,
                            enableDirectStream = false,
                            forceEncodeSubtitle =
                                ForceEncodeSubtitle(
                                    streamIndex = streamIndex,
                                    normalizedFormat = format,
                                ),
                            clientTrigger = PlaybackClientTrigger.SubtitleActivationFallback,
                        ),
                ),
            state = nextState,
        )
    }

    private fun sessionState(input: PlaybackSessionRecoveryInput): PlaybackSessionRecoveryState =
        input.state.takeIf { state ->
            state.generation == input.generation && state.itemId == input.itemId
        } ?: reset(input.generation, input.itemId)

    private fun noAction(state: PlaybackSessionRecoveryState): PlaybackSessionRecoveryResult =
        PlaybackSessionRecoveryResult(PlaybackSessionRecoveryDecision.NoAction, state)
}

fun PlaybackError?.toPlaybackSessionRecoveryTrigger(): AutoPlaybackRecoveryTrigger? =
    when (this) {
        PlaybackError.Decoder -> AutoPlaybackRecoveryTrigger.DecoderFailure
        PlaybackError.UnsupportedMedia,
        PlaybackError.OfflineArtifactUnavailable,
        PlaybackError.Unknown,
        -> AutoPlaybackRecoveryTrigger.UnsupportedMedia
        PlaybackError.AudioOutput,
        PlaybackError.Network,
        PlaybackError.Drm,
        is PlaybackError.OfflinePlayerUnavailable,
        null,
        -> null
    }

private fun PlannedSubtitle.activationTarget(): SubtitleActivationTarget? =
    when (this) {
        is PlannedSubtitle.Track -> activationTarget
        is PlannedSubtitle.LocalAsset -> activationTarget
        is PlannedSubtitle.Unavailable -> activationTarget
        PlannedSubtitle.Off -> null
    }

private fun PlannedSubtitle.normalizedFormat(): String? =
    when (this) {
        is PlannedSubtitle.Track -> normalizedFormat
        is PlannedSubtitle.Unavailable -> normalizedFormat
        PlannedSubtitle.Off,
        is PlannedSubtitle.LocalAsset,
        -> null
    }
