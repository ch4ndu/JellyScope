// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.jellyscope.core.playback

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.DEFAULT_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.EmbeddedTrackKind
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.NativeTrackCandidate
import com.jellyscope.core.domain.playback.NativeTrackMappingReason
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.NativeTrackResolution
import com.jellyscope.core.domain.playback.OfflinePrepareResult
import com.jellyscope.core.domain.playback.PlaybackContentTimeline
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticOperation
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlaybackTransitionObservation
import com.jellyscope.core.domain.playback.PlaybackTransitionOutcome
import com.jellyscope.core.domain.playback.PlaybackTransitionRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackTransitionRuntimeKind
import com.jellyscope.core.domain.playback.PlaybackTransitionRuntimeState
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerOperation
import com.jellyscope.core.domain.playback.SubtitleActivationFailureReason
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackHealthNoVideoOutputThresholdMs
import com.jellyscope.core.domain.playback.resolveEmbeddedTrack
import com.jellyscope.core.domain.playback.vlcSlaveSubtitleUrl
import com.jellyscope.core.security.CredentialOriginGuard
import com.jellyscope.core.security.JELLYFIN_API_KEY_QUERY_NAME
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import io.ktor.http.URLBuilder
import io.ktor.http.parseUrl
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.videolan.vlckit.VLCMedia
import org.videolan.vlckit.VLCMediaDelegateProtocol
import org.videolan.vlckit.VLCMediaFetchNetwork
import org.videolan.vlckit.VLCMediaParse
import org.videolan.vlckit.VLCMediaParsedStatusFailed
import org.videolan.vlckit.VLCMediaParsedStatusTimeout
import org.videolan.vlckit.VLCMediaParser
import org.videolan.vlckit.VLCMediaPlaybackSlaveTypeSubtitle
import org.videolan.vlckit.VLCMediaPlayer
import org.videolan.vlckit.VLCMediaPlayerDelegateProtocol
import org.videolan.vlckit.VLCMediaPlayerState
import org.videolan.vlckit.VLCMediaPlayerTrack
import org.videolan.vlckit.VLCMediaTrackType
import org.videolan.vlckit.VLCMediaTrackTypeAudio
import org.videolan.vlckit.VLCTime
import org.videolan.vlckit.audioTracks
import org.videolan.vlckit.deselectAllTextTracks
import org.videolan.vlckit.selectTextTracks
import org.videolan.vlckit.selectTrackAtIndex
import org.videolan.vlckit.textTracks
import platform.Foundation.NSNotification
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.atomics.AtomicLong
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * VLCKit-backed iOS [PlayerController]. The native delegate is intentionally
 * held strongly because VLCKit's delegate properties are weak. Every
 * callback carries the prepared player/media identity and generation so a
 * late callback from a replaced native session cannot mutate shared state.
 */
class VlcKitPlayerController(
    private val session: Session,
    private val stateScope: CoroutineScope,
    private val localSubtitleFileStore: LocalSubtitleFileStore? = null,
    private val diagnosticPlatform: PlaybackDiagnosticPlatform = PlaybackDiagnosticPlatform.Ios,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : PlayerController,
    IosPlaybackSurfaceProvider,
    IosPictureInPictureSurfaceProvider,
    VlcKitPictureInPictureTransport,
    PlaybackAudioSessionHandler {
    private var player: VLCMediaPlayer? = VLCMediaPlayer()
    private var currentMedia: VLCMedia? = null
    private var surfaceView: VlcKitPictureInPictureSurface? = null
    private var nativeDelegate: VlcKitDelegate? = null
    private val generation = AtomicLong(0L)
    private val credentialOriginGuard = CredentialOriginGuard(session.serverUrl)
    private val offlineLeaseHolder = OfflineArtifactLeaseHolder()
    private var offlinePath: String? = null
    private var offlineArtifactResolver: OfflineArtifactResolver? = null
    private var offlineSidecarPath: String? = null

    /** Platform DI seam; the public controller constructor remains remote-compatible. */
    internal fun setOfflineArtifactResolver(resolver: OfflineArtifactResolver) {
        offlineArtifactResolver = resolver
    }

    private val _playbackState =
        MutableStateFlow(
            PlaybackState(
                status = PlaybackStatus.Idle,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
            ),
        )
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _runtimeDiagnostics = MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = _runtimeDiagnostics.asStateFlow()
    private val videoOutputObservationsChannel = Channel<VideoOutputObservation>(Channel.BUFFERED)
    override val videoOutputObservations: Flow<VideoOutputObservation> =
        videoOutputObservationsChannel.receiveAsFlow()
    private val playbackTransitionObservationsChannel = Channel<PlaybackTransitionObservation>(Channel.BUFFERED)
    override val playbackTransitionObservations: Flow<PlaybackTransitionObservation> =
        playbackTransitionObservationsChannel.receiveAsFlow()

    override val platformPlayer: Any?
        get() = player
    override val activeBackend: PlayerBackend = PlayerBackend.VlcKit

    override fun createSurfaceView(): UIView =
        getOrCreateSurfaceView().also { view ->
            player?.drawable = view
        }

    override fun configurePictureInPicture(
        enabled: Boolean,
        callbacks: IosPictureInPictureSurfaceCallbacks,
    ) {
        if (released) return
        val surface = getOrCreateSurfaceView()
        surface.configure(enabled, callbacks)
        player?.drawable = surface
    }

    private fun getOrCreateSurfaceView(): VlcKitPictureInPictureSurface =
        surfaceView
            ?: VlcKitPictureInPictureSurface(this).also { view ->
                view.bindNativeSession(generation.load())
                surfaceView = view
            }

    override fun clearPictureInPictureCallbacks() {
        surfaceView?.clearCallbacks()
    }

    override fun requestPictureInPictureStart(): IosPictureInPictureStartAdmission =
        surfaceView?.requestPictureInPictureStart()
            ?: IosPictureInPictureStartAdmission.Rejected

    override fun requestPictureInPictureStop(sourceIdentity: Long) {
        surfaceView?.requestPictureInPictureStop(sourceIdentity)
    }

    override val transcodeSeekRestartsStream: Boolean = false

    override val playbackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities(
            hasReliableBufferingTransitions = true,
            hasDroppedFrameMeasurements = false,
            // Device evidence shows VLCKit's public displayedPictures counter
            // can remain zero for an actively presented HLS transcode. Keep it
            // as best-effort transition evidence, but never advertise it to the
            // shared health layer as a universally reliable first-output fact.
            hasReliableFirstVideoOutput = false,
        )
    override val videoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.Unsupported

    // VLCKit applies subtitle style through media open-time input options,
    // so a mid-session style change is inert.
    override val appliesSubtitleStyle: Boolean = false

    private var released = false
    private var lastPlan: PlaybackPlan? = null
    private var requestedPlaybackSpeed = DEFAULT_PLAYBACK_SPEED
    private var requestedSubtitleStyle = SubtitleStyle()
    private var playWhenReady = false
    private val pictureInPictureSeekable = AtomicLong(0L)
    private val pictureInPictureNativeTimeMs = AtomicLong(0L)

    // Play intent captured when a system audio interruption begins, honored
    // together with iOS's shouldResume hint when the interruption ends.
    private val interruptionIntent = PlaybackInterruptionIntent()
    private var parsed = false
    private var runtimeDiagnosticsTick = 0

    // VLCKit statistics are cumulative per media session. Each transition uses
    // the latest readable count as its baseline and requires two separately
    // observed positive advances; a counter reset cannot prove fresh output.
    private var lastDisplayedPictures: Long? = null
    private val firstVideoOutputObservedGeneration = AtomicLong(NO_VIDEO_OUTPUT_GENERATION)
    private val playbackTransition = VlcKitPlaybackTransition()
    private var playbackTransitionSequence = 0L
    private var playbackTransitionTimeoutJob: Job? = null

    // Deliberately loose latch: true once the native player reported playing or a
    // non-zero clock. It feeds ONLY failure classification (a failed/timed-out
    // network parse before any native output is a connectivity failure; anything
    // after stays the conservative Unknown). The terminal end-of-stream decision
    // uses the stricter shared evidence below, never this field.
    private var nativePlaybackObserved = false

    // End-of-stream evidence for the shared terminal kernel. VLC's clock is already
    // dead at Stopped, and it stays in the Stopped state afterwards, so the decision is
    // made from per-prepare facts (progress latch, seek exclusion, one-shot
    // rejection log) rather than a live native read. See `resolveVlcTerminalStatus`.
    private val endOfStream = VlcEndOfStreamEvidence()

    // VLCKit exposes the terminal condition as a sticky STATE; the kernel decides
    // one-shot EDGES. This converts one to the other.
    private val terminalLatch = VlcTerminalEventLatch()
    private var startPositionMs = 0L
    private var pendingInitialSeekMs: Long? = null

    private val pictureInPictureSeekGate = VlcSingleFlightCompletionGate()
    private var pictureInPictureSeekCompletionJob: Job? = null
    private var pendingEmbeddedAudioSelection: EmbeddedAudioSelection? = null
    private var pendingEmbeddedSubtitleSelection: EmbeddedSubtitleSelection? = null
    private var expectedSubtitleTarget: SubtitleActivationTarget? = null
    private var subtitleSelectionSequence = 0L
    private var externalSubtitleTrackIds = emptySet<String>()
    private var subtitleTrackIdsBeforeExternal = emptySet<String>()
    private var externalSubtitleAttachAttempted = false
    private var buffering = false
    private var nativeBufferingProgress: Float? = null
    private val trackResolutionDiagnostics = TrackResolutionDiagnosticGate()

    private val subtitleActivationConfirmation =
        SubtitleActivationConfirmation(
            scope = stateScope,
            currentState = { _playbackState.value.subtitleActivation },
            publish = { activation -> emitPlaybackState { current -> current.copy(subtitleActivation = activation) } },
            platform = diagnosticPlatform,
        )
    private val audioActivationConfirmation =
        AudioActivationConfirmation(
            scope = stateScope,
            platform = diagnosticPlatform,
            currentState = { _playbackState.value.audioActivation },
            publish = { activation -> emitPlaybackState { current -> current.copy(audioActivation = activation) } },
            onActivated = ::releasePlayIntentAfterAudioActivation,
        )

    init {
        if (player == null) {
            logDiagnostic(PlaybackDiagnosticStage.Prepare, PlaybackDiagnosticEvent.Failed)
        }
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): OfflinePrepareResult {
        if (released) return OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit))
        val resolver =
            offlineArtifactResolver
                ?: return OfflinePrepareResult.Unavailable(PlaybackError.OfflineArtifactUnavailable)
        stop()
        val lease =
            when (val resolution = resolver.acquireForOfflinePlan(plan, session.accountIdentity())) {
                is OfflineArtifactResolution.Available -> resolution.lease
                is OfflineArtifactResolution.Unavailable ->
                    return OfflinePrepareResult.Unavailable(PlaybackError.OfflineArtifactUnavailable)
            }
        offlineLeaseHolder.replace(lease)
        offlinePath = lease.mainPathForController
        offlineSidecarPath = lease.sidecarPathsForController.firstOrNull()
        return try {
            prepare(plan)
            if (_playbackState.value.status == PlaybackStatus.Failed) {
                OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit))
            } else {
                OfflinePrepareResult.Started
            }
        } catch (_: Throwable) {
            offlinePath = null
            offlineSidecarPath = null
            val failedLease = offlineLeaseHolder.detach()
            detachNativeSession { failedLease?.release() }
            OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit))
        }
    }

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        if (released) return warnReleased(PlayerOperation.Prepare)

        // The app must own the audio session on the VLC path too: VLCKit's
        // internal handling does not guarantee the .playback category (mute
        // switch / lock behavior), and this registers the interruption handler.
        PlaybackAudioSession.activate(owner = this, platform = diagnosticPlatform)
        lastPlan = plan
        requestedPlaybackSpeed = plan.playbackSpeed
        requestedSubtitleStyle = plan.subtitleStyle
        startPositionMs =
            plan.clampedStartPositionMs().let { positionMs ->
                resolvePlaybackDurationMs(plan.contentTimeline, null)?.let(positionMs::coerceAtMost) ?: positionMs
            }
        playWhenReady = false
        // Interruption intent is item-session-scoped: an interruption ending
        // after a queue switch must not resume the replacement item.
        interruptionIntent.reset()
        endOfStream.onPrepare(startPositionMs)
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        expectedSubtitleTarget = plan.subtitleActivationTarget
        subtitleSelectionSequence += 1L
        externalSubtitleTrackIds = emptySet()
        subtitleTrackIdsBeforeExternal = emptySet()
        externalSubtitleAttachAttempted = false
        buffering = false
        nativeBufferingProgress = null
        parsed = false
        nativePlaybackObserved = false
        trackResolutionDiagnostics.reset()
        runtimeDiagnosticsTick = 0
        audioActivationConfirmation.applyInitial(initialAudioActivationFor(plan))
        subtitleActivationConfirmation.beginForPlan(plan, subtitleAsset)
        emitPlaybackState { current ->
            current.copy(
                status = PlaybackStatus.Loading,
                positionMs = startPositionMs,
                durationMs = resolvePlaybackDurationMs(plan.contentTimeline, null),
                bufferedPositionMs = startPositionMs,
                playbackSpeed = requestedPlaybackSpeed,
                subtitleStyle = requestedSubtitleStyle,
                error = null,
                audioUnavailable = false,
            )
        }

        // Direct Offline -> remote replacement is valid even when the caller
        // does not go through prepareOffline(). Detach only the exact outgoing
        // lease and release it from the native teardown completion; otherwise
        // the old artifact remains held indefinitely (or is released while VLC
        // is still reading it).
        val replacedOfflineLease =
            if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
                offlineLeaseHolder.detach()
            } else {
                null
            }
        detachNativeSession(
            expectReplacement = true,
            onTeardown = { replacedOfflineLease?.release() },
        )
        val preparedGeneration = generation.load()
        resetVideoOutputBaseline()
        surfaceView?.bindNativeSession(preparedGeneration)
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY.copy(prepareEpoch = preparedGeneration)
        val nativePlayer = VLCMediaPlayer()
        player = nativePlayer
        surfaceView?.let { view -> nativePlayer.drawable = view }

        val streamUrl =
            if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                offlinePath ?: return failPlayback(PlaybackDiagnosticStage.Prepare)
            } else {
                guardedVlcUrl(plan.streamUrl) ?: return failPlayback(PlaybackDiagnosticStage.Prepare)
            }
        val mediaUrl =
            if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                NSURL.fileURLWithPath(streamUrl)
            } else {
                NSURL(string = streamUrl)
            }
        val media =
            VLCMedia.mediaWithURL(mediaUrl)
                ?: return failPlayback(PlaybackDiagnosticStage.Prepare)
        currentMedia = media
        val delegate = VlcKitDelegate(this, preparedGeneration, nativePlayer, media)
        nativeDelegate = delegate
        nativePlayer.delegate = delegate
        media.delegate = delegate
        applySubtitleStyle(media, requestedSubtitleStyle)
        // VLC ignores player.time set before playback starts, so a resume position
        // (Continue Watching, quality/track re-plans) cannot be applied here. Defer
        // it to a one-shot seek performed once the player is actually playing —
        // mirroring AppleAVPlayerController's seek-after-item-ready, using the same
        // content-timeline seek that works for both direct play and transcode.
        pendingInitialSeekMs = startPositionMs.takeIf { positionMs -> positionMs > 0L }
        nativePlayer.media = media
        // Attach the external subtitle slave right after setting the media and BEFORE
        // play(), matching the required VLCKit attachment order. Attaching
        // after play failed to register the slave over an HLS/transcode input; doing it
        // at media setup makes VLC open the media with the sidecar and select it.
        attachExternalSubtitleForSession(
            nativePlayer,
            media,
            subtitleAsset.takeUnless {
                plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline
            },
        )
        nativePlayer.pause()

        val parseResult =
            VLCMediaParser.sharedParser().queueMedia(
                media,
                VLCMediaParse or VLCMediaFetchNetwork,
            )
        if (parseResult < 0) {
            return failPlayback(PlaybackDiagnosticStage.Prepare, classifyVlcFailure())
        }
        updateStateFromPlayer(statusOverride = PlaybackStatus.Paused)
    }

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) {
        if (released) return warnReleased(PlayerOperation.SelectEmbeddedAudio)
        pendingEmbeddedAudioSelection = selection
        if ((_playbackState.value.audioActivation as? AudioActivationState.Pending)?.target != selection.target) {
            audioActivationConfirmation.begin(selection.target)
        }
        tryActivateAudio()
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        if (released) return warnReleased(PlayerOperation.SelectEmbeddedSubtitle)
        subtitleSelectionSequence += 1L
        pendingEmbeddedSubtitleSelection = selection
        if (selection == null) {
            expectedSubtitleTarget = null
            // A local-slave attach queued by this session's prepare is invalidated by
            // the selection sequence above before it can re-enforce the slave.
            player?.deselectAllTextTracks()
            subtitleActivationConfirmation.clear()
            return
        }
        expectedSubtitleTarget = selection.target
        subtitleActivationConfirmation.begin(selection.target)
        tryActivateSubtitle()
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return warnReleased(PlayerOperation.SetPlaybackSpeed)
        if (!speed.isFinite() || speed <= 0f) return
        requestedPlaybackSpeed = speed
        player?.rate = speed
        emitPlaybackState { current -> current.copy(playbackSpeed = speed) }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        if (released) return warnReleased(PlayerOperation.SetSubtitleStyle)
        requestedSubtitleStyle = style
        currentMedia?.let { media -> applySubtitleStyle(media, style) }
        emitPlaybackState { current -> current.copy(subtitleStyle = style) }
    }

    override fun play() {
        if (released) return warnReleased(PlayerOperation.Play)
        val shouldBeginTransition =
            !playWhenReady ||
                _playbackState.value.status !in setOf(PlaybackStatus.Playing, PlaybackStatus.Buffering)
        if (shouldBeginTransition) {
            // A paused PiP seek can still be waiting for VLCKit's seek-finished
            // callback. Play replaces that transition, so release the system
            // completion before starting the replacement.
            cancelPictureInPictureSeek()
            val targetPositionMs = pendingInitialSeekMs ?: _playbackState.value.positionMs
            beginPlaybackTransition(
                kind =
                    if (targetPositionMs > 0L) {
                        VlcKitPlaybackTransitionKind.Resume
                    } else {
                        VlcKitPlaybackTransitionKind.Prepare
                    },
                targetPositionMs = targetPositionMs,
                playIntent = true,
            )
        }
        playWhenReady = true
        // Unlike AVPlayer (which exposes track groups on a ready-but-paused item),
        // VLC only surfaces audio/subtitle tracks once playback has actually
        // started (typed track callbacks). Gating player.play() on the initial DirectPlay audio
        // mapping would therefore deadlock — no play means no tracks means no
        // mapping means no play. So VLC starts playback immediately and maps the
        // selected audio opportunistically as tracks arrive (see tryActivateAudio,
        // driven by typed track/parse callbacks); audioActivation still publishes
        // Pending -> Active. The initial-audio gate remains a no-op play blocker
        // here by design.
        player?.play()
        applyRequestedRate()
        updateStateFromPlayer()
    }

    override fun pause() {
        if (released) return warnReleased(PlayerOperation.Pause)
        playWhenReady = false
        buffering = false
        cancelPlaybackTransitionTimeout()
        val transitionUpdate = playbackTransition.pause(generation.load())
        if (transitionUpdate?.becameReady == true) {
            settlePlaybackTransitionReady()
        }
        player?.pause()
        updateStateFromPlayer(statusOverride = PlaybackStatus.Paused)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return warnReleased(PlayerOperation.SeekTo)
        val nativePlayer = player ?: return
        val media = currentMedia ?: return
        cancelPictureInPictureSeek()
        seekToOnOwnerLane(
            generation = generation.load(),
            positionMs = positionMs,
            nativePlayer = nativePlayer,
            media = media,
        )
    }

    private fun seekToOnOwnerLane(
        generation: Long,
        positionMs: Long,
        nativePlayer: VLCMediaPlayer,
        media: VLCMedia,
    ): Boolean {
        if (!isCurrentCallback(generation, nativePlayer, media)) return false
        val targetPositionMs =
            positionMs
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .let { target -> currentDurationMs(media.length.intValue.toLong())?.let(target::coerceAtMost) ?: target }
        // An explicit user seek supersedes any deferred initial resume seek.
        pendingInitialSeekMs = null
        // Publish the requested target until the native clock reaches it, and drop
        // the sample that resolves it: a seek-derived position is not proof of
        // playback progress (see VlcEndOfStreamEvidence.ignoreNextSample).
        if (
            !beginPlaybackTransition(
                kind = VlcKitPlaybackTransitionKind.Seek,
                targetPositionMs = targetPositionMs,
                playIntent = playWhenReady,
            )
        ) {
            return false
        }
        logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.SeekStarted)
        nativePlayer.time =
            VLCTime.timeWithInt(targetPositionMs.toInt())
        if (!isCurrentCallback(generation, nativePlayer, media)) return true
        logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.SeekCompleted)
        updateStateFromPlayer(statusOverride = if (playWhenReady) PlaybackStatus.Buffering else PlaybackStatus.Paused)
        return true
    }

    override fun currentPictureInPictureGeneration(): Long = generation.load()

    override fun hasPictureInPictureContent(generation: Long): Boolean =
        !released &&
            generation == this.generation.load() &&
            firstVideoOutputObservedGeneration.load() == generation

    override fun isPictureInPicturePlaying(generation: Long): Boolean {
        val status = _playbackState.value.status
        return !released &&
            generation == this.generation.load() &&
            (status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering)
    }

    override fun isPictureInPictureSeekable(generation: Long): Boolean =
        !released && generation == this.generation.load() && pictureInPictureSeekable.load() == 1L

    override fun pictureInPictureDurationMs(generation: Long): Long? =
        if (!released && generation == this.generation.load()) {
            _playbackState.value.durationMs
        } else {
            null
        }

    override fun pictureInPictureTimeMs(generation: Long): Long =
        if (!released && generation == this.generation.load()) {
            pictureInPictureNativeTimeMs.load()
        } else {
            0L
        }

    override fun playFromPictureInPicture(generation: Long) {
        dispatch_async(dispatch_get_main_queue()) {
            val callbackPlayer = player
            val callbackMedia = currentMedia
            if (
                callbackPlayer == null ||
                callbackMedia == null ||
                !isCurrentCallback(generation, callbackPlayer, callbackMedia)
            ) {
                return@dispatch_async
            }
            play()
        }
    }

    override fun pauseFromPictureInPicture(generation: Long) {
        dispatch_async(dispatch_get_main_queue()) {
            val callbackPlayer = player
            val callbackMedia = currentMedia
            if (
                callbackPlayer == null ||
                callbackMedia == null ||
                !isCurrentCallback(generation, callbackPlayer, callbackMedia)
            ) {
                return@dispatch_async
            }
            pause()
        }
    }

    override fun reportPictureInPictureUnavailable(generation: Long) {
        if (generation != this.generation.load()) return
        logDiagnostic(PlaybackDiagnosticStage.PictureInPicture, PlaybackDiagnosticEvent.Timeout)
    }

    override fun seekByFromPictureInPicture(
        generation: Long,
        offsetMs: Long,
        completion: () -> Unit,
    ) {
        dispatch_async(dispatch_get_main_queue()) {
            val callbackPlayer = player
            val callbackMedia = currentMedia
            if (
                callbackPlayer == null ||
                callbackMedia == null ||
                !isCurrentCallback(generation, callbackPlayer, callbackMedia)
            ) {
                completion()
                return@dispatch_async
            }
            seekByFromPictureInPictureOnOwnerLane(
                generation = generation,
                offsetMs = offsetMs,
                completion = completion,
                nativePlayer = callbackPlayer,
                media = callbackMedia,
            )
        }
    }

    private fun seekByFromPictureInPictureOnOwnerLane(
        generation: Long,
        offsetMs: Long,
        completion: () -> Unit,
        nativePlayer: VLCMediaPlayer,
        media: VLCMedia,
    ) {
        val durationMs = currentDurationMs(media.length.intValue.toLong())
        if (released ||
            !isCurrentCallback(generation, nativePlayer, media) ||
            !nativePlayer.seekable
        ) {
            completion()
            return
        }
        val upperBoundMs = durationMs?.coerceAtMost(Int.MAX_VALUE.toLong()) ?: Int.MAX_VALUE.toLong()
        val nativePositionMs = pictureInPictureNativeTimeMs.load().coerceIn(0L, upperBoundMs)
        val adjustedOffsetMs = offsetMs.coerceIn(-nativePositionMs, upperBoundMs - nativePositionMs)
        val targetPositionMs = nativePositionMs + adjustedOffsetMs
        if (adjustedOffsetMs == 0L) {
            completion()
            return
        }

        val token = pictureInPictureSeekGate.admit(generation, completion) ?: return
        pendingInitialSeekMs = null
        if (!beginPlaybackTransition(
                kind = VlcKitPlaybackTransitionKind.Seek,
                targetPositionMs = targetPositionMs,
                playIntent = playWhenReady,
            )
        ) {
            pictureInPictureSeekGate.complete(token, generation)
            logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Rejected)
            return
        }
        logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.SeekStarted)

        var nativeResultKnown = false
        var completionArrivedSynchronously = false
        val accepted =
            nativePlayer.jumpWithOffset(adjustedOffsetMs.toInt()) {
                if (nativeResultKnown) {
                    completePictureInPictureSeek(token, generation, nativePlayer, media)
                } else {
                    completionArrivedSynchronously = true
                }
            }
        nativeResultKnown = true
        if (!accepted) {
            clearPlaybackTransition()
            logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Rejected)
            return
        }

        armPictureInPictureSeekCompletionDeadline(token, generation)
        updateStateFromPlayer(statusOverride = if (playWhenReady) PlaybackStatus.Buffering else PlaybackStatus.Paused)
        if (completionArrivedSynchronously) {
            completePictureInPictureSeek(token, generation, nativePlayer, media)
        }
    }

    private fun cancelPictureInPictureSeek() {
        pictureInPictureSeekCompletionJob?.cancel()
        pictureInPictureSeekCompletionJob = null
        pictureInPictureSeekGate.cancel()
    }

    private fun armPictureInPictureSeekCompletionDeadline(
        token: Long,
        seekGeneration: Long,
    ) {
        pictureInPictureSeekCompletionJob?.cancel()
        pictureInPictureSeekCompletionJob =
            stateScope.launch {
                delay(playbackHealthNoVideoOutputThresholdMs(activeBackend))
                dispatch_async(dispatch_get_main_queue()) {
                    if (pictureInPictureSeekGate.complete(token, seekGeneration)) {
                        pictureInPictureSeekCompletionJob = null
                        logDiagnostic(PlaybackDiagnosticStage.PictureInPicture, PlaybackDiagnosticEvent.Timeout)
                    }
                }
            }
    }

    private fun completePictureInPictureSeek(
        token: Long,
        seekGeneration: Long,
        nativePlayer: VLCMediaPlayer,
        media: VLCMedia,
    ) {
        val completed =
            pictureInPictureSeekGate.complete(token, seekGeneration) {
                if (isCurrentCallback(seekGeneration, nativePlayer, media)) {
                    val durationMs = currentDurationMs(media.length.intValue.toLong())
                    val nativeTimeMs =
                        nativePlayer.time.intValue
                            .toLong()
                            .coerceAtLeast(0L)
                            .let { timeMs -> durationMs?.let(timeMs::coerceAtMost) ?: timeMs }
                    pictureInPictureNativeTimeMs.store(nativeTimeMs)
                }
            }
        if (!completed) return
        pictureInPictureSeekCompletionJob?.cancel()
        pictureInPictureSeekCompletionJob = null
        logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.SeekCompleted)
        surfaceView?.invalidatePlaybackState()
    }

    override fun stop() {
        if (released) return warnReleased(PlayerOperation.Stop)
        val detachedOfflineLease = offlineLeaseHolder.detach()
        playWhenReady = false
        interruptionIntent.reset()
        pendingInitialSeekMs = null
        endOfStream.onStop()
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        expectedSubtitleTarget = null
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        detachNativeSession { detachedOfflineLease?.release() }
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        emitPlaybackState { current ->
            current.copy(
                status = PlaybackStatus.Idle,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
                error = null,
                audioActivation = AudioActivationState.None,
                subtitleActivation = SubtitleActivationState.None,
                audioUnavailable = false,
            )
        }
        offlinePath = null
        offlineSidecarPath = null
    }

    override fun retry() {
        if (released) return warnReleased(PlayerOperation.Retry)
        val plan = lastPlan ?: return
        val audioSelection = pendingEmbeddedAudioSelection
        val subtitleSelection = pendingEmbeddedSubtitleSelection
        val selectionDecision =
            retrySelectionDecision(
                audioSelection = audioSelection,
                subtitleIntent =
                    subtitleSelection
                        ?.let(RetrySubtitleIntent::Selection)
                        ?: RetrySubtitleIntent.Unspecified,
                planSubtitleTarget = plan.subtitleActivationTarget,
            )
        val shouldPlay = playWhenReady
        prepare(
            plan,
            plan.subtitleAsset.takeUnless {
                plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline
            },
        )
        selectionDecision.audioSelection?.let(::selectEmbeddedAudio)
        when (selectionDecision) {
            is RetrySelectionDecision.RestorePlan -> Unit
            is RetrySelectionDecision.ReassertSubtitleOff -> selectEmbeddedSubtitle(null)
            is RetrySelectionDecision.SelectSubtitle -> selectEmbeddedSubtitle(selectionDecision.selection)
        }
        if (shouldPlay) {
            val retryGeneration = generation.load()
            val retryPlayer = player
            val retryMedia = currentMedia
            // Let the ViewModel bind this prepare epoch to its health session
            // before the transition's Started observation can be emitted.
            dispatch_async(dispatch_get_main_queue()) {
                if (
                    retryPlayer == null ||
                    retryMedia == null ||
                    !isCurrentCallback(retryGeneration, retryPlayer, retryMedia)
                ) {
                    return@dispatch_async
                }
                play()
            }
        }
    }

    override fun release() {
        if (released) return
        val detachedOfflineLease = offlineLeaseHolder.detach()
        released = true
        playWhenReady = false
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        expectedSubtitleTarget = null
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        detachNativeSession { detachedOfflineLease?.release() }
        surfaceView?.releasePictureInPicture()
        videoOutputObservationsChannel.close()
        playbackTransitionObservationsChannel.close()
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        PlaybackAudioSession.release(owner = this)
        offlinePath = null
        offlineSidecarPath = null
    }

    override fun onAudioSessionInterruptionBegan() {
        if (released) return
        interruptionIntent.onInterruptionBegan(playWhenReady)
        // pause() clears playWhenReady so nothing re-asserts play mid-call.
        pause()
    }

    override fun onAudioSessionInterruptionEnded(shouldResume: Boolean) {
        if (released) return
        val resume = interruptionIntent.onInterruptionEnded(shouldResume)
        if (resume) play()
    }

    private fun attachExternalSubtitle(
        nativePlayer: VLCMediaPlayer,
        subtitleAsset: SubtitleAsset?,
        callbackGeneration: Long,
        callbackMedia: VLCMedia,
        callbackSubtitleSelectionSequence: Long,
        trustedSidecarPath: String? = null,
    ): Boolean? {
        if (trustedSidecarPath != null) {
            return addExternalSubtitleUrl(nativePlayer, NSURL.fileURLWithPath(trustedSidecarPath))
        }
        val subtitle = subtitleAsset ?: return false
        when (subtitle) {
            is SubtitleAsset.JellyfinRemote -> {
                val subtitleUrl =
                    guardedVlcUrl(vlcSlaveSubtitleUrl(subtitle.url))?.let { value -> NSURL(string = value) }
                        ?: return false.also { logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected) }
                return addExternalSubtitleUrl(nativePlayer, subtitleUrl)
            }
            is SubtitleAsset.LocalFile -> {
                val fileStore = localSubtitleFileStore ?: return false
                stateScope.launch(ioDispatcher) {
                    val path =
                        runCatching { fileStore.resolvePath(subtitle.fileId) }
                            .getOrNull()
                    // Only the path resolve belongs off-main. addPlaybackSlave, the
                    // external-track bookkeeping and the activation/state update below
                    // otherwise run exclusively on main via the VLCKit delegate
                    // callbacks, so resume there. The generation/identity guard is
                    // re-checked after the hop: a prepare/stop/release can land while
                    // this block is queued.
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!isCurrentCallback(callbackGeneration, nativePlayer, callbackMedia) ||
                            subtitleSelectionSequence != callbackSubtitleSelectionSequence
                        ) {
                            return@dispatch_async
                        }
                        val attached =
                            path?.let { value -> addExternalSubtitleUrl(nativePlayer, NSURL.fileURLWithPath(value)) }
                                ?: false
                        if (!attached) {
                            logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
                            failExternalSubtitleActivation()
                        }
                        tryActivateSubtitle()
                        updateStateFromPlayer()
                    }
                }
                return null
            }
        }
    }

    private fun addExternalSubtitleUrl(
        nativePlayer: VLCMediaPlayer,
        subtitleUrl: NSURL,
    ): Boolean {
        subtitleTrackIdsBeforeExternal = nativeSubtitleTrackIds(nativePlayer)
        val nativeResult =
            nativePlayer.addPlaybackSlave(
                subtitleUrl,
                VLCMediaPlaybackSlaveTypeSubtitle,
                // enforce = true: the attached slave IS the requested subtitle, so let
                // VLC select it directly. A remote slave over an HLS/transcode input
                // loads asynchronously, so the index isn't present yet at attach time
                // and the manual index-diff selection misses it — enforced selection
                // makes VLC render it as soon as it loads without relying on that diff.
                true,
            )
        if (nativeResult < 0) {
            logDiagnostic(
                PlaybackDiagnosticStage.Mapping,
                PlaybackDiagnosticEvent.Rejected,
                nativeCode = nativeResult.toLong(),
            )
            return false
        }
        refreshExternalSubtitleTrackIds(nativePlayer)
        // Sanitized diagnostic (no URL): records the addPlaybackSlave return code
        // (nativeCode) and how many external sub tracks were detected right after,
        // to distinguish "attach rejected" from "accepted but track not surfaced yet".
        logDiagnostic(
            stage = PlaybackDiagnosticStage.Mapping,
            event = PlaybackDiagnosticEvent.Resolved,
            trackKind = PlaybackDiagnosticTrackKind.Subtitle,
            candidateCount = externalSubtitleTrackIds.size,
            nativeCode = nativeResult.toLong(),
        )
        return true
    }

    private fun failExternalSubtitleActivation() {
        if (expectedSubtitleTarget?.kind != LocalSubtitleKind.ExternalText) return
        expectedSubtitleTarget?.let { target ->
            subtitleActivationConfirmation.fail(
                target,
                reason = SubtitleActivationFailureReason.VlckitSidecarUnavailable,
            )
        }
    }

    private fun tryActivateAudio() {
        val selection = pendingEmbeddedAudioSelection ?: return
        if (!parsed) return
        val candidates = nativeAudioCandidates()
        // VLC can finish parsing before elementary streams are added, so an empty
        // list means "tracks not available yet". Per contract the deadline begins
        // after Ready OR a non-empty track list: while not yet playing, keep waiting
        // (avoids the false-Unavailable during parse); once playing (Ready), arm the
        // bounded 3s deadline even with no tracks so activation can't hang Pending.
        if (candidates.isEmpty()) {
            if (player?.playing == true) audioActivationConfirmation.armTimeout(selection.target, 0)
            return
        }
        val resolution =
            resolveEmbeddedTrack(
                descriptor = selection.descriptor,
                orderedCandidates = candidates,
                trackKind = EmbeddedTrackKind.Audio,
            )
        logTrackResolution(PlaybackDiagnosticTrackKind.Audio, selection.target, candidates.size, resolution)
        when (resolution.result) {
            NativeTrackMappingResult.Active -> {
                val nativeTrack = resolution.candidate ?: return
                val nativePlayer = player ?: return
                val nativeIndex =
                    nativeAudioTracks(nativePlayer).indexOfFirst { candidate ->
                        candidate.trackId == nativeTrack.trackId
                    }
                if (nativeIndex < 0) {
                    audioActivationConfirmation.armTimeout(selection.target, candidates.size)
                    return
                }
                nativePlayer.selectTrackAtIndex(nativeIndex.toLong(), VLCMediaTrackTypeAudio)
                if (
                    nativeAudioTracks(nativePlayer).any { candidate ->
                        candidate.trackId == nativeTrack.trackId && candidate.selected
                    }
                ) {
                    audioActivationConfirmation.confirm(selection.target)
                } else {
                    audioActivationConfirmation.armTimeout(selection.target, candidates.size)
                }
            }
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            -> audioActivationConfirmation.fail(selection.target, resolution.result, candidates.size)
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> audioActivationConfirmation.armTimeout(selection.target, candidates.size)
        }
    }

    private fun tryActivateSubtitle() {
        val target = expectedSubtitleTarget ?: return
        if (!parsed) return
        val nativePlayer = player ?: return
        if (target.kind == LocalSubtitleKind.ExternalText) {
            refreshExternalSubtitleTrackIds(nativePlayer)
            val nativeTrack =
                nativeTextTracks(nativePlayer).singleOrNull { track -> track.trackId in externalSubtitleTrackIds }
            if (nativeTrack == null) {
                // The slave is attached with enforced selection (see addExternalSubtitleUrl),
                // so VLC selects+renders it as soon as it finishes loading — remote subs
                // over HLS load asynchronously and the track appears later. Do NOT arm the
                // failure deadline while waiting: a premature Unavailable triggers
                // PlayerViewModel's ForceEncode fallback/re-plan, which tears the session
                // down before the slave loads (the transcode-subtitle bug). Wait for it to
                // appear; confirm below once it does. A genuinely-unreachable sidecar simply
                // stays pending (video keeps playing) rather than looping a heavy re-plan.
                return
            }
            nativePlayer.selectTextTracks(listOf(nativeTrack))
            // Enforced selection already applies; a readback match just confirms it.
            if (
                nativeTextTracks(nativePlayer).any { track ->
                    track.trackId == nativeTrack.trackId && track.selected
                }
            ) {
                subtitleActivationConfirmation.confirm(target)
            }
            return
        }

        val selection = pendingEmbeddedSubtitleSelection?.takeIf { it.target == target } ?: return
        val candidates = nativeSubtitleCandidates()
        // Wait for tracks before starting the deadline; once playing (Ready), arm the
        // bounded deadline even if empty so it can't hang Pending (see tryActivateAudio).
        if (candidates.isEmpty()) {
            if (nativePlayer.playing) subtitleActivationConfirmation.armTimeout(target, 0)
            return
        }
        val resolution =
            resolveEmbeddedTrack(
                descriptor = selection.descriptor,
                orderedCandidates = candidates,
                trackKind = EmbeddedTrackKind.Subtitle,
            )
        logTrackResolution(PlaybackDiagnosticTrackKind.Subtitle, target, candidates.size, resolution)
        when (resolution.result) {
            NativeTrackMappingResult.Active -> {
                val nativeTrack = resolution.candidate ?: return
                val nativeIndex =
                    nativeTextTracks(nativePlayer).indexOfFirst { candidate ->
                        candidate.trackId == nativeTrack.trackId
                    }
                if (nativeIndex < 0) {
                    subtitleActivationConfirmation.armTimeout(target, candidates.size)
                    return
                }
                nativePlayer.selectTextTracks(listOf(nativeTrack))
                if (
                    nativeTextTracks(nativePlayer).any { candidate ->
                        candidate.trackId == nativeTrack.trackId && candidate.selected
                    }
                ) {
                    subtitleActivationConfirmation.confirm(target)
                } else {
                    subtitleActivationConfirmation.armTimeout(target, candidates.size)
                }
            }
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            -> subtitleActivationConfirmation.fail(target, result = resolution.result, candidateCount = candidates.size)
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> subtitleActivationConfirmation.armTimeout(target, candidates.size)
        }
    }

    private fun nativeAudioCandidates(): List<NativeTrackCandidate<VLCMediaPlayerTrack>> {
        val nativePlayer = player ?: return emptyList()
        return nativeAudioTracks(nativePlayer).map { track ->
            NativeTrackCandidate(
                value = track,
                // VLC exposes a native ES ID, not Jellyfin's source stream index, so
                // it is not comparable identity (selection value only).
                stableSourceIndex = null,
                // VLC's codec (a fourcc DISPLAY name) and track description are
                // server-un-matchable synthesized strings — comparing them forces
                // false CodecConflict/TitleConflict and an unnecessary transcode.
                // Match on the reliable filtered ordinal + ISO language instead.
                codec = null,
                language = track.language,
                label = null,
            )
        }
    }

    private fun nativeSubtitleCandidates(): List<NativeTrackCandidate<VLCMediaPlayerTrack>> {
        val nativePlayer = player ?: return emptyList()
        return nativeTextTracks(nativePlayer)
            .filter { track -> track.trackId !in externalSubtitleTrackIds }
            .map { track ->
                NativeTrackCandidate(
                    value = track,
                    // Native ID is not Jellyfin identity and shifts when a slave is added.
                    stableSourceIndex = null,
                    // VLC codec/description are synthesized display strings that never match
                    // Jellyfin's subtitle codec/raw title; match on filtered ordinal + ISO
                    // language to avoid false CodecConflict/TitleConflict (see audio).
                    codec = null,
                    language = track.language,
                    label = null,
                )
            }
    }

    private fun nativeSubtitleTrackIds(nativePlayer: VLCMediaPlayer): Set<String> =
        nativeTextTracks(nativePlayer).map { track -> track.trackId }.toSet()

    private fun nativeAudioTracks(nativePlayer: VLCMediaPlayer): List<VLCMediaPlayerTrack> =
        nativePlayer.audioTracks.filterIsInstance<VLCMediaPlayerTrack>()

    private fun nativeTextTracks(nativePlayer: VLCMediaPlayer): List<VLCMediaPlayerTrack> =
        nativePlayer.textTracks.filterIsInstance<VLCMediaPlayerTrack>()

    private fun refreshExternalSubtitleTrackIds(nativePlayer: VLCMediaPlayer) {
        val addedIds = nativeSubtitleTrackIds(nativePlayer) - subtitleTrackIdsBeforeExternal
        if (addedIds.isNotEmpty()) {
            externalSubtitleTrackIds = externalSubtitleTrackIds + addedIds
        }
    }

    private fun updateStateFromPlayer(
        statusOverride: PlaybackStatus? = null,
        callbackState: VLCMediaPlayerState? = null,
        callbackLength: Long? = null,
    ) {
        if (released) return
        val nativePlayer = player ?: return
        // One-shot resume seek: VLC only honors a time set once playback is live,
        // so apply the deferred start position the first time the player is playing.
        pendingInitialSeekMs?.let { seekTargetMs ->
            if (nativePlayer.playing) {
                pendingInitialSeekMs = null
                nativePlayer.time =
                    VLCTime.timeWithInt(
                        seekTargetMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
            }
        }
        tryActivateAudio()
        tryActivateSubtitle()

        val nativePositionMs =
            nativePlayer.time.intValue
                .toLong()
                .coerceAtLeast(0L)
        pictureInPictureNativeTimeMs.store(nativePositionMs)
        val currentGeneration = generation.load()
        val transitionUpdate = playbackTransition.observePosition(currentGeneration, nativePositionMs)
        if (transitionUpdate?.arrivedNow == true) {
            endOfStream.ignoreNextSample()
            logPlaybackTransitionEvent("target-arrived", nativePlayer)
        }
        if (transitionUpdate?.becameReady == true) {
            settlePlaybackTransitionReady()
        }
        sampleDisplayedPicturesForTransition()
        val activeTransitionDecision = playbackTransition.currentDecision(currentGeneration)
        if (nativePlayer.playing || nativePositionMs > 0L) {
            nativePlaybackObserved = true
        }
        val playbackProgressedBeforeSample = endOfStream.playbackEverProgressed
        endOfStream.onPositionSample(
            positionMs = nativePositionMs,
            playing = nativePlayer.playing,
            seekInFlight =
                (
                    activeTransitionDecision?.state == VlcKitPlaybackTransitionState.Pending
                ) ||
                    pendingInitialSeekMs != null,
        )
        if (!playbackProgressedBeforeSample && endOfStream.playbackEverProgressed) {
            logPlaybackTransitionEvent("playback-progress-proven", nativePlayer)
        }
        if (runtimeDiagnosticsTick == 0) {
            updateRuntimeDiagnostics(nativePlayer)
        }
        runtimeDiagnosticsTick = (runtimeDiagnosticsTick + 1) % RUNTIME_DIAGNOSTICS_SAMPLE_TICKS
        val nativeDurationMs =
            if (callbackLength != null) {
                callbackLength
            } else {
                currentMedia?.length?.intValue?.toLong()
            }
        val durationMs = currentDurationMs(nativeDurationMs)
        val previousState = _playbackState.value
        val previousStatus = previousState.status
        val nativeState = callbackState ?: nativePlayer.state
        // VLCKit 4 reports both natural completion and interruption as a sticky
        // Stopped state. The shared 3.x Ended/Stopped kernel remains authoritative
        // for its existing callers; this controller first classifies the 4.x stop
        // from strict progress and near-end evidence.
        // A status override (pause/seek) must not consume a terminal edge, so the
        // latch is only advanced when we are actually reading the native level.
        val terminalLevel = if (statusOverride == null) nativeState.terminalEventOrNull() else null
        val terminalEdge = if (statusOverride == null) terminalLatch.observe(terminalLevel) else null
        val terminalDecision =
            when {
                terminalLevel == null -> null
                // Sticky repeat: change nothing. Falling through to a live status read
                // would reclassify the held Stopped state against a dead clock.
                terminalEdge == null -> VlcTerminalStatusDecision()
                terminalEdge == VlcTerminalEvent.Stopped ->
                    when (
                        classifyVlcKit4StoppedTransition(
                            playIntent = playWhenReady,
                            playbackEverProgressed = endOfStream.playbackEverProgressed,
                            nativePositionMs = nativePositionMs,
                            lastPublishedPositionMs = previousState.positionMs,
                            durationMs = durationMs,
                        )
                    ) {
                        VlcTerminalEvent.EndReached ->
                            resolveVlcTerminalStatus(
                                event = VlcTerminalEvent.EndReached,
                                currentStatus = previousStatus,
                                playIntent = playWhenReady,
                                playbackEverProgressed = endOfStream.playbackEverProgressed,
                                endRejectedAwaitingStopped = endOfStream.endRejectedAwaitingStopped,
                                nativePositionMs = nativePositionMs,
                                lastPublishedPositionMs = previousState.positionMs,
                                durationMs = durationMs,
                            )
                        VlcTerminalEvent.Stopped ->
                            resolveVlcKit4StoppedTransition(
                                currentStatus = previousStatus,
                                playIntent = playWhenReady,
                                nativePositionMs = nativePositionMs,
                                lastPublishedPositionMs = previousState.positionMs,
                            )
                    }
                terminalEdge == VlcTerminalEvent.EndReached ->
                    resolveVlcTerminalStatus(
                        event = terminalEdge,
                        currentStatus = previousStatus,
                        playIntent = playWhenReady,
                        playbackEverProgressed = endOfStream.playbackEverProgressed,
                        endRejectedAwaitingStopped = endOfStream.endRejectedAwaitingStopped,
                        nativePositionMs = nativePositionMs,
                        lastPublishedPositionMs = previousState.positionMs,
                        durationMs = durationMs,
                    )
                else -> null
            }
        if (terminalDecision != null) {
            if (terminalEdge != null) {
                logTerminalDecision(
                    nativePlayer = nativePlayer,
                    terminalEvent = terminalEdge,
                    decision = terminalDecision,
                    nativePositionMs = nativePositionMs,
                    previousState = previousState,
                    durationMs = durationMs,
                )
            }
            if (terminalDecision.endRejected) {
                endOfStream.onEndRejected()
                // Once per prepare: VLC stays in the Stopped state after a rejected
                // completion, so every later callback re-decides the same event.
                if (endOfStream.shouldLogRejection()) {
                    logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Rejected)
                }
            }
            if (terminalDecision.consumeEndRejected) endOfStream.consumeEndRejected()
        }
        // A terminal decision with no published status changes nothing: keep the
        // decided status AND the position it published, because the native clock is
        // already dead and VLCKit reports these states on every later callback.
        val status =
            when {
                terminalDecision != null -> terminalDecision.publishedStatus ?: previousStatus
                statusOverride != null -> statusOverride
                activeTransitionDecision?.forceBuffering == true -> PlaybackStatus.Buffering
                else ->
                    nativePlayer.resolvePlaybackStatus(
                        previous = previousStatus,
                        buffering = buffering,
                        playIntent = playWhenReady,
                        nativeState = nativeState,
                    )
            }
        pictureInPictureSeekable.store(if (nativePlayer.seekable) 1L else 0L)
        val resolvedPosition =
            when {
                terminalDecision == null -> activeTransitionDecision?.publishedPositionMs ?: nativePositionMs
                terminalDecision.publishedStatus == null -> previousState.positionMs
                else -> terminalDecision.positionMs
            }
        if (
            status == PlaybackStatus.Failed ||
            status == PlaybackStatus.Completed ||
            terminalDecision?.publishedStatus != null
        ) {
            clearPlaybackTransition()
        }
        emitPlaybackState { current ->
            current.copy(
                status = status,
                positionMs = resolvedPosition,
                durationMs = durationMs,
                bufferedPositionMs = resolvedPosition,
                playbackSpeed = requestedPlaybackSpeed,
                error =
                    when {
                        status == PlaybackStatus.Failed -> current.error ?: classifyVlcFailure()
                        else -> null
                    },
                audioUnavailable = false,
            )
        }
        surfaceView?.invalidatePlaybackState()
    }

    private fun updateRuntimeDiagnostics(nativePlayer: VLCMediaPlayer) {
        val videoSize =
            nativePlayer.videoSize.useContents {
                width.toInt() to height.toInt()
            }
        val droppedFrames =
            currentMedia
                ?.statistics
                ?.useContents {
                    lostPictures.toLong()
                }?.takeIf { value -> value >= 0L }
        _runtimeDiagnostics.update { current ->
            current.copy(
                videoWidth = videoSize.first.takeIf { value -> value > 0 },
                videoHeight = videoSize.second.takeIf { value -> value > 0 },
                droppedVideoFrames = droppedFrames,
                outputDroppedVideoFrames = droppedFrames,
                videoDecoderName = null,
                videoFrameRate = null,
                bandwidthEstimateBps = null,
                playbackTransition = playbackTransition.runtimeDiagnostics(generation.load()),
            )
        }
    }

    private fun VlcKitPlaybackTransition.runtimeDiagnostics(currentGeneration: Long): PlaybackTransitionRuntimeDiagnostics? =
        snapshot(currentGeneration)?.let { snapshot ->
            PlaybackTransitionRuntimeDiagnostics(
                kind =
                    when (snapshot.kind) {
                        VlcKitPlaybackTransitionKind.Prepare -> PlaybackTransitionRuntimeKind.Prepare
                        VlcKitPlaybackTransitionKind.Resume -> PlaybackTransitionRuntimeKind.Resume
                        VlcKitPlaybackTransitionKind.Seek -> PlaybackTransitionRuntimeKind.Seek
                    },
                state =
                    when (snapshot.state) {
                        VlcKitPlaybackTransitionState.Pending -> PlaybackTransitionRuntimeState.Pending
                        VlcKitPlaybackTransitionState.Ready -> PlaybackTransitionRuntimeState.Ready
                        VlcKitPlaybackTransitionState.TimedOut -> PlaybackTransitionRuntimeState.TimedOut
                    },
                targetPositionMs = snapshot.targetPositionMs,
                nativePositionMs = snapshot.nativePositionMs,
                targetArrived = snapshot.targetArrived,
                clockAdvanced = snapshot.clockAdvanced,
                pictureAdvances = snapshot.pictureAdvances,
                requiredPictureAdvances = snapshot.requiredPictureAdvances,
            )
        }

    /**
     * Transition readiness must follow native state cadence rather than the
     * intentionally throttled diagnostics cadence. This remains on the owner
     * lane, after the native delegate's unconditional asynchronous Main hop.
     */
    private fun sampleDisplayedPicturesForTransition() {
        val displayedPictures =
            currentMedia
                ?.statistics
                ?.useContents {
                    displayedPictures.toLong()
                }?.takeIf { value -> value >= 0L }
        observeDisplayedPictures(displayedPictures)
    }

    private fun releasePlayIntentAfterAudioActivation() {
        if (playWhenReady && !released) {
            player?.play()
            applyRequestedRate()
            updateStateFromPlayer()
        }
    }

    private fun applyRequestedRate() {
        if (requestedPlaybackSpeed != DEFAULT_PLAYBACK_SPEED) {
            player?.rate = requestedPlaybackSpeed
        }
    }

    // VLC's delegate exposes no error codes. Classify only the trustworthy
    // signal — a failed or timed-out network parse before playback ever
    // progressed is a connectivity failure (gets the shared one-shot network
    // retry). Everything ambiguous stays Unknown, which remains eligible for
    // the decoder fallback ladder; never guess Decoder/UnsupportedMedia.
    private fun classifyVlcFailure(): PlaybackError {
        if (nativePlaybackObserved) return PlaybackError.Unknown
        return when (currentMedia?.parsedStatus) {
            VLCMediaParsedStatusFailed, VLCMediaParsedStatusTimeout -> PlaybackError.Network
            else -> PlaybackError.Unknown
        }
    }

    private fun failPlayback(
        stage: PlaybackDiagnosticStage,
        error: PlaybackError = PlaybackError.Unknown,
    ) {
        if (lastPlan?.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
            val detachedOfflineLease = offlineLeaseHolder.detach()
            offlinePath = null
            offlineSidecarPath = null
            detachNativeSession { detachedOfflineLease?.release() }
        }
        logDiagnostic(stage, PlaybackDiagnosticEvent.Failed)
        emitPlaybackState { current ->
            current.copy(
                status = PlaybackStatus.Failed,
                error = error,
                audioUnavailable = false,
            )
        }
    }

    private fun guardedVlcUrl(candidateUrl: String): String? {
        val strippedUrl = CredentialOriginGuard.stripAuthQueryParams(candidateUrl)
        val parsedUrl = parseUrl(strippedUrl) ?: return null
        val protocol = parsedUrl.protocol.name
        if (!protocol.equals("http", ignoreCase = true) && !protocol.equals("https", ignoreCase = true)) return null
        if (parsedUrl.user != null || parsedUrl.password != null) return null
        if (!credentialOriginGuard.mayAttachCredentials(strippedUrl)) return strippedUrl
        return URLBuilder(strippedUrl)
            .apply { parameters.append(JELLYFIN_API_KEY_QUERY_NAME, session.accessToken) }
            .buildString()
    }

    private fun applySubtitleStyle(
        media: VLCMedia,
        style: SubtitleStyle,
    ) {
        val fontSize = (DEFAULT_VLC_SUBTITLE_SIZE * style.fontScale.coerceIn(0.1f, 5f)).roundToInt().coerceAtLeast(1)
        media.addOption("--freetype-fontsize=$fontSize")
        style.foregroundColor.toVlcRgb()?.let { color -> media.addOption("--freetype-color=$color") }
        // VLCKit has no public per-media equivalent for background or edge style.
    }

    private fun detachNativeSession(
        expectReplacement: Boolean = false,
        onTeardown: (() -> Unit)? = null,
    ) {
        cancelPictureInPictureSeek()
        val surfaceDetachStarted = TimeSource.Monotonic.markNow()
        surfaceView?.detachNativeSession(expectReplacement)
        logSlowTeardownStep("surface-detach", surfaceDetachStarted.elapsedNow().inWholeMilliseconds)
        generation.addAndFetch(1L)
        clearPlaybackTransition()
        resetVideoOutputBaseline()
        val oldMedia = currentMedia
        val oldPlayer = player
        oldPlayer?.delegate = null
        oldMedia?.delegate = null
        // libvlc's stop() joins the input/decoder threads and tears the vout down
        // through the main queue, so running it here — on Main, with the drawable
        // still attached — can stall for seconds on a live HLS/transcode input or
        // deadlock against the vout's own main-queue work. Detach the view first and
        // hand the blocking teardown to a background dispatcher, exactly as the
        // Android LibVLC controller does. Everything the staleness guard depends on
        // (generation bump, delegate detach, field clearing) stays synchronous here,
        // so late callbacks from this session are already rejected by
        // `isCurrentCallback` before the native teardown finishes.
        if (oldPlayer != null) {
            // Ownership transfers to the teardown coroutine, so the field is cleared
            // FIRST: leaving it set would let a following stop()/release() schedule a
            // second, concurrent teardown of the same native player. `prepare()`
            // always builds a fresh VLCMediaPlayer and rebinds the retained
            // surfaceView, and every command uses a safe call, so a null player
            // between sessions is inert rather than a lost capability.
            val drawableDetachStarted = TimeSource.Monotonic.markNow()
            oldPlayer.drawable = null
            logSlowTeardownStep("drawable-detach", drawableDetachStarted.elapsedNow().inWholeMilliseconds)
            player = null
            stateScope.launch(ioDispatcher) {
                try {
                    oldPlayer.stop()
                    oldPlayer.media = null
                } finally {
                    onTeardown?.invoke()
                }
            }
        } else {
            onTeardown?.invoke()
        }
        nativeDelegate = null
        currentMedia = null
        terminalLatch.onSessionReset()
        externalSubtitleTrackIds = emptySet()
        subtitleTrackIdsBeforeExternal = emptySet()
        externalSubtitleAttachAttempted = false
        buffering = false
        nativeBufferingProgress = null
        pictureInPictureSeekable.store(0L)
        pictureInPictureNativeTimeMs.store(0L)
    }

    private fun logSlowTeardownStep(
        step: String,
        durationMs: Long,
    ) {
        if (durationMs < SLOW_VLCKIT_TEARDOWN_LOG_THRESHOLD_MS) return
        vlcControllerLogger.i {
            "stage=teardown event=slow-step step=$step durationMs=$durationMs"
        }
    }

    /**
     * Event-scoped, credential-free facts for support bundles. These are emitted
     * only at transition boundaries, buffering boundaries, and the first proven
     * playback advance; sampling every native time callback would flood logs.
     */
    private fun logPlaybackTransitionEvent(
        event: String,
        nativePlayer: VLCMediaPlayer? = player,
    ) {
        val currentGeneration = generation.load()
        val snapshot = playbackTransition.snapshot(currentGeneration)
        val decision = playbackTransition.currentDecision(currentGeneration)
        val presentation = currentPresentationFacts()
        val videoSize =
            nativePlayer?.videoSize?.useContents {
                width.toInt() to height.toInt()
            }
        vlcControllerLogger.i {
            "stage=playback-transition event=$event platform=ios backend=vlckit " +
                "generation=$currentGeneration transitionSequence=${playbackTransition.transitionSequence(currentGeneration)} " +
                "kind=${snapshot?.kind} state=${snapshot?.state} targetPositionMs=${snapshot?.targetPositionMs} " +
                "nativePositionMs=${snapshot?.nativePositionMs} publishedPositionMs=${decision?.publishedPositionMs} " +
                "targetArrived=${snapshot?.targetArrived} clockAdvanced=${snapshot?.clockAdvanced} " +
                "pictureAdvances=${snapshot?.pictureAdvances} requiredPictureAdvances=${snapshot?.requiredPictureAdvances} " +
                "decodedVideo=${presentation?.decodedVideo} displayedPictures=${presentation?.displayedPictures} " +
                "lostPictures=${presentation?.lostPictures} hasVideoOut=${nativePlayer?.hasVideoOut} " +
                "videoWidth=${videoSize?.first} videoHeight=${videoSize?.second} nativePlaying=${nativePlayer?.playing} " +
                "nativeState=${nativePlayer?.state} nativeBuffering=$buffering " +
                "nativeBufferingProgressMilli=${nativeBufferingProgress?.times(1_000f)?.roundToInt()} " +
                "playIntent=$playWhenReady pendingInitialSeek=${pendingInitialSeekMs != null} " +
                "playbackEverProgressed=${endOfStream.playbackEverProgressed}"
        }
    }

    private fun logTerminalDecision(
        nativePlayer: VLCMediaPlayer,
        terminalEvent: VlcTerminalEvent,
        decision: VlcTerminalStatusDecision,
        nativePositionMs: Long,
        previousState: PlaybackState,
        durationMs: Long?,
    ) {
        val currentGeneration = generation.load()
        val transition = playbackTransition.snapshot(currentGeneration)
        val presentation = currentPresentationFacts()
        vlcControllerLogger.i {
            "stage=terminal event=classified platform=ios backend=vlckit generation=$currentGeneration " +
                "terminalEvent=$terminalEvent nativeState=${nativePlayer.state} nativePlaying=${nativePlayer.playing} " +
                "nativePositionMs=$nativePositionMs previousPositionMs=${previousState.positionMs} durationMs=$durationMs " +
                "previousStatus=${previousState.status} decisionStatus=${decision.publishedStatus} " +
                "decisionPositionMs=${decision.positionMs} endRejected=${decision.endRejected} " +
                "playIntent=$playWhenReady playbackEverProgressed=${endOfStream.playbackEverProgressed} " +
                "transitionKind=${transition?.kind} transitionState=${transition?.state} " +
                "transitionTargetPositionMs=${transition?.targetPositionMs} transitionNativePositionMs=${transition?.nativePositionMs} " +
                "targetArrived=${transition?.targetArrived} clockAdvanced=${transition?.clockAdvanced} " +
                "pictureAdvances=${transition?.pictureAdvances} requiredPictureAdvances=${transition?.requiredPictureAdvances} " +
                "decodedVideo=${presentation?.decodedVideo} displayedPictures=${presentation?.displayedPictures} " +
                "lostPictures=${presentation?.lostPictures} hasVideoOut=${nativePlayer.hasVideoOut} " +
                "nativeBuffering=$buffering nativeBufferingProgressMilli=${nativeBufferingProgress?.times(1_000f)?.roundToInt()}"
        }
    }

    private fun currentPresentationFacts(): VlcKitPresentationFacts? =
        currentMedia?.statistics?.useContents {
            VlcKitPresentationFacts(
                decodedVideo = decodedVideo.toLong(),
                displayedPictures = displayedPictures.toLong(),
                lostPictures = lostPictures.toLong(),
            )
        }

    private fun beginPlaybackTransition(
        kind: VlcKitPlaybackTransitionKind,
        targetPositionMs: Long,
        playIntent: Boolean,
    ): Boolean {
        val currentGeneration = generation.load()
        if (released) return false
        val videoExpected = lastPlan?.videoExpected ?: true
        val sequence =
            if (playIntent && videoExpected) {
                ++playbackTransitionSequence
            } else {
                null
            }
        val boundedTarget =
            targetPositionMs.coerceAtLeast(0L).let { target ->
                currentDurationMs()?.let(target::coerceAtMost) ?: target
            }
        playbackTransition.begin(
            generation = currentGeneration,
            kind = kind,
            targetPositionMs = boundedTarget,
            playIntent = playIntent,
            videoExpected = videoExpected,
            requiredPictureAdvances = REQUIRED_VLCKIT_PICTURE_ADVANCES,
            arrivalToleranceMs = VLCKIT_SEEK_ARRIVAL_TOLERANCE_MS,
            transitionSequence = sequence,
            initialDisplayedPictures = lastDisplayedPictures,
        )
        logPlaybackTransitionEvent("started")
        cancelPlaybackTransitionTimeout()
        if (sequence != null) {
            emitPlaybackTransitionObservation(
                prepareEpoch = currentGeneration,
                transitionSequence = sequence,
                outcome = PlaybackTransitionOutcome.Started,
            )
            armPlaybackTransitionTimeout(currentGeneration, sequence)
        }
        return true
    }

    private fun armPlaybackTransitionTimeout(
        transitionGeneration: Long,
        transitionSequence: Long,
    ) {
        val callbackPlayer = player ?: return
        val callbackMedia = currentMedia ?: return
        playbackTransitionTimeoutJob =
            stateScope.launch {
                delay(playbackHealthNoVideoOutputThresholdMs(activeBackend))
                dispatch_async(dispatch_get_main_queue()) {
                    if (!isCurrentCallback(transitionGeneration, callbackPlayer, callbackMedia)) return@dispatch_async
                    val timeoutDecision =
                        playbackTransition.timeout(transitionGeneration, transitionSequence)
                            ?: return@dispatch_async
                    if (
                        shouldLatchVlcKitPictureInPictureContent(
                            transitionState = timeoutDecision.state,
                            hasVideoOut = callbackPlayer.hasVideoOut,
                            nativePlaying = callbackPlayer.playing,
                        )
                    ) {
                        firstVideoOutputObservedGeneration.store(transitionGeneration)
                    }
                    logPlaybackTransitionEvent("timed-out", callbackPlayer)
                    emitPlaybackTransitionObservation(
                        prepareEpoch = transitionGeneration,
                        transitionSequence = transitionSequence,
                        outcome = PlaybackTransitionOutcome.TimedOut,
                    )
                    updateStateFromPlayer()
                }
            }
    }

    private fun observeDisplayedPictures(displayedPictures: Long?) {
        val currentGeneration = generation.load()
        if (released) return
        val transitionUpdate = playbackTransition.observeDisplayedPictures(currentGeneration, displayedPictures)
        displayedPictures?.takeIf { count -> count >= 0L }?.let { count -> lastDisplayedPictures = count }
        if (transitionUpdate?.becameReady == true) {
            settlePlaybackTransitionReady()
        }
    }

    private fun settlePlaybackTransitionReady() {
        val currentGeneration = generation.load()
        val sequence = playbackTransition.transitionSequence(currentGeneration)
        cancelPlaybackTransitionTimeout()
        logPlaybackTransitionEvent("presented")
        if (sequence != null) {
            emitPlaybackTransitionObservation(
                prepareEpoch = currentGeneration,
                transitionSequence = sequence,
                outcome = PlaybackTransitionOutcome.Presented,
            )
        }
        if (
            sequence == null ||
            firstVideoOutputObservedGeneration.load() == currentGeneration ||
            lastPlan?.videoExpected != true
        ) {
            return
        }
        firstVideoOutputObservedGeneration.store(currentGeneration)
        if (!videoOutputMeasurementCapabilities.isSupported) return
        videoOutputObservationsChannel.trySend(
            VideoOutputObservation(
                generation = currentGeneration,
                presented = true,
                observedAtMs = 0L,
            ),
        )
    }

    private fun emitPlaybackTransitionObservation(
        prepareEpoch: Long,
        transitionSequence: Long,
        outcome: PlaybackTransitionOutcome,
    ) {
        playbackTransitionObservationsChannel.trySend(
            PlaybackTransitionObservation(
                prepareEpoch = prepareEpoch,
                transitionSequence = transitionSequence,
                outcome = outcome,
                observedAtMs = 0L,
            ),
        )
    }

    private fun cancelPlaybackTransitionTimeout() {
        playbackTransitionTimeoutJob?.cancel()
        playbackTransitionTimeoutJob = null
    }

    private fun clearPlaybackTransition() {
        cancelPlaybackTransitionTimeout()
        cancelPictureInPictureSeek()
        playbackTransition.clear()
    }

    private fun currentDurationMs(nativeDurationMs: Long? = currentMedia?.length?.intValue?.toLong()): Long? =
        resolvePlaybackDurationMs(
            contentTimeline = lastPlan?.contentTimeline ?: PlaybackContentTimeline.UnknownOrUnbounded,
            nativeDurationMs = nativeDurationMs,
        )

    private fun resetVideoOutputBaseline() {
        lastDisplayedPictures = null
        firstVideoOutputObservedGeneration.store(NO_VIDEO_OUTPUT_GENERATION)
    }

    private fun isCurrentCallback(
        callbackGeneration: Long,
        callbackPlayer: VLCMediaPlayer,
        callbackMedia: VLCMedia,
    ): Boolean =
        !released &&
            generation.load() == callbackGeneration &&
            player === callbackPlayer &&
            currentMedia === callbackMedia

    private fun enqueueCallbackFact(
        callbackGeneration: Long,
        callbackPlayer: VLCMediaPlayer,
        callbackMedia: VLCMedia,
        fact: VlcKitCallbackFact,
    ) {
        dispatch_async(dispatch_get_main_queue()) {
            handleCallbackFact(
                callbackGeneration = callbackGeneration,
                callbackPlayer = callbackPlayer,
                callbackMedia = callbackMedia,
                fact = fact,
            )
        }
    }

    private fun handleCallbackFact(
        callbackGeneration: Long,
        callbackPlayer: VLCMediaPlayer,
        callbackMedia: VLCMedia,
        fact: VlcKitCallbackFact,
    ) {
        if (!isCurrentCallback(callbackGeneration, callbackPlayer, callbackMedia)) return
        when (fact) {
            is VlcKitCallbackFact.PlayerStateChanged ->
                updateStateFromPlayer(callbackState = fact.state)
            is VlcKitCallbackFact.BufferingChanged -> {
                val wasBuffering = buffering
                nativeBufferingProgress = fact.progress
                buffering = fact.progress < 1f
                if (wasBuffering != buffering) {
                    logPlaybackTransitionEvent(
                        event = if (buffering) "native-buffering-started" else "native-buffering-completed",
                        nativePlayer = callbackPlayer,
                    )
                }
                updateStateFromPlayer()
            }
            is VlcKitCallbackFact.TrackChanged -> {
                refreshExternalSubtitleTrackIds(callbackPlayer)
                tryActivateAudio()
                tryActivateSubtitle()
                updateStateFromPlayer()
            }
            is VlcKitCallbackFact.LengthChanged ->
                updateStateFromPlayer(callbackLength = fact.length)
            VlcKitCallbackFact.TimeChanged ->
                updateStateFromPlayer()
            VlcKitCallbackFact.MediaParsingFinished -> {
                parsed = true
                handleMediaFact()
            }
            VlcKitCallbackFact.MediaMetadataChanged ->
                handleMediaFact()
        }
    }

    private fun handleMediaFact() {
        // External-subtitle attach is NOT done here — it happens in prepare() right
        // after the media is set and before play() (attachExternalSubtitleForSession),
        // matching VLCKit's required attachment pattern for the slave to register over
        // an HLS/transcode input.
        tryActivateAudio()
        tryActivateSubtitle()
        updateStateFromPlayer()
    }

    private fun attachExternalSubtitleForSession(
        nativePlayer: VLCMediaPlayer,
        media: VLCMedia,
        subtitleAsset: SubtitleAsset?,
    ) {
        if (externalSubtitleAttachAttempted) return
        // Only attach when an external subtitle is the requested target.
        if (expectedSubtitleTarget?.kind != LocalSubtitleKind.ExternalText) return
        externalSubtitleAttachAttempted = true
        val externalAttached =
            attachExternalSubtitle(
                nativePlayer,
                subtitleAsset,
                generation.load(),
                media,
                subtitleSelectionSequence,
                offlineSidecarPath,
            )
        if (externalAttached == false) {
            failExternalSubtitleActivation()
        }
    }

    private fun logTrackResolution(
        kind: PlaybackDiagnosticTrackKind,
        target: Any?,
        candidateCount: Int,
        resolution: NativeTrackResolution<*>,
    ) {
        if (
            !trackResolutionDiagnostics.admit(
                kind = kind,
                target = target,
                candidateCount = candidateCount,
                result = resolution.result,
                reason = resolution.reason,
            )
        ) {
            return
        }
        logDiagnostic(
            stage = PlaybackDiagnosticStage.Mapping,
            event = PlaybackDiagnosticEvent.Resolved,
            trackKind = kind,
            candidateCount = candidateCount,
            mappingResult = resolution.result,
            mappingReason = resolution.reason,
        )
    }

    private fun emitPlaybackState(transform: (PlaybackState) -> PlaybackState) {
        if (released) return
        _playbackState.update(transform)
    }

    private fun warnReleased(operation: PlayerOperation) {
        logDiagnostic(
            PlaybackDiagnosticStage.Release,
            PlaybackDiagnosticEvent.Rejected,
            operation = operation,
        )
    }

    private fun logDiagnostic(
        stage: PlaybackDiagnosticStage,
        event: PlaybackDiagnosticEvent,
        trackKind: PlaybackDiagnosticTrackKind? = null,
        candidateCount: Int? = null,
        mappingResult: NativeTrackMappingResult? = null,
        mappingReason: NativeTrackMappingReason? = null,
        nativeCode: Long? = null,
        operation: PlaybackDiagnosticOperation? = null,
    ) {
        vlcControllerLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = stage,
                    event = event,
                    platform = diagnosticPlatform,
                    trackKind = trackKind,
                    candidateCount = candidateCount,
                    mappingResult = mappingResult,
                    mappingReason = mappingReason,
                    nativeCode = nativeCode,
                    operation = operation,
                ),
            )
        }
    }

    private class VlcKitDelegate(
        private val owner: VlcKitPlayerController,
        private val callbackGeneration: Long,
        private val callbackPlayer: VLCMediaPlayer,
        private val callbackMedia: VLCMedia,
    ) : NSObject(),
        VLCMediaPlayerDelegateProtocol,
        VLCMediaDelegateProtocol {
        override fun mediaPlayerStateChanged(newState: VLCMediaPlayerState) {
            owner.enqueueCallbackFact(
                callbackGeneration,
                callbackPlayer,
                callbackMedia,
                VlcKitCallbackFact.PlayerStateChanged(newState),
            )
        }

        override fun mediaPlayerBufferingChanged(progress: Float) {
            owner.enqueueCallbackFact(
                callbackGeneration,
                callbackPlayer,
                callbackMedia,
                VlcKitCallbackFact.BufferingChanged(progress),
            )
        }

        override fun mediaPlayerTrackAdded(
            trackId: String,
            withType: VLCMediaTrackType,
        ) {
            enqueueTrackChange()
        }

        override fun mediaPlayerTrackRemoved(
            trackId: String,
            withType: VLCMediaTrackType,
        ) {
            enqueueTrackChange()
        }

        override fun mediaPlayerTrackUpdated(
            trackId: String,
            withType: VLCMediaTrackType,
        ) {
            enqueueTrackChange()
        }

        override fun mediaPlayerTrackSelected(
            trackType: VLCMediaTrackType,
            selectedId: String,
            unselectedId: String,
        ) {
            enqueueTrackChange()
        }

        private fun enqueueTrackChange() {
            owner.enqueueCallbackFact(
                callbackGeneration,
                callbackPlayer,
                callbackMedia,
                VlcKitCallbackFact.TrackChanged,
            )
        }

        override fun mediaPlayerLengthChanged(length: Long) {
            owner.enqueueCallbackFact(
                callbackGeneration,
                callbackPlayer,
                callbackMedia,
                VlcKitCallbackFact.LengthChanged(length),
            )
        }

        override fun mediaPlayerTimeChanged(aNotification: NSNotification) {
            owner.enqueueCallbackFact(
                callbackGeneration,
                callbackPlayer,
                callbackMedia,
                VlcKitCallbackFact.TimeChanged,
            )
        }

        // Do NOT gate on `aMedia === callbackMedia`: Kotlin/Native ObjC interop can
        // hand back a fresh Kotlin wrapper for the same underlying VLCMedia, so that
        // identity check spuriously drops mediaDidFinishParsing — leaving `parsed`
        // false forever and freezing audio/subtitle activation as Pending (Issue #3).
        // handleCallbackFact validates via isCurrentCallback (stable field refs +
        // generation), which is the correct staleness guard.
        override fun mediaDidFinishParsing(aMedia: VLCMedia) {
            owner.enqueueCallbackFact(
                callbackGeneration,
                callbackPlayer,
                callbackMedia,
                VlcKitCallbackFact.MediaParsingFinished,
            )
        }

        override fun mediaMetaDataDidChange(aMedia: VLCMedia) {
            owner.enqueueCallbackFact(
                callbackGeneration,
                callbackPlayer,
                callbackMedia,
                VlcKitCallbackFact.MediaMetadataChanged,
            )
        }
    }
}

private sealed interface VlcKitCallbackFact {
    data class PlayerStateChanged(
        val state: VLCMediaPlayerState,
    ) : VlcKitCallbackFact

    data class BufferingChanged(
        val progress: Float,
    ) : VlcKitCallbackFact

    data object TrackChanged : VlcKitCallbackFact

    data class LengthChanged(
        val length: Long,
    ) : VlcKitCallbackFact

    data object TimeChanged : VlcKitCallbackFact

    data object MediaParsingFinished : VlcKitCallbackFact

    data object MediaMetadataChanged : VlcKitCallbackFact
}

private data class VlcKitPresentationFacts(
    val decodedVideo: Long,
    val displayedPictures: Long,
    val lostPictures: Long,
)

// VLCKit 4 exposes natural completion and interruption through the same sticky
// Stopped player state. The controller classifies that level before handing it to
// the common terminal resolver.
private fun VLCMediaPlayerState.terminalEventOrNull(): VlcTerminalEvent? =
    when (this) {
        VLCMediaPlayerState.VLCMediaPlayerStateStopped -> VlcTerminalEvent.Stopped
        else -> null
    }

// Derives shared status from BOTH the VLC state enum and the authoritative
// `playing` (isPlaying) flag. Buffering is a separate delegate fact in VLCKit 4;
// it is honored only while play intent is active. Stopped is decided by the
// VLCKit 4 classifier before this runs.
private fun VLCMediaPlayer.resolvePlaybackStatus(
    previous: PlaybackStatus,
    buffering: Boolean,
    playIntent: Boolean,
    nativeState: VLCMediaPlayerState,
): PlaybackStatus =
    when (nativeState) {
        VLCMediaPlayerState.VLCMediaPlayerStateError -> PlaybackStatus.Failed
        VLCMediaPlayerState.VLCMediaPlayerStateOpening -> PlaybackStatus.Loading
        VLCMediaPlayerState.VLCMediaPlayerStateStopped ->
            if (previous == PlaybackStatus.Completed) PlaybackStatus.Completed else PlaybackStatus.Paused
        else ->
            when {
                playIntent && buffering -> PlaybackStatus.Buffering
                playing -> PlaybackStatus.Playing
                nativeState == VLCMediaPlayerState.VLCMediaPlayerStatePaused -> PlaybackStatus.Paused
                else -> PlaybackStatus.Paused
            }
    }

private fun String?.toVlcRgb(): String? {
    val hex = this?.removePrefix("#") ?: return null
    val rgb =
        when (hex.length) {
            6 -> hex
            8 -> hex.takeLast(6)
            else -> return null
        }
    return rgb.toLongOrNull(radix = 16)?.toString()
}

private const val DEFAULT_VLC_SUBTITLE_SIZE = 25f

// Native time callbacks arrive ~4x/s; sample the runtime diagnostics (videoSize
// plus the media statistics C struct) roughly once per second.
private const val RUNTIME_DIAGNOSTICS_SAMPLE_TICKS = 4

private const val REQUIRED_VLCKIT_PICTURE_ADVANCES = 2

private const val NO_VIDEO_OUTPUT_GENERATION = -1L

// A seek is considered landed once the native clock reads within this distance of
// the requested target; libVLC resumes at the nearest keyframe, so an exact match
// is never guaranteed. Matches the desktop VLC controller's arrival tolerance.
private const val VLCKIT_SEEK_ARRIVAL_TOLERANCE_MS = 2_000L

private val vlcControllerLogger = diagnosticLogger(DiagnosticTag.VlcKitPlayerController)

private const val SLOW_VLCKIT_TEARDOWN_LOG_THRESHOLD_MS = 100L
