// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.PlayerBackendOverrideStore
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.data.repository.LocalSubtitleMutationCoordinator
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.SavePlaybackSelectionAction
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadCommandResult
import com.jellyscope.core.domain.model.DownloadDeletionResult
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.DirectPlayPlanner
import com.jellyscope.core.domain.playback.DroppedFrameMeasurement
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackInfoPlanner
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackMediaSourceInfo
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerVolumeController
import com.jellyscope.core.domain.playback.PlayerVolumeState
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.domain.playback.applePlayerBackendPolicy
import com.jellyscope.core.domain.usecase.GetChronologicalEpisodeQueueUseCase
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetItemsByIdsUseCase
import com.jellyscope.core.domain.usecase.GetLocalSubtitleAssetUseCase
import com.jellyscope.core.domain.usecase.GetMediaSegmentsUseCase
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionUseCase
import com.jellyscope.core.domain.usecase.GetPlayerBackendOverrideUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext

internal enum class TestGuidancePolicy {
    Disabled,
    Actionable,
    Advisory,
}

private fun TestGuidancePolicy.toPlaybackHealthGuidancePolicy(): PlaybackHealthGuidancePolicy =
    when (this) {
        TestGuidancePolicy.Disabled -> PlaybackHealthGuidancePolicy.Disabled
        TestGuidancePolicy.Actionable -> PlaybackHealthGuidancePolicy.Actionable
        TestGuidancePolicy.Advisory -> PlaybackHealthGuidancePolicy.Advisory
    }

internal val PlayerUiState.Content.qualityReductionGuidance: PlaybackHealthGuidance?
    get() =
        playbackGuidance
            ?.nextLowerQualityRungBps
            ?.takeIf { playbackGuidance.canReduceQuality }
            ?.let { playbackGuidance }

internal val PlayerUiState.Content.passiveGuidance: PlaybackHealthGuidance?
    get() =
        playbackGuidance
            ?.takeIf { !it.canReduceQuality }

internal fun localSubtitleAsset() =
    LocalSubtitleAsset(
        id = "asset-1",
        serverId = session.serverId,
        userId = session.userId,
        itemId = "item-1",
        mediaSourceId = "source-1",
        provider = "OpenSubtitles",
        providerSubtitleId = "subtitle-1",
        providerFileId = "file-1",
        language = "en",
        label = "English download",
        releaseName = "Movie.1080p",
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

private class FakeLogCollectionPreferenceStore(
    playbackInfoAtStart: Boolean,
) : LogCollectionPreferenceStore {
    override val enabled = MutableStateFlow(false)
    override val verboseLogcatEnabled = MutableStateFlow(false)
    override val playbackInfoAtStartEnabled = MutableStateFlow(playbackInfoAtStart)

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
        verboseLogcatEnabled.value = enabled
    }

    override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        playbackInfoAtStartEnabled.value = enabled
    }
}

internal fun offlineRecord(
    downloadId: DownloadId,
    artifactKind: DownloadArtifactKind = DownloadArtifactKind.OriginalFile,
): DownloadRecord {
    val quality =
        if (artifactKind == DownloadArtifactKind.LocalHlsPackage) {
            DownloadQuality.Fixed(maxBitrateBps = 2_000_000L)
        } else {
            DownloadQuality.Original
        }
    val request =
        DownloadRequest(
            downloadId = downloadId,
            businessKey =
                DownloadBusinessKey(
                    session.accountIdentity(),
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                ),
            quality = quality,
            artifactKind = artifactKind,
            selectedAudioStreamIndex = null,
            subtitleSelection = com.jellyscope.core.domain.model.DownloadSubtitleSelection.Off,
            admissionEstimateBytes = 100L,
            initialReservationBytes = 100L,
            expectedSourceBytes = 100L.takeIf { artifactKind == DownloadArtifactKind.OriginalFile },
            artifactKey = DownloadArtifactKey("offline-artifact"),
            snapshot =
                OfflineMediaSnapshot(
                    title = "Offline item",
                    itemKind = MediaKind.Movie,
                    durationMs = 60_000L,
                    sourcePresentation = "Original",
                    backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                ),
            createdAtEpochMs = 0L,
        )
    return DownloadRecord(
        request = request,
        fifoSequence = 1L,
        state = DownloadState.Completed,
        reservationBytes = 100L,
        physicalBytes = 100L,
        checkpointBytes = 100L,
        attemptGeneration = 1L,
        localResumePositionMs = 1_000L,
        updatedAtEpochMs = 1L,
    )
}

internal fun appleOfflineProfileProvider(
    supportedBackends: Set<PlayerBackend> = setOf(PlayerBackend.AVPlayer, PlayerBackend.VlcKit),
): DeviceProfileProvider =
    object : DeviceProfileProvider {
        override val backendPolicy = applePlayerBackendPolicy()
        override val availableBackends: Set<PlayerBackend> = supportedBackends

        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
            )
    }

internal class OfflinePlaybackDownloadRepository(
    private val record: DownloadRecord,
) : DownloadRepository {
    var getDownloadCalls = 0
    var hasCompletedArtifactCalls = 0

    override fun observeDownloads(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> = flowOf(listOf(record))

    override suspend fun getDownload(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord? {
        getDownloadCalls += 1
        return record.takeIf {
            it.downloadId == downloadId && it.businessKey.accountIdentity == accountIdentity
        }
    }

    override suspend fun hasCompletedArtifact(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        attemptGeneration: Long,
    ): Boolean {
        hasCompletedArtifactCalls += 1
        return record.businessKey.accountIdentity == accountIdentity &&
            record.downloadId == downloadId &&
            record.attemptGeneration == attemptGeneration
    }

    override suspend fun isArtifactLeased(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): Boolean = false

    override suspend fun getDownloadSettings(): DownloadSettings =
        DownloadSettings(quotaBytes = null, nextFifoSequence = 2L, membershipRevision = 0L)

    override suspend fun getDownloadUsage(accountIdentity: AccountIdentity): DownloadUsage =
        DownloadUsage(
            physicalBytes = record.physicalBytes,
            currentAccountPhysicalBytes = record.physicalBytes,
            otherAccountsPhysicalBytes = 0L,
            outstandingReservationBytes = 0L,
            projectedCommittedBytes = record.physicalBytes,
            quotaBytes = null,
            remainingQuotaBytes = null,
            deviceAvailableBytes = Long.MAX_VALUE,
            safetyReserveBytes = 0L,
            maximumConfigurableQuotaBytes = Long.MAX_VALUE,
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
    ): Boolean = true
}

internal fun runPlayerViewModelTest(block: suspend TestScope.() -> Unit) {
    runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }
}

internal fun TestScope.drainPlayerViewModelSchedulers(workDispatcher: TestDispatcher) {
    repeat(4) {
        runCurrent()
        workDispatcher.scheduler.advanceUntilIdle()
    }
    runCurrent()
}

internal fun playerFixture(
    itemId: String = "item-1",
    playbackInfo: PlaybackInfo = directPlayPlaybackInfo,
    playbackInfoResults: ArrayDeque<Result<PlaybackInfo>> = ArrayDeque(),
    mediaStreams: List<PlaybackMediaStream> = playbackStreams,
    initialAudioStreamIndex: Int? = null,
    initialSubtitleStreamIndex: Int? = null,
    initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.fromWireIndex(initialSubtitleStreamIndex),
    localSubtitleAsset: LocalSubtitleAsset? = null,
    memory: PlaybackSelectionMemory = PlaybackSelectionMemory(),
    selectionStore: FakePlaybackSelectionStore? = null,
    queue: List<String> = emptyList(),
    queueItems: List<MediaItem> = emptyList(),
    seasons: List<MediaItem> = emptyList(),
    episodesBySeasonId: Map<String, List<MediaItem>> = emptyMap(),
    seasonsFailure: Boolean = false,
    episodeFailures: Set<String> = emptySet(),
    detailItems: Map<String, MediaItem> = emptyMap(),
    detailFailures: Set<String> = emptySet(),
    mediaSegments: List<MediaSegment> = emptyList(),
    chapters: List<Chapter> = emptyList(),
    trickplayByMediaSourceId: Map<String, TrickplayInfo?> = emptyMap(),
    playbackPreferencesStore: PlaybackPreferencesStore? = null,
    playerDeviceSettingsStore: PlayerDeviceSettingsStore = FakePlayerDeviceSettingsStore(),
    confirmInitialAudio: Boolean = true,
    // Default to the installed Main (a StandardTestDispatcher via setMain) so
    // off-main work (withContext/async(workDispatcher)) is driven by runCurrent().
    workDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
    fallbackRequestGate: CompletableDeferred<Unit>? = null,
    hasReliableBufferingTransitions: Boolean = false,
    appliesSubtitleStyle: Boolean = true,
    hasDroppedFrameMeasurements: Boolean = false,
    hasReliableFirstVideoOutput: Boolean = false,
    playbackInfoAtStartEnabled: Boolean = false,
    videoOutputMeasurementCapabilities: VideoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.Unsupported,
    guidancePolicy: TestGuidancePolicy = TestGuidancePolicy.Actionable,
    activeBackend: PlayerBackend = PlayerBackend.Auto,
    initialControllerIsPending: Boolean = false,
    playerControllerFactory: ((PlayerBackend) -> PlayerController)? = null,
    offlineDownloadId: DownloadId? = null,
    getOfflinePlaybackPlanUseCase: GetOfflinePlaybackPlanUseCase? = null,
    monotonicTimeMs: (() -> Long)? = null,
    publishPrepareEpoch: Boolean = false,
    volumeController: FakeVolumePlayerController? = null,
    deviceProfileProvider: DeviceProfileProvider? = null,
    playerBackendOverrideStore: PlayerBackendOverrideStore? = null,
    playbackDiagnosticsContext: PlaybackDiagnosticsContext? = null,
): PlayerFixture {
    val monotonicOrigin =
        kotlin.time.TimeSource.Monotonic
            .markNow()
    val coordinatorClock =
        monotonicTimeMs ?: {
            monotonicOrigin.elapsedNow().inWholeMilliseconds
        }
    val controller = volumeController ?: FakePlayerController(confirmInitialAudio, publishPrepareEpoch)
    controller.appliesSubtitleStyle = appliesSubtitleStyle
    controller.videoOutputMeasurementCapabilities = videoOutputMeasurementCapabilities
    controller.playbackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities(
            hasReliableBufferingTransitions = hasReliableBufferingTransitions,
            hasDroppedFrameMeasurements = hasDroppedFrameMeasurements,
            hasReliableFirstVideoOutput = hasReliableFirstVideoOutput,
        )
    controller.activeBackend = activeBackend
    val reporter = FakePlaybackProgressReporter()
    val repository =
        FakeMediaRepository(
            playbackInfo = playbackInfo,
            playbackInfoResults = playbackInfoResults,
            queueItems = queueItems,
            seasons = seasons,
            episodesBySeasonId = episodesBySeasonId,
            seasonsFailure = seasonsFailure,
            episodeFailures = episodeFailures,
            detailItems = detailItems,
            detailFailures = detailFailures,
            mediaSegments = mediaSegments,
            chapters = chapters,
            trickplayByMediaSourceId = trickplayByMediaSourceId,
            mediaStreams = mediaStreams,
            fallbackRequestGate = fallbackRequestGate,
        )
    val viewModel =
        PlayerViewModel(
            session = session,
            itemId = itemId,
            startPositionTicks = 10_000_000L,
            mediaSourceId = "source-1",
            initialAudioStreamIndex = initialAudioStreamIndex,
            initialSubtitleSelection = initialSubtitleSelection,
            queue = queue,
            playerController = controller,
            initialControllerIsPending = initialControllerIsPending,
            playbackInfoPlanner =
                PlaybackInfoPlanner(
                    mediaRepository = repository,
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider = deviceProfileProvider,
                ),
            getPlaybackInfoAtStartStateUseCase =
                GetPlaybackInfoAtStartStateUseCase(FakeLogCollectionPreferenceStore(playbackInfoAtStartEnabled)),
            progressReporter = reporter,
            playbackStopSettlementRegistry = PlaybackStopSettlementRegistry(),
            getItemDetailUseCase = GetItemDetailUseCase(repository),
            getMediaSegmentsUseCase = GetMediaSegmentsUseCase(repository),
            getItemsByIdsUseCase = GetItemsByIdsUseCase(repository),
            getChronologicalEpisodeQueueUseCase =
                GetChronologicalEpisodeQueueUseCase(
                    getSeriesSeasonsUseCase = GetSeriesSeasonsUseCase(repository),
                    getSeasonEpisodesUseCase = GetSeasonEpisodesUseCase(repository),
                ),
            imageUrlBuilder = JellyfinImageUrlBuilder(),
            deviceInfoProvider = FakeDeviceInfoProvider(),
            playbackSelectionMemory = memory,
            getPlaybackLaunchContextUseCase =
                GetPlaybackLaunchContextUseCase(
                    getPlaybackPreferences = playbackPreferencesStore?.let { GetPlaybackPreferencesUseCase(it) },
                    getPlaybackSelection = selectionStore?.let { GetPlaybackSelectionUseCase(it) },
                ),
            savePlaybackSelectionAction =
                selectionStore?.let { store ->
                    SavePlaybackSelectionAction(store, CoroutineScope(SupervisorJob() + workDispatcher), workDispatcher)
                },
            observePlayerDeviceSettingsUseCase = ObservePlayerDeviceSettingsUseCase(playerDeviceSettingsStore),
            playerControllerFactory = playerControllerFactory ?: { controller },
            deviceProfileProvider = deviceProfileProvider,
            playbackDiagnosticsContext = playbackDiagnosticsContext,
            getPlayerBackendOverrideUseCase =
                playerBackendOverrideStore?.let(::GetPlayerBackendOverrideUseCase),
            getLocalSubtitleAssetUseCase =
                localSubtitleAsset?.let { asset ->
                    val coordinator =
                        LocalSubtitleMutationCoordinator(
                            assetStore = FakeLocalSubtitleAssetStore(asset),
                            fileStore = FakeLocalSubtitleFileStore(asset.fileId),
                            selectionStore = PlayerNoopSubtitleSelectionStore,
                            scope = CoroutineScope(SupervisorJob() + workDispatcher),
                        )
                    GetLocalSubtitleAssetUseCase(coordinator)
                },
            workDispatcher = workDispatcher,
            monotonicTimeMs = coordinatorClock,
            playbackHealthGuidancePolicy = guidancePolicy.toPlaybackHealthGuidancePolicy(),
            offlineDownloadId = offlineDownloadId,
            getOfflinePlaybackPlanUseCase = getOfflinePlaybackPlanUseCase,
        )

    return PlayerFixture(
        viewModel = viewModel,
        controller = controller,
        reporter = reporter,
        repository = repository,
    )
}

internal data class PlayerFixture(
    val viewModel: PlayerViewModel,
    val controller: FakePlayerController,
    val reporter: FakePlaybackProgressReporter,
    val repository: FakeMediaRepository,
)

internal class RecordingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    var running = false
        private set
    var dispatchCount = 0
        private set

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatchCount += 1
        delegate.dispatch(
            context,
            Runnable {
                val wasRunning = running
                running = true
                try {
                    block.run()
                } finally {
                    running = wasRunning
                }
            },
        )
    }
}

internal class FakePlaybackSelectionStore : PlaybackSelectionStore {
    val values = mutableMapOf<PlaybackSelectionKey, PlaybackSelection>()

    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? = values[key]

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) {
        values[key] = selection
    }

    override suspend fun delete(key: PlaybackSelectionKey) {
        values.remove(key)
    }

    override suspend fun clearServerScoped() = values.clear()

    override suspend fun clearServerScoped(serverId: String) {
        values.keys.removeAll { key -> key.serverId == serverId }
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        clearAccount(accountIdentity.serverId, accountIdentity.userId)
    }

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) {
        values.keys.removeAll { key -> key.serverId == serverId && key.userId == userId }
    }
}

internal class FakePlayerBackendOverrideStore(
    private val backend: PlayerBackend?,
) : PlayerBackendOverrideStore {
    override suspend fun get(
        serverId: String,
        itemId: String,
    ): PlayerBackend? = backend

    override suspend fun save(
        serverId: String,
        itemId: String,
        backend: PlayerBackend?,
    ) = Unit

    override suspend fun delete(
        serverId: String,
        itemId: String,
    ) = Unit

    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit
}

internal class FakePlayerDeviceSettingsStore(
    initialSettings: PlayerDeviceSettings = PlayerDeviceSettings(),
) : PlayerDeviceSettingsStore {
    private val settingsFlow = MutableStateFlow(initialSettings)

    override val settings: StateFlow<PlayerDeviceSettings> = settingsFlow

    override suspend fun setSettings(settings: PlayerDeviceSettings) {
        settingsFlow.value = settings
    }
}

private class FakeLocalSubtitleAssetStore(
    initial: LocalSubtitleAsset,
) : LocalSubtitleAssetStore {
    private var asset: LocalSubtitleAsset? = initial

    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> =
        flowOf(
            listOfNotNull(
                asset?.takeIf {
                    it.serverId == context.serverId &&
                        it.userId == context.userId &&
                        it.itemId == context.itemId &&
                        it.mediaSourceId == context.mediaSourceId
                },
            ),
        )

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> = flowOf(emptyList())

    override suspend fun get(assetId: String): LocalSubtitleAsset? = asset?.takeIf { it.id == assetId }

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? = asset?.takeIf { it.provider == provider && it.providerFileId == providerFileId }

    override suspend fun upsert(asset: LocalSubtitleAsset) {
        this.asset = asset
    }

    override suspend fun delete(assetId: String) {
        if (asset?.id == assetId) asset = null
    }

    override suspend fun all(): List<LocalSubtitleAsset> = listOfNotNull(asset)

    override suspend fun clearAll() {
        asset = null
    }
}

private class FakeLocalSubtitleFileStore(
    private val fileId: String,
) : LocalSubtitleFileStore {
    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun read(fileId: String): ByteArray? = "WEBVTT\n\n".encodeToByteArray().takeIf { fileId == this.fileId }

    override suspend fun exists(fileId: String): Boolean = fileId == this.fileId

    override suspend fun delete(fileId: String) = Unit

    override suspend fun listFileIds(): Set<String> = setOf(fileId)

    override fun resolvePath(fileId: String): String? = "/tmp/$fileId".takeIf { fileId == this.fileId }
}

private object PlayerNoopSubtitleSelectionStore : SubtitleSelectionStore {
    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? = null

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) = Unit

    override suspend fun delete(key: SubtitleSelectionKey) = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

/**
 * Emits one dropped-frame measurement exactly as a controller would. Going
 * through the production factory keeps fixtures from expressing a rate that a
 * real backend could never report.
 */
internal fun FakePlayerController.emitMeasurement(
    droppedFrames: Long,
    intervalMs: Long,
) {
    val measurement =
        DroppedFrameMeasurement.create(droppedFrames = droppedFrames, intervalMs = intervalMs)
            ?: error("invalid measurement fixture: $droppedFrames frames over $intervalMs ms")
    droppedFrameMeasurementsChannel.trySend(measurement)
}

internal open class FakePlayerController(
    private val confirmInitialAudio: Boolean,
    private val publishPrepareEpoch: Boolean = false,
) : PlayerController {
    val playbackStateFlow = MutableStateFlow(playbackState(PlaybackStatus.Idle))
    val runtimeDiagnosticsFlow = MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    val seekPositions = mutableListOf<Long>()
    val embeddedAudioOrdinals = mutableListOf<Int>()
    val embeddedTextOrdinals = mutableListOf<Int?>()
    var retryCount = 0
    var offlinePrepareCount = 0
    val offlinePreparePlans = mutableListOf<PlaybackPlan>()
    var preparedPlan: PlaybackPlan? = null
    var prepareCount = 0
    var prepareFailure: Throwable? = null
    var preparePublishedState: PlaybackState? = null
    var playCount = 0
    var pauseCount = 0
    var stopCount = 0
    var releaseCount = 0
    var preparedSubtitleAsset: com.jellyscope.core.domain.playback.SubtitleAsset? = null
    val preparedExternalSubtitle: com.jellyscope.core.domain.playback.SubtitleAsset.JellyfinRemote?
        get() = preparedSubtitleAsset as? com.jellyscope.core.domain.playback.SubtitleAsset.JellyfinRemote
    var appliedPlaybackSpeed: Float = 1f
    var appliedSubtitleStyle: SubtitleStyle = SubtitleStyle()
    val recordedLaunchDurations = mutableListOf<Pair<Long, Long>>()

    // Mirrors the production primitive: a buffered channel, so a measurement
    // emitted before the ViewModel's collector starts is still delivered.
    val droppedFrameMeasurementsChannel = Channel<DroppedFrameMeasurement>(Channel.BUFFERED)
    val videoOutputObservationsChannel = Channel<VideoOutputObservation>(Channel.BUFFERED)

    override val playbackState: StateFlow<PlaybackState> = playbackStateFlow
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = runtimeDiagnosticsFlow
    override val droppedFrameMeasurements: Flow<DroppedFrameMeasurement> = droppedFrameMeasurementsChannel.receiveAsFlow()
    override val videoOutputObservations: Flow<VideoOutputObservation> = videoOutputObservationsChannel.receiveAsFlow()
    override val platformPlayer: Any? = null
    override var activeBackend: PlayerBackend = PlayerBackend.Auto
    override var transcodeSeekRestartsStream: Boolean = false
    override var appliesSubtitleStyle: Boolean = true
    override var playbackHealthMeasurementCapabilities: PlaybackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities.None
    override var videoOutputMeasurementCapabilities: VideoOutputMeasurementCapabilities =
        VideoOutputMeasurementCapabilities.Unsupported

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: com.jellyscope.core.domain.playback.SubtitleAsset?,
    ) {
        prepareCount += 1
        prepareFailure?.let { failure -> throw failure }
        preparedPlan = plan
        preparedSubtitleAsset = subtitleAsset
        if (publishPrepareEpoch) {
            runtimeDiagnosticsFlow.value =
                PlaybackRuntimeDiagnostics.EMPTY.copy(prepareEpoch = prepareCount.toLong())
        }
        val target = plan.subtitleActivationTarget
        val audioTarget = plan.audioActivationTarget
        val preparedState =
            playbackStateFlow.value.copy(
                status =
                    if (playbackStateFlow.value.status == PlaybackStatus.Failed) {
                        PlaybackStatus.Loading
                    } else {
                        playbackStateFlow.value.status
                    },
                audioActivation =
                    when {
                        audioTarget == null -> AudioActivationState.None
                        prepareCount == 1 && confirmInitialAudio -> AudioActivationState.Active(audioTarget)
                        else -> playbackStateFlow.value.audioActivation
                    },
                subtitleActivation =
                    if (target?.kind == LocalSubtitleKind.ExternalText && subtitleAsset != null) {
                        SubtitleActivationState.Active(target)
                    } else {
                        SubtitleActivationState.None
                    },
            )
        playbackStateFlow.value = preparePublishedState ?: preparedState
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): com.jellyscope.core.domain.playback.OfflinePrepareResult {
        offlinePrepareCount += 1
        offlinePreparePlans += plan
        preparedPlan = plan
        playbackStateFlow.value = playbackStateFlow.value.copy(status = PlaybackStatus.Loading, error = null)
        return com.jellyscope.core.domain.playback.OfflinePrepareResult.Started
    }

    override fun recordLaunchToFirstFrame(
        prepareEpoch: Long,
        durationMs: Long,
    ) {
        recordedLaunchDurations += prepareEpoch to durationMs
    }

    override fun selectEmbeddedAudio(selection: com.jellyscope.core.domain.playback.EmbeddedAudioSelection) {
        embeddedAudioOrdinals += selection.descriptor.filteredContainerOrdinal
        if (selection.target != preparedPlan?.audioActivationTarget) {
            playbackStateFlow.value =
                playbackStateFlow.value.copy(audioActivation = AudioActivationState.Active(selection.target))
        }
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        embeddedTextOrdinals += selection?.descriptor?.filteredContainerOrdinal
        playbackStateFlow.value =
            playbackStateFlow.value.copy(
                subtitleActivation =
                    selection?.target?.let(SubtitleActivationState::Active)
                        ?: SubtitleActivationState.None,
            )
    }

    override fun setPlaybackSpeed(speed: Float) {
        appliedPlaybackSpeed = speed
        playbackStateFlow.value = playbackStateFlow.value.copy(playbackSpeed = speed)
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        appliedSubtitleStyle = style
        playbackStateFlow.value = playbackStateFlow.value.copy(subtitleStyle = style)
    }

    override fun play() {
        playCount += 1
    }

    override fun pause() {
        pauseCount += 1
        playbackStateFlow.value = playbackStateFlow.value.copy(status = PlaybackStatus.Paused)
    }

    override fun seekTo(positionMs: Long) {
        seekPositions += positionMs
    }

    override fun stop() {
        stopCount += 1
    }

    override fun retry() {
        retryCount += 1
    }

    override fun release() {
        releaseCount += 1
    }
}

internal class FakeVolumePlayerController(
    confirmInitialAudio: Boolean,
) : FakePlayerController(confirmInitialAudio),
    PlayerVolumeController {
    val volumeStateFlow = MutableStateFlow(PlayerVolumeState())

    override val volumeState: StateFlow<PlayerVolumeState> = volumeStateFlow

    override fun setVolume(percent: Int) {
        volumeStateFlow.value = volumeStateFlow.value.copy(volumePercent = percent.coerceIn(0, 100))
    }

    override fun setMuted(muted: Boolean) {
        volumeStateFlow.value = volumeStateFlow.value.copy(muted = muted)
    }
}

internal class FakePlaybackProgressReporter : PlaybackProgressReporter {
    val reports = mutableListOf<Report>()

    override suspend fun reportStart(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        reports += Report.Start(positionMs, playSessionId, plan.streamMode.playMethod)
    }

    override suspend fun reportProgress(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
        isPaused: Boolean,
        eventName: PlaybackProgressEvent,
    ) {
        reports += Report.Progress(positionMs, isPaused, eventName, playSessionId, plan.streamMode.playMethod)
    }

    override suspend fun reportStopped(
        session: Session,
        plan: PlaybackPlan,
        playSessionId: String,
        positionMs: Long,
    ) {
        reports += Report.Stopped(positionMs, playSessionId, plan.streamMode.playMethod)
    }
}

internal sealed interface Report {
    data class Start(
        val positionMs: Long,
        val playSessionId: String = "play-session-1",
        val playMethod: String = "DirectPlay",
    ) : Report

    data class Progress(
        val positionMs: Long,
        val isPaused: Boolean,
        val eventName: PlaybackProgressEvent,
        val playSessionId: String = "play-session-1",
        val playMethod: String = "DirectPlay",
    ) : Report

    data class Stopped(
        val positionMs: Long,
        val playSessionId: String = "play-session-1",
        val playMethod: String = "DirectPlay",
    ) : Report
}

internal val StreamMode.playMethod: String
    get() =
        when (this) {
            StreamMode.DirectPlay -> "DirectPlay"
            StreamMode.DirectStream -> "DirectStream"
            StreamMode.Transcode -> "Transcode"
            StreamMode.Offline -> "DirectPlay"
        }

internal class FakeMediaRepository(
    private val playbackInfo: PlaybackInfo,
    private val playbackInfoResults: ArrayDeque<Result<PlaybackInfo>> = ArrayDeque(),
    private val queueItems: List<MediaItem> = emptyList(),
    private val seasons: List<MediaItem> = emptyList(),
    private val episodesBySeasonId: Map<String, List<MediaItem>> = emptyMap(),
    private val seasonsFailure: Boolean = false,
    private val episodeFailures: Set<String> = emptySet(),
    private val detailItems: Map<String, MediaItem> = emptyMap(),
    private val detailFailures: Set<String> = emptySet(),
    private val mediaSegments: List<MediaSegment> = emptyList(),
    private val chapters: List<Chapter> = emptyList(),
    private val trickplayByMediaSourceId: Map<String, TrickplayInfo?> = emptyMap(),
    private val mediaStreams: List<PlaybackMediaStream> = playbackStreams,
    private val fallbackRequestGate: CompletableDeferred<Unit>? = null,
) : MediaRepository {
    var honoursRequestPolicy: Boolean = true

    val requests = mutableListOf<PlaybackInfoRequest>()
    val itemIdRequests = mutableListOf<List<String>>()
    val seasonSeriesIds = mutableListOf<String>()
    val episodeRequests = mutableListOf<EpisodeRequest>()
    var detailCallCount = 0
    var mediaSegmentsCallCount = 0

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(
        seriesId: String?,
        includeResumable: Boolean,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> =
        if (itemId in detailFailures) {
            detailCallCount += 1
            Result.failure(IllegalStateException("Item detail unavailable."))
        } else {
            detailCallCount += 1
            val item =
                detailItems[itemId]
                    ?: MediaItem(
                        id = itemId,
                        name = "Item",
                        kind = MediaKind.Movie,
                    )
            Result.success(
                MediaItemDetail(
                    item = item,
                    versions =
                        item.versions.takeIf { versions -> versions.isNotEmpty() }
                            ?: listOf(
                                MediaVersion(
                                    id = "source-1",
                                    name = "1080p",
                                    mediaStreams = mediaStreams,
                                ),
                            ),
                    chapters = chapters,
                    trickplayByMediaSourceId = trickplayByMediaSourceId,
                ),
            )
        }

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> {
        seasonSeriesIds += seriesId
        return if (seasonsFailure) {
            Result.failure(IllegalStateException("Seasons unavailable."))
        } else {
            Result.success(seasons)
        }
    }

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> {
        episodeRequests += EpisodeRequest(seriesId, seasonId, seasonIndex)
        return if (seasonId in episodeFailures) {
            Result.failure(IllegalStateException("Episodes unavailable."))
        } else {
            Result.success(episodesBySeasonId[seasonId].orEmpty())
        }
    }

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getItemsByIds(ids: List<String>): Result<List<MediaItem>> {
        itemIdRequests += ids
        return Result.success(queueItems)
    }

    override suspend fun getMediaSegments(itemId: String): Result<List<MediaSegment>> {
        mediaSegmentsCallCount += 1
        return Result.success(mediaSegments)
    }

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
    ): Result<Unit> = Result.success(Unit)

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> =
        getPlaybackInfo(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            startTimeTicks = startTimeTicks,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            maxStreamingBitrate = maxStreamingBitrate,
            requestPolicy = PlaybackInfoRequestPolicy(),
        )

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfo> {
        requests +=
            PlaybackInfoRequest(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                startTimeTicks = startTimeTicks,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                maxStreamingBitrate = maxStreamingBitrate,
                requestPolicy = requestPolicy,
            )
        if (requestPolicy.forceEncodeSubtitle != null) {
            fallbackRequestGate?.await()
        }
        if (playbackInfoResults.isNotEmpty()) {
            return playbackInfoResults.removeFirst()
        }
        // Model a compliant server: EnableDirectPlay=false makes a real server
        // answer with a transcode rather than a DirectPlay-only source. The
        // planner now enforces the policy locally (defense against servers that
        // ignore the flags), so a fake that returns DirectPlay against a
        // DirectPlay-disabled request would fail every fallback replan with
        // NoSupportedStream — a response shape no real server produces.
        // `honoursRequestPolicy = false` keeps the non-compliant shape available
        // for the tests that exist to prove those defenses hold.
        if (honoursRequestPolicy && !requestPolicy.enableDirectPlay) {
            return Result.success(
                playbackInfo.copy(
                    mediaSources =
                        playbackInfo.mediaSources.map { source ->
                            source.copy(
                                supportsDirectPlay = false,
                                supportsTranscoding = true,
                                transcodingUrl = source.transcodingUrl ?: "/Videos/item-1/master.m3u8?VideoCodec=h264",
                            )
                        },
                ),
            )
        }
        return Result.success(playbackInfo)
    }
}

internal data class PlaybackInfoRequest(
    val itemId: String,
    val mediaSourceId: String?,
    val startTimeTicks: Long,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val maxStreamingBitrate: Long?,
    val requestPolicy: PlaybackInfoRequestPolicy,
)

internal data class EpisodeRequest(
    val seriesId: String,
    val seasonId: String,
    val seasonIndex: Int?,
)

private class FakeDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName: String = "Test"

    override fun newDeviceId(): String = "play-session-1"
}

internal fun warningsEnabledPreferencesStore(): PlaybackPreferencesStore =
    FakePlaybackPreferencesStore(PlaybackPreferences(playbackWarningsEnabled = true))

internal class FakePlaybackPreferencesStore(
    private val preferences: PlaybackPreferences,
) : PlaybackPreferencesStore {
    override suspend fun get(accountIdentity: AccountIdentity): PlaybackPreferences = preferences

    override suspend fun save(
        accountIdentity: AccountIdentity,
        preferences: PlaybackPreferences,
    ) = Unit

    override suspend fun clearAccount(accountIdentity: AccountIdentity) = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

internal fun playbackState(
    status: PlaybackStatus,
    positionMs: Long = 0L,
    error: PlaybackError? = null,
    audioUnavailable: Boolean = false,
) = PlaybackState(
    status = status,
    positionMs = positionMs,
    durationMs = 60_000L,
    bufferedPositionMs = positionMs,
    error = error,
    audioUnavailable = audioUnavailable,
)

internal fun mediaItem(
    id: String,
    name: String,
    primaryTag: String? = null,
    kind: MediaKind = MediaKind.Movie,
    seriesId: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): MediaItem =
    MediaItem(
        id = id,
        name = name,
        kind = kind,
        seriesId = seriesId,
        seasonId = seasonNumber?.let { season -> "season-$season" },
        parentIndexNumber = seasonNumber,
        indexNumber = episodeNumber,
        imageRefs = ImageRefs(primaryTag = primaryTag),
    )

internal val playbackStreams =
    listOf(
        PlaybackMediaStream(
            index = 0,
            type = "Video",
            displayTitle = "1080p",
            title = null,
            language = null,
            codec = "h264",
            channelLayout = null,
            bitRate = 17_100_000L,
            height = 1080,
            isDefault = null,
            isExternal = null,
            deliveryMethod = null,
            deliveryUrl = null,
            // Real Jellyfin streams always carry both dimensions; without the
            // width the preflight treats the source as unprovable and replans
            // fail closed, which is not what these tests exercise.
            width = 1920,
        ),
        PlaybackMediaStream(
            index = 1,
            type = "Audio",
            displayTitle = "English",
            title = null,
            language = "eng",
            codec = "aac",
            channelLayout = "stereo",
            bitRate = 192_000L,
            height = null,
            isDefault = true,
            isExternal = null,
            deliveryMethod = null,
            deliveryUrl = null,
        ),
        PlaybackMediaStream(
            index = 3,
            type = "Audio",
            displayTitle = "Spanish",
            title = null,
            language = "spa",
            codec = "aac",
            channelLayout = "stereo",
            bitRate = 192_000L,
            height = null,
            isDefault = false,
            isExternal = null,
            deliveryMethod = null,
            deliveryUrl = null,
        ),
        PlaybackMediaStream(
            index = 4,
            type = "Subtitle",
            displayTitle = "English PGS",
            title = null,
            language = "eng",
            codec = "pgssub",
            channelLayout = null,
            bitRate = null,
            height = null,
            isDefault = null,
            isExternal = false,
            deliveryMethod = null,
            deliveryUrl = null,
        ),
    )

internal fun explicitlyExcludedVideoStreams(): List<PlaybackMediaStream> =
    playbackStreams.map { stream ->
        if (stream.type.equals("Video", ignoreCase = true)) {
            stream.copy(
                codec = "H265",
                videoRangeType = "dovi-with-hdr10-plus",
            )
        } else {
            stream
        }
    }

internal fun explicitlyExcludedVideoProfileProvider(): DeviceProfileProvider =
    object : DeviceProfileProvider {
        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("hevc"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = true,
                unsupportedVideoRangeTypesByCodec =
                    mapOf(
                        "hevc" to
                            setOf(
                                "DOVIWithHDR10Plus",
                                "DOVIWithELHDR10Plus",
                            ),
                    ),
            )
    }

internal val directPlayPlaybackInfo =
    playbackInfoWithStreams(playbackStreams)

internal fun playbackInfoWithStreams(streams: List<PlaybackMediaStream>): PlaybackInfo =
    PlaybackInfo(
        playSessionId = null,
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = true,
                    supportsDirectStream = false,
                    supportsTranscoding = false,
                    transcodingUrl = null,
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = streams.withResponseSubtitleDelivery("Embed"),
                ),
            ),
    )

internal fun defaultSubtitlePlaybackStreams(): List<PlaybackMediaStream> =
    playbackStreams.map { stream ->
        if (stream.index == 4) {
            stream.copy(
                displayTitle = "English SRT",
                codec = "srt",
                isDefault = true,
            )
        } else {
            stream
        }
    }

internal fun externalSubtitlePlaybackStreams(): List<PlaybackMediaStream> =
    playbackStreams.map { stream ->
        if (stream.index == 4) {
            stream.copy(
                displayTitle = "English External",
                codec = "srt",
                isExternal = true,
                deliveryMethod = "External",
                deliveryUrl = "/Videos/item-1/subtitles/4.srt",
            )
        } else {
            stream
        }
    }

internal fun englishTextAndFrenchPgsPlaybackStreams(): List<PlaybackMediaStream> =
    playbackStreams
        .map { stream ->
            if (stream.index == 4) {
                stream.copy(
                    displayTitle = "English SRT",
                    language = "eng",
                    codec = "srt",
                    isDefault = true,
                )
            } else {
                stream
            }
        }.plus(
            playbackStreams
                .first { stream -> stream.index == 4 }
                .copy(
                    index = 5,
                    displayTitle = "French PGS",
                    language = "fra",
                    codec = "pgssub",
                    isDefault = false,
                ),
        )

internal fun englishAndFrenchAssPlaybackStreams(): List<PlaybackMediaStream> =
    englishTextAndFrenchPgsPlaybackStreams().map { stream ->
        when (stream.index) {
            4 -> stream.copy(displayTitle = "English ASS", codec = "ass")
            5 -> stream.copy(displayTitle = "French ASS", codec = "ass")
            else -> stream
        }
    }

internal val transcodePlaybackInfo =
    PlaybackInfo(
        playSessionId = "server-play-session-1",
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    supportsTranscoding = true,
                    transcodingUrl = "/Videos/item-1/master.m3u8",
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = playbackStreams.withResponseSubtitleDelivery("Encode"),
                ),
            ),
    )

internal val subtitleTranscodePlaybackInfo =
    PlaybackInfo(
        playSessionId = "server-subtitle-session-1",
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    supportsTranscoding = true,
                    transcodingUrl = "/Videos/item-1/master.m3u8",
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = playbackStreams.withResponseSubtitleDelivery("Encode"),
                    transcodeReasons = listOf("SubtitleCodecNotSupported"),
                ),
            ),
    )

internal fun subtitleTranscodePlaybackInfoWithStreams(streams: List<PlaybackMediaStream>): PlaybackInfo =
    subtitleTranscodePlaybackInfo.copy(
        mediaSources =
            subtitleTranscodePlaybackInfo.mediaSources.map { source ->
                source.copy(mediaStreams = streams.withResponseSubtitleDelivery("Encode"))
            },
    )

internal fun transcodePlaybackInfoWithStreams(streams: List<PlaybackMediaStream>): PlaybackInfo =
    transcodePlaybackInfo.copy(
        mediaSources =
            transcodePlaybackInfo.mediaSources.map { source ->
                source.copy(mediaStreams = streams.withResponseSubtitleDelivery("Encode"))
            },
    )

private fun List<PlaybackMediaStream>.withResponseSubtitleDelivery(method: String): List<PlaybackMediaStream> =
    map { stream ->
        if (stream.type.equals("Subtitle", ignoreCase = true) && stream.deliveryMethod == null) {
            stream.copy(deliveryMethod = method)
        } else {
            stream
        }
    }

internal val noSupportedPlaybackInfo =
    PlaybackInfo(
        playSessionId = null,
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = "source-1",
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    supportsTranscoding = false,
                    transcodingUrl = null,
                    container = "mkv",
                    bitrate = 17_100_000L,
                    mediaStreams = playbackStreams,
                ),
            ),
    )

internal val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        userId = "user-1",
        userName = "Demo User",
        accessToken = "token-1",
        deviceId = "device-1",
    )
