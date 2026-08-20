// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleActivationConfirmationTest {
    @Test
    fun pendingDoesNotExpireUntilReadinessArmsTheTimeout() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            advanceTimeBy(SUBTITLE_ACTIVATION_CONFIRMATION_TIMEOUT_MS * 2)
            runCurrent()

            assertEquals(SubtitleActivationState.Pending(firstTarget), fixture.state)
        }

    @Test
    fun readinessTimeoutExpiresOnceWithoutBeingExtendedByRepeatedObservations() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.armTimeout(firstTarget)
            advanceTimeBy(SUBTITLE_ACTIVATION_CONFIRMATION_TIMEOUT_MS - 1_000L)
            fixture.confirmation.armTimeout(firstTarget)
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(SubtitleActivationState.Unavailable(firstTarget), fixture.state)
        }

    @Test
    fun exactConfirmationCancelsTimeoutAndStaysActive() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.armTimeout(firstTarget)
            fixture.confirmation.confirm(firstTarget)
            advanceTimeBy(SUBTITLE_ACTIVATION_CONFIRMATION_TIMEOUT_MS)
            runCurrent()
            fixture.confirmation.armTimeout(firstTarget)

            assertEquals(SubtitleActivationState.Active(firstTarget), fixture.state)
        }

    @Test
    fun explicitFailureIsImmediate() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.fail(firstTarget)
            assertEquals(SubtitleActivationState.Unavailable(firstTarget), fixture.state)
        }

    @Test
    fun lateConfirmationCanRecoverAfterTimeout() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.armTimeout(firstTarget)
            advanceTimeBy(SUBTITLE_ACTIVATION_CONFIRMATION_TIMEOUT_MS)
            runCurrent()
            assertEquals(SubtitleActivationState.Unavailable(firstTarget), fixture.state)

            fixture.confirmation.confirm(firstTarget)
            assertEquals(SubtitleActivationState.Active(firstTarget), fixture.state)
        }

    @Test
    fun replacementIgnoresStaleCallbacksAndOldTimeout() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.armTimeout(firstTarget)
            fixture.confirmation.begin(secondTarget)
            fixture.confirmation.confirm(firstTarget)
            fixture.confirmation.fail(firstTarget)
            advanceTimeBy(SUBTITLE_ACTIVATION_CONFIRMATION_TIMEOUT_MS)
            runCurrent()

            assertEquals(SubtitleActivationState.Pending(secondTarget), fixture.state)
        }

    @Test
    fun clearCancelsTimeoutAndPublishesNone() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.armTimeout(firstTarget)
            fixture.confirmation.clear()
            advanceTimeBy(SUBTITLE_ACTIVATION_CONFIRMATION_TIMEOUT_MS)
            runCurrent()

            assertEquals(SubtitleActivationState.None, fixture.state)
        }

    @Test
    fun beginForPlanWithMissingExternalAssetPublishesPendingThenUnavailable() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.beginForPlan(plan(firstTarget), subtitleAsset = null)

            assertEquals(
                listOf(
                    SubtitleActivationState.Pending(firstTarget),
                    SubtitleActivationState.Unavailable(firstTarget),
                ),
                fixture.publications,
            )
        }

    @Test
    fun beginForPlanWithExternalAssetStaysPending() =
        runTest {
            val fixture = fixture()
            val asset =
                SubtitleAsset.LocalFile(
                    assetId = "asset-1",
                    fileId = "file-1",
                    mimeType = "text/vtt",
                    label = "English",
                    language = "en",
                )

            fixture.confirmation.beginForPlan(plan(firstTarget), asset)

            assertEquals(SubtitleActivationState.Pending(firstTarget), fixture.state)
        }

    @Test
    fun beginForPlanWithEmbeddedTargetStaysPendingWithoutAnAsset() =
        runTest {
            val fixture = fixture()
            val embeddedTarget = firstTarget.copy(kind = LocalSubtitleKind.EmbeddedText)

            fixture.confirmation.beginForPlan(plan(embeddedTarget), subtitleAsset = null)

            assertEquals(SubtitleActivationState.Pending(embeddedTarget), fixture.state)
        }

    @Test
    fun beginForPlanWithNoTargetClearsThePreviousActivation() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.beginForPlan(plan(subtitleActivationTarget = null), subtitleAsset = null)

            assertEquals(SubtitleActivationState.None, fixture.state)
        }

    private fun kotlinx.coroutines.test.TestScope.fixture(): ConfirmationFixture {
        var state: SubtitleActivationState = SubtitleActivationState.None
        val publications = mutableListOf<SubtitleActivationState>()
        val confirmation =
            SubtitleActivationConfirmation(
                scope = backgroundScope,
                currentState = { state },
                publish = { next ->
                    state = next
                    publications += next
                },
            )
        return ConfirmationFixture(
            confirmation = confirmation,
            stateProvider = { state },
            publications = publications,
        )
    }

    private data class ConfirmationFixture(
        val confirmation: SubtitleActivationConfirmation,
        val stateProvider: () -> SubtitleActivationState,
        val publications: List<SubtitleActivationState>,
    ) {
        val state: SubtitleActivationState
            get() = stateProvider()
    }

    private companion object {
        fun plan(subtitleActivationTarget: SubtitleActivationTarget?): PlaybackPlan =
            PlaybackPlan(
                itemId = "item-1",
                mediaSourceId = "source-1",
                startPositionMs = 0L,
                streamMode = StreamMode.DirectPlay,
                streamUrl = "https://jellyfin.example/Videos/item-1/stream",
                progressReportingPolicy = ProgressReportingPolicy(10_000L),
                subtitleActivationTarget = subtitleActivationTarget,
            )

        val firstTarget =
            SubtitleActivationTarget(
                requestId = 1L,
                itemId = "item-1",
                identity = SubtitleActivationIdentity.JellyfinTrack(4),
                kind = LocalSubtitleKind.ExternalText,
            )
        val secondTarget = firstTarget.copy(requestId = 2L, identity = SubtitleActivationIdentity.JellyfinTrack(5))
    }
}
