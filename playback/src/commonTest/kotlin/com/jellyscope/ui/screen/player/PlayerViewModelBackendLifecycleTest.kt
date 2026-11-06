// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceDirectPlayProfile
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackContentTimeline
import com.jellyscope.core.domain.playback.PlaybackContentTimelineSource
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerVolumeState
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.domain.playback.androidPlayerBackendPolicy
import com.jellyscope.core.domain.playback.applePlayerBackendPolicy
import com.jellyscope.core.domain.playback.resolvePlayerBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlayerViewModelBackendLifecycleTest {
    @Test
    fun desktopAutoKeepsTheConcreteBackendOwnedByTheActiveController() {
        assertTrue(
            activeControllerSatisfiesBackend(
                resolvedBackend = PlayerBackend.Auto,
                trackedBackend = PlayerBackend.LibVlc,
                activeBackend = PlayerBackend.LibVlc,
            ),
        )
        assertFalse(
            activeControllerSatisfiesBackend(
                resolvedBackend = PlayerBackend.Auto,
                trackedBackend = PlayerBackend.LibVlc,
                activeBackend = PlayerBackend.Auto,
            ),
        )
    }

    @Test
    fun pendingControllerConstructsResolvedDefaultOnceOnWorkAndPublishesConcreteController() =
        runPlayerViewModelTest {
            val workDispatcher = RecordingDispatcher(Dispatchers.Main)
            val concreteController = FakePlayerController(confirmInitialAudio = true)
            concreteController.activeBackend = PlayerBackend.AVPlayer
            val requests = mutableListOf<PlayerBackend>()
            val fixture =
                playerFixture(
                    initialControllerIsPending = true,
                    deviceProfileProvider = appleOfflineProfileProvider(),
                    workDispatcher = workDispatcher,
                    playerControllerFactory = { backend ->
                        check(workDispatcher.running)
                        requests += backend
                        concreteController
                    },
                )

            runCurrent()

            assertEquals(listOf(PlayerBackend.AVPlayer), requests)
            assertEquals(1, fixture.controller.releaseCount)
            assertEquals(concreteController, fixture.viewModel.currentPlayerController)
            assertEquals(1, concreteController.prepareCount)
            fixture.viewModel.dispose()
        }

    @Test
    fun itemBackendOverrideConstructsOnlyTheOverriddenBackend() =
        runPlayerViewModelTest {
            val overriddenController = FakePlayerController(confirmInitialAudio = true)
            overriddenController.activeBackend = PlayerBackend.VlcKit
            val factoryRequests = mutableListOf<PlayerBackend>()
            val fixture =
                playerFixture(
                    initialControllerIsPending = true,
                    deviceProfileProvider = appleOfflineProfileProvider(),
                    playerBackendOverrideStore = FakePlayerBackendOverrideStore(PlayerBackend.VlcKit),
                    playerControllerFactory = { backend ->
                        factoryRequests += backend
                        overriddenController
                    },
                )

            runCurrent()

            assertEquals(listOf(PlayerBackend.VlcKit), factoryRequests)
            assertEquals(overriddenController, fixture.viewModel.currentPlayerController)
            assertEquals(1, overriddenController.prepareCount)
            fixture.viewModel.dispose()
        }

    @Test
    fun appleAutoRoutesOnPreferredLaunchAudioRatherThanFirstAudioStream() =
        runPlayerViewModelTest {
            // English AAC (index 1) is the default and first; Spanish DTS (index 3) is preferred.
            val streams = playbackStreams.map { stream -> if (stream.index == 3) stream.copy(codec = "dts") else stream }
            val version =
                MediaVersion(id = "source-1", name = "Source", mediaStreams = streams, container = "mp4", runtime = 90.seconds)
            val profileProvider =
                object : DeviceProfileProvider {
                    override val backendPolicy = applePlayerBackendPolicy()
                    override val availableBackends: Set<PlayerBackend> = setOf(PlayerBackend.AVPlayer, PlayerBackend.VlcKit)

                    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                        DeviceDecodingCapabilities(
                            videoCodecs = listOf("h264"),
                            audioCodecs = listOf("aac"),
                            supportsDolbyVision = false,
                            directPlayProfiles = listOf(DeviceDirectPlayProfile(listOf("mp4"), listOf("h264"), listOf("aac"))),
                        )
                }
            // Guard: describing the first Audio stream would admit AVPlayer, so only launch audio can route to VLCKit.
            assertEquals(
                PlayerBackend.AVPlayer,
                resolvePlayerBackend(
                    defaultBackend = PlayerBackend.Auto,
                    itemOverride = null,
                    source =
                        BackendSourceDescriptor(
                            container = version.container,
                            videoCodec = streams.first { stream -> stream.type == "Video" }.codec,
                            audioCodec = streams.first { stream -> stream.type == "Audio" }.codec,
                            isHdrOrDolbyVision = false,
                        ),
                    avPlayerCapabilities = profileProvider.capabilities(PlayerBackend.AVPlayer),
                    backendPolicy = profileProvider.backendPolicy,
                ),
            )
            val vlcController = FakePlayerController(confirmInitialAudio = true)
            vlcController.activeBackend = PlayerBackend.VlcKit
            val factoryRequests = mutableListOf<PlayerBackend>()
            val store = FakePlaybackSelectionStore()
            val fixture =
                playerFixture(
                    detailItems =
                        mapOf(
                            "item-1" to MediaItem(id = "item-1", name = "Item", kind = MediaKind.Movie, versions = listOf(version)),
                        ),
                    selectionStore = store,
                    deviceProfileProvider = profileProvider,
                    playbackPreferencesStore =
                        FakePlaybackPreferencesStore(
                            PlaybackPreferences(
                                defaultPlayerBackend = PlayerBackend.Auto,
                                preferredAudioLanguage = "spa",
                                vlcTranscodeMaxBitrateBps = 8_000_000L,
                            ),
                        ),
                    playerControllerFactory = { backend ->
                        factoryRequests += backend
                        vlcController
                    },
                )

            runCurrent()

            assertEquals(listOf(PlayerBackend.VlcKit), factoryRequests)
            val request = fixture.repository.requests.first()
            assertEquals(3, request.audioStreamIndex)
            assertEquals(8_000_000L, request.maxStreamingBitrate)
            advanceUntilIdle()
            // The preferred-language pick is not an explicit choice, so nothing is saved durably.
            assertNull(store.values[PlaybackSelectionKey(session.serverId, session.userId, "item-1", "source-1")])
            fixture.viewModel.dispose()
        }

    @Test
    fun targetBackendPlanningFailureKeepsHealthyPlaybackAndReportingInstalled() =
        runPlayerViewModelTest {
            val fixture =
                playerFixture(
                    activeBackend = PlayerBackend.AVPlayer,
                    deviceProfileProvider = appleOfflineProfileProvider(),
                    playbackInfoResults =
                        ArrayDeque(
                            listOf(
                                Result.success(transcodePlaybackInfo),
                                Result.failure(IllegalStateException("target planning failed")),
                            ),
                        ),
                    playerControllerFactory = { error("target planning failure must not construct a controller") },
                )

            try {
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 12_000L)
                runCurrent()
                val healthyPlan = assertNotNull(fixture.controller.preparedPlan)

                fixture.controller.transcodeSeekRestartsStream = true
                fixture.repository.ordinaryPlanGate = CompletableDeferred()
                fixture.viewModel.accept(PlayerIntent.SeekTo(40_000L))
                runCurrent()
                fixture.viewModel.accept(PlayerIntent.SelectBackend(PlayerBackend.AVPlayer))
                assertEquals(true, assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).pendingSeekPlayWhenReady)
                fixture.repository.ordinaryPlanGate = null
                fixture.viewModel.accept(PlayerIntent.ShowPicker(PlayerPicker.Backend))
                val playCountBeforeRestoration = fixture.controller.playCount
                fixture.viewModel.accept(PlayerIntent.SelectBackend(PlayerBackend.VlcKit))
                runCurrent()

                assertEquals(playCountBeforeRestoration + 1, fixture.controller.playCount)
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value
                        .copy(status = PlaybackStatus.Playing)
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertSame(fixture.controller, fixture.viewModel.currentPlayerController)
                assertSame(healthyPlan, fixture.controller.preparedPlan)
                assertEquals(PlayerBackend.AVPlayer, content.activeBackend)
                assertEquals("item-1", content.playbackItemId)
                assertEquals(PlayerPicker.None, content.pickerVisible)
                assertNotNull(content.playbackChangeNotice)
                assertNull(content.pendingSeekPlayWhenReady)
                assertEquals(PlaybackStatus.Playing, content.playbackState.status)
                assertEquals(0, fixture.controller.releaseCount)
                assertTrue(fixture.reporter.reports.none { report -> report is Report.Stopped })
            } finally {
                fixture.viewModel.dispose()
                fixture.viewModel.viewModelScope.cancel()
                runCurrent()
            }
        }

    @Test
    fun requestedConstructionFailureFallsBackOnlyAfterOutgoingReleaseAndRecordsActualBackend() =
        runPlayerViewModelTest {
            val fallbackController = FakePlayerController(confirmInitialAudio = true)
            fallbackController.activeBackend = PlayerBackend.ExoPlayer
            val factoryRequests = mutableListOf<PlayerBackend>()
            lateinit var outgoingController: FakePlayerController
            val fixture =
                playerFixture(
                    activeBackend = PlayerBackend.VlcKit,
                    deviceProfileProvider = appleOfflineProfileProvider(),
                    playerControllerFactory = { backend ->
                        factoryRequests += backend
                        when (backend) {
                            PlayerBackend.AVPlayer -> {
                                error("requested controller construction failed")
                            }
                            PlayerBackend.ExoPlayer -> fallbackController
                            else -> error("unexpected backend: $backend")
                        }
                    },
                )
            outgoingController = fixture.controller

            runCurrent()

            assertEquals(listOf(PlayerBackend.AVPlayer, PlayerBackend.ExoPlayer), factoryRequests)
            assertEquals(1, outgoingController.releaseCount)
            assertEquals(fallbackController, fixture.viewModel.currentPlayerController)
            assertEquals(1, fallbackController.prepareCount)
            assertEquals(
                PlayerBackend.ExoPlayer,
                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).debugInfo?.backend,
            )
            fixture.viewModel.dispose()
        }

    @Test
    fun initialControllerFactoryFailureUsesStartupErrorThenRetryInstallsConcreteController() =
        runPlayerViewModelTest {
            val recoveredController = FakePlayerController(confirmInitialAudio = true)
            recoveredController.activeBackend = PlayerBackend.AVPlayer
            var factoryAttempts = 0
            val fixture =
                playerFixture(
                    initialControllerIsPending = true,
                    deviceProfileProvider = appleOfflineProfileProvider(),
                    playerControllerFactory = {
                        factoryAttempts += 1
                        if (factoryAttempts <= 2) {
                            error("controller construction failed")
                        }
                        recoveredController
                    },
                )

            runCurrent()

            assertEquals(PlayerUiState.Error(error = PlaybackError.Unknown), fixture.viewModel.state.value)
            assertEquals(1, fixture.controller.releaseCount)
            assertEquals(0, fixture.controller.prepareCount)
            assertEquals(0, fixture.controller.playCount)

            fixture.viewModel.accept(PlayerIntent.Retry)
            runCurrent()

            assertEquals(3, factoryAttempts)
            assertEquals(recoveredController, fixture.viewModel.currentPlayerController)
            assertEquals(1, recoveredController.prepareCount)
            assertEquals(0, fixture.controller.prepareCount)
            fixture.viewModel.dispose()
        }

    @Test
    fun disposalBeforeInitialCandidateMainTransferReleasesOutgoingAndCandidateOnce() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val workScheduler = TestCoroutineScheduler()
            Dispatchers.setMain(mainDispatcher)
            try {
                val candidate = FakePlayerController(confirmInitialAudio = true)
                candidate.activeBackend = PlayerBackend.AVPlayer
                val candidateReturned = CompletableDeferred<Unit>()
                val factoryRequests = mutableListOf<PlayerBackend>()
                var factoryCount = 0
                val workDispatcher = RecordingDispatcher(StandardTestDispatcher(workScheduler))
                val fixture =
                    playerFixture(
                        initialControllerIsPending = true,
                        deviceProfileProvider = appleOfflineProfileProvider(),
                        workDispatcher = workDispatcher,
                        playerControllerFactory = { backend ->
                            assertTrue(workDispatcher.running)
                            factoryRequests += backend
                            factoryCount += 1
                            candidateReturned.complete(Unit)
                            candidate
                        },
                    )

                var schedulerTurns = 0
                while (!candidateReturned.isCompleted && schedulerTurns < 20) {
                    runCurrent()
                    if (!candidateReturned.isCompleted) {
                        workScheduler.runCurrent()
                    }
                    schedulerTurns += 1
                }

                assertTrue(candidateReturned.isCompleted)
                assertEquals(listOf(PlayerBackend.AVPlayer), factoryRequests)
                assertEquals(1, factoryCount)
                assertFalse(fixture.viewModel.currentPlayerController === candidate)

                fixture.viewModel.dispose()
                runCurrent()
                fixture.viewModel.dispose()

                assertEquals(1, fixture.controller.releaseCount)
                assertEquals(1, candidate.releaseCount)
                assertEquals(0, candidate.prepareCount)
                assertEquals(0, candidate.playCount)
                assertFalse(fixture.viewModel.currentPlayerController === candidate)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedRequestedAndFallbackReplacementDoesNotRereleaseOutgoingOnDispose() =
        runPlayerViewModelTest {
            val factoryRequests = mutableListOf<PlayerBackend>()
            val fixture =
                playerFixture(
                    activeBackend = PlayerBackend.VlcKit,
                    deviceProfileProvider = appleOfflineProfileProvider(),
                    playerControllerFactory = { backend ->
                        factoryRequests += backend
                        error("construction failure")
                    },
                )

            runCurrent()

            assertEquals(listOf(PlayerBackend.AVPlayer, PlayerBackend.ExoPlayer), factoryRequests)
            assertEquals(PlayerUiState.Error(error = PlaybackError.Unknown), fixture.viewModel.state.value)
            assertEquals(1, fixture.controller.releaseCount)

            fixture.viewModel.dispose()
            fixture.viewModel.dispose()

            assertEquals(1, fixture.controller.releaseCount)
        }

    @Test
    fun repeatedDisposeSettlesInstalledControllerAndStopReportingOnce() =
        runPlayerViewModelTest {
            val fixture = playerFixture()
            runCurrent()
            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
            runCurrent()

            fixture.viewModel.dispose()
            fixture.viewModel.dispose()
            runCurrent()

            assertEquals(1, fixture.controller.releaseCount)
            assertEquals(
                1,
                fixture.reporter.reports
                    .filterIsInstance<Report.Stopped>()
                    .size,
            )
        }

    @Test
    fun publishesVolumeControlAndDelegatesVolumeActions() =
        runPlayerViewModelTest {
            try {
                val volumeController = FakeVolumePlayerController(confirmInitialAudio = true)
                val fixture = playerFixture(volumeController = volumeController)
                runCurrent()

                assertEquals(PlayerVolumeState(), (fixture.viewModel.state.value as PlayerUiState.Content).volumeControl)

                fixture.viewModel.accept(PlayerIntent.SetVolume(35))
                fixture.viewModel.accept(PlayerIntent.ToggleMute)
                runCurrent()

                assertEquals(PlayerVolumeState(volumePercent = 35, muted = true), volumeController.volumeStateFlow.value)
                assertEquals(
                    PlayerVolumeState(volumePercent = 35, muted = true),
                    (fixture.viewModel.state.value as PlayerUiState.Content).volumeControl,
                )
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun hidesVolumeControlAndLeavesActionsInertWithoutVolumeInterface() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()

                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).volumeControl)
                fixture.viewModel.accept(PlayerIntent.SetVolume(35))
                fixture.viewModel.accept(PlayerIntent.ToggleMute)
                assertNull((fixture.viewModel.state.value as PlayerUiState.Content).volumeControl)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun exposesDisplayRefreshRateMatchingSetting() =
        runPlayerViewModelTest {
            try {
                val deviceSettings = FakePlayerDeviceSettingsStore(PlayerDeviceSettings(matchDisplayRefreshRate = true))
                val fixture = playerFixture(playerDeviceSettingsStore = deviceSettings)
                runCurrent()

                assertTrue(fixture.viewModel.matchDisplayRefreshRate.value)

                deviceSettings.setSettings(PlayerDeviceSettings(matchDisplayRefreshRate = false))
                runCurrent()

                assertFalse(fixture.viewModel.matchDisplayRefreshRate.value)
            } finally {
            }
        }

    @Test
    fun alternateControllerFactoryRunsOnWorkDispatcherAndGuardsOldControls() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            try {
                val workDispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
                val replacementController = FakePlayerController(confirmInitialAudio = true)
                replacementController.activeBackend = PlayerBackend.ExoPlayer
                lateinit var initialController: FakePlayerController
                lateinit var viewModel: PlayerViewModel
                val profileProvider =
                    object : DeviceProfileProvider {
                        override val backendPolicy = androidPlayerBackendPolicy()

                        override val availableBackends: Set<PlayerBackend> =
                            setOf(PlayerBackend.LibVlc, PlayerBackend.ExoPlayer)

                        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                            DeviceDecodingCapabilities(
                                videoCodecs = listOf("h264"),
                                audioCodecs = listOf("aac"),
                                supportsDolbyVision = false,
                            )
                    }
                val fixture =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        deviceProfileProvider = profileProvider,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultPlayerBackend = PlayerBackend.LibVlc),
                            ),
                        workDispatcher = workDispatcher,
                        playerControllerFactory = { backend ->
                            if (backend == PlayerBackend.ExoPlayer) {
                                check(workDispatcher.running)
                                val previousPlayCount = initialController.playCount
                                viewModel.accept(PlayerIntent.Play)
                                assertEquals(previousPlayCount, initialController.playCount)
                                replacementController
                            } else {
                                initialController
                            }
                        },
                    )
                initialController = fixture.controller
                viewModel = fixture.viewModel
                runCurrent()

                initialController.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder, positionMs = 4_000L)
                runCurrent()

                assertEquals(PlayerBackend.ExoPlayer, replacementController.activeBackend)
                assertTrue(replacementController.prepareCount > 0)
                assertTrue(workDispatcher.dispatchCount > 0)

                viewModel.dispose()
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun stopDuringControllerInstallReportsOnceAndPreventsFallbackReplan() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            try {
                val workDispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
                val replacementController = FakePlayerController(confirmInitialAudio = true)
                replacementController.activeBackend = PlayerBackend.ExoPlayer
                lateinit var initialController: FakePlayerController
                lateinit var viewModel: PlayerViewModel
                val profileProvider =
                    object : DeviceProfileProvider {
                        override val backendPolicy = androidPlayerBackendPolicy()
                        override val availableBackends: Set<PlayerBackend> =
                            setOf(PlayerBackend.LibVlc, PlayerBackend.ExoPlayer)

                        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                            DeviceDecodingCapabilities(
                                videoCodecs = listOf("h264"),
                                audioCodecs = listOf("aac"),
                                supportsDolbyVision = false,
                            )
                    }
                val fixture =
                    playerFixture(
                        activeBackend = PlayerBackend.LibVlc,
                        guidancePolicy = TestGuidancePolicy.Advisory,
                        deviceProfileProvider = profileProvider,
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultPlayerBackend = PlayerBackend.LibVlc),
                            ),
                        workDispatcher = workDispatcher,
                        playerControllerFactory = { backend ->
                            if (backend == PlayerBackend.ExoPlayer) {
                                check(workDispatcher.running)
                                viewModel.accept(PlayerIntent.Stop)
                                assertEquals(0, initialController.stopCount)
                                replacementController
                            } else {
                                initialController
                            }
                        },
                    )
                initialController = fixture.controller
                viewModel = fixture.viewModel
                runCurrent()

                initialController.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 4_000L)
                runCurrent()
                assertEquals(
                    1,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Start>()
                        .size,
                )
                initialController.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder, positionMs = 4_000L)
                runCurrent()

                assertEquals(1, replacementController.releaseCount)
                assertEquals(0, replacementController.stopCount)
                assertEquals(0, replacementController.prepareCount)
                assertEquals(
                    1,
                    fixture.reporter.reports
                        .filterIsInstance<Report.Stopped>()
                        .size,
                )

                viewModel.dispose()
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun exposesPlaybackExtensionsInStateAndPreparedPlan() =
        runPlayerViewModelTest {
            try {
                val segments =
                    listOf(
                        MediaSegment(MediaSegmentType.Intro, startTicks = 10_000_000L, endTicks = 30_000_000L),
                    )
                val chapters = listOf(Chapter(name = "Opening", startTicks = 0L))
                val trickplay =
                    TrickplayInfo(
                        mediaSourceId = "source-1",
                        resolutionKey = "320",
                        width = 320,
                        height = 180,
                        tileWidth = 10,
                        tileHeight = 10,
                        thumbnailWidth = 320,
                        thumbnailHeight = 180,
                        thumbnailCount = 120,
                        intervalMs = 10_000L,
                    )
                val fixture =
                    playerFixture(
                        mediaSegments = segments,
                        chapters = chapters,
                        trickplayByMediaSourceId = mapOf("source-1" to trickplay),
                    )
                runCurrent()

                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(chapters, content.chapters)
                assertEquals(segments, content.mediaSegments)
                assertEquals(trickplay, content.trickplay)
                assertEquals(
                    listOf(
                        "https://jellyfin.example/Videos/item-1/Trickplay/320/0.jpg?MediaSourceId=source-1",
                        "https://jellyfin.example/Videos/item-1/Trickplay/320/1.jpg?MediaSourceId=source-1",
                    ),
                    content.trickplayTileUrls,
                )
                assertEquals(chapters, fixture.controller.preparedPlan?.chapters)
                assertEquals(segments, fixture.controller.preparedPlan?.mediaSegments)
                assertEquals(trickplay, fixture.controller.preparedPlan?.trickplay)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_500L)
                runCurrent()

                assertEquals(
                    MediaSegmentType.Intro,
                    fixture.viewModel.playbackState.value.currentSegment
                        ?.type,
                )
                assertEquals(
                    MediaSegmentType.Intro,
                    (fixture.viewModel.state.value as PlayerUiState.Content).currentSegment?.type,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun cycleResizeModePreservesAllSupportedStateTransitions() =
        runPlayerViewModelTest {
            val cases =
                listOf(
                    false to listOf(PlayerResizeMode.Fit, PlayerResizeMode.Fill, PlayerResizeMode.Zoom, PlayerResizeMode.Fit),
                    true to listOf(PlayerResizeMode.Fit, PlayerResizeMode.Fill, PlayerResizeMode.Fit),
                )
            cases.forEach { (twoState, expectedModes) ->
                var fixtureRef: PlayerFixture? = null
                try {
                    val fixture = playerFixture().also { fixtureRef = it }
                    runCurrent()
                    expectedModes.drop(1).forEach { expectedMode ->
                        fixture.viewModel.accept(PlayerIntent.CycleResizeMode(twoState = twoState))
                        assertEquals(
                            expectedMode,
                            (fixture.viewModel.state.value as PlayerUiState.Content).resizeMode,
                        )
                    }
                } finally {
                    fixtureRef?.viewModel?.dispose()
                    runCurrent()
                }
            }
        }

    @Test
    fun initialPreparePublishingFailureRejectsInstalledTruthAndPlayerCommands() =
        runPlayerViewModelTest {
            val boundedItem =
                MediaItem(
                    id = "item-1",
                    name = "Item",
                    kind = MediaKind.Movie,
                    versions =
                        listOf(
                            MediaVersion(
                                id = "source-1",
                                name = "Source",
                                mediaStreams = playbackStreams,
                                runtime = 90.seconds,
                            ),
                        ),
                )
            val fixture = playerFixture(detailItems = mapOf("item-1" to boundedItem))
            fixture.controller.preparePublishedState = playbackState(PlaybackStatus.Failed)

            runCurrent()

            val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
            assertEquals(1, fixture.controller.prepareCount)
            assertEquals(0, fixture.controller.playCount)
            assertTrue(fixture.controller.embeddedAudioOrdinals.isEmpty())
            assertTrue(fixture.controller.embeddedTextOrdinals.isEmpty())

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused)
            runCurrent()
            assertEquals(error, fixture.viewModel.state.value)
            fixture.viewModel.dispose()
        }

    @Test
    fun sameItemReplanPublishingFailureKeepsInstalledTruthRevokedAndSkipsCommands() =
        runPlayerViewModelTest {
            val boundedItem =
                MediaItem(
                    id = "item-1",
                    name = "Item",
                    kind = MediaKind.Movie,
                    versions =
                        listOf(
                            MediaVersion(
                                id = "source-1",
                                name = "Source",
                                mediaStreams = playbackStreams,
                                runtime = 90.seconds,
                            ),
                        ),
                )
            val fixture = playerFixture(detailItems = mapOf("item-1" to boundedItem))
            runCurrent()
            assertTrue(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).isSeekable)
            val playCount = fixture.controller.playCount
            val audioSelectionCount = fixture.controller.embeddedAudioOrdinals.size
            val subtitleSelectionCount = fixture.controller.embeddedTextOrdinals.size
            fixture.controller.preparePublishedState = playbackState(PlaybackStatus.Failed)

            fixture.viewModel.accept(PlayerIntent.SelectQualityBitrate(maxBitrateBps = 8_000_000L))
            runCurrent()

            val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
            assertEquals(2, fixture.controller.prepareCount)
            assertEquals(playCount, fixture.controller.playCount)
            assertEquals(audioSelectionCount, fixture.controller.embeddedAudioOrdinals.size)
            assertEquals(subtitleSelectionCount, fixture.controller.embeddedTextOrdinals.size)

            fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused)
            runCurrent()
            assertEquals(error, fixture.viewModel.state.value)
            fixture.viewModel.dispose()
        }

    @Test
    fun sameItemReplanPrepareFailureLeavesInstalledSeekabilityRevoked() {
        val expectedFailure = IllegalStateException("prepare failed")
        val thrown =
            assertFailsWith<IllegalStateException> {
                runPlayerViewModelTest {
                    val boundedItem =
                        MediaItem(
                            id = "item-1",
                            name = "Item",
                            kind = MediaKind.Movie,
                            versions =
                                listOf(
                                    MediaVersion(
                                        id = "source-1",
                                        name = "Source",
                                        mediaStreams = playbackStreams,
                                        runtime = 90.seconds,
                                    ),
                                ),
                        )
                    val fixture = playerFixture(detailItems = mapOf("item-1" to boundedItem))
                    runCurrent()
                    assertTrue(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).isSeekable)
                    fixture.controller.prepareFailure = expectedFailure

                    fixture.viewModel.accept(PlayerIntent.SelectQualityBitrate(maxBitrateBps = 8_000_000L))
                    runCurrent()

                    assertFalse(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).isSeekable)
                    fixture.viewModel.dispose()
                }
            }
        assertSame(expectedFailure, thrown)
    }

    @Test
    fun sameItemRetryRestoresSeekabilityFromConcreteRetainedPlan() =
        runPlayerViewModelTest {
            val unboundedItem =
                MediaItem(
                    id = "item-1",
                    name = "Item",
                    kind = MediaKind.Movie,
                    versions = listOf(MediaVersion(id = "source-1", name = "Source", mediaStreams = playbackStreams)),
                )
            // The controller re-prepares a bounded snapshot, so only its own retained plan can grant seeking.
            val controller =
                object : FakePlayerController(confirmInitialAudio = true, publishPrepareEpoch = true) {
                    override val retainedPlan: PlaybackPlan?
                        get() = preparedPlan

                    override fun retry() {
                        super.retry()
                        val retained = preparedPlan ?: error("retry requires a prepared plan")
                        val retryPositionMs = playbackStateFlow.value.positionMs
                        prepare(
                            retained.copy(
                                startPositionMs = retryPositionMs,
                                contentTimeline =
                                    PlaybackContentTimeline.BoundedVod(
                                        durationMs = 90.seconds.inWholeMilliseconds,
                                        source = PlaybackContentTimelineSource.SelectedMediaSource,
                                    ),
                            ),
                        )
                    }
                }
            controller.activeBackend = PlayerBackend.VlcKit
            controller.preparePublishedState = playbackState(PlaybackStatus.Failed, positionMs = 20_000L)
            val fixture =
                playerFixture(
                    detailItems = mapOf("item-1" to unboundedItem),
                    initialControllerIsPending = true,
                    deviceProfileProvider = appleOfflineProfileProvider(),
                    playerBackendOverrideStore = FakePlayerBackendOverrideStore(PlayerBackend.VlcKit),
                    playerControllerFactory = { controller },
                )

            runCurrent()

            assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
            assertEquals(1, controller.prepareCount)
            assertEquals(PlaybackContentTimeline.UnknownOrUnbounded, controller.preparedPlan?.contentTimeline)
            assertEquals(1_000L, controller.preparedPlan?.startPositionMs)
            controller.preparePublishedState = playbackState(PlaybackStatus.Paused)

            fixture.viewModel.accept(PlayerIntent.Retry)
            runCurrent()

            assertSame(controller, fixture.viewModel.currentPlayerController)
            assertEquals(2, controller.prepareCount)
            assertEquals(1, controller.retryCount)
            assertEquals(20_000L, controller.preparedPlan?.startPositionMs)
            assertEquals(0L, controller.playbackState.value.positionMs)
            assertTrue(assertIs<PlayerUiState.Content>(fixture.viewModel.state.value).isSeekable)
            fixture.viewModel.dispose()
            runCurrent()
            assertEquals(
                20_000L,
                fixture.reporter.reports
                    .filterIsInstance<Report.Stopped>()
                    .single()
                    .positionMs,
            )
        }

    @Test
    fun ordinaryRetryRepeatsOnlyARecordedUnresolvedInadmissibleAudioFallback() =
        runPlayerViewModelTest {
            // The mp4 detail source routes to AVPlayer while the mkv response stamps both AAC switches inadmissible.
            val version =
                MediaVersion(id = "source-1", name = "Source", mediaStreams = playbackStreams, container = "mp4", runtime = 90.seconds)
            val profileProvider =
                object : DeviceProfileProvider {
                    override val backendPolicy = applePlayerBackendPolicy()
                    override val availableBackends: Set<PlayerBackend> = setOf(PlayerBackend.AVPlayer, PlayerBackend.VlcKit)

                    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                        DeviceDecodingCapabilities(
                            videoCodecs = listOf("h264"),
                            audioCodecs = listOf("aac"),
                            supportsDolbyVision = false,
                            directPlayProfiles = listOf(DeviceDirectPlayProfile(listOf("mp4"), listOf("h264"), listOf("aac"))),
                        )
                }
            // Like AVPlayer, native Retry re-prepares the retained plan and restarts Audio mapping as Pending.
            val controller =
                object : FakePlayerController(confirmInitialAudio = true, publishPrepareEpoch = true) {
                    override fun retry() {
                        super.retry()
                        val retained = checkNotNull(preparedPlan)
                        prepare(retained)
                        playbackStateFlow.value =
                            playbackStateFlow.value.copy(
                                audioActivation = AudioActivationState.Pending(checkNotNull(retained.audioActivationTarget)),
                            )
                    }
                }
            controller.activeBackend = PlayerBackend.AVPlayer
            val fixture =
                playerFixture(
                    detailItems =
                        mapOf(
                            "item-1" to MediaItem(id = "item-1", name = "Item", kind = MediaKind.Movie, versions = listOf(version)),
                        ),
                    playbackInfoResults =
                        ArrayDeque(
                            listOf(
                                Result.success(directPlayPlaybackInfo),
                                Result.failure(IllegalStateException("fallback failed")),
                            ),
                        ),
                    initialControllerIsPending = true,
                    deviceProfileProvider = profileProvider,
                    playerControllerFactory = { controller },
                )
            val requests = fixture.repository.requests

            runCurrent()
            val initialPlan = checkNotNull(controller.preparedPlan)
            assertEquals(StreamMode.DirectPlay, initialPlan.streamMode)
            assertEquals(listOf(false, false), initialPlan.embeddedAudioTracks.map { track -> track.directPlayAdmissible })
            controller.playbackStateFlow.value = controller.playbackStateFlow.value.copy(status = PlaybackStatus.Playing)
            runCurrent()
            controller.playbackStateFlow.value =
                controller.playbackStateFlow.value.copy(status = PlaybackStatus.Failed, error = PlaybackError.Drm)
            runCurrent()
            assertEquals(PlayerUiState.Error(error = PlaybackError.Drm), fixture.viewModel.state.value)
            assertEquals(1, requests.size)

            // The server-approved initial Audio has no recorded fallback, so its switch stamp alone is no gap.
            fixture.viewModel.accept(PlayerIntent.Retry)
            runCurrent()
            assertEquals(1, requests.size)
            controller.playbackStateFlow.value =
                controller.playbackStateFlow.value.copy(status = PlaybackStatus.Playing, error = null)
            runCurrent()

            fixture.viewModel.accept(PlayerIntent.SelectAudio(3))
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals(PlayerUiState.Error(error = PlaybackError.UnsupportedMedia), fixture.viewModel.state.value)
            assertEquals(3, requests.last().audioStreamIndex)
            assertFalse(requests.last().requestPolicy.enableDirectPlay)

            // Native Retry re-maps Audio 1, so only the recorded unresolved Audio 3 fallback repeats.
            fixture.viewModel.accept(PlayerIntent.Retry)
            runCurrent()
            assertEquals(3, requests.size)
            val fallbackRequest = requests.last()
            assertEquals(3, fallbackRequest.audioStreamIndex)
            assertFalse(fallbackRequest.requestPolicy.enableDirectPlay)
            assertEquals(PlaybackClientTrigger.AudioActivationFallback, fallbackRequest.requestPolicy.clientTrigger)
            assertEquals(StreamMode.Transcode, controller.preparedPlan?.streamMode)
            fixture.viewModel.dispose()
            runCurrent()
        }
}
