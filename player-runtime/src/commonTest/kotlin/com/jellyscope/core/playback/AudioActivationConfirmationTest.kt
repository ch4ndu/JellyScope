// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AudioActivationConfirmationTest {
    @Test
    fun pendingDoesNotExpireBeforeReadiness() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            advanceTimeBy(AUDIO_ACTIVATION_TIMEOUT_MS * 2)
            runCurrent()

            assertEquals(AudioActivationState.Pending(firstTarget), fixture.state)
        }

    @Test
    fun readinessStartsOneNonExtendingTimeout() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.armTimeout(firstTarget)
            advanceTimeBy(AUDIO_ACTIVATION_TIMEOUT_MS - 1_000L)
            fixture.confirmation.armTimeout(firstTarget)
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(AudioActivationState.Unavailable(firstTarget), fixture.state)
        }

    @Test
    fun exactActivationCancelsTimeoutAndReleasesPlayIntent() =
        runTest {
            var activations = 0
            val fixture = fixture(onActivated = { activations += 1 })

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.armTimeout(firstTarget)
            fixture.confirmation.confirm(firstTarget)
            advanceTimeBy(AUDIO_ACTIVATION_TIMEOUT_MS)
            runCurrent()

            assertEquals(AudioActivationState.Active(firstTarget), fixture.state)
            assertEquals(1, activations)
        }

    @Test
    fun duplicateConfirmationPublishesAndReleasesPlayIntentOnce() =
        runTest {
            var activations = 0
            val fixture = fixture(onActivated = { activations += 1 })

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.confirm(firstTarget)
            fixture.confirmation.confirm(firstTarget)

            assertEquals(AudioActivationState.Active(firstTarget), fixture.state)
            assertEquals(
                listOf(
                    AudioActivationState.Pending(firstTarget),
                    AudioActivationState.Active(firstTarget),
                ),
                fixture.publications,
            )
            assertEquals(1, activations)
        }

    @Test
    fun matchingConfirmationRecoversFromUnavailable() =
        runTest {
            var activations = 0
            val fixture = fixture(onActivated = { activations += 1 })

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.fail(firstTarget, NativeTrackMappingResult.NotFound)
            fixture.confirmation.confirm(firstTarget)

            assertEquals(AudioActivationState.Active(firstTarget), fixture.state)
            assertEquals(1, activations)
        }

    @Test
    fun replacementCancelsOldTimeoutAndIgnoresStaleCallbacks() =
        runTest {
            val fixture = fixture()

            fixture.confirmation.begin(firstTarget)
            fixture.confirmation.armTimeout(firstTarget)
            fixture.confirmation.begin(secondTarget)
            fixture.confirmation.confirm(firstTarget)
            fixture.confirmation.fail(firstTarget, NativeTrackMappingResult.NotFound)
            advanceTimeBy(AUDIO_ACTIVATION_TIMEOUT_MS)
            runCurrent()

            assertEquals(AudioActivationState.Pending(secondTarget), fixture.state)
        }

    private fun kotlinx.coroutines.test.TestScope.fixture(onActivated: () -> Unit = {}): ConfirmationFixture {
        var state: AudioActivationState = AudioActivationState.None
        val publications = mutableListOf<AudioActivationState>()
        val confirmation =
            AudioActivationConfirmation(
                scope = backgroundScope,
                platform = PlaybackDiagnosticPlatform.Desktop,
                currentState = { state },
                publish = { next ->
                    state = next
                    publications += next
                },
                onActivated = onActivated,
            )
        return ConfirmationFixture(confirmation, { state }, publications)
    }

    private data class ConfirmationFixture(
        val confirmation: AudioActivationConfirmation,
        val stateProvider: () -> AudioActivationState,
        val publications: List<AudioActivationState>,
    ) {
        val state: AudioActivationState
            get() = stateProvider()
    }

    private companion object {
        val firstTarget = AudioActivationTarget(requestId = 1L, itemId = "item-1", streamIndex = 4)
        val secondTarget = AudioActivationTarget(requestId = 2L, itemId = "item-1", streamIndex = 5)
    }
}
