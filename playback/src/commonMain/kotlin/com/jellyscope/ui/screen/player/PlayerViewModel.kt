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
import com.jellyscope.core.domain.model.OfflineArtifactRef
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
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
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
import com.jellyscope.core.domain.playback.SoftwarePlaybackProgress
import com.jellyscope.core.domain.playback.SoftwarePlaybackProgressOutcome
import com.jellyscope.core.domain.playback.SoftwarePlaybackRecoveryDecision
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
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
import com.jellyscope.core.domain.playback.unambiguousAudioStreamIndexes
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
import com.jellyscope.core.domain.usecase.ObservePlayerClockEnabledUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackReportingCoordinator
import com.jellyscope.core.playback.PlaybackReportingQueue
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import com.jellyscope.core.playback.resolvePlaybackDurationMs
import com.jellyscope.core.playback.toDiagnosticsSourceDescriptor
import com.jellyscope.core.util.PlaybackDiagnosticSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    observePlayerClockEnabledUseCase: ObservePlayerClockEnabledUseCase? = null,
    private val monotonicTimeMs: () -> Long = ::playerMonotonicTimeMs,
    private val playbackHealthGuidancePolicy: PlaybackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Actionable,
    offlineDownloadId: DownloadId? = null,
    private val offlineRestartFromBeginning: Boolean = false,
    private val getOfflinePlaybackPlanUseCase: GetOfflinePlaybackPlanUseCase? = null,
    private val launchPolicy: PlayerLaunchPolicy = PlayerLaunchPolicy.Normal,
) : ViewModel() {
    /** Diagnostics preference captured once per playback start. */
    val startWithPlaybackInfoOverlay: Boolean =
        getPlaybackInfoAtStartStateUseCase?.invoke()?.value ?: false
    val playerClockEnabled: StateFlow<Boolean> =
        observePlayerClockEnabledUseCase?.invoke() ?: MutableStateFlow(true).asStateFlow()
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

    private val isKidsSingleAsset: Boolean = launchPolicy == PlayerLaunchPolicy.KidsSingleAsset
    private var currentOfflineDownloadId: DownloadId? = offlineDownloadId
    private val initialPlaybackTarget = PlayerPlaybackTarget(itemId, offlineDownloadId)
    private val initialOfflineRestartFromBeginning = offlineRestartFromBeginning && offlineDownloadId != null
    private var forceStartFromBeginning = initialOfflineRestartFromBeginning
    private var pendingRestartFromBeginningTarget: PlayerPlaybackTarget? =
        initialPlaybackTarget.takeIf { initialOfflineRestartFromBeginning }
    private var pendingRestartFromBeginningLaunchGeneration: Long? = null
    private var playingPositionOwner: PlayerPlaybackTarget? = null

    // Raw accepted launch inputs, never resolved runtime selections, stay authoritative for their
    // target through planning failure and full Retry. For Kids online playback this is the latest
    // explicit launch plus later accepted track actions; defaults and server substitutions never enter.
    private var launchIntentTarget: PlayerPlaybackTarget? = initialPlaybackTarget
    private var launchIntent =
        PlayerLaunchOptions(
            startPositionTicks = startPositionTicks,
            mediaSourceId = mediaSourceId,
            initialAudioStreamIndex = initialAudioStreamIndex,
            initialSubtitleSelection = initialSubtitleSelection,
        )
    private var externalLaunchEpoch = 0L
    private var externalLaunchesClosed = false
    private val _selectedPlaybackTarget = MutableStateFlow(PlayerPlaybackTarget(itemId, offlineDownloadId))
    val selectedPlaybackTarget: StateFlow<PlayerPlaybackTarget> = _selectedPlaybackTarget.asStateFlow()
    private var selectedPlaybackGeneration = 0L
    private var queueIds = normalizedQueue(initialItemId = itemId, queue = if (isKidsSingleAsset) emptyList() else queue)
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
            // The constructor parameter is the pending placeholder; read the installed member.
            controllerProvider = {
                if (disposed || !concreteControllerInstalled) null else this@PlayerViewModel.playerController
            },
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
    private var playbackIntentRevision = 0L
    private var requestedSubtitleStreamIndex: Int? = null
    private var requestedSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified
    private var requestedLocalSubtitleAsset: LocalSubtitleAsset? = null
    private var localSubtitleAssets: List<LocalSubtitleAsset> = emptyList()
    private var offlineSidecarOption: OfflineSidecarOption? = null
    private var subtitleActivationRequestId = 0L
    private var offlineSubtitleSelectionGeneration = 0L
    private var offlineReprepareGeneration: Long? = null
    private var offlineRepreparePositionMs: Long? = null
    private var subtitleFallbackTarget: SubtitleActivationTarget? = null
    private var subtitleFallbackGeneration = 0L
    private var subtitleNoticeToken = 0L
    private var subtitleNotice: PlayerNotice? = null
    private var backendNoticeToken = 0L
    private var backendNotice: PlayerBackendNotice? = null
    private var playbackChangeNoticeToken = 0L
    private var playbackChangeNotice: PlayerPlaybackChangeNotice? = null
    private var availableBackendsForSession: Set<PlayerBackend> = emptySet()
    private var backendSwitchGeneration = 0L
    private var backendSwitchInProgress = false
    private var backendSwitchJob: Job? = null
    private var qualitySession = PlayerQualitySessionState()
    private var playbackLaunchGeneration = 0L

    /** Process-unique diagnostic correlation for the current launch generation; never compared operationally. */
    private var playbackDiagnosticSession = 0L
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
    private var replanRequestGeneration = 0L
    private var localSubtitleAssetsJob: Job? = null
    private var lastStatus: PlaybackStatus = PlaybackStatus.Idle
    private var softwareProgress = SoftwarePlaybackProgress()
    private var softwarePrepareEpoch: Long? = null
    private var softwareProgressExcludedUntilMs = 0L
    private var softwareProgressReported = false
    private var softwareRecoveryToken = 0L
    private val _softwarePlaybackRecovery = MutableStateFlow<PlayerSoftwarePlaybackRecovery?>(null)
    val softwarePlaybackRecovery: StateFlow<PlayerSoftwarePlaybackRecovery?> = _softwarePlaybackRecovery.asStateFlow()
    private var alternateBackendFallbackAttempted = false
    private var controllerInstallInFlight = false
    private val controllerInstallMutex = Mutex()
    private var stopRequestedDuringControllerInstall = false

    // Stop closes recovery admission until an accepted explicit playback action reopens it.
    private var recoveryClosedByStop = false

    // Launch generation of a startup prepare or same-plan Retry still establishing its install; callbacks wait for it.
    private var startupPrepareGeneration: Long? = null
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
                if (!concreteControllerInstalled) {
                    PlaybackHealthMeasurementCapabilities.None
                } else {
                    val currentController = this@PlayerViewModel.playerController
                    val capabilities = currentController.playbackHealthMeasurementCapabilities
                    capabilities.copy(
                        hasReliableFirstVideoOutput =
                            capabilities.hasReliableFirstVideoOutput &&
                                currentController.videoOutputMeasurementCapabilities.isSupported &&
                                firstVideoOutputState.debug.state != PlaybackFirstVideoOutputState.Unsupported,
                    )
                }
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
            // Reads the currently installed controller, never a captured outgoing one.
            latestHostSeek = { this@PlayerViewModel.playerController.lastAcceptedHostSeek },
            latestHostPlaybackIntent = { this@PlayerViewModel.playerController.lastHostPlaybackIntent },
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

    fun accept(intent: PlayerIntent) {
        when (intent) {
            PlayerIntent.Play -> play()
            PlayerIntent.Pause -> pause()
            PlayerIntent.TogglePlayPause -> togglePlayPause()
            PlayerIntent.PlayPrevious -> playPrevious()
            is PlayerIntent.SetVolume -> setVolume(intent.percent)
            PlayerIntent.ToggleMute -> toggleMute()
            is PlayerIntent.SeekTo -> seekTo(intent.positionMs)
            PlayerIntent.Retry -> retry()
            is PlayerIntent.PlayQueueItem -> playQueueItem(intent.index)
            PlayerIntent.ConfirmStillWatching -> confirmStillWatching()
            PlayerIntent.SkipCurrentSegment -> skipCurrentSegment()
            is PlayerIntent.SetResizeMode -> setResizeMode(intent.mode)
            is PlayerIntent.CycleResizeMode -> cycleResizeMode(intent.twoState)
            PlayerIntent.ShuffleQueue -> shuffleQueue()
            PlayerIntent.Stop -> stop()
            is PlayerIntent.ShowPicker -> showPicker(intent.picker)
            PlayerIntent.HidePicker -> hidePicker()
            is PlayerIntent.SelectBackend -> selectBackend(intent.backend)
            is PlayerIntent.SelectAudio -> selectAudio(intent.streamIndex)
            PlayerIntent.SelectOfflineSidecar -> selectOfflineSidecar()
            is PlayerIntent.SelectSubtitle -> selectSubtitle(intent.streamIndex)
            is PlayerIntent.SelectLocalSubtitle -> selectLocalSubtitle(intent.assetId)
            is PlayerIntent.SelectQualityBitrate -> selectQuality(intent.maxBitrateBps)
            is PlayerIntent.SelectQuality -> selectQuality(intent.policy)
            PlayerIntent.ClearQualityOverride -> clearQualityOverride()
            is PlayerIntent.HandlePlaybackAction -> handlePlaybackAction(intent.action)
            PlayerIntent.DismissPlaybackGuidance -> dismissPlaybackGuidance()
            is PlayerIntent.PictureInPictureChanged -> setPictureInPictureMode(intent.inPictureInPicture)
            is PlayerIntent.SetPlaybackSpeed -> setPlaybackSpeed(intent.speed)
            is PlayerIntent.SetSubtitleStyle -> setSubtitleStyle(intent.style)
            is PlayerIntent.AdjustAudioTiming -> adjustAudioTiming(intent.deltaMs)
            is PlayerIntent.AdjustSubtitleTiming -> adjustSubtitleTiming(intent.deltaMs)
            PlayerIntent.ResetAudioTiming -> resetAudioTiming()
            PlayerIntent.ResetSubtitleTiming -> resetSubtitleTiming()
            is PlayerIntent.ContinueSoftwareRecovery -> continueSoftwarePlaybackRecovery(intent.token)
            is PlayerIntent.SwitchSoftwareRecovery -> switchSoftwarePlaybackRecovery(intent.token)
            is PlayerIntent.Request -> {
                accept(intent)
            }
        }
    }

    fun accept(intent: PlayerIntent.Request): Boolean =
        when (intent) {
            is PlayerIntent.PlayNext -> playNext(intent.auto, intent.expectedGeneration)
            PlayerIntent.ReplayCurrent -> replayCurrentPlayback()
            is PlayerIntent.SelectAsset ->
                selectPlaybackAsset(intent.itemId, intent.offlineDownloadId, intent.restartFromBeginning, intent.allowCurrentTargetRetry)
        }

    suspend fun accept(intent: PlayerIntent.Admission): Boolean =
        when (intent) {
            is PlayerIntent.KidsLaunch -> acceptKidsLaunch(intent.itemId, intent.options)
        }

    private fun reduce(change: PlayerChange) {
        _state.update { current -> reducePlayer(current, change) }
    }

    private fun play() {
        if (_softwarePlaybackRecovery.value != null) return
        if (!canAcceptPlaybackStart()) return
        recoveryClosedByStop = false
        invalidateBackendSwitch()
        desiredPlayWhenReady = true
        playbackIntentRevision += 1L
        if (offlineReprepareGeneration != null) return
        excludeSoftwarePlaybackProgress()
        restartPlaybackHealthEvidence()
        playerController.play()
    }

    private fun pause() {
        if (_softwarePlaybackRecovery.value != null) return
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        desiredPlayWhenReady = false
        playbackIntentRevision += 1L
        if (offlineReprepareGeneration != null) return
        excludeSoftwarePlaybackProgress()
        restartPlaybackHealthEvidence()
        playerController.pause()
    }

    private fun togglePlayPause() {
        when (playbackState.value.status) {
            PlaybackStatus.Playing -> pause()
            PlaybackStatus.Loading,
            PlaybackStatus.Buffering,
            -> if (desiredPlayWhenReady) pause() else play()
            PlaybackStatus.Completed -> {
                if (isKidsSingleAsset) replayCurrentPlayback() else play()
            }
            PlaybackStatus.Idle,
            PlaybackStatus.Paused,
            PlaybackStatus.Failed,
            -> play()
        }
    }

    private fun playPrevious() {
        if (isKidsSingleAsset || controllerInstallInFlight) return
        val playback = playbackState.value
        if (playback.positionMs >= PREVIOUS_ITEM_RESTART_THRESHOLD_MS) {
            seekTo(0L)
            return
        }
        val previousIndex =
            (currentQueueIndex - 1)
                .takeIf { index -> queueIds.size > 1 && index in queueIds.indices }
                ?: return
        // Only an actual queue start needs full start admission; seekTo applies its own guards.
        if (!canAcceptPlaybackStart()) return
        stillWatchingState = stillWatchingState.resetForManualNavigation()
        invalidateSubtitleFallback()
        startQueueSwitch(
            index = previousIndex,
            stopPositionMs = playback.positionMs,
            explicitPlaybackAction = true,
        )
    }

    private fun setVolume(percent: Int) {
        if (controllerInstallInFlight) return
        (playerController as? PlayerVolumeController)?.setVolume(percent)
    }

    private fun toggleMute() {
        if (controllerInstallInFlight) return
        val volumeController = playerController as? PlayerVolumeController ?: return
        volumeController.setMuted(!volumeController.volumeState.value.muted)
    }

    private fun seekTo(positionMs: Long) {
        if (_softwarePlaybackRecovery.value != null) return
        if (controllerInstallInFlight || queueSwitchInFlight) return
        invalidateBackendSwitch()
        val target = positionMs.coerceAtLeast(0L)
        excludeSoftwarePlaybackProgress()
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

    private fun retry() {
        if (disposed) return
        if (_softwarePlaybackRecovery.value != null) return
        if (controllerInstallInFlight) return
        if (isKidsSingleAsset) {
            retryCurrentPlayback()
            return
        }
        if (stopRequestedDuringControllerInstall || controllerInstallMutex.isLocked) return
        if (queueSwitchInFlight && queueSwitchJob?.isActive == true) return
        val retryItemId = currentItemId
        val retryLaunchIntent = launchIntent
        recoveryClosedByStop = false
        invalidateBackendSwitch()
        autoplayGeneration += 1
        reduce(PlayerChange.Loading)
        playSessionId = deviceInfoProvider.newDeviceId()
        lastStatus = PlaybackStatus.Idle
        replanJob?.cancel()
        replanJob = null
        val sameItemFastRetry = !queueSwitchInFlight && plan?.itemId == currentItemId
        emitPlaybackHealthSummary()
        val retryGeneration = ++playbackLaunchGeneration
        playbackDiagnosticSession = PlaybackDiagnosticSessions.allocate()
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
        plan = plan?.copy(diagnosticSessionSequence = playbackDiagnosticSession)
        pendingRecoveredPlaybackGuidanceItemId = null
        pendingRecoveredAutoQualityBps = null
        pendingPictureInPictureRecoveryTrigger = null
        playbackActionNotice = null
        playbackChangeNotice = null
        autoRecoveryState = autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(playbackLaunchGeneration, currentItemId)
        if (currentOfflineDownloadId != null || plan?.streamMode == StreamMode.Offline) {
            if (currentOfflineDownloadId == null) {
                failOfflineLaunch(retryGeneration, PlaybackError.OfflineArtifactUnavailable)
                return
            }
            // A full Retry owns the launch slot, so Stop, disposal and newer launches cancel it.
            queueSwitchJob?.cancel()
            queueSwitchJob =
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
            val retryPlan = plan
            val retryController = playerController
            val retryInstalledPlan = installedPlan
            val retrySelectionGeneration = selectedPlaybackGeneration
            val retryReplanGeneration = replanRequestGeneration

            // An inline Stop, disposal, queue change, selection, install or replan during Retry owns playback now.
            fun ownsRetry(): Boolean =
                admitsPlaybackSessionRecovery() &&
                    isCurrentReplan(retryReplanGeneration, retryGeneration, retryItemId) &&
                    selectedPlaybackGeneration == retrySelectionGeneration &&
                    playerController === retryController &&
                    installedPlan === retryInstalledPlan &&
                    plan === retryPlan
            startupPrepareGeneration = retryGeneration
            try {
                retryController.retry()
            } finally {
                if (startupPrepareGeneration == retryGeneration) startupPrepareGeneration = null
            }
            if (!ownsRetry() || rejectSynchronouslyFailedPrepare(refreshObservation = true)) return
            retryController.runtimeDiagnostics.value.prepareEpoch
                ?.let(playbackHealthCoordinator::expectVideoOutput)
            armSoftwarePlaybackRecovery()
            armFirstVideoOutputState()
            if (retryPlan == null || !ownsRetry()) return
            // Native Retry keeps matching selections but cannot recreate a missing activation descriptor.
            // VLCKit reprepares this plan's start paused at raw zero before its deferred play.
            val vlcKitRetry = backend == PlayerBackend.VlcKit
            maybeStartInstalledActivationFallback(
                expectedController = retryController,
                expectedPlan = retryPlan,
                reconcileNativeState = true,
                expectedInstalledPlan = retryInstalledPlan,
                recoveryPositionOverrideMs = retryPlan.startPositionMs.takeIf { vlcKitRetry },
                pausedAtRequestOverride = (!desiredPlayWhenReady).takeIf { vlcKitRetry },
            )
            return
        }

        // The early admission check preserves an active unsettled launch; only an admitted full relaunch takes the slot.
        queueSwitchJob?.cancel()
        queueSwitchJob =
            viewModelScope.launch {
                if (
                    startPlaybackForItem(
                        itemId = retryItemId,
                        requestedMediaSourceId = retryLaunchIntent.mediaSourceId,
                        requestedAudioStreamIndex = retryLaunchIntent.initialAudioStreamIndex,
                        requestedSubtitleSelection = retryLaunchIntent.initialSubtitleSelection,
                        startPositionTicks = retryLaunchIntent.startPositionTicks,
                        resetReporting = false,
                    )
                ) {
                    observePlaybackState()
                    if (!isKidsSingleAsset) {
                        fetchPlaylistMetadata()
                    }
                }
            }
    }

    /**
     * Replaces the single Kids asset without creating another player route or controller owner.
     * The accepted target is published before planning so recommendation selection cannot drift
     * when a newer request cancels an older one.
     */
    private fun selectPlaybackAsset(
        itemId: String,
        offlineDownloadId: DownloadId? = null,
        restartFromBeginning: Boolean = false,
        allowCurrentTargetRetry: Boolean = false,
    ): Boolean =
        selectKidsPlaybackTarget(
            itemId = itemId,
            offlineDownloadId = offlineDownloadId,
            restartFromBeginning = restartFromBeginning,
            allowCurrentTargetRetry = allowCurrentTargetRetry,
            launchOptions = null,
        )

    /**
     * Admits a later Kids Play request into this session. The install lock is only an admission
     * barrier: selection runs after it is released, with no intervening suspension, so the
     * existing selection generations own the accepted replacement.
     */
    private suspend fun acceptKidsLaunch(
        itemId: String,
        options: PlayerLaunchOptions,
    ): Boolean {
        val epoch = ++externalLaunchEpoch
        controllerInstallMutex.withLock {}
        currentCoroutineContext().ensureActive()
        val rejection =
            when {
                epoch != externalLaunchEpoch -> "superseded"
                externalLaunchesClosed || disposed -> "closed"
                !isKidsSingleAsset || options.launchPolicy != PlayerLaunchPolicy.KidsSingleAsset -> "ineligible"
                else -> null
            }
        val accepted =
            rejection == null &&
                selectKidsPlaybackTarget(
                    itemId = itemId,
                    offlineDownloadId = options.offlineDownloadId,
                    restartFromBeginning = options.offlineRestartFromBeginning && options.offlineDownloadId != null,
                    allowCurrentTargetRetry = true,
                    launchOptions = options,
                )
        val result = if (accepted) "accepted" else rejection ?: "refused"
        playerViewModelLogger.i { "stage=kids-launch event=admission platform=shared result=$result" }
        return accepted
    }

    private fun selectKidsPlaybackTarget(
        itemId: String,
        offlineDownloadId: DownloadId?,
        restartFromBeginning: Boolean,
        allowCurrentTargetRetry: Boolean,
        launchOptions: PlayerLaunchOptions?,
    ): Boolean {
        if (!isKidsSingleAsset || !canAcceptPlaybackStart()) {
            return false
        }
        val normalizedItemId = itemId.trim()
        if (normalizedItemId.isEmpty()) return false

        val target = PlayerPlaybackTarget(normalizedItemId, offlineDownloadId)
        val retainsCurrentSelection = target == _selectedPlaybackTarget.value
        if (retainsCurrentSelection && !restartFromBeginning && !allowCurrentTargetRetry) return false

        // An explicit launch replaces runtime selections, even for the title already playing.
        val retainsRuntimeSelection = retainsCurrentSelection && launchOptions == null
        val targetLaunchIntent =
            launchOptions ?: launchIntent.takeIf { retainsCurrentSelection && target == launchIntentTarget }
        val retainedSourceId =
            selectedMediaSourceId.takeIf { retainsRuntimeSelection && offlineDownloadId == null }
                ?: targetLaunchIntent?.mediaSourceId?.takeIf { offlineDownloadId == null }
        // Offline startup retains artifact tracks directly. Online startup receives only explicit
        // intent here; current playback values travel separately so they are never saved as choices.
        val retainedAudioStreamIndex =
            requestedAudioStreamIndex.takeIf { retainsRuntimeSelection && offlineDownloadId != null }
                ?: targetLaunchIntent?.initialAudioStreamIndex
        val retainedSubtitleSelection =
            requestedSubtitleSelection.takeIf {
                retainsRuntimeSelection && offlineDownloadId != null && it != SubtitleSelectionIntent.Unspecified
            }
                ?: targetLaunchIntent?.initialSubtitleSelection
                ?: SubtitleSelectionIntent.Unspecified
        val retainedKidsSelection =
            selectedMediaSourceId
                ?.takeIf { sourceId -> retainsRuntimeSelection && offlineDownloadId == null && sourceId.isNotBlank() }
                ?.let { sourceId ->
                    RetainedKidsPlaybackSelection(
                        mediaSourceId = sourceId,
                        audioStreamIndex = requestedAudioStreamIndex,
                        subtitleSelection = requestedSubtitleSelection,
                    )
                }
        val retainedOfflineSidecarIdentity =
            (plan?.plannedSubtitle as? PlannedSubtitle.OfflineSidecar)
                ?.takeIf { retainsRuntimeSelection && offlineDownloadId != null }
                ?.identity
        val retainsPendingRestartFromBeginning =
            retainsRuntimeSelection &&
                forceStartFromBeginning &&
                target == pendingRestartFromBeginningTarget
        val startsFromBeginning = restartFromBeginning || retainsPendingRestartFromBeginning
        val hasCurrentTargetPositionProof = retainsRuntimeSelection && target == playingPositionOwner
        val retainedStartPositionTicks =
            when {
                startsFromBeginning -> 0L
                launchOptions != null -> launchOptions.startPositionTicks.coerceAtLeast(0L)
                !retainsCurrentSelection -> 0L
                hasCurrentTargetPositionProof ->
                    millisecondsToTicks(playerController.playbackState.value.positionMs)
                        .takeIf { positionTicks -> positionTicks > 0L }
                        ?: currentStartPositionTicks
                else -> currentStartPositionTicks
            }
        val outgoingPositionMs = playerController.playbackState.value.positionMs
        recoveryClosedByStop = false
        val selectionGeneration = ++selectedPlaybackGeneration
        externalLaunchEpoch += 1L

        if (launchOptions != null) {
            launchIntentTarget = target
            launchIntent = launchOptions
        } else if (!retainsCurrentSelection) {
            launchIntentTarget = null
        }
        if (!retainsRuntimeSelection) {
            clearPlayingPositionProof()
        }
        forceStartFromBeginning = startsFromBeginning
        pendingRestartFromBeginningTarget = target.takeIf { startsFromBeginning }
        pendingRestartFromBeginningLaunchGeneration = null
        _selectedPlaybackTarget.value = target
        currentItemId = target.itemId
        currentStartPositionTicks = retainedStartPositionTicks
        currentOfflineDownloadId = offlineDownloadId
        invalidateBackendSwitch()
        autoplayGeneration += 1L
        queueSwitchInFlight = true
        reportingJob?.cancel()
        reportingJob = null
        queueSwitchJob?.cancel()
        queueSwitchJob = null
        replanJob?.cancel()
        replanJob = null
        derivedEpisodeQueueJob?.cancel()
        derivedEpisodeQueueJob = null
        playlistMetadataJob?.cancel()
        playlistMetadataJob = null
        cancelAndInvalidateSubtitleFallback()
        timingCoordinator.cancelLoad()
        endPlaybackHealthSession()
        playbackLaunchMarker = null
        activePlaybackTimelineFacts = null
        launchToFirstFrameMs = null
        plan = null
        installedPlan = null
        selectedMediaSourceId = null
        selectedSourceContainer = null
        mediaStreams = emptyList()
        requestedAudioStreamIndex = null
        installedAudioStreamIndex = null
        requestedSubtitleStreamIndex = null
        requestedSubtitleSelection = SubtitleSelectionIntent.Unspecified
        requestedLocalSubtitleAsset = null
        localSubtitleAssets = emptyList()
        offlineSidecarOption = null
        explicitAudioStreamIndex = null
        chapters = emptyList()
        mediaSegments = emptyList()
        trickplay = null
        trickplayByMediaSourceId = emptyMap()
        playlist = null
        queueIds = listOf(normalizedItemId)
        currentQueueIndex = 0
        reduce(PlayerChange.Loading)

        queueSwitchJob =
            viewModelScope.launch {
                playerController.stop()
                playbackReportingCoordinator.stopNow(positionMs = outgoingPositionMs)
                if (!isSelectedPlaybackTarget(target, selectionGeneration)) return@launch
                if (
                    startPlaybackForItem(
                        itemId = target.itemId,
                        requestedMediaSourceId = retainedSourceId,
                        requestedAudioStreamIndex = retainedAudioStreamIndex,
                        requestedSubtitleSelection = retainedSubtitleSelection,
                        startPositionTicks = retainedStartPositionTicks,
                        resetReporting = true,
                        retainedOfflineSidecarIdentity = retainedOfflineSidecarIdentity,
                        retainedKidsSelection = retainedKidsSelection,
                    )
                ) {
                    if (isSelectedPlaybackTarget(target, selectionGeneration)) {
                        observePlaybackState()
                    }
                }
            }
        return true
    }

    /**
     * Records an accepted Kids online track action as the target's latest explicit intent, so it
     * survives a startup failure. Null audio or Unspecified subtitle leaves that choice unchanged.
     */
    private fun updateKidsTrackIntent(
        audioStreamIndex: Int? = null,
        subtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
    ) {
        if (!isKidsSingleAsset || currentOfflineDownloadId != null) return
        val target = PlayerPlaybackTarget(currentItemId, null)
        val intent =
            launchIntent.takeIf { target == launchIntentTarget }
                ?: PlayerLaunchOptions(
                    startPositionTicks = currentStartPositionTicks,
                    mediaSourceId = selectedMediaSourceId,
                    launchPolicy = PlayerLaunchPolicy.KidsSingleAsset,
                )
        launchIntentTarget = target
        launchIntent =
            intent.copy(
                mediaSourceId = selectedMediaSourceId,
                initialAudioStreamIndex = audioStreamIndex ?: intent.initialAudioStreamIndex,
                initialSubtitleSelection =
                    subtitleSelection.takeUnless { selection -> selection == SubtitleSelectionIntent.Unspecified }
                        ?: intent.initialSubtitleSelection,
            )
    }

    private fun retryCurrentPlayback(): Boolean =
        selectPlaybackAsset(
            itemId = currentItemId,
            offlineDownloadId = currentOfflineDownloadId,
            restartFromBeginning = false,
            allowCurrentTargetRetry = true,
        )

    private fun replayCurrentPlayback(): Boolean =
        selectPlaybackAsset(
            itemId = currentItemId,
            offlineDownloadId = currentOfflineDownloadId,
            restartFromBeginning = true,
            allowCurrentTargetRetry = true,
        )

    private fun isSelectedPlaybackTarget(
        target: PlayerPlaybackTarget,
        generation: Long,
    ): Boolean =
        !disposed &&
            selectedPlaybackGeneration == generation &&
            _selectedPlaybackTarget.value == target

    private fun clearPendingRestartFromBeginning() {
        forceStartFromBeginning = false
        pendingRestartFromBeginningTarget = null
        pendingRestartFromBeginningLaunchGeneration = null
    }

    private fun clearPlayingPositionProof() {
        playingPositionOwner = null
    }

    private fun capturePlayingPositionForCurrentTarget(positionMs: Long) {
        if (!isKidsSingleAsset) return
        val target = PlayerPlaybackTarget(currentItemId, currentOfflineDownloadId)
        if (_selectedPlaybackTarget.value != target) return
        playingPositionOwner = target
        currentStartPositionTicks = millisecondsToTicks(positionMs)
    }

    private fun consumeRestartFromBeginningIntentForPlayingTarget() {
        val supportsRestartFromBeginning = isKidsSingleAsset || currentOfflineDownloadId != null
        if (
            !supportsRestartFromBeginning ||
            !forceStartFromBeginning ||
            pendingRestartFromBeginningTarget != PlayerPlaybackTarget(currentItemId, currentOfflineDownloadId) ||
            pendingRestartFromBeginningLaunchGeneration != playbackLaunchGeneration
        ) {
            return
        }
        clearPendingRestartFromBeginning()
    }

    private fun playQueueItem(index: Int) {
        if (isKidsSingleAsset || !canAcceptPlaybackStart()) return
        if (queueIds.size <= 1 || index !in queueIds.indices) {
            return
        }
        stillWatchingState = stillWatchingState.resetForManualNavigation()
        // A newer queue switch cancels the in-flight plan and prepare.
        invalidateSubtitleFallback()
        startQueueSwitch(
            index = index,
            stopPositionMs = playerController.playbackState.value.positionMs,
            explicitPlaybackAction = true,
        )
    }

    /** False keeps Up Next visible when the switch is refused. */
    private fun playNext(
        auto: Boolean = false,
        expectedGeneration: Long? = null,
    ): Boolean {
        if (isKidsSingleAsset || controllerInstallInFlight) return false
        return playNext(
            auto = auto,
            stopPositionMs = playerController.playbackState.value.positionMs,
            expectedGeneration = expectedGeneration,
        )
    }

    private fun confirmStillWatching() {
        if (!canAcceptPlaybackStart()) return
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
                explicitPlaybackAction = true,
            )
        }
    }

    // Auto-skip fires once per segment identity in each item session.
    private fun maybeAutoSkipSegment(enrichedPlaybackState: PlaybackState) {
        if (enrichedPlaybackState.status != PlaybackStatus.Playing || queueSwitchInFlight) return
        val segment = enrichedPlaybackState.currentSegment ?: return
        if (activePlaybackPreferences.policyFor(segment.type) != SegmentSkipPolicy.AutoSkip) return
        val key = "${segment.type.name}:${segment.startTicks}:${segment.endTicks}"
        if (!autoSkippedSegmentKeys.add(key)) return
        seekTo(segment.endMs)
    }

    private fun skipCurrentSegment() {
        if (controllerInstallInFlight) return
        val segment = playbackState.value.currentSegment ?: return
        seekTo(segment.endMs)
    }

    private fun setResizeMode(mode: PlayerResizeMode) {
        if (controllerInstallInFlight || !playerController.supportsVideoSizing) return
        invalidateBackendSwitch()
        resizeMode = mode
        if (state.value is PlayerUiState.Content) {
            publishContent()
        }
    }

    private fun cycleResizeMode(twoState: Boolean = false) {
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

    private fun shuffleQueue() {
        if (isKidsSingleAsset) return
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

    private fun stop() {
        // Close before invalidation so deferred Stop already refuses recovery and drops PiP evidence.
        recoveryClosedByStop = true
        pendingPictureInPictureRecoveryTrigger = null
        externalLaunchEpoch += 1L
        externalLaunchesClosed = true
        clearPlayingPositionProof()
        invalidateBackendSwitch()
        timingCoordinator.cancelLoad()
        if (controllerInstallInFlight || controllerInstallMutex.isLocked) {
            stopRequestedDuringControllerInstall = true
            return
        }
        performStop(stopController = true)
    }

    private fun performStop(stopController: Boolean) {
        recoveryClosedByStop = true
        clearSoftwarePlaybackRecovery(SoftwarePlaybackRecoveryDecision.Stopped)
        selectedPlaybackGeneration += 1L
        clearPendingRestartFromBeginning()
        clearPlayingPositionProof()
        queueSwitchJob?.cancel()
        queueSwitchJob = null
        cancelAndInvalidateSubtitleFallback()
        timingCoordinator.cancelLoad()
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

    private fun showPicker(picker: PlayerPicker) {
        if (_softwarePlaybackRecovery.value != null) return
        if (picker == PlayerPicker.Backend && !canOpenBackendPicker()) {
            return
        }
        publishContent(pickerVisible = picker)
    }

    private fun hidePicker() {
        publishContent(pickerVisible = PlayerPicker.None)
    }

    /** Starts a session-only remote backend replacement without touching preferences. */
    private fun selectBackend(targetBackend: PlayerBackend) {
        if (_softwarePlaybackRecovery.value != null) return
        startBackendSwitch(targetBackend)
    }

    private fun startBackendSwitch(
        targetBackend: PlayerBackend,
        recoveryToken: Long? = null,
    ): Boolean {
        val policy = deviceProfileProvider?.backendPolicy ?: return false
        val currentPlan = installedPlan ?: return false
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
            return false
        }

        replanJob?.cancel()
        replanJob = null

        val switch =
            BackendSwitchSnapshot(
                generation = ++backendSwitchGeneration,
                launchGeneration = playbackLaunchGeneration,
                diagnosticSession = playbackDiagnosticSession,
                itemId = currentItemId,
                mediaSourceId = currentPlan.mediaSourceId,
                activePlan = currentPlan,
                activeController = playerController,
                wasActiveAtRequest = playbackState.value.status in playbackChangeActiveStatuses,
                explicitAudioStreamIndex = explicitAudioStreamIndex,
                requestedAudioStreamIndex = requestedAudioStreamIndex,
                requestedSubtitleSelection = requestedSubtitleSelection,
                requestedLocalSubtitleAsset = requestedLocalSubtitleAsset,
                qualityPolicy =
                    if (recoveryToken != null || qualitySession.isExplicitSessionChoice) {
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
                softwareRecoveryToken = recoveryToken,
                recoveryQuality = qualitySession.takeIf { recoveryToken != null },
                recoveryBudget = autoRecoveryState.takeIf { recoveryToken != null },
            )
        backendSwitchInProgress = true
        playbackChangeNotice = null
        publishContent(pickerVisible = if (recoveryToken == null) PlayerPicker.Backend else PlayerPicker.None)
        backendSwitchJob =
            viewModelScope.launch {
                var prepared = false
                try {
                    prepared = switchBackend(switch)
                } finally {
                    if (recoveryToken != null && _softwarePlaybackRecovery.value?.token == recoveryToken) {
                        if (prepared && backend == PlayerBackend.ExoPlayer && isCurrentBackendSwitch(switch)) {
                            clearSoftwarePlaybackRecovery(SoftwarePlaybackRecoveryDecision.SwitchPrepared)
                        } else {
                            val currentMpvAvailable =
                                backend == PlayerBackend.Mpv &&
                                    installedPlan != null &&
                                    playerControllerFieldOwned &&
                                    state.value !is PlayerUiState.Error &&
                                    playbackState.value.status !in backendSwitchTerminalStatuses
                            if (currentMpvAvailable) {
                                desiredPlayWhenReady = false
                                playerController.pause()
                            }
                            _softwarePlaybackRecovery.value =
                                _softwarePlaybackRecovery.value?.copy(
                                    switching = false,
                                    switchFailed = true,
                                    canSwitch = currentMpvAvailable && PlayerBackend.ExoPlayer in availableBackendsForSession,
                                    canContinue = currentMpvAvailable,
                                )
                            playerDiagnosticsRecorder.recordSoftwarePlaybackRecovery(
                                playerDiagnosticContext(),
                                SoftwarePlaybackRecoveryDecision.SwitchFailed,
                            )
                        }
                    }
                }
                // Fallback can replan, so it waits until the switch outcome is reported.
                val switchedPlan = installedPlan
                if (prepared && isCurrentBackendSwitch(switch) && switchedPlan != null) {
                    maybeStartInstalledActivationFallback(playerController, switchedPlan)
                }
            }
        return true
    }

    private fun selectAudio(streamIndex: Int) {
        if (controllerInstallInFlight || queueSwitchInFlight) return
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
        updateKidsTrackIntent(audioStreamIndex = streamIndex)
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
            reduce(PlayerChange.Failed(PlayerUiState.Error(error = PlaybackError.UnsupportedMedia)))
            return
        }
        invalidateSubtitleFallback()
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
        if (offlineReprepareGeneration == null) {
            playerController.selectEmbeddedAudio(
                EmbeddedAudioSelection(target = target, descriptor = descriptor),
            )
        }
        reloadTimingForCurrentTracks()
        publishContent(pickerVisible = PlayerPicker.None)
    }

    /** Offline subtitles require a fresh trusted artifact lease. */
    private fun selectOfflineSubtitle(
        currentPlan: PlaybackPlan,
        option: SubtitleTrackOption?,
    ) {
        if (currentPlan.offlineArtifactKind != DownloadArtifactKind.OriginalFile) return
        if (option?.isExternal == true) return
        if (option == null && currentPlan.plannedSubtitle is PlannedSubtitle.Off) return
        if (
            option != null &&
            (currentPlan.plannedSubtitle as? PlannedSubtitle.Track)?.streamIndex == option.streamIndex
        ) {
            return
        }
        val targetAndDescriptor =
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
                target to descriptor
            }
        val target = targetAndDescriptor?.first
        val replacementPlan =
            currentPlan.copy(
                selectedSubtitleStreamIndex = option?.streamIndex,
                subtitleAsset = null,
                subtitleActivationTarget = target,
                plannedSubtitle =
                    targetAndDescriptor?.let { (_, descriptor) ->
                        PlannedSubtitle.Track(
                            streamIndex = option?.streamIndex ?: return,
                            embeddedTrack = descriptor,
                            deliveryMethod = SubtitleDeliveryMethod.Embed,
                            kind = subtitleKind(descriptor.codec),
                            activationTarget = target,
                            normalizedFormat = descriptor.codec,
                        )
                    } ?: PlannedSubtitle.Off,
            )
        scheduleOfflineSubtitleReprepare(
            replacementPlan = replacementPlan,
            requestedSelection = option?.streamIndex.toSubtitleSelectionIntent(),
        )
    }

    private fun selectOfflineSidecar() {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        val currentPlan = plan ?: return
        val option = offlineSidecarOption ?: return
        if (
            currentPlan.streamMode != StreamMode.Offline ||
            currentPlan.offlineArtifactKind != DownloadArtifactKind.OriginalFile ||
            currentPlan.offlineArtifactRef != option.identity.artifactRef
        ) {
            return
        }
        val installedSidecar = currentPlan.plannedSubtitle as? PlannedSubtitle.OfflineSidecar
        if (installedSidecar?.identity == option.identity) return
        val target =
            SubtitleActivationTarget(
                requestId = nextSubtitleActivationRequestId(),
                itemId = currentItemId,
                identity = option.identity,
                kind = LocalSubtitleKind.ExternalText,
            )
        scheduleOfflineSubtitleReprepare(
            replacementPlan =
                currentPlan.copy(
                    selectedSubtitleStreamIndex = null,
                    subtitleAsset = null,
                    subtitleActivationTarget = target,
                    plannedSubtitle =
                        PlannedSubtitle.OfflineSidecar(
                            identity = option.identity,
                            label = option.displayName,
                            language = option.language,
                            activationTarget = target,
                        ),
                ),
            requestedSelection = SubtitleSelectionIntent.Off,
        )
    }

    private fun scheduleOfflineSubtitleReprepare(
        replacementPlan: PlaybackPlan,
        requestedSelection: SubtitleSelectionIntent,
    ) {
        val artifactRef = replacementPlan.offlineArtifactRef ?: return
        if (
            replacementPlan.streamMode != StreamMode.Offline ||
            plan?.offlineArtifactRef != artifactRef
        ) {
            return
        }
        val retainedPositionMs =
            if (offlineReprepareGeneration != null) {
                offlineRepreparePositionMs ?: playbackState.value.positionMs.coerceAtLeast(0L)
            } else {
                playbackState.value.positionMs.coerceAtLeast(0L)
            }
        invalidateSubtitleFallback()
        replanJob?.cancel()
        requestedSubtitleStreamIndex = replacementPlan.selectedSubtitleStreamIndex
        requestedLocalSubtitleAsset = null
        requestedSubtitleSelection = requestedSelection
        val selectionGeneration = ++offlineSubtitleSelectionGeneration
        offlineReprepareGeneration = selectionGeneration
        offlineRepreparePositionMs = retainedPositionMs
        val launchGeneration = playbackLaunchGeneration
        val itemId = currentItemId
        val controller = playerController
        val replacementAtPosition =
            replacementPlan.copy(
                startPositionMs = retainedPositionMs,
                playbackSpeed = playbackSpeed,
                subtitleStyle = subtitleStyle,
            )
        replanJob =
            viewModelScope.launch {
                prepareOfflinePlayback(
                    playbackPlan = replacementAtPosition,
                    selectionGeneration = selectionGeneration,
                    launchGeneration = launchGeneration,
                    itemId = itemId,
                    controller = controller,
                    reportingAuthority = OfflinePrepareReportingAuthority.PreserveCurrent,
                    resetReporting = false,
                    unavailableBackend =
                        backend.takeUnless { active -> active == PlayerBackend.Auto }
                            ?: controller.activeBackend.takeUnless { active -> active == PlayerBackend.Auto }
                            ?: PlayerBackend.AVPlayer,
                )
            }
    }

    private fun selectSubtitle(streamIndex: Int?) {
        if (controllerInstallInFlight || queueSwitchInFlight) return
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
        updateKidsTrackIntent(subtitleSelection = requestedSubtitleSelection)
        persistSubtitleSelection(requestedSubtitleSelection)
        rememberSelection(persistDurable = false)

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

    private fun selectLocalSubtitle(assetId: String) {
        if (controllerInstallInFlight || queueSwitchInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        val sourceId = selectedMediaSourceId?.takeIf(String::isNotBlank) ?: return
        val context = LocalSubtitleContext(session.serverId, session.userId, currentItemId, sourceId)
        invalidateSubtitleFallback()
        replanJob?.cancel()
        replanJob =
            viewModelScope.launch {
                val asset = withContext(workDispatcher) { getLocalSubtitleAssetUseCase?.invoke(assetId, context) }
                if (controllerInstallInFlight || queueSwitchInFlight) return@launch
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
                updateKidsTrackIntent(subtitleSelection = requestedSubtitleSelection)
                persistSubtitleSelection(requestedSubtitleSelection)
                playerController.selectEmbeddedSubtitle(null)
                reloadTimingForCurrentTracks()
                publishContent(pickerVisible = PlayerPicker.None)
                // Clear the shared job slot before the replan replaces it.
                replanJob = null
                replanAtCurrentPosition()
            }
    }

    private fun selectQuality(maxBitrateBps: Long?) {
        if (controllerInstallInFlight || queueSwitchInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        requestQualityChange(qualitySession.selectFixedOrOriginal(maxBitrateBps))
    }

    private fun selectQuality(policy: PlaybackQualityPolicy) {
        if (controllerInstallInFlight || queueSwitchInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        val normalized = policy.normalized()
        val proposedQuality =
            when (normalized.mode) {
                PlaybackQualityMode.Auto -> qualitySession.selectAuto()
                PlaybackQualityMode.Fixed -> qualitySession.selectFixedOrOriginal(normalized.maxBitrateBps)
                PlaybackQualityMode.Original -> qualitySession.selectFixedOrOriginal(maximumBitrateBps = null)
            }
        requestQualityChange(proposedQuality)
    }

    private fun acceptAutoPlayback() {
        if (controllerInstallInFlight || queueSwitchInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        requestQualityChange(qualitySession.selectAuto())
    }

    private fun clearQualityOverride() {
        if (controllerInstallInFlight || queueSwitchInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        val inheritedPolicy = activePlaybackPreferences.effectiveDefaultQualityPolicy(backend)
        requestQualityChange(qualitySession.inheritLaunchOrDefault(inheritedPolicy))
    }

    private fun keepRecoveredQualityForSession() {
        if (controllerInstallInFlight || queueSwitchInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        val bitrate = autoRecoveryState.runtimeQualityCapBps ?: qualitySession.maximumBitrateBps ?: return
        requestQualityChange(qualitySession.retainRecoveredQuality(bitrate))
    }

    private fun tryHigherQualityOrOriginal() {
        if (controllerInstallInFlight || queueSwitchInFlight) return
        invalidateBackendSwitch()
        if (plan?.streamMode == StreamMode.Offline) return
        requestQualityChange(
            proposedQualitySessionState = qualitySession.retryUncappedAuto(),
            preserveRecoveryBudgetOnCommit = true,
        )
    }

    private fun requestQualityChange(
        proposedQualitySessionState: PlayerQualitySessionState,
        preserveRecoveryBudgetOnCommit: Boolean = false,
    ) {
        val clearedPreviousRejection = playbackChangeNotice != null
        playbackChangeNotice = null
        if (clearedPreviousRejection) {
            publishContent()
        }
        replanAtCurrentPosition(
            proposedQualitySessionState = proposedQualitySessionState,
            preserveRecoveryBudgetOnQualityCommit = preserveRecoveryBudgetOnCommit,
        )
    }

    private fun dismissPlaybackActionNotice() {
        if (playbackChangeNotice != null) {
            playbackChangeNotice = null
        } else {
            playbackActionNotice = null
        }
        publishContent()
    }

    private fun retryPlaybackFromNotice() {
        if (!canAcceptPlaybackStart()) return
        recoveryClosedByStop = false
        invalidateBackendSwitch()
        playbackActionNotice = null
        playbackSessionRecoveryState =
            playbackSessionRecoveryPolicy.reset(playbackLaunchGeneration, currentItemId)
        playerController.retry()
        playerController.runtimeDiagnostics.value.prepareEpoch
            ?.let(playbackHealthCoordinator::expectVideoOutput)
        armSoftwarePlaybackRecovery()
        armFirstVideoOutputState()
    }

    private fun handlePlaybackAction(action: PlaybackAction) {
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

    private fun dismissPlaybackGuidance() {
        playbackHealthCoordinator.dismissGuidance()
    }

    /** Defers actionable recovery until the full player UI can present it. */
    private fun setPictureInPictureMode(inPictureInPicture: Boolean) {
        excludeSoftwarePlaybackProgress()
        pictureInPictureMode = inPictureInPicture
        if (!inPictureInPicture) {
            val pendingTrigger = pendingPictureInPictureRecoveryTrigger ?: return
            pendingPictureInPictureRecoveryTrigger = null
            handleAutomaticRecoveryTrigger(pendingTrigger)
        }
    }

    private fun setPlaybackSpeed(speed: Float) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        if (!speed.isFinite()) {
            return
        }
        excludeSoftwarePlaybackProgress()
        playbackSpeed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        plan = plan?.copy(playbackSpeed = playbackSpeed)
        if (offlineReprepareGeneration == null) {
            playerController.setPlaybackSpeed(playbackSpeed)
        }
        publishContent(playbackState = playbackState.value.copy(playbackSpeed = playbackSpeed))
    }

    private fun setSubtitleStyle(style: SubtitleStyle) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        subtitleStyle = style
        plan = plan?.copy(subtitleStyle = subtitleStyle)
        if (offlineReprepareGeneration == null) {
            playerController.setSubtitleStyle(style)
        }
        publishContent(playbackState = playbackState.value.copy(subtitleStyle = subtitleStyle))
    }

    private fun adjustAudioTiming(deltaMs: Long) {
        adjustTiming(PlaybackTimingKind.Audio, deltaMs)
    }

    private fun adjustSubtitleTiming(deltaMs: Long) {
        adjustTiming(PlaybackTimingKind.Subtitle, deltaMs)
    }

    private fun resetAudioTiming() {
        setTiming(PlaybackTimingKind.Audio, 0L)
    }

    private fun resetSubtitleTiming() {
        setTiming(PlaybackTimingKind.Subtitle, 0L)
    }

    private fun adjustTiming(
        kind: PlaybackTimingKind,
        deltaMs: Long,
    ) {
        if (controllerInstallInFlight || queueSwitchInFlight) return
        timingCoordinator.adjust(kind, deltaMs)
    }

    private fun setTiming(
        kind: PlaybackTimingKind,
        offsetMs: Long,
    ) {
        if (controllerInstallInFlight || queueSwitchInFlight) return
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

    private suspend fun loadTimingOffsets(): Boolean = timingCoordinator.load()

    private fun reloadTimingForCurrentTracks() {
        timingCoordinator.reloadForCurrentTracks()
    }

    fun dispose() {
        recoveryClosedByStop = true
        externalLaunchEpoch += 1L
        externalLaunchesClosed = true
        clearSoftwarePlaybackRecovery(SoftwarePlaybackRecoveryDecision.Superseded)
        if (disposed) return
        invalidateBackendSwitch()
        disposed = true
        selectedPlaybackGeneration += 1L
        clearPendingRestartFromBeginning()
        clearPlayingPositionProof()
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
        queueSwitchJob?.cancel()
        queueSwitchJob = null
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
        queueSwitchJob =
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
                    if (currentOfflineDownloadId == null && !isKidsSingleAsset) {
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
        retainedOfflineSidecarIdentity: SubtitleActivationIdentity.OfflineSidecar? = null,
        retainedKidsSelection: RetainedKidsPlaybackSelection? = null,
    ): Boolean {
        if (!isKidsSingleAsset && currentOfflineDownloadId == null) {
            launchIntentTarget = PlayerPlaybackTarget(itemId, null)
            launchIntent =
                PlayerLaunchOptions(
                    startPositionTicks = startPositionTicks,
                    mediaSourceId = requestedMediaSourceId,
                    initialAudioStreamIndex = requestedAudioStreamIndex,
                    initialSubtitleSelection = requestedSubtitleSelection,
                )
        }
        clearSoftwarePlaybackRecovery(SoftwarePlaybackRecoveryDecision.Superseded)
        invalidateBackendSwitch()
        emitPlaybackHealthSummary()
        activePlaybackTimelineFacts = null
        playbackLaunchGeneration += 1L
        val launchGeneration = playbackLaunchGeneration
        playbackDiagnosticSession = PlaybackDiagnosticSessions.allocate()
        val launchDiagnosticSession = playbackDiagnosticSession
        if (
            forceStartFromBeginning &&
            pendingRestartFromBeginningTarget == PlayerPlaybackTarget(itemId, currentOfflineDownloadId)
        ) {
            pendingRestartFromBeginningLaunchGeneration = launchGeneration
        }
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
        playbackChangeNotice = null
        if (currentOfflineDownloadId != null) {
            return startOfflinePlaybackForItem(
                itemId = itemId,
                requestedAudioStreamIndex = requestedAudioStreamIndex,
                requestedSubtitleSelection = requestedSubtitleSelection,
                startPositionTicks = startPositionTicks,
                launchGeneration = launchGeneration,
                launchDiagnosticSession = launchDiagnosticSession,
                resetReporting = resetReporting,
                retainedOfflineSidecarIdentity = retainedOfflineSidecarIdentity,
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
        // A superseded detail read must not publish an Error over the newer launch.
        currentCoroutineContext().ensureActive()
        if (!isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            segmentsDeferred.cancel()
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        val selectedVersion = detail?.selectedVersion(requestedMediaSourceId)

        if (selectedVersion == null) {
            clearPlaybackLaunch(launchGeneration)
            segmentsDeferred.cancel()
            reduce(PlayerChange.Failed(PlayerUiState.Error(error = PlaybackError.UnsupportedMedia)))
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
        // Resolve launch audio before the backend so Apple Auto routes on the track that will play.
        val durableSelection = launchContext.playbackSelection
        val rememberedSelection =
            resolvePlaybackSelection(
                durableSelection = durableSelection,
                sourceSelection = playbackSelectionMemory.sourceSelectionFor(accountIdentity, itemId, selectedVersion.id),
                legacyItemSelection = playbackSelectionMemory.selectionFor(accountIdentity, itemId),
                durableStoreAvailable = launchContext.playbackSelectionOutcome != PlaybackLaunchReadOutcome.Unavailable,
            )
        // A Kids Retry/Replay hint applies only to this attempt on its own source; it is never saved.
        val retainedSelection = retainedKidsSelection?.takeIf { hint -> hint.mediaSourceId == selectedVersion.id }
        val audioTrackOptions = audioOptions(mediaStreams)
        val launchAudioSelection =
            resolveLaunchAudioSelection(
                explicitLaunchAudioStreamIndex = requestedAudioStreamIndex,
                durableSelection = durableSelection,
                rememberedSelection = rememberedSelection,
                audioTrackOptions = audioTrackOptions,
                preferredAudioStreamIndex =
                    audioTrackOptions.preferredAudioStreamIndex(playbackPreferences.preferredAudioLanguage),
                retainedAudioStreamIndex = retainedSelection?.audioStreamIndex,
            )
        val backendResolved =
            try {
                resolveBackendForSession(
                    selectedVersion = selectedVersion,
                    launchAudioStreamIndex = launchAudioSelection.requestedAudioStreamIndex,
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
                // Installer failure can drain a deferred Stop before rethrowing, so recheck ownership.
                currentCoroutineContext().ensureActive()
                if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) return false
                val startupError = startupPlanningError(exception)
                reduce(PlayerChange.Failed(startupError))
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
        // Controller reuse can return after suspended reads; recheck authority before selection writes.
        currentCoroutineContext().ensureActive()
        if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) {
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
            context = playerDiagnosticContext(sessionSequence = launchDiagnosticSession),
            facts =
                PlayerPersistenceDiagnosticFacts(
                    event = PlaybackDiagnosticEvent.Read,
                    target = PlaybackPersistenceTarget.PlaybackSelection,
                    result = launchContext.playbackSelectionOutcome.toPersistenceResult(),
                ),
        )
        val subtitleTrackOptions = subtitleOptions(mediaStreams)
        requestedLocalSubtitleAsset = null
        explicitAudioStreamIndex = launchAudioSelection.explicitAudioStreamIndex
        val subtitleSelectionKey = subtitleSelectionKey(itemId = itemId, mediaSourceId = selectedVersion.id)
        val effectiveSubtitleSelection =
            resolveSubtitleSelection(
                explicit = requestedSubtitleSelection,
                stored = launchContext.subtitleSelection,
                key = subtitleSelectionKey,
                options = subtitleTrackOptions,
                playbackPreferences = playbackPreferences,
                retained = retainedSelection?.subtitleSelection ?: SubtitleSelectionIntent.Unspecified,
            )
        val effectiveSubtitleStreamIndex = (effectiveSubtitleSelection as? SubtitleSelectionIntent.Track)?.streamIndex
        this.requestedAudioStreamIndex = launchAudioSelection.requestedAudioStreamIndex
        installedAudioStreamIndex = null
        audioRecoveryTarget = null
        this.requestedSubtitleStreamIndex = effectiveSubtitleStreamIndex
        this.requestedSubtitleSelection = effectiveSubtitleSelection
        if (requestedSubtitleSelection.isValidFor(subtitleTrackOptions)) {
            // Save the validated raw explicit choice, even when a retained hint is what plays.
            saveSubtitleSelectionAction?.save(subtitleSelectionKey, requestedSubtitleSelection)
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
                diagnosticSessionSequence = launchDiagnosticSession,
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
                currentCoroutineContext().ensureActive()
                if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) return false
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
                reduce(PlayerChange.Failed(startupError))
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
        // Load timing offsets before native prepare. Install admission keeps track and offset
        // commands out of the restore window; the selection it restores must still own prepare.
        val controller = playerController
        val selectionGeneration = selectedPlaybackGeneration
        val restoreAudioStreamIndex = this.requestedAudioStreamIndex
        val restoreSubtitleSelection = this.requestedSubtitleSelection
        val restoreLocalSubtitleAsset = requestedLocalSubtitleAsset

        fun ownsRestore(): Boolean =
            !disposed &&
                isCurrentPlaybackLaunch(launchGeneration, itemId) &&
                concreteControllerInstalled &&
                playerControllerFieldOwned &&
                playerController === controller &&
                selectedPlaybackGeneration == selectionGeneration &&
                plan === playbackPlanWithMetadata &&
                this.requestedAudioStreamIndex == restoreAudioStreamIndex &&
                this.requestedSubtitleSelection == restoreSubtitleSelection &&
                requestedLocalSubtitleAsset == restoreLocalSubtitleAsset

        val restored =
            controllerInstallMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (disposed) {
                    drainStopRequestedDuringControllerInstall(stopController = false)
                    return@withLock false
                }
                // A superseding launch owns any pending Stop.
                if (!isCurrentPlaybackLaunch(launchGeneration, itemId)) return@withLock false
                if (stopRequestedDuringControllerInstall) {
                    drainStopRequestedDuringControllerInstall(stopController = playerControllerFieldOwned)
                    return@withLock false
                }
                if (!ownsRestore()) return@withLock false

                controllerInstallInFlight = true
                playbackHealthCoordinator.suspendForInstallation()
                try {
                    val loaded = loadTimingOffsets()
                    currentCoroutineContext().ensureActive()
                    when {
                        disposed -> {
                            drainStopRequestedDuringControllerInstall(stopController = false)
                            false
                        }
                        !isCurrentPlaybackLaunch(launchGeneration, itemId) -> false
                        // Stop also invalidates the load, so drain before rejecting it.
                        stopRequestedDuringControllerInstall -> {
                            drainStopRequestedDuringControllerInstall(stopController = playerControllerFieldOwned)
                            false
                        }
                        else -> loaded && ownsRestore()
                    }
                } catch (exception: Throwable) {
                    if (disposed || isCurrentPlaybackLaunch(launchGeneration, itemId)) {
                        drainStopRequestedDuringControllerInstall(stopController = false)
                    }
                    throw exception
                } finally {
                    finishControllerInstallation(launchGeneration)
                }
            }
        // Unlocking can run a queued owner; nothing may suspend between this check and prepare.
        currentCoroutineContext().ensureActive()
        if (!restored || !ownsRestore() || controllerInstallInFlight || controllerInstallMutex.isLocked) return false
        excludeSoftwarePlaybackProgress()
        markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Prepare)
        playerDiagnosticsRecorder.recordPrepareRequested(playerDiagnosticContext())
        startupPrepareGeneration = launchGeneration
        try {
            controller.prepare(playbackPlanWithMetadata)
        } finally {
            if (startupPrepareGeneration == launchGeneration) startupPrepareGeneration = null
        }
        // An inline Stop, disposal or replacement during prepare owns the controller now.
        currentCoroutineContext().ensureActive()
        if (
            !admitsPlaybackSessionRecovery() ||
            !isCurrentPlaybackLaunch(launchGeneration, itemId) ||
            playerController !== controller ||
            selectedPlaybackGeneration != selectionGeneration ||
            installedPlan != null
        ) {
            return false
        }
        if (rejectSynchronouslyFailedPrepare(refreshObservation = true)) return false
        installedPlan = playbackPlanWithMetadata
        // A recovery admitted from here on owns this installed plan and takes priority.
        val replanGenerationAtInstall = replanRequestGeneration
        playerController.runtimeDiagnostics.value.prepareEpoch
            ?.let(playbackHealthCoordinator::expectVideoOutput)
        armSoftwarePlaybackRecovery()
        armFirstVideoOutputState()
        playerDiagnosticsRecorder.recordPrepareDispatched(playerDiagnosticContext())
        bindPlaybackLaunchToPrepare(launchGeneration, playerController)
        applyInitialEmbeddedSelections(playbackPlanWithMetadata)
        desiredPlayWhenReady = true
        playerController.play()
        publishContent()
        if (
            replanRequestGeneration == replanGenerationAtInstall &&
            isCurrentPlaybackLaunch(launchGeneration, itemId)
        ) {
            maybeStartInstalledActivationFallback(controller, playbackPlanWithMetadata, reconcileNativeState = true)
        }
        if (!isKidsSingleAsset) {
            deriveEpisodeQueueIfNeeded(detail.item)
        }
        return true
    }

    /** Local-only launch that fails without remote planning or fallback. */
    private suspend fun startOfflinePlaybackForItem(
        itemId: String,
        requestedAudioStreamIndex: Int?,
        requestedSubtitleSelection: SubtitleSelectionIntent,
        startPositionTicks: Long,
        launchGeneration: Long,
        launchDiagnosticSession: Long,
        resetReporting: Boolean,
        retainedOfflineSidecarIdentity: SubtitleActivationIdentity.OfflineSidecar?,
    ): Boolean {
        val downloadId = currentOfflineDownloadId ?: return false
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
        currentCoroutineContext().ensureActive()
        if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            clearPlaybackLaunch(launchGeneration)
            return false
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
        val activationRequestId = nextSubtitleActivationRequestId()
        val startsFromBeginning =
            forceStartFromBeginning &&
                pendingRestartFromBeginningTarget == PlayerPlaybackTarget(itemId, downloadId) &&
                pendingRestartFromBeginningLaunchGeneration == launchGeneration
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
                    localResumePositionMs = if (startsFromBeginning) 0L else record.localResumePositionMs,
                    launchGeneration = launchGeneration,
                    subtitleActivationRequestId = activationRequestId,
                ),
            )
        val retainedOfflineAudioStreamIndex =
            requestedAudioStreamIndex?.takeIf { requestedIndex ->
                offlineProjection.offlinePlan.embeddedAudioTracks.any { track ->
                    track.jellyfinStreamIndex == requestedIndex && track.directPlayAdmissible
                }
            }
        val retainedOfflineSubtitleDescriptor =
            (requestedSubtitleSelection as? SubtitleSelectionIntent.Track)?.let { selection ->
                offlineProjection.offlinePlan.embeddedSubtitleTracks.firstOrNull { track ->
                    track.jellyfinStreamIndex == selection.streamIndex
                }
            }
        val projectedOfflineSidecar = offlineProjection.offlinePlan.plannedSubtitle as? PlannedSubtitle.OfflineSidecar
        val retainsOfflineSidecar =
            projectedOfflineSidecar?.takeIf { sidecar ->
                sidecar.identity == retainedOfflineSidecarIdentity
            }
        val explicitOfflineSubtitleOff =
            requestedSubtitleSelection == SubtitleSelectionIntent.Off && retainsOfflineSidecar == null
        val offlinePlanWithRetainedTracks =
            if (
                retainedOfflineAudioStreamIndex != null ||
                retainedOfflineSubtitleDescriptor != null ||
                explicitOfflineSubtitleOff
            ) {
                offlineProjection.offlinePlan.copy(
                    selectedAudioStreamIndex =
                        retainedOfflineAudioStreamIndex ?: offlineProjection.offlinePlan.selectedAudioStreamIndex,
                    selectedSubtitleStreamIndex =
                        retainedOfflineSubtitleDescriptor?.jellyfinStreamIndex
                            ?: if (explicitOfflineSubtitleOff) null else offlineProjection.offlinePlan.selectedSubtitleStreamIndex,
                    plannedSubtitle =
                        retainedOfflineSubtitleDescriptor?.let { track ->
                            PlannedSubtitle.Track(
                                streamIndex = track.jellyfinStreamIndex,
                                embeddedTrack = track,
                                deliveryMethod = SubtitleDeliveryMethod.Embed,
                                kind = subtitleKind(track.codec),
                                normalizedFormat = track.codec,
                            )
                        } ?: if (explicitOfflineSubtitleOff) PlannedSubtitle.Off else offlineProjection.offlinePlan.plannedSubtitle,
                )
            } else {
                offlineProjection.offlinePlan
            }
        val selectedOfflineSubtitleSelection =
            when (val plannedSubtitle = offlinePlanWithRetainedTracks.plannedSubtitle) {
                is PlannedSubtitle.Track -> SubtitleSelectionIntent.Track(plannedSubtitle.streamIndex)
                else -> SubtitleSelectionIntent.Off
            }
        val launchContext =
            withContext(workDispatcher) {
                getPlaybackLaunchContextUseCase(session = session, itemId = itemId, mediaSourceId = sourceId)
            }
        currentCoroutineContext().ensureActive()
        if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        metadata = offlineProjection.metadata
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
        this.requestedAudioStreamIndex = offlinePlanWithRetainedTracks.selectedAudioStreamIndex
        installedAudioStreamIndex = offlinePlanWithRetainedTracks.selectedAudioStreamIndex
        this.requestedSubtitleStreamIndex = offlinePlanWithRetainedTracks.selectedSubtitleStreamIndex
        this.requestedSubtitleSelection = selectedOfflineSubtitleSelection
        requestedLocalSubtitleAsset = null
        offlineSidecarOption = offlineProjection.offlineSidecarOption
        qualitySession =
            qualitySession.forOfflineSession(activePlaybackPreferences.effectiveDefaultQualityPolicy(backend))
        explicitAudioStreamIndex = offlinePlanWithRetainedTracks.selectedAudioStreamIndex
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
                currentCoroutineContext().ensureActive()
                if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) {
                    clearPlaybackLaunch(launchGeneration)
                    return false
                }
                return failOfflineLaunch(
                    launchGeneration,
                    PlaybackError.OfflinePlayerUnavailable(
                        requiredBackend =
                            backend.takeUnless { it == PlayerBackend.Auto } ?: PlayerBackend.AVPlayer,
                    ),
                )
            }
        currentCoroutineContext().ensureActive()
        if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) {
            clearPlaybackLaunch(launchGeneration)
            return false
        }
        backend = resolvedBackend
        val offlinePlan =
            offlinePlanWithRetainedTracks
                .withAudioActivationTarget(
                    offlinePlanWithRetainedTracks.selectedAudioStreamIndex?.let(::newAudioActivationTarget),
                ).withSubtitleActivationTarget(
                    requestId = activationRequestId,
                    itemId = itemId,
                    selectedSubtitle = selectedSubtitleMediaStream(),
                ).copy(
                    playbackSpeed = playbackSpeed,
                    subtitleStyle = subtitleStyle,
                    diagnosticSessionSequence = launchDiagnosticSession,
                )
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
                // A failed install can drain a deferred Stop first; only a current attempt reports failure.
                currentCoroutineContext().ensureActive()
                if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) return false
                return failOfflineLaunch(
                    launchGeneration,
                    PlaybackError.OfflinePlayerUnavailable(exception.requiredBackend),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                currentCoroutineContext().ensureActive()
                if (disposed || !isCurrentPlaybackLaunch(launchGeneration, itemId)) return false
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
        desiredPlayWhenReady = true
        return prepareOfflinePlayback(
            playbackPlan = offlinePlan,
            selectionGeneration = null,
            launchGeneration = launchGeneration,
            itemId = itemId,
            controller = playerController,
            reportingAuthority = OfflinePrepareReportingAuthority.InstallInitial,
            resetReporting = resetReporting,
            unavailableBackend =
                if (exactOfflineBackendRequired) {
                    resolvedBackend
                } else {
                    activeBackend
                },
        )
    }

    private suspend fun prepareOfflinePlayback(
        playbackPlan: PlaybackPlan,
        selectionGeneration: Long?,
        launchGeneration: Long,
        itemId: String,
        controller: PlayerController,
        reportingAuthority: OfflinePrepareReportingAuthority,
        resetReporting: Boolean,
        unavailableBackend: PlayerBackend,
    ): Boolean {
        val artifactRef = playbackPlan.offlineArtifactRef ?: return false
        if (playbackPlan.streamMode != StreamMode.Offline || playbackPlan.itemId != itemId) {
            clearOfflineReprepare(selectionGeneration)
            return false
        }
        val requestIsCurrent =
            when (reportingAuthority) {
                OfflinePrepareReportingAuthority.InstallInitial ->
                    !disposed &&
                        isCurrentPlaybackLaunch(launchGeneration, itemId) &&
                        playerController === controller
                OfflinePrepareReportingAuthority.PreserveCurrent ->
                    isCurrentOfflinePrepare(
                        artifactRef = artifactRef,
                        selectionGeneration = selectionGeneration,
                        launchGeneration = launchGeneration,
                        itemId = itemId,
                        controller = controller,
                        reportingAuthority = reportingAuthority,
                    )
            }
        if (!requestIsCurrent) {
            clearOfflineReprepare(selectionGeneration)
            return false
        }
        val preparePlan =
            if (reportingAuthority == OfflinePrepareReportingAuthority.PreserveCurrent) {
                plan
                    ?.takeIf { current ->
                        current.streamMode == StreamMode.Offline &&
                            current.itemId == itemId &&
                            current.offlineArtifactRef == artifactRef
                    }?.let { current ->
                        playbackPlan.copy(
                            selectedAudioStreamIndex = current.selectedAudioStreamIndex,
                            audioActivationTarget = current.audioActivationTarget,
                            playbackSpeed = playbackSpeed,
                            subtitleStyle = subtitleStyle,
                        )
                    } ?: playbackPlan
            } else {
                playbackPlan
            }
        try {
            when (reportingAuthority) {
                OfflinePrepareReportingAuthority.InstallInitial ->
                    installPlan(
                        playbackPlan = preparePlan,
                        resetReporting = resetReporting,
                        stabilizesQueueSwitch = true,
                    )
                OfflinePrepareReportingAuthority.PreserveCurrent -> {
                    installedPlan = null
                    plan = preparePlan
                    selectedMediaSourceId = preparePlan.mediaSourceId
                    updatePlaybackHealthSessionContext()
                }
            }
            val loadingState =
                PlaybackState(
                    status = PlaybackStatus.Loading,
                    positionMs = preparePlan.startPositionMs,
                    durationMs = resolvePlaybackDurationMs(preparePlan.contentTimeline, null),
                    bufferedPositionMs = 0L,
                ).withPlaybackMetadata()
            publishContent(playbackState = loadingState, pickerVisible = PlayerPicker.None)
            loadTimingOffsets()
            currentCoroutineContext().ensureActive()
            if (
                !isCurrentOfflinePrepare(
                    artifactRef = artifactRef,
                    selectionGeneration = selectionGeneration,
                    launchGeneration = launchGeneration,
                    itemId = itemId,
                    controller = controller,
                    reportingAuthority = reportingAuthority,
                )
            ) {
                return false
            }
            if (reportingAuthority == OfflinePrepareReportingAuthority.PreserveCurrent) {
                val markerTimeMs = monotonicTimeMs()
                playbackLaunchMarker =
                    PlaybackLaunchMarker(
                        generation = launchGeneration,
                        startedAtMs = markerTimeMs,
                        healthStartedAtMs = markerTimeMs,
                    )
                launchToFirstFrameMs = null
            }
            excludeSoftwarePlaybackProgress()
            markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Prepare)
            playerDiagnosticsRecorder.recordPrepareRequested(playerDiagnosticContext())
            val result = controller.prepareOffline(preparePlan)
            currentCoroutineContext().ensureActive()
            if (
                !isCurrentOfflinePrepare(
                    artifactRef = artifactRef,
                    selectionGeneration = selectionGeneration,
                    launchGeneration = launchGeneration,
                    itemId = itemId,
                    controller = controller,
                    reportingAuthority = reportingAuthority,
                )
            ) {
                return false
            }
            when (result) {
                com.jellyscope.core.domain.playback.OfflinePrepareResult.Started -> Unit
                is com.jellyscope.core.domain.playback.OfflinePrepareResult.Unavailable -> {
                    releaseOwnedPlayerController()
                    return failOfflineLaunch(launchGeneration, result.error)
                }
            }
            val currentPlan =
                plan?.takeIf { candidate ->
                    candidate.streamMode == StreamMode.Offline &&
                        candidate.itemId == itemId &&
                        candidate.offlineArtifactRef == artifactRef
                } ?: return false
            installedPlan = currentPlan
            controller.runtimeDiagnostics.value.prepareEpoch
                ?.let(playbackHealthCoordinator::expectVideoOutput)
            armSoftwarePlaybackRecovery()
            armFirstVideoOutputState()
            playerDiagnosticsRecorder.recordPrepareDispatched(playerDiagnosticContext())
            bindPlaybackLaunchToPrepare(launchGeneration, controller)
            applyInitialEmbeddedSelections(currentPlan)
            controller.setPlaybackSpeed(playbackSpeed)
            controller.setSubtitleStyle(subtitleStyle)
            if (desiredPlayWhenReady) {
                controller.play()
            } else {
                controller.pause()
            }
            if (reportingAuthority == OfflinePrepareReportingAuthority.PreserveCurrent) {
                clearOfflineReprepare(selectionGeneration)
                handlePlaybackState(controller.playbackState.value)
            } else {
                publishContent(pickerVisible = PlayerPicker.None)
            }
            return true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            if (
                isCurrentOfflinePrepare(
                    artifactRef = artifactRef,
                    selectionGeneration = selectionGeneration,
                    launchGeneration = launchGeneration,
                    itemId = itemId,
                    controller = controller,
                    reportingAuthority = reportingAuthority,
                )
            ) {
                releaseOwnedPlayerController()
                return failOfflineLaunch(
                    launchGeneration,
                    PlaybackError.OfflinePlayerUnavailable(
                        requiredBackend = unavailableBackend,
                    ),
                )
            }
            return false
        } finally {
            if (reportingAuthority == OfflinePrepareReportingAuthority.PreserveCurrent) {
                clearOfflineReprepare(selectionGeneration)
            }
        }
    }

    private fun isCurrentOfflinePrepare(
        artifactRef: OfflineArtifactRef,
        selectionGeneration: Long?,
        launchGeneration: Long,
        itemId: String,
        controller: PlayerController,
        reportingAuthority: OfflinePrepareReportingAuthority,
    ): Boolean =
        when (reportingAuthority) {
            OfflinePrepareReportingAuthority.InstallInitial ->
                !disposed &&
                    isCurrentPlaybackLaunch(launchGeneration, itemId) &&
                    playerController === controller &&
                    offlineReprepareGeneration == null &&
                    plan?.streamMode == StreamMode.Offline &&
                    plan?.itemId == itemId &&
                    plan?.offlineArtifactRef == artifactRef
            OfflinePrepareReportingAuthority.PreserveCurrent ->
                selectionGeneration != null &&
                    offlineReprepareGeneration == selectionGeneration &&
                    isCurrentOfflineReprepare(
                        artifactRef = artifactRef,
                        selectionGeneration = selectionGeneration,
                        launchGeneration = launchGeneration,
                        itemId = itemId,
                        controller = controller,
                    )
        }

    private fun clearOfflineReprepare(selectionGeneration: Long?) {
        if (selectionGeneration != null && offlineReprepareGeneration == selectionGeneration) {
            offlineReprepareGeneration = null
            offlineRepreparePositionMs = null
        }
    }

    private enum class OfflinePrepareReportingAuthority {
        InstallInitial,
        PreserveCurrent,
    }

    private fun isCurrentOfflineReprepare(
        artifactRef: OfflineArtifactRef,
        selectionGeneration: Long,
        launchGeneration: Long,
        itemId: String,
        controller: PlayerController,
    ): Boolean =
        !disposed &&
            offlineReprepareGeneration == selectionGeneration &&
            offlineSubtitleSelectionGeneration == selectionGeneration &&
            playbackLaunchGeneration == launchGeneration &&
            currentItemId == itemId &&
            playerController === controller &&
            plan?.streamMode == StreamMode.Offline &&
            plan?.itemId == itemId &&
            plan?.offlineArtifactRef == artifactRef

    private fun failOfflineLaunch(
        launchGeneration: Long,
        error: PlaybackError,
    ): Boolean {
        clearPlaybackLaunch(launchGeneration)
        reduce(PlayerChange.Failed(PlayerUiState.Error(error = error)))
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
        retained: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
    ): SubtitleSelectionIntent {
        // A retained Kids hint is tried first without deleting an invalid selection at this resolver.
        // The explicit branch below already resolves an identical value once.
        if (retained != explicit) {
            resolveLocalSelection(retained, key, options, preferConfirmedServer = false, deleteInvalid = false)?.let { return it }
            if (retained.isValidFor(options)) return retained
        }
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

    private fun isCurrentReplan(
        requestGeneration: Long,
        launchGeneration: Long,
        itemId: String,
    ): Boolean =
        !disposed &&
            replanRequestGeneration == requestGeneration &&
            isCurrentPlaybackLaunch(launchGeneration, itemId)

    private fun ownsCurrentQualityProposal(
        requestGeneration: Long,
        launchGeneration: Long,
        itemId: String,
        controller: PlayerController,
        installedPlan: PlaybackPlan?,
    ): Boolean =
        installedPlan != null &&
            isCurrentReplan(requestGeneration, launchGeneration, itemId) &&
            !controllerInstallInFlight &&
            playerController === controller &&
            this.installedPlan === installedPlan

    /**
     * A failure handled across diagnostics capture may now belong to a replaced install. A held
     * lock means the next owner has already been admitted; recovery queued behind it would take
     * the lock from an admitted Kids launch.
     */
    private fun ownsCapturedControllerFailure(
        controller: PlayerController,
        launchGeneration: Long,
        selectionGeneration: Long,
        installedPlan: PlaybackPlan?,
    ): Boolean =
        !disposed &&
            !recoveryClosedByStop &&
            !controllerInstallInFlight &&
            !controllerInstallMutex.isLocked &&
            offlineReprepareGeneration == null &&
            playerController === controller &&
            playbackLaunchGeneration == launchGeneration &&
            selectedPlaybackGeneration == selectionGeneration &&
            this.installedPlan === installedPlan

    private fun preservesPlaybackForQualityFailure(
        requestGeneration: Long,
        launchGeneration: Long,
        itemId: String,
        controller: PlayerController,
        installedPlan: PlaybackPlan?,
    ): Boolean =
        ownsCurrentQualityProposal(
            requestGeneration = requestGeneration,
            launchGeneration = launchGeneration,
            itemId = itemId,
            controller = controller,
            installedPlan = installedPlan,
        ) &&
            controller.playbackState.value.status in playbackChangeActiveStatuses

    private fun commitQualityProposal(
        proposedQualitySessionState: PlayerQualitySessionState,
        preserveRecoveryBudget: Boolean,
    ) {
        autoplayGeneration += 1L
        playbackHealthCoordinator.dismissGuidance()
        pendingRecoveredPlaybackGuidanceItemId = null
        pendingRecoveredAutoQualityBps = null
        playbackActionNotice = null
        qualitySession = proposedQualitySessionState
        autoRecoveryState =
            if (preserveRecoveryBudget) {
                autoRecoveryCoordinator.clearRuntimeQualityCap(autoRecoveryState)
            } else {
                autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, backend)
            }
        updatePlaybackHealthSessionContext()
        excludeSoftwarePlaybackProgress()
        markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Replan)
        invalidateSubtitleFallback()
    }

    private fun publishPlaybackChangeRejection(
        operation: PlayerPlaybackChangeOperation,
        error: PlaybackError,
        context: PlayerDiagnosticContext = playerDiagnosticContext(),
    ): Boolean {
        val retainedPlaybackState = playerController.playbackState.value
        if (retainedPlaybackState.status !in playbackChangeActiveStatuses) return false
        playbackChangeNotice =
            PlayerPlaybackChangeNotice(
                token = ++playbackChangeNoticeToken,
                operation = operation,
                error = error,
            )
        playerDiagnosticsRecorder.recordPlaybackChangeRejected(
            context = context,
            facts =
                PlayerPlaybackChangeDiagnosticFacts(
                    operation = operation,
                    error = error,
                ),
        )
        publishContent(
            playbackState = retainedPlaybackState,
            pickerVisible = PlayerPicker.None,
        )
        return true
    }

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

        // Register before starting: the body may run inline and reach an installer that captures reportingJob.
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                // Edge handling is fast and idempotent; avoid child jobs per emission.
                playerController.playbackState.collect { playbackState ->
                    handlePlaybackState(playbackState)
                }
            }
        reportingJob = job
        job.start()
    }

    private fun observeRuntimeDiagnostics() {
        runtimeDiagnosticsJob?.cancel()
        droppedFrameMeasurementsJob?.cancel()
        videoOutputObservationsJob?.cancel()
        playbackTransitionObservationsJob?.cancel()
        val controller = playerController
        val controllerBackend = backend
        _runtimeDiagnostics.value = controller.runtimeDiagnostics.value
        runtimeDiagnosticsJob =
            viewModelScope.launch {
                controller.runtimeDiagnostics.collect { diagnostics ->
                    _runtimeDiagnostics.value = diagnostics
                    maybeCompletePlaybackLaunch(controller, diagnostics)
                    // Diagnostics only: renderer-target color never reaches planning, health or recovery.
                    if (concreteControllerInstalled) {
                        playerDiagnosticsRecorder.recordOutputColorIfChanged(
                            playerDiagnosticContext(
                                backend = controllerBackend,
                                prepareSequence = diagnostics.prepareEpoch,
                                runtimeDiagnostics = diagnostics,
                            ),
                        )
                    }
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

    private fun armSoftwarePlaybackRecovery() {
        softwareProgress = SoftwarePlaybackProgress()
        softwarePrepareEpoch = playerController.runtimeDiagnostics.value.prepareEpoch
        softwareProgressReported = false
        softwareProgressExcludedUntilMs = monotonicTimeMs() + SOFTWARE_PLAYBACK_PREPARE_GRACE_MS
        if (_softwarePlaybackRecovery.value?.switching != true) {
            clearSoftwarePlaybackRecovery(SoftwarePlaybackRecoveryDecision.Superseded)
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
        launchAudioStreamIndex: Int?,
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
                    source = selectedVersion.backendSourceDescriptor(launchAudioStreamIndex),
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
            playbackHealthCoordinator.suspendForInstallation()
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
                excludeSoftwarePlaybackProgress()
                markPlaybackHealthExclusion(PlaybackHealthExclusionReason.BackendReplacement)
                reduce(PlayerChange.Loading)
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
                finishControllerInstallation(expectedGeneration)
            }
        }

    private fun drainStopRequestedDuringControllerInstall(stopController: Boolean) {
        if (!stopRequestedDuringControllerInstall) return
        stopRequestedDuringControllerInstall = false
        performStop(stopController)
    }

    /**
     * Releases installation admission and resumes health evaluation. Only the installer's own
     * launch, still holding a concrete prepared controller, supplies that controller's current state;
     * a superseded, stopped, or released owner passes null so the next prepare or observed state
     * rearms health instead. Never reenters [handlePlaybackState] and its failure handling.
     */
    private fun finishControllerInstallation(ownerLaunchGeneration: Long) {
        controllerInstallInFlight = false
        val currentPlaybackState =
            if (
                !disposed &&
                !stopRequestedDuringControllerInstall &&
                ownerLaunchGeneration == playbackLaunchGeneration &&
                concreteControllerInstalled &&
                playerControllerFieldOwned &&
                installedPlan != null
            ) {
                playerController.playbackState.value
            } else {
                null
            }
        playbackHealthCoordinator.resumeAfterInstallation(currentPlaybackState)
    }

    private fun releaseOwnedPlayerController() {
        pendingPictureInPictureRecoveryTrigger = null
        installedPlan = null
        if (!playerControllerFieldOwned) {
            return
        }
        playerControllerFieldOwned = false
        concreteControllerInstalled = false
        backendResolvedForSession = false
        timingCoordinator.cancelLoad()
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

    private suspend fun switchBackend(switch: BackendSwitchSnapshot): Boolean {
        planBackendSwitch(
            switch = switch,
            requestedBackend = switch.targetBackend,
            startPositionMs = switch.activePlan.startPositionMs,
        ) ?: run {
            keepCurrentPlaybackAfterBackendSwitchFailure(switch)
            return false
        }
        if (!isCurrentBackendSwitch(switch)) return false
        val prepared = installBackendSwitchController(switch)
        if (!prepared) reconcileRetainedControllerFailure(switch)
        return prepared
    }

    /** Plans against fresh backend facts while the current controller remains healthy and installed. */
    private suspend fun planBackendSwitch(
        switch: BackendSwitchSnapshot,
        requestedBackend: PlayerBackend,
        startPositionMs: Long,
    ): PlaybackPlan? {
        if (!isCurrentBackendSwitch(switch)) return null
        switch.planningError = PlaybackError.Unknown
        val qualityPolicy =
            if (switch.recoveryQuality != null || switch.qualityExplicit) {
                switch.qualityPolicy
            } else {
                activePlaybackPreferences.effectiveDefaultQualityPolicy(requestedBackend)
            }
        val qualityCapOrigin =
            if (switch.recoveryQuality != null) {
                switch.recoveryQuality.capOrigin
            } else if (switch.qualityExplicit) {
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
                diagnosticSessionSequence = switch.diagnosticSession,
                bitrateConstraint =
                    if (switch.recoveryQuality != null) {
                        switch.recoveryBudget
                            ?.runtimeQualityCapBps
                            ?.let { PlaybackBitrateConstraint.AutoSessionLimit(it) } ?: qualityPolicy.toBitrateConstraint()
                    } else {
                        PlaybackBitrateConstraint.NoClientLimit
                    },
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
                    switch.planningError = planningPlaybackError(exception)
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
        if (!isCurrentBackendSwitch(switch)) return null
        if (!preservesBackendSwitchIntent(playbackPlan, switch)) {
            switch.planningError = PlaybackError.UnsupportedMedia
            return null
        }
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
        if (
            switch.wasActiveAtRequest &&
            playerController === switch.activeController &&
            installedPlan === switch.activePlan &&
            playerController.playbackState.value.status in playbackChangeActiveStatuses
        ) {
            publishPlaybackChangeRejection(
                operation = PlayerPlaybackChangeOperation.Backend,
                error = switch.planningError,
            )
        }
    }

    /**
     * Runs after the switch released installation ownership. A failure observed while it held the
     * lock was dropped by the captured-authority guard; when the switch kept that failed controller,
     * restart observation so StateFlow redelivers it through ordinary recovery. Cancelling the old
     * observer also stops a handler still suspended in diagnostics from processing it twice. A lock
     * handed to a waiter (an admitted Kids launch) keeps priority.
     */
    private fun reconcileRetainedControllerFailure(switch: BackendSwitchSnapshot) {
        if (
            !isCurrentBackendSwitch(switch) ||
            stopRequestedDuringControllerInstall ||
            controllerInstallInFlight ||
            controllerInstallMutex.isLocked ||
            offlineReprepareGeneration != null ||
            playerController !== switch.activeController ||
            installedPlan !== switch.activePlan ||
            playerController.playbackState.value.status != PlaybackStatus.Failed ||
            lastStatus == PlaybackStatus.Failed ||
            state.value is PlayerUiState.Error
        ) {
            return
        }
        restartPlaybackStateObservation()
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
            playbackHealthCoordinator.suspendForInstallation()
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
                excludeSoftwarePlaybackProgress()
                markPlaybackHealthExclusion(PlaybackHealthExclusionReason.BackendReplacement)
                reduce(PlayerChange.Loading)
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
                                    if (switch.recoveryQuality != null) {
                                        switch.recoveryQuality
                                    } else if (switch.qualityExplicit) {
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
                                    switch.recoveryBudget?.copy(backend = actualBackend)
                                        ?: autoRecoveryCoordinator.reset(playbackLaunchGeneration, currentItemId, actualBackend)
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
                excludeSoftwarePlaybackProgress()
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
                armSoftwarePlaybackRecovery()
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
                if (switch.wasPaused && switch.softwareRecoveryToken == null) {
                    desiredPlayWhenReady = false
                    playerController.pause()
                } else {
                    desiredPlayWhenReady = true
                    playerController.play()
                }
                finishBackendSwitch(switch)
                publishContent(pickerVisible = PlayerPicker.None)
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
                            reduce(PlayerChange.Failed(PlayerUiState.Error(error = PlaybackError.Unknown)))
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
                finishControllerInstallation(switch.launchGeneration)
                if (keptCurrentPlayback) {
                    publishContent(pickerVisible = PlayerPicker.None)
                }
            }
        }

    /**
     * Describes the launch audio stream, falling back to the source default then the first stream.
     * Only Audio streams with an actual nonnegative, unambiguous index qualify. The selected
     * stream's codec is kept even when unknown. Writes nothing.
     */
    private fun MediaVersion.backendSourceDescriptor(launchAudioStreamIndex: Int?): BackendSourceDescriptor {
        val videoStream = mediaStreams.firstOrNull { stream -> stream.type.equals("Video", ignoreCase = true) }
        val selectableIndexes = unambiguousAudioStreamIndexes(mediaStreams)
        val audioStreams =
            mediaStreams.filter { stream ->
                stream.type.equals("Audio", ignoreCase = true) && stream.index in selectableIndexes
            }
        val audioStream =
            launchAudioStreamIndex?.let { index -> audioStreams.firstOrNull { stream -> stream.index == index } }
                ?: audioStreams.firstOrNull { stream -> stream.isDefault == true }
                ?: audioStreams.firstOrNull()
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
        if (offlineReprepareGeneration != null) return
        // Stop closed recovery: drop this failure before publication or a suspending capture.
        if (recoveryClosedByStop && playbackState.status == PlaybackStatus.Failed) return
        // Startup observes the current failure again once its prepare settles.
        if (startupPrepareGeneration != null && playbackState.status == PlaybackStatus.Failed) return
        // Failure capture suspends; capture the install authority it must still own afterwards.
        val controllerAtState = playerController
        val launchGenerationAtState = playbackLaunchGeneration
        val selectionGenerationAtState = selectedPlaybackGeneration
        val installedPlanAtState = installedPlan
        val enrichedPlaybackState = playbackState.withPlaybackMetadata()
        _playbackState.value = enrichedPlaybackState
        val installedAudioChanged = updateInstalledAudio(enrichedPlaybackState)
        recordTrackDiagnostics(enrichedPlaybackState)
        if (observeSoftwarePlaybackRecovery(enrichedPlaybackState)) {
            if (
                enrichedPlaybackState.status == PlaybackStatus.Failed &&
                !ownsCapturedControllerFailure(
                    controller = controllerAtState,
                    launchGeneration = launchGenerationAtState,
                    selectionGeneration = selectionGenerationAtState,
                    installedPlan = installedPlanAtState,
                )
            ) {
                return
            }
            publishContent(playbackState = enrichedPlaybackState)
            playbackReportingCoordinator.onPlaybackState(enrichedPlaybackState, lastStatus)
            lastStatus = enrichedPlaybackState.status
            return
        }
        playbackHealthCoordinator.observePlaybackState(enrichedPlaybackState)
        if (enrichedPlaybackState.status == PlaybackStatus.Failed) {
            captureControllerFailure()
            if (
                !ownsCapturedControllerFailure(
                    controller = controllerAtState,
                    launchGeneration = launchGenerationAtState,
                    selectionGeneration = selectionGenerationAtState,
                    installedPlan = installedPlanAtState,
                )
            ) {
                return
            }
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
        val upNext = if (isKidsSingleAsset) null else upNextFor(enrichedPlaybackState)
        val shouldPublishPlaybackState =
            currentPlaybackState == null ||
                currentPlaybackState.status != enrichedPlaybackState.status ||
                currentPlaybackState.currentSegment != enrichedPlaybackState.currentSegment ||
                currentPlaybackState.audioActivation != enrichedPlaybackState.audioActivation ||
                currentPlaybackState.subtitleActivation != enrichedPlaybackState.subtitleActivation ||
                currentAudioUnavailable != enrichedPlaybackState.audioUnavailable ||
                currentUpNext != upNext
        val terminalRecovery = maybeHandlePlaybackSessionRecovery(enrichedPlaybackState)
        // Queue refusal must not publish the outgoing item's failure, and awaited backend recovery
        // may have handed ownership to another launch. Fallback can cancel this collector legitimately.
        if (
            enrichedPlaybackState.status == PlaybackStatus.Failed &&
            (
                disposed ||
                    recoveryClosedByStop ||
                    queueSwitchInFlight ||
                    playbackLaunchGeneration != launchGenerationAtState ||
                    selectedPlaybackGeneration != selectionGenerationAtState
            )
        ) {
            return
        }
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
            capturePlayingPositionForCurrentTarget(enrichedPlaybackState.positionMs)
            consumeRestartFromBeginningIntentForPlayingTarget()
            maybeCompleteRecoveredPlaybackGuidance()
        }

        if (enrichedPlaybackState.status == PlaybackStatus.Failed) {
            reduce(PlayerChange.Failed(PlayerUiState.Error(error = enrichedPlaybackState.error)))
        } else if (shouldPublishPlaybackState || installedAudioChanged) {
            publishContent(playbackState = enrichedPlaybackState)
        }

        playbackReportingCoordinator.onPlaybackState(enrichedPlaybackState, lastStatus)
        // Report Start before an auto-skip TimeUpdate.
        maybeAutoSkipSegment(enrichedPlaybackState)

        if (completed) {
            playbackReportingCoordinator.stop(enrichedPlaybackState.positionMs, completed = true)
            if (!isKidsSingleAsset && lastStatus != PlaybackStatus.Completed) {
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
        currentCoroutineContext().ensureActive()
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

    private fun excludeSoftwarePlaybackProgress() {
        softwareProgress.reset()
        softwareProgressExcludedUntilMs =
            maxOf(softwareProgressExcludedUntilMs, monotonicTimeMs() + SOFTWARE_PLAYBACK_TRANSITION_EXCLUSION_MS)
    }

    private fun clearSoftwarePlaybackRecovery(decision: SoftwarePlaybackRecoveryDecision) {
        if (_softwarePlaybackRecovery.value != null) {
            playerDiagnosticsRecorder.recordSoftwarePlaybackRecovery(playerDiagnosticContext(), decision)
            _softwarePlaybackRecovery.value = null
        }
    }

    private suspend fun observeSoftwarePlaybackRecovery(playbackState: PlaybackState): Boolean {
        val prompt = _softwarePlaybackRecovery.value
        if (prompt != null) {
            if (!prompt.switching && playbackState.status == PlaybackStatus.Playing) playerController.pause()
            if (playbackState.status == PlaybackStatus.Failed || playbackState.status == PlaybackStatus.Completed) {
                _softwarePlaybackRecovery.value = prompt.copy(canSwitch = false, canContinue = false)
                if (playbackState.status == PlaybackStatus.Failed) captureControllerFailure()
            }
            return true
        }
        if (backend != PlayerBackend.Mpv || deviceProfileProvider?.backendPolicy?.platform != PlayerBackendPlatform.Android) return false
        if (softwareProgressReported) return false
        val diagnostics = playerController.runtimeDiagnostics.value
        val now = monotonicTimeMs()
        val evidence =
            softwareProgress.observe(
                state = playbackState,
                decodingMode = diagnostics.videoDecodingMode,
                admitted =
                    desiredPlayWhenReady &&
                        !pictureInPictureMode &&
                        plan?.videoExpected == true &&
                        plan?.streamMode != StreamMode.Offline &&
                        softwarePrepareEpoch != null &&
                        diagnostics.prepareEpoch == softwarePrepareEpoch &&
                        !backendSwitchInProgress &&
                        !queueSwitchInFlight &&
                        replanJob?.isActive != true,
                excluded = now < softwareProgressExcludedUntilMs,
                nowMs = now,
            ) ?: return false
        playerDiagnosticsRecorder.recordSoftwarePlaybackProgress(playerDiagnosticContext(), evidence)
        if (evidence.outcome != SoftwarePlaybackProgressOutcome.TooSlow) return false
        softwareProgressReported = true
        desiredPlayWhenReady = false
        playbackIntentRevision += 1L
        playerController.pause()
        _softwarePlaybackRecovery.value =
            PlayerSoftwarePlaybackRecovery(
                token = ++softwareRecoveryToken,
                canSwitch = PlayerBackend.ExoPlayer in availableBackendsForSession,
            )
        publishContent(pickerVisible = PlayerPicker.None)
        playerDiagnosticsRecorder.recordSoftwarePlaybackRecovery(playerDiagnosticContext(), SoftwarePlaybackRecoveryDecision.Prompted)
        return true
    }

    private fun continueSoftwarePlaybackRecovery(token: Long) {
        val prompt = _softwarePlaybackRecovery.value ?: return
        if (prompt.token != token || prompt.switching || !prompt.canContinue || !canAcceptPlaybackStart()) return
        clearSoftwarePlaybackRecovery(SoftwarePlaybackRecoveryDecision.ContinueRequested)
        // Keep the once-per-prepare latch: choosing to continue must not reopen the dialog.
        play()
    }

    private fun switchSoftwarePlaybackRecovery(token: Long) {
        val prompt = _softwarePlaybackRecovery.value ?: return
        if (prompt.token != token || prompt.switching || !prompt.canSwitch || disposed) return
        _softwarePlaybackRecovery.value = prompt.copy(switching = true, switchFailed = false)
        playerDiagnosticsRecorder.recordSoftwarePlaybackRecovery(
            playerDiagnosticContext(),
            SoftwarePlaybackRecoveryDecision.SwitchRequested,
        )
        if (!startBackendSwitch(PlayerBackend.ExoPlayer, recoveryToken = token)) {
            _softwarePlaybackRecovery.value = prompt.copy(switchFailed = true)
            playerDiagnosticsRecorder.recordSoftwarePlaybackRecovery(
                playerDiagnosticContext(),
                SoftwarePlaybackRecoveryDecision.SwitchFailed,
            )
        }
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
        pendingPictureInPictureRecoveryTrigger = null
        playbackLaunchGeneration += 1L
        playbackDiagnosticSession = PlaybackDiagnosticSessions.allocate()
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
        // An owned install or unsettled queue identity rejects before spending recovery state or the PiP slot.
        if (controllerInstallInFlight || queueSwitchInFlight) return null
        if (recoveryClosedByStop) return null
        if (_softwarePlaybackRecovery.value != null) return null
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

    private fun maybeHandleImmediatePlaybackSessionRecovery(
        playbackState: PlaybackState,
        pausedAtRequestOverride: Boolean? = null,
    ): Boolean {
        if (plan?.streamMode == StreamMode.Offline) return false
        return executePlaybackSessionRecovery(
            nextPlaybackSessionRecoveryDecision(playbackState),
            playbackState,
            pausedAtRequestOverride,
        )
    }

    /**
     * An explicit start needs a live, settled install slot with no pending Stop. Callers reopen
     * recovery closed by Stop only after this and their own checks accept.
     */
    private fun canAcceptPlaybackStart(): Boolean =
        !disposed &&
            !controllerInstallInFlight &&
            !controllerInstallMutex.isLocked &&
            !stopRequestedDuringControllerInstall

    /**
     * Session recovery acts only for settled queue identity and a settled controller owner that Stop
     * has not closed. A null installed plan stays admissible: synchronous failures and fast retries
     * recover without one.
     */
    private fun admitsPlaybackSessionRecovery(): Boolean =
        canAcceptPlaybackStart() &&
            !queueSwitchInFlight &&
            !recoveryClosedByStop &&
            offlineReprepareGeneration == null &&
            concreteControllerInstalled &&
            playerControllerFieldOwned &&
            startupPrepareGeneration == null

    private fun nextPlaybackSessionRecoveryDecision(playbackState: PlaybackState): PlaybackSessionRecoveryDecision {
        // Rejected admission must not spend recovery allowances.
        if (!admitsPlaybackSessionRecovery()) return PlaybackSessionRecoveryDecision.NoAction
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
        pausedAtRequestOverride: Boolean? = null,
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
                    pausedAtRequestOverride = pausedAtRequestOverride,
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
                    pausedAtRequestOverride = pausedAtRequestOverride,
                )
                true
            }

            is PlaybackSessionRecoveryDecision.SubtitleUnavailable -> {
                if (
                    plan?.plannedSubtitle is PlannedSubtitle.LocalAsset ||
                    plan?.plannedSubtitle is PlannedSubtitle.OfflineSidecar
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
        val fallbackGeneration = playbackLaunchGeneration
        val fallbackItemId = currentItemId
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
                expectedGeneration = fallbackGeneration,
                expectedItemId = fallbackItemId,
            ) ?: return false
        // Follow actual launch ownership, not lock handoff: a waiter that has not run or declines leaves
        // this fallback current. The caller's late Failed guard drops every refusal here.
        if (
            disposed ||
            recoveryClosedByStop ||
            queueSwitchInFlight ||
            !isCurrentPlaybackLaunch(fallbackGeneration, fallbackItemId)
        ) {
            return false
        }
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
        if (
            playbackPlan.streamMode == StreamMode.DirectPlay ||
            playbackPlan.streamMode == StreamMode.Offline
        ) {
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
        if (plannedSubtitle is PlannedSubtitle.OfflineSidecar) {
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

    private fun replanAtCurrentPosition(
        proposedQualitySessionState: PlayerQualitySessionState? = null,
        preserveRecoveryBudgetOnQualityCommit: Boolean = false,
    ) {
        replanAtPosition(
            targetPositionMs = playerController.playbackState.value.positionMs,
            proposedQualitySessionState = proposedQualitySessionState,
            preserveRecoveryBudgetOnQualityCommit = preserveRecoveryBudgetOnQualityCommit,
        )
    }

    // Fetch fresh PlaybackInfo and re-prepare at the target position.
    private fun replanAtPosition(
        targetPositionMs: Long,
        requestPolicy: PlaybackInfoRequestPolicy = PlaybackInfoRequestPolicy(),
        forcePlay: Boolean = false,
        nonFatalSubtitleFallback: SubtitleActivationTarget? = null,
        subtitleFallbackGeneration: Long? = null,
        audioRecovery: AudioActivationTarget? = null,
        proposedQualitySessionState: PlayerQualitySessionState? = null,
        preserveRecoveryBudgetOnQualityCommit: Boolean = false,
        pausedAtRequestOverride: Boolean? = null,
    ) {
        if (controllerInstallInFlight) return
        invalidateBackendSwitch()
        // Offline plans never enter the remote replan path.
        if (plan?.streamMode == StreamMode.Offline) return
        val mediaSource = selectedMediaSourceId ?: return
        val qualitySessionForRequest = proposedQualitySessionState ?: qualitySession
        val requestGeneration = ++replanRequestGeneration
        val controllerAtRequest = playerController
        val installedPlanAtRequest = installedPlan
        val playbackWasPausedAtRequest =
            pausedAtRequestOverride ?: (controllerAtRequest.playbackState.value.status == PlaybackStatus.Paused)
        val playbackIntentRevisionAtRequest = playbackIntentRevision
        val proposalWasActiveAtRequest =
            proposedQualitySessionState != null &&
                installedPlanAtRequest != null &&
                controllerAtRequest.playbackState.value.status in playbackChangeActiveStatuses
        val target = targetPositionMs.coerceAtLeast(0L)
        val effectiveRequestPolicy =
            requestPolicy.copy(
                backend = backend,
                bitrateConstraint =
                    requestPolicy.bitrateConstraint.takeUnless { constraint ->
                        constraint == PlaybackBitrateConstraint.NoClientLimit
                    } ?: proposedQualitySessionState
                        ?.policy
                        ?.toBitrateConstraint()
                        ?: activePlaybackBitrateConstraint(),
            )
        val selectedSubtitle = selectedSubtitleMediaStream()
        val selectedAudioStreamIndex = requestedAudioStreamIndex
        val subtitleSelection = requestedSubtitleSelection
        val localSubtitleAsset = requestedLocalSubtitleAsset?.toPlaybackAsset()
        val detailMediaStreams = mediaStreams
        val sourceContainer = selectedSourceContainer
        val replanItemId = currentItemId
        val replanGeneration = playbackLaunchGeneration
        val replanDiagnosticSession = playbackDiagnosticSession
        val diagnosticContext = playerDiagnosticContext()
        if (proposedQualitySessionState == null) {
            autoplayGeneration += 1
            excludeSoftwarePlaybackProgress()
            markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Replan)
            if (nonFatalSubtitleFallback == null) {
                invalidateSubtitleFallback()
            }
        }
        val activationRequestId =
            if (proposedQualitySessionState == null) {
                nextSubtitleActivationRequestId()
            } else {
                null
            }
        replanJob?.cancel()
        replanJob =
            viewModelScope.launch {
                if (controllerInstallInFlight) return@launch
                if (forcePlay) {
                    reduce(PlayerChange.Loading)
                }
                val correlatedRequestPolicy =
                    effectiveRequestPolicy.copy(
                        diagnosticSessionSequence = replanDiagnosticSession,
                    )

                val playbackPlan =
                    try {
                        withContext(workDispatcher) {
                            playbackInfoPlanner.plan(
                                session = session,
                                itemId = replanItemId,
                                mediaSourceId = mediaSource,
                                startPositionTicks = millisecondsToTicks(target),
                                audioStreamIndex = selectedAudioStreamIndex,
                                detailMediaStreams = detailMediaStreams,
                                subtitleSelection = subtitleSelection,
                                localSubtitleAsset = localSubtitleAsset,
                                maxStreamingBitrate = qualitySessionForRequest.maximumBitrateBps,
                                qualityPolicy = qualitySessionForRequest.policy,
                                qualityCapOrigin = qualitySessionForRequest.capOrigin,
                                requestPolicy = correlatedRequestPolicy,
                                // Preserve the known container in fallback diagnostics.
                                sourceContainer = sourceContainer,
                            )
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentReplan(requestGeneration, replanGeneration, replanItemId)) return@launch
                        playerDiagnosticsRecorder.recordPlannerAttemptFailure(
                            context = diagnosticContext,
                            facts =
                                PlayerPlannerFailureDiagnosticFacts(
                                    exception = exception,
                                    requestPolicy = correlatedRequestPolicy,
                                    qualityPolicy = qualitySessionForRequest.policy,
                                    qualityCapOrigin = qualitySessionForRequest.capOrigin,
                                    requestCapBitrateBps = qualitySessionForRequest.maximumBitrateBps,
                                ),
                        )
                        val planningError = planningPlaybackError(exception)
                        val canPreserveQualityFailure =
                            proposedQualitySessionState != null &&
                                proposalWasActiveAtRequest &&
                                preservesPlaybackForQualityFailure(
                                    requestGeneration = requestGeneration,
                                    launchGeneration = replanGeneration,
                                    itemId = replanItemId,
                                    controller = controllerAtRequest,
                                    installedPlan = installedPlanAtRequest,
                                )
                        val qualityFailurePreserved =
                            canPreserveQualityFailure &&
                                publishPlaybackChangeRejection(
                                    operation = PlayerPlaybackChangeOperation.Quality,
                                    error = planningError,
                                    context = diagnosticContext,
                                )
                        if (!qualityFailurePreserved && nonFatalSubtitleFallback != null) {
                            if (
                                isCurrentSubtitleFallback(
                                    target = nonFatalSubtitleFallback,
                                    generation = subtitleFallbackGeneration,
                                    itemId = replanItemId,
                                )
                            ) {
                                markSubtitleFallbackUnavailable(nonFatalSubtitleFallback)
                            }
                        } else if (!qualityFailurePreserved && audioRecovery != null) {
                            if (audioRecoveryTarget == audioRecovery) {
                                reduce(PlayerChange.Failed(PlayerUiState.Error(error = PlaybackError.UnsupportedMedia)))
                            }
                        } else if (!qualityFailurePreserved) {
                            reduce(PlayerChange.Failed(startupPlanningError(exception)))
                        }
                        if (!qualityFailurePreserved) {
                            playerDiagnosticsRecorder.recordTerminalOutcome(
                                context = playerDiagnosticContext(),
                                facts =
                                    PlayerTerminalDiagnosticFacts(
                                        outcome = PlaybackTerminalOutcome.Failed,
                                        error =
                                            if (audioRecovery != null) {
                                                PlaybackError.UnsupportedMedia
                                            } else {
                                                planningError
                                            },
                                        autoRecoveryTrigger = null,
                                        recoveryDecision = null,
                                    ),
                            )
                        }
                        return@launch
                    }

                if (!isCurrentReplan(requestGeneration, replanGeneration, replanItemId)) return@launch

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
                        reduce(PlayerChange.Failed(PlayerUiState.Error(error = PlaybackError.UnsupportedMedia)))
                        return@launch
                    }
                }

                if (
                    proposedQualitySessionState != null &&
                    !ownsCurrentQualityProposal(
                        requestGeneration = requestGeneration,
                        launchGeneration = replanGeneration,
                        itemId = replanItemId,
                        controller = controllerAtRequest,
                        installedPlan = installedPlanAtRequest,
                    )
                ) {
                    return@launch
                }
                val committedActivationRequestId = activationRequestId ?: nextSubtitleActivationRequestId()

                val playbackPlanWithMetadata =
                    enrichPlaybackPlan(
                        playbackPlan = playbackPlan,
                        requestedMediaSourceId = mediaSource,
                        generation = replanGeneration,
                        itemId = replanItemId,
                        activationRequestId = committedActivationRequestId,
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
                if (!isCurrentReplan(requestGeneration, replanGeneration, replanItemId)) return@launch
                currentCoroutineContext().ensureActive()
                if (!isCurrentReplan(requestGeneration, replanGeneration, replanItemId)) return@launch
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
                if (
                    proposedQualitySessionState != null &&
                    !ownsCurrentQualityProposal(
                        requestGeneration = requestGeneration,
                        launchGeneration = replanGeneration,
                        itemId = replanItemId,
                        controller = controllerAtRequest,
                        installedPlan = installedPlanAtRequest,
                    )
                ) {
                    return@launch
                }
                // A same-item replan cannot stabilize a queue switch.
                if (!isCurrentReplan(requestGeneration, replanGeneration, replanItemId)) return@launch
                var prepareFailedSynchronously = false
                val replacementInstalled =
                    controllerInstallMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        if (stopRequestedDuringControllerInstall) {
                            drainStopRequestedDuringControllerInstall(stopController = true)
                            return@withLock false
                        }
                        if (
                            controllerInstallInFlight ||
                            !isCurrentReplan(requestGeneration, replanGeneration, replanItemId) ||
                            playerController !== controllerAtRequest ||
                            installedPlan !== installedPlanAtRequest
                        ) {
                            return@withLock false
                        }
                        if (
                            proposedQualitySessionState != null &&
                            !ownsCurrentQualityProposal(
                                requestGeneration = requestGeneration,
                                launchGeneration = replanGeneration,
                                itemId = replanItemId,
                                controller = controllerAtRequest,
                                installedPlan = installedPlanAtRequest,
                            )
                        ) {
                            return@withLock false
                        }

                        controllerInstallInFlight = true
                        playbackHealthCoordinator.suspendForInstallation()
                        try {
                            playbackReportingCoordinator.stopNow(positionMs = target)
                            currentCoroutineContext().ensureActive()
                            if (stopRequestedDuringControllerInstall) {
                                drainStopRequestedDuringControllerInstall(stopController = true)
                                return@withLock false
                            }
                            if (
                                !isCurrentReplan(requestGeneration, replanGeneration, replanItemId) ||
                                playerController !== controllerAtRequest ||
                                installedPlan !== installedPlanAtRequest
                            ) {
                                performStop(stopController = true)
                                return@withLock false
                            }
                            if (proposedQualitySessionState != null) {
                                commitQualityProposal(
                                    proposedQualitySessionState = proposedQualitySessionState,
                                    preserveRecoveryBudget = preserveRecoveryBudgetOnQualityCommit,
                                )
                            }
                            installPlan(playbackPlanWithMetadata, resetReporting = true, stabilizesQueueSwitch = false)
                            reduce(PlayerChange.SeekUnavailable)
                            if (nonFatalSubtitleFallback != null) {
                                subtitleFallbackTarget = null
                            }
                            loadTimingOffsets()
                            currentCoroutineContext().ensureActive()
                            if (stopRequestedDuringControllerInstall) {
                                drainStopRequestedDuringControllerInstall(stopController = true)
                                return@withLock false
                            }
                            playbackLaunchMarker = null
                            excludeSoftwarePlaybackProgress()
                            markPlaybackHealthExclusion(PlaybackHealthExclusionReason.Prepare)
                            playerDiagnosticsRecorder.recordPrepareRequested(playerDiagnosticContext())
                            playerController.prepare(playbackPlanWithMetadata)
                            if (playerController.playbackState.value.status == PlaybackStatus.Failed) {
                                installedPlan = null
                                prepareFailedSynchronously = !stopRequestedDuringControllerInstall
                                drainStopRequestedDuringControllerInstall(stopController = true)
                                return@withLock false
                            }
                            installedPlan = playbackPlanWithMetadata
                            playerController.runtimeDiagnostics.value.prepareEpoch
                                ?.let(playbackHealthCoordinator::expectVideoOutput)
                            armSoftwarePlaybackRecovery()
                            armFirstVideoOutputState()
                            playerDiagnosticsRecorder.recordPrepareDispatched(playerDiagnosticContext())
                            applyInitialEmbeddedSelections(playbackPlanWithMetadata)
                            if (stopRequestedDuringControllerInstall) {
                                drainStopRequestedDuringControllerInstall(stopController = true)
                                return@withLock false
                            }
                            if (forcePlay) {
                                playerController.play()
                            } else if (
                                !desiredPlayWhenReady ||
                                (playbackIntentRevision == playbackIntentRevisionAtRequest && playbackWasPausedAtRequest)
                            ) {
                                playerController.pause()
                            } else {
                                playerController.play()
                            }
                            publishContent(pickerVisible = PlayerPicker.None)
                            if (stopRequestedDuringControllerInstall) {
                                drainStopRequestedDuringControllerInstall(stopController = true)
                                false
                            } else {
                                true
                            }
                        } finally {
                            finishControllerInstallation(replanGeneration)
                        }
                    }
                if (replacementInstalled) {
                    if (isCurrentReplan(requestGeneration, replanGeneration, replanItemId)) {
                        maybeStartInstalledActivationFallback(controllerAtRequest, playbackPlanWithMetadata)
                    }
                } else if (prepareFailedSynchronously) {
                    // The install guard intentionally suppresses controller
                    // publications until the replacement boundary is released.
                    handlePlaybackState(playerController.playbackState.value)
                }
            }
    }

    /**
     * Reports planned activation gaps after installation ownership is released. Both facts share one
     * policy decision, so audio takes precedence and the subtitle allowance stays for a later state.
     * Startup also reconsiders native unavailability that prepare callbacks could not act on.
     * Same-plan Retry passes its logical plan facts as [expectedPlan] while [expectedInstalledPlan]
     * guards the possibly null prepared identity; the overrides replace a transitional Retry snapshot.
     */
    private fun maybeStartInstalledActivationFallback(
        expectedController: PlayerController,
        expectedPlan: PlaybackPlan,
        reconcileNativeState: Boolean = false,
        expectedInstalledPlan: PlaybackPlan? = expectedPlan,
        recoveryPositionOverrideMs: Long? = null,
        pausedAtRequestOverride: Boolean? = null,
    ) {
        if (
            !admitsPlaybackSessionRecovery() ||
            playerController !== expectedController ||
            installedPlan !== expectedInstalledPlan
        ) {
            return
        }
        val audioTarget =
            expectedPlan.audioActivationTarget?.takeIf { target ->
                expectedPlan.streamMode == StreamMode.DirectPlay &&
                    expectedPlan.embeddedAudioTracks.none { track -> track.jellyfinStreamIndex == target.streamIndex }
            }
        val unavailableSubtitle = expectedPlan.plannedSubtitle as? PlannedSubtitle.Unavailable
        val subtitleTarget = unavailableSubtitle?.activationTarget
        if (subtitleTarget != null) {
            playerDiagnosticsRecorder.recordDiagnostic(
                context = playerDiagnosticContext(),
                facts =
                    PlayerDiagnosticFacts(
                        stage = PlaybackDiagnosticStage.Mapping,
                        event = PlaybackDiagnosticEvent.Failed,
                        trackKind = PlaybackDiagnosticTrackKind.Subtitle,
                    ),
            )
        }
        val encodeSubtitleTarget = unavailableSubtitle?.takeIf { subtitle -> subtitle.allowEncodeFallback }?.activationTarget
        val nativeState = playerController.playbackState.value
        val nativeActivationUnavailable =
            nativeState.audioActivation is AudioActivationState.Unavailable ||
                nativeState.subtitleActivation is com.jellyscope.core.domain.playback.SubtitleActivationState.Unavailable
        // A current Failed snapshot without a planned gap stays with the collector.
        val reconcilesNativeActivation =
            reconcileNativeState && nativeState.status != PlaybackStatus.Failed && nativeActivationUnavailable
        if (audioTarget == null && encodeSubtitleTarget == null && !reconcilesNativeActivation) return
        maybeHandleImmediatePlaybackSessionRecovery(
            nativeState.copy(
                positionMs = recoveryPositionOverrideMs ?: nativeState.positionMs,
                audioActivation =
                    audioTarget?.let { target -> AudioActivationState.Unavailable(target) }
                        ?: nativeState.audioActivation,
                subtitleActivation =
                    encodeSubtitleTarget?.let { target ->
                        com.jellyscope.core.domain.playback.SubtitleActivationState
                            .Unavailable(target)
                    } ?: nativeState.subtitleActivation,
            ),
            pausedAtRequestOverride = pausedAtRequestOverride,
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
                is PlannedSubtitle.OfflineSidecar,
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

    private fun rejectSynchronouslyFailedPrepare(refreshObservation: Boolean = false): Boolean {
        if (playerController.playbackState.value.status != PlaybackStatus.Failed) return false
        installedPlan = null
        if (refreshObservation && reportingJob != null) {
            // The existing collector may have deferred this failure during startup prepare.
            restartPlaybackStateObservation()
        } else {
            // Initial direct injection has no collector yet; use the ordinary failure path.
            observePlaybackState()
        }
        return true
    }

    private fun publishContent(
        playbackState: PlaybackState = playerController.playbackState.value,
        pickerVisible: PlayerPicker = currentPicker(),
    ) {
        val enrichedPlaybackState = playbackState.withPlaybackMetadata()
        val subtitleRenderInfo = currentSubtitleRenderInfo(enrichedPlaybackState)
        val subtitleStyleable = subtitleRenderInfo.styleable && playerController.appliesSubtitleStyle
        val upNext = if (isKidsSingleAsset) null else upNextFor(enrichedPlaybackState)
        _playbackState.value = enrichedPlaybackState
        recordTrackDiagnostics(enrichedPlaybackState, subtitleRenderInfo)
        val content =
            PlayerUiState.Content(
                playbackState = enrichedPlaybackState,
                volumeControl = volumeControl,
                audioUnavailable = enrichedPlaybackState.audioUnavailable,
                metadata = metadata,
                // Stable list identity preserves Compose strong skipping.
                audioOptions = projections.audioTrackOptions(currentItemId, mediaStreams),
                subtitleOptions =
                    projections
                        .subtitleTrackOptions(currentItemId, mediaStreams)
                        .takeUnless { plan?.offlineArtifactKind == DownloadArtifactKind.LocalHlsPackage }
                        .orEmpty(),
                localSubtitleOptions = localSubtitleAssets.takeUnless { plan?.streamMode == StreamMode.Offline }.orEmpty(),
                offlineSidecarOption = offlineSidecarOption,
                qualityOptions =
                    if (plan?.streamMode == StreamMode.Offline) {
                        emptyList()
                    } else {
                        playerQualityOptions(currentSourceBitrate(), qualitySession.policy)
                    },
                selectedAudioStreamIndex = installedAudioStreamIndex,
                selectedSubtitleStreamIndex = subtitleRenderInfo.activeStreamIndex,
                selectedSubtitleAssetId = requestedLocalSubtitleAsset?.id,
                offlineSidecarSelected = plan?.plannedSubtitle is PlannedSubtitle.OfflineSidecar,
                selectedQualityMaxBitrate = qualitySession.maximumBitrateBps,
                selectedQualityPolicy = qualitySession.policy,
                qualityOverrideExplicit = qualitySession.isExplicitSessionChoice,
                inheritedQualityPolicy = activePlaybackPreferences.effectiveDefaultQualityPolicy(backend),
                inheritedQualityUsesVlcSetting = activePlaybackPreferences.usesVlcDefaultQuality(backend),
                pickerVisible = pickerVisible,
                playlist = if (isKidsSingleAsset) null else playlist,
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
                playbackChangeNotice = playbackChangeNotice,
                activeBackend = backend,
                backendChoices = backendSwitchChoices(),
                backendSwitchControlVisible = hasBackendSwitchTarget(),
                backendSwitchControlEnabled = canOpenBackendPicker(),
                backendSwitchInProgress = backendSwitchInProgress,
                playbackGuidance = playbackGuidance,
                playbackActionNotice = playbackActionNotice,
                resizeMode = resizeMode,
                supportsVideoSizing = playerController.supportsVideoSizing,
                upNext = upNext,
                autoplayPolicy = if (isKidsSingleAsset) AutoplayPolicySnapshot(enabled = false) else autoplayPolicyFor(upNext),
                stillWatchingPrompt = !isKidsSingleAsset && stillWatchingState.isPromptVisible,
                timingState = timingCoordinator.timingState,
                debugInfo = buildDebugInfo(subtitleRenderInfo, subtitleStyleable),
                videoPresentation = plan?.videoPresentation,
                pictureInPictureRequiresLinearPlayback =
                    installedPlan?.streamMode == StreamMode.Transcode &&
                        playerController.transcodeSeekRestartsStream,
                isSeekable = installedPlan?.contentTimeline is PlaybackContentTimeline.BoundedVod,
                playbackItemId = currentItemId.takeUnless { queueSwitchInFlight },
            )
        reduce(PlayerChange.ContentReady(content))
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
            (playbackState.subtitleActivation as? com.jellyscope.core.domain.playback.SubtitleActivationState.Active)
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
        sessionSequence: Long = currentPlan?.diagnosticSessionSequence ?: playbackDiagnosticSession,
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
        val error = planningPlaybackError(exception)
        return PlayerUiState.Error(
            retryable = error != PlaybackError.UnsupportedMedia,
            error = error,
        )
    }

    private fun planningPlaybackError(exception: Throwable): PlaybackError {
        val copyFailure = exception as? PlaybackPlanningException.SourceVideoCopyUnsupported
        val isDeterministicCapabilityFailure =
            when (copyFailure?.cause) {
                null -> copyFailure != null
                is PlaybackPlanningException.SourceVideoCopyRejected,
                PlaybackPlanningException.NoSupportedStream,
                -> true

                else -> false
            }
        val rootFailure = copyFailure?.cause ?: exception
        return when {
            isDeterministicCapabilityFailure -> PlaybackError.UnsupportedMedia
            rootFailure is PlaybackPlanningException.RemoteRequestFailed && rootFailure.isNetworkFailure ->
                PlaybackError.Network
            else -> PlaybackError.Unknown
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

    /** Durable writes are reserved for explicit audio choices; process-local memory tracks substitutions and subtitle actions. */
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
        // Automatic progression never reopens recovery closed by Stop.
        if (!canAcceptPlaybackStart() || (auto && recoveryClosedByStop)) return false
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
            explicitPlaybackAction = !auto,
        )
        return true
    }

    private fun nextQueueIndex(): Int? =
        (currentQueueIndex + 1)
            .takeIf { index -> queueIds.size > 1 && index in queueIds.indices }

    // Callers check start admission before their own mutations; only an explicit start reopens recovery.
    private fun startQueueSwitch(
        index: Int,
        stopPositionMs: Long,
        explicitPlaybackAction: Boolean,
    ) {
        if (queueIds.size <= 1 || index !in queueIds.indices) {
            return
        }
        if (explicitPlaybackAction) recoveryClosedByStop = false
        invalidateBackendSwitch()
        autoplayGeneration += 1
        // Invalidate identity before async planning so held seeks cancel now.
        queueSwitchInFlight = true
        installedPlan = null
        reduce(PlayerChange.QueueSwitchStarted)
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
        if (isKidsSingleAsset) return
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
        if (isKidsSingleAsset) return
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

/**
 * Current Kids playback values carried into one same-target Retry/Replay attempt. They apply only
 * while that source still resolves and never become explicit or persisted intent.
 */
private data class RetainedKidsPlaybackSelection(
    val mediaSourceId: String,
    val audioStreamIndex: Int?,
    val subtitleSelection: SubtitleSelectionIntent,
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
    val diagnosticSession: Long,
    val itemId: String,
    val mediaSourceId: String,
    val activePlan: PlaybackPlan,
    val activeController: PlayerController,
    val wasActiveAtRequest: Boolean,
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
    var planningError: PlaybackError = PlaybackError.Unknown,
    val softwareRecoveryToken: Long? = null,
    val recoveryQuality: PlayerQualitySessionState? = null,
    val recoveryBudget: AutoPlaybackRecoveryState? = null,
)

private val backendSwitchTerminalStatuses =
    setOf(
        PlaybackStatus.Idle,
        PlaybackStatus.Failed,
        PlaybackStatus.Completed,
    )

private val playbackChangeActiveStatuses =
    setOf(
        PlaybackStatus.Playing,
        PlaybackStatus.Paused,
        PlaybackStatus.Buffering,
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

private const val SOFTWARE_PLAYBACK_PREPARE_GRACE_MS = 10_000L
private const val SOFTWARE_PLAYBACK_TRANSITION_EXCLUSION_MS = 2_000L
