// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.data.repository.LocalSubtitleMutationCoordinator
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.DeleteLocalSubtitleAction
import com.jellyscope.core.domain.action.EnqueueDownloadAction
import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.action.SetItemFavoriteAction
import com.jellyscope.core.domain.action.SetItemPlayedAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaPerson
import com.jellyscope.core.domain.model.MediaPersonType
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.RelatedGroup
import com.jellyscope.core.domain.model.RelatedGroupKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.JELLYFIN_TICKS_PER_MILLISECOND
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.usecase.FixedDownloadAdmission
import com.jellyscope.core.domain.usecase.FixedDownloadAdmissionResult
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionUseCase
import com.jellyscope.core.domain.usecase.GetRelatedItemsUseCase
import com.jellyscope.core.domain.usecase.GetSubtitleSelectionUseCase
import com.jellyscope.core.domain.usecase.ObserveDownloadsUseCase
import com.jellyscope.core.domain.usecase.ObserveLocalSubtitleAssetsUseCase
import com.jellyscope.core.domain.usecase.ObservePlaybackStopSettlementUseCase
import com.jellyscope.core.domain.usecase.OriginalDownloadAdmission
import com.jellyscope.core.domain.usecase.OriginalDownloadAdmissionResult
import com.jellyscope.core.domain.usecase.PreviewFixedDownloadUseCase
import com.jellyscope.core.domain.usecase.PreviewOriginalDownloadUseCase
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import com.jellyscope.core.playback.SettlementKey
import com.jellyscope.ui.screen.player.PlaybackSelection
import com.jellyscope.ui.screen.player.PlaybackSelectionMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @Test
    fun staleFixedPreviewCannotPublishAfterANewerChoiceWins() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val admission = BlockingFixedDownloadAdmission()
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        previewFixedDownloadUseCase = PreviewFixedDownloadUseCase(admission),
                    )
                advanceUntilIdle()

                viewModel.previewFixedDownload(
                    quality = DownloadQuality.Fixed(4_000_000L),
                    selectedAudioStreamIndex = 1,
                    subtitleSelection = SubtitleSelectionIntent.Off,
                )
                runCurrent()
                viewModel.previewFixedDownload(
                    quality = DownloadQuality.Fixed(8_000_000L),
                    selectedAudioStreamIndex = 1,
                    subtitleSelection = SubtitleSelectionIntent.Off,
                )
                runCurrent()

                admission.results[1].complete(
                    FixedDownloadAdmissionResult.Rejected(DownloadAdmissionDecision.SourceChanged),
                )
                admission.results[0].complete(
                    FixedDownloadAdmissionResult.Ready(admission.requests[0].toDownloadRequest()),
                )
                advanceUntilIdle()

                val state = assertIs<DetailDownloadState.FixedRejected>(viewModel.downloadState.value)
                assertEquals(DownloadAdmissionDecision.SourceChanged, state.decision)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun dismissingFixedPreviewPreventsLatePublication() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val admission = BlockingFixedDownloadAdmission()
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        previewFixedDownloadUseCase = PreviewFixedDownloadUseCase(admission),
                    )
                advanceUntilIdle()

                viewModel.previewFixedDownload(
                    quality = DownloadQuality.Fixed(4_000_000L),
                    selectedAudioStreamIndex = 1,
                    subtitleSelection = SubtitleSelectionIntent.Off,
                )
                runCurrent()
                viewModel.resetDownloadState()
                admission.results[0].complete(
                    FixedDownloadAdmissionResult.Ready(admission.requests[0].toDownloadRequest()),
                )
                advanceUntilIdle()

                assertIs<DetailDownloadState.Idle>(viewModel.downloadState.value)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun formatsResolutionBucketsAndTimeLeft() {
        assertEquals("4K", resolutionLabel(2160))
        assertEquals("1080p", resolutionLabel(1080))
        assertEquals("720p", resolutionLabel(720))
        assertEquals("SD", resolutionLabel(480))
        assertEquals("87%", criticRatingText(86.6))
        assertEquals(
            "1h 46m left",
            timeLeftText(
                runtime = 2.hours,
                playbackPositionTicks = 14.minutes.inWholeMilliseconds * JELLYFIN_TICKS_PER_MILLISECOND,
                strings = englishDetailFormatterStrings(),
            ),
        )
    }

    @Test
    fun loadSuccessProjectsDetailUi() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(listOf(relatedMovie)))),
                    )
                val viewModel = repository.detailViewModel(itemId = "episode-1")
                advanceUntilIdle()

                val state = assertIs<DetailUiState.Content>(viewModel.state.value)

                assertEquals("episode-1", state.detail.itemId)
                assertEquals("Episode", state.detail.title)
                assertEquals("A Show · S2E3", state.detail.headerLine)
                assertEquals("2024 · 45 min", state.detail.metadataLine)
                assertEquals("Drama", state.detail.genresLine)
                assertEquals("TV-14", state.detail.officialRating)
                assertEquals("★ 7.4", state.detail.communityRating)
                assertEquals("87%", state.detail.criticRatingText)
                assertEquals("https://www.imdb.com/title/tt1234567/", state.detail.imdbUrl)
                assertEquals("https://www.themoviedb.org/movie/603", state.detail.tmdbUrl)
                assertEquals("Directed by Director One", state.detail.directedByLine)
                assertEquals(listOf("Actor One", "Director One"), state.detail.castAndCrew.map { it.name })
                assertEquals(listOf("1080p", "H264", "EAC3", "5.1"), state.detail.streamBadges)
                assertEquals("43m left", state.detail.timeLeftText)
                assertEquals(true, state.detail.isFavorite)
                assertIs<DetailPlayLabel.Resume>(state.detail.playAction.label)
                assertEquals(
                    2.minutes.inWholeMilliseconds * JELLYFIN_TICKS_PER_MILLISECOND,
                    state.detail.playAction.startPositionTicks,
                )
                assertEquals("source-1", state.detail.playAction.mediaSourceId)
                assertEquals(
                    listOf(1),
                    state.detail.trackSelection.audioOptions
                        .map { option -> option.streamIndex },
                )
                assertEquals(
                    listOf(2),
                    state.detail.trackSelection.subtitleOptions
                        .map { option -> option.streamIndex },
                )
                assertEquals(1, state.detail.trackSelection.defaultAudioStreamIndex)
                assertEquals(2, state.detail.trackSelection.defaultSubtitleStreamIndex)
                assertEquals(
                    listOf("movie-1"),
                    state.detail.relatedGroups
                        .flatMap { group -> group.items }
                        .map { item -> item.id },
                )
                assertEquals(listOf("1080p"), state.detail.versions.map { version -> version.name })
                assertEquals("https://trailers.example/episode", state.detail.trailerUrl)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun durableDownloadProjectionMatchesTheSelectedAccountItemAndSource() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val downloads = DetailDownloadRepository()
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        observeDownloadsUseCase = ObserveDownloadsUseCase(downloads),
                    )
                advanceUntilIdle()

                assertIs<DetailDownloadEntryState.Add>(viewModel.downloadEntryState.value)

                downloads.records.value =
                    listOf(
                        detailDownloadRecord(
                            state = DownloadState.Queued,
                            itemId = "episode-1",
                            mediaSourceId = "other-source",
                        ),
                    )
                runCurrent()
                assertIs<DetailDownloadEntryState.Add>(viewModel.downloadEntryState.value)

                downloads.records.value =
                    listOf(
                        detailDownloadRecord(
                            state = DownloadState.Queued,
                            accountIdentity = AccountIdentity("server-1", "other-user"),
                        ),
                    )
                runCurrent()
                assertIs<DetailDownloadEntryState.Add>(viewModel.downloadEntryState.value)

                val queued = detailDownloadRecord(state = DownloadState.Queued)
                downloads.records.value = listOf(queued)
                runCurrent()
                assertEquals(
                    queued,
                    assertIs<DetailDownloadEntryState.Manage>(viewModel.downloadEntryState.value).record,
                )

                val paused = detailDownloadRecord(state = DownloadState.Paused)
                downloads.records.value = listOf(paused)
                runCurrent()
                assertEquals(
                    paused,
                    assertIs<DetailDownloadEntryState.Manage>(viewModel.downloadEntryState.value).record,
                )

                val failed = detailDownloadRecord(state = DownloadState.Failed)
                downloads.records.value = listOf(failed)
                runCurrent()
                assertEquals(
                    failed,
                    assertIs<DetailDownloadEntryState.Manage>(viewModel.downloadEntryState.value).record,
                )

                val completed = detailDownloadRecord(state = DownloadState.Completed)
                downloads.records.value = listOf(completed)
                runCurrent()
                assertEquals(
                    completed,
                    assertIs<DetailDownloadEntryState.PlayOffline>(viewModel.downloadEntryState.value).record,
                )

                val fixedCompleted =
                    detailDownloadRecord(
                        state = DownloadState.Completed,
                        quality = DownloadQuality.Fixed(4_000_000L),
                        artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    )
                downloads.records.value = listOf(fixedCompleted)
                runCurrent()
                assertEquals(
                    fixedCompleted,
                    assertIs<DetailDownloadEntryState.PlayOffline>(viewModel.downloadEntryState.value).record,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun disabledSessionHidesDownloadEntryAndRejectsDownloadPreview() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val fixedAdmission = BlockingFixedDownloadAdmission()
                val downloadRepository = DetailDownloadRepository()
                val originalAdmission = AvailableOriginalDownloadAdmission()
                val lifecycleHost =
                    object : DownloadLifecycleHost {
                        override fun start() = Unit

                        override fun stop() = Unit

                        override suspend fun wakeFromUserAction(): Result<Unit> = Result.success(Unit)
                    }
                val previewOriginalDownloadUseCase = PreviewOriginalDownloadUseCase(originalAdmission)
                val enqueueDownloadAction =
                    EnqueueDownloadAction(
                        repository = downloadRepository,
                        lifecycleHost = lifecycleHost,
                        admission = originalAdmission,
                    )
                val enabledViewModel =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    ).detailViewModel(
                        itemId = "episode-1",
                        sessionOverride = session.copy(enableContentDownloading = true),
                        previewOriginalDownloadUseCase = previewOriginalDownloadUseCase,
                        enqueueDownloadAction = enqueueDownloadAction,
                    )
                advanceUntilIdle()

                assertTrue(enabledViewModel.isDownloadAvailable)

                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        sessionOverride = session.copy(enableContentDownloading = false),
                        previewOriginalDownloadUseCase = previewOriginalDownloadUseCase,
                        enqueueDownloadAction = enqueueDownloadAction,
                        observeDownloadsUseCase = ObserveDownloadsUseCase(downloadRepository),
                        previewFixedDownloadUseCase = PreviewFixedDownloadUseCase(fixedAdmission),
                    )
                advanceUntilIdle()

                assertFalse(viewModel.isDownloadAvailable)
                viewModel.previewFixedDownload(
                    quality = DownloadQuality.Fixed(4_000_000L),
                    selectedAudioStreamIndex = 1,
                    subtitleSelection = SubtitleSelectionIntent.Off,
                )

                assertIs<DetailDownloadState.Idle>(viewModel.downloadState.value)
                assertIs<DetailDownloadEntryState.Add>(viewModel.downloadEntryState.value)
                assertEquals(0, fixedAdmission.requests.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun enqueueResultsKeepRejectedAndRemovalStatesDistinctFromQueued() {
        val record = detailDownloadRecord(state = DownloadState.Queued)

        assertIs<DetailDownloadState.Created>(DownloadEnqueueResult.Created(record).toDetailDownloadState())
        assertIs<DetailDownloadState.Existing>(DownloadEnqueueResult.Existing(record).toDetailDownloadState())
        assertIs<DetailDownloadState.SchedulingRejected>(
            DownloadEnqueueResult.SchedulingRejected(record).toDetailDownloadState(),
        )
        assertIs<DetailDownloadState.EnqueueRejected>(
            DownloadEnqueueResult
                .Rejected(com.jellyscope.core.domain.model.DownloadAdmissionDecision.NetworkUnavailable)
                .toDetailDownloadState(),
        )
        assertIs<DetailDownloadState.RemovalInProgress>(
            DownloadEnqueueResult.RemovalInProgress.toDetailDownloadState(),
        )
    }

    @Test
    fun selectedPlaybackVersionAloneSuppliesTheSubtitleSearchReleaseBasename() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                suspend fun project(versions: List<MediaVersion>): DetailUi {
                    val detail = episodeDetail.copy(versions = versions)
                    val repository =
                        QueueMediaRepository(
                            detailResults = ArrayDeque(listOf(Result.success(detail))),
                            relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                        )
                    val viewModel = repository.detailViewModel(itemId = "episode-1")
                    advanceUntilIdle()
                    return assertIs<DetailUiState.Content>(viewModel.state.value).detail
                }

                val single = project(listOf(MediaVersion("source-1", "1080p", releaseBasename = "Episode.Release.mkv")))
                assertEquals("source-1", single.playAction.mediaSourceId)
                assertEquals("source-1", single.restartAction?.mediaSourceId)
                assertEquals("Episode.Release.mkv", single.selectedSourceReleaseBasename)

                val selectedValid =
                    project(
                        listOf(
                            MediaVersion("", "First", releaseBasename = "Wrong.Release.mkv"),
                            MediaVersion("source-2", "Second", releaseBasename = "Selected.Release.mkv"),
                            MediaVersion("source-3", "Third", releaseBasename = "Later.Release.mkv"),
                        ),
                    )
                assertEquals("source-2", selectedValid.playAction.mediaSourceId)
                assertEquals("Selected.Release.mkv", selectedValid.selectedSourceReleaseBasename)

                val missingId = project(listOf(MediaVersion("", "1080p", releaseBasename = "Must.Not.Leak.mkv")))
                assertEquals(null, missingId.playAction.mediaSourceId)
                assertEquals(null, missingId.restartAction?.mediaSourceId)
                assertEquals(null, missingId.selectedSourceReleaseBasename)

                val genericLabel = project(listOf(MediaVersion("source-4", "1080p", releaseBasename = null)))
                assertEquals("source-4", genericLabel.playAction.mediaSourceId)
                assertEquals(null, genericLabel.selectedSourceReleaseBasename)

                val blankBasename = project(listOf(MediaVersion("source-5", "1080p", releaseBasename = "   ")))
                assertEquals(null, blankBasename.selectedSourceReleaseBasename)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun mediaVersionsFilterInvalidIdsAndUseLocalizedFallbackLabelsAfterDeduplication() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val detail =
                    episodeDetail.copy(
                        versions =
                            listOf(
                                MediaVersion(id = "", name = "Invalid"),
                                MediaVersion(id = "source-1", name = ""),
                                MediaVersion(id = "source-1", name = "Duplicate"),
                                MediaVersion(id = "source-2", name = "   "),
                            ),
                    )
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(detail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )

                val viewModel = repository.detailViewModel(itemId = "episode-1")
                advanceUntilIdle()

                val projected = assertIs<DetailUiState.Content>(viewModel.state.value).detail
                assertEquals(listOf("source-1", "source-2"), projected.versions.map(MediaVersionUi::id))
                assertEquals(listOf("Version 1", "Version 2"), projected.versions.map(MediaVersionUi::name))
                assertEquals("source-1", projected.selectedMediaSourceId)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun selectingVersionAtomicallyProjectsExactSourceFieldsPreferencesAndLocalSubtitles() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val detail = detailWithSelectableSources()
                val playbackStore =
                    DetailPlaybackSelectionStore(
                        stored =
                            mapOf(
                                PlaybackSelectionKey("server-1", "user-1", "episode-1", "source-2") to
                                    PlaybackSelection(audioStreamIndex = 11),
                            ),
                    )
                val subtitleStore = DetailViewModelRecordingSubtitleSelectionStore()
                subtitleStore.writes +=
                    SubtitleSelectionKey("server-1", "user-1", "episode-1", "source-2") to
                    SubtitleSelectionIntent.Track(12)
                val sourceTwoAsset =
                    detailLocalSubtitleAsset.copy(
                        id = "asset-2",
                        mediaSourceId = "source-2",
                        fileId = "asset-2.vtt",
                    )
                val assetStore = DetailViewModelLocalSubtitleAssetStore(detailLocalSubtitleAsset, sourceTwoAsset)
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(detail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val launchContext =
                    GetPlaybackLaunchContextUseCase(
                        getPlaybackSelection = GetPlaybackSelectionUseCase(playbackStore),
                        getSubtitleSelection = GetSubtitleSelectionUseCase(subtitleStore),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        getPlaybackLaunchContextUseCase = launchContext,
                        observeLocalSubtitleAssetsUseCase = ObserveLocalSubtitleAssetsUseCase(assetStore),
                    )
                advanceUntilIdle()

                viewModel.selectMediaVersion("source-2")
                advanceUntilIdle()

                val selected = assertIs<DetailUiState.Content>(viewModel.state.value).detail
                assertEquals("source-2", selected.selectedMediaSourceId)
                assertEquals("Second.Release.mkv", selected.selectedSourceReleaseBasename)
                assertEquals(listOf("4K", "HEVC", "AAC", "2.0"), selected.streamBadges)
                assertEquals("2.1 GB", selected.mediaInfo?.fileLine)
                assertEquals("source-2", selected.playAction.mediaSourceId)
                assertEquals("source-2", selected.restartAction?.mediaSourceId)
                assertEquals(11, selected.trackSelection.defaultAudioStreamIndex)
                assertEquals(12, selected.trackSelection.defaultSubtitleStreamIndex)
                assertEquals(listOf("asset-2"), selected.trackSelection.localSubtitleOptions.map(LocalSubtitleAsset::id))
                assertEquals(
                    listOf("asset-2"),
                    selected.versions
                        .first { version -> version.id == "source-2" }
                        .trackSelection
                        .localSubtitleOptions
                        .map(LocalSubtitleAsset::id),
                )
                assertEquals("source-2", assetStore.observedContexts.last().mediaSourceId)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedOptimisticTogglesPreserveANewerVersionSelection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val playedResult = CompletableDeferred<Result<Unit>>()
                val favoriteResult = CompletableDeferred<Result<Unit>>()
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(detailWithSelectableSources()))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                        deferredSetPlayedResult = playedResult,
                        deferredSetFavoriteResult = favoriteResult,
                    )
                val viewModel = repository.detailViewModel(itemId = "episode-1")
                advanceUntilIdle()

                viewModel.toggleWatched()
                viewModel.toggleFavorite()
                runCurrent()
                viewModel.selectMediaVersion("source-2")
                advanceUntilIdle()

                playedResult.complete(Result.failure(IllegalStateException("played failed")))
                favoriteResult.complete(Result.failure(IllegalStateException("favorite failed")))
                advanceUntilIdle()

                val detail = assertIs<DetailUiState.Content>(viewModel.state.value).detail
                assertEquals("source-2", detail.selectedMediaSourceId)
                assertEquals("source-2", detail.playAction.mediaSourceId)
                assertEquals("source-2", detail.restartAction?.mediaSourceId)
                assertEquals(listOf("4K", "HEVC", "AAC", "2.0"), detail.streamBadges)
                assertEquals("2.1 GB", detail.mediaInfo?.fileLine)
                assertEquals(false, detail.isWatched)
                assertEquals(false, detail.watchedToggleInFlight)
                assertEquals(true, detail.isFavorite)
                assertEquals(false, detail.favoriteToggleInFlight)
                assertEquals(listOf("episode-1" to true), repository.setPlayedCalls)
                assertEquals(listOf("episode-1" to false), repository.setFavoriteCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun refreshPreservesSelectedVersionThenFallsBackWhenItDisappears() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val detail = detailWithSelectableSources()
                val repository =
                    QueueMediaRepository(
                        detailResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(detail),
                                    Result.success(detail),
                                    Result.success(detail.copy(versions = listOf(detail.versions.first()))),
                                ),
                            ),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel = repository.detailViewModel(itemId = "episode-1")
                advanceUntilIdle()

                viewModel.selectMediaVersion("source-2")
                advanceUntilIdle()
                viewModel.refresh()
                advanceUntilIdle()
                assertEquals(
                    "source-2",
                    assertIs<DetailUiState.Content>(viewModel.state.value).detail.selectedMediaSourceId,
                )

                viewModel.refresh()
                advanceUntilIdle()
                val fallback = assertIs<DetailUiState.Content>(viewModel.state.value).detail
                assertEquals("source-1", fallback.selectedMediaSourceId)
                assertEquals("source-1", fallback.playAction.mediaSourceId)
                assertEquals(listOf("source-1"), fallback.versions.map(MediaVersionUi::id))
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun staleRefreshCannotRevertSelectionOrReplaceItsLocalSubtitleObserver() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val detail = detailWithSelectableSources()
                val repository = OverlappingVersionRefreshRepository(detail)
                val sourceTwoAsset =
                    detailLocalSubtitleAsset.copy(
                        id = "asset-2",
                        mediaSourceId = "source-2",
                        fileId = "asset-2.vtt",
                    )
                val assetStore = DetailViewModelLocalSubtitleAssetStore(detailLocalSubtitleAsset, sourceTwoAsset)
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        observeLocalSubtitleAssetsUseCase = ObserveLocalSubtitleAssetsUseCase(assetStore),
                    )
                advanceUntilIdle()
                viewModel.selectMediaVersion("source-2")
                advanceUntilIdle()

                viewModel.refresh()
                runCurrent()
                viewModel.refresh()
                runCurrent()
                repository.newerRefresh.complete(Result.success(detail))
                runCurrent()
                repository.staleRefresh.complete(
                    Result.success(detail.copy(versions = listOf(detail.versions.first()))),
                )
                advanceUntilIdle()

                val selected = assertIs<DetailUiState.Content>(viewModel.state.value).detail
                assertEquals("source-2", selected.selectedMediaSourceId)
                assertEquals("source-2", selected.playAction.mediaSourceId)
                assertEquals("source-2", assetStore.observedContexts.last().mediaSourceId)
                assertEquals(1, assetStore.observedContexts.count { context -> context.mediaSourceId == "source-1" })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rapidMediaVersionSelectionsCommitOnlyTheLatestExactSourceRead() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val detail = detailWithSelectableSources(includeThirdSource = true)
                val selectionStore = DelayedDetailPlaybackSelectionStore()
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(detail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        getPlaybackLaunchContextUseCase =
                            GetPlaybackLaunchContextUseCase(
                                getPlaybackSelection = GetPlaybackSelectionUseCase(selectionStore),
                            ),
                    )
                advanceUntilIdle()

                viewModel.selectMediaVersion("source-2")
                runCurrent()
                viewModel.selectMediaVersion("source-3")
                runCurrent()
                selectionStore.sourceThree.complete(PlaybackSelection(audioStreamIndex = 21))
                runCurrent()
                selectionStore.sourceTwo.complete(PlaybackSelection(audioStreamIndex = 11))
                advanceUntilIdle()

                val selected = assertIs<DetailUiState.Content>(viewModel.state.value).detail
                assertEquals("source-3", selected.selectedMediaSourceId)
                assertEquals("source-3", selected.playAction.mediaSourceId)
                assertEquals("source-3", selected.restartAction?.mediaSourceId)
                assertEquals(21, selected.trackSelection.defaultAudioStreamIndex)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun reselectingRenderedVersionCancelsPendingExactSourceRead() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val detail = detailWithSelectableSources()
                val selectionStore = DelayedDetailPlaybackSelectionStore()
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(detail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        getPlaybackLaunchContextUseCase =
                            GetPlaybackLaunchContextUseCase(
                                getPlaybackSelection = GetPlaybackSelectionUseCase(selectionStore),
                            ),
                    )
                advanceUntilIdle()

                viewModel.selectMediaVersion("source-2")
                runCurrent()
                viewModel.selectMediaVersion("source-1")
                runCurrent()
                selectionStore.sourceTwo.complete(PlaybackSelection(audioStreamIndex = 11))
                advanceUntilIdle()

                val selected = assertIs<DetailUiState.Content>(viewModel.state.value).detail
                assertEquals("source-1", selected.selectedMediaSourceId)
                assertEquals("source-1", selected.playAction.mediaSourceId)
                assertEquals("source-1", selected.restartAction?.mediaSourceId)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun subtitleSelectionsPersistExplicitOffTrackAndLocalAssetIntents() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val store = DetailViewModelRecordingSubtitleSelectionStore()
                val assetStore = DetailViewModelLocalSubtitleAssetStore(detailLocalSubtitleAsset)
                val fileStore = DetailViewModelLocalSubtitleFileStore(detailLocalSubtitleAsset.fileId)
                val coordinator = LocalSubtitleMutationCoordinator(assetStore, fileStore, store, backgroundScope)
                val saveAction = SaveSubtitleSelectionAction(coordinator, backgroundScope)
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        saveSubtitleSelectionAction = saveAction,
                    )
                advanceUntilIdle()
                val content = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals("source-1", content.detail.playAction.mediaSourceId)

                viewModel.selectSubtitle(null)
                viewModel.selectSubtitle(2)
                viewModel.selectLocalSubtitle("asset-1")
                runCurrent()
                val latestWrite = saveAction.latestWrite() ?: error("Expected a subtitle selection write")
                latestWrite.await()

                assertEquals(
                    listOf(
                        SubtitleSelectionIntent.Off,
                        SubtitleSelectionIntent.Track(2),
                        SubtitleSelectionIntent.LocalAsset("asset-1"),
                    ),
                    store.writes.map { write -> write.second },
                )
                assertTrue(store.writes.all { write -> write.first == SubtitleSelectionKey("server-1", "user-1", "episode-1", "source-1") })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun deletingLocalAssetAfterRestoredOffReprojectsPickerAndPlaySelection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val key = SubtitleSelectionKey("server-1", "user-1", "episode-1", "source-1")
                val selectionStore = DetailViewModelRecordingSubtitleSelectionStore()
                selectionStore.writes += key to SubtitleSelectionIntent.Off
                val assetStore = DetailViewModelLocalSubtitleAssetStore(detailLocalSubtitleAsset)
                val fileStore = DetailViewModelLocalSubtitleFileStore(detailLocalSubtitleAsset.fileId)
                val coordinator = LocalSubtitleMutationCoordinator(assetStore, fileStore, selectionStore, backgroundScope)
                val saveAction = SaveSubtitleSelectionAction(coordinator = coordinator, scope = backgroundScope)
                val deleteAction = DeleteLocalSubtitleAction(coordinator)
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail), Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        getSubtitleSelectionUseCase = GetSubtitleSelectionUseCase(selectionStore),
                        observeLocalSubtitleAssetsUseCase = ObserveLocalSubtitleAssetsUseCase(assetStore),
                        deleteLocalSubtitleAction = deleteAction,
                        saveSubtitleSelectionAction = saveAction,
                    )
                advanceUntilIdle()

                val restoredOff = assertIs<DetailUiState.Content>(viewModel.state.value).detail.trackSelection
                assertEquals(null, restoredOff.defaultSubtitleStreamIndex)
                assertEquals(SubtitleSelectionIntent.Off, restoredOff.initialSubtitleSelection)
                val selectionState =
                    DetailTrackSelectionState(
                        audioStreamIndex = restoredOff.defaultAudioStreamIndex,
                        subtitleStreamIndex = restoredOff.defaultSubtitleStreamIndex,
                        mediaSourceId = restoredOff.mediaSourceId,
                        initialSubtitleExplicitlySelected = true,
                    )

                viewModel.selectLocalSubtitle(detailLocalSubtitleAsset.id)
                selectionState.selectLocalSubtitle()
                saveAction.latestWrite()?.await() ?: error("Expected local subtitle selection write")
                assertEquals(
                    SubtitleSelectionIntent.LocalAsset(detailLocalSubtitleAsset.id),
                    selectionStore.writes.lastOrNull()?.second,
                )
                assertEquals(SubtitleSelectionIntent.LocalAsset(detailLocalSubtitleAsset.id), saveAction.current(key))

                viewModel.deleteLocalSubtitle(detailLocalSubtitleAsset.id)
                runCurrent()
                advanceUntilIdle()
                saveAction.latestWrite()?.await() ?: error("Expected local subtitle deletion write")
                advanceUntilIdle()

                assertEquals(null, assetStore.get(detailLocalSubtitleAsset.id))
                assertEquals(null, selectionStore.get(key))
                assertEquals(2, repository.detailCalls)
                val afterDelete = assertIs<DetailUiState.Content>(viewModel.state.value).detail.trackSelection
                assertEquals(2, afterDelete.defaultSubtitleStreamIndex)
                assertEquals(SubtitleSelectionIntent.Track(2), afterDelete.initialSubtitleSelection)
                assertEquals(null, afterDelete.defaultLocalSubtitleAssetId)

                selectionState.reconcile(afterDelete)

                assertEquals(2, selectionState.selectedSubtitleStreamIndex)
                assertEquals(SubtitleSelectionIntent.Track(2), selectionState.subtitleSelectionForPlay(afterDelete))
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun deletingLocalAssetReprojectsPickerSelectionWhenTheFollowUpRefreshFails() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val key = SubtitleSelectionKey("server-1", "user-1", "episode-1", "source-1")
                val selectionStore = DetailViewModelRecordingSubtitleSelectionStore()
                selectionStore.writes += key to SubtitleSelectionIntent.Off
                val assetStore = DetailViewModelLocalSubtitleAssetStore(detailLocalSubtitleAsset)
                val fileStore = DetailViewModelLocalSubtitleFileStore(detailLocalSubtitleAsset.fileId)
                val coordinator = LocalSubtitleMutationCoordinator(assetStore, fileStore, selectionStore, backgroundScope)
                val saveAction = SaveSubtitleSelectionAction(coordinator = coordinator, scope = backgroundScope)
                val deleteAction = DeleteLocalSubtitleAction(coordinator)
                val repository =
                    QueueMediaRepository(
                        detailResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(episodeDetail),
                                    // The post-deletion refresh fails: offline, or a
                                    // transient server error.
                                    Result.failure(IllegalStateException("refresh unavailable")),
                                ),
                            ),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        getSubtitleSelectionUseCase = GetSubtitleSelectionUseCase(selectionStore),
                        observeLocalSubtitleAssetsUseCase = ObserveLocalSubtitleAssetsUseCase(assetStore),
                        deleteLocalSubtitleAction = deleteAction,
                        saveSubtitleSelectionAction = saveAction,
                    )
                advanceUntilIdle()

                val restoredOff = assertIs<DetailUiState.Content>(viewModel.state.value).detail.trackSelection
                assertEquals(null, restoredOff.defaultSubtitleStreamIndex)
                val selectionState =
                    DetailTrackSelectionState(
                        audioStreamIndex = restoredOff.defaultAudioStreamIndex,
                        subtitleStreamIndex = restoredOff.defaultSubtitleStreamIndex,
                        mediaSourceId = restoredOff.mediaSourceId,
                        initialSubtitleExplicitlySelected = true,
                    )

                viewModel.selectLocalSubtitle(detailLocalSubtitleAsset.id)
                selectionState.selectLocalSubtitle()
                saveAction.latestWrite()?.await() ?: error("Expected local subtitle selection write")

                viewModel.deleteLocalSubtitle(detailLocalSubtitleAsset.id)
                runCurrent()
                advanceUntilIdle()
                saveAction.latestWrite()?.await() ?: error("Expected local subtitle deletion write")
                advanceUntilIdle()

                assertEquals(null, selectionStore.get(key))
                assertEquals(2, repository.detailCalls)
                val afterDelete = assertIs<DetailUiState.Content>(viewModel.state.value).detail.trackSelection
                // The failed refresh committed nothing, so these defaults can only
                // come from the local reprojection.
                assertEquals(2, afterDelete.defaultSubtitleStreamIndex)
                assertEquals(SubtitleSelectionIntent.Track(2), afterDelete.initialSubtitleSelection)
                assertEquals(null, afterDelete.defaultLocalSubtitleAssetId)

                selectionState.reconcile(afterDelete)

                assertEquals(2, selectionState.selectedSubtitleStreamIndex)
                assertEquals(SubtitleSelectionIntent.Track(2), selectionState.subtitleSelectionForPlay(afterDelete))
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun deletingAnUnselectedLocalAssetKeepsTheStoredSelection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val key = SubtitleSelectionKey("server-1", "user-1", "episode-1", "source-1")
                val otherAsset = detailLocalSubtitleAsset.copy(id = "asset-2", fileId = "file-2")
                val selectionStore = DetailViewModelRecordingSubtitleSelectionStore()
                selectionStore.writes += key to SubtitleSelectionIntent.LocalAsset(detailLocalSubtitleAsset.id)
                val assetStore = DetailViewModelLocalSubtitleAssetStore(detailLocalSubtitleAsset, otherAsset)
                val fileStore = DetailViewModelLocalSubtitleFileStore(otherAsset.fileId)
                val coordinator = LocalSubtitleMutationCoordinator(assetStore, fileStore, selectionStore, backgroundScope)
                val saveAction = SaveSubtitleSelectionAction(coordinator = coordinator, scope = backgroundScope)
                val deleteAction = DeleteLocalSubtitleAction(coordinator)
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail), Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel =
                    repository.detailViewModel(
                        itemId = "episode-1",
                        getSubtitleSelectionUseCase = GetSubtitleSelectionUseCase(selectionStore),
                        observeLocalSubtitleAssetsUseCase = ObserveLocalSubtitleAssetsUseCase(assetStore),
                        deleteLocalSubtitleAction = deleteAction,
                        saveSubtitleSelectionAction = saveAction,
                    )
                advanceUntilIdle()

                viewModel.deleteLocalSubtitle(otherAsset.id)
                runCurrent()
                advanceUntilIdle()

                assertEquals(null, assetStore.get(otherAsset.id))
                // Deleting an unselected asset must not disturb the selection that
                // points at a different, still-present asset.
                assertEquals(SubtitleSelectionIntent.LocalAsset(detailLocalSubtitleAsset.id), selectionStore.get(key))
                val afterDelete = assertIs<DetailUiState.Content>(viewModel.state.value).detail.trackSelection
                assertEquals(detailLocalSubtitleAsset.id, afterDelete.defaultLocalSubtitleAssetId)
                assertEquals(null, afterDelete.defaultSubtitleStreamIndex)
                assertEquals(
                    SubtitleSelectionIntent.LocalAsset(detailLocalSubtitleAsset.id),
                    afterDelete.initialSubtitleSelection,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun silentRefreshPreservesRelatedGroupsWithoutRefetchingThem() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    QueueMediaRepository(
                        detailResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(episodeDetail),
                                    Result.success(episodeDetail),
                                ),
                            ),
                        relatedResults = ArrayDeque(listOf(Result.success(listOf(relatedMovie)))),
                    )
                val viewModel = repository.detailViewModel(itemId = "episode-1")
                advanceUntilIdle()

                viewModel.refresh()
                advanceUntilIdle()

                val state = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals(
                    listOf("movie-1"),
                    state.detail.relatedGroups
                        .flatMap { group -> group.items }
                        .map { item -> item.id },
                )
                assertEquals(1, repository.relatedCalls)
                assertEquals(false, state.detail.relatedLoading)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun relatedGroupsEmittedDuringSilentRefreshAreNotLostOrCompletedEarly() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = StreamingRefreshMediaRepository()
                val viewModel = repository.detailViewModel(itemId = "episode-1")
                runCurrent()
                repository.relatedGroups.send(
                    RelatedGroup(RelatedGroupKind.Similar, items = listOf(relatedMovie)),
                )
                runCurrent()

                viewModel.refresh()
                runCurrent()
                repository.relatedGroups.send(
                    RelatedGroup(
                        RelatedGroupKind.Genre,
                        label = "Drama",
                        items = listOf(relatedMovie.copy(id = "movie-2", name = "Second movie")),
                    ),
                )
                runCurrent()
                repository.refreshDetail.complete(episodeDetail)
                runCurrent()

                val refreshing = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals(
                    listOf("movie-1", "movie-2"),
                    refreshing.detail.relatedGroups
                        .flatMap { group -> group.items }
                        .map { item -> item.id },
                )
                assertTrue(refreshing.detail.relatedLoading)

                repository.relatedGroups.close()
                advanceUntilIdle()

                val completed = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals(false, completed.detail.relatedLoading)
                assertEquals(1, repository.relatedCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedSilentRefreshPreservesCurrentDetailAndRelatedState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    QueueMediaRepository(
                        detailResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(episodeDetail),
                                    Result.failure(IllegalStateException("refresh failed")),
                                ),
                            ),
                        relatedResults = ArrayDeque(listOf(Result.success(listOf(relatedMovie)))),
                    )
                val viewModel = repository.detailViewModel(itemId = "episode-1")
                advanceUntilIdle()
                val before = assertIs<DetailUiState.Content>(viewModel.state.value)

                viewModel.refresh()
                advanceUntilIdle()

                assertEquals(before, viewModel.state.value)
                assertEquals(1, repository.relatedCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun storedSubtitleOffSuppressesDefaultSubtitleInDetailTrackSelection() {
        val trackSelection =
            episodeDetail
                .versions
                .first()
                .toDetailTrackSelectionUi(
                    playbackPreferences = PlaybackPreferences(),
                    rememberedSelection = PlaybackSelection(),
                    storedSubtitleSelection = com.jellyscope.core.domain.playback.SubtitleSelectionIntent.Off,
                )

        assertEquals(null, trackSelection.defaultSubtitleStreamIndex)
        assertEquals(
            SubtitleSelectionIntent.Off,
            trackSelection.initialSubtitleSelection,
        )
    }

    @Test
    fun retryRecoversAfterLoadError() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    QueueMediaRepository(
                        detailResults =
                            ArrayDeque(
                                listOf(
                                    Result.failure(IllegalStateException("first")),
                                    Result.success(movieDetail),
                                ),
                            ),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel = repository.detailViewModel(itemId = "movie-1")
                advanceUntilIdle()

                assertIs<DetailUiState.Error>(viewModel.state.value)

                viewModel.retry()
                advanceUntilIdle()

                val state = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals("Movie", state.detail.title)
                assertEquals(0L, state.detail.playAction.startPositionTicks)
                assertEquals(DetailPlayLabel.Start, state.detail.playAction.label)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun watchedToggleOptimisticallyUpdatesAndRevertsOnFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(movieDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                        setPlayedResults = ArrayDeque(listOf(Result.failure(IllegalStateException("failed")))),
                    )
                val viewModel = repository.detailViewModel(itemId = "movie-1")
                advanceUntilIdle()

                viewModel.toggleWatched()
                val optimistic = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals(true, optimistic.detail.isWatched)
                assertEquals(true, optimistic.detail.watchedToggleInFlight)

                advanceUntilIdle()

                val reverted = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals(false, reverted.detail.isWatched)
                assertEquals(listOf("movie-1" to true), repository.setPlayedCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun favoriteToggleOptimisticallyUpdatesAndRevertsOnFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(movieDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                        setFavoriteResults = ArrayDeque(listOf(Result.failure(IllegalStateException("failed")))),
                    )
                val viewModel = repository.detailViewModel(itemId = "movie-1")
                advanceUntilIdle()

                viewModel.toggleFavorite()
                val optimistic = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals(true, optimistic.detail.isFavorite)
                assertEquals(true, optimistic.detail.favoriteToggleInFlight)

                advanceUntilIdle()

                val reverted = assertIs<DetailUiState.Content>(viewModel.state.value)
                assertEquals(false, reverted.detail.isFavorite)
                assertEquals(listOf("movie-1" to true), repository.setFavoriteCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun settlementRefreshesAfterTheRevealRefreshReadStaleDetail() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val registry = PlaybackStopSettlementRegistry()
                val repository =
                    QueueMediaRepository(
                        detailResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(episodeDetail),
                                    Result.success(episodeDetail),
                                    Result.success(episodeDetail.copy(item = episodeDetail.item.copy(played = true))),
                                ),
                            ),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                val viewModel = repository.detailViewModel(itemId = "episode-1", settlementRegistry = registry)
                advanceUntilIdle()

                viewModel.refresh()
                advanceUntilIdle()
                registry.publish(SettlementKey(session.serverId, session.userId, "episode-1"))
                advanceUntilIdle()

                assertEquals(3, repository.detailCalls)
                assertTrue(assertIs<DetailUiState.Content>(viewModel.state.value).detail.isWatched)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun settlementDuringRefreshRunsOneTrailingRefresh() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val registry = PlaybackStopSettlementRegistry()
                val repository = DeferredSettlementDetailRepository()
                val viewModel = repository.detailViewModel(itemId = "episode-1", settlementRegistry = registry)
                advanceUntilIdle()

                registry.publish(SettlementKey(session.serverId, session.userId, "episode-1"))
                runCurrent()
                registry.publish(SettlementKey(session.serverId, session.userId, "episode-1"))
                runCurrent()
                assertEquals(2, repository.detailCalls)

                repository.firstRefresh.complete(Result.success(episodeDetail))
                runCurrent()
                assertEquals(3, repository.detailCalls)
                repository.trailingRefresh.complete(
                    Result.success(episodeDetail.copy(item = episodeDetail.item.copy(played = true))),
                )
                advanceUntilIdle()

                assertEquals(3, repository.detailCalls)
                assertTrue(assertIs<DetailUiState.Content>(viewModel.state.value).detail.isWatched)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun replayedSettlementIsRefreshedOnce() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val registry = PlaybackStopSettlementRegistry()
                registry.publish(SettlementKey(session.serverId, session.userId, "episode-1"))
                val repository =
                    QueueMediaRepository(
                        detailResults = ArrayDeque(listOf(Result.success(episodeDetail), Result.success(episodeDetail))),
                        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
                    )
                repository.detailViewModel(itemId = "episode-1", settlementRegistry = registry)
                advanceUntilIdle()

                registry.publish(SettlementKey(session.serverId, session.userId, "other-item"))
                advanceUntilIdle()

                assertEquals(2, repository.detailCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun MediaRepository.detailViewModel(
    itemId: String,
    sessionOverride: Session = session,
    settlementRegistry: PlaybackStopSettlementRegistry = PlaybackStopSettlementRegistry(),
    getSubtitleSelectionUseCase: GetSubtitleSelectionUseCase? = null,
    getPlaybackLaunchContextUseCase: GetPlaybackLaunchContextUseCase =
        GetPlaybackLaunchContextUseCase(
            getSubtitleSelection = getSubtitleSelectionUseCase,
        ),
    observeLocalSubtitleAssetsUseCase: ObserveLocalSubtitleAssetsUseCase? = null,
    deleteLocalSubtitleAction: DeleteLocalSubtitleAction? = null,
    saveSubtitleSelectionAction: SaveSubtitleSelectionAction? = null,
    previewOriginalDownloadUseCase: PreviewOriginalDownloadUseCase? = null,
    enqueueDownloadAction: EnqueueDownloadAction? = null,
    observeDownloadsUseCase: ObserveDownloadsUseCase? = null,
    previewFixedDownloadUseCase: PreviewFixedDownloadUseCase? = null,
) = DetailViewModel(
    session = sessionOverride,
    itemId = itemId,
    getItemDetailUseCase = GetItemDetailUseCase(this),
    getRelatedItemsUseCase = GetRelatedItemsUseCase(this),
    observePlaybackStopSettlementUseCase = ObservePlaybackStopSettlementUseCase(settlementRegistry),
    setItemFavoriteAction = SetItemFavoriteAction(this),
    setItemPlayedAction = SetItemPlayedAction(this),
    imageUrlBuilder = JellyfinImageUrlBuilder(),
    playbackSelectionMemory = PlaybackSelectionMemory(),
    getPlaybackLaunchContextUseCase = getPlaybackLaunchContextUseCase,
    observeLocalSubtitleAssetsUseCase = observeLocalSubtitleAssetsUseCase,
    deleteLocalSubtitleAction = deleteLocalSubtitleAction,
    saveSubtitleSelectionAction = saveSubtitleSelectionAction,
    previewOriginalDownloadUseCase = previewOriginalDownloadUseCase,
    enqueueDownloadAction = enqueueDownloadAction,
    observeDownloadsUseCase = observeDownloadsUseCase,
    previewFixedDownloadUseCase = previewFixedDownloadUseCase,
    formatterStringsProvider = { englishDetailFormatterStrings() },
    // Installed Main is a StandardTestDispatcher (setMain) so off-main mapping runs under runCurrent().
    workDispatcher = Dispatchers.Main,
)

private fun detailDownloadRecord(
    state: DownloadState,
    itemId: String = "episode-1",
    mediaSourceId: String = "source-1",
    accountIdentity: AccountIdentity = AccountIdentity("server-1", "user-1"),
    quality: DownloadQuality = DownloadQuality.Original,
    artifactKind: DownloadArtifactKind = DownloadArtifactKind.OriginalFile,
): DownloadRecord {
    val physicalBytes = if (state == DownloadState.Completed) 100L else 0L
    val reservationBytes = if (state == DownloadState.Completed) physicalBytes else 100L
    return DownloadRecord(
        request =
            DownloadRequest(
                downloadId = DownloadId("download-$itemId-$mediaSourceId"),
                businessKey = DownloadBusinessKey(accountIdentity, itemId, mediaSourceId),
                quality = quality,
                artifactKind = artifactKind,
                selectedAudioStreamIndex = null,
                subtitleSelection = DownloadSubtitleSelection.Off,
                admissionEstimateBytes = 100L,
                initialReservationBytes = 100L,
                artifactKey = DownloadArtifactKey("artifact-$itemId-$mediaSourceId"),
                snapshot =
                    OfflineMediaSnapshot(
                        title = "Episode",
                        itemKind = MediaKind.Episode,
                        backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                    ),
                createdAtEpochMs = 1L,
            ),
        fifoSequence = 1L,
        state = state,
        reservationBytes = reservationBytes,
        physicalBytes = physicalBytes,
        checkpointBytes = physicalBytes,
        attemptGeneration = 0L,
        failure = if (state == DownloadState.Failed) DownloadFailure.Network else null,
        updatedAtEpochMs = 2L,
    )
}

private class DetailDownloadRepository : DownloadRepository {
    val records = MutableStateFlow<List<DownloadRecord>>(emptyList())

    override fun observeDownloads(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> = records

    override suspend fun getDownload(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord? = null

    override suspend fun getDownloadSettings(): DownloadSettings =
        DownloadSettings(quotaBytes = null, nextFifoSequence = 1L, membershipRevision = 0L)

    override suspend fun getDownloadUsage(accountIdentity: AccountIdentity): DownloadUsage =
        DownloadUsage(
            physicalBytes = 0L,
            currentAccountPhysicalBytes = 0L,
            otherAccountsPhysicalBytes = 0L,
            outstandingReservationBytes = 0L,
            projectedCommittedBytes = 0L,
            quotaBytes = null,
            remainingQuotaBytes = null,
            deviceAvailableBytes = 1L,
            safetyReserveBytes = 0L,
            maximumConfigurableQuotaBytes = 1L,
            overAllocation = false,
        )

    override suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings = getDownloadSettings()

    override suspend fun enqueue(request: DownloadRequest): DownloadEnqueueResult = DownloadEnqueueResult.RemovalInProgress

    override suspend fun pause(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = DownloadCommandResult.Applied

    override suspend fun resume(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = DownloadCommandResult.Applied

    override suspend fun retry(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadCommandResult = DownloadCommandResult.Applied

    override suspend fun cancel(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = DownloadDeletionResult.Deleted

    override suspend fun delete(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadDeletionResult = DownloadDeletionResult.Deleted

    override suspend fun updateLocalPlayback(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean?,
    ): Boolean = false
}

private class BlockingFixedDownloadAdmission : FixedDownloadAdmission {
    val requests = mutableListOf<FixedDownloadDraft>()
    val results = mutableListOf<CompletableDeferred<FixedDownloadAdmissionResult>>()

    override suspend fun admit(draft: FixedDownloadDraft): FixedDownloadAdmissionResult {
        requests += draft
        return CompletableDeferred<FixedDownloadAdmissionResult>()
            .also { deferred ->
                results += deferred
            }.let { deferred -> withContext(NonCancellable) { deferred.await() } }
    }

    override suspend fun admitAndEnqueue(
        draft: FixedDownloadDraft,
        enqueue: suspend (DownloadRequest) -> DownloadEnqueueResult,
    ): DownloadEnqueueResult = error("The preview-only test admission must not enqueue.")
}

private class AvailableOriginalDownloadAdmission : OriginalDownloadAdmission {
    override suspend fun admit(draft: OriginalDownloadDraft): OriginalDownloadAdmissionResult =
        OriginalDownloadAdmissionResult.Ready(draft.toDownloadRequest())

    override suspend fun admitAndEnqueue(
        draft: OriginalDownloadDraft,
        enqueue: suspend (DownloadRequest) -> DownloadEnqueueResult,
    ): DownloadEnqueueResult = enqueue(draft.toDownloadRequest())
}

private fun OriginalDownloadDraft.toDownloadRequest(): DownloadRequest =
    DownloadRequest(
        downloadId = downloadId,
        businessKey = businessKey,
        quality = DownloadQuality.Original,
        artifactKind = DownloadArtifactKind.OriginalFile,
        selectedAudioStreamIndex = selectedAudioStreamIndex,
        subtitleSelection = subtitleSelection,
        admissionEstimateBytes = 1L,
        initialReservationBytes = 1L,
        expectedSourceBytes = 1L,
        artifactKey = artifactKey,
        snapshot = snapshot,
        createdAtEpochMs = createdAtEpochMs,
    )

private fun FixedDownloadDraft.toDownloadRequest(): DownloadRequest =
    DownloadRequest(
        downloadId = downloadId,
        businessKey = businessKey,
        quality = quality,
        artifactKind = DownloadArtifactKind.LocalHlsPackage,
        selectedAudioStreamIndex = selectedAudioStreamIndex,
        subtitleSelection = subtitleSelection,
        admissionEstimateBytes = 1L,
        initialReservationBytes = 1L,
        artifactKey = artifactKey,
        snapshot = snapshot,
        createdAtEpochMs = createdAtEpochMs,
    )

private open class QueueMediaRepository(
    private val detailResults: ArrayDeque<Result<MediaItemDetail>>,
    private val relatedResults: ArrayDeque<Result<List<MediaItem>>>,
    private val setPlayedResults: ArrayDeque<Result<Unit>> = ArrayDeque(listOf(Result.success(Unit))),
    private val setFavoriteResults: ArrayDeque<Result<Unit>> = ArrayDeque(listOf(Result.success(Unit))),
    private val deferredSetPlayedResult: CompletableDeferred<Result<Unit>>? = null,
    private val deferredSetFavoriteResult: CompletableDeferred<Result<Unit>>? = null,
) : MediaRepository {
    val setPlayedCalls = mutableListOf<Pair<String, Boolean>>()
    val setFavoriteCalls = mutableListOf<Pair<String, Boolean>>()
    var relatedCalls = 0
    var detailCalls = 0

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(seriesId: String?): Result<List<MediaItem>> = Result.success(emptyList())

    open override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> {
        detailCalls += 1
        return detailResults.removeFirst()
    }

    open override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> {
        relatedCalls += 1
        return relatedResults.removeFirst()
    }

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getFavorites(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun search(query: FindQuery): Result<FindResults> = Result.success(FindResults())

    override suspend fun findPersons(term: String): Result<List<Person>> = Result.success(emptyList())

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> {
        setPlayedCalls += itemId to played
        return deferredSetPlayedResult?.await() ?: setPlayedResults.removeFirst()
    }

    override suspend fun setFavorite(
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> {
        setFavoriteCalls += itemId to favorite
        return deferredSetFavoriteResult?.await() ?: setFavoriteResults.removeFirst()
    }

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> = Result.failure(UnsupportedOperationException())
}

private class StreamingRefreshMediaRepository :
    QueueMediaRepository(
        detailResults = ArrayDeque(),
        relatedResults = ArrayDeque(),
    ) {
    val refreshDetail = CompletableDeferred<MediaItemDetail>()
    val relatedGroups = Channel<RelatedGroup>(Channel.UNLIMITED)
    private var streamingDetailCalls = 0

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> {
        streamingDetailCalls += 1
        return Result.success(if (streamingDetailCalls == 1) episodeDetail else refreshDetail.await())
    }

    override fun getRelatedGroups(detail: MediaItemDetail): Flow<RelatedGroup> {
        relatedCalls += 1
        return relatedGroups.receiveAsFlow()
    }
}

private class DeferredSettlementDetailRepository :
    QueueMediaRepository(
        detailResults = ArrayDeque(),
        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
    ) {
    val firstRefresh = CompletableDeferred<Result<MediaItemDetail>>()
    val trailingRefresh = CompletableDeferred<Result<MediaItemDetail>>()

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> =
        when (++detailCalls) {
            1 -> Result.success(episodeDetail)
            2 -> firstRefresh.await()
            else -> trailingRefresh.await()
        }
}

private class OverlappingVersionRefreshRepository(
    private val initialDetail: MediaItemDetail,
) : QueueMediaRepository(
        detailResults = ArrayDeque(),
        relatedResults = ArrayDeque(listOf(Result.success(emptyList()))),
    ) {
    val staleRefresh = CompletableDeferred<Result<MediaItemDetail>>()
    val newerRefresh = CompletableDeferred<Result<MediaItemDetail>>()

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> =
        when (++detailCalls) {
            1 -> Result.success(initialDetail)
            2 -> staleRefresh.await()
            else -> newerRefresh.await()
        }
}

private class DetailPlaybackSelectionStore(
    stored: Map<PlaybackSelectionKey, PlaybackSelection>,
) : PlaybackSelectionStore {
    private val selections = stored.toMutableMap()

    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? = selections[key]

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) {
        selections[key] = selection
    }

    override suspend fun delete(key: PlaybackSelectionKey) {
        selections.remove(key)
    }

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) {
        selections.keys.removeAll { key -> key.serverId == serverId && key.userId == userId }
    }

    override suspend fun clearServerScoped(serverId: String) {
        selections.keys.removeAll { key -> key.serverId == serverId }
    }

    override suspend fun clearServerScoped() {
        selections.clear()
    }
}

private class DelayedDetailPlaybackSelectionStore : PlaybackSelectionStore {
    val sourceTwo = CompletableDeferred<PlaybackSelection?>()
    val sourceThree = CompletableDeferred<PlaybackSelection?>()

    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? =
        when (key.mediaSourceId) {
            "source-2" -> sourceTwo.await()
            "source-3" -> sourceThree.await()
            else -> null
        }

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) = Unit

    override suspend fun delete(key: PlaybackSelectionKey) = Unit

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private class DetailViewModelRecordingSubtitleSelectionStore : SubtitleSelectionStore {
    val writes = mutableListOf<Pair<SubtitleSelectionKey, SubtitleSelectionIntent>>()

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? =
        writes.lastOrNull { write -> write.first == key }?.second

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) {
        writes += key to selection
    }

    override suspend fun delete(key: SubtitleSelectionKey) {
        writes.removeAll { write -> write.first == key }
    }

    override suspend fun clearServerScoped(serverId: String) {
        writes.removeAll { write -> write.first.serverId == serverId }
    }

    override suspend fun clearServerScoped() {
        writes.clear()
    }
}

private class DetailViewModelLocalSubtitleAssetStore(
    vararg initialAssets: LocalSubtitleAsset,
) : LocalSubtitleAssetStore {
    private val assets = initialAssets.toMutableList()
    val observedContexts = mutableListOf<LocalSubtitleContext>()

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> {
        observedContexts += context
        return flowOf(assets.filter { candidate -> candidate.context() == context })
    }

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = flowOf(emptyList())

    override suspend fun get(assetId: String): LocalSubtitleAsset? = assets.firstOrNull { candidate -> candidate.id == assetId }

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? =
        assets.firstOrNull { candidate ->
            candidate.context() == context && candidate.provider == provider && candidate.providerFileId == providerFileId
        }

    override suspend fun upsert(asset: LocalSubtitleAsset) {
        assets.removeAll { candidate -> candidate.id == asset.id }
        assets += asset
    }

    override suspend fun delete(assetId: String) {
        assets.removeAll { candidate -> candidate.id == assetId }
    }

    override suspend fun all(): List<LocalSubtitleAsset> = assets.toList()

    override suspend fun clearAll() {
        assets.clear()
    }
}

private class DetailViewModelLocalSubtitleFileStore(
    private val fileId: String,
) : LocalSubtitleFileStore {
    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun read(fileId: String): ByteArray? = null

    override suspend fun exists(fileId: String): Boolean = fileId == this.fileId

    override suspend fun delete(fileId: String) = Unit

    override suspend fun listFileIds(): Set<String> = setOf(fileId)

    override fun resolvePath(fileId: String): String? = null
}

private fun LocalSubtitleAsset.context(): LocalSubtitleContext =
    LocalSubtitleContext(
        serverId = serverId,
        userId = userId,
        itemId = itemId,
        mediaSourceId = mediaSourceId,
    )

private val detailLocalSubtitleAsset =
    LocalSubtitleAsset(
        id = "asset-1",
        serverId = "server-1",
        userId = "user-1",
        itemId = "episode-1",
        mediaSourceId = "source-1",
        provider = "OpenSubtitles",
        providerSubtitleId = "subtitle-1",
        providerFileId = "file-1",
        language = "en",
        label = "English download",
        releaseName = "Episode.1080p",
        originalFormat = "srt",
        mimeType = "text/vtt",
        fileId = "asset-1.vtt",
        hearingImpaired = false,
        forced = false,
        trusted = true,
        createdAtEpochMs = 1L,
        lastUsedAtEpochMs = 1L,
        syncState = LocalSubtitleSyncState.Pending,
    )

private fun detailWithSelectableSources(includeThirdSource: Boolean = false): MediaItemDetail {
    val sourceOne =
        episodeDetail.versions.single().copy(
            releaseBasename = "First.Release.mkv",
            sizeBytes = 1_073_741_824L,
        )
    val sourceTwo =
        MediaVersion(
            id = "source-2",
            name = "4K",
            releaseBasename = "Second.Release.mkv",
            sizeBytes = 2_147_483_648L,
            mediaStreams =
                listOf(
                    PlaybackMediaStream(
                        index = 9,
                        type = "Video",
                        displayTitle = null,
                        title = null,
                        language = null,
                        codec = "hevc",
                        channelLayout = null,
                        bitRate = null,
                        height = 2160,
                        isDefault = null,
                        isExternal = null,
                        deliveryMethod = null,
                        deliveryUrl = null,
                    ),
                    PlaybackMediaStream(
                        index = 10,
                        type = "Audio",
                        displayTitle = "English AAC 2.0",
                        title = null,
                        language = "eng",
                        codec = "aac",
                        channelLayout = "2.0",
                        bitRate = null,
                        height = null,
                        isDefault = true,
                        isExternal = null,
                        deliveryMethod = null,
                        deliveryUrl = null,
                    ),
                    PlaybackMediaStream(
                        index = 11,
                        type = "Audio",
                        displayTitle = "Spanish AAC 2.0",
                        title = null,
                        language = "spa",
                        codec = "aac",
                        channelLayout = "2.0",
                        bitRate = null,
                        height = null,
                        isDefault = false,
                        isExternal = null,
                        deliveryMethod = null,
                        deliveryUrl = null,
                    ),
                    PlaybackMediaStream(
                        index = 12,
                        type = "Subtitle",
                        displayTitle = "Spanish SRT",
                        title = null,
                        language = "spa",
                        codec = "srt",
                        channelLayout = null,
                        bitRate = null,
                        height = null,
                        isDefault = false,
                        isExternal = true,
                        deliveryMethod = "External",
                        deliveryUrl = "/subtitles/12.srt",
                    ),
                ),
        )
    val sourceThree =
        sourceTwo.copy(
            id = "source-3",
            name = "Alternate",
            releaseBasename = "Third.Release.mkv",
            mediaStreams =
                sourceTwo.mediaStreams.map { stream ->
                    when (stream.index) {
                        10 -> stream.copy(index = 20)
                        11 -> stream.copy(index = 21)
                        12 -> stream.copy(index = 22)
                        else -> stream
                    }
                },
        )
    return episodeDetail.copy(
        versions =
            if (includeThirdSource) {
                listOf(sourceOne, sourceTwo, sourceThree)
            } else {
                listOf(sourceOne, sourceTwo)
            },
    )
}

private val episodeDetail =
    MediaItemDetail(
        item =
            MediaItem(
                id = "episode-1",
                name = "Episode",
                kind = MediaKind.Episode,
                seriesName = "A Show",
                seriesId = "series-1",
                indexNumber = 3,
                parentIndexNumber = 2,
                runtime = 45.minutes,
                played = false,
                isFavorite = true,
                playedPercentage = 40.0,
                playbackPositionTicks = 2.minutes.inWholeMilliseconds * JELLYFIN_TICKS_PER_MILLISECOND,
                imageRefs =
                    ImageRefs(
                        primaryTag = "poster-tag",
                        backdropTag = "backdrop-tag",
                    ),
            ),
        overview = "Episode overview.",
        genres = listOf("Drama"),
        officialRating = "TV-14",
        communityRating = 7.4,
        criticRating = 86.6,
        imdbId = "tt1234567",
        tmdbId = "603",
        tmdbItemType = "movie",
        productionYear = 2024,
        people =
            listOf(
                MediaPerson(
                    id = "actor-1",
                    name = "Actor One",
                    role = "Lead",
                    type = MediaPersonType.Actor,
                    primaryImageTag = "actor-tag",
                ),
                MediaPerson(
                    id = "director-1",
                    name = "Director One",
                    type = MediaPersonType.Director,
                ),
            ),
        versions =
            listOf(
                MediaVersion(
                    id = "source-1",
                    name = "1080p",
                    audioTracks = listOf("English AAC"),
                    subtitleTracks = listOf("English SDH"),
                    mediaStreams =
                        listOf(
                            PlaybackMediaStream(
                                index = 0,
                                type = "Video",
                                displayTitle = null,
                                title = null,
                                language = null,
                                codec = "h264",
                                channelLayout = null,
                                bitRate = null,
                                height = 1080,
                                isDefault = null,
                                isExternal = null,
                                deliveryMethod = null,
                                deliveryUrl = null,
                            ),
                            PlaybackMediaStream(
                                index = 1,
                                type = "Audio",
                                displayTitle = "English EAC3 5.1",
                                title = null,
                                language = "eng",
                                codec = "eac3",
                                channelLayout = "5.1",
                                bitRate = null,
                                height = null,
                                isDefault = true,
                                isExternal = null,
                                deliveryMethod = null,
                                deliveryUrl = null,
                            ),
                            PlaybackMediaStream(
                                index = 2,
                                type = "Subtitle",
                                displayTitle = "English SRT",
                                title = null,
                                language = "eng",
                                codec = "srt",
                                channelLayout = null,
                                bitRate = null,
                                height = null,
                                isDefault = true,
                                isExternal = true,
                                deliveryMethod = "External",
                                deliveryUrl = "/subtitles/2.srt",
                            ),
                        ),
                ),
            ),
        trailerUrl = "https://trailers.example/episode",
    )

private val movieDetail =
    MediaItemDetail(
        item =
            MediaItem(
                id = "movie-1",
                name = "Movie",
                kind = MediaKind.Movie,
            ),
    )

private val relatedMovie =
    MediaItem(
        id = "movie-1",
        name = "Related Movie",
        kind = MediaKind.Movie,
    )

private val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        userId = "user-1",
        userName = "Demo User",
        accessToken = "token-1",
        deviceId = "device-1",
        enableContentDownloading = true,
    )
