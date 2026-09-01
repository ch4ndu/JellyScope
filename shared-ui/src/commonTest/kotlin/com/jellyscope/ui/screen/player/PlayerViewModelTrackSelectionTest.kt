// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.MAX_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleEdgeStyle
import com.jellyscope.core.domain.playback.SubtitleRenderMode
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.SubtitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlayerViewModelTrackSelectionTest {
    @Test
    fun unavailableInitialAudioRecoversOnceWithDirectPlayDisabled() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(directPlayPlaybackInfo),
                                    Result.success(transcodePlaybackInfo),
                                ),
                            ),
                    )
                runCurrent()
                val failedTarget = requireNotNull(fixture.controller.preparedPlan?.audioActivationTarget)

                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 12_000L).copy(
                        audioActivation = AudioActivationState.Unavailable(failedTarget),
                    )
                runCurrent()

                assertEquals(2, fixture.repository.requests.size)
                assertEquals(
                    false,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.enableDirectPlay,
                )
                assertEquals(
                    PlaybackClientTrigger.AudioActivationFallback,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.clientTrigger,
                )
                assertEquals(
                    120_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(1, (fixture.viewModel.state.value as PlayerUiState.Content).selectedAudioStreamIndex)

                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        audioActivation = AudioActivationState.Unavailable(failedTarget),
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)
            } finally {
            }
        }

    @Test
    fun audioRecoveryRejectsAnotherDirectPlayPlanWithoutLooping() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                // This test exists to prove the recovery rejects a server that
                // IGNORES EnableDirectPlay, so the fake must stay non-compliant.
                fixture.repository.honoursRequestPolicy = false
                runCurrent()
                val failedTarget = requireNotNull(fixture.controller.preparedPlan?.audioActivationTarget)

                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing).copy(
                        audioActivation = AudioActivationState.Unavailable(failedTarget),
                    )
                runCurrent()

                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(1, fixture.controller.prepareCount)
            } finally {
            }
        }

    @Test
    fun responseSelectedAudioSubstitutionBecomesRequestedAndInstalledTruth() =
        runPlayerViewModelTest {
            try {
                val memory = PlaybackSelectionMemory()
                val serverSubstitution =
                    directPlayPlaybackInfo.copy(
                        mediaSources =
                            directPlayPlaybackInfo.mediaSources.map { source ->
                                source.copy(defaultAudioStreamIndex = 3)
                            },
                    )
                val fixture = playerFixture(playbackInfo = serverSubstitution, memory = memory)
                runCurrent()

                assertEquals(
                    1,
                    fixture.repository.requests
                        .single()
                        .audioStreamIndex,
                )
                assertEquals(3, (fixture.viewModel.state.value as PlayerUiState.Content).selectedAudioStreamIndex)
                assertEquals(3, memory.selectionFor(session.accountIdentity(), "item-1")?.audioStreamIndex)
            } finally {
            }
        }

    @Test
    fun audioUnavailablePlaybackStateStaysContentAndPublishesNoticeState() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Playing,
                        positionMs = 2_000L,
                        audioUnavailable = true,
                    )
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(true, content.audioUnavailable)
                assertEquals(true, content.playbackState.audioUnavailable)
                assertEquals(PlaybackStatus.Playing, content.playbackState.status)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun selectingEmbeddedAudioOnDirectPlayUsesControllerOrdinalWithoutReplan() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                fixture.viewModel.selectAudio(streamIndex = 3)
                runCurrent()

                // Ordinal 0 is the deliberate prepare-time application of the
                // Jellyfin default track (keeps ExoPlayer's auto-selection in
                // sync with the UI); ordinal 1 is the user's selection.
                assertEquals(listOf(0, 1), fixture.controller.embeddedAudioOrdinals)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(1, fixture.repository.requests.size)
                assertEquals(
                    3,
                    (fixture.viewModel.state.value as PlayerUiState.Content).selectedAudioStreamIndex,
                )
            } finally {
            }
        }

    @Test
    fun subtitleReplanKeepsPreviouslyActiveTrackUntilFrenchPlanIsInstalled() =
        runPlayerViewModelTest {
            try {
                val streams = englishTextAndFrenchPgsPlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(subtitleTranscodePlaybackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val englishContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, englishContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, englishContent.subtitleRenderInfo.status)
                assertEquals("English SRT", englishContent.subtitleRenderInfo.label)

                fixture.viewModel.selectSubtitle(streamIndex = 5)

                val replanningContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, replanningContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, replanningContent.subtitleRenderInfo.status)
                assertEquals("English SRT", replanningContent.subtitleRenderInfo.label)

                runCurrent()

                val frenchContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(5, frenchContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, frenchContent.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, frenchContent.subtitleRenderInfo.mode)
                assertEquals("French PGS", frenchContent.subtitleRenderInfo.label)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun textSubtitleReplanInVideoTranscodePlanStaysServerRenderedWithoutUnavailableState() =
        runPlayerViewModelTest {
            try {
                val streams = englishAndFrenchAssPlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(transcodePlaybackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val englishContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, englishContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, englishContent.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, englishContent.subtitleRenderInfo.mode)

                fixture.viewModel.selectSubtitle(streamIndex = 5)

                val replanningContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, replanningContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, replanningContent.subtitleRenderInfo.status)
                assertEquals("English ASS", replanningContent.subtitleRenderInfo.label)

                runCurrent()

                val frenchContent = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(5, frenchContent.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, frenchContent.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, frenchContent.subtitleRenderInfo.mode)
                assertEquals("French ASS", frenchContent.subtitleRenderInfo.label)
                assertEquals("Server rendered", frenchContent.subtitleRenderInfo.reason)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun newerDirectSubtitleChoiceCancelsPendingFrenchReplan() =
        runPlayerViewModelTest {
            try {
                val streams = englishTextAndFrenchPgsPlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(subtitleTranscodePlaybackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 5)
                fixture.viewModel.selectSubtitle(streamIndex = null)
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(SubtitleRenderStatus.Off, content.subtitleRenderInfo.status)
                assertEquals(null, content.selectedSubtitleStreamIndex)
                assertEquals(null, fixture.controller.embeddedTextOrdinals.last())
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(1, fixture.repository.requests.size)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun embeddedSubtitleOffThenOnUsesControllerWithoutReplan() =
        runPlayerViewModelTest {
            val workDispatcher = StandardTestDispatcher(testScheduler)
            try {
                val fixture =
                    playerFixture(
                        mediaStreams = defaultSubtitlePlaybackStreams(),
                        playbackInfo = playbackInfoWithStreams(defaultSubtitlePlaybackStreams()),
                        initialSubtitleStreamIndex = -1,
                        workDispatcher = workDispatcher,
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        status = PlaybackStatus.Playing,
                        positionMs = 15_000L,
                        bufferedPositionMs = 15_000L,
                    )
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 4)
                runCurrent()

                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(1, fixture.repository.requests.size)
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertIs<PlannedSubtitle.Off>(fixture.controller.preparedPlan?.plannedSubtitle)
                assertEquals(15_000L, fixture.controller.playbackStateFlow.value.positionMs)
                assertEquals(listOf<Int?>(null, 0), fixture.controller.embeddedTextOrdinals)
                val activeTarget =
                    assertIs<SubtitleActivationState.Active>(fixture.controller.playbackStateFlow.value.subtitleActivation).target
                assertEquals(4, activeTarget.streamIndex)
                assertEquals(LocalSubtitleKind.EmbeddedText, activeTarget.kind)
                assertEquals(
                    SubtitleRenderStatus.Active,
                    (fixture.viewModel.state.value as PlayerUiState.Content).subtitleRenderInfo.status,
                )
                assertEquals(
                    SubtitleRenderMode.LocalEmbeddedText,
                    (fixture.viewModel.state.value as PlayerUiState.Content).subtitleRenderInfo.mode,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 15_000L)
                runCurrent()
                fixture.viewModel.dispose()
                advanceUntilIdle()
            } finally {
            }
        }

    @Test
    fun selectingPgsSubtitleReplansWithSubtitleIndexAtCurrentPosition() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(directPlayPlaybackInfo),
                                    Result.success(subtitleTranscodePlaybackInfo),
                                ),
                            ),
                    )
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 42_000L)
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 4)
                runCurrent()

                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(
                    4,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertEquals(
                    420_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(4, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertFalse(fixture.controller.embeddedTextOrdinals.contains(0))
                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(4, content.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, content.subtitleRenderInfo.status)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, content.subtitleRenderInfo.mode)
                assertFalse(content.subtitleRenderInfo.styleable)
                assertEquals(SubtitleRenderMode.ServerBurnedIn, content.debugInfo?.subtitleRenderMode)
                assertFalse(content.debugInfo?.subtitleStyleable ?: true)
                assertEquals(
                    1,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Stopped>()
                        .size,
                )
            } finally {
            }
        }

    @Test
    fun selectingNoneAfterBurnedSubtitleReplansAndKeepsPlaybackActive() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(directPlayPlaybackInfo),
                                    Result.success(subtitleTranscodePlaybackInfo),
                                    Result.success(directPlayPlaybackInfo),
                                ),
                            ),
                    )
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 42_000L)
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 4)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 44_000L)
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = null)
                runCurrent()

                assertEquals(3, fixture.controller.prepareCount)
                assertEquals(
                    -1,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertEquals(
                    440_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertEquals(null, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(null, fixture.controller.preparedPlan?.subtitleActivationTarget)
                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(SubtitleRenderStatus.Off, content.subtitleRenderInfo.status)
                assertEquals(null, content.selectedSubtitleStreamIndex)
                assertEquals(3, fixture.controller.playCount)
                assertEquals(
                    2,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Stopped>()
                        .size,
                )
            } finally {
            }
        }

    @Test
    fun selectingAudioDoesNotFreezeAutoResolvedQualityInDurableStore() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val fixture = playerFixture(selectionStore = store)
                runCurrent()

                // Quality is auto-resolved; the user changes ONLY audio. The auto
                // quality must not freeze durably.
                fixture.viewModel.selectAudio(streamIndex = 3)
                advanceUntilIdle()

                val saved =
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")]
                assertNotNull(saved)
                assertEquals(3, saved.audioStreamIndex)
            } finally {
            }
        }

    @Test
    fun explicitDetailAudioPickMatchingAutomaticDefaultIsPersistedAtLaunch() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                // Detail sends a non-null route only after an explicit action. Even though
                // Spanish also matches automatic language resolution, provenance makes this
                // an explicit choice and it must survive process death.
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        initialAudioStreamIndex = 3,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(preferredAudioLanguage = "spa"),
                            ),
                    )
                runCurrent()
                advanceUntilIdle()

                val saved =
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")]
                assertNotNull(saved)
                assertEquals(3, saved.audioStreamIndex)
            } finally {
            }
        }

    @Test
    fun serverSelectedAudioSubstitutionIsNotWrittenDurably() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                val memory = PlaybackSelectionMemory()
                val substitutedResponse =
                    directPlayPlaybackInfo.copy(
                        mediaSources =
                            directPlayPlaybackInfo.mediaSources.map { source ->
                                source.copy(defaultAudioStreamIndex = 3)
                            },
                    )
                val first =
                    playerFixture(
                        playbackInfo = substitutedResponse,
                        selectionStore = store,
                        memory = memory,
                    )
                runCurrent()
                first.viewModel.dispose()
                runCurrent()

                val reopened =
                    playerFixture(
                        playbackInfo = substitutedResponse,
                        selectionStore = store,
                        memory = memory,
                    )
                runCurrent()

                reopened.viewModel.selectQuality(maxBitrateBps = 4_000_000L)
                advanceUntilIdle()

                assertNull(
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")],
                )
            } finally {
            }
        }

    @Test
    fun explicitDetailAudioPickDistinctFromAutoIsPersistedAtLaunch() =
        runPlayerViewModelTest {
            try {
                val store = FakePlaybackSelectionStore()
                // Auto-resolution is English (index 1, the default track); the user explicitly
                // picked Spanish (index 3) in Detail. That genuine pick — distinct from the
                // auto-resolved track — must persist durably at launch even with no in-player
                // change afterward (an explicit user choice wins).
                val fixture =
                    playerFixture(
                        selectionStore = store,
                        initialAudioStreamIndex = 3,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(preferredAudioLanguage = "eng"),
                            ),
                    )
                runCurrent()
                advanceUntilIdle()

                val saved =
                    store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")]
                assertNotNull(saved)
                assertEquals(3, saved.audioStreamIndex)
            } finally {
            }
        }

    @Test
    fun playbackPreferencesApplyInitialTracksAndBitrateWhileResumeAlwaysResumes() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    defaultMaxBitrateBps = 8_000_000L,
                                    preferredAudioLanguage = "spa",
                                    preferredSubtitleLanguage = "eng",
                                ),
                            ),
                        workDispatcher = dispatcher,
                    )
                runCurrent()

                val request = fixture.repository.requests.first()
                val content = fixture.viewModel.state.value as PlayerUiState.Content

                // Resume is unconditional now: the launch position is always honoured.
                assertEquals(10_000_000L, request.startTimeTicks)
                assertEquals(3, request.audioStreamIndex)
                assertEquals(4, request.subtitleStreamIndex)
                assertEquals(8_000_000L, request.maxStreamingBitrate)
                assertEquals(3, content.selectedAudioStreamIndex)
                assertEquals(4, content.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, content.subtitleRenderInfo.status)
                assertEquals(8_000_000L, content.selectedQualityMaxBitrate)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun defaultSubtitleIsSelectedAtPlaybackStartWhenNoRememberedOrPreferredSelection() =
        runPlayerViewModelTest {
            try {
                val streams = defaultSubtitlePlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfo = playbackInfoWithStreams(streams),
                        mediaStreams = streams,
                    )
                runCurrent()

                val request = fixture.repository.requests.first()
                val content = fixture.viewModel.state.value as PlayerUiState.Content

                assertEquals(4, request.subtitleStreamIndex)
                assertEquals(4, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(4, content.selectedSubtitleStreamIndex)
                assertEquals(listOf<Int?>(0), fixture.controller.embeddedTextOrdinals)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun explicitSubtitleOffSuppressesDefaultSubtitleAtPlaybackStart() =
        runPlayerViewModelTest {
            try {
                val streams = defaultSubtitlePlaybackStreams()
                val memory =
                    PlaybackSelectionMemory().also { selectionMemory ->
                        selectionMemory.remember(
                            accountIdentity = session.accountIdentity(),
                            itemId = "item-1",
                            selection =
                                PlaybackSelection(audioStreamIndex = null),
                        )
                    }
                val fixture =
                    playerFixture(
                        playbackInfo = playbackInfoWithStreams(streams),
                        mediaStreams = streams,
                        memory = memory,
                        initialSubtitleStreamIndex = -1,
                    )
                runCurrent()

                val request = fixture.repository.requests.first()
                val content = fixture.viewModel.state.value as PlayerUiState.Content

                assertEquals(-1, request.subtitleStreamIndex)
                assertEquals(null, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(null, content.selectedSubtitleStreamIndex)
                assertEquals(listOf<Int?>(null), fixture.controller.embeddedTextOrdinals)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun initialTrackIndicesOverrideRememberedAndPreferredDefaults() =
        runPlayerViewModelTest {
            try {
                val memory =
                    PlaybackSelectionMemory().also { selectionMemory ->
                        selectionMemory.remember(
                            accountIdentity = session.accountIdentity(),
                            itemId = "item-1",
                            selection =
                                PlaybackSelection(audioStreamIndex = 1),
                        )
                    }
                val fixture =
                    playerFixture(
                        initialAudioStreamIndex = 3,
                        initialSubtitleStreamIndex = 4,
                        memory = memory,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(
                                    preferredAudioLanguage = "eng",
                                    preferredSubtitleLanguage = "eng",
                                ),
                            ),
                    )
                runCurrent()

                val request = fixture.repository.requests.first()
                val content = fixture.viewModel.state.value as PlayerUiState.Content

                assertEquals(3, request.audioStreamIndex)
                assertEquals(4, request.subtitleStreamIndex)
                assertEquals(3, content.selectedAudioStreamIndex)
                assertEquals(4, content.selectedSubtitleStreamIndex)
                assertEquals(SubtitleRenderStatus.Active, content.subtitleRenderInfo.status)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun playbackSpeedAndSubtitleStyleUpdateControllerAndState() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()
                val style = SubtitleStyle(fontScale = 1.25f, edgeStyle = SubtitleEdgeStyle.Outline)

                fixture.viewModel.setPlaybackSpeed(9f)
                fixture.viewModel.setSubtitleStyle(style)
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(MAX_PLAYBACK_SPEED, fixture.controller.appliedPlaybackSpeed)
                assertEquals(MAX_PLAYBACK_SPEED, content.playbackSpeed)
                assertEquals(MAX_PLAYBACK_SPEED, fixture.viewModel.playbackState.value.playbackSpeed)
                assertEquals(style, fixture.controller.appliedSubtitleStyle)
                assertEquals(style, content.subtitleStyle)
                assertEquals(style, fixture.viewModel.playbackState.value.subtitleStyle)
            } finally {
            }
        }

    @Test
    fun subtitleStyleIsNotOfferedWhenTheBackendCannotApplyIt() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val fixture = playerFixture(appliesSubtitleStyle = false).also { fixtureRef = it }
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                // Whatever the domain decides about the track, a backend that cannot
                // apply appearance must never be offered the control.
                assertEquals(false, content.subtitleStyleable)
                assertEquals(false, content.debugInfo?.subtitleStyleable)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun selectingAndDisablingInstalledLocalSubtitleReplansWithoutCancellingItself() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var fixtureRef: PlayerFixture? = null
            try {
                val asset = localSubtitleAsset()
                val fixture = playerFixture(localSubtitleAsset = asset).also { fixtureRef = it }
                runCurrent()

                fixture.viewModel.selectLocalSubtitle(asset.id)
                runCurrent()

                assertEquals(2, fixture.repository.requests.size)
                assertEquals(
                    -1,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertIs<SubtitleAsset.LocalFile>(fixture.controller.preparedSubtitleAsset)

                fixture.viewModel.selectSubtitle(null)
                runCurrent()

                assertEquals(3, fixture.repository.requests.size)
                assertEquals(
                    -1,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertEquals(null, fixture.controller.preparedSubtitleAsset)
                assertEquals(null, fixture.controller.embeddedTextOrdinals.last())
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
                Dispatchers.resetMain()
            }
        }
}
