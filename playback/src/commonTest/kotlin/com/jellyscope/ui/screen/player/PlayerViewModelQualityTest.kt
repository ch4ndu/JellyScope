// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.VideoCodecResolution
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerViewModelQualityTest {
    @Test
    fun keepingRecoveredQualityReplansAsAnExplicitFixedSessionChoice() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()
                val initialRequestCount = fixture.repository.requests.size

                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }

                val recoveredLimit =
                    assertIs<PlaybackBitrateConstraint.AutoSessionLimit>(
                        fixture.repository.requests
                            .last()
                            .requestPolicy.bitrateConstraint,
                    )
                fixture.viewModel.handlePlaybackAction(PlaybackAction.KeepCurrentQuality)
                runCurrent()

                assertEquals(initialRequestCount + 2, fixture.repository.requests.size)
                val keptRequest = fixture.repository.requests.last()
                assertEquals(recoveredLimit.bitrateBps, keptRequest.maxStreamingBitrate)
                assertIs<PlaybackBitrateConstraint.ExactUserLimit>(keptRequest.requestPolicy.bitrateConstraint)
                assertEquals(PlaybackQualityCapOrigin.ExplicitSessionChoice, keptRequest.requestPolicy.qualityCapOrigin)
                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals("Fixed", content.debugInfo?.qualityPolicyMode)
                assertEquals(PlaybackQualityPolicyOrigin.SessionOverride, content.debugInfo?.qualityPolicyOrigin)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun explicitAutoRecoveryThenAudioChangePersistsAutoInsteadOfTheSessionCap() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                var nowMs = 100_000L
                val store = FakePlaybackSelectionStore()
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Actionable,
                        monotonicTimeMs = { nowMs },
                    ).also { fixtureRef = it }
                runCurrent()

                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                runCurrent()

                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }
                runCurrent()

                val recoveryRequest = fixture.repository.requests.last()
                assertIs<PlaybackBitrateConstraint.AutoSessionLimit>(
                    recoveryRequest.requestPolicy.bitrateConstraint,
                )

                fixture.viewModel.selectAudio(streamIndex = 3)
                runCurrent()
                advanceTimeBy(150L)
                runCurrent()

                val saved =
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")]
                assertEquals(3, assertNotNull(saved).audioStreamIndex)
            } finally {
                fixtureRef?.viewModel?.dispose()
                advanceUntilIdle()
            }
        }

    @Test
    fun explicitQualityChangeClearsAdvisoryGuidanceButDoesNotRearmTheItemSession() =
        runPlayerViewModelTest {
            try {
                var nowMs = 100_000L
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        hasReliableBufferingTransitions = true,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        monotonicTimeMs = { nowMs },
                    )
                runCurrent()
                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = stall.toLong())
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering, positionMs = stall.toLong())
                    runCurrent()
                }
                assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                fixture.viewModel.selectQuality(null)
                runCurrent()
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                nowMs += 10_000L
                repeat(3) { stall ->
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 10_000L + stall)
                    runCurrent()
                    nowMs += 5_000L
                    fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Buffering, positionMs = 10_000L + stall)
                    runCurrent()
                }
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun selectingQualityCapReplansWithMaxBitrateAndResumesPosition() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 55_000L)
                runCurrent()

                fixture.viewModel.selectQuality(maxBitrateBps = 8_000_000L)
                runCurrent()

                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(
                    8_000_000L,
                    fixture.repository.requests
                        .last()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    550_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(8_000_000L, fixture.controller.preparedPlan?.maxStreamingBitrate)
            } finally {
            }
        }

    @Test
    fun selectingOriginalReplansWithOriginalPolicyAndNoFiniteClientLimit() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                fixture.viewModel.selectQuality(maxBitrateBps = null)
                runCurrent()

                assertEquals(
                    null,
                    fixture.repository.requests
                        .last()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    PlaybackQualityMode.Original,
                    fixture.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
            } finally {
            }
        }

    @Test
    fun originalSettingsDefaultReachesPlannerAsOriginalPolicy() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Original),
                            ),
                    )
                runCurrent()

                assertEquals(
                    null,
                    fixture.repository.requests
                        .single()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    PlaybackQualityMode.Original,
                    fixture.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
            } finally {
            }
        }

    @Test
    fun vlcDefaultQualityAppliesToTheInitialRequestAndExplainsItsSource() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    defaultQualityPolicy = PlaybackQualityPolicy.Auto,
                                    vlcTranscodeMaxBitrateBps = 8_000_000L,
                                ),
                            ),
                    )
                runCurrent()

                val request = fixture.repository.requests.single()
                assertEquals(8_000_000L, request.maxStreamingBitrate)
                assertIs<PlaybackBitrateConstraint.ExactUserLimit>(request.requestPolicy.bitrateConstraint)
                assertEquals(PlaybackQualityCapOrigin.SettingsDefault, request.requestPolicy.qualityCapOrigin)
                assertEquals(VideoCodecResolution(1_920, 1_080), request.requestPolicy.qualityResolutionCap)

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(PlaybackQualityPolicy.fixed(8_000_000L), content.inheritedQualityPolicy)
                assertTrue(content.inheritedQualityUsesVlcSetting)
                assertFalse(content.qualityOverrideExplicit)
            } finally {
            }
        }

    @Test
    fun explicitOriginalOverridesVlcDefaultOnlyForTheCurrentPlayback() =
        runPlayerViewModelTest {
            try {
                val preferences =
                    FakePlaybackPreferencesStore(
                        PlaybackPreferences(
                            defaultQualityPolicy = PlaybackQualityPolicy.Auto,
                            vlcTranscodeMaxBitrateBps = 8_000_000L,
                        ),
                    )
                val first =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        playbackPreferencesStore = preferences,
                    )
                runCurrent()

                first.viewModel.selectQuality(PlaybackQualityPolicy.Original)
                runCurrent()

                assertNull(
                    first.repository.requests
                        .last()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    PlaybackQualityMode.Original,
                    first.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
                first.viewModel.dispose()
                runCurrent()

                val reopened =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        playbackPreferencesStore = preferences,
                    )
                runCurrent()

                assertEquals(
                    8_000_000L,
                    reopened.repository.requests
                        .first()
                        .maxStreamingBitrate,
                )
                assertEquals(
                    PlaybackQualityMode.Fixed,
                    reopened.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
            } finally {
            }
        }

    @Test
    fun chooseLowerQualityNoticeActionOpensTheInPlayerQualityPicker() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                fixture.viewModel.handlePlaybackAction(PlaybackAction.ChooseLowerQuality)

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(PlayerPicker.Quality, content.pickerVisible)
            } finally {
            }
        }

    @Test
    fun playbackSelectionMemoryReappliesForSameItemButNotQualityForNextItem() =
        runPlayerViewModelTest {
            try {
                val memory = PlaybackSelectionMemory()
                val first = playerFixture(memory = memory)
                runCurrent()
                first.viewModel.selectAudio(streamIndex = 3)
                first.viewModel.selectSubtitle(streamIndex = null)
                first.viewModel.selectQuality(maxBitrateBps = 12_000_000L)
                runCurrent()

                val sameItem = playerFixture(memory = memory)
                runCurrent()

                assertEquals(
                    3,
                    sameItem.repository.requests
                        .first()
                        .audioStreamIndex,
                )
                assertEquals(
                    -1,
                    sameItem.repository.requests
                        .first()
                        .subtitleStreamIndex,
                )
                assertEquals(
                    null,
                    sameItem.repository.requests
                        .first()
                        .maxStreamingBitrate,
                )

                val nextItem = playerFixture(itemId = "item-2", memory = memory)
                runCurrent()

                assertEquals(
                    1,
                    nextItem.repository.requests
                        .first()
                        .audioStreamIndex,
                )
                assertEquals(
                    null,
                    nextItem.repository.requests
                        .first()
                        .maxStreamingBitrate,
                )
            } finally {
            }
        }

    @Test
    fun selectingQualityDoesNotFreezeAutoResolvedAudioInDurableStore() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val fixture = playerFixture(selectionStore = store)
                runCurrent()

                // Audio is auto-resolved (never explicitly picked); the user changes
                // ONLY quality. Auto-resolved audio must not freeze into a
                // durable title override.
                fixture.viewModel.selectQuality(maxBitrateBps = 4_000_000L)
                advanceUntilIdle()

                assertNull(
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")],
                )
            } finally {
            }
        }

    @Test
    fun selectingAutoIsSessionOnly() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Original),
                            ),
                    )
                runCurrent()

                fixture.viewModel.selectQuality(PlaybackQualityPolicy.Auto)
                advanceUntilIdle()

                assertNull(
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")],
                )
                assertEquals(
                    PlaybackQualityMode.Auto,
                    fixture.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
            } finally {
            }
        }

    @Test
    fun clearingQualityOverrideReinheritsTheServerDefault() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Original),
                            ),
                    )
                runCurrent()
                fixture.viewModel.selectQuality(PlaybackQualityPolicy.fixed(4_000_000L))
                advanceUntilIdle()

                fixture.viewModel.clearQualityOverride()
                advanceUntilIdle()

                assertNull(
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")],
                )
                assertEquals(
                    PlaybackQualityMode.Original,
                    fixture.controller.preparedPlan
                        ?.qualityPolicy
                        ?.mode,
                )
                assertNull(
                    fixture.repository.requests
                        .last()
                        .maxStreamingBitrate,
                )
            } finally {
            }
        }
}
