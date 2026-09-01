// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerViewModelOfflineTest {
    @Test
    fun offlineRetryReacquiresLocalPlanWithoutRemoteDependencies() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-retry")
                val downloads = OfflinePlaybackDownloadRepository(offlineRecord(downloadId))
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.LibVlc,
                    )
                runCurrent()

                assertEquals(1, fixture.controller.offlinePrepareCount)
                assertEquals(1, downloads.getDownloadCalls)
                assertEquals(1, downloads.hasCompletedArtifactCalls)
                assertTrue(fixture.repository.requests.isEmpty())
                assertEquals(0, fixture.repository.detailCallCount)
                assertEquals(0, fixture.repository.mediaSegmentsCallCount)

                fixture.controller.playbackStateFlow.value = playbackState(PlaybackStatus.Failed)
                runCurrent()
                fixture.viewModel.retry()
                runCurrent()

                assertEquals(2, fixture.controller.offlinePrepareCount)
                assertEquals(2, downloads.getDownloadCalls)
                assertEquals(2, downloads.hasCompletedArtifactCalls)
                assertEquals(0, fixture.controller.retryCount)
                assertTrue(fixture.repository.requests.isEmpty())
                assertEquals(0, fixture.repository.detailCallCount)
                assertEquals(0, fixture.repository.mediaSegmentsCallCount)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun originalOfflineLaunchUsesNormalFactoryFallbackWhenRequestedBackendFails() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-fallback")
                val downloads = OfflinePlaybackDownloadRepository(offlineRecord(downloadId))
                val fallbackController =
                    FakePlayerController(confirmInitialAudio = true).apply {
                        activeBackend = PlayerBackend.ExoPlayer
                    }
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.LibVlc,
                        playerControllerFactory = { backend ->
                            if (backend == PlayerBackend.LibVlc) {
                                error("requested offline backend unavailable")
                            }
                            check(backend == PlayerBackend.ExoPlayer)
                            fallbackController
                        },
                    )
                runCurrent()

                assertSame(fallbackController, fixture.viewModel.currentPlayerController)
                assertEquals(PlayerBackend.ExoPlayer, fallbackController.activeBackend)
                assertEquals(1, fallbackController.offlinePrepareCount)
                assertTrue(fixture.repository.requests.isEmpty())
                assertEquals(0, fixture.repository.detailCallCount)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun appleLocalHlsLaunchRequiresVlcKitWithoutPreparingAvPlayer() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-local-hls")
                val record =
                    offlineRecord(
                        downloadId = downloadId,
                        artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    )
                val downloads = OfflinePlaybackDownloadRepository(record)
                val requestedBackends = mutableListOf<PlayerBackend>()
                val vlcKitController =
                    FakePlayerController(confirmInitialAudio = true).apply {
                        activeBackend = PlayerBackend.VlcKit
                    }
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.AVPlayer,
                        deviceProfileProvider = appleOfflineProfileProvider(),
                        playerControllerFactory = { backend ->
                            requestedBackends += backend
                            check(backend == PlayerBackend.VlcKit)
                            vlcKitController
                        },
                    )
                runCurrent()

                assertEquals(listOf(PlayerBackend.VlcKit), requestedBackends)
                assertSame(vlcKitController, fixture.viewModel.currentPlayerController)
                assertEquals(0, fixture.controller.prepareCount)
                assertEquals(0, fixture.controller.offlinePrepareCount)
                assertEquals(1, vlcKitController.offlinePrepareCount)
                assertEquals(
                    DownloadArtifactKind.LocalHlsPackage,
                    vlcKitController.preparedPlan?.offlineArtifactKind,
                )
                assertTrue(fixture.repository.requests.isEmpty())
                assertEquals(0, fixture.repository.detailCallCount)
                assertEquals(0, fixture.repository.mediaSegmentsCallCount)
                assertEquals(DownloadState.Completed, record.state)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun appleLocalHlsVlcKitFactoryFailureIsTypedAndDoesNotFallback() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-local-hls-missing")
                val record =
                    offlineRecord(
                        downloadId = downloadId,
                        artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    )
                val downloads = OfflinePlaybackDownloadRepository(record)
                val requestedBackends = mutableListOf<PlayerBackend>()
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.AVPlayer,
                        deviceProfileProvider =
                            appleOfflineProfileProvider(
                                supportedBackends = setOf(PlayerBackend.AVPlayer),
                            ),
                        playerControllerFactory = { backend ->
                            requestedBackends += backend
                            error("native VLCKit unavailable at construction")
                        },
                    )
                runCurrent()

                assertEquals(listOf(PlayerBackend.VlcKit), requestedBackends)
                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(
                    PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit),
                    (fixture.viewModel.state.value as PlayerUiState.Error).error,
                )
                assertEquals(0, fixture.controller.prepareCount)
                assertEquals(0, fixture.controller.offlinePrepareCount)
                assertEquals(DownloadState.Completed, record.state)
                assertTrue(fixture.repository.requests.isEmpty())
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun appleLocalHlsWrongVlcKitCandidateIsReleasedAndTyped() =
        runPlayerViewModelTest {
            try {
                val downloadId = DownloadId("offline-local-hls-wrong")
                val record =
                    offlineRecord(
                        downloadId = downloadId,
                        artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    )
                val downloads = OfflinePlaybackDownloadRepository(record)
                val requestedBackends = mutableListOf<PlayerBackend>()
                val wrongController =
                    FakePlayerController(confirmInitialAudio = true).apply {
                        activeBackend = PlayerBackend.AVPlayer
                    }
                val fixture =
                    playerFixture(
                        offlineDownloadId = downloadId,
                        getOfflinePlaybackPlanUseCase = GetOfflinePlaybackPlanUseCase(downloads),
                        activeBackend = PlayerBackend.AVPlayer,
                        deviceProfileProvider = appleOfflineProfileProvider(),
                        playerControllerFactory = { backend ->
                            requestedBackends += backend
                            wrongController
                        },
                    )
                runCurrent()

                assertEquals(listOf(PlayerBackend.VlcKit), requestedBackends)
                assertEquals(1, wrongController.releaseCount)
                assertIs<PlayerUiState.Error>(fixture.viewModel.state.value)
                assertEquals(
                    PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit),
                    (fixture.viewModel.state.value as PlayerUiState.Error).error,
                )
                assertEquals(DownloadState.Completed, record.state)
                fixture.viewModel.dispose()
                runCurrent()
            } finally {
            }
        }

    @Test
    fun localSubtitleUsesLocalAssetAndNeverStartsEncodeFallback() =
        runPlayerViewModelTest {
            var fixtureRef: PlayerFixture? = null
            try {
                val asset = localSubtitleAsset()
                val fixture =
                    playerFixture(
                        initialSubtitleSelection = SubtitleSelectionIntent.LocalAsset(asset.id),
                        localSubtitleAsset = asset,
                    ).also { fixtureRef = it }
                runCurrent()

                assertEquals(
                    -1,
                    fixture.repository.requests
                        .single()
                        .subtitleStreamIndex,
                )
                assertIs<SubtitleAsset.LocalFile>(fixture.controller.preparedSubtitleAsset)
                assertIs<PlannedSubtitle.LocalAsset>(fixture.controller.preparedPlan?.plannedSubtitle)
                val content = fixture.viewModel.state.value as PlayerUiState.Content
                assertEquals(asset.id, content.selectedSubtitleAssetId)

                val target = requireNotNull(fixture.controller.preparedPlan?.subtitleActivationTarget)
                fixture.controller.playbackStateFlow.value =
                    fixture.controller.playbackStateFlow.value.copy(
                        subtitleActivation = SubtitleActivationState.Unavailable(target),
                    )
                runCurrent()

                assertEquals(1, fixture.repository.requests.size)
                assertEquals(asset.id, (fixture.viewModel.state.value as PlayerUiState.Content).selectedSubtitleAssetId)
            } finally {
                fixtureRef?.viewModel?.dispose()
                runCurrent()
            }
        }
}
