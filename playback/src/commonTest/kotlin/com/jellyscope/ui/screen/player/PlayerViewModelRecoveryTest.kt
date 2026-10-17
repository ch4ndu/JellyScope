// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthGuidanceReason
import com.jellyscope.core.domain.playback.PlaybackPlanningException
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleRenderMode
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.androidPlayerBackendPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
import kotlin.test.assertTrue

class PlayerViewModelRecoveryTest {
    @Test
    fun failureShowsRetryableErrorAndRetryDelegatesToController() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Failed)
                runCurrent()

                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(Report.Stopped(positionMs = 0L), fixture.reporter.reports.last())

                fixture.viewModel.retry()
                runCurrent()

                assertEquals(1, fixture.controller.retryCount)
            } finally {
            }
        }

    @Test
    fun initialPlaybackInfoFailureKeepsVideoAndDoesNotGuessSubtitleDelivery() =
        runPlayerViewModelTest {
            try {
                val streams = defaultSubtitlePlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults = ArrayDeque(listOf(Result.failure(IllegalStateException("offline")))),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertEquals(SubtitleRenderStatus.Unavailable, content.subtitleRenderInfo.status)
                assertEquals(1, fixture.repository.requests.size)
            } finally {
            }
        }

    @Test
    fun originalCapabilityRefusalShowsNonRetryableUnsupportedMediaAtStartup() =
        runPlayerViewModelTest {
            var fixture: PlayerFixture? = null
            try {
                val streams = explicitlyExcludedVideoStreams()
                fixture =
                    playerFixture(
                        playbackInfo = playbackInfoWithStreams(streams),
                        mediaStreams = streams,
                        deviceProfileProvider = explicitlyExcludedVideoProfileProvider(),
                        playbackPreferencesStore =
                            FakePlaybackPreferencesStore(
                                PlaybackPreferences(defaultQualityPolicy = PlaybackQualityPolicy.Original),
                            ),
                    )
                runCurrent()

                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertFalse(error.retryable)
                assertEquals(PlaybackError.UnsupportedMedia, error.error)
                assertEquals(1, fixture.repository.requests.size)
                assertEquals(0, fixture.controller.prepareCount)
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun exhaustedCapabilityRecoveryShowsNonRetryableUnsupportedMediaAtStartup() =
        runPlayerViewModelTest {
            var fixture: PlayerFixture? = null
            try {
                val streams = explicitlyExcludedVideoStreams()
                fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(noSupportedPlaybackInfo),
                                ),
                            ),
                        mediaStreams = streams,
                        deviceProfileProvider = explicitlyExcludedVideoProfileProvider(),
                    )
                runCurrent()

                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertFalse(error.retryable)
                assertEquals(PlaybackError.UnsupportedMedia, error.error)
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(0, fixture.controller.prepareCount)
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun capabilityRecoveryRepositoryFailureKeepsRetryableNetworkErrorAtStartup() =
        runPlayerViewModelTest {
            var fixture: PlayerFixture? = null
            try {
                val streams = explicitlyExcludedVideoStreams()
                fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.failure(
                                        PlaybackPlanningException.RemoteRequestFailed(
                                            isNetworkFailure = true,
                                            sourceExceptionType = "NotReachable",
                                        ),
                                    ),
                                ),
                            ),
                        mediaStreams = streams,
                        deviceProfileProvider = explicitlyExcludedVideoProfileProvider(),
                    )
                runCurrent()

                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertTrue(error.retryable)
                assertEquals(PlaybackError.Network, error.error)
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(0, fixture.controller.prepareCount)
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun unrelatedStartupPlanningFailureUsesUnknownError() =
        runPlayerViewModelTest {
            var fixture: PlayerFixture? = null
            try {
                fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(Result.success(noSupportedPlaybackInfo)),
                            ),
                    )
                runCurrent()

                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertTrue(error.retryable)
                assertEquals(PlaybackError.Unknown, error.error)
                assertEquals(1, fixture.repository.requests.size)
                assertEquals(0, fixture.controller.prepareCount)
            } finally {
                fixture?.viewModel?.dispose()
                runCurrent()
            }
        }

    @Test
    fun decoderFailureReplansWithDirectPlayDisabledBeforeShowingError() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        error = PlaybackError.Decoder,
                    )
                runCurrent()

                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(2, fixture.repository.requests.size)
                val fallbackRequest = fixture.repository.requests.last()
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectPlay)
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectStream)
                assertEquals(2, fixture.controller.prepareCount)
                assertTrue(fixture.controller.playCount >= 1)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun decoderRecoveryWaitsUntilPictureInPictureExits() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()
                fixture.viewModel.setPictureInPictureMode(true)

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        error = PlaybackError.Decoder,
                    )
                runCurrent()

                assertEquals(1, fixture.repository.requests.size)
                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)

                fixture.viewModel.setPictureInPictureMode(false)
                runCurrent()

                assertEquals(2, fixture.repository.requests.size)
                val fallbackRequest = fixture.repository.requests.last()
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectPlay)
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectStream)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun networkFailureRetriesOnceAtPositionWithDefaultPolicy() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 1_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()

                // One position-preserving replan with the DEFAULT policy (a
                // network drop is not a codec problem, so no ladder step).
                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(2, fixture.repository.requests.size)
                val retryRequest = fixture.repository.requests.last()
                assertEquals(true, retryRequest.requestPolicy.enableDirectPlay)
                assertEquals(true, retryRequest.requestPolicy.enableDirectStream)
                assertEquals(PlaybackClientTrigger.NetworkRetry, retryRequest.requestPolicy.clientTrigger)
                assertEquals(10_000_000L, retryRequest.startTimeTicks)
                assertEquals(2, fixture.controller.prepareCount)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun secondNetworkFailureInSameItemSessionIsFatal() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 1_000L)
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 1_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 2_000L)
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(
                        status = PlaybackStatus.Failed,
                        positionMs = 2_000L,
                        error = PlaybackError.Network,
                    )
                runCurrent()

                // The one-shot is per item playback session (NOT per installed
                // plan): the second failure surfaces fatally instead of looping.
                val error = assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(PlaybackError.Network, error.error)
                assertEquals(2, fixture.repository.requests.size)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun recoveredGuidanceWaitsForReplacementPlanToReachPlaying() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackPreferencesStore = warningsEnabledPreferencesStore(),
                        guidancePolicy = TestGuidancePolicy.Advisory,
                    )
                runCurrent()

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder)
                runCurrent()
                assertNull((fixture.viewModel.state.value as? PlayerUiState.Content)?.passiveGuidance)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                assertEquals(
                    PlaybackHealthGuidanceReason.RecoveredPlaybackFailure,
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance).reason,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun advisoryRecoverySurvivesReplacementWithAnotherController() =
        runPlayerViewModelTest {
            try {
                val replacementController = FakePlayerController(confirmInitialAudio = true)
                replacementController.activeBackend = PlayerBackend.ExoPlayer
                lateinit var initialController: FakePlayerController
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
                                PlaybackPreferences(
                                    playbackWarningsEnabled = true,
                                    defaultPlayerBackend = PlayerBackend.LibVlc,
                                ),
                            ),
                        playerControllerFactory = { backend ->
                            if (backend == PlayerBackend.ExoPlayer) replacementController else initialController
                        },
                    )
                initialController = fixture.controller
                runCurrent()

                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder)
                runCurrent()
                assertNull((fixture.viewModel.state.value as? PlayerUiState.Content)?.passiveGuidance)

                replacementController.playbackStateFlow.value = playbackState(PlaybackStatus.Playing)
                runCurrent()
                assertEquals(
                    PlaybackHealthGuidanceReason.RecoveredPlaybackFailure,
                    assertNotNull((fixture.viewModel.state.value as PlayerUiState.Content).passiveGuidance).reason,
                )

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun decoderFallbackPlaybackInfoFailureDoesNotReinstallDirectPlay() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(directPlayPlaybackInfo),
                                    Result.failure(IllegalStateException("retry failed")),
                                ),
                            ),
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Failed, error = PlaybackError.Decoder)
                runCurrent()

                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(
                    false,
                    fixture.repository.requests
                        .last()
                        .requestPolicy.enableDirectPlay,
                )
            } finally {
            }
        }

    @Test
    fun retryAfterInitialPlanningFailureRunsFullLoadPath() =
        runPlayerViewModelTest {
            try {
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(noSupportedPlaybackInfo),
                                ),
                            ),
                    )
                runCurrent()

                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(0, fixture.controller.prepareCount)

                fixture.viewModel.retry()
                runCurrent()

                assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(0, fixture.controller.retryCount)
                assertEquals(2, fixture.repository.requests.size)
            } finally {
            }
        }

    @Test
    fun transcodeSeekOutsideProducedWindowRestartsTranscodeAtTarget() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(playbackInfo = transcodePlaybackInfo)
                fixture.controller.transcodeSeekRestartsStream = true
                runCurrent()
                val prepareCountBefore = fixture.controller.prepareCount
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 5_000L).copy(bufferedPositionMs = 6_000L)
                runCurrent()

                fixture.viewModel.seekTo(30_000L)
                runCurrent()

                // No in-stream seek; the transcode is restarted at the target instead.
                assertEquals(emptyList<Long>(), fixture.controller.seekPositions)
                assertEquals(prepareCountBefore + 1, fixture.controller.prepareCount)
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(30_000L, fixture.controller.preparedPlan?.startPositionMs)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun transcodeSeekInsideProducedWindowSeeksInStream() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture(playbackInfo = transcodePlaybackInfo)
                fixture.controller.transcodeSeekRestartsStream = true
                runCurrent()
                val prepareCountBefore = fixture.controller.prepareCount
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 5_000L).copy(bufferedPositionMs = 6_000L)
                runCurrent()

                // 4s is inside [start=1s, buffered=6s]; already produced, so seek in-stream.
                fixture.viewModel.seekTo(4_000L)
                runCurrent()

                assertEquals(listOf(4_000L), fixture.controller.seekPositions)
                assertEquals(prepareCountBefore, fixture.controller.prepareCount)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun transcodeSeekSeeksInStreamWhenControllerDoesNotRestart() =
        runPlayerViewModelTest {
            try {
                // Android/desktop controllers leave transcodeSeekRestartsStream = false:
                // even an out-of-window transcode seek stays an in-stream seek (unchanged behavior).
                val fixture = playerFixture(playbackInfo = transcodePlaybackInfo)
                runCurrent()
                val prepareCountBefore = fixture.controller.prepareCount
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 5_000L).copy(bufferedPositionMs = 6_000L)
                runCurrent()

                fixture.viewModel.seekTo(30_000L)
                runCurrent()

                assertEquals(listOf(30_000L), fixture.controller.seekPositions)
                assertEquals(prepareCountBefore, fixture.controller.prepareCount)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun seekAfterSubtitleEncodeFallbackUsesDefaultPolicyAgain() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val encodedStreams =
                    streams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                    Result.success(playbackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                fixture.controller.transcodeSeekRestartsStream = true
                runCurrent()

                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 12_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)

                fixture.viewModel.seekTo(30_000L)
                runCurrent()

                val seekRequest = fixture.repository.requests.last()
                assertEquals(300_000_000L, seekRequest.startTimeTicks)
                assertEquals(true, seekRequest.requestPolicy.enableDirectPlay)
                assertEquals(true, seekRequest.requestPolicy.enableDirectStream)
                assertNull(seekRequest.requestPolicy.forceEncodeSubtitle)
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertEquals(30_000L, fixture.controller.preparedPlan?.startPositionMs)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun replanWhilePausedRemainsPaused() =
        runPlayerViewModelTest {
            try {
                val fixture = playerFixture()
                runCurrent()
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Paused, positionMs = 30_000L)
                runCurrent()
                val playCountBefore = fixture.controller.playCount

                fixture.viewModel.selectQuality(maxBitrateBps = 8_000_000L)
                runCurrent()

                assertEquals(
                    300_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(playCountBefore, fixture.controller.playCount)
                assertEquals(1, fixture.controller.pauseCount)
                assertEquals(PlaybackStatus.Paused, fixture.controller.playbackState.value.status)
            } finally {
            }
        }

    @Test
    fun subtitleEncodeFallbackForPausedPlaybackPreservesPositionAndPauseIntent() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val encodedStreams =
                    streams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Paused, positionMs = 8_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()

                val fallbackRequest = fixture.repository.requests.last()
                assertEquals(80_000_000L, fallbackRequest.startTimeTicks)
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectPlay)
                assertEquals(false, fallbackRequest.requestPolicy.enableDirectStream)
                assertEquals(4, fallbackRequest.requestPolicy.forceEncodeSubtitle?.streamIndex)
                assertEquals("srt", fallbackRequest.requestPolicy.forceEncodeSubtitle?.normalizedFormat)
                assertEquals(PlaybackClientTrigger.SubtitleActivationFallback, fallbackRequest.requestPolicy.clientTrigger)
                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(1, fixture.controller.pauseCount)
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)
                assertEquals(
                    SubtitleDeliveryMethod.Encode,
                    assertIs<PlannedSubtitle.Track>(fixture.controller.preparedPlan?.plannedSubtitle).deliveryMethod,
                )
                assertEquals(8_000L, fixture.controller.preparedPlan?.startPositionMs)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun offDuringPendingSubtitleEncodeFallbackRestoresDirectPlayAndRejectsStaleResult() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fallbackGate = CompletableDeferred<Unit>()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(playbackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                        fallbackRequestGate = fallbackGate,
                    )
                runCurrent()

                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 9_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)

                fixture.viewModel.selectSubtitle(null)
                runCurrent()
                fallbackGate.complete(Unit)
                runCurrent()

                val offRequest = fixture.repository.requests.last()
                assertEquals(true, offRequest.requestPolicy.enableDirectPlay)
                assertEquals(true, offRequest.requestPolicy.enableDirectStream)
                assertNull(offRequest.requestPolicy.forceEncodeSubtitle)
                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(StreamMode.DirectPlay, fixture.controller.preparedPlan?.streamMode)
                assertIs<PlannedSubtitle.Off>(fixture.controller.preparedPlan?.plannedSubtitle)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun duplicateSubtitleUnavailableWhileEncodeFallbackIsPendingDoesNotRetry() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fallbackGate = CompletableDeferred<Unit>()
                val encodedStreams =
                    streams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                        fallbackRequestGate = fallbackGate,
                    )
                runCurrent()

                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 9_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 9_000L).copy(
                        bufferedPositionMs = 10_000L,
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()

                assertEquals(2, fixture.repository.requests.size)
                fallbackGate.complete(Unit)
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(StreamMode.Transcode, fixture.controller.preparedPlan?.streamMode)

                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun unknownExternalSubtitleMimeAutomaticallyRequestsEncodeOnce() =
        runPlayerViewModelTest {
            try {
                val externalStreams =
                    externalSubtitlePlaybackStreams().map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(codec = "mystery", deliveryUrl = "/Videos/item-1/subtitles/4.mystery")
                        } else {
                            stream
                        }
                    }
                val encodedStreams =
                    externalStreams.map { stream ->
                        if (stream.type.equals("Subtitle", ignoreCase = true)) {
                            stream.copy(isExternal = false, deliveryMethod = "Encode", deliveryUrl = null)
                        } else {
                            stream
                        }
                    }
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(externalStreams)),
                                    Result.success(transcodePlaybackInfoWithStreams(encodedStreams)),
                                ),
                            ),
                        mediaStreams = externalStreams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(2, fixture.repository.requests.size)
                assertEquals(
                    "mystery",
                    fixture.repository.requests
                        .last()
                        .requestPolicy.forceEncodeSubtitle
                        ?.normalizedFormat,
                )
                assertEquals(SubtitleRenderMode.ServerBurnedIn, content.subtitleRenderInfo.mode)
                assertTrue(content.subtitleNotice != null)
            } finally {
            }
        }

    @Test
    fun subtitleEncodeFallbackFailureKeepsVideoPlayingAndMarksSubtitleUnavailable() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.failure(IllegalStateException("encode unavailable")),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                    )
                runCurrent()
                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 12_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()

                val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                assertEquals(SubtitleRenderStatus.Unavailable, content.subtitleRenderInfo.status)
                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(PlaybackStatus.Playing, fixture.controller.playbackStateFlow.value.status)
                assertTrue(content.subtitleNotice != null)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun subtitleFallbackRejectsNonEncodeAndWrongStreamResponses() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val wrongStream =
                    transcodePlaybackInfoWithStreams(
                        streams.map { stream ->
                            if (stream.index == 4) stream.copy(index = 5) else stream
                        },
                    ).let { info ->
                        info.copy(
                            mediaSources = info.mediaSources.map { source -> source.copy(defaultSubtitleStreamIndex = 5) },
                        )
                    }
                val fallbackResponses =
                    listOf(
                        playbackInfoWithStreams(streams),
                        wrongStream,
                    )

                fallbackResponses.forEach { fallbackResponse ->
                    val fixture =
                        playerFixture(
                            playbackInfoResults =
                                ArrayDeque(
                                    listOf(
                                        Result.success(playbackInfoWithStreams(streams)),
                                        Result.success(fallbackResponse),
                                    ),
                                ),
                            mediaStreams = streams,
                            initialSubtitleStreamIndex = 4,
                        )
                    runCurrent()
                    val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                    fixture.controller.playbackStateFlow.value =
                        playbackState(PlaybackStatus.Paused, positionMs = 8_000L).copy(
                            subtitleActivation = SubtitleActivationState.Unavailable(target),
                        )
                    runCurrent()

                    val content = assertIs<PlayerUiState.Content>(fixture.viewModel.state.value)
                    assertEquals(SubtitleRenderStatus.Unavailable, content.subtitleRenderInfo.status)
                    assertEquals(1, fixture.controller.prepareCount)
                    assertEquals(PlaybackStatus.Paused, fixture.controller.playbackStateFlow.value.status)
                    fixture.viewModel.dispose()
                    runCurrent()
                }
            } finally {
            }
        }

    @Test
    fun stopInvalidatesSuspendedSubtitleFallbackBeforeItCanInstallOrResume() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fallbackGate = CompletableDeferred<Unit>()
                val fixture =
                    playerFixture(
                        playbackInfoResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(playbackInfoWithStreams(streams)),
                                    Result.success(transcodePlaybackInfoWithStreams(streams)),
                                ),
                            ),
                        mediaStreams = streams,
                        initialSubtitleStreamIndex = 4,
                        fallbackRequestGate = fallbackGate,
                    )
                runCurrent()
                val target = fixture.controller.preparedPlan?.subtitleActivationTarget ?: error("Missing target")
                fixture.controller.playbackStateFlow.value =
                    playbackState(PlaybackStatus.Playing, positionMs = 9_000L).copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()
                assertEquals(2, fixture.repository.requests.size)

                fixture.viewModel.stop()
                fallbackGate.complete(Unit)
                runCurrent()

                assertEquals(1, fixture.controller.prepareCount)
                assertEquals(1, fixture.controller.playCount)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun externalSubtitleTransitionsReplanWithoutServerSubtitleIndex() =
        runPlayerViewModelTest {
            try {
                val streams = externalSubtitlePlaybackStreams()
                val fixture =
                    playerFixture(
                        playbackInfo = playbackInfoWithStreams(streams),
                        mediaStreams = streams,
                    )
                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 25_000L)
                runCurrent()

                fixture.viewModel.selectSubtitle(streamIndex = 4)
                runCurrent()

                val externalRequest = fixture.repository.requests.last()
                assertEquals(4, externalRequest.subtitleStreamIndex)
                assertEquals(250_000_000L, externalRequest.startTimeTicks)
                assertEquals(2, fixture.controller.prepareCount)
                assertEquals(4, fixture.controller.preparedPlan?.selectedSubtitleStreamIndex)
                assertEquals(
                    LocalSubtitleKind.ExternalText,
                    fixture.controller.preparedPlan
                        ?.subtitleActivationTarget
                        ?.kind,
                )
                assertEquals(
                    "https://jellyfin.example/Videos/item-1/subtitles/4.srt",
                    fixture.controller.preparedExternalSubtitle?.url,
                )
                assertEquals(
                    SubtitleRenderStatus.Active,
                    (fixture.viewModel.state.value as PlayerUiState.Content).subtitleRenderInfo.status,
                )

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Playing, positionMs = 27_000L)
                runCurrent()
                fixture.viewModel.selectSubtitle(streamIndex = null)
                runCurrent()

                assertEquals(
                    -1,
                    fixture.repository.requests
                        .last()
                        .subtitleStreamIndex,
                )
                assertEquals(
                    270_000_000L,
                    fixture.repository.requests
                        .last()
                        .startTimeTicks,
                )
                assertEquals(3, fixture.controller.prepareCount)
                assertEquals(null, fixture.controller.preparedPlan?.subtitleActivationTarget)
                assertEquals(null, fixture.controller.preparedExternalSubtitle)
                assertEquals(
                    SubtitleRenderStatus.Off,
                    (fixture.viewModel.state.value as PlayerUiState.Content).subtitleRenderInfo.status,
                )
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
    fun sameItemRetryCancelsAReplanStartedUnderTheSupersededGeneration() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            val workDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

            var fixtureRef: PlayerFixture? = null
            try {
                val fixture =
                    playerFixture(workDispatcher = workDispatcher)
                        .also { fixtureRef = it }
                drainPlayerViewModelSchedulers(workDispatcher)
                val prepareCountBeforeRetry = fixture.controller.prepareCount

                fixture.viewModel.selectQuality(maxBitrateBps = 8_000_000L)
                runCurrent()
                fixture.viewModel.retry()
                runCurrent()
                drainPlayerViewModelSchedulers(workDispatcher)

                assertEquals(1, fixture.controller.retryCount)
                assertEquals(prepareCountBeforeRetry, fixture.controller.prepareCount)
            } finally {
                fixtureRef?.viewModel?.dispose()
                drainPlayerViewModelSchedulers(workDispatcher)
                Dispatchers.resetMain()
            }
        }
}
