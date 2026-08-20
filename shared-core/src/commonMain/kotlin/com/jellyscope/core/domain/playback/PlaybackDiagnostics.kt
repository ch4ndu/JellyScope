// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.safeDiagnosticType

sealed interface DiagnosticReason

sealed interface PlaybackDiagnosticOperation {
    val diagnosticValue: String
}

/**
 * Where the current quality policy came from. Unlike [PlaybackQualityCapOrigin],
 * this stays meaningful for unbounded Auto and Original playback.
 */
enum class PlaybackQualityPolicyOrigin {
    SettingsDefault,
    SessionOverride,
    SessionAutoRecovery,
}

/** The source-copy conclusion from the decoder-capability preflight. */
enum class PlaybackCapabilityResult {
    SourceCopyAllowed,
    SourceCopyRejected,
    NoFiniteCapabilityCap,
}

/** Runtime state of the strongest first-video-output fact the active backend exposes. */
enum class PlaybackFirstVideoOutputState {
    Unsupported,
    Awaiting,
    Observed,
    TimedOut,
}

enum class PlaybackPreflightReason : DiagnosticReason {
    VideoCodecNotSupported,
    VideoBitDepthNotSupported,
    VideoResolutionNotSupported,
    VideoRangeTypeNotSupported,
}

enum class PlaybackCompletionReadinessReason : DiagnosticReason {
    PlaylistEntryUnresolved,
}

enum class Media3AudioDecoderReason : DiagnosticReason {
    BundledFfmpeg,
    Platform,
    Unknown,
}

enum class RepositoryOperation(
    val diagnosticOperation: DiagnosticOperation,
) : PlaybackDiagnosticOperation {
    UploadClientLogs(DiagnosticOperation.UploadClientLogs),
    GetLibraries(DiagnosticOperation.GetLibraries),
    GetContinueWatching(DiagnosticOperation.GetContinueWatching),
    GetNextUp(DiagnosticOperation.GetNextUp),
    GetItemDetail(DiagnosticOperation.GetItemDetail),
    GetRelated(DiagnosticOperation.GetRelated),
    GetRelatedGroups(DiagnosticOperation.GetRelatedGroups),
    GetSeasons(DiagnosticOperation.GetSeasons),
    GetEpisodes(DiagnosticOperation.GetEpisodes),
    GetRecentlyAdded(DiagnosticOperation.GetRecentlyAdded),
    GetUpcomingEpisodes(DiagnosticOperation.GetUpcomingEpisodes),
    GetLibraryItems(DiagnosticOperation.GetLibraryItems),
    GetLibraryShuffleQueue(DiagnosticOperation.GetLibraryShuffleQueue),
    GetItemsByIds(DiagnosticOperation.GetItemsByIds),
    GetGenres(DiagnosticOperation.GetGenres),
    GetStudios(DiagnosticOperation.GetStudios),
    GetLibraryFilters(DiagnosticOperation.GetLibraryFilters),
    GetLibraryRecommendationSection(DiagnosticOperation.GetLibraryRecommendationSection),
    GetMediaSegments(DiagnosticOperation.GetMediaSegments),
    GetCollections(DiagnosticOperation.GetCollections),
    GetCollectionItems(DiagnosticOperation.GetCollectionItems),
    GetGenreItems(DiagnosticOperation.GetGenreItems),
    GetStudioItems(DiagnosticOperation.GetStudioItems),
    GetPerson(DiagnosticOperation.GetPerson),
    GetPersonItems(DiagnosticOperation.GetPersonItems),
    GetPersonItemsPage(DiagnosticOperation.GetPersonItemsPage),
    GetSuggestions(DiagnosticOperation.GetSuggestions),
    GetRibbonItems(DiagnosticOperation.GetRibbonItems),
    GetFavorites(DiagnosticOperation.GetFavorites),
    Search(DiagnosticOperation.Search),
    FindPersons(DiagnosticOperation.FindPersons),
    GetPlaybackInfo(DiagnosticOperation.GetPlaybackInfo),
    SetPlayed(DiagnosticOperation.SetPlayed),
    SetFavorite(DiagnosticOperation.SetFavorite),
    ;

    override val diagnosticValue: String
        get() = diagnosticOperation.wireValue
}

enum class PlayerOperation(
    override val diagnosticValue: String,
) : PlaybackDiagnosticOperation {
    Pause("pause"),
    Play("play"),
    Prepare("prepare"),
    Retry("retry"),
    SeekTo("seekTo"),
    SelectEmbeddedAudio("selectEmbeddedAudio"),
    SelectEmbeddedSubtitle("selectEmbeddedSubtitle"),
    SetPlaybackSpeed("setPlaybackSpeed"),
    SetSubtitleStyle("setSubtitleStyle"),
    Stop("stop"),
    EventDrain("eventDrain"),
    SubtitleActivation("subtitleActivation"),
    AudioActivation("audioActivation"),
    RenderFrameIfNeeded("renderFrameIfNeeded"),
}

enum class SubtitleActivationFailureReason(
    val diagnosticValue: String,
) : DiagnosticReason {
    AvfoundationSidecarCompositionUnavailable("avfoundation-sidecar-composition-unavailable"),
    ControllerRejected("controller-rejected"),
    DesktopVlcSelectionExhausted("desktop-vlc-selection-exhausted"),
    DesktopVlcSidecarUnavailable("desktop-vlc-sidecar-unavailable"),
    MissingExternalAsset("missing-external-asset"),
    MissingExternalResource("missing-external-resource"),
    MpvEventQueueOverflow("mpv-event-queue-overflow"),
    MpvLoadEndedBeforeSubAdd("mpv-load-ended-before-sub-add"),
    MpvSubAddRejected("mpv-sub-add-rejected"),
    MpvSubAddSuperseded("mpv-sub-add-superseded"),
    PlaylistEntryUnresolved("playlist-entry-unresolved"),
    ResourceOriginRejected("resource-origin-rejected"),
    SubtitleAssetUnavailable("subtitle-asset-unavailable"),
    SubtitleAttachRejected("subtitle-attach-rejected"),
    VlckitSidecarUnavailable("vlckit-sidecar-unavailable"),
}

enum class PlaybackDiagnosticStage {
    Repository,
    Planner,
    Profile,
    Mapping,
    Prepare,
    AudioSession,
    TimeObserver,
    Render,
    Release,
    PictureInPicture,
    NativePlayer,
    Persistence,
    Reporting,
    TvDisplay,
}

enum class PlaybackBackendAvailability {
    Bundled,
    Unavailable,
}

enum class PlaybackBackendUnavailableReason {
    OsApiBelowMinimum,
    ClassLoaderUnavailable,
    MpvLibraryMissing,
    PlayerLibraryMissing,
    LibraryAbiUnknown,
    LibraryAbiMismatch,
    AbiNotShipped,
    DeviceAbiUnsupported,
}

enum class PlaybackBackendAbi {
    Arm64V8a,
    ArmeabiV7a,
    X86_64,
}

enum class PlaybackBackendConstructionStage {
    NotAttempted,
    ResolveSubtitleStore,
    CreateNetworkPolicy,
    CreateTrustBundle,
    ResolveAudioFocus,
    CreateController,
    CreateNativeEngine,
    ApplyNativeOption,
    RegisterNativeObservers,
    ObserveNativeProperty,
    InitializeNativeEngine,
}

enum class PlaybackBackendConstructionResult {
    Created,
    Failed,
    Unavailable,
    NotAttempted,
}

enum class PlaybackNativePlayerMilestone {
    SurfaceAttached,
    SurfaceAlreadyAttached,
    SurfaceSizeChanged,
    SurfaceDetached,
    SurfaceReleaseTimedOut,
    StaleSurfaceReleaseIgnored,
    StaleSurfaceCallbackIgnored,
    NetworkRequestAccepted,
    LoadCommandDispatched,
    StartFile,
    FileLoaded,
    PlayUnpauseDispatched,
    EndFile,
    Shutdown,
    StartupTimeout,
}

enum class PlaybackNativeCommandShape {
    LoadFileUrlFlagsIndexOptions,
}

enum class PlaybackDiagnosticEvent {
    Failed,
    Fallback,
    QualityCap,
    ResolutionCap,
    Probe,
    CodecDropped,
    Resolved,
    Rejected,
    Timeout,
    Stalled,
    Ended,
    Waiting,
    ErrorLog,
    AccessLog,
    SeekStarted,
    SeekCompleted,
    PerformanceSummary,
    HealthSignal,
    HealthSummary,
    TerminalError,
    BackendSelection,
    BackendReadiness,
    RecoveryDecision,
    VideoOutput,
    Read,
    Write,
    PrepareRequested,
    PrepareDispatched,
    BackendConstruction,
    NativeLifecycle,
    TrackState,
    Reporting,
    TvDisplay,
}

enum class PlaybackPersistenceTarget {
    PlaybackSelection,
    BackendOverride,
}

enum class PlaybackPersistenceResult {
    Present,
    Missing,
    Applied,
    Cancelled,
    Failed,
    Unavailable,
}

/** Closed native-player outcome; never carries a native message. */
enum class PlaybackTerminalOutcome {
    Failed,
    RetryScheduled,
    VideoOnlyDegradationScheduled,
    VideoOnlyPlayback,
}

/** Result of resolving the requested player engine to a concrete backend. */
enum class PlaybackBackendFallbackResult {
    NotRequired,
    Applied,
}

/** Closed description of a decoder resource policy applied by JellyScope. */
enum class PlaybackDecoderResourcePolicy {
    BoundedDav1dFrameThreads,
}

/** Closed projection of the pure Auto recovery result for upload diagnostics. */
enum class PlaybackRecoveryDecision {
    NoAction,
    CompatibilityReplan,
    LowerQuality,
    PromptUser,
}

enum class PlaybackHealthThresholdClass {
    SlowStartup10Seconds,
    NoVideoOutput5Seconds,
    NoVideoOutput20Seconds,
    LongBuffering5Seconds,
    CumulativeBuffering10SecondsIn60Seconds,
    RepeatedStalls3In60Seconds,
    DroppedFrames2PerSecondFor20SecondsIn60Seconds,
}

fun PlaybackHealthSignal.thresholdClass(): PlaybackHealthThresholdClass =
    when (this) {
        is PlaybackHealthSignal.SlowStartup -> PlaybackHealthThresholdClass.SlowStartup10Seconds
        is PlaybackHealthSignal.NoVideoOutput ->
            if (thresholdMs >= PLAYBACK_HEALTH_VLC_NO_VIDEO_OUTPUT_THRESHOLD_MS) {
                PlaybackHealthThresholdClass.NoVideoOutput20Seconds
            } else {
                PlaybackHealthThresholdClass.NoVideoOutput5Seconds
            }
        is PlaybackHealthSignal.LongBuffering -> PlaybackHealthThresholdClass.LongBuffering5Seconds
        is PlaybackHealthSignal.CumulativeBuffering ->
            PlaybackHealthThresholdClass.CumulativeBuffering10SecondsIn60Seconds
        is PlaybackHealthSignal.RepeatedStalls -> PlaybackHealthThresholdClass.RepeatedStalls3In60Seconds
        is PlaybackHealthSignal.DroppedFrames ->
            PlaybackHealthThresholdClass.DroppedFrames2PerSecondFor20SecondsIn60Seconds
    }

enum class PlaybackDiagnosticPlatform {
    Shared,
    Android,
    Ios,
    TvOs,
    Desktop,
}

enum class PlaybackDiagnosticTrackKind {
    Audio,
    Subtitle,
}

/** Closed requested/confirmed projection for an identity-free track record. */
enum class PlaybackDiagnosticTrackState {
    Off,
    Selected,
}

/** Closed activation state for an identity-free track record. */
enum class PlaybackDiagnosticTrackActivation {
    None,
    Pending,
    Active,
    Unavailable,
}

/** Operations emitted by the ordered playback reporting queue. */
enum class PlaybackReportingOperation(
    val diagnosticValue: String,
) {
    Start("start"),
    Progress("progress"),
    Stop("stop"),
}

/** Result of an attempted reporting operation; no exception text is carried. */
enum class PlaybackReportingResult {
    Success,
    Failed,
}

/** Closed TV display outcome used by the structured diagnostic boundary. */
enum class PlaybackDiagnosticDisplayResult {
    Idle,
    Off,
    Unavailable,
    Applied,
    Switched,
    TimedOut,
    Unrecognized,
}

/** Closed TV display match tier used by the structured diagnostic boundary. */
enum class PlaybackDiagnosticDisplayTier {
    Exact,
    IntegerMultiple,
    TwoPointFive,
    Unrecognized,
}

enum class PlaybackRequestPolicyClass {
    Default,
    DirectPlayDisabled,
    DirectStreamDisabled,
    ForceEncodeSubtitle,
}

data class PlaybackDiagnostic(
    val stage: PlaybackDiagnosticStage,
    val event: PlaybackDiagnosticEvent,
    val platform: PlaybackDiagnosticPlatform? = null,
    val backend: PlayerBackend? = null,
    val requestedBackend: PlayerBackend? = null,
    val backendFallbackResult: PlaybackBackendFallbackResult? = null,
    val backendAvailability: PlaybackBackendAvailability? = null,
    val backendUnavailableReason: PlaybackBackendUnavailableReason? = null,
    val backendAbi: PlaybackBackendAbi? = null,
    val backendConstructionStage: PlaybackBackendConstructionStage? = null,
    val backendConstructionResult: PlaybackBackendConstructionResult? = null,
    val backendConfigurationKey: String? = null,
    val nativePlayerMilestone: PlaybackNativePlayerMilestone? = null,
    val nativeCommandShape: PlaybackNativeCommandShape? = null,
    val nativeReady: Boolean? = null,
    val nativeLoadOutstanding: Boolean? = null,
    val nativeStartObserved: Boolean? = null,
    val nativeFileLoaded: Boolean? = null,
    val nativeSurfaceAttached: Boolean? = null,
    val nativeSurfaceWidthPx: Int? = null,
    val nativeSurfaceHeightPx: Int? = null,
    val nativePlayIntent: Boolean? = null,
    val nativeAudioGatePending: Boolean? = null,
    val decoderResourcePolicy: PlaybackDecoderResourcePolicy? = null,
    val decoderFrameThreads: Int? = null,
    val exceptionType: String? = null,
    val errorCategory: PlaybackError? = null,
    val terminalOutcome: PlaybackTerminalOutcome? = null,
    val prepareSequence: Long? = null,
    val sessionSequence: Long? = null,
    val retryAttempted: Boolean? = null,
    val degradationAttempted: Boolean? = null,
    val nativeCode: Long? = null,
    val httpCode: Int? = null,
    val streamMode: StreamMode? = null,
    val requestPolicy: PlaybackRequestPolicyClass? = null,
    val clientTrigger: PlaybackClientTrigger? = null,
    val qualityCapOrigin: PlaybackQualityCapOrigin? = null,
    val capabilityResult: PlaybackCapabilityResult? = null,
    val resolutionPolicy: PlaybackResolutionPolicy? = null,
    val transcodeReasons: List<String> = emptyList(),
    val startPositionMs: Long? = null,
    val seekOriginPositionMs: Long? = null,
    val seekTargetPositionMs: Long? = null,
    val seekObservedPositionMs: Long? = null,
    val deliveryMethod: SubtitleDeliveryMethod? = null,
    val trackKind: PlaybackDiagnosticTrackKind? = null,
    val candidateCount: Int? = null,
    val mappingResult: NativeTrackMappingResult? = null,
    val mappingReason: NativeTrackMappingReason? = null,
    val reason: DiagnosticReason? = null,
    val operation: PlaybackDiagnosticOperation? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val frameRate: Double? = null,
    val sourceBitDepth: Int? = null,
    val sourceVideoRangeType: String? = null,
    val sourceBitrateBps: Long? = null,
    val cappedWidth: Int? = null,
    val cappedHeight: Int? = null,
    val maxStreamingBitrate: Long? = null,
    val requestCapBitrateBps: Long? = null,
    val effectiveTranscodeCapBitrateBps: Long? = null,
    val codec: String? = null,
    val level: Int? = null,
    val container: String? = null,
    val videoDecoderName: String? = null,
    val runtimeVideoWidth: Int? = null,
    val runtimeVideoHeight: Int? = null,
    val runtimeVideoFrameRate: Double? = null,
    val bandwidthEstimateBps: Long? = null,
    val recentVideoRenderP95Ms: Double? = null,
    val recentPresentedFrameRate: Double? = null,
    val presentationGapCount: Long? = null,
    val bufferPolicy: PlaybackBufferPolicy? = null,
    val lowRamDevice: Boolean? = null,
    val targetBufferBytes: Long? = null,
    val peakAllocatedBufferBytes: Long? = null,
    val minBufferedAheadMs: Long? = null,
    val maxBufferedAheadMs: Long? = null,
    val nativePrepareToFirstFrameMs: Long? = null,
    val launchToFirstFrameMs: Long? = null,
    val rebufferCount: Int? = null,
    val totalRebufferMs: Long? = null,
    val maxRebufferMs: Long? = null,
    val audioUnderrunCount: Int? = null,
    val maxAudioFeedGapMs: Long? = null,
    val healthSignal: PlaybackHealthSignalKind? = null,
    val healthThresholdClass: PlaybackHealthThresholdClass? = null,
    val healthDurationMs: Long? = null,
    val healthCount: Int? = null,
    val healthFirstPlayingObserved: Boolean? = null,
    val healthBufferingDurationMs: Long? = null,
    val healthBufferingIntervalCount: Int? = null,
    val healthStallCount: Int? = null,
    val healthDroppedFrameDurationMs: Long? = null,
    val healthDroppedFrameSampleCount: Int? = null,
    val healthPostStartGuidanceShown: Boolean? = null,
    val healthNoVideoOutputGuidanceShown: Boolean? = null,
    val healthGuidancePolicy: PlaybackHealthGuidancePolicy? = null,
    val healthGuidancePublishable: Boolean? = null,
    val healthBufferingIntervalOpenedSinceEvidenceRestart: Boolean? = null,
    val backendDroppedVideoFrames: Long? = null,
    val backendDroppedVideoFramesPerSecond: Double? = null,
    val decoderDroppedVideoFrames: Long? = null,
    val outputDroppedVideoFrames: Long? = null,
    val healthEmittedSignalCount: Int? = null,
    val effectiveTranscodeBudgetBps: Long? = null,
    val qualityPolicyMode: PlaybackQualityMode? = null,
    val qualityPolicyOrigin: PlaybackQualityPolicyOrigin? = null,
    val bitrateConstraint: String? = null,
    val recoveryIntent: PlaybackRecoveryIntent? = null,
    val autoRecoveryTrigger: AutoPlaybackRecoveryTrigger? = null,
    val recoveryDecision: PlaybackRecoveryDecision? = null,
    val recoveryPromptReason: AutoPlaybackRecoveryPromptReason? = null,
    val recoveryCompatibilityAttempted: Boolean? = null,
    val recoveryQualityAttempted: Boolean? = null,
    val recoveryBudgetExhausted: Boolean? = null,
    val recoveryFromQualityBudgetBps: Long? = null,
    val recoveryToQualityBudgetBps: Long? = null,
    val firstVideoOutputAvailable: Boolean? = null,
    val firstVideoOutputObserved: Boolean? = null,
    val firstVideoOutputEvidence: VideoOutputEvidence? = null,
    val persistenceTarget: PlaybackPersistenceTarget? = null,
    val persistenceResult: PlaybackPersistenceResult? = null,
    /** Current runtime values are optional: null means the backend did not expose them. */
    val allocatedBufferBytes: Long? = null,
    val bufferedAheadMs: Long? = null,
    val libVlcCachePercent: Float? = null,
    val reportingOperation: PlaybackReportingOperation? = null,
    val reportingResult: PlaybackReportingResult? = null,
    val trackRequestedState: PlaybackDiagnosticTrackState? = null,
    val trackConfirmedState: PlaybackDiagnosticTrackState? = null,
    val trackMatchesRequest: Boolean? = null,
    val trackActivation: PlaybackDiagnosticTrackActivation? = null,
    val subtitleRenderMode: SubtitleRenderMode? = null,
    val subtitleRenderStatus: SubtitleRenderStatus? = null,
    val subtitleStyleable: Boolean? = null,
    val tvDisplayResult: PlaybackDiagnosticDisplayResult? = null,
    val tvDisplayTier: PlaybackDiagnosticDisplayTier? = null,
    val tvDisplayActiveWidthPx: Int? = null,
    val tvDisplayActiveHeightPx: Int? = null,
    val tvDisplayActiveRefreshMilliHz: Int? = null,
    val tvDisplayRequestedWidthPx: Int? = null,
    val tvDisplayRequestedHeightPx: Int? = null,
    val tvDisplayRequestedRefreshMilliHz: Int? = null,
    val tvDisplaySwitchDurationMs: Long? = null,
)

fun formatPlaybackDiagnostic(diagnostic: PlaybackDiagnostic): String =
    buildList {
        appendDiagnosticField(PlaybackDiagnosticField.Stage, diagnostic.stage.name.lowercase())
        appendDiagnosticField(PlaybackDiagnosticField.Event, diagnostic.event.diagnosticName())
        diagnostic.platform?.let {
            appendDiagnosticField(PlaybackDiagnosticField.Platform, it.name.lowercase())
        }
        diagnostic.backend?.let {
            appendDiagnosticField(PlaybackDiagnosticField.Backend, it.name.lowercase())
        }
        diagnostic.requestedBackend?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RequestedBackend, it.name.lowercase())
        }
        diagnostic.backendFallbackResult?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BackendFallbackResult, it.name.lowercase())
        }
        diagnostic.backendAvailability?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BackendAvailability, it.name)
        }
        diagnostic.backendUnavailableReason?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BackendUnavailableReason, it.name)
        }
        diagnostic.backendAbi?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BackendAbi, it.diagnosticName())
        }
        diagnostic.backendConstructionStage?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BackendConstructionStage, it.name)
        }
        diagnostic.backendConstructionResult?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BackendConstructionResult, it.name)
        }
        diagnostic.backendConfigurationKey?.safeBackendConfigurationKey()?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BackendConfigurationKey, it)
        }
        diagnostic.nativePlayerMilestone?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativePlayerMilestone, it.name)
        }
        diagnostic.nativeCommandShape?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativeCommandShape, it.name)
        }
        diagnostic.nativeReady?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativeReady, it)
        }
        diagnostic.nativeLoadOutstanding?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativeLoadOutstanding, it)
        }
        diagnostic.nativeStartObserved?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativeStartObserved, it)
        }
        diagnostic.nativeFileLoaded?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativeFileLoaded, it)
        }
        diagnostic.nativeSurfaceAttached?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativeSurfaceAttached, it)
        }
        diagnostic.nativeSurfaceWidthPx
            ?.takeIf { it in 1..MAX_DIAGNOSTIC_VIDEO_DIMENSION }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.NativeSurfaceWidthPx, it) }
        diagnostic.nativeSurfaceHeightPx
            ?.takeIf { it in 1..MAX_DIAGNOSTIC_VIDEO_DIMENSION }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.NativeSurfaceHeightPx, it) }
        diagnostic.nativePlayIntent?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativePlayIntent, it)
        }
        diagnostic.nativeAudioGatePending?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativeAudioGatePending, it)
        }
        diagnostic.decoderResourcePolicy?.let {
            appendDiagnosticField(PlaybackDiagnosticField.DecoderResourcePolicy, it.name)
        }
        diagnostic.decoderFrameThreads
            ?.takeIf { it in MINIMUM_DECODER_FRAME_THREADS..MAXIMUM_DECODER_FRAME_THREADS }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.DecoderFrameThreads, it) }
        diagnostic.exceptionType?.safeExceptionType()?.let {
            appendDiagnosticField(PlaybackDiagnosticField.ExceptionType, it)
        }
        diagnostic.errorCategory?.let {
            appendDiagnosticField(PlaybackDiagnosticField.ErrorCategory, it.diagnosticName())
        }
        diagnostic.terminalOutcome?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TerminalOutcome, it.name)
        }
        diagnostic.prepareSequence?.coerceIn(0L, MAX_DIAGNOSTIC_SEQUENCE)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.PrepareSequence, it)
        }
        diagnostic.sessionSequence?.coerceIn(0L, MAX_DIAGNOSTIC_SEQUENCE)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SessionSequence, it)
        }
        diagnostic.retryAttempted?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RetryAttempted, it)
        }
        diagnostic.degradationAttempted?.let {
            appendDiagnosticField(PlaybackDiagnosticField.DegradationAttempted, it)
        }
        diagnostic.nativeCode?.let { appendDiagnosticField(PlaybackDiagnosticField.NativeCode, it) }
        diagnostic.httpCode?.let { appendDiagnosticField(PlaybackDiagnosticField.HttpCode, it) }
        diagnostic.streamMode?.let { appendDiagnosticField(PlaybackDiagnosticField.StreamMode, it.name) }
        diagnostic.requestPolicy?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RequestPolicy, it.name)
        }
        diagnostic.clientTrigger?.let {
            appendDiagnosticField(PlaybackDiagnosticField.ClientTrigger, it.name)
        }
        diagnostic.qualityCapOrigin?.let {
            appendDiagnosticField(PlaybackDiagnosticField.QualityCapOrigin, it.diagnosticName())
        }
        diagnostic.capabilityResult?.let {
            appendDiagnosticField(PlaybackDiagnosticField.CapabilityResult, it.name)
        }
        diagnostic.resolutionPolicy?.let {
            appendDiagnosticField(PlaybackDiagnosticField.ResolutionPolicy, it.name)
        }
        diagnostic.transcodeReasons
            .map(::safeTranscodeReason)
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(",")
            ?.let { reasons ->
                appendDiagnosticField(PlaybackDiagnosticField.TranscodeReasons, reasons)
            }
        diagnostic.startPositionMs?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.StartPositionMs, it)
        }
        diagnostic.seekOriginPositionMs
            ?.coerceIn(0L, MAX_DIAGNOSTIC_POSITION_MS)
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.SeekOriginPositionMs, it) }
        diagnostic.seekTargetPositionMs
            ?.coerceIn(0L, MAX_DIAGNOSTIC_POSITION_MS)
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.SeekTargetPositionMs, it) }
        diagnostic.seekObservedPositionMs
            ?.coerceIn(0L, MAX_DIAGNOSTIC_POSITION_MS)
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.SeekObservedPositionMs, it) }
        diagnostic.deliveryMethod?.let {
            appendDiagnosticField(PlaybackDiagnosticField.DeliveryMethod, it.name)
        }
        diagnostic.trackKind?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TrackKind, it.name.lowercase())
        }
        diagnostic.trackRequestedState?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TrackRequestedState, it.name)
        }
        diagnostic.trackConfirmedState?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TrackConfirmedState, it.name)
        }
        diagnostic.trackMatchesRequest?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TrackMatchesRequest, it)
        }
        diagnostic.trackActivation?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TrackActivation, it.name)
        }
        diagnostic.subtitleRenderMode?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SubtitleRenderMode, it.name)
        }
        diagnostic.subtitleRenderStatus?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SubtitleRenderStatus, it.name)
        }
        diagnostic.subtitleStyleable?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SubtitleStyleable, it)
        }
        diagnostic.candidateCount?.coerceAtLeast(0)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.CandidateCount, it)
        }
        diagnostic.mappingResult?.let {
            appendDiagnosticField(PlaybackDiagnosticField.MappingResult, it.name.lowercase())
        }
        diagnostic.mappingReason?.let {
            appendDiagnosticField(PlaybackDiagnosticField.MappingReason, it.name.lowercase())
        }
        diagnostic.sourceWidth?.coerceIn(0, MAX_DIAGNOSTIC_VIDEO_DIMENSION)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SourceWidth, it)
        }
        diagnostic.sourceHeight?.coerceIn(0, MAX_DIAGNOSTIC_VIDEO_DIMENSION)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SourceHeight, it)
        }
        diagnostic.frameRate?.boundedDiagnosticDouble(MAX_DIAGNOSTIC_FRAME_RATE)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.FrameRate, it)
        }
        diagnostic.sourceBitDepth?.coerceIn(0, MAX_DIAGNOSTIC_BIT_DEPTH)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SourceBitDepth, it)
        }
        diagnostic.sourceVideoRangeType?.safeSourceVideoRangeType()?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SourceVideoRangeType, it)
        }
        diagnostic.sourceBitrateBps?.coerceIn(0L, MAX_DIAGNOSTIC_BITRATE_BPS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.SourceBitrateBps, it)
        }
        diagnostic.cappedWidth?.let {
            appendDiagnosticField(PlaybackDiagnosticField.CappedWidth, it)
        }
        diagnostic.cappedHeight?.let {
            appendDiagnosticField(PlaybackDiagnosticField.CappedHeight, it)
        }
        diagnostic.codec?.let {
            appendDiagnosticField(PlaybackDiagnosticField.Codec, DiagnosticsCodecAllowlist.renderCodec(it))
        }
        diagnostic.level?.let {
            appendDiagnosticField(PlaybackDiagnosticField.Level, it)
        }
        diagnostic.container?.let {
            appendDiagnosticField(PlaybackDiagnosticField.Container, DiagnosticsCodecAllowlist.renderContainer(it))
        }
        diagnostic.videoDecoderName?.safeVideoDecoderCategory()?.let {
            appendDiagnosticField(PlaybackDiagnosticField.VideoDecoderName, it)
        }
        diagnostic.runtimeVideoWidth?.coerceIn(0, MAX_DIAGNOSTIC_VIDEO_DIMENSION)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RuntimeVideoWidth, it)
        }
        diagnostic.runtimeVideoHeight?.coerceIn(0, MAX_DIAGNOSTIC_VIDEO_DIMENSION)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RuntimeVideoHeight, it)
        }
        diagnostic.runtimeVideoFrameRate?.boundedDiagnosticDouble(MAX_DIAGNOSTIC_FRAME_RATE)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RuntimeVideoFrameRate, it)
        }
        diagnostic.bandwidthEstimateBps?.coerceIn(0L, MAX_DIAGNOSTIC_BITRATE_BPS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BandwidthEstimateBps, it)
        }
        diagnostic.recentVideoRenderP95Ms?.boundedDiagnosticDouble(MAX_HEALTH_DURATION_MS.toDouble())?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecentVideoRenderP95Ms, it)
        }
        diagnostic.recentPresentedFrameRate?.boundedDiagnosticDouble(MAX_DIAGNOSTIC_FRAME_RATE)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecentPresentedFrameRate, it)
        }
        diagnostic.presentationGapCount?.coerceIn(0L, MAX_DROPPED_FRAME_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.PresentationGapCount, it)
        }
        diagnostic.reason?.let {
            appendDiagnosticField(PlaybackDiagnosticField.Reason, it.diagnosticName())
        }
        diagnostic.maxStreamingBitrate?.let {
            appendDiagnosticField(
                PlaybackDiagnosticField.MaxStreamingBitrate,
                it.coerceIn(0L, MAX_DIAGNOSTIC_BITRATE_BPS),
            )
        }
        diagnostic.requestCapBitrateBps?.coerceIn(0L, MAX_DIAGNOSTIC_BITRATE_BPS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RequestCapBitrateBps, it)
        }
        diagnostic.effectiveTranscodeCapBitrateBps?.coerceIn(0L, MAX_DIAGNOSTIC_BITRATE_BPS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.EffectiveTranscodeCapBitrateBps, it)
        }
        diagnostic.qualityPolicyMode?.let {
            appendDiagnosticField(PlaybackDiagnosticField.QualityPolicyMode, it.name)
        }
        diagnostic.qualityPolicyOrigin?.let {
            appendDiagnosticField(PlaybackDiagnosticField.QualityPolicyOrigin, it.name)
        }
        diagnostic.bitrateConstraint?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BitrateConstraint, it)
        }
        diagnostic.recoveryIntent?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecoveryIntent, it.name)
        }
        diagnostic.autoRecoveryTrigger?.let {
            appendDiagnosticField(PlaybackDiagnosticField.AutoRecoveryTrigger, it.name)
        }
        diagnostic.recoveryDecision?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecoveryDecision, it.name)
        }
        diagnostic.recoveryPromptReason?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecoveryPromptReason, it.name)
        }
        diagnostic.recoveryCompatibilityAttempted?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecoveryCompatibilityAttempted, it)
        }
        diagnostic.recoveryQualityAttempted?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecoveryQualityAttempted, it)
        }
        diagnostic.recoveryBudgetExhausted?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecoveryBudgetExhausted, it)
        }
        diagnostic.recoveryFromQualityBudgetBps?.coerceIn(0L, MAX_DIAGNOSTIC_BITRATE_BPS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecoveryFromQualityBudgetBps, it)
        }
        diagnostic.recoveryToQualityBudgetBps?.coerceIn(0L, MAX_DIAGNOSTIC_BITRATE_BPS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RecoveryToQualityBudgetBps, it)
        }
        diagnostic.firstVideoOutputAvailable?.let {
            appendDiagnosticField(PlaybackDiagnosticField.FirstVideoOutputAvailable, it)
        }
        diagnostic.firstVideoOutputObserved?.let {
            appendDiagnosticField(PlaybackDiagnosticField.FirstVideoOutputObserved, it)
        }
        diagnostic.firstVideoOutputEvidence?.let {
            appendDiagnosticField(PlaybackDiagnosticField.FirstVideoOutputEvidence, it.name)
        }
        diagnostic.persistenceTarget?.let {
            appendDiagnosticField(PlaybackDiagnosticField.PersistenceTarget, it.name)
        }
        diagnostic.persistenceResult?.let {
            appendDiagnosticField(PlaybackDiagnosticField.PersistenceResult, it.name)
        }
        diagnostic.reportingOperation?.let {
            appendDiagnosticField(PlaybackDiagnosticField.ReportingOperation, it.diagnosticValue)
        }
        diagnostic.reportingResult?.let {
            appendDiagnosticField(PlaybackDiagnosticField.ReportingResult, it.name)
        }
        diagnostic.operation?.let {
            appendDiagnosticField(PlaybackDiagnosticField.Operation, it.diagnosticValue)
        }
        diagnostic.bufferPolicy?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BufferPolicy, it.name)
        }
        diagnostic.lowRamDevice?.let {
            appendDiagnosticField(PlaybackDiagnosticField.LowRamDevice, it)
        }
        diagnostic.targetBufferBytes?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TargetBufferBytes, it)
        }
        diagnostic.allocatedBufferBytes?.takeIf { it >= 0L }?.let {
            appendDiagnosticField(PlaybackDiagnosticField.AllocatedBufferBytes, it)
        }
        diagnostic.peakAllocatedBufferBytes?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.PeakAllocatedBufferBytes, it)
        }
        diagnostic.bufferedAheadMs?.takeIf { it >= 0L }?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BufferedAheadMs, it)
        }
        diagnostic.minBufferedAheadMs?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.MinBufferedAheadMs, it)
        }
        diagnostic.maxBufferedAheadMs?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.MaxBufferedAheadMs, it)
        }
        diagnostic.libVlcCachePercent
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.coerceIn(0f, 100f)
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.LibVlcCachePercent, it) }
        diagnostic.tvDisplayResult?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TvDisplayResult, it.diagnosticName())
        }
        diagnostic.tvDisplayTier?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TvDisplayTier, it.diagnosticName())
        }
        diagnostic.tvDisplayActiveWidthPx
            ?.takeIf { it in 1..MAX_DIAGNOSTIC_VIDEO_DIMENSION }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.TvDisplayActiveWidthPx, it) }
        diagnostic.tvDisplayActiveHeightPx
            ?.takeIf { it in 1..MAX_DIAGNOSTIC_VIDEO_DIMENSION }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.TvDisplayActiveHeightPx, it) }
        diagnostic.tvDisplayActiveRefreshMilliHz
            ?.takeIf { it in 1..MAX_DIAGNOSTIC_REFRESH_MILLIHZ }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.TvDisplayActiveRefreshMilliHz, it) }
        diagnostic.tvDisplayRequestedWidthPx
            ?.takeIf { it in 1..MAX_DIAGNOSTIC_VIDEO_DIMENSION }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.TvDisplayRequestedWidthPx, it) }
        diagnostic.tvDisplayRequestedHeightPx
            ?.takeIf { it in 1..MAX_DIAGNOSTIC_VIDEO_DIMENSION }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.TvDisplayRequestedHeightPx, it) }
        diagnostic.tvDisplayRequestedRefreshMilliHz
            ?.takeIf { it in 1..MAX_DIAGNOSTIC_REFRESH_MILLIHZ }
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.TvDisplayRequestedRefreshMilliHz, it) }
        diagnostic.tvDisplaySwitchDurationMs
            ?.takeIf { it >= 0L }
            ?.coerceAtMost(MAX_HEALTH_DURATION_MS)
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.TvDisplaySwitchDurationMs, it) }
        diagnostic.nativePrepareToFirstFrameMs?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.NativePrepareToFirstFrameMs, it)
        }
        diagnostic.launchToFirstFrameMs?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.LaunchToFirstFrameMs, it)
        }
        diagnostic.rebufferCount?.coerceAtLeast(0)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.RebufferCount, it)
        }
        diagnostic.totalRebufferMs?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.TotalRebufferMs, it)
        }
        diagnostic.maxRebufferMs?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.MaxRebufferMs, it)
        }
        diagnostic.audioUnderrunCount?.coerceAtLeast(0)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.AudioUnderrunCount, it)
        }
        diagnostic.maxAudioFeedGapMs?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.MaxAudioFeedGapMs, it)
        }
        diagnostic.healthSignal?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthSignal, it.name)
        }
        diagnostic.healthThresholdClass?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthThresholdClass, it.name)
        }
        diagnostic.healthDurationMs?.coerceIn(0L, MAX_HEALTH_DURATION_MS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthDurationMs, it)
        }
        diagnostic.healthCount?.coerceIn(0, MAX_HEALTH_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthCount, it)
        }
        diagnostic.healthFirstPlayingObserved?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthFirstPlayingObserved, it)
        }
        diagnostic.healthBufferingDurationMs?.coerceIn(0L, MAX_HEALTH_DURATION_MS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthBufferingDurationMs, it)
        }
        diagnostic.healthBufferingIntervalCount?.coerceIn(0, MAX_HEALTH_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthBufferingIntervalCount, it)
        }
        diagnostic.healthStallCount?.coerceIn(0, MAX_HEALTH_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthStallCount, it)
        }
        diagnostic.healthDroppedFrameDurationMs?.coerceIn(0L, MAX_HEALTH_DURATION_MS)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthDroppedFrameDurationMs, it)
        }
        diagnostic.healthDroppedFrameSampleCount?.coerceIn(0, MAX_HEALTH_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthDroppedFrameSampleCount, it)
        }
        diagnostic.healthPostStartGuidanceShown?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthPostStartGuidanceShown, it)
        }
        diagnostic.healthNoVideoOutputGuidanceShown?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthNoVideoOutputGuidanceShown, it)
        }
        diagnostic.healthGuidancePolicy?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthGuidancePolicy, it.name)
        }
        diagnostic.healthGuidancePublishable?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthGuidancePublishable, it)
        }
        diagnostic.healthBufferingIntervalOpenedSinceEvidenceRestart?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthBufferingIntervalOpenedSinceEvidenceRestart, it)
        }
        diagnostic.backendDroppedVideoFrames?.coerceIn(0L, MAX_DROPPED_FRAME_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.BackendDroppedVideoFrames, it)
        }
        diagnostic.backendDroppedVideoFramesPerSecond
            ?.takeIf(Double::isFinite)
            ?.coerceIn(0.0, MAX_DROPPED_FRAME_RATE)
            ?.let { appendDiagnosticField(PlaybackDiagnosticField.BackendDroppedVideoFramesPerSecond, it) }
        diagnostic.decoderDroppedVideoFrames?.coerceIn(0L, MAX_DROPPED_FRAME_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.DecoderDroppedVideoFrames, it)
        }
        diagnostic.outputDroppedVideoFrames?.coerceIn(0L, MAX_DROPPED_FRAME_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.OutputDroppedVideoFrames, it)
        }
        diagnostic.healthEmittedSignalCount?.coerceIn(0, MAX_HEALTH_COUNT)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.HealthEmittedSignalCount, it)
        }
        diagnostic.effectiveTranscodeBudgetBps?.coerceAtLeast(0L)?.let {
            appendDiagnosticField(PlaybackDiagnosticField.EffectiveTranscodeBudgetBps, it)
        }
    }.joinToString(" ")

internal enum class PlaybackDiagnosticField(
    val wireName: String,
) {
    Stage("stage"),
    Event("event"),
    Platform("platform"),
    Backend("backend"),
    RequestedBackend("requestedBackend"),
    BackendFallbackResult("backendFallbackResult"),
    BackendAvailability("backendAvailability"),
    BackendUnavailableReason("backendUnavailableReason"),
    BackendAbi("backendAbi"),
    BackendConstructionStage("backendConstructionStage"),
    BackendConstructionResult("backendConstructionResult"),
    BackendConfigurationKey("backendConfigurationKey"),
    NativePlayerMilestone("nativePlayerMilestone"),
    NativeCommandShape("nativeCommandShape"),
    NativeReady("nativeReady"),
    NativeLoadOutstanding("nativeLoadOutstanding"),
    NativeStartObserved("nativeStartObserved"),
    NativeFileLoaded("nativeFileLoaded"),
    NativeSurfaceAttached("nativeSurfaceAttached"),
    NativeSurfaceWidthPx("nativeSurfaceWidthPx"),
    NativeSurfaceHeightPx("nativeSurfaceHeightPx"),
    NativePlayIntent("nativePlayIntent"),
    NativeAudioGatePending("nativeAudioGatePending"),
    DecoderResourcePolicy("decoderResourcePolicy"),
    DecoderFrameThreads("decoderFrameThreads"),
    ExceptionType("exceptionType"),
    ErrorCategory("errorCategory"),
    TerminalOutcome("terminalOutcome"),
    PrepareSequence("prepareSequence"),
    SessionSequence("sessionSequence"),
    RetryAttempted("retryAttempted"),
    DegradationAttempted("degradationAttempted"),
    NativeCode("nativeCode"),
    HttpCode("httpCode"),
    StreamMode("streamMode"),
    RequestPolicy("requestPolicy"),
    ClientTrigger("clientTrigger"),
    QualityCapOrigin("qualityCapOrigin"),
    CapabilityResult("capabilityResult"),
    ResolutionPolicy("resolutionPolicy"),
    TranscodeReasons("transcodeReasons"),
    StartPositionMs("startPositionMs"),
    SeekOriginPositionMs("seekOriginPositionMs"),
    SeekTargetPositionMs("seekTargetPositionMs"),
    SeekObservedPositionMs("seekObservedPositionMs"),
    DeliveryMethod("deliveryMethod"),
    TrackKind("trackKind"),
    TrackRequestedState("trackRequestedState"),
    TrackConfirmedState("trackConfirmedState"),
    TrackMatchesRequest("trackMatchesRequest"),
    TrackActivation("trackActivation"),
    SubtitleRenderMode("subtitleRenderMode"),
    SubtitleRenderStatus("subtitleRenderStatus"),
    SubtitleStyleable("subtitleStyleable"),
    CandidateCount("candidateCount"),
    MappingResult("mappingResult"),
    MappingReason("mappingReason"),
    Reason("reason"),
    Operation("operation"),
    SourceWidth("sourceWidth"),
    SourceHeight("sourceHeight"),
    FrameRate("frameRate"),
    SourceBitDepth("sourceBitDepth"),
    SourceVideoRangeType("sourceVideoRangeType"),
    SourceBitrateBps("sourceBitrateBps"),
    CappedWidth("cappedWidth"),
    CappedHeight("cappedHeight"),
    MaxStreamingBitrate("maxStreamingBitrate"),
    RequestCapBitrateBps("requestCapBitrateBps"),
    EffectiveTranscodeCapBitrateBps("effectiveTranscodeCapBitrateBps"),
    Codec("codec"),
    Level("level"),
    Container("container"),
    VideoDecoderName("videoDecoderName"),
    RuntimeVideoWidth("runtimeVideoWidth"),
    RuntimeVideoHeight("runtimeVideoHeight"),
    RuntimeVideoFrameRate("runtimeVideoFrameRate"),
    BandwidthEstimateBps("bandwidthEstimateBps"),
    RecentVideoRenderP95Ms("recentVideoRenderP95Ms"),
    RecentPresentedFrameRate("recentPresentedFrameRate"),
    PresentationGapCount("presentationGapCount"),
    BufferPolicy("bufferPolicy"),
    LowRamDevice("lowRamDevice"),
    TargetBufferBytes("targetBufferBytes"),
    AllocatedBufferBytes("allocatedBufferBytes"),
    PeakAllocatedBufferBytes("peakAllocatedBufferBytes"),
    BufferedAheadMs("bufferedAheadMs"),
    MinBufferedAheadMs("minBufferedAheadMs"),
    MaxBufferedAheadMs("maxBufferedAheadMs"),
    LibVlcCachePercent("libVlcCachePercent"),
    TvDisplayResult("tvDisplayResult"),
    TvDisplayTier("tvDisplayTier"),
    TvDisplayActiveWidthPx("tvDisplayActiveWidthPx"),
    TvDisplayActiveHeightPx("tvDisplayActiveHeightPx"),
    TvDisplayActiveRefreshMilliHz("tvDisplayActiveRefreshMilliHz"),
    TvDisplayRequestedWidthPx("tvDisplayRequestedWidthPx"),
    TvDisplayRequestedHeightPx("tvDisplayRequestedHeightPx"),
    TvDisplayRequestedRefreshMilliHz("tvDisplayRequestedRefreshMilliHz"),
    TvDisplaySwitchDurationMs("tvDisplaySwitchDurationMs"),
    NativePrepareToFirstFrameMs("nativePrepareToFirstFrameMs"),
    LaunchToFirstFrameMs("launchToFirstFrameMs"),
    RebufferCount("rebufferCount"),
    TotalRebufferMs("totalRebufferMs"),
    MaxRebufferMs("maxRebufferMs"),
    AudioUnderrunCount("audioUnderrunCount"),
    MaxAudioFeedGapMs("maxAudioFeedGapMs"),
    HealthSignal("healthSignal"),
    HealthThresholdClass("healthThresholdClass"),
    HealthDurationMs("healthDurationMs"),
    HealthCount("healthCount"),
    HealthFirstPlayingObserved("healthFirstPlayingObserved"),
    HealthBufferingDurationMs("healthBufferingDurationMs"),
    HealthBufferingIntervalCount("healthBufferingIntervalCount"),
    HealthStallCount("healthStallCount"),
    HealthDroppedFrameDurationMs("healthDroppedFrameDurationMs"),
    HealthDroppedFrameSampleCount("healthDroppedFrameSampleCount"),
    HealthPostStartGuidanceShown("healthPostStartGuidanceShown"),
    HealthNoVideoOutputGuidanceShown("healthNoVideoOutputGuidanceShown"),
    HealthGuidancePolicy("healthGuidancePolicy"),
    HealthGuidancePublishable("healthGuidancePublishable"),
    HealthBufferingIntervalOpenedSinceEvidenceRestart("healthBufferingIntervalOpenedSinceEvidenceRestart"),
    BackendDroppedVideoFrames("backendDroppedVideoFrames"),
    BackendDroppedVideoFramesPerSecond("backendDroppedVideoFramesPerSecond"),
    DecoderDroppedVideoFrames("decoderDroppedVideoFrames"),
    OutputDroppedVideoFrames("outputDroppedVideoFrames"),
    HealthEmittedSignalCount("healthEmittedSignalCount"),
    EffectiveTranscodeBudgetBps("effectiveTranscodeBudgetBps"),
    QualityPolicyMode("qualityPolicyMode"),
    QualityPolicyOrigin("qualityPolicyOrigin"),
    BitrateConstraint("bitrateConstraint"),
    RecoveryIntent("recoveryIntent"),
    AutoRecoveryTrigger("autoRecoveryTrigger"),
    RecoveryDecision("recoveryDecision"),
    RecoveryPromptReason("recoveryPromptReason"),
    RecoveryCompatibilityAttempted("recoveryCompatibilityAttempted"),
    RecoveryQualityAttempted("recoveryQualityAttempted"),
    RecoveryBudgetExhausted("recoveryBudgetExhausted"),
    RecoveryFromQualityBudgetBps("recoveryFromQualityBudgetBps"),
    RecoveryToQualityBudgetBps("recoveryToQualityBudgetBps"),
    FirstVideoOutputAvailable("firstVideoOutputAvailable"),
    FirstVideoOutputObserved("firstVideoOutputObserved"),
    FirstVideoOutputEvidence("firstVideoOutputEvidence"),
    PersistenceTarget("persistenceTarget"),
    PersistenceResult("persistenceResult"),
    ReportingOperation("reportingOperation"),
    ReportingResult("reportingResult"),
}

internal val playbackDiagnosticFieldNames: Set<String> =
    PlaybackDiagnosticField.entries
        .map(PlaybackDiagnosticField::wireName)
        .toSet()

private fun MutableList<String>.appendDiagnosticField(
    field: PlaybackDiagnosticField,
    value: Any,
) {
    add("${field.wireName}=$value")
}

private fun DiagnosticReason.diagnosticName(): String =
    when (this) {
        is Media3AudioDecoderReason -> name
        is PlaybackCompletionReadinessReason -> name
        is PlaybackPreflightReason -> name
        is SubtitleActivationFailureReason -> diagnosticValue
        is UnusableVideoCodecReason -> name
    }

private fun PlaybackQualityCapOrigin.diagnosticName(): String =
    when (this) {
        PlaybackQualityCapOrigin.ExplicitSessionChoice -> "ExplicitSessionChoice"
        PlaybackQualityCapOrigin.SettingsDefault -> "SettingsDefault"
        PlaybackQualityCapOrigin.AutoSessionRecovery -> "AutoSessionRecovery"
    }

private fun safeTranscodeReason(reason: String): String = reason.takeIf(knownTranscodeReasons::contains) ?: "unrecognized"

private fun String.safeBackendConfigurationKey(): String = takeIf(knownBackendConfigurationKeys::contains) ?: "unrecognized"

private fun String.safeSourceVideoRangeType(): String {
    val normalized =
        trim()
            .lowercase()
            .replace("_", "")
            .replace("-", "")
    return knownSourceVideoRangeTypesByNormalizedValue[normalized] ?: "unrecognized"
}

private fun String.safeVideoDecoderCategory(): String {
    val normalized = lowercase()
    return when {
        "videotoolbox" in normalized -> "videotoolbox"
        "mediacodec" in normalized || normalized.startsWith("c2.") -> "android-codec"
        "libvlc" in normalized -> "libvlc"
        "ffmpeg" in normalized || "lavc" in normalized -> "ffmpeg"
        "vaapi" in normalized -> "vaapi"
        "vdpau" in normalized -> "vdpau"
        "nvdec" in normalized || "cuda" in normalized -> "nvdec"
        "d3d11va" in normalized -> "d3d11va"
        "dxva2" in normalized -> "dxva2"
        "vulkan" in normalized -> "vulkan"
        else -> "unrecognized"
    }
}

private fun PlaybackDiagnosticEvent.diagnosticName(): String =
    when (this) {
        PlaybackDiagnosticEvent.QualityCap -> "quality-cap"
        PlaybackDiagnosticEvent.ResolutionCap -> "resolution-cap"
        PlaybackDiagnosticEvent.CodecDropped -> "codec-dropped"
        PlaybackDiagnosticEvent.PerformanceSummary -> "performance-summary"
        PlaybackDiagnosticEvent.HealthSignal -> "health-signal"
        PlaybackDiagnosticEvent.HealthSummary -> "health-summary"
        PlaybackDiagnosticEvent.TerminalError -> "terminal-error"
        PlaybackDiagnosticEvent.BackendSelection -> "backend-selection"
        PlaybackDiagnosticEvent.BackendReadiness -> "backend-readiness"
        PlaybackDiagnosticEvent.RecoveryDecision -> "recovery-decision"
        PlaybackDiagnosticEvent.VideoOutput -> "video-output"
        PlaybackDiagnosticEvent.PrepareRequested -> "prepare-requested"
        PlaybackDiagnosticEvent.PrepareDispatched -> "prepare-dispatched"
        PlaybackDiagnosticEvent.BackendConstruction -> "backend-construction"
        PlaybackDiagnosticEvent.NativeLifecycle -> "native-lifecycle"
        PlaybackDiagnosticEvent.TrackState -> "track-state"
        PlaybackDiagnosticEvent.Reporting -> "reporting"
        PlaybackDiagnosticEvent.TvDisplay -> "tv-display"
        else -> name.lowercase()
    }

private fun PlaybackBackendAbi.diagnosticName(): String =
    when (this) {
        PlaybackBackendAbi.Arm64V8a -> "arm64-v8a"
        PlaybackBackendAbi.ArmeabiV7a -> "armeabi-v7a"
        PlaybackBackendAbi.X86_64 -> "x86_64"
    }

private fun PlaybackDiagnosticDisplayResult.diagnosticName(): String =
    if (this == PlaybackDiagnosticDisplayResult.Unrecognized) "unrecognized" else name

private fun PlaybackDiagnosticDisplayTier.diagnosticName(): String =
    if (this == PlaybackDiagnosticDisplayTier.Unrecognized) "unrecognized" else name

fun Throwable.playbackExceptionType(): String = safeDiagnosticType()

fun PlaybackInfoRequestPolicy.diagnosticClass(): PlaybackRequestPolicyClass =
    when {
        forceEncodeSubtitle != null -> PlaybackRequestPolicyClass.ForceEncodeSubtitle
        !enableDirectStream -> PlaybackRequestPolicyClass.DirectStreamDisabled
        !enableDirectPlay -> PlaybackRequestPolicyClass.DirectPlayDisabled
        else -> PlaybackRequestPolicyClass.Default
    }

private fun String?.safeExceptionType(): String? =
    this
        ?.takeIf { value ->
            value.length in 1..80 &&
                (value.first().isLetter() || value.first() == '_') &&
                value.all { character -> character.isLetterOrDigit() || character == '_' || character == '.' }
        } ?: "Unknown"

private fun Double.boundedDiagnosticDouble(maximum: Double): Double? =
    takeIf { value -> value.isFinite() && value >= 0.0 }
        ?.coerceAtMost(maximum)

private val knownTranscodeReasons =
    setOf(
        "AnamorphicVideoNotSupported",
        "AudioBitrateNotSupported",
        "AudioChannelsNotSupported",
        "AudioCodecNotSupported",
        "AudioIsExternal",
        "AudioProfileNotSupported",
        "AudioSampleRateNotSupported",
        "ContainerBitrateExceedsLimit",
        "ContainerNotSupported",
        "DirectPlayError",
        "InterlacedVideoNotSupported",
        "RefFramesNotSupported",
        "SecondaryAudioNotSupported",
        "SecondarySubtitleNotSupported",
        "SubtitleCodecNotSupported",
        "UnknownAudioStreamInfo",
        "UnknownVideoStreamInfo",
        "VideoBitDepthNotSupported",
        "VideoBitrateNotSupported",
        "VideoCodecNotSupported",
        "VideoCodecTagNotSupported",
        "VideoFramerateNotSupported",
        "VideoLevelNotSupported",
        "VideoProfileNotSupported",
        "VideoRangeTypeNotSupported",
        "VideoResolutionNotSupported",
    )

private val knownSourceVideoRangeTypesByNormalizedValue =
    setOf(
        "SDR",
        "HDR10",
        "HDR10Plus",
        "HLG",
        "DOVI",
        "DOVIWithSDR",
        "DOVIWithHDR10",
        "DOVIWithHDR10Plus",
        "DOVIWithHLG",
        "DOVIWithEL",
        "DOVIWithELHDR10Plus",
    ).associateBy { value -> value.lowercase() }

private val knownBackendConfigurationKeys =
    setOf(
        "config",
        "load-auto-profiles",
        "scripts",
        "osc",
        "input-default-bindings",
        "input-vo-keyboard",
        "ytdl",
        "cookies",
        "autoload-files",
        "vo",
        "gpu-context",
        "opengl-es",
        "hwdec",
        "hwdec-codecs",
        "tls-verify",
        "tls-ca-file",
        "cache",
        "cache-secs",
        "cache-pause-initial",
        "cache-pause",
        "demuxer-max-bytes",
        "demuxer-max-back-bytes",
        "idle",
        "keep-open",
        "force-window",
        "audio-client-name",
        "alang",
        "slang",
        "sid",
        "aid",
        "sub-auto",
        "audio-file-auto",
        "sub-file-paths",
        "profile",
        "log-file",
        "mpv-log-level",
    )

private const val MAX_HEALTH_DURATION_MS = 60_000L
private const val MAX_HEALTH_COUNT = 60
private const val MAX_DIAGNOSTIC_SEQUENCE = 1_000_000L
private const val MAX_DIAGNOSTIC_POSITION_MS = 604_800_000L
private const val MAX_DIAGNOSTIC_BITRATE_BPS = 1_000_000_000L
private const val MAX_DROPPED_FRAME_COUNT = 1_000_000_000L
private const val MAX_DROPPED_FRAME_RATE = 100_000.0
private const val MAX_DIAGNOSTIC_VIDEO_DIMENSION = 32_768
private const val MAX_DIAGNOSTIC_REFRESH_MILLIHZ = 1_000_000
private const val MAX_DIAGNOSTIC_FRAME_RATE = 1_000.0
private const val MAX_DIAGNOSTIC_BIT_DEPTH = 64
private const val MINIMUM_DECODER_FRAME_THREADS = 1
private const val MAXIMUM_DECODER_FRAME_THREADS = 2
