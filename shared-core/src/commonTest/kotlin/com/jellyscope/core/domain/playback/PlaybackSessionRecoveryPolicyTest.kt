// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaybackSessionRecoveryPolicyTest {
    private val policy = PlaybackSessionRecoveryPolicy()

    @Test
    fun audioActivationFailureWinsOverDecoderFailureAndCarriesTypedCause() {
        val target = audioTarget(requestId = 1L, streamIndex = 2)
        val result =
            decide(
                plan = plan(audioTarget = target),
                playbackState =
                    state(
                        status = PlaybackStatus.Failed,
                        error = PlaybackError.Decoder,
                        audioActivation = AudioActivationState.Unavailable(target),
                    ),
            )

        val decision = assertIs<PlaybackSessionRecoveryDecision.AudioActivationRecovery>(result.decision)
        assertEquals(target, decision.target)
        assertEquals(false, decision.requestPolicy.enableDirectPlay)
        assertEquals(PlaybackClientTrigger.AudioActivationFallback, decision.requestPolicy.clientTrigger)
        assertEquals(target, result.state.lastAudioRecoveryTarget)
    }

    @Test
    fun laterAudioTargetHasIndependentRecoveryOpportunity() {
        val first = audioTarget(requestId = 1L, streamIndex = 2)
        val second = audioTarget(requestId = 2L, streamIndex = 3)
        val firstResult =
            decide(
                plan = plan(audioTarget = first),
                playbackState = state(audioActivation = AudioActivationState.Unavailable(first)),
            )
        val duplicate =
            decide(
                plan = plan(audioTarget = first),
                playbackState = state(audioActivation = AudioActivationState.Unavailable(first)),
                recoveryState = firstResult.state,
            )
        val secondResult =
            decide(
                plan = plan(audioTarget = second),
                playbackState = state(audioActivation = AudioActivationState.Unavailable(second)),
                recoveryState = firstResult.state,
            )

        assertIs<PlaybackSessionRecoveryDecision.NoAction>(duplicate.decision)
        assertEquals(second, assertIs<PlaybackSessionRecoveryDecision.AudioActivationRecovery>(secondResult.decision).target)
    }

    @Test
    fun audioActivationRequiresDirectPlayAndExactCurrentTarget() {
        val requested = audioTarget(requestId = 1L, streamIndex = 2)
        val stale = requested.copy(requestId = 0L)

        assertIs<PlaybackSessionRecoveryDecision.NoAction>(
            decide(
                plan = plan(audioTarget = requested),
                playbackState = state(audioActivation = AudioActivationState.Unavailable(stale)),
            ).decision,
        )
        assertIs<PlaybackSessionRecoveryDecision.NoAction>(
            decide(
                plan = plan(streamMode = StreamMode.DirectStream, audioTarget = requested),
                playbackState = state(audioActivation = AudioActivationState.Unavailable(requested)),
            ).decision,
        )
    }

    @Test
    fun jellyfinSubtitleFailureRequestsExactEncodeWithTypedCause() {
        val target = subtitleTarget(requestId = 4L, streamIndex = 7)
        val result =
            decide(
                plan =
                    plan(
                        subtitleTarget = target,
                        plannedSubtitle =
                            PlannedSubtitle.Track(
                                streamIndex = 7,
                                embeddedTrack = null,
                                deliveryMethod = SubtitleDeliveryMethod.External,
                                kind = SubtitleKind.Text,
                                activationTarget = target,
                                normalizedFormat = "srt",
                            ),
                    ),
                playbackState = state(subtitleActivation = SubtitleActivationState.Unavailable(target)),
            )

        val decision = assertIs<PlaybackSessionRecoveryDecision.SubtitleEncodeRecovery>(result.decision)
        assertEquals(target, decision.target)
        assertEquals(false, decision.requestPolicy.enableDirectPlay)
        assertEquals(false, decision.requestPolicy.enableDirectStream)
        assertEquals(7, decision.requestPolicy.forceEncodeSubtitle?.streamIndex)
        assertEquals("srt", decision.requestPolicy.forceEncodeSubtitle?.normalizedFormat)
        assertEquals(PlaybackClientTrigger.SubtitleActivationFallback, decision.requestPolicy.clientTrigger)
    }

    @Test
    fun subtitleFailureRequiresPlanAndPlannedSubtitleToMatchExactRequest() {
        val current = subtitleTarget(requestId = 2L, streamIndex = 7)
        val stale = current.copy(requestId = 1L)
        val plannedSubtitle =
            PlannedSubtitle.Track(
                streamIndex = 7,
                embeddedTrack = null,
                deliveryMethod = SubtitleDeliveryMethod.External,
                kind = SubtitleKind.Text,
                activationTarget = stale,
                normalizedFormat = "srt",
            )

        assertIs<PlaybackSessionRecoveryDecision.NoAction>(
            decide(
                plan = plan(subtitleTarget = current, plannedSubtitle = plannedSubtitle),
                playbackState = state(subtitleActivation = SubtitleActivationState.Unavailable(current)),
            ).decision,
        )
    }

    @Test
    fun localSubtitleFailureNeverRequestsServerEncode() {
        val target =
            SubtitleActivationTarget(
                requestId = 3L,
                itemId = ITEM_ID,
                identity = SubtitleActivationIdentity.LocalAsset("asset-1"),
                kind = LocalSubtitleKind.ExternalText,
            )
        val result =
            decide(
                plan =
                    plan(
                        subtitleTarget = target,
                        plannedSubtitle =
                            PlannedSubtitle.LocalAsset(
                                assetId = "asset-1",
                                kind = SubtitleKind.Text,
                                activationTarget = target,
                            ),
                    ),
                playbackState = state(subtitleActivation = SubtitleActivationState.Unavailable(target)),
            )

        assertEquals(target, assertIs<PlaybackSessionRecoveryDecision.SubtitleUnavailable>(result.decision).target)
    }

    @Test
    fun networkFailureRetriesOnceWithoutChangingStreamPolicy() {
        val first =
            decide(
                plan = plan(),
                playbackState = state(status = PlaybackStatus.Failed, error = PlaybackError.Network),
            )
        val second =
            decide(
                plan = plan(),
                playbackState = state(status = PlaybackStatus.Failed, error = PlaybackError.Network),
                recoveryState = first.state,
            )

        val retry = assertIs<PlaybackSessionRecoveryDecision.NetworkRetry>(first.decision)
        assertEquals(true, retry.requestPolicy.enableDirectPlay)
        assertEquals(true, retry.requestPolicy.enableDirectStream)
        assertEquals(PlaybackClientTrigger.NetworkRetry, retry.requestPolicy.clientTrigger)
        assertIs<PlaybackSessionRecoveryDecision.NoAction>(second.decision)
    }

    @Test
    fun offlineNetworkFailureDoesNotRetry() {
        assertIs<PlaybackSessionRecoveryDecision.NoAction>(
            decide(
                plan = plan(streamMode = StreamMode.Offline),
                playbackState = state(status = PlaybackStatus.Failed, error = PlaybackError.Network),
            ).decision,
        )
    }

    @Test
    fun offlineNativeFailuresNeverRequestAutomaticRecoveryOrTrackReplan() {
        val target = audioTarget(requestId = 1L, streamIndex = 2)
        assertIs<PlaybackSessionRecoveryDecision.NoAction>(
            decide(
                plan = plan(streamMode = StreamMode.Offline, audioTarget = target),
                playbackState =
                    state(
                        status = PlaybackStatus.Failed,
                        error = PlaybackError.Decoder,
                        audioActivation = AudioActivationState.Unavailable(target),
                    ),
            ).decision,
        )
        assertIs<PlaybackSessionRecoveryDecision.NoAction>(
            decide(
                plan = plan(streamMode = StreamMode.Offline),
                playbackState =
                    state(
                        status = PlaybackStatus.Failed,
                        error = PlaybackError.UnsupportedMedia,
                        subtitleActivation =
                            SubtitleActivationState.Unavailable(
                                subtitleTarget(requestId = 1L, streamIndex = 7),
                            ),
                    ),
            ).decision,
        )
    }

    @Test
    fun newGenerationOrItemResetsAttemptFacts() {
        val spent =
            PlaybackSessionRecoveryState(
                generation = GENERATION,
                itemId = ITEM_ID,
                networkRetryAttempted = true,
            )

        assertIs<PlaybackSessionRecoveryDecision.NetworkRetry>(
            decide(
                generation = GENERATION + 1L,
                plan = plan(),
                playbackState = state(status = PlaybackStatus.Failed, error = PlaybackError.Network),
                recoveryState = spent,
            ).decision,
        )
        assertIs<PlaybackSessionRecoveryDecision.NetworkRetry>(
            decide(
                itemId = "item-2",
                plan = plan(itemId = "item-2"),
                playbackState = state(status = PlaybackStatus.Failed, error = PlaybackError.Network),
                recoveryState = spent,
            ).decision,
        )
    }

    @Test
    fun decoderAndUnsupportedFailuresMapToAutomaticRecovery() {
        assertEquals(
            AutoPlaybackRecoveryTrigger.DecoderFailure,
            assertIs<PlaybackSessionRecoveryDecision.AutomaticRecovery>(
                decide(
                    plan = plan(),
                    playbackState = state(status = PlaybackStatus.Failed, error = PlaybackError.Decoder),
                ).decision,
            ).trigger,
        )
        assertEquals(
            AutoPlaybackRecoveryTrigger.UnsupportedMedia,
            assertIs<PlaybackSessionRecoveryDecision.AutomaticRecovery>(
                decide(
                    plan = plan(),
                    playbackState = state(status = PlaybackStatus.Failed, error = PlaybackError.Unknown),
                ).decision,
            ).trigger,
        )
    }

    private fun decide(
        generation: Long = GENERATION,
        itemId: String = ITEM_ID,
        plan: PlaybackPlan?,
        playbackState: PlaybackState,
        recoveryState: PlaybackSessionRecoveryState = PlaybackSessionRecoveryState(),
    ): PlaybackSessionRecoveryResult =
        policy.decide(
            PlaybackSessionRecoveryInput(
                generation = generation,
                itemId = itemId,
                plan = plan,
                playbackState = playbackState,
                state = recoveryState,
            ),
        )

    private fun plan(
        itemId: String = ITEM_ID,
        streamMode: StreamMode = StreamMode.DirectPlay,
        audioTarget: AudioActivationTarget? = null,
        subtitleTarget: SubtitleActivationTarget? = null,
        plannedSubtitle: PlannedSubtitle = PlannedSubtitle.Off,
    ): PlaybackPlan =
        PlaybackPlan(
            itemId = itemId,
            mediaSourceId = "source-1",
            startPositionMs = 0L,
            streamMode = streamMode,
            streamUrl = "https://example.invalid/stream",
            progressReportingPolicy = ProgressReportingPolicy(10_000L),
            audioActivationTarget = audioTarget,
            subtitleActivationTarget = subtitleTarget,
            plannedSubtitle = plannedSubtitle,
        )

    private fun state(
        status: PlaybackStatus = PlaybackStatus.Playing,
        error: PlaybackError? = null,
        audioActivation: AudioActivationState = AudioActivationState.None,
        subtitleActivation: SubtitleActivationState = SubtitleActivationState.None,
    ): PlaybackState =
        PlaybackState(
            status = status,
            positionMs = 4_000L,
            durationMs = 60_000L,
            bufferedPositionMs = 8_000L,
            audioActivation = audioActivation,
            subtitleActivation = subtitleActivation,
            error = error,
        )

    private fun audioTarget(
        requestId: Long,
        streamIndex: Int,
    ): AudioActivationTarget = AudioActivationTarget(requestId, ITEM_ID, streamIndex)

    private fun subtitleTarget(
        requestId: Long,
        streamIndex: Int,
    ): SubtitleActivationTarget =
        SubtitleActivationTarget(
            requestId = requestId,
            itemId = ITEM_ID,
            identity = SubtitleActivationIdentity.JellyfinTrack(streamIndex),
            kind = LocalSubtitleKind.ExternalText,
        )

    private companion object {
        const val GENERATION = 9L
        const val ITEM_ID = "item-1"
    }
}
