// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import co.touchlab.kermit.Logger
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.PLAYBACK_TIMING_OFFSET_LIMIT_MS
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.DroppedFrameMeasurement
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.EmbeddedTrackKind
import com.jellyscope.core.domain.playback.LibVlcTimingController
import com.jellyscope.core.domain.playback.NativeTrackCandidate
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.OfflinePrepareResult
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackHealthMeasurementCapabilities
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlaybackTerminalOutcome
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerOperation
import com.jellyscope.core.domain.playback.PlayerTimingCommandResult
import com.jellyscope.core.domain.playback.PlayerTimingState
import com.jellyscope.core.domain.playback.PlayerTimingSupport
import com.jellyscope.core.domain.playback.PlayerTimingValue
import com.jellyscope.core.domain.playback.SubtitleActivationFailureReason
import com.jellyscope.core.domain.playback.SubtitleActivationState
import com.jellyscope.core.domain.playback.SubtitleActivationTarget
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.domain.playback.droppedFramePoll
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.domain.playback.resolveEmbeddedTrack
import com.jellyscope.core.domain.playback.vlcSlaveSubtitleUrl
import com.jellyscope.core.security.CredentialOriginGuard
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** In-process LibVLC 3.7.5 controller selected explicitly on Android. */
@OptIn(UnstableApi::class)
class LibVlcPlayerController(
    context: Context,
    private val session: Session,
    private val localSubtitleFileStore: LocalSubtitleFileStore? = null,
    private val audioFocusCoordinator: AndroidAudioFocusCoordinator? = null,
) : PlayerController,
    AndroidPlayerSurfaceBridge {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nativeTransitionMutex = Mutex()
    private val libVlc = LibVLC(appContext, arrayListOf("--no-video-title-show"))
    private val mediaPlayer = MediaPlayer(libVlc)
    private val voutCallback =
        object : IVLCVout.Callback {
            override fun onSurfacesCreated(vlcVout: IVLCVout) {
                if (released || stopped || isNativePreparePendingForCurrentGeneration()) return
                // The initial attach happens before LibVLC has discovered the
                // HLS video tracks, so VideoHelper's eager enable call can be
                // a no-op. Re-enable at the ready seam before replaying the
                // pending play request; otherwise Cube can advance audio with
                // a permanently disabled video track.
                runCatching { mediaPlayer.setVideoTrackEnabled(true) }
                controllerLogger.i {
                    "surfaces created playIntent=$playIntent videoTrack=${runCatching { mediaPlayer.videoTrack }.getOrDefault(-1)}"
                }
                if (playIntent && playbackFocusAdmitted) mediaPlayer.play()
                schedulePendingStartPosition()
            }

            override fun onSurfacesDestroyed(vlcVout: IVLCVout) {
                controllerLogger.i { "surfaces destroyed" }
            }
        }
    private val credentialOriginGuard = CredentialOriginGuard(session.serverUrl)
    private val timing = AndroidLibVlcTimingController(mediaPlayer)
    private var currentMedia: Media? = null
    private var nativeActiveMedia: Media? = null
    private var pendingNativePrepare: NativePrepareRequest? = null
    private var pendingNativeStop = false
    private val pendingNativeStopLeases = mutableListOf<OfflineArtifactLease>()
    private var nativePrepareLoopActive = false

    // Guarded by nativeTransitionMutex. Prevents a dequeued loop iteration from
    // touching the native player if release wins the mutex first.
    private var nativeTeardownStarted = false
    private var nativePrepareGeneration: Long? = null
    private var pendingPrepareSeekTargetMs: Long? = null
    private var surfaceView: VLCVideoLayout? = null
    private var attachedSurfaceView: VLCVideoLayout? = null
    private val offlineLeaseHolder = OfflineArtifactLeaseHolder()
    private var offlinePath: String? = null
    private var offlineSidecarPath: String? = null
    private var offlineArtifactResolver: OfflineArtifactResolver? = null

    /** Platform DI seam; the public controller constructor remains remote-compatible. */
    internal fun setOfflineArtifactResolver(resolver: OfflineArtifactResolver) {
        offlineArtifactResolver = resolver
    }

    private data class NativePrepareRequest(
        val generation: Long,
        val streamUrl: String,
        val plan: PlaybackPlan,
        val subtitleAsset: SubtitleAsset?,
        val previousMedia: Media?,
    )

    private data class NativePrepareResult(
        val media: Media?,
        val failure: Throwable?,
    )

    private sealed class NativeTransition {
        data class Prepare(
            val request: NativePrepareRequest,
        ) : NativeTransition()

        data class Stop(
            val leases: List<OfflineArtifactLease>,
        ) : NativeTransition()
    }

    private var tickerJob: Job? = null
    private var lastPlan: PlaybackPlan? = null
    private var playIntent = false
    private var playbackFocusAdmitted = audioFocusCoordinator == null
    private var nativePlayingObserved = false

    // A Subtitle Off requested before the native Playing event is recorded here
    // instead of being issued: see `selectEmbeddedSubtitle` for why the native
    // disable must wait for the first Playing event on the Cube.
    private var deferredSubtitleDisable = false

    // Matches Media3: only a pause caused by a transient focus loss may be
    // resumed on focus gain, so a duck or an unrelated grant never starts
    // playback the user did not ask for.
    private var resumeAfterTransientFocusLoss = false

    private var runtimeDiagnosticsBaselineLostPictures: Long? = null
    private var runtimeDiagnosticsBaselineTimeMs: Long? = null

    // LibVLC statistics are cumulative for a media session. Establish the
    // displayed-picture baseline after each prepare so a stale counter cannot
    // satisfy a later prepare's first-output wait.
    private var displayedPicturesBaseline: Long? = null
    private var firstVideoOutputObservedGeneration: Long? = null

    // End-of-stream evidence. libVLC's clock is already dead at EndReached (time
    // reads -1), and it emits Stopped straight after, so the terminal decision is
    // made from per-prepare facts instead of live native reads. Its own baseline is
    // kept rather than reusing pendingStartPositionMs, which is nulled as soon as
    // the resume seek lands — long before an EndReached needs to know where
    // playback actually started. See `resolveVlcTerminalStatus`.
    private val endOfStream = VlcEndOfStreamEvidence()

    // Collapses a burst of discrete seeks into the one target the user landed on.
    private val seekCoalescer =
        SeekCoalescer(
            scope = scope,
            commit = { target -> commitSeek(target) },
        )

    // Distinguishes a deliberate stop() (terminal) from a transient pause() so a late
    // native error after stop cannot resurrect Failed over Idle, while a genuine error
    // that arrives while merely paused still surfaces.
    private var stopped = false
    private var pendingEmbeddedAudioSelection: EmbeddedAudioSelection? = null
    private var pendingEmbeddedSubtitleSelection: EmbeddedSubtitleSelection? = null
    private var lastRequestedAudioSelection: EmbeddedAudioSelection? = null
    private var lastRequestedSubtitleSelection: EmbeddedSubtitleSelection? = null
    private var lastRequestedSubtitleSelectionWasSet = false

    // Bound the per-event retry so an unresolvable selection cannot churn JNI on
    // every TimeChanged forever; reset when a new selection is requested.
    private var pendingEmbeddedAudioAttempts = 0
    private var pendingEmbeddedSubtitleAttempts = 0
    private var audioActivationRetryJob: Job? = null
    private var subtitleActivationRetryJob: Job? = null
    private var audioSetterAccepted = false
    private var subtitleSetterAccepted = false
    private var subtitleAttachmentTarget: SubtitleActivationTarget? = null
    private var preAttachmentSubtitleIds: Set<Int> = emptySet()
    private val postAttachmentSubtitleIds = mutableSetOf<Int>()
    private val selectedPostAttachmentSubtitleIds = mutableSetOf<Int>()
    private var pendingStartPositionMs: Long? = null
    private var pendingStartSeekJob: Job? = null

    // Fresh-start A/V re-lock. libVLC starts its audio master clock at play(),
    // but on some containers (e.g. VP9/AAC in mp4) the first decoded video frame
    // lands ~1-2s later and the clock is never re-referenced, so audio runs ahead.
    // Resume hides this because its start seek re-references the clock once both
    // pipelines prime. For start-from-zero (no resume seek) we reproduce that by
    // issuing one flush-seek to the current position when video output begins.
    private var startupResyncArmed = false
    private var startupResyncJob: Job? = null
    private var pendingResumeOutputRelockTargetMs: Long? = null
    private var resumeOutputRelockJob: Job? = null
    private var resumeOutputRelockState = LibVlcResumeOutputRelockState()

    // Elapsed-realtime stamp of the first Playing event after a fresh start, used to
    // measure how long video output lagged the audio clock and skip the re-lock seek
    // when it was prompt (well-behaved media is already in A/V lock).
    private var startupPlayingAtMs = 0L
    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekArrivalPositionMs: Long? = null
    private var pendingSeekTimeoutJob: Job? = null
    private var released = false
    private var generation = 0L
    private var volumeBeforeDuck: Int? = null

    private val _playbackState =
        MutableStateFlow(
            PlaybackState(
                status = PlaybackStatus.Idle,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
            ),
        )
    private val _runtimeDiagnostics = MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    private val droppedFrameMeasurementsChannel = Channel<DroppedFrameMeasurement>(Channel.BUFFERED)
    private val videoOutputObservationsChannel = Channel<VideoOutputObservation>(Channel.BUFFERED)

    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> = _runtimeDiagnostics.asStateFlow()
    override val droppedFrameMeasurements: Flow<DroppedFrameMeasurement> =
        droppedFrameMeasurementsChannel.receiveAsFlow()
    override val videoOutputObservations: Flow<VideoOutputObservation> =
        videoOutputObservationsChannel.receiveAsFlow()
    override val platformPlayer: Any? = this
    override val activeBackend: PlayerBackend = PlayerBackend.LibVlc
    override val timingController: LibVlcTimingController = timing

    // libvlc-android 3.7.5 has no subtitle text-scale API; font size is a
    // LibVLC construction-time option, so this controller cannot apply the style.
    override val appliesSubtitleStyle: Boolean = false
    override val playbackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities(
            hasReliableBufferingTransitions = true,
            hasDroppedFrameMeasurements = true,
            hasReliableFirstVideoOutput = true,
        )
    override val videoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.DisplayedPictureCounter

    private val audioActivationConfirmation =
        AudioActivationConfirmation(
            scope = scope,
            platform = PlaybackDiagnosticPlatform.Android,
            currentState = { _playbackState.value.audioActivation },
            publish = { activation ->
                _playbackState.update { current -> current.copy(audioActivation = activation) }
            },
        )
    private val subtitleActivationConfirmation =
        SubtitleActivationConfirmation(
            scope = scope,
            currentState = { _playbackState.value.subtitleActivation },
            publish = { activation ->
                _playbackState.update { current -> current.copy(subtitleActivation = activation) }
            },
            platform = PlaybackDiagnosticPlatform.Android,
        )

    init {
        mediaPlayer.vlcVout.addCallback(voutCallback)
        mediaPlayer.setEventListener(null)
    }

    private fun installEventListener(eventGeneration: Long) {
        mediaPlayer.setEventListener { event ->
            handleEvent(event, eventGeneration)
        }
    }

    private fun isNativePreparePendingForCurrentGeneration(): Boolean = nativePrepareGeneration == generation

    private fun detachViewsBeforeNativeTransition() {
        if (attachedSurfaceView != null) {
            controllerLogger.i { "surface detach before native transition" }
        }
        runCatching { mediaPlayer.detachViews() }
        // Keep surfaceView so the completed generation can reattach the existing
        // Compose AndroidView without requiring a second surface creation.
        attachedSurfaceView = null
    }

    private fun enqueueNativePrepare() {
        if (released || nativePrepareLoopActive) return
        nativePrepareLoopActive = true
        nativeScope.launch {
            while (true) {
                val transition =
                    withContext(Dispatchers.Main.immediate) {
                        when {
                            pendingNativeStop -> {
                                pendingNativeStop = false
                                NativeTransition.Stop(
                                    leases = pendingNativeStopLeases.toList().also { pendingNativeStopLeases.clear() },
                                )
                            }
                            else ->
                                pendingNativePrepare?.let { request ->
                                    pendingNativePrepare = null
                                    NativeTransition.Prepare(request)
                                }
                        }
                    } ?: break
                when (transition) {
                    is NativeTransition.Stop ->
                        nativeTransitionMutex.withLock {
                            if (!nativeTeardownStarted) {
                                runCatching { mediaPlayer.stop() }
                                val activeMedia = nativeActiveMedia
                                nativeActiveMedia = null
                                runCatching { activeMedia?.release() }
                            }
                            transition.leases.forEach(OfflineArtifactLease::release)
                        }
                    is NativeTransition.Prepare -> {
                        val result =
                            nativeTransitionMutex.withLock {
                                if (nativeTeardownStarted) {
                                    NativePrepareResult(media = null, failure = null)
                                } else {
                                    performNativePrepare(transition.request)
                                }
                            }
                        withContext(Dispatchers.Main.immediate) {
                            completeNativePrepare(transition.request, result)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                nativePrepareLoopActive = false
                if (!released && (pendingNativePrepare != null || pendingNativeStop)) enqueueNativePrepare()
            }
        }
    }

    private fun performNativePrepare(request: NativePrepareRequest): NativePrepareResult {
        val stopFailure = runCatching { mediaPlayer.stop() }.exceptionOrNull()
        controllerLogger.i { "prepare stopped generation=${request.generation}" }

        val activeMedia = nativeActiveMedia
        nativeActiveMedia = null
        runCatching { activeMedia?.release() }
        if (request.plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
            // The native stop above is the first point at which the previous file-backed item
            // is no longer allowed to read its artifact. Detach/release exactly that generation
            // here; never drain a shared lease queue that could contain a replacement.
            offlineLeaseHolder.detach()?.let { lease ->
                if (stopFailure == null) {
                    lease.release()
                } else {
                    // A thrown stop is not proof of teardown. Retry through the
                    // serialized Stop transition and keep this exact generation
                    // leased until that transition completes.
                    pendingNativeStopLeases += lease
                    pendingNativeStop = true
                }
            }
        }
        if (request.previousMedia !== activeMedia) {
            runCatching { request.previousMedia?.release() }
        }
        if (stopFailure != null) return NativePrepareResult(media = null, failure = stopFailure)

        return runCatching {
            val mediaUri =
                if (request.plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                    Uri.fromFile(File(request.streamUrl))
                } else {
                    Uri.parse(request.streamUrl)
                }
            val media = Media(libVlc, mediaUri)
            try {
                AndroidLibVlcDecoderResourcePolicy.mediaOptions.forEach(media::addOption)
                logBackendReadinessDiagnostic(request.plan, request.generation)
                mediaPlayer.media = media
                nativeActiveMedia = media
                media
            } catch (exception: Throwable) {
                runCatching { media.release() }
                throw exception
            }
        }.fold(
            onSuccess = { media -> NativePrepareResult(media = media, failure = null) },
            onFailure = { exception -> NativePrepareResult(media = null, failure = exception) },
        )
    }

    private fun completeNativePrepare(
        request: NativePrepareRequest,
        result: NativePrepareResult,
    ) {
        if (!shouldApplyLibVlcNativePrepareResult(request.generation, generation, released)) {
            // A newer prepare will stop and replace this media. If the generation
            // changed without a replacement (for example stop() or a rejected
            // prepare), enqueue teardown so the stale native media cannot remain
            // assigned and retain decoder/surface resources indefinitely.
            if (!released && pendingNativePrepare == null) {
                pendingNativeStop = true
            }
            return
        }
        nativePrepareGeneration = null
        val media = result.media
        val failure = result.failure
        if (media == null || failure != null) {
            stopped = true
            failure?.let { exception -> logFailureDiagnostic(PlaybackDiagnosticStage.Prepare, exception) }
            // A failed prepare is not itself proof that LibVLC has finished
            // closing the previous file-backed media. Route the exact detached
            // lease through the same serialized native stop transition used by
            // stop()/replacement; releasing it on this callback would let the
            // transfer delete a file while the engine is still unwinding.
            offlineLeaseHolder.detach()?.let(pendingNativeStopLeases::add)
            pendingNativeStop = true
            offlinePath = null
            offlineSidecarPath = null
            enqueueNativePrepare()
            _playbackState.update { current ->
                current.copy(status = PlaybackStatus.Failed, error = PlaybackError.UnsupportedMedia)
            }
            return
        }

        currentMedia = media
        installEventListener(generation)
        val nativeSubtitleAsset =
            request.subtitleAsset.takeUnless {
                request.plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline
            }
        if ((
                nativeSubtitleAsset != null ||
                    (request.plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline && offlineSidecarPath != null)
            ) &&
            request.plan.subtitleActivationTarget != null &&
            pendingEmbeddedSubtitleSelection == null &&
            !deferredSubtitleDisable
        ) {
            subtitleActivationConfirmation.begin(request.plan.subtitleActivationTarget)
            nativeSubtitleAsset?.let { asset ->
                attachExternalSubtitle(request.plan.subtitleActivationTarget, asset)
            } ?: offlineSidecarPath?.let { path ->
                attachExternalSubtitle(request.plan.subtitleActivationTarget, path)
            }
        }
        if (pendingEmbeddedAudioSelection == null) {
            // No clear() on this path: a null target was already published as
            // AudioActivationState.None by the state projection above, and clearing
            // here would also cancel a timeout this controller expects to keep.
            audioActivationConfirmation.applyInitialWithoutClearing(
                initialAudioActivationFor(request.plan),
            )
        }
        applyPendingEmbeddedAudioSelection()
        applyPendingEmbeddedSubtitleSelection()
        runCatching { mediaPlayer.setRate(_playbackState.value.playbackSpeed) }
        // prepare() abandoned focus, so a duck cached before this transition is
        // stale: the system re-issues Duck against the new focus request if it
        // still wants a reduced level. This also completes a Gained that arrived
        // while the prepare was pending, which cannot touch the player itself.
        volumeBeforeDuck?.let { volume ->
            // setVolume reports -1 until an audio output exists, and no output
            // exists yet on this path. Keep the cached level when the call is
            // rejected so a later focus event can still restore it.
            val applied = runCatching { mediaPlayer.setVolume(volume) }.getOrNull()
            if (applied != null && applied >= 0) volumeBeforeDuck = null
        }

        pendingPrepareSeekTargetMs?.let { target ->
            pendingStartSeekJob?.cancel()
            pendingStartSeekJob = null
            pendingSeekTargetMs = null
            pendingSeekArrivalPositionMs = null
            pendingSeekTimeoutJob?.cancel()
            pendingSeekTimeoutJob = null
            pendingStartPositionMs = target.takeIf { position -> position > 0L }
            pendingPrepareSeekTargetMs = null
            if (!playIntent && target > 0L) {
                runCatching { mediaPlayer.setTime(target) }
            }
        }
        surfaceView?.let(::attachSurface)
        if (playIntent && playbackFocusAdmitted && !resumeAfterTransientFocusLoss) {
            mediaPlayer.play()
            schedulePendingStartPosition()
            armPendingResumeOutputRelock()
            publishStatus(PlaybackStatus.Buffering)
        }
        controllerLogger.i { "prepare media assigned generation=$generation" }
        controllerLogger.i { "prepare complete" }
    }

    private fun handleEvent(
        event: MediaPlayer.Event,
        eventGeneration: Long,
    ) {
        if (event.type !in HANDLED_LIBVLC_EVENTS || released || eventGeneration != generation) return
        if (event.type in IMPORTANT_LIBVLC_EVENTS) {
            controllerLogger.i { "event=${event.type} status=${_playbackState.value.status}" }
        }
        when (event.type) {
            MediaPlayer.Event.Opening -> publishStatus(PlaybackStatus.Buffering)
            MediaPlayer.Event.Buffering -> {
                // libvlc-android 3.7.5 exposes the native cache-fill input, but
                // the pinned VLC source computes it against a dynamic internal
                // buffering duration (PTS delay, preroll, and extra buffering).
                // Keep bufferedPositionMs unsupported rather than deriving a
                // misleading millisecond estimate from this percentage.
                _runtimeDiagnostics.update { current ->
                    current.copy(libVlcCachePercent = event.getBuffering())
                }
                if (pendingSeekTargetMs != null) {
                    if (_playbackState.value.status != PlaybackStatus.Buffering) {
                        publishStatus(PlaybackStatus.Buffering)
                    }
                    return
                }
                if (playIntent && mediaPlayer.isPlaying && readPositionMs() > 0L && !isResumeOutputPending()) {
                    if (_playbackState.value.status != PlaybackStatus.Playing) {
                        publishStatus(PlaybackStatus.Playing)
                    }
                } else if (_playbackState.value.status != PlaybackStatus.Buffering) {
                    publishStatus(PlaybackStatus.Buffering)
                }
            }
            MediaPlayer.Event.Playing -> {
                nativePlayingObserved = true
                if (startupResyncArmed && startupPlayingAtMs == 0L) {
                    startupPlayingAtMs = SystemClock.elapsedRealtime()
                }
                applyDeferredSubtitleDisable()
                applyPendingEmbeddedAudioSelection()
                applyPendingEmbeddedSubtitleSelection()
                armAttachedSubtitleTimeout()
                schedulePendingStartPosition()
                if (
                    pendingStartPositionMs == null &&
                    pendingSeekTargetMs == null &&
                    readPositionMs() > 0L &&
                    !isResumeOutputPending()
                ) {
                    publishStatus(PlaybackStatus.Playing)
                } else {
                    updateProgress(PlaybackStatus.Buffering)
                }
            }
            MediaPlayer.Event.Paused -> {
                if (pendingSeekTargetMs != null && playIntent) {
                    publishStatus(PlaybackStatus.Buffering)
                } else {
                    publishStatus(PlaybackStatus.Paused)
                }
            }
            MediaPlayer.Event.Stopped -> handleTerminalEvent(VlcTerminalEvent.Stopped)
            MediaPlayer.Event.EndReached -> handleTerminalEvent(VlcTerminalEvent.EndReached)
            MediaPlayer.Event.Vout -> maybeStartupResync(event.voutCount)
            MediaPlayer.Event.EncounteredError ->
                if (!stopped) {
                    logEncounteredError()
                    _playbackState.update { current ->
                        current.copy(status = PlaybackStatus.Failed, error = PlaybackError.Unknown)
                    }
                }
            MediaPlayer.Event.ESAdded -> {
                when (event.getEsChangedType()) {
                    IMedia.Track.Type.Audio -> applyPendingEmbeddedAudioSelection()
                    IMedia.Track.Type.Text -> {
                        recordSubtitleTrackAdded(event.getEsChangedID())
                        applyPendingEmbeddedSubtitleSelection()
                    }
                }
            }
            MediaPlayer.Event.ESSelected -> {
                when (event.getEsChangedType()) {
                    IMedia.Track.Type.Audio -> confirmAudioSelection(event.getEsChangedID())
                    IMedia.Track.Type.Text -> confirmSubtitleSelection(event.getEsChangedID())
                }
            }
            MediaPlayer.Event.TimeChanged,
            MediaPlayer.Event.PositionChanged,
            -> {
                val pendingSeekTarget = pendingSeekTargetMs
                if (pendingSeekTarget != null && playIntent && mediaPlayer.isPlaying) {
                    val position = readPositionMs()
                    val arrivalPosition = pendingSeekArrivalPositionMs
                    if (hasLibVlcSeekPlaybackResumed(arrivalPosition, position)) {
                        controllerLogger.i {
                            "seek playback resumed targetMs=$pendingSeekTarget " +
                                "arrivalMs=$arrivalPosition positionMs=$position"
                        }
                        // This event's sample is seek-derived; only a later,
                        // playback-driven one may arm the progress latch.
                        endOfStream.ignoreNextSample()
                        pendingSeekTargetMs = null
                        pendingSeekArrivalPositionMs = null
                        pendingSeekTimeoutJob?.cancel()
                        pendingSeekTimeoutJob = null
                    } else if (arrivalPosition == null && abs(position - pendingSeekTarget) <= SEEK_POSITION_TOLERANCE_MS) {
                        pendingSeekArrivalPositionMs = position
                        controllerLogger.i { "seek target arrived targetMs=$pendingSeekTarget positionMs=$position" }
                        updateProgress(PlaybackStatus.Buffering)
                        return
                    } else {
                        updateProgress(PlaybackStatus.Buffering)
                        return
                    }
                }
                val nativePlaying = mediaPlayer.isPlaying
                val position = readPositionMs()
                endOfStream.onPositionSample(
                    positionMs = position,
                    playing = playIntent && nativePlaying,
                    seekInFlight = pendingSeekTargetMs != null || pendingStartPositionMs != null,
                )
                val status =
                    if (playIntent && nativePlaying && position > 0L && !isResumeOutputPending()) {
                        PlaybackStatus.Playing
                    } else {
                        _playbackState.value.status
                    }
                updateProgress(status)
            }
        }
    }

    // libVLC's EndReached/Stopped pair is decided by the shared kernel from
    // per-prepare evidence, never from the post-end native clock. The publish goes
    // through the position-carrying path because updateProgress() would re-read
    // that dead clock and overwrite the honest position with 0.
    private fun handleTerminalEvent(event: VlcTerminalEvent) {
        val current = _playbackState.value
        val decision =
            resolveVlcTerminalStatus(
                event = event,
                currentStatus = current.status,
                playIntent = playIntent,
                playbackEverProgressed = endOfStream.playbackEverProgressed,
                endRejectedAwaitingStopped = endOfStream.endRejectedAwaitingStopped,
                nativePositionMs = readPositionMs(),
                lastPublishedPositionMs = current.positionMs,
                durationMs = readDurationMs() ?: current.durationMs,
            )
        if (decision.endRejected) {
            endOfStream.onEndRejected()
            if (endOfStream.shouldLogRejection()) {
                controllerLogger.w {
                    "endReached rejected status=${current.status} positionMs=${decision.positionMs}"
                }
            }
        }
        if (decision.consumeEndRejected) {
            endOfStream.consumeEndRejected()
        }
        decision.publishedStatus?.let { status ->
            publishStatusAt(status = status, positionMs = decision.positionMs)
        }
    }

    override fun createSurfaceView(context: Context): View = (surfaceView ?: VLCVideoLayout(context).also { surfaceView = it })

    override fun attachSurface(view: View) {
        val layout = view as? VLCVideoLayout ?: return
        if (released || stopped) return
        if (attachedSurfaceView === layout) return
        if (isNativePreparePendingForCurrentGeneration()) {
            surfaceView = layout
            return
        }
        if (!layout.isAttachedToWindow) {
            // AndroidView invokes its factory before the view is inserted into
            // the hierarchy. Attaching VLC's SurfaceHolder in that window can
            // immediately produce a destroy callback on Fire OS, leaving the
            // player with no vout. Retry once the view has been attached.
            layout.post { attachSurface(layout) }
            return
        }
        if (attachedSurfaceView != null) {
            clearResumeOutputRelock()
        }
        attachedSurfaceView?.let { runCatching { mediaPlayer.detachViews() } }
        surfaceView = layout
        runCatching {
            // SurfaceView is LibVLC's normal Android output path and avoids
            // the still-frame behavior observed with TextureView on the Cube.
            // Backend replacement cleanup is now isolated from this surface
            // bridge, so the SurfaceView can remain attached for the session.
            mediaPlayer.attachViews(layout, null, false, false)
            attachedSurfaceView = layout
            controllerLogger.i { "surface attached waiting=${mediaPlayer.vlcVout.areViewsAttached()}" }
            // Compose can attach the VLC view after the ViewModel has already
            // issued play(). LibVLC records that request while no vout exists,
            // but older Fire OS builds do not reliably replay it from the vout
            // callback. Re-issue it once the views are attached so the
            // selected backend cannot remain indefinitely in Loading.
            if (playIntent && playbackFocusAdmitted) {
                controllerLogger.i { "surface attached resuming play" }
                mediaPlayer.play()
            }
        }.onFailure { exception ->
            logFailureDiagnostic(PlaybackDiagnosticStage.Render, exception)
        }
    }

    override fun detachSurface() {
        controllerLogger.i { "surface detach" }
        // prepare()/stop() already detached before entering the serialized
        // native transition. Avoid a delayed Compose disposal racing another
        // detachViews() against native stop/media replacement.
        if (!stopped && !isNativePreparePendingForCurrentGeneration()) {
            runCatching { mediaPlayer.detachViews() }
        }
        attachedSurfaceView = null
        surfaceView = null
    }

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        if (released) return
        if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
            offlinePath = null
            offlineSidecarPath = null
        } else if (offlinePath == null) {
            _playbackState.update { current ->
                current.copy(status = PlaybackStatus.Failed, error = PlaybackError.OfflineArtifactUnavailable)
            }
            return
        }
        mediaPlayer.setEventListener(null)
        generation += 1L
        stopped = true
        tickerJob?.cancel()
        audioFocusCoordinator?.abandon()
        resumeAfterTransientFocusLoss = false
        playIntent = false
        playbackFocusAdmitted = false
        nativePlayingObserved = false
        // A deferred Off belongs to the session that requested it; the new plan
        // owns subtitle state from here.
        deferredSubtitleDisable = false
        endOfStream.onPrepare(plan.startPositionMs)
        seekCoalescer.cancel()
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        lastRequestedAudioSelection = null
        lastRequestedSubtitleSelection = null
        lastRequestedSubtitleSelectionWasSet = false
        cancelActivationJobs()
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        subtitleAttachmentTarget = null
        preAttachmentSubtitleIds = emptySet()
        postAttachmentSubtitleIds.clear()
        selectedPostAttachmentSubtitleIds.clear()
        audioSetterAccepted = false
        subtitleSetterAccepted = false
        pendingStartSeekJob?.cancel()
        pendingStartSeekJob = null
        pendingStartPositionMs = plan.resumeSeekPositionMs()
        clearResumeOutputRelock()
        // Arm the fresh-start re-lock only when there is no resume seek to do it.
        startupResyncArmed = pendingStartPositionMs == null
        startupResyncJob?.cancel()
        startupResyncJob = null
        startupPlayingAtMs = 0L
        pendingSeekTargetMs = null
        pendingSeekArrivalPositionMs = null
        pendingSeekTimeoutJob?.cancel()
        pendingSeekTimeoutJob = null
        pendingPrepareSeekTargetMs = null
        lastPlan = plan
        timing.clearForDiscontinuity()
        resetRuntimeDiagnosticsBaseline()
        resetVideoOutputBaseline()
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY.copy(prepareEpoch = generation)
        val streamUrl =
            if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                offlinePath
            } else {
                credentialOriginGuard.authorizedUrl(plan.streamUrl, session.accessToken)
            }
        if (streamUrl == null) {
            controllerLogger.e { "prepare rejected unavailable stream source" }
            _playbackState.value =
                _playbackState.value.copy(
                    status = PlaybackStatus.Failed,
                    error =
                        if (plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline) {
                            PlaybackError.OfflineArtifactUnavailable
                        } else {
                            PlaybackError.Network
                        },
                )
            return
        }
        controllerLogger.i { "prepare streamMode=${plan.streamMode} mime=${plan.streamMimeType}" }
        detachViewsBeforeNativeTransition()
        val previousMedia = currentMedia
        currentMedia = null
        nativePrepareGeneration = generation
        pendingNativePrepare =
            NativePrepareRequest(
                generation = generation,
                streamUrl = streamUrl,
                plan = plan,
                subtitleAsset = subtitleAsset,
                previousMedia = previousMedia,
            )
        val subtitleActivationTarget = plan.subtitleActivationTarget
        _playbackState.value =
            _playbackState.value.copy(
                status = PlaybackStatus.Loading,
                positionMs = plan.clampedStartPositionMs(),
                durationMs = null,
                bufferedPositionMs = 0L,
                playbackSpeed = plan.playbackSpeed,
                subtitleStyle = plan.subtitleStyle,
                error = null,
                audioUnavailable = false,
                // Publishing this contract is what lets the VM update installed audio
                // and trigger DirectPlay-disabled recovery on the LibVLC backend; the
                // shared table it renders is documented in data-playback.md.
                audioActivation = initialAudioActivationFor(plan).toAudioActivationState(),
                subtitleActivation =
                    if (
                        subtitleActivationTarget != null &&
                        (
                            subtitleAsset != null ||
                                (
                                    plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline &&
                                        offlineSidecarPath != null
                                )
                        )
                    ) {
                        SubtitleActivationState.Pending(subtitleActivationTarget)
                    } else {
                        SubtitleActivationState.None
                    },
            )
        controllerLogger.i { "prepare state published" }
        stopped = false
        controllerLogger.i { "prepare queued generation=$generation" }
        controllerLogger.i { "prepare time deferred startMs=${plan.clampedStartPositionMs()}" }
        setPlaybackSpeed(plan.playbackSpeed)
        enqueueNativePrepare()
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): OfflinePrepareResult {
        if (released) return OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.LibVlc))
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
            OfflinePrepareResult.Started
        } catch (_: Throwable) {
            offlinePath = null
            offlineSidecarPath = null
            val failedLease = offlineLeaseHolder.detach()
            pendingNativeStopLeases += listOfNotNull(failedLease)
            pendingNativeStop = true
            enqueueNativePrepare()
            OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.LibVlc))
        }
    }

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) {
        if (released) return warnReleased(PlayerOperation.SelectEmbeddedAudio)
        lastRequestedAudioSelection = selection
        audioActivationRetryJob?.cancel()
        audioActivationRetryJob = null
        pendingEmbeddedAudioSelection = selection
        pendingEmbeddedAudioAttempts = 0
        audioSetterAccepted = false
        // Publish Pending for this target; the apply below confirms Active or Unavailable.
        audioActivationConfirmation.begin(selection.target)
        publishAudioActivation(AudioActivationState.Pending(selection.target))
        applyPendingEmbeddedAudioSelection()
    }

    private fun publishAudioActivation(activation: AudioActivationState) {
        _playbackState.update { current -> current.copy(audioActivation = activation) }
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        if (released) return warnReleased(PlayerOperation.SelectEmbeddedSubtitle)
        lastRequestedSubtitleSelection = selection
        lastRequestedSubtitleSelectionWasSet = true
        // The initial transcode plan commonly selects Subtitle Off before
        // LibVLC has parsed its media. Calling setSpuTrack(-1) at that point can
        // block the playback coroutine on the Cube, so the disable waits for the
        // native Playing event — the request-time `play()` intent is not proof
        // that libVLC has started. Off is already the native default, so the
        // deferral is invisible; the recorded intent is replayed at Playing so a
        // user Off during startup is applied rather than dropped.
        subtitleActivationRetryJob?.cancel()
        subtitleActivationRetryJob = null
        subtitleSetterAccepted = false
        subtitleAttachmentTarget = null
        postAttachmentSubtitleIds.clear()
        selectedPostAttachmentSubtitleIds.clear()
        deferredSubtitleDisable = false
        pendingEmbeddedSubtitleSelection = selection
        pendingEmbeddedSubtitleAttempts = 0
        if (selection == null) {
            subtitleActivationConfirmation.clear()
            if (nativePlayingObserved) {
                mediaPlayer.setSpuTrack(-1)
            } else {
                deferredSubtitleDisable = true
            }
        } else {
            subtitleActivationConfirmation.begin(selection.target)
            applyPendingEmbeddedSubtitleSelection()
        }
    }

    private fun applyDeferredSubtitleDisable() {
        if (!deferredSubtitleDisable) return
        deferredSubtitleDisable = false
        runCatching { mediaPlayer.setSpuTrack(-1) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return warnReleased(PlayerOperation.SetPlaybackSpeed)
        if (!isNativePreparePendingForCurrentGeneration()) {
            runCatching { mediaPlayer.setRate(speed) }
        }
        _playbackState.update { current -> current.copy(playbackSpeed = speed) }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        _playbackState.update { current -> current.copy(subtitleStyle = style) }
    }

    override fun play() {
        if (released) return
        val resumingStartedMedia =
            nativePlayingObserved && pendingStartPositionMs == null && !isResumeOutputPending()
        controllerLogger.i {
            "play generation=$generation nativePreparePending=${isNativePreparePendingForCurrentGeneration()}"
        }
        playIntent = true
        val admitted =
            PlaybackFocusAdmission.admit(
                requestFocus = {
                    audioFocusCoordinator?.request(generation) { eventGeneration, event ->
                        if (released || eventGeneration != generation) return@request
                        when (event) {
                            AndroidAudioFocusEvent.Gained -> {
                                if (isNativePreparePendingForCurrentGeneration()) {
                                    // Preserve the latest play intent, but consume the focus
                                    // interruption now so prepare completion can replay it.
                                    // Completion also restores volumeBeforeDuck; no native
                                    // call may happen here while the transition is in flight.
                                    resumeAfterTransientFocusLoss = false
                                    return@request
                                }
                                volumeBeforeDuck?.let { volume -> runCatching { mediaPlayer.setVolume(volume) } }
                                volumeBeforeDuck = null
                                // Resume only what a transient loss paused, matching Media3:
                                // a duck or an unrelated grant must not start playback.
                                if (resumeAfterTransientFocusLoss && playIntent) {
                                    resumeAfterTransientFocusLoss = false
                                    mediaPlayer.play()
                                }
                            }
                            AndroidAudioFocusEvent.Duck -> {
                                if (isNativePreparePendingForCurrentGeneration()) return@request
                                if (volumeBeforeDuck == null) {
                                    // volume can be -1 before an audio output exists; never
                                    // cache the sentinel or the restore would setVolume(-1).
                                    volumeBeforeDuck = runCatching { mediaPlayer.volume }.getOrNull()?.takeIf { value -> value >= 0 }
                                }
                                volumeBeforeDuck?.let { volume -> runCatching { mediaPlayer.setVolume((volume * 0.2f).roundToInt()) } }
                            }
                            AndroidAudioFocusEvent.TransientLoss -> {
                                clearResumeOutputRelock()
                                resumeAfterTransientFocusLoss = playIntent
                                if (!isNativePreparePendingForCurrentGeneration()) mediaPlayer.pause()
                            }
                            AndroidAudioFocusEvent.PermanentLoss,
                            AndroidAudioFocusEvent.BecomingNoisy,
                            -> {
                                clearResumeOutputRelock()
                                resumeAfterTransientFocusLoss = false
                                playIntent = false
                                playbackFocusAdmitted = false
                                if (!isNativePreparePendingForCurrentGeneration()) {
                                    volumeBeforeDuck?.let { volume -> runCatching { mediaPlayer.setVolume(volume) } }
                                    volumeBeforeDuck = null
                                    mediaPlayer.pause()
                                }
                                audioFocusCoordinator.abandon(eventGeneration)
                            }
                        }
                    } ?: true
                },
                nativeStart = {
                    playbackFocusAdmitted = true
                    if (!isNativePreparePendingForCurrentGeneration()) mediaPlayer.play()
                },
            )
        if (!admitted) {
            playIntent = false
            playbackFocusAdmitted = false
            val currentStatus = _playbackState.value.status
            if (currentStatus != PlaybackStatus.Failed && currentStatus != PlaybackStatus.Completed) {
                publishStatus(PlaybackStatus.Paused)
            }
            return
        }
        if (isNativePreparePendingForCurrentGeneration()) {
            publishStatus(PlaybackStatus.Buffering)
            return
        }
        schedulePendingStartPosition()
        armPendingResumeOutputRelock()
        publishStatus(if (resumingStartedMedia) PlaybackStatus.Playing else PlaybackStatus.Buffering)
    }

    override fun pause() {
        if (released) return
        controllerLogger.i { "pause generation=$generation" }
        // A seek requested just before pausing must still land, or the user pauses
        // at the old position.
        seekCoalescer.flush()
        playIntent = false
        clearResumeOutputRelock()
        pendingSeekTargetMs = null
        pendingSeekArrivalPositionMs = null
        pendingSeekTimeoutJob?.cancel()
        pendingSeekTimeoutJob = null
        if (!isNativePreparePendingForCurrentGeneration()) mediaPlayer.pause()
        publishStatus(PlaybackStatus.Paused)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceAtLeast(0L)
        // A user seek re-locks the A/V clock itself; cancel any pending fresh-start resync.
        startupResyncArmed = false
        startupResyncJob?.cancel()
        startupResyncJob = null
        clearResumeOutputRelock()
        pendingStartSeekJob?.cancel()
        pendingStartSeekJob = null
        pendingStartPositionMs = null
        // Coalesced: every setTime() flushes libVLC's decoder, so a burst of
        // discrete seeks (repeated D-pad taps, double-tap skips) used to thrash the
        // pipeline — presenting frames from superseded targets and restarting the
        // arrival-then-advance detection on each one, so the published status
        // trailed reality until a fallback probe corrected it. Only the final
        // target of a burst reaches the native player.
        seekCoalescer.request(target)
        // Publish the requested target at once so the seek bar lands where the user
        // aimed and stays there, instead of waiting for the deferred commit. Status
        // handling matches the pre-coalescing behaviour: Buffering only while a play
        // intent stands (a paused seek keeps its status, as `beginSeekBuffering` did).
        if (playIntent) publishStatus(PlaybackStatus.Buffering) else updateProgress()
    }

    private fun commitSeek(targetMs: Long) {
        if (released) return
        beginSeekBuffering(targetMs)
        if (isNativePreparePendingForCurrentGeneration()) {
            pendingPrepareSeekTargetMs = targetMs
            updateProgress()
            return
        }
        mediaPlayer.setTime(targetMs)
        updateProgress()
    }

    private fun beginSeekBuffering(
        targetMs: Long,
        arrivalPositionMs: Long? = null,
    ) {
        pendingSeekTimeoutJob?.cancel()
        pendingSeekTimeoutJob = null
        pendingSeekTargetMs = targetMs.takeIf { playIntent }
        pendingSeekArrivalPositionMs = arrivalPositionMs.takeIf { pendingSeekTargetMs != null }
        if (pendingSeekTargetMs == null) return

        controllerLogger.i { "seek buffering targetMs=$targetMs arrivalMs=$arrivalPositionMs" }
        publishStatus(PlaybackStatus.Buffering)
        val seekGeneration = generation
        pendingSeekTimeoutJob =
            scope.launch {
                delay(SEEK_BUFFERING_PROGRESS_PROBE_MS)
                if (!released && seekGeneration == generation && pendingSeekTargetMs == targetMs) {
                    pendingSeekTimeoutJob = null
                    val position = readPositionMs()
                    val arrivalPosition = pendingSeekArrivalPositionMs
                    if (hasLibVlcSeekPlaybackResumed(arrivalPosition, position)) {
                        // Same rule as the event-handler path: the sample that
                        // follows this resolution is still seek-derived.
                        endOfStream.ignoreNextSample()
                        pendingSeekTargetMs = null
                        pendingSeekArrivalPositionMs = null
                        controllerLogger.i {
                            "seek playback resumed on fallback probe targetMs=$targetMs " +
                                "arrivalMs=$arrivalPosition positionMs=$position"
                        }
                        publishStatus(PlaybackStatus.Playing)
                    } else {
                        controllerLogger.w {
                            "seek still buffering after fallback probe targetMs=$targetMs positionMs=$position"
                        }
                        updateProgress(PlaybackStatus.Buffering)
                    }
                }
            }
    }

    override fun stop() {
        if (released) return
        mediaPlayer.setEventListener(null)
        generation += 1L
        playIntent = false
        resumeAfterTransientFocusLoss = false
        nativePlayingObserved = false
        deferredSubtitleDisable = false
        endOfStream.onStop()
        seekCoalescer.cancel()
        stopped = true
        cancelActivationJobs()
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        subtitleAttachmentTarget = null
        postAttachmentSubtitleIds.clear()
        selectedPostAttachmentSubtitleIds.clear()
        audioFocusCoordinator?.abandon()
        playbackFocusAdmitted = false
        tickerJob?.cancel()
        tickerJob = null
        pendingStartSeekJob?.cancel()
        pendingStartSeekJob = null
        pendingStartPositionMs = null
        clearResumeOutputRelock()
        pendingSeekTargetMs = null
        pendingSeekArrivalPositionMs = null
        pendingSeekTimeoutJob?.cancel()
        pendingSeekTimeoutJob = null
        startupResyncArmed = false
        startupResyncJob?.cancel()
        startupResyncJob = null
        resetRuntimeDiagnosticsBaseline()
        resetVideoOutputBaseline()
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        currentMedia = null
        pendingNativePrepare = null
        offlineLeaseHolder.detach()?.let(pendingNativeStopLeases::add)
        pendingNativeStop = true
        nativePrepareGeneration = null
        pendingPrepareSeekTargetMs = null
        detachViewsBeforeNativeTransition()
        offlinePath = null
        offlineSidecarPath = null
        enqueueNativePrepare()
        publishStatus(PlaybackStatus.Idle)
    }

    override fun retry() {
        if (released) return warnReleased(PlayerOperation.Retry)
        val plan = lastPlan ?: return
        // prepare() clears the pending selections, so retain the last requested
        // choices and LibVLC's distinct explicit-Off classification.
        val audioSelection = lastRequestedAudioSelection
        val subtitleSelection = lastRequestedSubtitleSelection
        val subtitleSelectionWasSet = lastRequestedSubtitleSelectionWasSet
        val selectionDecision =
            retrySelectionDecision(
                audioSelection = audioSelection,
                subtitleIntent =
                    when {
                        !subtitleSelectionWasSet -> RetrySubtitleIntent.Unspecified
                        subtitleSelection == null -> RetrySubtitleIntent.ExplicitOff
                        else -> RetrySubtitleIntent.Selection(subtitleSelection)
                    },
                planSubtitleTarget = plan.subtitleActivationTarget,
            )
        val shouldPlay = playIntent
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
        if (shouldPlay) play()
    }

    override fun release() {
        if (released) return
        mediaPlayer.setEventListener(null)
        // Flip released + tear down callbacks/jobs synchronously so no further
        // event coroutine, ticker, or seek job can observe a half-released player.
        released = true
        generation += 1L
        resumeAfterTransientFocusLoss = false
        nativePlayingObserved = false
        deferredSubtitleDisable = false
        seekCoalescer.cancel()
        cancelActivationJobs()
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        audioActivationConfirmation.clear()
        subtitleActivationConfirmation.clear()
        audioFocusCoordinator?.abandon()
        playbackFocusAdmitted = false
        tickerJob?.cancel()
        pendingStartSeekJob?.cancel()
        pendingStartSeekJob = null
        clearResumeOutputRelock()
        pendingSeekTargetMs = null
        pendingSeekArrivalPositionMs = null
        pendingSeekTimeoutJob?.cancel()
        pendingSeekTimeoutJob = null
        startupResyncArmed = false
        startupResyncJob?.cancel()
        startupResyncJob = null
        detachSurface()
        resetRuntimeDiagnosticsBaseline()
        resetVideoOutputBaseline()
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        droppedFrameMeasurementsChannel.close()
        videoOutputObservationsChannel.close()
        mediaPlayer.vlcVout.removeCallback(voutCallback)
        val media = currentMedia
        currentMedia = null
        pendingNativePrepare = null
        pendingNativeStop = false
        nativePrepareGeneration = null
        pendingPrepareSeekTargetMs = null
        val detachedOfflineLease = offlineLeaseHolder.detach()
        val pendingOfflineLeases = pendingNativeStopLeases.toList().also { pendingNativeStopLeases.clear() }
        // Keep the release ordering used by the existing backend: detach the
        // views first, then serialize media/player teardown away from Main.
        nativeScope.launch {
            nativeTransitionMutex.withLock {
                nativeTeardownStarted = true
                val activeMedia = nativeActiveMedia
                nativeActiveMedia = null
                // INVARIANT: no path sets pendingNativeStop while currentMedia is
                // non-null, so `media` is never a Media that the Stop transition
                // already released. stop() nulls currentMedia before queueing, and
                // the stale-completion writer only fires when no newer prepare is
                // pending — i.e. the generation moved via stop()/release(), both of
                // which null it too. Keep that ordering: if a future teardown path
                // queues a Stop while currentMedia still holds the active media,
                // this line becomes a native double-free on release.
                if (media !== activeMedia) runCatching { media?.release() }
                runCatching { activeMedia?.release() }
                runCatching { mediaPlayer.stop() }
                runCatching { mediaPlayer.release() }
                runCatching { libVlc.release() }
                detachedOfflineLease?.release()
                pendingOfflineLeases.forEach(OfflineArtifactLease::release)
            }
            nativeScope.cancel()
            scope.cancel()
        }
        offlinePath = null
        offlineSidecarPath = null
    }

    private fun schedulePendingStartPosition() {
        val target = pendingStartPositionMs ?: return
        if (target <= 0L || released || !mediaPlayer.hasMedia()) return
        pendingStartSeekJob?.cancel()
        val seekGeneration = generation
        pendingStartSeekJob =
            scope.launch {
                repeat(8) { attempt ->
                    if (released || seekGeneration != generation || pendingStartPositionMs != target) {
                        return@launch
                    }
                    if (attempt > 0) delay(250L)
                    runCatching { mediaPlayer.setTime(target) }
                    delay(100L)
                    val appliedPosition = readPositionMs()
                    if (abs(appliedPosition - target) <= 1_000L || appliedPosition > target) {
                        pendingStartPositionMs = null
                        pendingStartSeekJob = null
                        beginSeekBuffering(targetMs = target, arrivalPositionMs = appliedPosition)
                        if (firstVideoOutputObservedGeneration != generation) {
                            pendingResumeOutputRelockTargetMs = target
                            resumeOutputRelockState =
                                LibVlcResumeOutputRelockState(
                                    generation = seekGeneration,
                                    awaitingFirstOutput = true,
                                )
                            // Establish the fresh-media displayed-picture baseline now,
                            // so the bounded follow-up sample can recognize a healthy
                            // picture without waiting for two 1 s ticker intervals.
                            updateRuntimeDiagnostics()
                            armPendingResumeOutputRelock()
                        }
                        controllerLogger.i { "start position applied startMs=$target" }
                        updateProgress(PlaybackStatus.Buffering)
                        return@launch
                    }
                }
                pendingStartPositionMs = null
                controllerLogger.w { "start position not confirmed startMs=$target" }
            }
    }

    private fun armPendingResumeOutputRelock() {
        if (
            released ||
            !playIntent ||
            pendingResumeOutputRelockTargetMs == null ||
            !shouldRelockLibVlcResumeOutput(resumeOutputRelockState, generation, playIntent) ||
            resumeOutputRelockJob?.isActive == true
        ) {
            return
        }
        if (pendingSeekTargetMs == null) {
            pendingResumeOutputRelockTargetMs?.let { target -> beginSeekBuffering(targetMs = target) }
        }
        val relockGeneration = generation
        resumeOutputRelockJob =
            scope.launch {
                delay(STARTUP_RESYNC_MIN_LAG_MS)
                if (released || relockGeneration != generation) return@launch
                // Pair with the baseline sample taken when the resume arrived. This
                // keeps a healthy resume off the 1 Hz ticker's two-sample latency.
                updateRuntimeDiagnostics()
                if (!shouldRelockLibVlcResumeOutput(resumeOutputRelockState, generation, playIntent)) return@launch
                resumeOutputRelockState = resumeOutputRelockState.copy(relockConsumed = true)
                val relockTarget = pendingResumeOutputRelockTargetMs ?: return@launch
                val flushTarget = libVlcResumeOutputRelockSeekTarget(relockTarget)
                pendingResumeOutputRelockTargetMs = null
                resumeOutputRelockJob = null
                beginSeekBuffering(targetMs = flushTarget)
                controllerLogger.i { "resume output re-lock setTime=$flushTarget from=$relockTarget" }
                runCatching { mediaPlayer.setTime(flushTarget) }
                updateProgress(PlaybackStatus.Buffering)
            }
    }

    private fun isResumeOutputPending(): Boolean = isLibVlcResumeOutputPending(resumeOutputRelockState, generation)

    private fun clearResumeOutputRelock() {
        resumeOutputRelockJob?.cancel()
        resumeOutputRelockJob = null
        pendingResumeOutputRelockTargetMs = null
        resumeOutputRelockState = LibVlcResumeOutputRelockState()
    }

    // Reproduce the resume-seek's clock re-reference for start-from-zero: once
    // video output actually exists, issue one flush-seek to the current position
    // so libVLC re-locks audio and video together instead of leaving audio ahead.
    private fun maybeStartupResync(voutCount: Int) {
        if (released || !startupResyncArmed || voutCount <= 0) return
        // A resume/user seek already flushes and re-locks; consume the one-shot so a
        // later Vout (e.g. surface recreation) can never fire a spurious mid-item seek.
        if (pendingStartPositionMs != null || pendingSeekTargetMs != null) {
            startupResyncArmed = false
            return
        }
        // Skip the re-lock entirely when video output appeared promptly after audio
        // started — that media is already in A/V lock, so spare it the seek hitch.
        // Drift only occurs when the first frame lags the audio clock by ~1-2s.
        val playingAt = startupPlayingAtMs
        if (playingAt > 0L) {
            val videoLagMs = SystemClock.elapsedRealtime() - playingAt
            if (videoLagMs <= STARTUP_RESYNC_MIN_LAG_MS) {
                startupResyncArmed = false
                controllerLogger.i { "startup resync skipped; video up in ${videoLagMs}ms" }
                return
            }
        }
        startupResyncArmed = false
        val resyncGeneration = generation
        startupResyncJob?.cancel()
        startupResyncJob =
            scope.launch {
                // Retry until the pipeline reports a real position — slow transcode
                // starts can still read 0 at the first attempt — then one flush-seek
                // re-references libVLC's clock. Happy path fires on the first attempt.
                repeat(STARTUP_RESYNC_MAX_ATTEMPTS) { attempt ->
                    delay(if (attempt == 0) STARTUP_RESYNC_DELAY_MS else STARTUP_RESYNC_RETRY_MS)
                    if (released || resyncGeneration != generation) return@launch
                    // A resume/user seek arriving mid-wait already re-locks; stand down.
                    if (pendingStartPositionMs != null || pendingSeekTargetMs != null) return@launch
                    if (!playIntent || !mediaPlayer.isPlaying) return@repeat
                    val position = readPositionMs()
                    if (position <= 0L) return@repeat
                    // Nudge slightly back so the target differs from the live playhead;
                    // an identical setTime() can be a no-op and would not flush/re-lock.
                    val target = (position - STARTUP_RESYNC_REWIND_MS).coerceAtLeast(0L)
                    controllerLogger.i { "startup resync setTime=$target from=$position attempt=$attempt" }
                    runCatching { mediaPlayer.setTime(target) }
                    return@launch
                }
            }
    }

    private fun publishStatus(status: PlaybackStatus) {
        updateProgress(status)
        applyTickerFor(status)
    }

    // Publishes an explicit position instead of reading the native clock. Used for
    // the terminal events, where that clock is already stopped and reports -1.
    private fun publishStatusAt(
        status: PlaybackStatus,
        positionMs: Long,
    ) {
        val position = positionMs.coerceAtLeast(0L)
        val duration = readDurationMs() ?: _playbackState.value.durationMs
        _playbackState.update { current ->
            current.copy(
                status = status,
                positionMs = position,
                durationMs = duration,
                bufferedPositionMs = position,
            )
        }
        applyTickerFor(status)
    }

    private fun applyTickerFor(status: PlaybackStatus) {
        if (status != PlaybackStatus.Playing) {
            resetRuntimeDiagnosticsBaseline()
            _runtimeDiagnostics.update { current -> current.copy(droppedVideoFramesPerSecond = null) }
        }
        if (status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering) {
            startTicker()
        } else {
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    // While a seek is outstanding, report the requested target instead of the
    // native clock. The clock still reads the pre-seek position during the
    // coalescing window and again until the seek lands, so letting it through made
    // the seek bar travel target -> old position -> target on every skip.
    private fun outstandingSeekTargetMs(): Long? = seekCoalescer.pendingTargetMs ?: pendingSeekTargetMs

    private fun updateProgress(status: PlaybackStatus = _playbackState.value.status) {
        val position = (outstandingSeekTargetMs() ?: readPositionMs()).coerceAtLeast(0L)
        val duration = readDurationMs()
        _playbackState.update { current ->
            current.copy(
                status = status,
                positionMs = position,
                durationMs = duration,
                bufferedPositionMs = position,
            )
        }
    }

    private fun startTicker() {
        if (tickerJob != null) return
        val tickerGeneration = generation
        tickerJob =
            scope.launch {
                while (!released && tickerGeneration == generation) {
                    delay(RUNTIME_DIAGNOSTICS_POLL_MS)
                    if (tickerGeneration != generation) return@launch
                    // Runtime diagnostics only need a 1 s cadence; keep them off the
                    // high-frequency TimeChanged/PositionChanged event path.
                    updateRuntimeDiagnostics()
                    updateProgress()
                }
            }
    }

    private fun readPositionMs(): Long = runCatching { mediaPlayer.time }.getOrDefault(_playbackState.value.positionMs)

    private fun readDurationMs(): Long? = runCatching { mediaPlayer.length }.getOrNull()?.takeIf { value -> value > 0L }

    private fun attachExternalSubtitle(
        target: SubtitleActivationTarget,
        subtitleAsset: SubtitleAsset,
    ) {
        attachExternalSubtitle(
            target = target,
            source =
                when (subtitleAsset) {
                    is SubtitleAsset.JellyfinRemote ->
                        credentialOriginGuard
                            .authorizedUrl(vlcSlaveSubtitleUrl(subtitleAsset.url), session.accessToken)
                            ?.let(Uri::parse)
                    is SubtitleAsset.LocalFile -> localSubtitleFileStore?.resolvePath(subtitleAsset.fileId)
                },
        )
    }

    private fun attachExternalSubtitle(
        target: SubtitleActivationTarget,
        source: String,
    ) {
        attachExternalSubtitle(target = target, source = source as Any)
    }

    private fun attachExternalSubtitle(
        target: SubtitleActivationTarget,
        source: Any?,
    ) {
        if (source == null) {
            subtitleAttachmentTarget = null
            subtitleActivationConfirmation.fail(
                target,
                reason = SubtitleActivationFailureReason.SubtitleAssetUnavailable,
            )
            return
        }

        subtitleAttachmentTarget = target
        preAttachmentSubtitleIds = currentSubtitleIds()
        val attached =
            runCatching {
                when (source) {
                    is Uri -> mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, source, true)
                    is String -> mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, source, true)
                    else -> false
                }
            }.getOrDefault(false)
        if (!attached) {
            subtitleAttachmentTarget = null
            subtitleActivationConfirmation.fail(
                target,
                reason = SubtitleActivationFailureReason.SubtitleAttachRejected,
            )
        }
    }

    private fun currentSubtitleIds(): Set<Int> =
        mediaPlayer.spuTracks
            ?.asSequence()
            ?.map { track -> track.id }
            ?.filter { id -> id != -1 }
            ?.toSet()
            .orEmpty()

    private fun recordSubtitleTrackAdded(trackId: Int) {
        val target = subtitleAttachmentTarget ?: return
        if (trackId >= 0 && trackId !in preAttachmentSubtitleIds) {
            postAttachmentSubtitleIds += trackId
            postAttachmentSubtitleIds += currentSubtitleIds() - preAttachmentSubtitleIds
            confirmAttachedSubtitle(target, trackId)
        }
    }

    private fun armAttachedSubtitleTimeout() {
        val target = subtitleAttachmentTarget ?: return
        if (postAttachmentSubtitleIds.isNotEmpty() || nativePlayingObserved) {
            subtitleActivationConfirmation.armTimeout(target, postAttachmentSubtitleIds.size)
        }
    }

    private fun confirmAttachedSubtitle(
        target: SubtitleActivationTarget,
        trackId: Int,
    ) {
        postAttachmentSubtitleIds += currentSubtitleIds() - preAttachmentSubtitleIds
        if (postAttachmentSubtitleIds.size != 1) return
        if (trackId !in postAttachmentSubtitleIds) return
        if (trackId !in selectedPostAttachmentSubtitleIds) return
        if (runCatching { mediaPlayer.spuTrack }.getOrDefault(-1) != trackId) return
        subtitleAttachmentTarget = null
        subtitleActivationConfirmation.confirm(target)
    }

    private fun nativeTrackCandidates(
        tracks: Array<MediaPlayer.TrackDescription>?,
    ): List<NativeTrackCandidate<MediaPlayer.TrackDescription>> =
        tracks
            ?.asSequence()
            ?.filter { track -> track.id != -1 }
            ?.map { track ->
                NativeTrackCandidate(
                    value = track,
                    stableSourceIndex = null,
                    codec = null,
                    language = null,
                    // libvlc's TrackDescription.name is a synthesized display
                    // string ("Track N", "Title - [lang]"), never comparable
                    // identity — comparing it to the container Title falsely
                    // vetoes the ordinal match and silently disables the track
                    // (the recorded eac3-joc lesson). Resolve by ordinal + id.
                    label = null,
                )
            }?.toList()
            .orEmpty()

    private fun applyPendingEmbeddedAudioSelection() {
        if (isNativePreparePendingForCurrentGeneration()) return
        val selection = pendingEmbeddedAudioSelection ?: return
        if (_playbackState.value.audioActivation != AudioActivationState.Pending(selection.target)) return
        if (audioActivationRetryJob?.isActive == true) return
        val candidates = nativeTrackCandidates(mediaPlayer.audioTracks)
        val resolution =
            resolveEmbeddedTrack(
                descriptor = selection.descriptor,
                orderedCandidates = candidates,
                trackKind = EmbeddedTrackKind.Audio,
            )
        when (resolution.result) {
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            -> {
                pendingEmbeddedAudioSelection = null
                audioActivationConfirmation.fail(selection.target, resolution.result, candidates.size)
                return
            }
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> {
                if (nativePlayingObserved || candidates.isNotEmpty()) {
                    audioActivationConfirmation.armTimeout(selection.target, candidates.size)
                }
                return
            }
            NativeTrackMappingResult.Active -> Unit
        }
        val track = resolution.candidate ?: return
        if (audioSetterAccepted) {
            if (runCatching { mediaPlayer.audioTrack }.getOrDefault(-1) == track.id) {
                confirmAudioSelection(track.id)
            } else {
                audioActivationConfirmation.armTimeout(selection.target, candidates.size)
            }
            return
        }

        val attempt = pendingEmbeddedAudioAttempts + 1
        pendingEmbeddedAudioAttempts = attempt
        val accepted = runCatching { mediaPlayer.setAudioTrack(track.id) }.getOrDefault(false)
        if (accepted) {
            audioSetterAccepted = true
            audioActivationRetryJob?.cancel()
            audioActivationRetryJob = null
            if (runCatching { mediaPlayer.audioTrack }.getOrDefault(-1) == track.id) {
                confirmAudioSelection(track.id)
            } else {
                audioActivationConfirmation.armTimeout(selection.target, candidates.size)
            }
        } else {
            if (nextActivationAttempt(attempt) == null) {
                pendingEmbeddedAudioSelection = null
                audioActivationConfirmation.fail(selection.target, NativeTrackMappingResult.Unsupported, candidates.size)
            } else {
                scheduleAudioActivationRetry(selection)
            }
        }
    }

    private fun applyPendingEmbeddedSubtitleSelection() {
        if (isNativePreparePendingForCurrentGeneration()) return
        val selection = pendingEmbeddedSubtitleSelection ?: return
        if (_playbackState.value.subtitleActivation != SubtitleActivationState.Pending(selection.target)) return
        if (subtitleActivationRetryJob?.isActive == true) return
        val candidates = nativeTrackCandidates(mediaPlayer.spuTracks)
        val resolution =
            resolveEmbeddedTrack(
                descriptor = selection.descriptor,
                orderedCandidates = candidates,
                trackKind = EmbeddedTrackKind.Subtitle,
            )
        when (resolution.result) {
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            -> {
                pendingEmbeddedSubtitleSelection = null
                subtitleActivationConfirmation.fail(
                    selection.target,
                    result = resolution.result,
                    candidateCount = candidates.size,
                )
                return
            }
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> {
                if (nativePlayingObserved || candidates.isNotEmpty()) {
                    subtitleActivationConfirmation.armTimeout(selection.target, candidates.size)
                }
                return
            }
            NativeTrackMappingResult.Active -> Unit
        }
        val track = resolution.candidate ?: return
        if (subtitleSetterAccepted) {
            if (runCatching { mediaPlayer.spuTrack }.getOrDefault(-1) == track.id) {
                confirmSubtitleSelection(track.id)
            } else {
                subtitleActivationConfirmation.armTimeout(selection.target, candidates.size)
            }
            return
        }

        val attempt = pendingEmbeddedSubtitleAttempts + 1
        pendingEmbeddedSubtitleAttempts = attempt
        val accepted = runCatching { mediaPlayer.setSpuTrack(track.id) }.getOrDefault(false)
        if (accepted) {
            subtitleSetterAccepted = true
            subtitleActivationRetryJob?.cancel()
            subtitleActivationRetryJob = null
            if (runCatching { mediaPlayer.spuTrack }.getOrDefault(-1) == track.id) {
                confirmSubtitleSelection(track.id)
            } else {
                subtitleActivationConfirmation.armTimeout(selection.target, candidates.size)
            }
        } else if (nextActivationAttempt(attempt) == null) {
            pendingEmbeddedSubtitleSelection = null
            subtitleActivationConfirmation.fail(
                selection.target,
                result = NativeTrackMappingResult.Unsupported,
                candidateCount = candidates.size,
            )
        } else {
            scheduleSubtitleActivationRetry(selection)
        }
    }

    private fun scheduleAudioActivationRetry(selection: EmbeddedAudioSelection) {
        audioActivationRetryJob?.cancel()
        val retryGeneration = generation
        audioActivationRetryJob =
            scope.launch {
                delay(ACTIVATION_RETRY_DELAY_MS)
                if (
                    released ||
                    retryGeneration != generation ||
                    pendingEmbeddedAudioSelection != selection ||
                    _playbackState.value.audioActivation != AudioActivationState.Pending(selection.target)
                ) {
                    return@launch
                }
                audioActivationRetryJob = null
                applyPendingEmbeddedAudioSelection()
            }
    }

    private fun scheduleSubtitleActivationRetry(selection: EmbeddedSubtitleSelection) {
        subtitleActivationRetryJob?.cancel()
        val retryGeneration = generation
        subtitleActivationRetryJob =
            scope.launch {
                delay(ACTIVATION_RETRY_DELAY_MS)
                if (
                    released ||
                    retryGeneration != generation ||
                    pendingEmbeddedSubtitleSelection != selection ||
                    _playbackState.value.subtitleActivation != SubtitleActivationState.Pending(selection.target)
                ) {
                    return@launch
                }
                subtitleActivationRetryJob = null
                applyPendingEmbeddedSubtitleSelection()
            }
    }

    private fun confirmAudioSelection(trackId: Int) {
        val selection = pendingEmbeddedAudioSelection ?: return
        val resolution =
            resolveEmbeddedTrack(
                descriptor = selection.descriptor,
                orderedCandidates = nativeTrackCandidates(mediaPlayer.audioTracks),
                trackKind = EmbeddedTrackKind.Audio,
            )
        val candidate = resolution.candidate ?: return
        if (candidate.id != trackId || runCatching { mediaPlayer.audioTrack }.getOrDefault(-1) != trackId) return
        audioActivationRetryJob?.cancel()
        audioActivationRetryJob = null
        pendingEmbeddedAudioSelection = null
        audioSetterAccepted = false
        audioActivationConfirmation.confirm(selection.target)
    }

    private fun confirmSubtitleSelection(trackId: Int) {
        val attachmentTarget = subtitleAttachmentTarget
        if (attachmentTarget != null) {
            selectedPostAttachmentSubtitleIds += trackId
            confirmAttachedSubtitle(attachmentTarget, trackId)
            return
        }
        val selection = pendingEmbeddedSubtitleSelection ?: return
        val resolution =
            resolveEmbeddedTrack(
                descriptor = selection.descriptor,
                orderedCandidates = nativeTrackCandidates(mediaPlayer.spuTracks),
                trackKind = EmbeddedTrackKind.Subtitle,
            )
        val candidate = resolution.candidate ?: return
        if (candidate.id != trackId || runCatching { mediaPlayer.spuTrack }.getOrDefault(-1) != trackId) return
        subtitleActivationRetryJob?.cancel()
        subtitleActivationRetryJob = null
        pendingEmbeddedSubtitleSelection = null
        subtitleSetterAccepted = false
        subtitleActivationConfirmation.confirm(selection.target)
    }

    private fun cancelActivationJobs() {
        audioActivationRetryJob?.cancel()
        audioActivationRetryJob = null
        subtitleActivationRetryJob?.cancel()
        subtitleActivationRetryJob = null
    }

    private fun updateRuntimeDiagnostics() {
        val videoTrack = runCatching { mediaPlayer.currentVideoTrack }.getOrNull()
        val stats = currentMedia?.let { media -> runCatching { media.getStats() }.getOrNull() }
        val lostPictures = stats?.lostPictures?.toLong()?.takeIf { count -> count >= 0L }
        observeDisplayedPictureDelta(
            displayedPictures = stats?.displayedPictures?.toLong()?.takeIf { count -> count >= 0L },
        )
        val droppedVideoFramesPerSecond =
            if (_playbackState.value.status != PlaybackStatus.Playing) {
                resetRuntimeDiagnosticsBaseline()
                null
            } else {
                val nowMs = SystemClock.elapsedRealtime()
                val previousCount = runtimeDiagnosticsBaselineLostPictures
                val previousTimeMs = runtimeDiagnosticsBaselineTimeMs
                val poll =
                    droppedFramePoll(
                        previousCount = previousCount,
                        previousTimeMs = previousTimeMs,
                        currentCount = lostPictures,
                        nowMs = nowMs,
                    )
                runtimeDiagnosticsBaselineLostPictures = poll.baselineCount
                runtimeDiagnosticsBaselineTimeMs = poll.baselineTimeMs
                val measurement =
                    if (
                        poll.ratePerSecond != null &&
                        previousCount != null &&
                        previousTimeMs != null &&
                        lostPictures != null
                    ) {
                        DroppedFrameMeasurement.create(
                            droppedFrames = lostPictures - previousCount,
                            intervalMs = nowMs - previousTimeMs,
                        )
                    } else {
                        null
                    }
                if (!released) {
                    measurement?.let { droppedFrameMeasurementsChannel.trySend(it) }
                }
                measurement?.ratePerSecond
            }
        val frameRate =
            videoTrack
                ?.takeIf { track -> track.frameRateNum > 0 && track.frameRateDen > 0 }
                ?.let { track -> track.frameRateNum.toDouble() / track.frameRateDen.toDouble() }
        _runtimeDiagnostics.update { current ->
            current.copy(
                videoDecoderName = videoTrack?.codec?.takeIf { codec -> codec.isNotBlank() }?.let { codec -> "libvlc:$codec" },
                videoWidth = videoTrack?.width?.takeIf { width -> width > 0 },
                videoHeight = videoTrack?.height?.takeIf { height -> height > 0 },
                videoFrameRate = frameRate,
                droppedVideoFrames = lostPictures,
                droppedVideoFramesPerSecond = droppedVideoFramesPerSecond,
                outputDroppedVideoFrames = lostPictures,
                // LibVLC's inputBitrate unit is not documented by the Android
                // 3.7.5 API. Do not expose a falsely converted bandwidth.
                bandwidthEstimateBps = null,
            )
        }
    }

    private fun resetRuntimeDiagnosticsBaseline() {
        runtimeDiagnosticsBaselineLostPictures = null
        runtimeDiagnosticsBaselineTimeMs = null
    }

    private fun resetVideoOutputBaseline() {
        displayedPicturesBaseline = null
        firstVideoOutputObservedGeneration = null
    }

    /** Emits only a positive native displayed-picture delta for this prepare epoch. */
    private fun observeDisplayedPictureDelta(displayedPictures: Long?) {
        if (released || firstVideoOutputObservedGeneration == generation) return
        val currentCount = displayedPictures ?: return
        val baseline = displayedPicturesBaseline
        if (baseline == null || currentCount < baseline) {
            displayedPicturesBaseline = currentCount
            return
        }
        if (currentCount == baseline) return

        val resumeOutputWasPending = isResumeOutputPending()
        firstVideoOutputObservedGeneration = generation
        if (resumeOutputRelockState.generation == generation) {
            resumeOutputRelockState = resumeOutputRelockState.copy(awaitingFirstOutput = false, relockConsumed = true)
        }
        resumeOutputRelockJob?.cancel()
        resumeOutputRelockJob = null
        pendingResumeOutputRelockTargetMs = null
        if (
            resumeOutputWasPending &&
            pendingSeekTargetMs == null &&
            playIntent &&
            mediaPlayer.isPlaying &&
            readPositionMs() > 0L
        ) {
            publishStatus(PlaybackStatus.Playing)
        }
        videoOutputObservationsChannel.trySend(
            VideoOutputObservation(
                generation = generation,
                presented = true,
                observedAtMs = SystemClock.elapsedRealtime(),
            ),
        )
    }

    // release() flips `released` and finishes its state teardown synchronously
    // before the native teardown runs on Dispatchers.IO, so a late command must
    // return here rather than reach JNI on a player being torn down.
    private fun warnReleased(operation: PlayerOperation) {
        controllerLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Release,
                    event = PlaybackDiagnosticEvent.Rejected,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = activeBackend,
                    operation = operation,
                ),
            )
        }
    }

    private fun logFailureDiagnostic(
        stage: PlaybackDiagnosticStage,
        throwable: Throwable,
    ) {
        controllerLogger.e {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = stage,
                    event = PlaybackDiagnosticEvent.Failed,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = activeBackend,
                    exceptionType = throwable.playbackExceptionType(),
                ),
            )
        }
    }

    private fun logBackendReadinessDiagnostic(
        plan: PlaybackPlan,
        prepareSequence: Long = generation,
    ) {
        controllerLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Prepare,
                    event = PlaybackDiagnosticEvent.BackendReadiness,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = activeBackend,
                    prepareSequence = prepareSequence,
                    sessionSequence = plan.diagnosticSessionSequence ?: prepareSequence,
                    decoderResourcePolicy = AndroidLibVlcDecoderResourcePolicy.diagnosticPolicy,
                    decoderFrameThreads = AndroidLibVlcDecoderResourcePolicy.FRAME_THREADS,
                ),
            )
        }
    }

    private fun logEncounteredError() {
        val plan = lastPlan
        val diagnostics = _runtimeDiagnostics.value
        controllerLogger.w {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.TerminalError,
                    platform = PlaybackDiagnosticPlatform.Android,
                    backend = activeBackend,
                    errorCategory = PlaybackError.Unknown,
                    terminalOutcome = PlaybackTerminalOutcome.Failed,
                    prepareSequence = generation,
                    sessionSequence = plan?.diagnosticSessionSequence ?: generation,
                    retryAttempted = false,
                    degradationAttempted = false,
                    streamMode = plan?.streamMode,
                    sourceBitrateBps = plan?.sourceBitrateBps,
                    videoDecoderName = diagnostics.videoDecoderName,
                    runtimeVideoWidth = diagnostics.videoWidth,
                    runtimeVideoHeight = diagnostics.videoHeight,
                    runtimeVideoFrameRate = diagnostics.videoFrameRate,
                    backendDroppedVideoFrames = diagnostics.droppedVideoFrames,
                    backendDroppedVideoFramesPerSecond = diagnostics.droppedVideoFramesPerSecond,
                    outputDroppedVideoFrames = diagnostics.outputDroppedVideoFrames,
                    requestCapBitrateBps = plan?.maxStreamingBitrate,
                    effectiveTranscodeCapBitrateBps = plan?.effectiveTranscodeMaxStreamingBitrate,
                ),
            )
        }
    }
}

private val controllerLogger = diagnosticLogger(DiagnosticTag.LibVlcPlayerController)

private const val ACTIVATION_RETRY_DELAY_MS = 250L
private const val SEEK_BUFFERING_PROGRESS_PROBE_MS = 3_000L
private const val SEEK_POSITION_TOLERANCE_MS = 2_000L
internal const val SEEK_RESUME_ADVANCE_MS = 50L

internal fun hasLibVlcSeekPlaybackResumed(
    arrivalPositionMs: Long?,
    currentPositionMs: Long,
): Boolean =
    arrivalPositionMs != null &&
        currentPositionMs - arrivalPositionMs >= SEEK_RESUME_ADVANCE_MS

internal fun libVlcResumeOutputRelockSeekTarget(resumeTargetMs: Long): Long = (resumeTargetMs - STARTUP_RESYNC_REWIND_MS).coerceAtLeast(0L)

internal fun shouldApplyLibVlcNativePrepareResult(
    requestGeneration: Long,
    currentGeneration: Long,
    released: Boolean,
): Boolean = !released && requestGeneration == currentGeneration

internal data class LibVlcResumeOutputRelockState(
    val generation: Long? = null,
    val awaitingFirstOutput: Boolean = false,
    val relockConsumed: Boolean = false,
)

internal fun shouldRelockLibVlcResumeOutput(
    state: LibVlcResumeOutputRelockState,
    currentGeneration: Long,
    playIntent: Boolean,
): Boolean =
    playIntent &&
        state.generation == currentGeneration &&
        state.awaitingFirstOutput &&
        !state.relockConsumed

internal fun isLibVlcResumeOutputPending(
    state: LibVlcResumeOutputRelockState,
    currentGeneration: Long,
): Boolean =
    state.generation == currentGeneration &&
        state.awaitingFirstOutput &&
        !state.relockConsumed

// Delay after first video output before the fresh-start A/V re-lock seek, so both
// pipelines are primed when libVLC re-references its clock.
private const val STARTUP_RESYNC_DELAY_MS = 500L

// Small backward nudge so the re-lock seek target differs from the live playhead
// (an identical setTime() can no-op and skip the flush). Replays a fraction of a
// second once (segment-granular on HLS).
private const val STARTUP_RESYNC_REWIND_MS = 250L

// Bounded retry for the fresh-start re-lock: slow transcode starts can still read
// position 0 at the first attempt, so retry a few times before giving up.
private const val STARTUP_RESYNC_RETRY_MS = 300L
private const val STARTUP_RESYNC_MAX_ATTEMPTS = 8

// If video output appears within this window of the first Playing event, the media
// is already A/V-locked and the re-lock seek is skipped (no startup hitch). Well
// under the ~1-2s lag that produces audible drift, comfortably over prompt starts.
private const val STARTUP_RESYNC_MIN_LAG_MS = 400L
private const val RUNTIME_DIAGNOSTICS_POLL_MS = 1_000L

private val HANDLED_LIBVLC_EVENTS =
    setOf(
        MediaPlayer.Event.Opening,
        MediaPlayer.Event.Buffering,
        MediaPlayer.Event.Playing,
        MediaPlayer.Event.Paused,
        MediaPlayer.Event.Stopped,
        MediaPlayer.Event.EndReached,
        MediaPlayer.Event.EncounteredError,
        MediaPlayer.Event.Vout,
        MediaPlayer.Event.ESAdded,
        MediaPlayer.Event.ESSelected,
        MediaPlayer.Event.TimeChanged,
        MediaPlayer.Event.PositionChanged,
    )

private val IMPORTANT_LIBVLC_EVENTS =
    setOf(
        MediaPlayer.Event.Opening,
        MediaPlayer.Event.Playing,
        MediaPlayer.Event.Paused,
        MediaPlayer.Event.Stopped,
        MediaPlayer.Event.EndReached,
        MediaPlayer.Event.EncounteredError,
        MediaPlayer.Event.Vout,
        MediaPlayer.Event.ESAdded,
        MediaPlayer.Event.ESSelected,
        // TimeChanged/PositionChanged fire ~4x/second — excluded from the logged
        // "important" set to keep the event coroutine off the hot path.
    )

private class AndroidLibVlcTimingController(
    private val mediaPlayer: MediaPlayer,
) : LibVlcTimingController {
    private val logger = Logger.withTag("LibVlcTiming")
    private val _timingState =
        MutableStateFlow(
            PlayerTimingState(
                audio = PlayerTimingValue(support = PlayerTimingSupport.Supported),
                subtitle = PlayerTimingValue(support = PlayerTimingSupport.Supported),
            ),
        )
    override val timingState: StateFlow<PlayerTimingState> = _timingState.asStateFlow()

    override fun setOffset(
        kind: PlaybackTimingKind,
        offsetMs: Long,
    ): PlayerTimingCommandResult {
        val normalized = offsetMs.coerceIn(-PLAYBACK_TIMING_OFFSET_LIMIT_MS, PLAYBACK_TIMING_OFFSET_LIMIT_MS)
        val applied =
            runCatching {
                when (kind) {
                    PlaybackTimingKind.Audio -> mediaPlayer.setAudioDelay(normalized * 1_000L)
                    PlaybackTimingKind.Subtitle -> mediaPlayer.setSpuDelay(normalized * 1_000L)
                }
            }.getOrDefault(false)
        // Sanitized: bare integer ms. Confirms whether a stored/user offset is
        // being pushed to LibVLC (the one A/V-sync cause the timing feature owns).
        logger.i { "setOffset kind=$kind offsetMs=$normalized applied=$applied" }
        if (!applied) return PlayerTimingCommandResult.Unsupported
        _timingState.update { current ->
            when (kind) {
                PlaybackTimingKind.Audio -> current.copy(audio = current.audio.copy(offsetMs = normalized))
                PlaybackTimingKind.Subtitle -> current.copy(subtitle = current.subtitle.copy(offsetMs = normalized))
            }
        }
        return PlayerTimingCommandResult.Applied
    }

    fun clearForDiscontinuity() {
        _timingState.update { current ->
            current.copy(
                audio = current.audio.copy(offsetMs = 0L),
                subtitle = current.subtitle.copy(offsetMs = 0L),
            )
        }
    }
}
