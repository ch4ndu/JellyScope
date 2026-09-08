// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.SavePlaybackSelectionAction
import com.jellyscope.core.domain.action.SavePlaybackTimingOffsetAction
import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.AudioActivationTarget
import com.jellyscope.core.domain.playback.AutoPlaybackQualityRecoveryAuthorization
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryCoordinator
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryInput
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryPromptReason
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryState
import com.jellyscope.core.domain.playback.AutoPlaybackRecoveryTrigger
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.DEFAULT_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.MAX_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.MIN_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.PlannedSubtitle
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackActionNotice
import com.jellyscope.core.domain.playback.PlaybackActionNoticeReason
import com.jellyscope.core.domain.playback.PlaybackBackendAvailability
import com.jellyscope.core.domain.playback.PlaybackBackendFallbackResult
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackContentTimeline
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackFirstVideoOutputState
import com.jellyscope.core.domain.playback.PlaybackHealthExclusionReason
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.domain.playback.PlaybackHealthSessionContext
import com.jellyscope.core.domain.playback.PlaybackHealthSessionCoordinator
import com.jellyscope.core.domain.playback.PlaybackHealthSignal
import com.jellyscope.core.domain.playback.PlaybackHealthSignalKind
import com.jellyscope.core.domain.playback.PlaybackHealthThresholdClass
import com.jellyscope.core.domain.playback.PlaybackInfoPlanner
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackLaunchReadOutcome
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPersistenceResult
import com.jellyscope.core.domain.playback.PlaybackPersistenceTarget
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackPlanningException
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackSessionRecoveryDecision
import com.jellyscope.core.domain.playback.PlaybackSessionRecoveryInput
import com.jellyscope.core.domain.playback.PlaybackSessionRecoveryPolicy
import com.jellyscope.core.domain.playback.PlaybackSessionRecoveryState
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlaybackTerminalOutcome
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerBackendPlatform
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerStillWatchingAutomaticAdvance
import com.jellyscope.core.domain.playback.PlayerStillWatchingState
import com.jellyscope.core.domain.playback.PlayerVolumeController
import com.jellyscope.core.domain.playback.PlayerVolumeState
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.SubtitleRenderInfo
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.domain.playback.audioOptions
import com.jellyscope.core.domain.playback.currentSegment
import com.jellyscope.core.domain.playback.defaultSubtitleStreamIndex
import com.jellyscope.core.domain.playback.millisecondsToTicks
import com.jellyscope.core.domain.playback.offlineControllerFallbackAllowed
import com.jellyscope.core.domain.playback.playerQualityOptions
import com.jellyscope.core.domain.playback.preferredAudioStreamIndex
import com.jellyscope.core.domain.playback.preferredSubtitleStreamIndex
import com.jellyscope.core.domain.playback.qualityOptions
import com.jellyscope.core.domain.playback.resolveOfflinePlaybackBackend
import com.jellyscope.core.domain.playback.resolvePlayerBackend
import com.jellyscope.core.domain.playback.strongestPendingRecoveryTrigger
import com.jellyscope.core.domain.playback.subtitleKind
import com.jellyscope.core.domain.playback.subtitleOptions
import com.jellyscope.core.domain.playback.thresholdClass
import com.jellyscope.core.domain.playback.toBitrateConstraint
import com.jellyscope.core.domain.usecase.GetChronologicalEpisodeQueueUseCase
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetItemsByIdsUseCase
import com.jellyscope.core.domain.usecase.GetLocalSubtitleAssetUseCase
import com.jellyscope.core.domain.usecase.GetMediaSegmentsUseCase
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackTimingOffsetUseCase
import com.jellyscope.core.domain.usecase.GetPlayerBackendOverrideUseCase
import com.jellyscope.core.domain.usecase.ObserveLocalSubtitleAssetsUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackReportingCoordinator
import com.jellyscope.core.playback.PlaybackReportingQueue
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import com.jellyscope.core.playback.resolvePlaybackDurationMs
import com.jellyscope.core.playback.toDiagnosticsSourceDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

class PlayerViewModel(
    private val session: Session,
    private val itemId: String,
    private val startPositionTicks: Long,
    private val mediaSourceId: String?,
    private val initialAudioStreamIndex: Int? = null,
    private val initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
    queue: List<String> = emptyList(),
    playerController: PlayerController,
    private val initialControllerIsPending: Boolean = false,
    private val playbackInfoPlanner: PlaybackInfoPlanner,
    private val progressReporter: PlaybackProgressReporter,
    private val playbackStopSettlementRegistry: PlaybackStopSettlementRegistry,
    private val getItemDetailUseCase: GetItemDetailUseCase,
    private val getMediaSegmentsUseCase: GetMediaSegmentsUseCase,
    private val getItemsByIdsUseCase: GetItemsByIdsUseCase,
    private val getChronologicalEpisodeQueueUseCase: GetChronologicalEpisodeQueueUseCase? = null,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val playbackSelectionMemory: PlaybackSelectionMemory,
    private val getPlaybackLaunchContextUseCase: GetPlaybackLaunchContextUseCase,
    private val getLocalSubtitleAssetUseCase: GetLocalSubtitleAssetUseCase? = null,
    private val observeLocalSubtitleAssetsUseCase: ObserveLocalSubtitleAssetsUseCase? = null,
    private val saveSubtitleSelectionAction: SaveSubtitleSelectionAction? = null,
    private val savePlaybackSelectionAction: SavePlaybackSelectionAction? = null,
    private val getPlaybackTimingOffsetUseCase: GetPlaybackTimingOffsetUseCase? = null,
    private val savePlaybackTimingOffsetAction: SavePlaybackTimingOffsetAction? = null,
    private val observePlayerDeviceSettingsUseCase: ObservePlayerDeviceSettingsUseCase,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    backend: PlayerBackend = PlayerBackend.AVPlayer,
    private val playerControllerFactory: (PlayerBackend) -> PlayerController = { playerController },
    private val playerControllerFactoryWithOptions: ((PlayerBackend, Boolean) -> PlayerController)? = null,
    private val deviceProfileProvider: DeviceProfileProvider? = null,
    private val playbackDiagnosticsContext: PlaybackDiagnosticsContext? = null,
    private val getPlayerBackendOverrideUseCase: GetPlayerBackendOverrideUseCase? = null,
    getPlaybackInfoAtStartStateUseCase: GetPlaybackInfoAtStartStateUseCase? = null,
    private val monotonicTimeMs: () -> Long = ::playerMonotonicTimeMs,
    private val playbackHealthGuidancePolicy: PlaybackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
    private val offlineDownloadId: DownloadId? = null,
    private val getOfflinePlaybackPlanUseCase: GetOfflinePlaybackPlanUseCase? = null,
) : ViewModel() {
    /** Diagnostics preference captured once per playback start. */
    val startWithPlaybackInfoOverlay: Boolean =
        getPlaybackInfoAtStartStateUseCase?.invoke()?.value ?: false
    private var playerController: PlayerController by mutableStateOf(playerController)
    private var backend =
        playerController.activeBackend
            .takeUnless { candidate -> candidate == PlayerBackend.Auto }
            ?: backend
    private var playerControllerFieldOwned = true
    private var concreteControllerInstalled = !initialControllerIsPending
    private var disposed = false
    val currentPlayerController: PlayerController
        get() = playerController
    private val accountIdentity = session.accountIdentity()
    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _playbackState = MutableStateFlow(playerController.playbackState.value)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    private val _runtimeDiagnostics = MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = _runtimeDiagnostics.asStateFlow()
    private val _matchDisplayRefreshRate =
        MutableStateFlow(observePlayerDeviceSettingsUseCase().value.matchDisplayRefreshRate)
    val matchDisplayRefreshRate: StateFlow<Boolean> = _matchDisplayRefreshRate.asStateFlow()
    private val playbackEndedEvents = Channel<Unit>(Channel.BUFFERED)
    val playbackEnded: Flow<Unit> = playbackEndedEvents.receiveAsFlow()

    private var queueIds = normalizedQueue(initialItemId = itemId, queue = queue)
    private val queueIdentity: String
        get() = queueIds.joinToString(separator = "\u001f")
    private var currentItemId = itemId
    private var currentStartPositionTicks = startPositionTicks
    private var currentQueueIndex = queueIds.indexOf(itemId).takeIf { index -> index >= 0 } ?: 0
    private var playSessionId = deviceInfoProvider.newDeviceId()
    private var plan: PlaybackPlan? = null
    private var installedPlan: PlaybackPlan? = null
    private var planReportingAuthority = 0L
    private var selectedMediaSourceId: String? = null
    private var selectedSourceContainer: String? = null
    private var mediaStreams: List<PlaybackMediaStream> = emptyList()

    private val projections = PlayerProjectionCache()
    private val queueProjection =
        PlayerQueueProjection(
            getItemDetailUseCase = getItemDetailUseCase,
            getItemsByIdsUseCase = getItemsByIdsUseCase,
            imageUrlBuilder = imageUrlBuilder,
            serverUrl = session.serverUrl,
            workDispatcher = workDispatcher,
        )
    private var mediaSegments: List<MediaSegment> = emptyList()

    // Settings changes apply on the next playback start.
    private var activePlaybackPreferences = PlaybackPreferences()
    private val timingCoordinator =
        PlayerTimingCoordinator(
            controllerProvider = { playerController },
            saveAction = savePlaybackTimingOffsetAction,
            getUseCase = getPlaybackTimingOffsetUseCase,
            scope = viewModelScope,
            workDispatcher = workDispatcher,
            keyProvider = ::timingKey,
            onTimingStateChanged = {
                if (_state.value is PlayerUiState.Content) {
                    publishContent()
                }
            },
        )
    private var autoplayGeneration = 0L
    private val autoSkippedSegmentKeys = mutableSetOf<String>()

    // Prevents controller emissions from republishing the outgoing item.
    private var queueSwitchInFlight = false
    private var chapters: List<Chapter> = emptyList()
    private var trickplay: TrickplayInfo? = null
    private var trickplayByMediaSourceId: Map<String, TrickplayInfo?> = emptyMap()
    private var playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED
    private var subtitleStyle: SubtitleStyle = SubtitleStyle()
    private var resizeMode: PlayerResizeMode = PlayerResizeMode.Fit
    private var metadata = PlayerMediaMetadata()
    private var playlist: PlaylistUi? = null
    private var requestedAudioStreamIndex: Int? = null
    private var installedAudioStreamIndex: Int? = null
    private var audioActivationRequestId = 0L
    private var audioRecoveryTarget: AudioActivationTarget? = null
    private var desiredPlayWhenReady = true
    private var requestedSubtitleStreamIndex: Int? = null
    private var requestedSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified
    private var requestedLocalSubtitleAsset: LocalSubtitleAsset? = null
    private var localSubtitleAssets: List<LocalSubtitleAsset> = emptyList()
    private var subtitleActivationRequestId = 0L
    private var subtitleFallbackTarget: SubtitleActivationTarget? = null
    private var subtitleFallbackGeneration = 0L
    private var subtitleNoticeToken = 0L
    private var subtitleNotice: PlayerNotice? = null
    private var backendNoticeToken = 0L
    private var backendNotice: PlayerBackendNotice? = null
    private var backendSwitchNoticeToken = 0L
    private var backendSwitchNotice: PlayerBackendSwitchNotice? = null
    private var availableBackendsForSession: Set<PlayerBackend> = emptySet()
    private var backendSwitchGeneration = 0L
    private var backendSwitchInProgress = false
    private var backendSwitchJob: Job? = null
    private var qualitySession = PlayerQualitySessionState()
    private var playbackLaunchGeneration = 0L
    private var playbackLaunchMarker: PlaybackLaunchMarker? = null
    private var launchToFirstFrameMs: Long? = null
    private var activePlaybackTimelineFacts: ActivePlaybackTimelineFacts? = null

    // Explicit audio is durable intent; requested and installed indices are runtime truth.
    private var explicitAudioStreamIndex: Int? = null
    private var stillWatchingState = PlayerStillWatchingState()
    private var reportingJob: Job? = null
    private var runtimeDiagnosticsJob: Job? = null
    private var droppedFrameMeasurementsJob: Job? = null
    private var videoOutputObservationsJob: Job? = null
    private var playbackTransitionObservationsJob: Job? = null
    private var volumeStateJob: Job? = null
    private var playlistMetadataJob: Job? = null
    private var derivedEpisodeQueueJob: Job? = null
    private var queueSwitchJob: Job? = null
    private var replanJob: Job? = null
    private var localSubtitleAssetsJob: Job? = null
    private var lastStatus: PlaybackStatus = PlaybackStatus.Idle
    private var alternateBackendFallbackAttempted = false
    private var controllerInstallInFlight = false
    private val controllerInstallMutex = Mutex()
    private var stopRequestedDuringControllerInstall = false
    private var volumeControl: PlayerVolumeState? = (playerController as? PlayerVolumeController)?.volumeState?.value

    private var playbackGuidance: PlaybackHealthGuidance? = null
    private var lastHealthSignal: PlaybackHealthSignalKind? = null
    private var lastHealthThresholdClass: PlaybackHealthThresholdClass? = null
    private val playerDiagnosticsRecorder = PlayerDiagnosticsRecorder()
    private var pendingRecoveredPlaybackGuidanceItemId: String? = null
    private var pendingRecoveredAutoQualityBps: Long? = null
    private var playbackActionNotice: PlaybackActionNotice? = null

    private fun setPlaybackActionNotice(notice: PlaybackActionNotice?) {
        playbackActionNotice = notice?.takeIf { activePlaybackPreferences.playbackWarningsEnabled }
    }

    private var pictureInPictureMode = false
    private var pendingPictureInPictureRecoveryTrigger: AutoPlaybackRecoveryTrigger? = null
    private val autoRecoveryCoordinator = AutoPlaybackRecoveryCoordinator()
    private var autoRecoveryState = AutoPlaybackRecoveryState()
    private val playbackSessionRecoveryPolicy = PlaybackSessionRecoveryPolicy()
    private var playbackSessionRecoveryState = PlaybackSessionRecoveryState()

    private var firstVideoOutputState = PlayerFirstVideoOutputState()
    private val playbackHealthCoordinator =
        PlaybackHealthSessionCoordinator(
            scope = viewModelScope,
            monotonicTimeMs = monotonicTimeMs,
            measurementCapabilities = {
                val capabilities = playerController.playbackHealthMeasurementCapabilities
                capabilities.copy(
                    hasReliableFirstVideoOutput =
                        capabilities.hasReliableFirstVideoOutput &&
                            playerController.videoOutputMeasurementCapabilities.isSupported &&
                            firstVideoOutputState.debug.state != PlaybackFirstVideoOutputState.Unsupported,
                )
            },
            onSignal = { signal ->
                lastHealthSignal = signal.kind
                lastHealthThresholdClass = signal.thresholdClass()
                if (
                    signal.kind == PlaybackHealthSignalKind.NoVideoOutput &&
                    firstVideoOutputState.debug.state != PlaybackFirstVideoOutputState.Unsupported
                ) {
                    firstVideoOutputState = firstVideoOutputState.markTimedOut()
                    if (_state.value is PlayerUiState.Content) {
                        publishContent(playbackState = playbackState.value)
                    }
                }
                playerDiagnosticsRecorder.recordPlaybackHealthSignal(
                    context = playerDiagnosticContext(),
                    signal = signal,
                )
                // Warning settings hide presentation, never automatic recovery.
                if (playbackHealthGuidancePolicy == PlaybackHealthGuidancePolicy.Actionable) {
                    handleAutomaticRecoverySignal(signal)
                }
            },
            onGuidanceChanged = { guidance ->
                playbackGuidance = guidance
                if (_state.value is PlayerUiState.Content) {
                    publishContent()
                }
            },
            onSessionEnded = { summary ->
                if (plan != null) {
                    playerDiagnosticsRecorder.recordPlaybackHealthSummary(
                        context = playerDiagnosticContext(),
                        facts =
                            PlayerPlaybackHealthSummaryDiagnosticFacts(
                                summary = summary,
                                firstVideoOutputEvidence = firstVideoOutputState.debug.evidence,
                                launchToFirstFrameMs = launchToFirstFrameMs,
                            ),
                    )
                }
            },
        )
    private var backendResolvedForSession = false
    private val playbackReportingQueue =
        PlaybackReportingQueue(
            reporter = progressReporter,
            dispatcher = workDispatcher,
            settlementRegistry = playbackStopSettlementRegistry,
        )
    private val playbackReportingCoordinator =
        PlaybackReportingCoordinator(
            queue = playbackReportingQueue,
            scope = viewModelScope,
            playbackState = _playbackState,
        )

    init {
        observePlayerDeviceSettings()
        observeRuntimeDiagnostics()
        observeVolumeState()
        observeTimingState()
        load()
    }

    private fun observePlayerDeviceSettings() {
        viewModelScope.launch {
            observePlayerDeviceSettingsUseCase().collect { settings ->
                _matchDisplayRefreshRate.value = settings.matchDisplayRefreshRate
            }
        }
    }

    private fun observeTimingState() {
        timingCoordinator.observe()
    }

    fun play() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        desiredPlayWhenReady = true
        restartPlaybackHealthEvidence()
        playerController.play()
    }

    fun pause() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        desiredPlayWhenReady = false
        restartPlaybackHealthEvidence()
        playerController.pause()
    }

    fun togglePlayPause() {
        if (playbackState.value.status == PlaybackStatus.Playing) {
            pause()
        } else {
            play()
        }
    }

    fun playPrevious() {
        if (controllerInstallInFlight) return
        val playback = playbackState.value
        if (playback.positionMs >= PREVIOUS_ITEM_RESTART_THRESHOLD_MS) {
            seekTo(0L)
            return
        }
        val previousIndex =
            (currentQueueIndex - 1)
                .takeIf { index -> queueIds.size > 1 && index in queueIds.indices }
                ?: return
        stillWatchingState = stillWatchingState.resetForManualNavigation()
        invalidateSubtitleFallback()
        startQueueSwitch(
            index = previousIndex,
            stopPositionMs = playback.positionMs,
        )
    }

    fun setVolume(percent: Int) {
        if (controllerInstallInFlight) return
        (playerController as? PlayerVolumeController)?.setVolume(percent)
    }

    fun toggleMute() {
        if (controllerInstallInFlight) return
        val volumeController = playerController as? PlayerVolumeController ?: return
        volumeController.setMuted(!volumeController.volumeState.value.muted)
    }

    fun seekTo(positionMs: Long) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        val target = positionMs.coerceAtLeast(0L)
        restartPlaybackHealthEvidence(PlaybackHealthExclusionReason.Seek)
        val currentPlan = plan
        if (
            playerController.transcodeSeekRestartsStream &&
            currentPlan?.streamMode == StreamMode.Transcode &&
            !isWithinTranscodedWindow(target, currentPlan)
        ) {
            // Restart when the target lies outside the server-produced HLS window.
            replanAtPosition(target)
            return
        }
        playerController.seekTo(target)
        playbackReportingCoordinator.progress(
            positionMs = target,
            isPaused = playerController.playbackState.value.status == PlaybackStatus.Paused,
            eventName = PlaybackProgressEvent.TimeUpdate,
        )
    }

    // Only the produced transcode window supports an in-stream seek.
    private fun isWithinTranscodedWindow(
        targetPositionMs: Long,
        currentPlan: PlaybackPlan,
    ): Boolean {
        val bufferedAheadMs = playerController.playbackState.value.bufferedPositionMs
        return targetPositionMs in currentPlan.startPositionMs..bufferedAheadMs
    }

    fun retry() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        autoplayGeneration += 1
        _state.update { PlayerUiState.Loading }
        playSessionId = deviceInfoProvider.newDeviceId()
        lastStatus = PlaybackStatus.Idle
        replanJob?.cancel()
        replanJob = null
        val sameItemFastRetry = plan?.itemId == currentItemId
        emitPlaybackHealthSummary()
        val retryGeneration = ++playbackLaunchGeneration
        activePlaybackTimelineFacts =
            if (sameItemFastRetry) {
                activePlaybackTimelineFacts
                    ?.takeIf { facts -> facts.itemId == currentItemId }
                    ?.copy(generation = retryGeneration)
            } else {
                null
            }
        // Retry resets all session health and recovery budgets.
        resetPlaybackHealthSession(retryGeneration)
        plan = plan?.copy(diagnosticSessionSequence = retryGeneration)
        pendingRecoveredPlaybackGuidanceItemId = null
        pendingRecoveredAutoQualityBps = null
        pendingPictureInPictureRecoveryTrigger = null
        playbackActionNotice = null
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(playbackLaunchGeneration, currentItemId)
        if (offlineDownloadId != null || plan?.streamMode == StreamMode.Offline) {
            if (offlineDownloadId == null) {
                failOfflineLaunch(retryGeneration, PlaybackError.OfflineArtifactUnavailable)
                return
            }
            viewModelScope.launch {
                if (
                    startPlaybackForItem(
                        itemId = currentItemId,
                        requestedMediaSourceId = selectedMediaSourceId,
                        requestedAudioStreamIndex = requestedAudioStreamIndex,
                        requestedSubtitleSelection = requestedSubtitleSelection,
                        startPositionTicks = currentStartPositionTicks,
                        resetReporting = false,
                    )
                ) {
                    observePlaybackState()
                }
            }
            return
        }
        if (sameItemFastRetry) {
            plan?.let { currentPlan ->
                playbackReportingCoordinator.install(
                    session = session,
                    plan = currentPlan,
                    playSessionId = currentPlan.effectivePlaySessionId(),
                )
            }
            playbackLaunchMarker = null
            playerController.retry()
            playerController.runtimeDiagnostics.value.prepareEpoch
                ?.let(playbackHealthCoordinator::expectVideoOutput)
            armFirstVideoOutputState()
            return
        }

        viewModelScope.launch {
            if (
                startPlaybackForItem(
                    itemId = currentItemId,
                    requestedMediaSourceId =
                        selectedMediaSourceId
                            ?: mediaSourceId.takeIf { currentItemId == this@PlayerViewModel.itemId },
                    requestedAudioStreamIndex = requestedAudioStreamIndex,
                    requestedSubtitleSelection = requestedSubtitleSelection,
                    startPositionTicks = currentStartPositionTicks,
                    resetReporting = false,
                )
            ) {
                observePlaybackState()
                fetchPlaylistMetadata()
            }
        }
    }

    fun playQueueItem(index: Int) {
        if (controllerInstallInFlight) return
        if (queueIds.size <= 1 || index !in queueIds.indices) {
            return
        }
        stillWatchingState = stillWatchingState.resetForManualNavigation()
        // A newer queue switch cancels the in-flight plan and prepare.
        invalidateSubtitleFallback()
        startQueueSwitch(
            index = index,
            stopPositionMs = playerController.playbackState.value.positionMs,
        )
    }

    /** False keeps Up Next visible when the switch is refused. */
    fun playNext(
        auto: Boolean = false,
        expectedGeneration: Long? = null,
    ): Boolean {
        if (controllerInstallInFlight) return false
        return playNext(
            auto = auto,
            stopPositionMs = playerController.playbackState.value.positionMs,
            expectedGeneration = expectedGeneration,
        )
    }

    fun confirmStillWatching() {
        if (controllerInstallInFlight) return
        val pendingIndex = stillWatchingState.pendingQueueIndex
        stillWatchingState = stillWatchingState.confirm()
        if (state.value is PlayerUiState.Content) {
            publishContent()
        }

        if (pendingIndex != null) {
            invalidateSubtitleFallback()
            startQueueSwitch(
                index = pendingIndex,
                stopPositionMs = playerController.playbackState.value.positionMs,
            )
        }
    }

    // Auto-skip fires once per segment identity in each item session.
    private fun maybeAutoSkipSegment(enrichedPlaybackState: PlaybackState) {
        if (enrichedPlaybackState.status != PlaybackStatus.Playing) return
        val segment = enrichedPlaybackState.currentSegment ?: return
        if (activePlaybackPreferences.policyFor(segment.type) != SegmentSkipPolicy.AutoSkip) return
        val key = "${segment.type.name}:${segment.startTicks}:${segment.endTicks}"
        if (!autoSkippedSegmentKeys.add(key)) return
        seekTo(segment.endMs)
    }

    fun skipCurrentSegment() {
        if (controllerInstallInFlight) return
        val segment = playbackState.value.currentSegment ?: return
        seekTo(segment.endMs)
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        resizeMode = mode
        if (state.value is PlayerUiState.Content) {
            publishContent()
        }
    }

    fun cycleResizeMode(twoState: Boolean = false) {
        val nextMode =
            if (twoState) {
                when (resizeMode) {
                    PlayerResizeMode.Fit -> PlayerResizeMode.Fill
                    PlayerResizeMode.Fill,
                    PlayerResizeMode.Zoom,
                    -> PlayerResizeMode.Fit
                }
            } else {
                when (resizeMode) {
                    PlayerResizeMode.Fit -> PlayerResizeMode.Fill
                    PlayerResizeMode.Fill -> PlayerResizeMode.Zoom
                    PlayerResizeMode.Zoom -> PlayerResizeMode.Fit
                }
            }
        setResizeMode(nextMode)
    }

    fun shuffleQueue() {
        if (queueIds.size <= 1) {
            return
        }
        invalidateBackendSwitch()
        val currentId = currentItemId
        val existingItemsById = playlist?.items.orEmpty().associateBy { item -> item.id }
        queueIds = listOf(currentId) + queueIds.filterNot { id -> id == currentId }.shuffled()
        currentQueueIndex = 0
        playlist =
            playlist?.copy(
                items =
                    queueProjection.queueItems(queueIds, existingItemsById),
                currentIndex = currentQueueIndex,
            )
        publishContent()
        if (queueIds.any { id -> id !in existingItemsById }) {
            fetchPlaylistMetadata()
        }
    }

    fun stop() {
        invalidateBackendSwitch()
        if (controllerInstallInFlight || controllerInstallMutex.isLocked) {
            stopRequestedDuringControllerInstall = true
            return
        }
        performStop(stopController = true)
    }

    private fun performStop(stopController: Boolean) {
        cancelAndInvalidateSubtitleFallback()
        activePlaybackTimelineFacts = null
        emitPlaybackHealthSummary()
        endPlaybackHealthSession()
        pendingRecoveredPlaybackGuidanceItemId = null
        if (_state.value is PlayerUiState.Content) {
            publishContent()
        }
        playbackReportingCoordinator.stop(playerController.playbackState.value.positionMs)
        if (stopController) playerController.stop()
    }

    fun showPicker(picker: PlayerPicker) {
        if (picker == PlayerPicker.Backend && !canOpenBackendPicker()) {
            return
        }
        publishContent(pickerVisible = picker)
    }

    fun hidePicker() {
        publishContent(pickerVisible = PlayerPicker.None)
    }

    /** Starts a session-only remote backend replacement without touching preferences. */
    fun selectBackend(targetBackend: PlayerBackend) {
        val policy = deviceProfileProvider?.backendPolicy ?: return
        val currentPlan = installedPlan ?: return
        if (
            disposed ||
            backendSwitchInProgress ||
            controllerInstallInFlight ||
            queueSwitchInFlight ||
            currentPlan.streamMode == StreamMode.Offline ||
            playbackState.value.status in backendSwitchTerminalStatuses ||
            targetBackend == PlayerBackend.Auto ||
            targetBackend == backend ||
            targetBackend !in policy.concreteBackends ||
            targetBackend !in availableBackendsForSession
        ) {
            return
        }

        replanJob?.cancel()
        replanJob = null

        val switch =
            BackendSwitchSnapshot(
                generation = ++backendSwitchGeneration,
                launchGeneration = playbackLaunchGeneration,
                itemId = currentItemId,
                mediaSourceId = currentPlan.mediaSourceId,
                activePlan = currentPlan,
                explicitAudioStreamIndex = explicitAudioStreamIndex,
                requestedAudioStreamIndex = requestedAudioStreamIndex,
                requestedSubtitleSelection = requestedSubtitleSelection,
                requestedLocalSubtitleAsset = requestedLocalSubtitleAsset,
                qualityPolicy =
                    if (qualitySession.isExplicitSessionChoice) {
                        qualitySession.policy
                    } else {
                        activePlaybackPreferences.effectiveDefaultQualityPolicy(targetBackend)
                    },
                qualityExplicit = qualitySession.isExplicitSessionChoice,
                wasPaused = playbackState.value.status == PlaybackStatus.Paused || !desiredPlayWhenReady,
                playbackSpeed = playbackSpeed,
                subtitleStyle = subtitleStyle,
                resizeMode = resizeMode,
                queueIdentity = queueIdentity,
                targetBackend = targetBackend,
                defaultBackend = policy.concreteDefaultBackend,
                planReportingAuthority = planReportingAuthority,
            )
        backendSwitchInProgress = true
        backendSwitchNotice = null
        publishContent(pickerVisible = PlayerPicker.Backend)
        backendSwitchJob =
            viewModelScope.launch {
                switchBackend(switch)
            }
    }

    fun selectAudio(streamIndex: Int) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        val option =
            audioOptions(mediaStreams)
                .firstOrNull { track -> track.streamIndex == streamIndex }
                ?: return
        val currentPlan = plan ?: return
        if (currentPlan.streamMode == StreamMode.Offline) {
            selectOfflineAudio(currentPlan, streamIndex)
            return
        }
        cancelAndInvalidateSubtitleFallback()
        audioRecoveryTarget = null
        requestedAudioStreamIndex = streamIndex
        explicitAudioStreamIndex = streamIndex
        rememberSelection()

        if (currentPlan.streamMode == StreamMode.DirectPlay) {
            val descriptor =
                currentPlan.embeddedAudioTracks.firstOrNull { track -> track.jellyfinStreamIndex == streamIndex }
            val target = newAudioActivationTarget(streamIndex)
            plan =
                currentPlan.copy(
                    selectedAudioStreamIndex = streamIndex,
                    audioActivationTarget = target,
                )
            // Native index selection does not prove the codec can decode.
            if (descriptor == null || !descriptor.directPlayAdmissible) {
                maybeHandleImmediatePlaybackSessionRecovery(
                    playerController.playbackState.value.copy(
                        audioActivation = AudioActivationState.Unavailable(target),
                    ),
                )
            } else {
                playerController.selectEmbeddedAudio(
                    EmbeddedAudioSelection(target = target, descriptor = descriptor),
                )
            }
            reloadTimingForCurrentTracks()
            publishContent(pickerVisible = PlayerPicker.None)
        } else {
            // Dismiss before the PlaybackInfo round trip and native swap.
            publishContent(pickerVisible = PlayerPicker.None)
            replanAtCurrentPosition()
        }
    }

    /** Offline audio is limited to tracks captured in the artifact snapshot. */
    private fun selectOfflineAudio(
        currentPlan: PlaybackPlan,
        streamIndex: Int,
    ) {
        val descriptor =
            currentPlan.embeddedAudioTracks.firstOrNull { track ->
                track.jellyfinStreamIndex == streamIndex
            }
        if (descriptor == null || !descriptor.directPlayAdmissible) {
            _state.update { PlayerUiState.Error(error = PlaybackError.UnsupportedMedia) }
            return
        }
        cancelAndInvalidateSubtitleFallback()
        audioRecoveryTarget = null
        requestedAudioStreamIndex = streamIndex
        explicitAudioStreamIndex = streamIndex
        rememberSelection()
        val target = newAudioActivationTarget(streamIndex)
        plan =
            currentPlan.copy(
                selectedAudioStreamIndex = streamIndex,
                audioActivationTarget = target,
            )
        playerController.selectEmbeddedAudio(
            EmbeddedAudioSelection(target = target, descriptor = descriptor),
        )
        reloadTimingForCurrentTracks()
        publishContent(pickerVisible = PlayerPicker.None)
    }

    /** Offline subtitles may disable a sidecar or switch captured embedded tracks. */
    private fun selectOfflineSubtitle(
        currentPlan: PlaybackPlan,
        option: SubtitleTrackOption?,
    ) {
        if (option?.isExternal == true) return
        invalidateSubtitleFallback()
        replanJob?.cancel()
        replanJob = null
        requestedSubtitleStreamIndex = option?.streamIndex
        requestedLocalSubtitleAsset = null
        requestedSubtitleSelection = option?.streamIndex.toSubtitleSelectionIntent()
        val targetAndSelection =
            option?.let { selectedOption ->
                val descriptor =
                    currentPlan.embeddedSubtitleTracks.firstOrNull { track ->
                        track.jellyfinStreamIndex == selectedOption.streamIndex
                    } ?: return
                val target =
                    newSubtitleActivationTarget(
                        streamIndex = selectedOption.streamIndex,
                        kind =
                            if (subtitleKind(descriptor.codec) == SubtitleKind.Bitmap) {
                                LocalSubtitleKind.EmbeddedBitmap
                            } else {
                                LocalSubtitleKind.EmbeddedText
                            },
                    )
                target to
                    EmbeddedSubtitleSelection(
                        target = target,
                        descriptor = descriptor,
                    )
            }
        val target = targetAndSelection?.first
        plan =
            currentPlan.copy(
                selectedSubtitleStreamIndex = option?.streamIndex,
                subtitleAsset = null,
                subtitleActivationTarget = target,
                plannedSubtitle =
                    targetAndSelection?.let { (_, selection) ->
                        PlannedSubtitle.Track(
                            streamIndex = selection.target.streamIndex ?: return,
                            embeddedTrack = selection.descriptor,
                            deliveryMethod = SubtitleDeliveryMethod.Embed,
                            kind = subtitleKind(selection.descriptor.codec),
                            activationTarget = selection.target,
                            normalizedFormat = selection.descriptor.codec,
                        )
                    } ?: PlannedSubtitle.Off,
            )
        playerController.selectEmbeddedSubtitle(targetAndSelection?.second)
        reloadTimingForCurrentTracks()
        publishContent(pickerVisible = PlayerPicker.None)
    }

    fun selectSubtitle(streamIndex: Int?) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        val option =
            streamIndex?.let { selectedIndex ->
                subtitleOptions(mediaStreams)
                    .firstOrNull { track -> track.streamIndex == selectedIndex }
                    ?: return
            }
        val currentPlan = plan ?: return
        if (currentPlan.streamMode == StreamMode.Offline) {
            selectOfflineSubtitle(currentPlan, option)
            return
        }
        invalidateSubtitleFallback()
        replanJob?.cancel()
        replanJob = null
        requestedSubtitleStreamIndex = streamIndex
        requestedLocalSubtitleAsset = null
        requestedSubtitleSelection = streamIndex.toSubtitleSelectionIntent()
        persistSubtitleSelection(requestedSubtitleSelection)
        rememberSelection()

        val installedTrack = currentPlan.plannedSubtitle as? PlannedSubtitle.Track
        val retainedEmbeddedDescriptor =
            option
                ?.takeUnless(SubtitleTrackOption::isExternal)
                ?.let { selectedOption ->
                    currentPlan.embeddedSubtitleTracks.firstOrNull { descriptor ->
                        descriptor.jellyfinStreamIndex == selectedOption.streamIndex
                    }
                }
        val canSwitchApprovedEmbeddedSubtitle =
            currentPlan.streamMode == StreamMode.DirectPlay &&
                currentPlan.plannedSubtitle is PlannedSubtitle.Off &&
                retainedEmbeddedDescriptor?.let { descriptor ->
                    subtitleKind(descriptor.codec) == SubtitleKind.Text
                } == true
        val canSwitchInPlayer =
            (option == null && installedTrack?.deliveryMethod in localEmbeddedDeliveryMethods) ||
                (
                    option != null &&
                        installedTrack?.streamIndex == option.streamIndex &&
                        installedTrack.deliveryMethod in localEmbeddedDeliveryMethods
                ) ||
                canSwitchApprovedEmbeddedSubtitle
        if (canSwitchInPlayer) {
            val target =
                option?.let { track ->
                    // The retained descriptor guard admits text tracks only.
                    val kind =
                        installedTrack?.localKind()
                            ?: retainedEmbeddedDescriptor?.let { LocalSubtitleKind.EmbeddedText }
                            ?: return
                    newSubtitleActivationTarget(
                        streamIndex = track.streamIndex,
                        kind = kind,
                    )
                }
            val selection =
                option?.let { track ->
                    val descriptor = installedTrack?.embeddedTrack ?: retainedEmbeddedDescriptor ?: return
                    EmbeddedSubtitleSelection(
                        target = target ?: return,
                        descriptor = descriptor,
                    )
                }
            plan =
                currentPlan.copy(
                    selectedSubtitleStreamIndex = streamIndex,
                    subtitleAsset = null,
                    subtitleActivationTarget = target,
                    plannedSubtitle =
                        if (option == null) {
                            PlannedSubtitle.Off
                        } else {
                            installedTrack?.copy(activationTarget = target)
                                ?: retainedEmbeddedDescriptor?.let { descriptor ->
                                    PlannedSubtitle.Track(
                                        streamIndex = option.streamIndex,
                                        embeddedTrack = descriptor,
                                        deliveryMethod = SubtitleDeliveryMethod.Embed,
                                        kind = subtitleKind(descriptor.codec),
                                        activationTarget = target,
                                        normalizedFormat = descriptor.codec,
                                    )
                                } ?: PlannedSubtitle.Off
                        },
                )
            playerController.selectEmbeddedSubtitle(selection)
            reloadTimingForCurrentTracks()
            publishContent(pickerVisible = PlayerPicker.None)
        } else {
            if (option == null || currentPlan.plannedSubtitle is PlannedSubtitle.LocalAsset) {
                playerController.selectEmbeddedSubtitle(null)
            }
            publishContent(pickerVisible = PlayerPicker.None)
            replanAtCurrentPosition()
        }
    }

    fun selectLocalSubtitle(assetId: String) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        val sourceId = selectedMediaSourceId?.takeIf(String::isNotBlank) ?: return
        val context = LocalSubtitleContext(session.serverId, session.userId, currentItemId, sourceId)
        invalidateSubtitleFallback()
        replanJob?.cancel()
        replanJob =
            viewModelScope.launch {
                val asset = withContext(workDispatcher) { getLocalSubtitleAssetUseCase?.invoke(assetId, context) }
                if (asset == null) {
                    playerDiagnosticsRecorder.recordDiagnostic(
                        context = playerDiagnosticContext(),
                        facts =
                            PlayerDiagnosticFacts(
                                stage = PlaybackDiagnosticStage.Mapping,
                                event = PlaybackDiagnosticEvent.Failed,
                                trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                            ),
                    )
                    return@launch
                }
                requestedSubtitleStreamIndex = null
                requestedLocalSubtitleAsset = asset
                requestedSubtitleSelection = SubtitleSelectionIntent.LocalAsset(asset.id)
                persistSubtitleSelection(requestedSubtitleSelection)
                playerController.selectEmbeddedSubtitle(null)
                reloadTimingForCurrentTracks()
                publishContent(pickerVisible = PlayerPicker.None)
                // Clear the shared job slot before the replan replaces it.
                replanJob = null
                replanAtCurrentPosition()
            }
    }

    fun selectQuality(maxBitrateBps: Long?) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        playbackHealthCoordinator.dismissGuidance()
        pendingRecoveredPlaybackGuidanceItemId = null
        qualitySession = qualitySession.selectFixedOrOriginal(maxBitrateBps)
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
        updatePlaybackHealthSessionContext()
        // Show buffering while the replan fetches PlaybackInfo off Main.
        val currentState = state.value
        if (currentState is PlayerUiState.Content) {
            publishContent(
                playbackState = currentState.playbackState.copy(status = PlaybackStatus.Buffering),
            )
        }
        replanAtCurrentPosition()
    }

    fun selectQuality(policy: PlaybackQualityPolicy) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        val normalized = policy.normalized()
        playbackHealthCoordinator.dismissGuidance()
        pendingRecoveredPlaybackGuidanceItemId = null
        pendingRecoveredAutoQualityBps = null
        playbackActionNotice = null
        qualitySession =
            when (normalized.mode) {
                PlaybackQualityMode.Auto -> qualitySession.selectAuto()
                PlaybackQualityMode.Fixed -> qualitySession.selectFixedOrOriginal(normalized.maxBitrateBps)
                PlaybackQualityMode.Original -> qualitySession.selectFixedOrOriginal(maximumBitrateBps = null)
            }
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
        updatePlaybackHealthSessionContext()
        replanAtCurrentPosition()
    }

    fun acceptAutoPlayback() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        playbackActionNotice = null
        pendingRecoveredAutoQualityBps = null
        qualitySession = qualitySession.selectAuto()
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
        updatePlaybackHealthSessionContext()
        replanAtCurrentPosition()
    }

    fun clearQualityOverride() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        val inheritedPolicy = activePlaybackPreferences.effectiveDefaultQualityPolicy(backend)
        playbackActionNotice = null
        pendingRecoveredAutoQualityBps = null
        qualitySession = qualitySession.inheritLaunchOrDefault(inheritedPolicy)
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
        updatePlaybackHealthSessionContext()
        replanAtCurrentPosition()
    }

    fun keepRecoveredQualityForSession() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        val bitrate = autoRecoveryState.runtimeQualityCapBps ?: qualitySession.maximumBitrateBps ?: return
        qualitySession = qualitySession.retainRecoveredQuality(bitrate)
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
        playbackActionNotice = null
        updatePlaybackHealthSessionContext()
        replanAtCurrentPosition()
    }

    fun tryHigherQualityOrOriginal() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        // Clear recovery state before an explicit uncapped retry.
        pendingRecoveredAutoQualityBps = null
        playbackActionNotice = null
        qualitySession = qualitySession.retryUncappedAuto()
        autoRecoveryState = autoRecoveryCoordinator.clearRuntimeQualityCap(autoRecoveryState)
        updatePlaybackHealthSessionContext()
        replanAtCurrentPosition()
    }

    fun dismissPlaybackActionNotice() {
        playbackActionNotice = null
        publishContent()
    }

    fun retryPlaybackFromNotice() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        playbackActionNotice = null
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(playbackLaunchGeneration, currentItemId)
        playerController.retry()
        playerController.runtimeDiagnostics.value.prepareEpoch
            ?.let(playbackHealthCoordinator::expectVideoOutput)
        armFirstVideoOutputState()
    }

    fun handlePlaybackAction(action: PlaybackAction) {
        if (controllerInstallInFlight) return
        when (action) {
            PlaybackAction.AcceptAuto -> acceptAutoPlayback()
            PlaybackAction.ClearQualityOverride -> clearQualityOverride()
            PlaybackAction.KeepCurrentQuality -> keepRecoveredQualityForSession()
            PlaybackAction.ChooseLowerQuality -> chooseLowerQuality()
            PlaybackAction.TryHigherQuality -> tryHigherQualityOrOriginal()
            PlaybackAction.TryOriginal -> selectQuality(PlaybackQualityPolicy.Original)
            PlaybackAction.Retry -> retryPlaybackFromNotice()
            PlaybackAction.Dismiss -> dismissPlaybackActionNotice()
            PlaybackAction.OpenPlaybackSettings,
            PlaybackAction.Close,
            -> Unit
        }
    }

    private fun chooseLowerQuality() {
        playbackActionNotice = null
        publishContent(pickerVisible = PlayerPicker.Quality)
    }

    fun dismissPlaybackGuidance() {
        playbackHealthCoordinator.dismissGuidance()
    }

    /** Defers actionable recovery until the full player UI can present it. */
    fun setPictureInPictureMode(inPictureInPicture: Boolean) {
        pictureInPictureMode = inPictureInPicture
        if (!inPictureInPicture) {
            val pendingTrigger = pendingPictureInPictureRecoveryTrigger ?: return
            pendingPictureInPictureRecoveryTrigger = null
            handleAutomaticRecoveryTrigger(pendingTrigger)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (!speed.isFinite()) {
            return
        }
        playbackSpeed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        plan = plan?.copy(playbackSpeed = playbackSpeed)
        playerController.setPlaybackSpeed(playbackSpeed)
        publishContent(playbackState = playbackState.value.copy(playbackSpeed = playbackSpeed))
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        subtitleStyle = style
        plan = plan?.copy(subtitleStyle = subtitleStyle)
        playerController.setSubtitleStyle(style)
        publishContent(playbackState = playbackState.value.copy(subtitleStyle = subtitleStyle))
    }

    fun adjustAudioTiming(deltaMs: Long) {
        adjustTiming(PlaybackTimingKind.Audio, deltaMs)
    }

    fun adjustSubtitleTiming(deltaMs: Long) {
        adjustTiming(PlaybackTimingKind.Subtitle, deltaMs)
    }

    fun resetAudioTiming() {
        setTiming(PlaybackTimingKind.Audio, 0L)
    }

    fun resetSubtitleTiming() {
        setTiming(PlaybackTimingKind.Subtitle, 0L)
    }

    private fun adjustTiming(
        kind: PlaybackTimingKind,
        deltaMs: Long,
    ) {
        if (controllerInstallInFlight) return
        timingCoordinator.adjust(kind, deltaMs)
    }

    private fun setTiming(
        kind: PlaybackTimingKind,
        offsetMs: Long,
    ) {
        if (controllerInstallInFlight) return
        timingCoordinator.set(kind, offsetMs)
    }

    private fun timingKey(kind: PlaybackTimingKind): PlaybackTimingKey? {
        val sourceId = selectedMediaSourceId?.takeIf(String::isNotBlank) ?: return null
        val trackId =
            when (kind) {
                PlaybackTimingKind.Audio ->
                    (requestedAudioStreamIndex ?: installedAudioStreamIndex)?.toString()
                PlaybackTimingKind.Subtitle ->
                    requestedLocalSubtitleAsset?.id
                        ?: requestedSubtitleStreamIndex?.toString()
            } ?: return null
        return PlaybackTimingKey(
            serverId = session.serverId,
            userId = session.userId,
            itemId = currentItemId,
            mediaSourceId = sourceId,
            trackId = trackId,
            kind = kind,
        )
    }

    private suspend fun loadTimingOffsets() {
        timingCoordinator.load()
    }

    private fun reloadTimingForCurrentTracks() {
        timingCoordinator.reloadForCurrentTracks()
    }

    fun dispose() {
        if (disposed) return
        invalidateBackendSwitch()
        disposed = true
        cancelAndInvalidateSubtitleFallback()
        emitPlaybackHealthSummary()
        endPlaybackHealthSession()
        audioRecoveryTarget = null
        reportingJob?.cancel()
        reportingJob = null
        runtimeDiagnosticsJob?.cancel()
        runtimeDiagnosticsJob = null
        droppedFrameMeasurementsJob?.cancel()
        droppedFrameMeasurementsJob = null
        videoOutputObservationsJob?.cancel()
        videoOutputObservationsJob = null
        playbackTransitionObservationsJob?.cancel()
        playbackTransitionObservationsJob = null
        localSubtitleAssetsJob?.cancel()
        timingCoordinator.dispose()
        saveSubtitleSelectionAction?.drainLatest()
        savePlaybackSelectionAction?.drainLatest()
        replanJob?.cancel()
        replanJob = null
        activePlaybackTimelineFacts = null
        playlistMetadataJob?.cancel()
        derivedEpisodeQueueJob?.cancel()
        playbackReportingCoordinator.dispose(playerController.playbackState.value.positionMs)
        releaseOwnedPlayerController()
    }

    override fun onCleared() {
        dispose()
        super.onCleared()
    }

    private fun load() {
        viewModelScope.launch {
            if (
                startPlaybackForItem(
                    itemId = itemId,
                    requestedMediaSourceId = mediaSourceId,
                    requestedAudioStreamIndex = initialAudioStreamIndex,
                    requestedSubtitleSelection = initialSubtitleSelection,
                    startPositionTicks = startPositionTicks,
                    resetReporting = false,
                )
            ) {
                observePlaybackState()
                if (offlineDownloadId == null) {
                    fetchPlaylistMetadata()
                }
            }
        }
    }

    private suspend fun startPlaybackForItem(
        itemId: String,
        requestedMediaSourceId: String?,
        requestedAudioStreamIndex: Int?,
        requestedSubtitleSelection: SubtitleSelectionIntent,
        startPositionTicks: Long,
        resetReporting: Boolean,
    ): Boolean {
        invalidateBackendSwitch()
        emitPlaybackHealthSummary()
        activePlaybackTimelineFacts = null
        playbackLaunchGeneration += 1L
        val launchGeneration = playbackLaunchGeneration
        playbackLaunchMarker =
            PlaybackLaunchMarker(
                generation = launchGeneration,
                startedAtMs = monotonicTimeMs(),
                healthStartedAtMs = monotonicTimeMs(),
            )
        resetPlaybackHealthSession(
            generation = launchGeneration,
            launchAtMs = playbackLaunchMarker?.healthStartedAtMs ?: monotonicTimeMs(),
        )
        launchToFirstFrameMs = null
        timingCoordinator.cancelLoad()
        invalidateSubtitleFallback()
        derivedEpisodeQueueJob?.cancel()
        derivedEpisodeQueueJob = null
        currentItemId = itemId
        trickplay = null
        trickplayByMediaSourceId = emptyMap()
        autoRecoveryState = autoRecoveryCoordinator.reset(launchGeneration, currentItemId, backend)
        pendingPictureInPictureRecoveryTrigger = null
        autoSkippedSegmentKeys.clear()
        alternateBackendFallbackAttempted = false
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(launchGeneration, currentItemId)
        pendingRecoveredPlaybackGuidanceItemId = null
        pendingRecoveredAutoQualityBps = null
        playbackActionNotice = null
        if (offlineDownloadId != null) {
            return startOfflinePlaybackForItem(
                itemId = itemId,
                startPositionTicks = startPositionTicks,
                launchGeneration = launchGeneration,
                resetReporting = resetReporting,
            )
        }
        val effectiveStartPositionTicks = startPositionTicks
        currentStartPositionTicks = effectiveStartPositionTicks
        // Fetch skip segments concurrently so they do not delay first frame.
        val segmentsDeferred =
            CoroutineScope(currentCoroutineContext()).async(workDispatcher) {
                getMediaSegmentsUseCase(itemId).getOrDefault(emptyList())
            }
        val detail =
            withContext(workDispatcher) {
                getItemDetailUseCase(itemId, includePlaybackFields = true).getOrNull()
            }
        val selectedVersion = detail?.selectedVersion(requestedMediaSourceId)

        if (selectedVersion == null) {
            clearPlaybackLaunch(launchGeneration)
            segmentsDeferred.cancel()
            _state.update { PlayerUiState.Error(error = PlaybackError.UnsupportedMedia) }
            return false
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            segmentsDeferred.cancel()
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        activePlaybackTimelineFacts = detail.toActivePlaybackTimelineFacts(launchGeneration)

        val launchContext =
            withContext(workDispatcher) {
                getPlaybackLaunchContextUseCase(session, itemId, selectedVersion.id)
            }
        currentCoroutineContext().ensureActive()
        if (!isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            segmentsDeferred.cancel()
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        val playbackPreferences = launchContext.playbackPreferences
        activePlaybackPreferences = playbackPreferences

        chapters = detail.chapters
        trickplayByMediaSourceId = detail.trickplayByMediaSourceId
        selectedMediaSourceId = selectedVersion.id
        selectedSourceContainer = selectedVersion.container
        observeLocalSubtitleAssets(itemId, selectedVersion.id)
        mediaStreams = selectedVersion.mediaStreams
        val backendResolved =
            try {
                resolveBackendForSession(
                    selectedVersion = selectedVersion,
                    expectedGeneration = launchGeneration,
                    expectedItemId = itemId,
                )
            } catch (exception: CancellationException) {
                segmentsDeferred.cancel()
                clearPlaybackLaunch(launchGeneration)
                throw exception
            } catch (exception: Throwable) {
                segmentsDeferred.cancel()
                clearPlaybackLaunch(launchGeneration)
                val startupError = startupPlanningError(exception)
                _state.update { startupError }
                playerDiagnosticsRecorder.recordTerminalOutcome(
                    context = playerDiagnosticContext(),
                    facts =
                        PlayerTerminalDiagnosticFacts(
                            outcome = PlaybackTerminalOutcome.Failed,
                            error = startupError.error,
                            autoRecoveryTrigger = null,
                            recoveryDecision = null,
                        ),
                )
                return false
            }
        if (!backendResolved) {
            segmentsDeferred.cancel()
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        metadata =
            PlayerMediaMetadata(
                title = detail.item.name,
                seriesName = detail.item.seriesName,
                episodeLabel = detail.item.episodeLabel,
                productionYear = detail.productionYear ?: detail.item.productionYear,
                runtimeMs = detail.item.runtime?.inWholeMilliseconds,
                qualityBadge = selectedVersion.name.takeIf { name -> name.isNotBlank() },
                imageUrl =
                    detail.item.imageRefs.primaryTag?.let { tag ->
                        imageUrlBuilder.build(
                            serverUrl = session.serverUrl,
                            itemId = itemId,
                            type = JellyfinImageType.Primary,
                            tag = tag,
                            maxWidth = 500,
                        )
                    },
            )

        playerDiagnosticsRecorder.recordPersistence(
            context = playerDiagnosticContext(sessionSequence = launchGeneration),
            facts =
                PlayerPersistenceDiagnosticFacts(
                    event = PlaybackDiagnosticEvent.Read,
                    target = PlaybackPersistenceTarget.PlaybackSelection,
                    result = launchContext.playbackSelectionOutcome.toPersistenceResult(),
                ),
        )
        val durableSelection = launchContext.playbackSelection
        val rememberedSelection =
            resolvePlaybackSelection(
                durableSelection = durableSelection,
                sourceSelection = playbackSelectionMemory.sourceSelectionFor(accountIdentity, itemId, selectedVersion.id),
                legacyItemSelection = playbackSelectionMemory.selectionFor(accountIdentity, itemId),
                durableStoreAvailable = launchContext.playbackSelectionOutcome != PlaybackLaunchReadOutcome.Unavailable,
            )
        val audioTrackOptions = audioOptions(mediaStreams)
        val subtitleTrackOptions = subtitleOptions(mediaStreams)
        requestedLocalSubtitleAsset = null
        val launchAudioSelection =
            resolveLaunchAudioSelection(
                explicitLaunchAudioStreamIndex = requestedAudioStreamIndex,
                durableSelection = durableSelection,
                rememberedSelection = rememberedSelection,
                audioTrackOptions = audioTrackOptions,
                preferredAudioStreamIndex =
                    audioTrackOptions.preferredAudioStreamIndex(playbackPreferences.preferredAudioLanguage),
            )
        explicitAudioStreamIndex = launchAudioSelection.explicitAudioStreamIndex
        val subtitleSelectionKey = subtitleSelectionKey(itemId = itemId, mediaSourceId = selectedVersion.id)
        val effectiveSubtitleSelection =
            resolveSubtitleSelection(
                explicit = requestedSubtitleSelection,
                stored = launchContext.subtitleSelection,
                key = subtitleSelectionKey,
                options = subtitleTrackOptions,
                playbackPreferences = playbackPreferences,
            )
        val effectiveSubtitleStreamIndex = (effectiveSubtitleSelection as? SubtitleSelectionIntent.Track)?.streamIndex
        this.requestedAudioStreamIndex = launchAudioSelection.requestedAudioStreamIndex
        installedAudioStreamIndex = null
        audioRecoveryTarget = null
        this.requestedSubtitleStreamIndex = effectiveSubtitleStreamIndex
        this.requestedSubtitleSelection = effectiveSubtitleSelection
        if (requestedSubtitleSelection.isValidFor(subtitleTrackOptions)) {
            saveSubtitleSelectionAction?.save(subtitleSelectionKey, effectiveSubtitleSelection)
        }
        val inheritedQualityPolicy = playbackPreferences.effectiveDefaultQualityPolicy(backend)
        qualitySession = qualitySession.inheritLaunchOrDefault(inheritedQualityPolicy)
        playerDiagnosticsRecorder.recordQualityResolved(
            context = playerDiagnosticContext(),
            maxStreamingBitrateBps = qualitySession.maximumBitrateBps,
            usesVlcDefault = playbackPreferences.usesVlcDefaultQuality(backend),
            settingsDefaultBitrateBps = playbackPreferences.effectiveDefaultQualityPolicy().maxBitrateBps,
            vlcDefaultBitrateBps = playbackPreferences.vlcTranscodeMaxBitrateBps,
        )

        val selectedSubtitle = selectedSubtitleMediaStream()
        val activationRequestId = nextSubtitleActivationRequestId()
        val initialRequestPolicy =
            PlaybackInfoRequestPolicy(
                backend = backend,
                diagnosticSessionSequence = launchGeneration,
            )
        val playbackPlan =
            try {
                withContext(workDispatcher) {
                    playbackInfoPlanner.plan(
                        session = session,
                        itemId = itemId,
                        mediaSourceId = selectedVersion.id,
                        startPositionTicks = effectiveStartPositionTicks,
                        audioStreamIndex = this@PlayerViewModel.requestedAudioStreamIndex,
                        detailMediaStreams = mediaStreams,
                        subtitleSelection = effectiveSubtitleSelection,
                        localSubtitleAsset = requestedLocalSubtitleAsset?.toPlaybackAsset(),
                        maxStreamingBitrate = qualitySession.maximumBitrateBps,
                        qualityPolicy = qualitySession.policy,
                        qualityCapOrigin = qualitySession.capOrigin,
                        requestPolicy = initialRequestPolicy,
                        sourceContainer = selectedVersion.container,
                    )
                }
            } catch (exception: CancellationException) {
                segmentsDeferred.cancel()
                clearPlaybackLaunch(launchGeneration)
                throw exception
            } catch (exception: Throwable) {
                segmentsDeferred.cancel()
                clearPlaybackLaunch(launchGeneration)
                playerDiagnosticsRecorder.recordPlannerAttemptFailure(
                    context = playerDiagnosticContext(),
                    facts =
                        PlayerPlannerFailureDiagnosticFacts(
                            exception = exception,
                            requestPolicy = initialRequestPolicy,
                            qualityPolicy = qualitySession.policy,
                            qualityCapOrigin = qualitySession.capOrigin,
                            requestCapBitrateBps = qualitySession.maximumBitrateBps,
                        ),
                )
                val startupError = startupPlanningError(exception)
                _state.update { startupError }
                playerDiagnosticsRecorder.recordTerminalOutcome(
                    context = playerDiagnosticContext(),
                    facts =
                        PlayerTerminalDiagnosticFacts(
                            outcome = PlaybackTerminalOutcome.Failed,
                            error = startupError.error,
                            autoRecoveryTrigger = null,
                            recoveryDecision = null,
                        ),
                )
                return false
            }
        if (!isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            segmentsDeferred.cancel()
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        mediaSegments = segmentsDeferred.await()
        currentCoroutineContext().ensureActive()
        if (!isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        val playbackPlanWithMetadata =
            enrichPlaybackPlan(
                playbackPlan = playbackPlan,
                requestedMediaSourceId = selectedVersion.id,
                generation = launchGeneration,
                itemId = itemId,
                activationRequestId = activationRequestId,
                selectedSubtitle = selectedSubtitle,
            ) ?: return false
        // Reject superseded work before the non-suspending prepare call.
        currentCoroutineContext().ensureActive()
        if (!isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        installPlan(playbackPlanWithMetadata, resetReporting = resetReporting, stabilizesQueueSwitch = true)
        if (launchAudioSelection.explicitLaunchAudioStreamIndex != null) {
            // Persist the explicit launch choice, not a later runtime substitution.
            rememberSelection()
        }
        publishContent(
            playbackState =
                PlaybackState(
                    status = PlaybackStatus.Loading,
                    positionMs = 0L,
                    durationMs = resolvePlaybackDurationMs(playbackPlanWithMetadata.contentTimeline, null),
                    bufferedPositionMs = 0L,
                ).withPlaybackMetadata(),
            pickerVisible = PlayerPicker.None,
        )
        // Load timing offsets before native prepare.
        loadTimingOffsets()
        markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Prepare)
        playerDiagnosticsRecorder.recordPrepareRequested(playerDiagnosticContext())
        playerController.prepare(playbackPlanWithMetadata)
        if (rejectSynchronouslyFailedPrepare()) return false
        installedPlan = playbackPlanWithMetadata
        playerController.runtimeDiagnostics.value.prepareEpoch
            ?.let(playbackHealthCoordinator::expectVideoOutput)
        armFirstVideoOutputState()
        playerDiagnosticsRecorder.recordPrepareDispatched(playerDiagnosticContext())
        bindPlaybackLaunchToPrepare(launchGeneration, playerController)
        applyInitialEmbeddedSelections(playbackPlanWithMetadata)
        desiredPlayWhenReady = true
        playerController.play()
        publishContent()
        maybeStartInstalledAudioUnavailableFallback(playbackPlanWithMetadata)
        maybeStartInstalledUnavailableFallback(playbackPlanWithMetadata)
        deriveEpisodeQueueIfNeeded(detail.item)
        return true
    }

    /** Local-only launch that fails without remote planning or fallback. */
    private suspend fun startOfflinePlaybackForItem(
        itemId: String,
        startPositionTicks: Long,
        launchGeneration: Long,
        resetReporting: Boolean,
    ): Boolean {
        val downloadId = offlineDownloadId ?: return false
        val useCase =
            getOfflinePlaybackPlanUseCase
                ?: return failOfflineLaunch(launchGeneration, PlaybackError.OfflineArtifactUnavailable)
        val record =
            try {
                useCase(accountIdentity, downloadId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                null
            }
        if (
            record == null ||
            record.businessKey.accountIdentity != accountIdentity ||
            record.businessKey.itemId != itemId
        ) {
            return failOfflineLaunch(launchGeneration, PlaybackError.OfflineArtifactUnavailable)
        }
        val snapshot = record.request.snapshot
        val sourceId = record.businessKey.mediaSourceId
        val offlineProjection =
            projectOfflinePlayback(
                OfflinePlaybackProjectionInput(
                    snapshot = snapshot,
                    itemId = itemId,
                    mediaSourceId = sourceId,
                    downloadId = record.downloadId,
                    attemptGeneration = record.attemptGeneration,
                    artifactKind = record.request.artifactKind,
                    accountIdentity = accountIdentity,
                    startPositionTicks = startPositionTicks,
                    localResumePositionMs = record.localResumePositionMs,
                    launchGeneration = launchGeneration,
                ),
            )
        metadata = offlineProjection.metadata
        val launchContext =
            withContext(workDispatcher) {
                getPlaybackLaunchContextUseCase(session = session, itemId = itemId, mediaSourceId = sourceId)
            }
        activePlaybackPreferences = launchContext.playbackPreferences
        currentItemId = itemId
        currentStartPositionTicks = startPositionTicks
        queueIds = listOf(itemId)
        currentQueueIndex = 0
        activePlaybackTimelineFacts = offlineProjection.timelineFacts
        chapters = offlineProjection.chapters
        trickplay = null
        trickplayByMediaSourceId = emptyMap()
        mediaSegments = emptyList()
        selectedMediaSourceId = sourceId
        selectedSourceContainer = offlineProjection.sourceContainer
        mediaStreams = offlineProjection.mediaStreams
        requestedAudioStreamIndex = offlineProjection.selectedAudioStreamIndex
        installedAudioStreamIndex = requestedAudioStreamIndex
        requestedSubtitleStreamIndex = offlineProjection.selectedSubtitleStreamIndex
        requestedSubtitleSelection = offlineProjection.selectedSubtitleSelection
        requestedLocalSubtitleAsset = null
        qualitySession =
            qualitySession.forOfflineSession(activePlaybackPreferences.effectiveDefaultQualityPolicy(backend))
        explicitAudioStreamIndex = offlineProjection.selectedAudioStreamIndex
        backendResolvedForSession = false

        val resolvedBackend =
            try {
                resolveOfflineBackendForSession(
                    source = snapshot.backendSource,
                    artifactKind = record.request.artifactKind,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                return failOfflineLaunch(
                    launchGeneration,
                    PlaybackError.OfflinePlayerUnavailable(
                        requiredBackend =
                            backend.takeUnless { it == PlayerBackend.Auto } ?: PlayerBackend.AVPlayer,
                    ),
                )
            }
        backend = resolvedBackend
        val offlinePlan = offlineProjection.offlinePlan
        if (!isCurrentPlaybackLaunch(launchGeneration, itemId)) return false
        val exactOfflineBackendRequired =
            !offlineControllerFallbackAllowed(
                artifactKind = record.request.artifactKind,
                resolvedBackend = resolvedBackend,
                requiredBackend = deviceProfileProvider?.requiredOfflineBackend,
            )
        val activeBackend =
            try {
                installController(
                    resolvedBackend = resolvedBackend,
                    requestedBackend = resolvedBackend,
                    allowFallback = !exactOfflineBackendRequired,
                    expectedGeneration = launchGeneration,
                    expectedItemId = itemId,
                ) ?: return false
            } catch (exception: OfflineControllerUnavailableException) {
                return failOfflineLaunch(
                    launchGeneration,
                    PlaybackError.OfflinePlayerUnavailable(exception.requiredBackend),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                return failOfflineLaunch(
                    launchGeneration,
                    PlaybackError.OfflinePlayerUnavailable(resolvedBackend),
                )
            }
        playerDiagnosticsRecorder.recordBackendSelection(
            context = playerDiagnosticContext(),
            facts =
                PlayerBackendSelectionDiagnosticFacts(
                    requestedBackend = resolvedBackend,
                    activeBackend = activeBackend,
                    fallbackResult =
                        if (activeBackend != resolvedBackend) {
                            PlaybackBackendFallbackResult.Applied
                        } else {
                            PlaybackBackendFallbackResult.NotRequired
                        },
                ),
        )
        installPlan(offlinePlan, resetReporting = resetReporting, stabilizesQueueSwitch = true)
        publishContent(
            playbackState =
                PlaybackState(
                    status = PlaybackStatus.Loading,
                    positionMs = offlinePlan.startPositionMs,
                    durationMs = resolvePlaybackDurationMs(offlinePlan.contentTimeline, null),
                    bufferedPositionMs = 0L,
                ).withPlaybackMetadata(),
            pickerVisible = PlayerPicker.None,
        )
        loadTimingOffsets()
        markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Prepare)
        playerDiagnosticsRecorder.recordPrepareRequested(playerDiagnosticContext())
        try {
            when (val result = playerController.prepareOffline(offlinePlan)) {
                com.jellyscope.core.domain.playback.OfflinePrepareResult.Started -> {
                    installedPlan = offlinePlan
                }
                is com.jellyscope.core.domain.playback.OfflinePrepareResult.Unavailable -> {
                    releaseOwnedPlayerController()
                    return failOfflineLaunch(launchGeneration, result.error)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            releaseOwnedPlayerController()
            return failOfflineLaunch(
                launchGeneration,
                PlaybackError.OfflinePlayerUnavailable(
                    requiredBackend =
                        if (exactOfflineBackendRequired) {
                            resolvedBackend
                        } else {
                            activeBackend
                        },
                ),
            )
        }
        playerController.runtimeDiagnostics.value.prepareEpoch
            ?.let(playbackHealthCoordinator::expectVideoOutput)
        armFirstVideoOutputState()
        playerDiagnosticsRecorder.recordPrepareDispatched(playerDiagnosticContext())
        bindPlaybackLaunchToPrepare(launchGeneration, playerController)
        applyInitialEmbeddedSelections(offlinePlan)
        desiredPlayWhenReady = true
        playerController.play()
        publishContent()
        return true
    }

    private fun failOfflineLaunch(
        launchGeneration: Long,
        error: PlaybackError,
    ): Boolean {
        clearPlaybackLaunch(launchGeneration)
        _state.update { PlayerUiState.Error(error = error) }
        playerDiagnosticsRecorder.recordTerminalOutcome(
            context = playerDiagnosticContext(),
            facts =
                PlayerTerminalDiagnosticFacts(
                    outcome = PlaybackTerminalOutcome.Failed,
                    error = error,
                    autoRecoveryTrigger = null,
                    recoveryDecision = null,
                ),
        )
        return false
    }

    private suspend fun resolveOfflineBackendForSession(
        source: BackendSourceDescriptor,
        artifactKind: DownloadArtifactKind,
    ): PlayerBackend {
        val provider = deviceProfileProvider
        val resolved =
            if (provider == null) {
                backend.takeUnless { candidate -> candidate == PlayerBackend.Auto }
                    ?: PlayerBackend.AVPlayer
            } else {
                val itemOverride =
                    getPlayerBackendOverrideUseCase?.let { getOverride ->
                        withContext(workDispatcher) { getOverride(session.serverId, currentItemId) }
                    }
                val requested =
                    withContext(workDispatcher) {
                        resolvePlayerBackend(
                            defaultBackend = activePlaybackPreferences.defaultPlayerBackend,
                            itemOverride = itemOverride,
                            source = source,
                            avPlayerCapabilities = provider.capabilities(PlayerBackend.AVPlayer),
                            backendPolicy = provider.backendPolicy,
                        )
                    }
                val availableBackends = withContext(workDispatcher) { provider.availableBackends }
                requested.takeIf { candidate -> candidate in availableBackends } ?: provider.backendPolicy.defaultBackend
            }
        val platform =
            provider?.backendPolicy?.platform
                ?: when (resolved) {
                    PlayerBackend.AVPlayer,
                    PlayerBackend.VlcKit,
                    -> PlayerBackendPlatform.Apple
                    PlayerBackend.ExoPlayer -> PlayerBackendPlatform.Android
                    PlayerBackend.Mpv,
                    PlayerBackend.LibVlc,
                    -> PlayerBackendPlatform.Desktop
                    PlayerBackend.Auto -> PlayerBackendPlatform.Desktop
                }
        return resolveOfflinePlaybackBackend(
            platform = platform,
            artifactKind = artifactKind,
            resolvedBackend = resolved,
            requiredBackend = provider?.requiredOfflineBackend,
        )
    }

    private suspend fun resolveSubtitleSelection(
        explicit: SubtitleSelectionIntent,
        stored: SubtitleSelectionIntent?,
        key: SubtitleSelectionKey,
        options: List<SubtitleTrackOption>,
        playbackPreferences: PlaybackPreferences,
    ): SubtitleSelectionIntent {
        resolveLocalSelection(explicit, key, options, preferConfirmedServer = false, deleteInvalid = false)?.let { return it }
        if (explicit.isValidFor(options)) {
            return explicit
        }

        resolveLocalSelection(stored, key, options, preferConfirmedServer = true, deleteInvalid = true)?.let { return it }
        if (stored.isValidFor(options)) {
            return requireNotNull(stored)
        }

        return options
            .preferredSubtitleStreamIndex(playbackPreferences.preferredSubtitleLanguage)
            ?.let(SubtitleSelectionIntent::Track)
            ?: options.defaultSubtitleStreamIndex()?.let(SubtitleSelectionIntent::Track)
            ?: SubtitleSelectionIntent.Off
    }

    private suspend fun resolveLocalSelection(
        selection: SubtitleSelectionIntent?,
        key: SubtitleSelectionKey,
        options: List<SubtitleTrackOption>,
        preferConfirmedServer: Boolean,
        deleteInvalid: Boolean,
    ): SubtitleSelectionIntent? {
        val local = selection as? SubtitleSelectionIntent.LocalAsset ?: return null
        val asset =
            getLocalSubtitleAssetUseCase?.invoke(
                local.assetId,
                LocalSubtitleContext(key.serverId, key.userId, key.itemId, key.mediaSourceId),
            )
        if (asset == null) {
            if (deleteInvalid) saveSubtitleSelectionAction?.delete(key)
            return null
        }
        val confirmed = asset.confirmedStreamIndex
        if (preferConfirmedServer && confirmed != null && options.any { option -> option.streamIndex == confirmed }) {
            requestedLocalSubtitleAsset = null
            return SubtitleSelectionIntent.Track(confirmed)
        }
        requestedLocalSubtitleAsset = asset
        return local
    }

    private fun LocalSubtitleAsset.toPlaybackAsset(): SubtitleAsset.LocalFile =
        SubtitleAsset.LocalFile(
            assetId = id,
            fileId = fileId,
            mimeType = mimeType,
            label = label,
            language = language,
        )

    private fun SubtitleSelectionIntent?.isValidFor(options: List<SubtitleTrackOption>): Boolean =
        when (this) {
            SubtitleSelectionIntent.Off -> true
            is SubtitleSelectionIntent.Track -> options.any { option -> option.streamIndex == streamIndex }
            is SubtitleSelectionIntent.LocalAsset -> false
            SubtitleSelectionIntent.Unspecified,
            null,
            -> false
        }

    private fun Int?.toSubtitleSelectionIntent(): SubtitleSelectionIntent =
        this?.let(SubtitleSelectionIntent::Track) ?: SubtitleSelectionIntent.Off

    private fun subtitleSelectionKey(
        itemId: String = currentItemId,
        mediaSourceId: String = selectedMediaSourceId.orEmpty(),
    ): SubtitleSelectionKey =
        SubtitleSelectionKey(
            serverId = session.serverId,
            userId = session.userId,
            itemId = itemId,
            mediaSourceId = mediaSourceId,
        )

    private fun persistSubtitleSelection(selection: SubtitleSelectionIntent) {
        val sourceId = selectedMediaSourceId?.takeIf { id -> id.isNotBlank() } ?: return
        saveSubtitleSelectionAction?.save(
            key = subtitleSelectionKey(mediaSourceId = sourceId),
            selection = selection,
        )
    }

    private fun isCurrentPlaybackLaunch(
        generation: Long,
        itemId: String,
    ): Boolean = playbackLaunchGeneration == generation && currentItemId == itemId

    private fun enrichPlaybackPlan(
        playbackPlan: PlaybackPlan,
        requestedMediaSourceId: String,
        generation: Long,
        itemId: String,
        activationRequestId: Long,
        selectedSubtitle: PlaybackMediaStream?,
    ): PlaybackPlan? {
        if (!isCurrentPlaybackLaunch(generation, itemId)) return null
        val contentTimeline =
            resolvePlaybackContentTimeline(
                playbackPlan = playbackPlan,
                requestedMediaSourceId = requestedMediaSourceId,
                facts =
                    activePlaybackTimelineFacts
                        ?.takeIf { active -> active.generation == generation && active.itemId == itemId },
            )
        if (!isCurrentPlaybackLaunch(generation, itemId)) return null
        val audioActivationTarget =
            playbackPlan.selectedAudioStreamIndex?.let(::newAudioActivationTarget)
        return playbackPlan
            .withAudioActivationTarget(audioActivationTarget)
            .withSubtitleActivationTarget(
                requestId = activationRequestId,
                itemId = itemId,
                selectedSubtitle = selectedSubtitle,
            ).copy(
                chapters = chapters,
                trickplay = trickplayByMediaSourceId.trickplayForMediaSource(playbackPlan.mediaSourceId),
                mediaSegments = mediaSegments,
                playbackSpeed = playbackSpeed,
                subtitleStyle = subtitleStyle,
                contentTimeline = contentTimeline,
            )
    }

    private fun observePlaybackState() {
        if (reportingJob != null) {
            return
        }

        reportingJob =
            viewModelScope.launch {
                // Edge handling is fast and idempotent; avoid child jobs per emission.
                playerController.playbackState.collect { playbackState ->
                    handlePlaybackState(playbackState)
                }
            }
    }

    private fun observeRuntimeDiagnostics() {
        runtimeDiagnosticsJob?.cancel()
        droppedFrameMeasurementsJob?.cancel()
        videoOutputObservationsJob?.cancel()
        playbackTransitionObservationsJob?.cancel()
        val controller = playerController
        _runtimeDiagnostics.value = controller.runtimeDiagnostics.value
        runtimeDiagnosticsJob =
            viewModelScope.launch {
                controller.runtimeDiagnostics.collect { diagnostics ->
                    _runtimeDiagnostics.value = diagnostics
                    maybeCompletePlaybackLaunch(controller, diagnostics)
                }
            }
        droppedFrameMeasurementsJob =
            viewModelScope.launch {
                controller.droppedFrameMeasurements.collect { measurement ->
                    playbackHealthCoordinator.recordDroppedFrameMeasurement(measurement)
                }
            }
        videoOutputObservationsJob =
            viewModelScope.launch {
                controller.videoOutputObservations.collect { observation ->
                    playbackHealthCoordinator.observeVideoOutput(observation)
                    if (
                        controller.videoOutputMeasurementCapabilities.isSupported &&
                        observation.generation == firstVideoOutputState.expectedPrepareEpoch
                    ) {
                        val wasObserved = firstVideoOutputState.debug.state == PlaybackFirstVideoOutputState.Observed
                        firstVideoOutputState =
                            firstVideoOutputState.acceptReliablePositiveObservation(
                                observationPrepareEpoch = observation.generation,
                                isPresented = observation.presented,
                                observedAtMs = observation.observedAtMs,
                            )
                        if (
                            !wasObserved &&
                            firstVideoOutputState.debug.state == PlaybackFirstVideoOutputState.Observed
                        ) {
                            playerDiagnosticsRecorder.recordFirstVideoOutput(
                                context =
                                    playerDiagnosticContext(
                                        prepareSequence = firstVideoOutputState.expectedPrepareEpoch,
                                    ),
                                facts =
                                    PlayerFirstVideoOutputDiagnosticFacts(
                                        state = firstVideoOutputState.debug.state,
                                        observed = true,
                                        evidence = firstVideoOutputState.debug.evidence,
                                    ),
                            )
                        }
                        if (
                            firstVideoOutputState.debug.state == PlaybackFirstVideoOutputState.Observed &&
                            state.value is PlayerUiState.Content
                        ) {
                            publishContent(playbackState = playbackState.value)
                        }
                    }
                }
            }
        playbackTransitionObservationsJob =
            viewModelScope.launch {
                controller.playbackTransitionObservations.collect { observation ->
                    playbackHealthCoordinator.observePlaybackTransition(observation)
                }
            }
    }

    private fun armFirstVideoOutputState() {
        val capabilities = playerController.videoOutputMeasurementCapabilities
        firstVideoOutputState =
            firstVideoOutputState.armForPrepare(
                prepareEpoch = playerController.runtimeDiagnostics.value.prepareEpoch,
                capability = capabilities,
            )
        playerDiagnosticsRecorder.recordFirstVideoOutput(
            context =
                playerDiagnosticContext(
                    prepareSequence = firstVideoOutputState.expectedPrepareEpoch,
                ),
            facts =
                PlayerFirstVideoOutputDiagnosticFacts(
                    state = firstVideoOutputState.debug.state,
                    observed = false,
                    evidence = firstVideoOutputState.debug.evidence,
                ),
        )
    }

    private fun observeVolumeState() {
        volumeStateJob?.cancel()
        val volumeController = playerController as? PlayerVolumeController
        volumeControl = volumeController?.volumeState?.value
        if (volumeController == null) {
            return
        }
        volumeStateJob =
            viewModelScope.launch {
                volumeController.volumeState.collect { state ->
                    volumeControl = state
                    if (this@PlayerViewModel.state.value is PlayerUiState.Content) {
                        publishContent(playbackState = this@PlayerViewModel.playbackState.value)
                    }
                }
            }
    }

    private fun restartPlaybackStateObservation() {
        if (reportingJob == null) {
            return
        }
        reportingJob?.cancel()
        reportingJob = null
        observePlaybackState()
    }

    private suspend fun resolveBackendForSession(
        selectedVersion: MediaVersion,
        expectedGeneration: Long,
        expectedItemId: String,
    ): Boolean {
        if (backendResolvedForSession && concreteControllerInstalled && playerControllerFieldOwned) {
            return !disposed && isCurrentPlaybackLaunch(expectedGeneration, expectedItemId)
        }
        val profileProvider =
            deviceProfileProvider
                ?: run {
                    val directControllerSatisfiesBackend =
                        concreteControllerInstalled &&
                            playerControllerFieldOwned &&
                            activeControllerSatisfiesBackend(
                                resolvedBackend = backend,
                                trackedBackend = backend,
                                activeBackend = playerController.activeBackend,
                            )
                    if (directControllerSatisfiesBackend) {
                        backendResolvedForSession = true
                    }
                    return directControllerSatisfiesBackend &&
                        !disposed &&
                        isCurrentPlaybackLaunch(expectedGeneration, expectedItemId)
                }
        val itemOverride =
            getPlayerBackendOverrideUseCase?.let { getOverride ->
                val persistenceContext = playerDiagnosticContext()
                try {
                    withContext(workDispatcher) {
                        getOverride(session.serverId, currentItemId)
                    }.also { override ->
                        playerDiagnosticsRecorder.recordPersistence(
                            context = persistenceContext,
                            facts =
                                PlayerPersistenceDiagnosticFacts(
                                    event = PlaybackDiagnosticEvent.Read,
                                    target = PlaybackPersistenceTarget.BackendOverride,
                                    result =
                                        if (override == null) {
                                            PlaybackPersistenceResult.Missing
                                        } else {
                                            PlaybackPersistenceResult.Present
                                        },
                                ),
                        )
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    playerDiagnosticsRecorder.recordPersistence(
                        context = persistenceContext,
                        facts =
                            PlayerPersistenceDiagnosticFacts(
                                event = PlaybackDiagnosticEvent.Read,
                                target = PlaybackPersistenceTarget.BackendOverride,
                                result = PlaybackPersistenceResult.Failed,
                                exception = exception,
                            ),
                    )
                    null
                }
            } ?: run {
                playerDiagnosticsRecorder.recordPersistence(
                    context = playerDiagnosticContext(),
                    facts =
                        PlayerPersistenceDiagnosticFacts(
                            event = PlaybackDiagnosticEvent.Read,
                            target = PlaybackPersistenceTarget.BackendOverride,
                            result = PlaybackPersistenceResult.Unavailable,
                        ),
                )
                null
            }
        val requestedBackend =
            withContext(workDispatcher) {
                resolvePlayerBackend(
                    defaultBackend = activePlaybackPreferences.defaultPlayerBackend,
                    itemOverride = itemOverride,
                    source = selectedVersion.backendSourceDescriptor(),
                    avPlayerCapabilities = profileProvider.capabilities(PlayerBackend.AVPlayer),
                    backendPolicy = profileProvider.backendPolicy,
                )
            }
        // Android may lazily construct LibVLC while resolving backends.
        val availableBackends = withContext(workDispatcher) { profileProvider.availableBackends }
        availableBackendsForSession = availableBackends
        val resolvedBackend =
            requestedBackend.takeIf { candidate -> candidate in availableBackends }
                ?: profileProvider.backendPolicy.defaultBackend
        val backendFallback = requestedBackend != resolvedBackend
        if (
            concreteControllerInstalled &&
            playerControllerFieldOwned &&
            activeControllerSatisfiesBackend(
                resolvedBackend = resolvedBackend,
                trackedBackend = backend,
                activeBackend = playerController.activeBackend,
            )
        ) {
            backendResolvedForSession = true
            if (backendFallback && profileProvider.backendPolicy.visibleBackends.isNotEmpty()) {
                backendNotice =
                    PlayerBackendNotice(
                        token = ++backendNoticeToken,
                        requested = requestedBackend,
                        active = backend,
                    )
            }
            playerDiagnosticsRecorder.recordBackendSelection(
                context = playerDiagnosticContext(),
                facts =
                    PlayerBackendSelectionDiagnosticFacts(
                        requestedBackend = requestedBackend,
                        activeBackend = backend,
                        fallbackResult =
                            if (backendFallback) {
                                PlaybackBackendFallbackResult.Applied
                            } else {
                                PlaybackBackendFallbackResult.NotRequired
                            },
                    ),
            )
            return true
        }

        val activeBackend =
            installController(
                resolvedBackend = resolvedBackend,
                requestedBackend = requestedBackend,
                expectedGeneration = expectedGeneration,
                expectedItemId = expectedItemId,
            ) ?: return false
        playerDiagnosticsRecorder.recordBackendSelection(
            context = playerDiagnosticContext(),
            facts =
                PlayerBackendSelectionDiagnosticFacts(
                    requestedBackend = requestedBackend,
                    activeBackend = activeBackend,
                    fallbackResult =
                        if (requestedBackend != activeBackend) {
                            PlaybackBackendFallbackResult.Applied
                        } else {
                            PlaybackBackendFallbackResult.NotRequired
                        },
                ),
        )
        return true
    }

    private fun constructPlayerController(resolvedBackend: PlayerBackend): PlayerController {
        val options =
            PlayerControllerConstructionOptions(
                allowInsecureDesktopTls = activePlaybackPreferences.allowInsecureDesktopTls,
            )
        return playerControllerFactoryWithOptions?.invoke(resolvedBackend, options.allowInsecureDesktopTls)
            ?: playerControllerFactory(resolvedBackend)
    }

    /** Replaces the controller and rebinds every observer. */
    private suspend fun installController(
        resolvedBackend: PlayerBackend,
        requestedBackend: PlayerBackend,
        allowFallback: Boolean = true,
        expectedGeneration: Long = playbackLaunchGeneration,
        expectedItemId: String = currentItemId,
    ): PlayerBackend? =
        controllerInstallMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (disposed) {
                drainStopRequestedDuringControllerInstall(stopController = false)
                return@withLock null
            }
            if (!isCurrentPlaybackLaunch(expectedGeneration, expectedItemId)) {
                return@withLock null
            }
            if (stopRequestedDuringControllerInstall) {
                drainStopRequestedDuringControllerInstall(stopController = playerControllerFieldOwned)
                return@withLock null
            }

            controllerInstallInFlight = true
            val previousReportingJob = reportingJob
            val currentJob = currentCoroutineContext()[Job]
            reportingJob = null
            if (previousReportingJob != null && previousReportingJob != currentJob) {
                previousReportingJob.cancel()
            }
            runtimeDiagnosticsJob?.cancel()
            runtimeDiagnosticsJob = null
            droppedFrameMeasurementsJob?.cancel()
            droppedFrameMeasurementsJob = null
            videoOutputObservationsJob?.cancel()
            videoOutputObservationsJob = null
            playbackTransitionObservationsJob?.cancel()
            playbackTransitionObservationsJob = null
            volumeStateJob?.cancel()
            volumeStateJob = null
            val candidateOwner = ControllerCandidateOwner()
            try {
                markPlaybackHealthExclusion(PlaybackHealthExclusionReason.BackendReplacement)
                _state.value = PlayerUiState.Loading
                _playbackState.update { current -> current.copy(status = PlaybackStatus.Loading, error = null) }
                releaseOwnedPlayerController()
                val replacementController =
                    try {
                        withContext(workDispatcher) {
                            constructPlayerController(resolvedBackend).also(candidateOwner::acquire)
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        if (!allowFallback || resolvedBackend == PlayerBackend.ExoPlayer) {
                            if (!allowFallback) {
                                playerDiagnosticsRecorder.recordOfflineBackendConstructionFailure(
                                    context = playerDiagnosticContext(),
                                    facts =
                                        PlayerOfflineBackendConstructionDiagnosticFacts(
                                            requiredBackend = resolvedBackend,
                                            availability = PlaybackBackendAvailability.Unavailable,
                                            exception = exception,
                                        ),
                                )
                                throw OfflineControllerUnavailableException(resolvedBackend, exception)
                            }
                            throw exception
                        }
                        withContext(workDispatcher) {
                            constructPlayerController(PlayerBackend.ExoPlayer).also(candidateOwner::acquire)
                        }
                    }
                val candidateBackend = replacementController.activeBackend
                val activeBackend =
                    candidateBackend
                        .takeUnless { candidate -> candidate == PlayerBackend.Auto }
                        ?: resolvedBackend
                if (!allowFallback && candidateBackend != resolvedBackend) {
                    playerDiagnosticsRecorder.recordOfflineBackendConstructionFailure(
                        context = playerDiagnosticContext(),
                        facts =
                            PlayerOfflineBackendConstructionDiagnosticFacts(
                                requiredBackend = resolvedBackend,
                                availability = PlaybackBackendAvailability.Bundled,
                                exception = null,
                            ),
                    )
                    throw OfflineControllerUnavailableException(resolvedBackend, null)
                }
                val installedBackend =
                    withContext(Dispatchers.Main.immediate) {
                        currentCoroutineContext().ensureActive()
                        when {
                            disposed -> {
                                drainStopRequestedDuringControllerInstall(stopController = false)
                                null
                            }
                            !isCurrentPlaybackLaunch(expectedGeneration, expectedItemId) -> null
                            stopRequestedDuringControllerInstall -> {
                                drainStopRequestedDuringControllerInstall(stopController = false)
                                null
                            }
                            else -> {
                                candidateOwner.transferTo { installedController ->
                                    playerController = installedController
                                    playerControllerFieldOwned = true
                                    backend = activeBackend
                                    concreteControllerInstalled = true
                                    if (requestedBackend != activeBackend) {
                                        backendNotice =
                                            PlayerBackendNotice(
                                                token = ++backendNoticeToken,
                                                requested = requestedBackend,
                                                active = activeBackend,
                                            )
                                    }
                                    if (!qualitySession.isExplicitSessionChoice) {
                                        qualitySession =
                                            qualitySession.inheritLaunchOrDefault(
                                                activePlaybackPreferences.effectiveDefaultQualityPolicy(backend),
                                            )
                                    }
                                    autoRecoveryState =
                                        autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
                                }
                                activeBackend
                            }
                        }
                    } ?: return@withLock null
                backendResolvedForSession = true
                _playbackState.value = playerController.playbackState.value
                observeRuntimeDiagnostics()
                observeVolumeState()
                observeTimingState()
                observePlaybackState()
                previousReportingJob?.cancel()
                drainStopRequestedDuringControllerInstall(stopController = true)
                installedBackend
            } catch (exception: Throwable) {
                if (disposed || isCurrentPlaybackLaunch(expectedGeneration, expectedItemId)) {
                    drainStopRequestedDuringControllerInstall(stopController = false)
                }
                throw exception
            } finally {
                candidateOwner.releaseUntransferred()
                previousReportingJob?.cancel()
                controllerInstallInFlight = false
            }
        }

    private fun drainStopRequestedDuringControllerInstall(stopController: Boolean) {
        if (!stopRequestedDuringControllerInstall) return
        stopRequestedDuringControllerInstall = false
        performStop(stopController)
    }

    private fun releaseOwnedPlayerController() {
        installedPlan = null
        if (!playerControllerFieldOwned) {
            return
        }
        playerControllerFieldOwned = false
        concreteControllerInstalled = false
        backendResolvedForSession = false
        playerController.release()
    }

    private fun canOpenBackendPicker(): Boolean =
        !disposed &&
            !backendSwitchInProgress &&
            !controllerInstallInFlight &&
            !queueSwitchInFlight &&
            hasBackendSwitchTarget()

    private fun hasBackendSwitchTarget(): Boolean {
        val policy = deviceProfileProvider?.backendPolicy ?: return false
        val currentPlan = installedPlan ?: return false
        return !disposed &&
            currentPlan.streamMode != StreamMode.Offline &&
            playbackState.value.status !in backendSwitchTerminalStatuses &&
            policy.concreteBackends.any { candidate -> candidate != backend }
    }

    private fun backendSwitchChoices(): List<PlayerBackendSwitchChoice> {
        val policy = deviceProfileProvider?.backendPolicy ?: return emptyList()
        return policy.concreteBackends.map { candidate ->
            PlayerBackendSwitchChoice(
                backend = candidate,
                available = candidate in availableBackendsForSession,
            )
        }
    }

    private fun invalidateBackendSwitch() {
        backendSwitchGeneration += 1L
        if (!controllerInstallInFlight) {
            backendSwitchJob?.cancel()
        }
        backendSwitchJob = null
        if (!backendSwitchInProgress) return
        backendSwitchInProgress = false
        if (state.value is PlayerUiState.Content) {
            publishContent(
                pickerVisible =
                    currentPicker().takeUnless { picker -> picker == PlayerPicker.Backend }
                        ?: PlayerPicker.None,
            )
        }
    }

    private fun finishBackendSwitch(switch: BackendSwitchSnapshot) {
        if (backendSwitchGeneration != switch.generation) return
        backendSwitchInProgress = false
        backendSwitchJob = null
    }

    private fun isCurrentBackendSwitch(switch: BackendSwitchSnapshot): Boolean =
        !disposed &&
            backendSwitchGeneration == switch.generation &&
            playbackLaunchGeneration == switch.launchGeneration &&
            currentItemId == switch.itemId &&
            queueIdentity == switch.queueIdentity &&
            planReportingAuthority == switch.planReportingAuthority &&
            !queueSwitchInFlight

    private fun abortBackendSwitchInstallation(
        switch: BackendSwitchSnapshot,
        candidateOwner: ControllerCandidateOwner,
        replacementControllerTransferred: Boolean,
    ) {
        candidateOwner.releaseUntransferred()
        if (replacementControllerTransferred) {
            releaseOwnedPlayerController()
        }
        drainStopRequestedDuringControllerInstall(stopController = false)
        finishBackendSwitch(switch)
    }

    private suspend fun switchBackend(switch: BackendSwitchSnapshot) {
        planBackendSwitch(
            switch = switch,
            requestedBackend = switch.targetBackend,
            startPositionMs = switch.activePlan.startPositionMs,
        ) ?: run {
            keepCurrentPlaybackAfterBackendSwitchFailure(switch)
            return
        }
        if (!isCurrentBackendSwitch(switch)) return
        installBackendSwitchController(switch)
    }

    /** Plans against fresh backend facts while the current controller remains healthy and installed. */
    private suspend fun planBackendSwitch(
        switch: BackendSwitchSnapshot,
        requestedBackend: PlayerBackend,
        startPositionMs: Long,
    ): PlaybackPlan? {
        if (!isCurrentBackendSwitch(switch)) return null
        val qualityPolicy =
            if (switch.qualityExplicit) {
                switch.qualityPolicy
            } else {
                activePlaybackPreferences.effectiveDefaultQualityPolicy(requestedBackend)
            }
        val qualityCapOrigin =
            if (switch.qualityExplicit) {
                PlaybackQualityCapOrigin.ExplicitSessionChoice.takeIf {
                    qualityPolicy.mode == PlaybackQualityMode.Fixed
                }
            } else {
                PlaybackQualityCapOrigin.SettingsDefault.takeIf {
                    qualityPolicy.mode == PlaybackQualityMode.Fixed
                }
            }
        val requestPolicy =
            PlaybackInfoRequestPolicy(
                backend = requestedBackend,
                diagnosticSessionSequence = switch.launchGeneration,
            )
        val playbackPlan =
            try {
                withContext(workDispatcher) {
                    playbackInfoPlanner.plan(
                        session = session,
                        itemId = switch.itemId,
                        mediaSourceId = switch.mediaSourceId,
                        startPositionTicks = millisecondsToTicks(startPositionMs),
                        audioStreamIndex = switch.requestedAudioStreamIndex,
                        detailMediaStreams = mediaStreams,
                        subtitleSelection = switch.requestedSubtitleSelection,
                        localSubtitleAsset = switch.requestedLocalSubtitleAsset?.toPlaybackAsset(),
                        maxStreamingBitrate = qualityPolicy.maxBitrateBps,
                        qualityPolicy = qualityPolicy,
                        qualityCapOrigin = qualityCapOrigin,
                        requestPolicy = requestPolicy,
                        sourceContainer = selectedSourceContainer,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                if (isCurrentBackendSwitch(switch)) {
                    playerDiagnosticsRecorder.recordPlannerAttemptFailure(
                        context = playerDiagnosticContext(),
                        facts =
                            PlayerPlannerFailureDiagnosticFacts(
                                exception = exception,
                                requestPolicy = requestPolicy,
                                qualityPolicy = qualityPolicy,
                                qualityCapOrigin = qualityCapOrigin,
                                requestCapBitrateBps = qualityPolicy.maxBitrateBps,
                            ),
                    )
                }
                return null
            }
        if (!isCurrentBackendSwitch(switch) || !preservesBackendSwitchIntent(playbackPlan, switch)) return null
        val enriched =
            enrichPlaybackPlan(
                playbackPlan = playbackPlan,
                requestedMediaSourceId = switch.mediaSourceId,
                generation = switch.launchGeneration,
                itemId = switch.itemId,
                activationRequestId = nextSubtitleActivationRequestId(),
                selectedSubtitle = selectedSubtitleMediaStream(),
            ) ?: return null
        return enriched.copy(
            playbackSpeed = switch.playbackSpeed,
            subtitleStyle = switch.subtitleStyle,
            qualityPolicy = qualityPolicy,
            qualityCapOrigin = qualityCapOrigin,
        )
    }

    private fun preservesBackendSwitchIntent(
        playbackPlan: PlaybackPlan,
        switch: BackendSwitchSnapshot,
    ): Boolean {
        if (playbackPlan.itemId != switch.itemId || playbackPlan.mediaSourceId != switch.mediaSourceId) return false
        if (
            switch.explicitAudioStreamIndex != null &&
            playbackPlan.selectedAudioStreamIndex != switch.explicitAudioStreamIndex
        ) {
            return false
        }
        return when (val selection = switch.requestedSubtitleSelection) {
            SubtitleSelectionIntent.Unspecified -> true
            SubtitleSelectionIntent.Off -> playbackPlan.plannedSubtitle is PlannedSubtitle.Off
            is SubtitleSelectionIntent.Track ->
                (playbackPlan.plannedSubtitle as? PlannedSubtitle.Track)?.streamIndex == selection.streamIndex
            is SubtitleSelectionIntent.LocalAsset ->
                playbackPlan.subtitleAsset?.let { asset ->
                    asset is SubtitleAsset.LocalFile && asset.assetId == selection.assetId
                } == true
        }
    }

    private fun keepCurrentPlaybackAfterBackendSwitchFailure(switch: BackendSwitchSnapshot) {
        if (!isCurrentBackendSwitch(switch)) return
        finishBackendSwitch(switch)
        backendSwitchNotice = PlayerBackendSwitchNotice(token = ++backendSwitchNoticeToken)
        publishContent(pickerVisible = PlayerPicker.None)
    }

    /**
     * Commits an explicit switch only after target planning succeeds. This intentionally does not
     * share the automatic Android health-fallback path, whose ExoPlayer-only contract is unchanged.
     */
    private suspend fun installBackendSwitchController(switch: BackendSwitchSnapshot): Boolean =
        controllerInstallMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                drainStopRequestedDuringControllerInstall(stopController = playerControllerFieldOwned)
                return@withLock false
            }

            controllerInstallInFlight = true
            var previousReportingJob: Job? = null
            val currentJob = currentCoroutineContext()[Job]
            val candidateOwner = ControllerCandidateOwner()
            var releasedHealthyController = false
            var replacementControllerTransferred = false
            var transactionSettled = false
            var keptCurrentPlayback = false
            var confirmedPositionMs = 0L
            try {
                val outgoingWasPlaying = playerController.playbackState.value.status == PlaybackStatus.Playing
                if (outgoingWasPlaying) {
                    playerController.pause()
                }
                confirmedPositionMs =
                    playerController.playbackState.value.positionMs
                        .coerceAtLeast(0L)
                val targetPlan =
                    planBackendSwitch(
                        switch = switch,
                        requestedBackend = switch.targetBackend,
                        startPositionMs = confirmedPositionMs,
                    )
                if (targetPlan == null) {
                    if (isCurrentBackendSwitch(switch) && !stopRequestedDuringControllerInstall) {
                        if (outgoingWasPlaying) {
                            playerController.play()
                        }
                        keepCurrentPlaybackAfterBackendSwitchFailure(switch)
                        keptCurrentPlayback = true
                    } else {
                        drainStopRequestedDuringControllerInstall(stopController = playerControllerFieldOwned)
                    }
                    return@withLock false
                }
                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    drainStopRequestedDuringControllerInstall(stopController = playerControllerFieldOwned)
                    return@withLock false
                }

                previousReportingJob = reportingJob
                reportingJob = null
                if (previousReportingJob != null && previousReportingJob != currentJob) {
                    previousReportingJob.cancel()
                }
                runtimeDiagnosticsJob?.cancel()
                runtimeDiagnosticsJob = null
                droppedFrameMeasurementsJob?.cancel()
                droppedFrameMeasurementsJob = null
                videoOutputObservationsJob?.cancel()
                videoOutputObservationsJob = null
                playbackTransitionObservationsJob?.cancel()
                playbackTransitionObservationsJob = null
                volumeStateJob?.cancel()
                volumeStateJob = null
                markPlaybackHealthExclusion(PlaybackHealthExclusionReason.BackendReplacement)
                _state.value = PlayerUiState.Loading
                _playbackState.update { current -> current.copy(status = PlaybackStatus.Loading, error = null) }
                releaseOwnedPlayerController()
                releasedHealthyController = true

                val replacementController =
                    try {
                        withContext(workDispatcher) {
                            constructPlayerController(switch.targetBackend).also(candidateOwner::acquire)
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Throwable) {
                        if (switch.targetBackend == switch.defaultBackend) throw BackendSwitchInstallationException()
                        withContext(workDispatcher) {
                            constructPlayerController(switch.defaultBackend).also(candidateOwner::acquire)
                        }
                    }
                val actualBackend = replacementController.activeBackend
                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }
                if (actualBackend == PlayerBackend.Auto) {
                    throw BackendSwitchInstallationException()
                }

                val actualPlan =
                    if (actualBackend == switch.targetBackend) {
                        targetPlan
                    } else {
                        planBackendSwitch(
                            switch = switch,
                            requestedBackend = actualBackend,
                            startPositionMs = confirmedPositionMs,
                        )
                    }
                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }
                val replacementPlan = actualPlan ?: throw BackendSwitchInstallationException()
                val installed =
                    withContext(Dispatchers.Main.immediate) {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                            null
                        } else {
                            candidateOwner.transferTo { controller ->
                                playerController = controller
                                playerControllerFieldOwned = true
                                replacementControllerTransferred = true
                                backend = actualBackend
                                concreteControllerInstalled = true
                                backendResolvedForSession = true
                                if (actualBackend != switch.targetBackend) {
                                    backendNotice =
                                        PlayerBackendNotice(
                                            token = ++backendNoticeToken,
                                            requested = switch.targetBackend,
                                            active = actualBackend,
                                        )
                                }
                                qualitySession =
                                    if (switch.qualityExplicit) {
                                        qualitySession.preserveExplicitBackendSwitch(
                                            policy = replacementPlan.qualityPolicy,
                                            capOrigin = replacementPlan.qualityCapOrigin,
                                        )
                                    } else {
                                        qualitySession.adoptInheritedBackendSwitch(
                                            policy = replacementPlan.qualityPolicy,
                                            capOrigin = replacementPlan.qualityCapOrigin,
                                        )
                                    }
                                resizeMode = switch.resizeMode
                                autoRecoveryState =
                                    autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, actualBackend)
                            }
                            actualBackend
                        }
                    }
                if (installed == null) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }

                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }
                _playbackState.value = playerController.playbackState.value
                observeRuntimeDiagnostics()
                observeVolumeState()
                observeTimingState()
                observePlaybackState()
                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }
                playbackReportingCoordinator.stopNow(positionMs = confirmedPositionMs)
                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }
                installPlan(replacementPlan, resetReporting = true, stabilizesQueueSwitch = false)
                switch.planReportingAuthority = planReportingAuthority
                loadTimingOffsets()
                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }
                playbackLaunchMarker = null
                markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Prepare)
                playerDiagnosticsRecorder.recordPrepareRequested(playerDiagnosticContext())
                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }
                playerController.prepare(replacementPlan)
                if (rejectSynchronouslyFailedPrepare()) {
                    finishBackendSwitch(switch)
                    drainStopRequestedDuringControllerInstall(stopController = true)
                    transactionSettled = true
                    return@withLock false
                }
                installedPlan = replacementPlan
                playerController.runtimeDiagnostics.value.prepareEpoch
                    ?.let(playbackHealthCoordinator::expectVideoOutput)
                armFirstVideoOutputState()
                playerDiagnosticsRecorder.recordPrepareDispatched(playerDiagnosticContext())
                applyInitialEmbeddedSelections(replacementPlan)
                if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                    return@withLock false
                }
                if (switch.wasPaused) {
                    desiredPlayWhenReady = false
                    playerController.pause()
                } else {
                    desiredPlayWhenReady = true
                    playerController.play()
                }
                finishBackendSwitch(switch)
                publishContent(pickerVisible = PlayerPicker.None)
                maybeStartInstalledAudioUnavailableFallback(replacementPlan)
                maybeStartInstalledUnavailableFallback(replacementPlan)
                previousReportingJob?.cancel()
                drainStopRequestedDuringControllerInstall(stopController = true)
                transactionSettled = true
                installed == actualBackend
            } catch (exception: CancellationException) {
                if (releasedHealthyController) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                    transactionSettled = true
                }
                throw exception
            } catch (_: Throwable) {
                if (releasedHealthyController) {
                    if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                        abortBackendSwitchInstallation(
                            switch = switch,
                            candidateOwner = candidateOwner,
                            replacementControllerTransferred = replacementControllerTransferred,
                        )
                    } else {
                        playbackReportingCoordinator.stopNow(positionMs = confirmedPositionMs)
                        if (!isCurrentBackendSwitch(switch) || stopRequestedDuringControllerInstall) {
                            abortBackendSwitchInstallation(
                                switch = switch,
                                candidateOwner = candidateOwner,
                                replacementControllerTransferred = replacementControllerTransferred,
                            )
                        } else {
                            finishBackendSwitch(switch)
                            drainStopRequestedDuringControllerInstall(stopController = true)
                            _state.value = PlayerUiState.Error(error = PlaybackError.Unknown)
                            playerDiagnosticsRecorder.recordTerminalOutcome(
                                context = playerDiagnosticContext(),
                                facts =
                                    PlayerTerminalDiagnosticFacts(
                                        outcome = PlaybackTerminalOutcome.Failed,
                                        error = PlaybackError.Unknown,
                                        autoRecoveryTrigger = null,
                                        recoveryDecision = null,
                                    ),
                            )
                        }
                    }
                    transactionSettled = true
                }
                false
            } finally {
                candidateOwner.releaseUntransferred()
                if (releasedHealthyController && !transactionSettled) {
                    abortBackendSwitchInstallation(
                        switch = switch,
                        candidateOwner = candidateOwner,
                        replacementControllerTransferred = replacementControllerTransferred,
                    )
                }
                previousReportingJob?.cancel()
                controllerInstallInFlight = false
                if (keptCurrentPlayback) {
                    publishContent(pickerVisible = PlayerPicker.None)
                }
            }
        }

    private fun MediaVersion.backendSourceDescriptor(): BackendSourceDescriptor {
        val videoStream = mediaStreams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
        val audioStream = mediaStreams.firstOrNull { stream -> stream.type.equals("Audio", ignoreCase = true) }
        return BackendSourceDescriptor(
            container = container,
            videoCodec = videoStream?.codec,
            audioCodec = audioStream?.codec,
            isHdrOrDolbyVision = videoStream?.videoRangeType.isHdrOrDolbyVision(),
        )
    }

    private fun String?.isHdrOrDolbyVision(): Boolean {
        val rangeType = this?.trim()?.uppercase() ?: return false
        return rangeType.contains("HDR") ||
            rangeType.contains("DOLBY") ||
            rangeType == "DOVI" ||
            rangeType == "DV" ||
            rangeType == "HLG"
    }

    private suspend fun handlePlaybackState(playbackState: PlaybackState) {
        if (controllerInstallInFlight) return
        val enrichedPlaybackState = playbackState.withPlaybackMetadata()
        _playbackState.value = enrichedPlaybackState
        val installedAudioChanged = updateInstalledAudio(enrichedPlaybackState)
        recordTrackDiagnostics(enrichedPlaybackState)
        playbackHealthCoordinator.observePlaybackState(enrichedPlaybackState)
        if (enrichedPlaybackState.status == PlaybackStatus.Failed) {
            captureControllerFailure()
        }

        val completed = enrichedPlaybackState.status == PlaybackStatus.Completed
        val hasNextQueueItem = currentQueueIndex < queueIds.lastIndex
        if (completed && hasNextQueueItem) {
            playbackReportingCoordinator.onPlaybackState(enrichedPlaybackState, lastStatus)
            if (lastStatus != PlaybackStatus.Completed) {
                playbackReportingCoordinator.stop(enrichedPlaybackState.positionMs, completed = true)
            }
            // Keep the completed item installed while Up Next owns the countdown.
            publishContent(playbackState = enrichedPlaybackState)
            lastStatus = enrichedPlaybackState.status
            return
        }

        val currentContent = state.value as? PlayerUiState.Content
        val currentPlaybackState = currentContent?.playbackState
        val currentAudioUnavailable = currentContent?.audioUnavailable
        val currentUpNext = currentContent?.upNext
        val upNext = upNextFor(enrichedPlaybackState)
        val shouldPublishPlaybackState =
            currentPlaybackState == null ||
                currentPlaybackState.status != enrichedPlaybackState.status ||
                currentPlaybackState.currentSegment != enrichedPlaybackState.currentSegment ||
                currentPlaybackState.audioActivation != enrichedPlaybackState.audioActivation ||
                currentPlaybackState.subtitleActivation != enrichedPlaybackState.subtitleActivation ||
                currentAudioUnavailable != enrichedPlaybackState.audioUnavailable ||
                currentUpNext != upNext
        val terminalRecovery = maybeHandlePlaybackSessionRecovery(enrichedPlaybackState)
        if (terminalRecovery != null) {
            terminalRecovery.outcome?.let { outcome ->
                playerDiagnosticsRecorder.recordTerminalOutcome(
                    context = playerDiagnosticContext(),
                    facts =
                        PlayerTerminalDiagnosticFacts(
                            outcome = outcome,
                            error = enrichedPlaybackState.error,
                            autoRecoveryTrigger = terminalRecovery.autoRecoveryTrigger,
                            recoveryDecision = terminalRecovery.recoveryDecision,
                        ),
                )
            }
            lastStatus = enrichedPlaybackState.status
            return
        }
        if (enrichedPlaybackState.status == PlaybackStatus.Playing) {
            maybeCompleteRecoveredPlaybackGuidance()
        }

        if (enrichedPlaybackState.status == PlaybackStatus.Failed) {
            _state.update { PlayerUiState.Error(error = enrichedPlaybackState.error) }
        } else if (shouldPublishPlaybackState || installedAudioChanged) {
            publishContent(playbackState = enrichedPlaybackState)
        }

        playbackReportingCoordinator.onPlaybackState(enrichedPlaybackState, lastStatus)
        // Report Start before an auto-skip TimeUpdate.
        maybeAutoSkipSegment(enrichedPlaybackState)

        if (completed) {
            playbackReportingCoordinator.stop(enrichedPlaybackState.positionMs, completed = true)
            if (lastStatus != PlaybackStatus.Completed) {
                playbackEndedEvents.send(Unit)
            }
        }
        if (enrichedPlaybackState.status == PlaybackStatus.Failed) {
            playerDiagnosticsRecorder.recordTerminalOutcome(
                context = playerDiagnosticContext(),
                facts =
                    PlayerTerminalDiagnosticFacts(
                        outcome = PlaybackTerminalOutcome.Failed,
                        error = enrichedPlaybackState.error,
                        autoRecoveryTrigger = null,
                        recoveryDecision = null,
                    ),
            )
            playbackReportingCoordinator.stop(enrichedPlaybackState.positionMs)
        }

        lastStatus = enrichedPlaybackState.status
    }

    private suspend fun captureControllerFailure() {
        val diagnosticsContext = playbackDiagnosticsContext ?: return
        val capabilities =
            try {
                withContext(workDispatcher) {
                    deviceProfileProvider?.capabilities(backend)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                null
            }
        diagnosticsContext.recordFailure(
            backend = backend,
            capabilities = capabilities,
            source =
                mediaStreams.toDiagnosticsSourceDescriptor(
                    container = selectedSourceContainer ?: plan?.container,
                    selectedAudioStreamIndex = installedAudioStreamIndex ?: requestedAudioStreamIndex,
                ),
        )
    }

    private fun maybeCompleteRecoveredPlaybackGuidance() {
        pendingRecoveredPlaybackGuidanceItemId?.let { pendingItemId ->
            pendingRecoveredPlaybackGuidanceItemId = null
            if (pendingItemId == currentItemId) {
                playbackHealthCoordinator.recordRecoveredPlaybackFailure()
            }
        }
        val recoveredQuality = pendingRecoveredAutoQualityBps ?: return
        pendingRecoveredAutoQualityBps = null
        setPlaybackActionNotice(
            PlaybackActionNotice(
                reason = PlaybackActionNoticeReason.QualityRecoveryApplied,
                runtimeQualityCapBps = recoveredQuality,
                actions =
                    setOf(
                        PlaybackAction.KeepCurrentQuality,
                        PlaybackAction.TryHigherQuality,
                        PlaybackAction.ChooseLowerQuality,
                        PlaybackAction.Dismiss,
                    ),
            ),
        )
        publishContent()
    }

    private fun resetPlaybackHealthSession(
        generation: Long = playbackLaunchGeneration,
        launchAtMs: Long = monotonicTimeMs(),
    ) {
        playbackHealthCoordinator.start(
            generation = generation,
            context = playbackHealthSessionContext(),
            startedAtMs = launchAtMs,
        )
        lastHealthSignal = null
        lastHealthThresholdClass = null
    }

    private fun endPlaybackHealthSession() {
        playbackLaunchGeneration += 1L
        playbackHealthCoordinator.end()
    }

    private fun markPlaybackHealthExclusion(reason: PlaybackHealthExclusionReason) {
        playbackHealthCoordinator.markExclusion(reason)
    }

    private fun restartPlaybackHealthEvidence(reason: PlaybackHealthExclusionReason = PlaybackHealthExclusionReason.Resume) {
        playbackHealthCoordinator.restartEvidenceWindow(reason)
    }

    private fun emitPlaybackHealthSummary() {
        playbackHealthCoordinator.end()
    }

    private fun playbackHealthSessionContext(): PlaybackHealthSessionContext =
        PlaybackHealthSessionContext(
            sessionToken = playbackLaunchGeneration,
            streamMode = plan?.streamMode,
            guidancePolicy =
                if (activePlaybackPreferences.playbackWarningsEnabled) {
                    playbackHealthGuidancePolicy
                } else {
                    PlaybackHealthGuidancePolicy.Disabled
                },
            nextLowerQualityRungBps = nextLowerQualityRung(),
            isOffline = plan?.streamMode == StreamMode.Offline,
            videoExpected = plan?.videoExpected ?: false,
            backend = backend,
        )

    private fun handleAutomaticRecoverySignal(signal: PlaybackHealthSignal) {
        val trigger =
            when (signal.kind) {
                PlaybackHealthSignalKind.NoVideoOutput -> AutoPlaybackRecoveryTrigger.NoVideoOutput
                PlaybackHealthSignalKind.CumulativeBuffering -> AutoPlaybackRecoveryTrigger.CumulativeBuffering
                PlaybackHealthSignalKind.RepeatedStalls -> AutoPlaybackRecoveryTrigger.RepeatedStalls
                PlaybackHealthSignalKind.DroppedFrames -> AutoPlaybackRecoveryTrigger.DroppedFrames
                PlaybackHealthSignalKind.SlowStartup,
                PlaybackHealthSignalKind.LongBuffering,
                -> return
            }
        handleAutomaticRecoveryTrigger(trigger)
    }

    private fun handleAutomaticRecoveryTrigger(trigger: AutoPlaybackRecoveryTrigger): AutomaticRecoveryDiagnostic? {
        if (plan?.streamMode == StreamMode.Offline) return null
        if (pictureInPictureMode) {
            pendingPictureInPictureRecoveryTrigger =
                strongestPendingRecoveryTrigger(pendingPictureInPictureRecoveryTrigger, trigger)
            return null
        }
        val currentPlan = plan ?: return null
        val previousState = autoRecoveryState
        val result =
            autoRecoveryCoordinator.decide(
                AutoPlaybackRecoveryInput(
                    generation = playbackLaunchGeneration,
                    policy = qualitySession.policy,
                    qualityRecoveryAuthorization = autoQualityRecoveryAuthorization(),
                    backend = backend,
                    plan = currentPlan,
                    sourceBitrateBps = currentSourceBitrate(),
                    lowerQualityRungsBps = qualityOptions(currentSourceBitrate()).mapNotNull { option -> option.maxBitrateBps },
                    state = autoRecoveryState,
                    trigger = trigger,
                ),
            )
        autoRecoveryState = result.state
        playerDiagnosticsRecorder.recordAutomaticRecoveryDecision(
            context = playerDiagnosticContext(currentPlan = currentPlan),
            facts =
                PlayerAutomaticRecoveryDiagnosticFacts(
                    trigger = trigger,
                    previousState = previousState,
                    result = result,
                ),
        )
        val recoveryDecision = result.decision.toPlaybackRecoveryDecision()
        val scheduled = applyAutomaticRecoveryDecision(result.decision)
        return AutomaticRecoveryDiagnostic(
            trigger = trigger,
            decision = recoveryDecision,
            scheduled = scheduled,
        )
    }

    private fun applyAutomaticRecoveryDecision(decision: AutoPlaybackRecoveryDecision): Boolean {
        if (plan?.streamMode == StreamMode.Offline) return false
        when (decision) {
            AutoPlaybackRecoveryDecision.NoAction -> return false
            is AutoPlaybackRecoveryDecision.CompatibilityReplan -> {
                playbackHealthCoordinator.dismissGuidance()
                pendingRecoveredPlaybackGuidanceItemId = currentItemId
                replanAtPosition(
                    targetPositionMs = playerController.playbackState.value.positionMs,
                    requestPolicy =
                        PlaybackInfoRequestPolicy(
                            enableDirectPlay = false,
                            enableDirectStream = false,
                            allowVideoStreamCopy = false,
                            clientTrigger = PlaybackClientTrigger.PlayerFailureFallback,
                            bitrateConstraint = activePlaybackBitrateConstraint(),
                            recoveryIntent = com.jellyscope.core.domain.playback.PlaybackRecoveryIntent.Compatibility,
                        ),
                    forcePlay = true,
                )
                return true
            }
            is AutoPlaybackRecoveryDecision.LowerTo -> {
                playbackHealthCoordinator.dismissGuidance()
                qualitySession = qualitySession.recoverWithAutoCap(decision.maxBitrateBps)
                pendingRecoveredAutoQualityBps = decision.maxBitrateBps
                replanAtPosition(
                    targetPositionMs = playerController.playbackState.value.positionMs,
                    requestPolicy =
                        PlaybackInfoRequestPolicy(
                            clientTrigger = PlaybackClientTrigger.PlayerFailureFallback,
                            bitrateConstraint = PlaybackBitrateConstraint.AutoSessionLimit(decision.maxBitrateBps),
                            recoveryIntent = com.jellyscope.core.domain.playback.PlaybackRecoveryIntent.Quality,
                        ),
                    forcePlay = true,
                )
                return true
            }
            is AutoPlaybackRecoveryDecision.PromptUser -> {
                playbackHealthCoordinator.dismissGuidance()
                // Recovery exhaustion invalidates the lower-quality replan.
                pendingRecoveredAutoQualityBps = null
                setPlaybackActionNotice(
                    PlaybackActionNotice(
                        reason = decision.reason.toNoticeReason(),
                        actions = decision.actions,
                        runtimeQualityCapBps = autoRecoveryState.runtimeQualityCapBps,
                    ),
                )
                publishContent()
                return false
            }
        }
    }

    private fun AutoPlaybackRecoveryDecision.toPlaybackRecoveryDecision(): PlaybackRecoveryDecision =
        when (this) {
            AutoPlaybackRecoveryDecision.NoAction -> PlaybackRecoveryDecision.NoAction
            is AutoPlaybackRecoveryDecision.CompatibilityReplan -> PlaybackRecoveryDecision.CompatibilityReplan
            is AutoPlaybackRecoveryDecision.LowerTo -> PlaybackRecoveryDecision.LowerQuality
            is AutoPlaybackRecoveryDecision.PromptUser -> PlaybackRecoveryDecision.PromptUser
        }

    private fun AutoPlaybackRecoveryPromptReason.toNoticeReason(): PlaybackActionNoticeReason =
        when (this) {
            AutoPlaybackRecoveryPromptReason.OriginalPlaybackFailed ->
                PlaybackActionNoticeReason.OriginalPlaybackFailed
            AutoPlaybackRecoveryPromptReason.FixedQualityFailed ->
                PlaybackActionNoticeReason.FixedQualityFailed
            AutoPlaybackRecoveryPromptReason.CompatibilityRecoveryExhausted ->
                PlaybackActionNoticeReason.CompatibilityRecoveryExhausted
            AutoPlaybackRecoveryPromptReason.QualityRecoveryExhausted,
            AutoPlaybackRecoveryPromptReason.NoLowerQualityAvailable,
            AutoPlaybackRecoveryPromptReason.AutoQualityRecoveryRequiresExplicitSessionChoice,
            -> PlaybackActionNoticeReason.NoLowerQualityAvailable
        }

    private fun autoQualityRecoveryAuthorization(): AutoPlaybackQualityRecoveryAuthorization =
        if (qualitySession.isExplicitSessionChoice && qualitySession.policy.mode == PlaybackQualityMode.Auto) {
            AutoPlaybackQualityRecoveryAuthorization.ExplicitSessionAuto
        } else {
            AutoPlaybackQualityRecoveryAuthorization.NotAuthorized
        }

    private fun updatePlaybackHealthSessionContext() {
        playbackHealthCoordinator.updateContext(playbackHealthSessionContext())
    }

    private fun activePlaybackBitrateConstraint(): PlaybackBitrateConstraint =
        if (qualitySession.policy.mode == PlaybackQualityMode.Auto) {
            autoRecoveryState.runtimeQualityCapBps
                ?.let(PlaybackBitrateConstraint.AutoSessionLimit::of)
                ?: PlaybackBitrateConstraint.NoClientLimit
        } else {
            qualitySession.policy.toBitrateConstraint()
        }

    // A lower rung must reduce both the active cap and the source bitrate.
    private fun nextLowerQualityRung(): Long? {
        val sourceBitrate = currentSourceBitrate()
        val currentCap = qualitySession.maximumBitrateBps ?: Long.MAX_VALUE
        return qualityOptions(sourceBitrate)
            .mapNotNull { option -> option.maxBitrateBps }
            .filter { rungBps ->
                rungBps < currentCap && (sourceBitrate == null || rungBps < sourceBitrate)
            }.maxOrNull()
    }

    private suspend fun maybeHandlePlaybackSessionRecovery(playbackState: PlaybackState): PlaybackTerminalRecovery? {
        // Offline failures never enter remote or alternate-backend recovery.
        if (plan?.streamMode == StreamMode.Offline) return null
        val decision = nextPlaybackSessionRecoveryDecision(playbackState)
        if (decision is PlaybackSessionRecoveryDecision.AutomaticRecovery) {
            if (
                (backend == PlayerBackend.LibVlc || backend == PlayerBackend.Mpv) &&
                !alternateBackendFallbackAttempted
            ) {
                val recovered = fallbackFromAndroidAlternateBackend(playbackState.positionMs)
                if (recovered) {
                    pendingRecoveredPlaybackGuidanceItemId = currentItemId
                }
                return PlaybackTerminalRecovery(PlaybackTerminalOutcome.RetryScheduled).takeIf { recovered }
            }
            val automaticRecovery = handleAutomaticRecoveryTrigger(decision.trigger)
            return PlaybackTerminalRecovery(
                outcome =
                    if (automaticRecovery?.scheduled == true) {
                        PlaybackTerminalOutcome.RetryScheduled
                    } else {
                        PlaybackTerminalOutcome.Failed
                    },
                autoRecoveryTrigger = automaticRecovery?.trigger,
                recoveryDecision = automaticRecovery?.decision,
            )
        }
        if (decision is PlaybackSessionRecoveryDecision.SubtitleUnavailable) {
            executePlaybackSessionRecovery(decision, playbackState)
            return PlaybackTerminalRecovery(outcome = null)
        }
        return PlaybackTerminalRecovery(PlaybackTerminalOutcome.RetryScheduled).takeIf {
            executePlaybackSessionRecovery(decision, playbackState)
        }
    }

    private fun maybeHandleImmediatePlaybackSessionRecovery(playbackState: PlaybackState): Boolean {
        if (plan?.streamMode == StreamMode.Offline) return false
        return executePlaybackSessionRecovery(nextPlaybackSessionRecoveryDecision(playbackState), playbackState)
    }

    private fun nextPlaybackSessionRecoveryDecision(playbackState: PlaybackState): PlaybackSessionRecoveryDecision {
        val result =
            playbackSessionRecoveryPolicy.decide(
                PlaybackSessionRecoveryInput(
                    generation = playbackLaunchGeneration,
                    itemId = currentItemId,
                    plan = plan,
                    playbackState = playbackState,
                    state = playbackSessionRecoveryState,
                ),
            )
        playbackSessionRecoveryState = result.state
        return result.decision
    }

    private fun executePlaybackSessionRecovery(
        decision: PlaybackSessionRecoveryDecision,
        playbackState: PlaybackState,
    ): Boolean =
        when (decision) {
            PlaybackSessionRecoveryDecision.NoAction,
            is PlaybackSessionRecoveryDecision.AutomaticRecovery,
            -> false

            is PlaybackSessionRecoveryDecision.NetworkRetry -> {
                playerDiagnosticsRecorder.recordDiagnostic(
                    context = playerDiagnosticContext(),
                    facts =
                        PlayerDiagnosticFacts(
                            stage = PlaybackDiagnosticStage.NativePlayer,
                            event = PlaybackDiagnosticEvent.Fallback,
                            streamMode = plan?.streamMode,
                        ),
                )
                replanAtPosition(
                    targetPositionMs = playbackState.positionMs,
                    requestPolicy = decision.requestPolicy,
                    forcePlay = true,
                )
                true
            }

            is PlaybackSessionRecoveryDecision.AudioActivationRecovery -> {
                audioRecoveryTarget = decision.target
                replanAtPosition(
                    targetPositionMs = playbackState.positionMs,
                    requestPolicy = decision.requestPolicy,
                    audioRecovery = decision.target,
                )
                true
            }

            is PlaybackSessionRecoveryDecision.SubtitleEncodeRecovery -> {
                subtitleFallbackTarget = decision.target
                val fallbackGeneration = subtitleFallbackGeneration
                playerController.selectEmbeddedSubtitle(null)
                subtitleNotice =
                    PlayerNotice(
                        token = ++subtitleNoticeToken,
                        message = "Local subtitles unavailable; switching to server-rendered subtitles.",
                    )
                publishContent(playbackState = playbackState)
                replanAtPosition(
                    targetPositionMs = playbackState.positionMs,
                    requestPolicy = decision.requestPolicy,
                    nonFatalSubtitleFallback = decision.target,
                    subtitleFallbackGeneration = fallbackGeneration,
                )
                true
            }

            is PlaybackSessionRecoveryDecision.SubtitleUnavailable -> {
                if (plan?.plannedSubtitle is PlannedSubtitle.LocalAsset) {
                    playerDiagnosticsRecorder.recordDiagnostic(
                        context = playerDiagnosticContext(),
                        facts =
                            PlayerDiagnosticFacts(
                                stage = PlaybackDiagnosticStage.Mapping,
                                event = PlaybackDiagnosticEvent.Failed,
                                trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                            ),
                    )
                    playerController.selectEmbeddedSubtitle(null)
                    subtitleNotice =
                        PlayerNotice(
                            token = ++subtitleNoticeToken,
                            message = "Local subtitle unavailable.",
                        )
                    publishContent(playbackState = playbackState)
                } else {
                    markSubtitleFallbackUnavailable(decision.target)
                }
                true
            }
        }

    private suspend fun fallbackFromAndroidAlternateBackend(positionMs: Long): Boolean {
        if (plan?.streamMode == StreamMode.Offline) return false
        val profileProvider = deviceProfileProvider ?: return false
        if (profileProvider.backendPolicy.platform != PlayerBackendPlatform.Android) return false
        if (PlayerBackend.ExoPlayer !in profileProvider.availableBackends) return false
        alternateBackendFallbackAttempted = true
        val lastConfirmedPositionMs = positionMs.coerceAtLeast(0L)
        val requestedBackend = backend
        playerDiagnosticsRecorder.recordDiagnostic(
            context = playerDiagnosticContext(),
            facts =
                PlayerDiagnosticFacts(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.Fallback,
                    streamMode = plan?.streamMode,
                    requestPolicy =
                        PlaybackInfoRequestPolicy(
                            backend = PlayerBackend.ExoPlayer,
                            clientTrigger = PlaybackClientTrigger.BackendFallback,
                        ),
                ),
        )
        val activeBackend =
            installController(
                resolvedBackend = PlayerBackend.ExoPlayer,
                requestedBackend = requestedBackend,
                expectedGeneration = playbackLaunchGeneration,
                expectedItemId = currentItemId,
            ) ?: return false
        replanAtPosition(
            targetPositionMs = lastConfirmedPositionMs,
            requestPolicy =
                PlaybackInfoRequestPolicy(
                    backend = PlayerBackend.ExoPlayer,
                    clientTrigger = PlaybackClientTrigger.BackendFallback,
                ),
            forcePlay = true,
        )
        playerDiagnosticsRecorder.recordBackendSelection(
            context = playerDiagnosticContext(),
            facts =
                PlayerBackendSelectionDiagnosticFacts(
                    requestedBackend = requestedBackend,
                    activeBackend = activeBackend,
                    fallbackResult = PlaybackBackendFallbackResult.Applied,
                ),
        )
        return true
    }

    private fun updateInstalledAudio(playbackState: PlaybackState): Boolean {
        val activeTarget = (playbackState.audioActivation as? AudioActivationState.Active)?.target ?: return false
        val currentPlan = plan ?: return false
        if (currentPlan.audioActivationTarget != activeTarget || activeTarget.itemId != currentItemId) return false
        if (installedAudioStreamIndex == activeTarget.streamIndex) return false
        installedAudioStreamIndex = activeTarget.streamIndex
        audioRecoveryTarget = null
        if (currentPlan.audioSelectionAuthoritative && requestedAudioStreamIndex != activeTarget.streamIndex) {
            requestedAudioStreamIndex = activeTarget.streamIndex
            // Runtime substitutions never replace durable audio intent.
            rememberSelection(persistDurable = false)
        }
        return true
    }

    private fun applyInitialEmbeddedSelections(playbackPlan: PlaybackPlan) {
        if (playbackPlan.streamMode == StreamMode.DirectPlay) {
            val target = playbackPlan.audioActivationTarget
            val descriptor =
                playbackPlan.embeddedAudioTracks.firstOrNull { track ->
                    track.jellyfinStreamIndex == playbackPlan.selectedAudioStreamIndex
                }
            if (target != null && descriptor != null) {
                playerController.selectEmbeddedAudio(
                    EmbeddedAudioSelection(target = target, descriptor = descriptor),
                )
            }
        }

        val plannedSubtitle = playbackPlan.plannedSubtitle
        if (plannedSubtitle is PlannedSubtitle.Off || plannedSubtitle is PlannedSubtitle.Unavailable) {
            playerController.selectEmbeddedSubtitle(null)
            return
        }
        if (plannedSubtitle is PlannedSubtitle.LocalAsset) {
            return
        }
        plannedSubtitle as PlannedSubtitle.Track
        if (plannedSubtitle.deliveryMethod in localEmbeddedDeliveryMethods) {
            val target = plannedSubtitle.activationTarget ?: return
            playerController.selectEmbeddedSubtitle(
                EmbeddedSubtitleSelection(
                    target = target,
                    descriptor = plannedSubtitle.embeddedTrack ?: return,
                ),
            )
        }
    }

    private fun replanAtCurrentPosition() {
        replanAtPosition(playerController.playbackState.value.positionMs)
    }

    // Fetch fresh PlaybackInfo and re-prepare at the target position.
    private fun replanAtPosition(
        targetPositionMs: Long,
        requestPolicy: PlaybackInfoRequestPolicy = PlaybackInfoRequestPolicy(),
        forcePlay: Boolean = false,
        nonFatalSubtitleFallback: SubtitleActivationTarget? = null,
        subtitleFallbackGeneration: Long? = null,
        audioRecovery: AudioActivationTarget? = null,
    ) {
        invalidateBackendSwitch()
        if (controllerInstallInFlight) return
        // Offline plans never enter the remote replan path.
        if (plan?.streamMode == StreamMode.Offline) return
        val mediaSource = selectedMediaSourceId ?: return
        autoplayGeneration += 1
        val target = targetPositionMs.coerceAtLeast(0L)
        val effectiveRequestPolicy =
            requestPolicy.copy(
                backend = backend,
                bitrateConstraint =
                    requestPolicy.bitrateConstraint.takeUnless { constraint ->
                        constraint == PlaybackBitrateConstraint.NoClientLimit
                    } ?: activePlaybackBitrateConstraint(),
            )
        val selectedSubtitle = selectedSubtitleMediaStream()
        val replanItemId = currentItemId
        val replanGeneration = playbackLaunchGeneration
        markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Replan)
        if (nonFatalSubtitleFallback == null) {
            invalidateSubtitleFallback()
        }
        val activationRequestId = nextSubtitleActivationRequestId()
        replanJob?.cancel()
        replanJob =
            viewModelScope.launch {
                if (controllerInstallInFlight) return@launch
                val playbackState = playerController.playbackState.value
                val wasPaused = playbackState.status == PlaybackStatus.Paused
                if (forcePlay) {
                    _state.value = PlayerUiState.Loading
                }
                val correlatedRequestPolicy =
                    effectiveRequestPolicy.copy(
                        diagnosticSessionSequence = replanGeneration,
                    )

                val playbackPlan =
                    try {
                        withContext(workDispatcher) {
                            playbackInfoPlanner.plan(
                                session = session,
                                itemId = replanItemId,
                                mediaSourceId = mediaSource,
                                startPositionTicks = millisecondsToTicks(target),
                                audioStreamIndex = requestedAudioStreamIndex,
                                detailMediaStreams = mediaStreams,
                                subtitleSelection = requestedSubtitleSelection,
                                localSubtitleAsset = requestedLocalSubtitleAsset?.toPlaybackAsset(),
                                maxStreamingBitrate = qualitySession.maximumBitrateBps,
                                qualityPolicy = qualitySession.policy,
                                qualityCapOrigin = qualitySession.capOrigin,
                                requestPolicy = correlatedRequestPolicy,
                                // Preserve the known container in fallback diagnostics.
                                sourceContainer = selectedSourceContainer,
                            )
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        if (!isCurrentPlaybackLaunch(replanGeneration, replanItemId)) return@launch
                        playerDiagnosticsRecorder.recordPlannerAttemptFailure(
                            context = playerDiagnosticContext(),
                            facts =
                                PlayerPlannerFailureDiagnosticFacts(
                                    exception = exception,
                                    requestPolicy = correlatedRequestPolicy,
                                    qualityPolicy = qualitySession.policy,
                                    qualityCapOrigin = qualitySession.capOrigin,
                                    requestCapBitrateBps = qualitySession.maximumBitrateBps,
                                ),
                        )
                        if (nonFatalSubtitleFallback != null) {
                            if (
                                isCurrentSubtitleFallback(
                                    target = nonFatalSubtitleFallback,
                                    generation = subtitleFallbackGeneration,
                                    itemId = replanItemId,
                                )
                            ) {
                                markSubtitleFallbackUnavailable(nonFatalSubtitleFallback)
                            }
                        } else if (audioRecovery != null) {
                            if (audioRecoveryTarget == audioRecovery) {
                                _state.update { PlayerUiState.Error(error = PlaybackError.UnsupportedMedia) }
                            }
                        } else {
                            _state.update { PlayerUiState.Error(error = PlaybackError.Network) }
                        }
                        playerDiagnosticsRecorder.recordTerminalOutcome(
                            context = playerDiagnosticContext(),
                            facts =
                                PlayerTerminalDiagnosticFacts(
                                    outcome = PlaybackTerminalOutcome.Failed,
                                    error =
                                        if (audioRecovery != null) {
                                            PlaybackError.UnsupportedMedia
                                        } else {
                                            PlaybackError.Network
                                        },
                                    autoRecoveryTrigger = null,
                                    recoveryDecision = null,
                                ),
                        )
                        return@launch
                    }

                if (!isCurrentPlaybackLaunch(replanGeneration, replanItemId)) return@launch

                if (controllerInstallInFlight) return@launch

                if (nonFatalSubtitleFallback != null) {
                    if (
                        !isCurrentSubtitleFallback(
                            target = nonFatalSubtitleFallback,
                            generation = subtitleFallbackGeneration,
                            itemId = replanItemId,
                        )
                    ) {
                        return@launch
                    }
                    val fallbackTrack = playbackPlan.plannedSubtitle as? PlannedSubtitle.Track
                    if (
                        fallbackTrack?.deliveryMethod != SubtitleDeliveryMethod.Encode ||
                        fallbackTrack.streamIndex != nonFatalSubtitleFallback.streamIndex
                    ) {
                        playerDiagnosticsRecorder.recordDiagnostic(
                            context = playerDiagnosticContext(),
                            facts =
                                PlayerDiagnosticFacts(
                                    stage = PlaybackDiagnosticStage.Mapping,
                                    event = PlaybackDiagnosticEvent.Failed,
                                    trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                                ),
                        )
                        markSubtitleFallbackUnavailable(nonFatalSubtitleFallback)
                        return@launch
                    }
                }
                if (audioRecovery != null) {
                    if (audioRecoveryTarget != audioRecovery || plan?.audioActivationTarget != audioRecovery) {
                        return@launch
                    }
                    if (playbackPlan.streamMode == StreamMode.DirectPlay) {
                        _state.update { PlayerUiState.Error(error = PlaybackError.UnsupportedMedia) }
                        return@launch
                    }
                }

                val playbackPlanWithMetadata =
                    enrichPlaybackPlan(
                        playbackPlan = playbackPlan,
                        requestedMediaSourceId = mediaSource,
                        generation = replanGeneration,
                        itemId = replanItemId,
                        activationRequestId = activationRequestId,
                        selectedSubtitle = selectedSubtitle,
                    ) ?: return@launch
                if (
                    nonFatalSubtitleFallback != null &&
                    !isCurrentSubtitleFallback(
                        target = nonFatalSubtitleFallback,
                        generation = subtitleFallbackGeneration,
                        itemId = replanItemId,
                    )
                ) {
                    return@launch
                }
                if (!isCurrentPlaybackLaunch(replanGeneration, replanItemId)) return@launch
                playbackReportingCoordinator.stopNow(positionMs = target)
                currentCoroutineContext().ensureActive()
                if (!isCurrentPlaybackLaunch(replanGeneration, replanItemId)) return@launch
                if (
                    nonFatalSubtitleFallback != null &&
                    !isCurrentSubtitleFallback(
                        target = nonFatalSubtitleFallback,
                        generation = subtitleFallbackGeneration,
                        itemId = replanItemId,
                    )
                ) {
                    return@launch
                }
                // A same-item replan cannot stabilize a queue switch.
                if (!isCurrentPlaybackLaunch(replanGeneration, replanItemId)) return@launch
                installPlan(playbackPlanWithMetadata, resetReporting = true, stabilizesQueueSwitch = false)
                _state.update { current ->
                    if (current is PlayerUiState.Content) current.copy(isSeekable = false) else current
                }
                if (nonFatalSubtitleFallback != null) {
                    subtitleFallbackTarget = null
                }
                loadTimingOffsets()
                playbackLaunchMarker = null
                markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Prepare)
                playerDiagnosticsRecorder.recordPrepareRequested(playerDiagnosticContext())
                playerController.prepare(playbackPlanWithMetadata)
                if (rejectSynchronouslyFailedPrepare()) return@launch
                installedPlan = playbackPlanWithMetadata
                playerController.runtimeDiagnostics.value.prepareEpoch
                    ?.let(playbackHealthCoordinator::expectVideoOutput)
                armFirstVideoOutputState()
                playerDiagnosticsRecorder.recordPrepareDispatched(playerDiagnosticContext())
                applyInitialEmbeddedSelections(playbackPlanWithMetadata)
                if (forcePlay) {
                    playerController.play()
                } else if (wasPaused || !desiredPlayWhenReady) {
                    playerController.pause()
                } else {
                    playerController.play()
                }
                publishContent(pickerVisible = PlayerPicker.None)
                maybeStartInstalledAudioUnavailableFallback(playbackPlanWithMetadata)
                maybeStartInstalledUnavailableFallback(playbackPlanWithMetadata)
            }
    }

    private fun maybeStartInstalledUnavailableFallback(playbackPlan: PlaybackPlan) {
        val unavailable = playbackPlan.plannedSubtitle as? PlannedSubtitle.Unavailable ?: return
        val target = unavailable.activationTarget ?: return
        playerDiagnosticsRecorder.recordDiagnostic(
            context = playerDiagnosticContext(),
            facts =
                PlayerDiagnosticFacts(
                    stage = PlaybackDiagnosticStage.Mapping,
                    event = PlaybackDiagnosticEvent.Failed,
                    trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                ),
        )
        if (!unavailable.allowEncodeFallback) return
        maybeHandleImmediatePlaybackSessionRecovery(
            playerController.playbackState.value.copy(
                subtitleActivation =
                    com.jellyscope.core.domain.playback.SubtitleActivationState
                        .Unavailable(target),
            ),
        )
    }

    private fun maybeStartInstalledAudioUnavailableFallback(playbackPlan: PlaybackPlan) {
        if (playbackPlan.streamMode != StreamMode.DirectPlay) return
        val target = playbackPlan.audioActivationTarget ?: return
        val descriptor =
            playbackPlan.embeddedAudioTracks.firstOrNull { track ->
                track.jellyfinStreamIndex == target.streamIndex
            }
        if (descriptor != null) return
        maybeHandleImmediatePlaybackSessionRecovery(
            playerController.playbackState.value.copy(
                audioActivation = AudioActivationState.Unavailable(target),
            ),
        )
    }

    private fun markSubtitleFallbackUnavailable(target: SubtitleActivationTarget) {
        val currentPlan = plan ?: return
        if (currentPlan.subtitleActivationTarget != target) return
        val unavailable =
            when (val plannedSubtitle = currentPlan.plannedSubtitle) {
                is PlannedSubtitle.Track ->
                    PlannedSubtitle.Unavailable(
                        streamIndex = plannedSubtitle.streamIndex,
                        kind = plannedSubtitle.kind,
                        normalizedFormat = plannedSubtitle.normalizedFormat,
                        reason = "Server-rendered subtitle fallback failed",
                    )
                is PlannedSubtitle.Unavailable ->
                    plannedSubtitle.copy(
                        allowEncodeFallback = false,
                        activationTarget = null,
                    )
                PlannedSubtitle.Off,
                is PlannedSubtitle.LocalAsset,
                -> return
            }
        playerDiagnosticsRecorder.recordDiagnostic(
            context = playerDiagnosticContext(),
            facts =
                PlayerDiagnosticFacts(
                    stage = PlaybackDiagnosticStage.Mapping,
                    event = PlaybackDiagnosticEvent.Failed,
                    trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                ),
        )
        plan =
            currentPlan.copy(
                selectedSubtitleStreamIndex = null,
                subtitleAsset = null,
                subtitleActivationTarget = null,
                plannedSubtitle = unavailable,
            )
        subtitleFallbackTarget = null
        publishContent()
    }

    private fun installPlan(
        playbackPlan: PlaybackPlan,
        resetReporting: Boolean,
        stabilizesQueueSwitch: Boolean,
    ) {
        installedPlan = null
        plan = playbackPlan
        selectedMediaSourceId = playbackPlan.mediaSourceId
        trickplay = playbackPlan.trickplay
        updatePlaybackHealthSessionContext()
        if (stabilizesQueueSwitch) {
            // The replacement plan makes item identity stable again.
            queueSwitchInFlight = false
        }
        val authoritativeAudio = playbackPlan.selectedAudioStreamIndex.takeIf { playbackPlan.audioSelectionAuthoritative }
        if (authoritativeAudio != null && requestedAudioStreamIndex != authoritativeAudio) {
            requestedAudioStreamIndex = authoritativeAudio
            // Response-authoritative audio changes runtime truth, not durable intent.
            rememberSelection(persistDurable = false)
        }
        if (playbackPlan.streamMode != StreamMode.DirectPlay) {
            installedAudioStreamIndex = playbackPlan.selectedAudioStreamIndex
            audioRecoveryTarget = null
        }
        metadata =
            metadata.copy(
                qualityBadge =
                    playbackPlan.videoStreamLabel
                        ?.takeIf { label -> label.isNotBlank() }
                        ?: metadata.qualityBadge,
            )
        if (resetReporting) {
            playSessionId = deviceInfoProvider.newDeviceId()
            lastStatus = PlaybackStatus.Idle
        }
        playbackReportingCoordinator.install(
            session = session,
            plan = playbackPlan,
            playSessionId = playbackPlan.effectivePlaySessionId(),
        )
        planReportingAuthority += 1L
    }

    private fun rejectSynchronouslyFailedPrepare(): Boolean {
        if (playerController.playbackState.value.status != PlaybackStatus.Failed) return false
        installedPlan = null
        // Initial direct injection has no collector yet; use the ordinary failure path.
        observePlaybackState()
        return true
    }

    private fun publishContent(
        playbackState: PlaybackState = playerController.playbackState.value,
        pickerVisible: PlayerPicker = currentPicker(),
    ) {
        val enrichedPlaybackState = playbackState.withPlaybackMetadata()
        val subtitleRenderInfo = currentSubtitleRenderInfo(enrichedPlaybackState)
        val subtitleStyleable = subtitleRenderInfo.styleable && playerController.appliesSubtitleStyle
        val upNext = upNextFor(enrichedPlaybackState)
        _playbackState.value = enrichedPlaybackState
        recordTrackDiagnostics(enrichedPlaybackState, subtitleRenderInfo)
        _state.update {
            PlayerUiState.Content(
                playbackState = enrichedPlaybackState,
                volumeControl = volumeControl,
                audioUnavailable = enrichedPlaybackState.audioUnavailable,
                metadata = metadata,
                // Stable list identity preserves Compose strong skipping.
                audioOptions = projections.audioTrackOptions(currentItemId, mediaStreams),
                subtitleOptions = projections.subtitleTrackOptions(currentItemId, mediaStreams),
                localSubtitleOptions = localSubtitleAssets.takeUnless { plan?.streamMode == StreamMode.Offline }.orEmpty(),
                qualityOptions =
                    if (plan?.streamMode == StreamMode.Offline) {
                        emptyList()
                    } else {
                        playerQualityOptions(currentSourceBitrate(), qualitySession.policy)
                    },
                selectedAudioStreamIndex = installedAudioStreamIndex,
                selectedSubtitleStreamIndex = subtitleRenderInfo.activeStreamIndex,
                selectedSubtitleAssetId = requestedLocalSubtitleAsset?.id,
                selectedQualityMaxBitrate = qualitySession.maximumBitrateBps,
                selectedQualityPolicy = qualitySession.policy,
                qualityOverrideExplicit = qualitySession.isExplicitSessionChoice,
                inheritedQualityPolicy = activePlaybackPreferences.effectiveDefaultQualityPolicy(backend),
                inheritedQualityUsesVlcSetting = activePlaybackPreferences.usesVlcDefaultQuality(backend),
                pickerVisible = pickerVisible,
                playlist = playlist,
                chapters = chapters,
                mediaSegments = mediaSegments,
                currentSegment = enrichedPlaybackState.currentSegment,
                skipPromptSegment =
                    enrichedPlaybackState.currentSegment?.takeIf { segment ->
                        activePlaybackPreferences.policyFor(segment.type) == SegmentSkipPolicy.Ask
                    },
                trickplay = trickplay,
                trickplayTileUrls = trickplayTileUrls(),
                playbackSpeed = playbackSpeed,
                subtitleStyle = subtitleStyle,
                subtitleRenderInfo = subtitleRenderInfo,
                subtitleStyleable = subtitleStyleable,
                subtitleNotice = subtitleNotice,
                backendNotice = backendNotice,
                backendSwitchNotice = backendSwitchNotice,
                activeBackend = backend,
                backendChoices = backendSwitchChoices(),
                backendSwitchControlVisible = hasBackendSwitchTarget(),
                backendSwitchControlEnabled = canOpenBackendPicker(),
                backendSwitchInProgress = backendSwitchInProgress,
                playbackGuidance = playbackGuidance,
                playbackActionNotice = playbackActionNotice,
                resizeMode = resizeMode,
                upNext = upNext,
                autoplayPolicy = autoplayPolicyFor(upNext),
                stillWatchingPrompt = stillWatchingState.isPromptVisible,
                timingState = timingCoordinator.timingState,
                debugInfo = buildDebugInfo(subtitleRenderInfo, subtitleStyleable),
                videoPresentation = plan?.videoPresentation,
                isSeekable = installedPlan?.contentTimeline is PlaybackContentTimeline.BoundedVod,
                playbackItemId = currentItemId.takeUnless { queueSwitchInFlight },
            )
        }
    }

    private fun autoplayPolicyFor(upNext: UpNextInfo?): AutoplayPolicySnapshot =
        AutoplayPolicySnapshot(
            enabled = activePlaybackPreferences.autoPlayNext,
            delayMs = activePlaybackPreferences.autoPlayNextDelaySeconds * 1_000L,
            queueIdentity = queueIdentity,
            itemId = upNext?.itemId.orEmpty(),
            playbackGeneration = autoplayGeneration,
        )

    private fun buildDebugInfo(
        subtitleRenderInfo: SubtitleRenderInfo = currentSubtitleRenderInfo(),
        subtitleStyleable: Boolean = subtitleRenderInfo.styleable && playerController.appliesSubtitleStyle,
    ): PlayerDebugInfo? {
        val plan = plan ?: return null
        return projectPlayerDebugInfo(
            PlayerDebugInfoProjectionInput(
                plan = plan,
                backend = backend,
                mediaStreams = mediaStreams,
                installedAudioStreamIndex = installedAudioStreamIndex,
                subtitleRenderInfo = subtitleRenderInfo,
                subtitleStyleable = subtitleStyleable,
                launchToFirstFrameMs = launchToFirstFrameMs,
                lastHealthSignal = lastHealthSignal,
                lastHealthThresholdClass = lastHealthThresholdClass,
                playSessionId = playSessionId,
                selectedQualityPolicy = qualitySession.policy,
                qualityExplicitlyChosen = qualitySession.isExplicitSessionChoice,
                autoRecoveryState = autoRecoveryState,
                activePlaybackPreferences = activePlaybackPreferences,
                firstVideoOutput = firstVideoOutputState.debug,
            ),
        )
    }

    private fun bindPlaybackLaunchToPrepare(
        generation: Long,
        controller: PlayerController,
    ) {
        val marker = playbackLaunchMarker ?: return
        if (marker.generation != generation) return
        val prepareEpoch = controller.runtimeDiagnostics.value.prepareEpoch ?: return
        playbackLaunchMarker =
            marker.copy(
                controller = controller,
                prepareEpoch = prepareEpoch,
            )
        maybeCompletePlaybackLaunch(controller, controller.runtimeDiagnostics.value)
    }

    private fun maybeCompletePlaybackLaunch(
        controller: PlayerController,
        diagnostics: PlaybackRuntimeDiagnostics,
    ) {
        val marker = playbackLaunchMarker ?: return
        if (!playbackLaunchMarkerMatches(marker.controller, marker.prepareEpoch, controller, diagnostics.prepareEpoch)) return
        if (diagnostics.nativePrepareToFirstFrameMs == null) return
        val durationMs = (monotonicTimeMs() - marker.startedAtMs).coerceAtLeast(0L)
        launchToFirstFrameMs = durationMs
        controller.recordLaunchToFirstFrame(
            prepareEpoch = marker.prepareEpoch ?: return,
            durationMs = durationMs,
        )
        playbackLaunchMarker = null
        if (_state.value is PlayerUiState.Content) {
            publishContent()
        }
    }

    private fun clearPlaybackLaunch(generation: Long) {
        if (playbackLaunchMarker?.generation == generation) {
            playbackLaunchMarker = null
        }
        if (activePlaybackTimelineFacts?.generation == generation) {
            activePlaybackTimelineFacts = null
        }
    }

    private fun currentSubtitleRenderInfo(playbackState: PlaybackState = playerController.playbackState.value): SubtitleRenderInfo =
        projectSubtitleRenderInfo(
            mediaStreams = mediaStreams,
            plannedSubtitle = plan?.plannedSubtitle ?: PlannedSubtitle.Off,
            activationState = playbackState.subtitleActivation,
            requestedLocalSubtitleAsset = requestedLocalSubtitleAsset,
        )

    private fun observeLocalSubtitleAssets(
        itemId: String,
        mediaSourceId: String,
    ) {
        localSubtitleAssetsJob?.cancel()
        localSubtitleAssets = emptyList()
        val observe = observeLocalSubtitleAssetsUseCase ?: return
        localSubtitleAssetsJob =
            viewModelScope.launch {
                observe(LocalSubtitleContext(session.serverId, session.userId, itemId, mediaSourceId))
                    .collectLatest { assets ->
                        localSubtitleAssets = assets
                        if (state.value is PlayerUiState.Content) publishContent()
                    }
            }
    }

    private fun PlaybackState.withPlaybackMetadata(): PlaybackState =
        copy(
            currentSegment = mediaSegments.currentSegment(positionMs),
            playbackSpeed = playbackSpeed,
            subtitleStyle = subtitleStyle,
        )

    private fun currentSourceBitrate(): Long? =
        plan?.sourceBitrateBps
            ?: mediaStreams
                .firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
                ?.bitRate

    private fun selectedSubtitleMediaStream(): PlaybackMediaStream? =
        resolveSelectedSubtitleMediaStream(
            mediaStreams = mediaStreams,
            requestedSubtitleStreamIndex = requestedSubtitleStreamIndex,
        )

    private fun nextSubtitleActivationRequestId(): Long {
        subtitleActivationRequestId += 1L
        return subtitleActivationRequestId
    }

    private fun invalidateSubtitleFallback() {
        subtitleFallbackGeneration += 1L
        subtitleFallbackTarget = null
    }

    private fun cancelAndInvalidateSubtitleFallback() {
        invalidateSubtitleFallback()
        replanJob?.cancel()
        replanJob = null
    }

    private fun isCurrentSubtitleFallback(
        target: SubtitleActivationTarget,
        generation: Long?,
        itemId: String,
    ): Boolean =
        generation == subtitleFallbackGeneration &&
            subtitleFallbackTarget == target &&
            target.itemId == itemId &&
            currentItemId == itemId &&
            plan?.subtitleActivationTarget == target &&
            (requestedSubtitleSelection as? SubtitleSelectionIntent.Track)?.streamIndex == target.streamIndex

    private fun newAudioActivationTarget(streamIndex: Int): AudioActivationTarget =
        audioActivationTarget(
            requestId = ++audioActivationRequestId,
            itemId = currentItemId,
            streamIndex = streamIndex,
        )

    private fun newSubtitleActivationTarget(
        streamIndex: Int,
        kind: LocalSubtitleKind,
    ): SubtitleActivationTarget =
        subtitleActivationTarget(
            requestId = nextSubtitleActivationRequestId(),
            itemId = currentItemId,
            streamIndex = streamIndex,
            kind = kind,
        )

    private fun currentPicker(): PlayerPicker = (state.value as? PlayerUiState.Content)?.pickerVisible ?: PlayerPicker.None

    private fun recordTrackDiagnostics(
        playbackState: PlaybackState,
        subtitleRenderInfo: SubtitleRenderInfo = currentSubtitleRenderInfo(playbackState),
    ) {
        val currentPlan = plan ?: return
        val confirmedSubtitle =
            (playbackState.subtitleActivation as? SubtitleActivationState.Active)
                ?.target
                ?.streamIndex
        playerDiagnosticsRecorder.recordTrackStates(
            context = playerDiagnosticContext(currentPlan = currentPlan),
            facts =
                PlayerTrackDiagnosticFacts(
                    requestedAudioStreamIndex = requestedAudioStreamIndex,
                    confirmedAudioStreamIndex = installedAudioStreamIndex,
                    audioActivation = playbackState.audioActivation,
                    subtitleSelection = requestedSubtitleSelection,
                    requestedLocalSubtitleAssetPresent = requestedLocalSubtitleAsset != null,
                    requestedSubtitleStreamIndex = requestedSubtitleStreamIndex,
                    confirmedSubtitleStreamIndex = confirmedSubtitle,
                    subtitleActivation = playbackState.subtitleActivation,
                    subtitleRenderInfo = subtitleRenderInfo,
                    subtitleStyleable = subtitleRenderInfo.styleable && playerController.appliesSubtitleStyle,
                ),
        )
    }

    private fun playerDiagnosticContext(
        currentPlan: PlaybackPlan? = plan,
        backend: PlayerBackend = this.backend,
        sessionSequence: Long = currentPlan?.diagnosticSessionSequence ?: playbackLaunchGeneration,
        prepareSequence: Long? = playerController.runtimeDiagnostics.value.prepareEpoch,
        runtimeDiagnostics: PlaybackRuntimeDiagnostics = _runtimeDiagnostics.value,
    ): PlayerDiagnosticContext =
        PlayerDiagnosticContext(
            backend = backend,
            sessionSequence = sessionSequence,
            prepareSequence = prepareSequence,
            streamMode = currentPlan?.streamMode,
            qualityCapOrigin = currentPlan?.qualityCapOrigin,
            qualityPolicyMode = currentPlan?.qualityPolicy?.mode,
            qualityPolicyOrigin =
                playbackQualityPolicyOriginForDebug(
                    selectedQualityPolicy = qualitySession.policy,
                    qualityExplicitlyChosen = qualitySession.isExplicitSessionChoice,
                    autoRecoveryState = autoRecoveryState,
                ),
            requestCapBitrateBps = currentPlan?.maxStreamingBitrate,
            effectiveTranscodeCapBitrateBps = currentPlan?.effectiveTranscodeMaxStreamingBitrate,
            sourceBitrateBps = currentPlan?.sourceBitrateBps,
            runtimeDiagnostics = runtimeDiagnostics,
        )

    private fun startupPlanningError(exception: Throwable): PlayerUiState.Error {
        val copyFailure = exception as? PlaybackPlanningException.SourceVideoCopyUnsupported
        val isDeterministicCapabilityFailure =
            when (copyFailure?.cause) {
                null -> copyFailure != null
                PlaybackPlanningException.SourceVideoCopyRejected,
                PlaybackPlanningException.NoSupportedStream,
                -> true

                else -> false
            }
        val rootFailure = copyFailure?.cause ?: exception
        return when {
            isDeterministicCapabilityFailure ->
                PlayerUiState.Error(
                    retryable = false,
                    error = PlaybackError.UnsupportedMedia,
                )
            rootFailure is PlaybackPlanningException.RemoteRequestFailed && rootFailure.isNetworkFailure ->
                PlayerUiState.Error(error = PlaybackError.Network)
            else -> PlayerUiState.Error(error = PlaybackError.Unknown)
        }
    }

    // Memoize tile URLs to preserve list identity across publications.
    private fun trickplayTileUrls(): List<String> {
        val trickplay = trickplay
        return projections.trickplayTileUrls(
            itemId = currentItemId,
            serverUrl = session.serverUrl,
            mediaSourceId = trickplay?.mediaSourceId,
            tileWidth = trickplay?.width,
            tileCount = trickplay?.tileCount,
        ) {
            queueProjection.trickplayTileUrls(itemId = currentItemId, trickplay = trickplay)
        }
    }

    private fun upNextFor(playbackState: PlaybackState): UpNextInfo? =
        queueProjection.upNextFor(
            playbackState = playbackState,
            playlist = playlist,
            mediaSegments = mediaSegments,
        )

    /** [persistDurable] is true only for explicit audio actions. */
    private fun rememberSelection(persistDurable: Boolean = true) {
        val selection =
            PlaybackSelection(
                audioStreamIndex = requestedAudioStreamIndex,
            )
        val sourceId = selectedMediaSourceId?.takeIf(String::isNotBlank)
        if (sourceId == null) {
            playbackSelectionMemory.remember(accountIdentity, currentItemId, selection)
            return
        }
        // The source-keyed entry remains authoritative over item-only memory.
        playbackSelectionMemory.remember(accountIdentity, currentItemId, selection)
        playbackSelectionMemory.remember(
            accountIdentity = accountIdentity,
            itemId = currentItemId,
            mediaSourceId = sourceId,
            selection = selection,
        )
        if (persistDurable) {
            val durableSelection =
                selection.copy(
                    audioStreamIndex = explicitAudioStreamIndex,
                )
            val persistenceContext = playerDiagnosticContext()
            val writeAction = savePlaybackSelectionAction
            if (writeAction == null) {
                playerDiagnosticsRecorder.recordPersistence(
                    context = persistenceContext,
                    facts =
                        PlayerPersistenceDiagnosticFacts(
                            event = PlaybackDiagnosticEvent.Write,
                            target = PlaybackPersistenceTarget.PlaybackSelection,
                            result = PlaybackPersistenceResult.Unavailable,
                        ),
                )
            } else {
                writeAction
                    .save(
                        key = PlaybackSelectionKey(session.serverId, session.userId, currentItemId, sourceId),
                        selection = durableSelection,
                    ).invokeOnCompletion { exception ->
                        playerDiagnosticsRecorder.recordPersistence(
                            context = persistenceContext,
                            facts =
                                PlayerPersistenceDiagnosticFacts(
                                    event = PlaybackDiagnosticEvent.Write,
                                    target = PlaybackPersistenceTarget.PlaybackSelection,
                                    result =
                                        when (exception) {
                                            null -> PlaybackPersistenceResult.Applied
                                            is CancellationException -> PlaybackPersistenceResult.Cancelled
                                            else -> PlaybackPersistenceResult.Failed
                                        },
                                    exception = exception,
                                ),
                        )
                    }
            }
        }
    }

    private fun MediaVersion.sourceBitrateBps(): Long? =
        mediaStreams
            .firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
            ?.bitRate

    private fun PlaybackPlan.effectivePlaySessionId(): String = this.playSessionId ?: this@PlayerViewModel.playSessionId

    private fun playNext(
        auto: Boolean,
        stopPositionMs: Long,
        expectedGeneration: Long? = null,
    ): Boolean {
        // Ignore countdowns from an older queue generation.
        if (expectedGeneration != null && expectedGeneration != autoplayGeneration) {
            return false
        }
        val nextIndex = nextQueueIndex() ?: return false

        if (auto) {
            if (!activePlaybackPreferences.stillWatchingPrompt) {
                stillWatchingState = stillWatchingState.resetForDisabledPreference()
            } else {
                val advance = stillWatchingState.automaticAdvance(nextIndex)
                stillWatchingState = advance.state
                if (advance is PlayerStillWatchingAutomaticAdvance.PromptBlocked) {
                    if (state.value is PlayerUiState.Content) {
                        publishContent()
                    }
                    return false
                }
            }
        } else {
            stillWatchingState = stillWatchingState.resetForManualNavigation()
        }

        invalidateSubtitleFallback()
        startQueueSwitch(
            index = nextIndex,
            stopPositionMs = stopPositionMs,
        )
        return true
    }

    private fun nextQueueIndex(): Int? =
        (currentQueueIndex + 1)
            .takeIf { index -> queueIds.size > 1 && index in queueIds.indices }

    private fun startQueueSwitch(
        index: Int,
        stopPositionMs: Long,
    ) {
        if (queueIds.size <= 1 || index !in queueIds.indices) {
            return
        }
        invalidateBackendSwitch()
        autoplayGeneration += 1
        // Invalidate identity before async planning so held seeks cancel now.
        queueSwitchInFlight = true
        installedPlan = null
        _state.update { current ->
            if (current is PlayerUiState.Content) {
                current.copy(isSeekable = false, playbackItemId = null)
            } else {
                current
            }
        }
        derivedEpisodeQueueJob?.cancel()
        // Cancel any replan targeting the outgoing item.
        replanJob?.cancel()
        replanJob = null
        queueSwitchJob?.cancel()
        queueSwitchJob =
            viewModelScope.launch {
                switchToQueueItem(
                    index = index,
                    stopPositionMs = stopPositionMs,
                )
            }
    }

    private suspend fun switchToQueueItem(
        index: Int,
        stopPositionMs: Long,
    ) {
        // Stop immediately while the next item is planned.
        playerController.stop()
        playbackReportingCoordinator.stopNow(positionMs = stopPositionMs)
        currentQueueIndex = index
        playlist = playlist?.copy(currentIndex = index)
        startPlaybackForItem(
            itemId = queueIds[index],
            requestedMediaSourceId = null,
            requestedAudioStreamIndex = null,
            requestedSubtitleSelection = SubtitleSelectionIntent.Unspecified,
            startPositionTicks = 0L,
            resetReporting = true,
        )
    }

    private fun deriveEpisodeQueueIfNeeded(item: MediaItem) {
        val getChronologicalEpisodeQueue = getChronologicalEpisodeQueueUseCase ?: return
        if (item.kind != MediaKind.Episode) {
            return
        }
        if (queueIds.isNotEmpty()) {
            return
        }

        val sourceItemId = item.id
        derivedEpisodeQueueJob =
            viewModelScope.launch {
                val derivedQueue =
                    try {
                        withContext(workDispatcher) {
                            getChronologicalEpisodeQueue(item)
                                .getOrDefault(emptyList())
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        emptyList()
                    }
                val derivedIds =
                    normalizedQueue(
                        initialItemId = sourceItemId,
                        queue = derivedQueue.map { episode -> episode.id },
                    )
                if (currentItemId != sourceItemId || queueIds.isNotEmpty() || derivedIds.isEmpty()) {
                    return@launch
                }

                // Build full-series queue projections off Main.
                val resolvedItems =
                    withContext(workDispatcher) {
                        val itemsById = derivedQueue.associateBy { episode -> episode.id }
                        queueProjection.queueItemsForMediaItems(derivedIds, itemsById)
                    }
                // Do not overwrite a queue installed during the off-Main build.
                if (currentItemId != sourceItemId || queueIds.isNotEmpty()) {
                    return@launch
                }
                queueIds = derivedIds
                currentQueueIndex = 0
                playlist = PlaylistUi(items = resolvedItems, currentIndex = currentQueueIndex)
                publishContent()
            }
    }

    private fun fetchPlaylistMetadata() {
        if (queueIds.size <= 1) {
            return
        }
        if (playlistMetadataJob?.isActive == true) {
            return
        }

        playlistMetadataJob =
            viewModelScope.launch {
                try {
                    val resolvedItemsById = queueProjection.resolveQueueItems(queueIds)
                    val existingItemsById = playlist?.items.orEmpty().associateBy { item -> item.id }
                    val appliedQueueIds = queueIds
                    playlist =
                        PlaylistUi(
                            items =
                                queueProjection.queueItems(
                                    queueIds = appliedQueueIds,
                                    itemsById = existingItemsById + resolvedItemsById,
                                ),
                            currentIndex =
                                currentQueueIndex.takeIf { index -> index in appliedQueueIds.indices }
                                    ?: 0,
                        )
                    publishContent()
                } finally {
                    playlistMetadataJob = null
                }
            }
    }
}

private data class PlaybackLaunchMarker(
    val generation: Long,
    val startedAtMs: Long,
    val healthStartedAtMs: Long,
    val controller: PlayerController? = null,
    val prepareEpoch: Long? = null,
)

internal fun playbackLaunchMarkerMatches(
    markerController: PlayerController?,
    markerPrepareEpoch: Long?,
    callbackController: PlayerController,
    callbackPrepareEpoch: Long?,
): Boolean = markerController === callbackController && markerPrepareEpoch == callbackPrepareEpoch

private val playerMonotonicOrigin = TimeSource.Monotonic.markNow()

private fun playerMonotonicTimeMs(): Long = playerMonotonicOrigin.elapsedNow().inWholeMilliseconds

internal data class PlayerControllerConstructionOptions(
    val allowInsecureDesktopTls: Boolean,
)

private data class PlaybackTerminalRecovery(
    val outcome: PlaybackTerminalOutcome?,
    val autoRecoveryTrigger: AutoPlaybackRecoveryTrigger? = null,
    val recoveryDecision: PlaybackRecoveryDecision? = null,
)

private data class BackendSwitchSnapshot(
    val generation: Long,
    val launchGeneration: Long,
    val itemId: String,
    val mediaSourceId: String,
    val activePlan: PlaybackPlan,
    val explicitAudioStreamIndex: Int?,
    val requestedAudioStreamIndex: Int?,
    val requestedSubtitleSelection: SubtitleSelectionIntent,
    val requestedLocalSubtitleAsset: LocalSubtitleAsset?,
    val qualityPolicy: PlaybackQualityPolicy,
    val qualityExplicit: Boolean,
    val wasPaused: Boolean,
    val playbackSpeed: Float,
    val subtitleStyle: SubtitleStyle,
    val resizeMode: PlayerResizeMode,
    val queueIdentity: String,
    val targetBackend: PlayerBackend,
    val defaultBackend: PlayerBackend,
    var planReportingAuthority: Long,
)

private val backendSwitchTerminalStatuses =
    setOf(
        PlaybackStatus.Idle,
        PlaybackStatus.Failed,
        PlaybackStatus.Completed,
    )

private class BackendSwitchInstallationException : IllegalStateException("Backend switch installation failed.")

private data class AutomaticRecoveryDiagnostic(
    val trigger: AutoPlaybackRecoveryTrigger,
    val decision: PlaybackRecoveryDecision,
    val scheduled: Boolean,
)

private val localEmbeddedDeliveryMethods =
    setOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Hls)

private class OfflineControllerUnavailableException(
    val requiredBackend: PlayerBackend,
    cause: Throwable?,
) : IllegalStateException("Offline controller backend unavailable.", cause)

private class ControllerCandidateOwner {
    private var controller: PlayerController? = null

    fun acquire(candidate: PlayerController): PlayerController {
        check(controller == null) { "Controller candidate is already owned." }
        controller = candidate
        return candidate
    }

    fun transferTo(block: (PlayerController) -> Unit) {
        val candidate = checkNotNull(controller) { "Controller candidate is not owned." }
        block(candidate)
        controller = null
    }

    fun releaseUntransferred() {
        val candidate = controller
        controller = null
        candidate?.release()
    }
}
