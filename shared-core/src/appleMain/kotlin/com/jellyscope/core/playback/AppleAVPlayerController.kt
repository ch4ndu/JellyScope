// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.remote.AuthHeaderBuilder
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.playback.AudioActivationState
import com.jellyscope.core.domain.playback.DEFAULT_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.EmbeddedAudioSelection
import com.jellyscope.core.domain.playback.EmbeddedSubtitleSelection
import com.jellyscope.core.domain.playback.EmbeddedTrackKind
import com.jellyscope.core.domain.playback.LocalSubtitleKind
import com.jellyscope.core.domain.playback.MediaSelectionGroupLoadAction
import com.jellyscope.core.domain.playback.MediaSelectionGroupLoadState
import com.jellyscope.core.domain.playback.NativeTrackCandidate
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.NativeTrackResolution
import com.jellyscope.core.domain.playback.OfflinePrepareResult
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
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.PlayerOperation
import com.jellyscope.core.domain.playback.SubtitleActivationFailureReason
import com.jellyscope.core.domain.playback.SubtitleAsset
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.VideoOutputMeasurementCapabilities
import com.jellyscope.core.domain.playback.VideoOutputObservation
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.domain.playback.resolveEmbeddedTrack
import com.jellyscope.core.domain.playback.resolveMediaSelectionGroupLoadAction
import com.jellyscope.core.domain.playback.sumValidDroppedFrames
import com.jellyscope.core.security.CredentialOriginGuard
import io.ktor.http.HttpHeaders
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeSubtitle
import platform.AVFoundation.AVMediaTypeText
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataCommonKeyTitle
import platform.AVFoundation.AVMetadataItem
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemAccessLogEvent
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemNewAccessLogEntryNotification
import platform.AVFoundation.AVPlayerItemNewErrorLogEntryNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.accessLog
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.asset
import platform.AVFoundation.commonKey
import platform.AVFoundation.commonMetadata
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.error
import platform.AVFoundation.errorLog
import platform.AVFoundation.loadMediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.loadTracksWithMediaType
import platform.AVFoundation.loadValuesAsynchronouslyForKeys
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.playbackBufferEmpty
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.selectedMediaOptionInMediaSelectionGroup
import platform.AVFoundation.setAutomaticallyWaitsToMinimizeStalling
import platform.AVFoundation.setRate
import platform.AVFoundation.status
import platform.AVFoundation.stringValue
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeContainsTime
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.atomics.AtomicLong
import kotlin.math.abs

/**
 * AVPlayer-backed [PlayerController] for the Apple family (iOS/tvOS). Playback
 * state is polled from the player on a periodic time observer (~500ms), a
 * wall-clock state poll, and
 * end/failure notifications — this avoids Kotlin/Native KVO, which cannot
 * override `observeValueForKeyPath` on an NSObject subclass. play/pause/seek/
 * prepare also push a fresh state immediately so the UI reacts without waiting
 * a tick.
 */
class AppleAVPlayerController(
    private val session: Session,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val clientInfo: ClientInfo,
    private val stateScope: CoroutineScope,
    private val localSubtitleFileStore: LocalSubtitleFileStore? = null,
    private val diagnosticPlatform: PlaybackDiagnosticPlatform,
) : PlayerController,
    PlaybackAudioSessionHandler {
    private val player = AVPlayer()
    private val notificationCenter = NSNotificationCenter.defaultCenter
    private val credentialOriginGuard = CredentialOriginGuard(session.serverUrl)

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
    override val platformPlayer: Any?
        get() = player
    override val activeBackend: PlayerBackend = PlayerBackend.AVPlayer
    private val subtitleActivationConfirmation =
        SubtitleActivationConfirmation(
            scope = stateScope,
            currentState = { _playbackState.value.subtitleActivation },
            publish = { activation ->
                emitPlaybackState { current -> current.copy(subtitleActivation = activation) }
            },
            platform = diagnosticPlatform,
        )
    private val audioActivationConfirmation =
        AudioActivationConfirmation(
            scope = stateScope,
            platform = diagnosticPlatform,
            currentState = { _playbackState.value.audioActivation },
            publish = { activation ->
                emitPlaybackState { current -> current.copy(audioActivation = activation) }
            },
            onActivated = ::releasePlayIntentAfterAudioActivation,
        )

    // AVPlayer wedges on transcode seeks to not-yet-produced segments (drops the variant
    // after -12889 timeouts); restart the transcode at the target instead. See PlayerController.
    override val transcodeSeekRestartsStream: Boolean = true

    override val playbackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities(
            hasReliableBufferingTransitions = true,
            hasDroppedFrameMeasurements = false,
            hasReliableFirstVideoOutput = true,
        )
    override val videoOutputMeasurementCapabilities = VideoOutputMeasurementCapabilities.ReadyForDisplayBridge

    private var released = false
    private var timeObserver: Any? = null
    private var statePollingJob: Job? = null
    private val notificationObservers = mutableListOf<Any>()
    private var lastPlan: PlaybackPlan? = null
    private val trackResolutionDiagnostics = TrackResolutionDiagnosticGate()
    private var requestedPlaybackSpeed = DEFAULT_PLAYBACK_SPEED

    private var playWhenReady = false

    // Play intent captured when a system audio interruption begins, so the
    // ended callback can honor it together with iOS's shouldResume hint.
    private val interruptionIntent = PlaybackInterruptionIntent()
    private var playbackStalled = false
    private var waitingStateEmitCount = 0
    private var pauseGeneration = 0L

    // Atomic: read on the main queue (async load completions) and Default (state
    // poll), bumped on the caller thread — same cross-thread rationale as `sequence`.
    private val prepareGeneration = AtomicLong(0L)
    private var firstVideoOutputObservedGeneration: Long? = null
    private var pendingEmbeddedAudioSelection: EmbeddedAudioSelection? = null
    private var pendingEmbeddedSubtitleSelection: EmbeddedSubtitleSelection? = null
    private var selectedAudioOption: AVMediaSelectionOption? = null
    private var selectedSubtitleOption: AVMediaSelectionOption? = null
    private var expectedSubtitleTarget: com.jellyscope.core.domain.playback.SubtitleActivationTarget? = null
    private var initialAudioGate = false
    private var externalSubtitleTrackInserted = false

    // Set while prepare() is waiting for the asynchronous sidecar-composition load:
    // the player deliberately has no item in that window (main-queue only, like the
    // rest of the prepare/stop/release path).
    private var awaitingSidecarComposition = false
    private var runtimeDiagnosticsPeriodicTick = true
    private var audioGroupLoad: GroupLoad = GroupLoad.NotLoaded
    private var subtitleGroupLoad: GroupLoad = GroupLoad.NotLoaded
    private var videoTracksLoadStarted = false
    private var loadedVideoTracks: List<AVAssetTrack>? = null
    private val offlineLeaseHolder = OfflineArtifactLeaseHolder()

    // AVFoundation sidecar composition loads may continue after stop()/replacement. Detached
    // leases stay here until every outstanding load callback has completed and the old item has
    // actually been replaced.
    private val deferredOfflineLeaseReleases = mutableListOf<OfflineArtifactLease>()
    private var pendingSidecarLoads = 0
    private var offlinePath: String? = null
    private var offlineSidecarPath: String? = null
    private var offlineArtifactResolver: OfflineArtifactResolver? = null

    /** Platform DI seam; the public controller constructor remains remote-compatible. */
    internal fun setOfflineArtifactResolver(resolver: OfflineArtifactResolver) {
        offlineArtifactResolver = resolver
    }

    private fun deferOfflineLeaseRelease(lease: OfflineArtifactLease?) {
        lease?.let(deferredOfflineLeaseReleases::add)
    }

    private fun releaseDeferredOfflineLeasesIfIdle() {
        if (pendingSidecarLoads != 0) return
        deferredOfflineLeaseReleases
            .toList()
            .also { deferredOfflineLeaseReleases.clear() }
            .forEach(OfflineArtifactLease::release)
    }

    private sealed interface GroupLoad {
        data object NotLoaded : GroupLoad

        data object Loading : GroupLoad

        data class Loaded(
            val group: AVMediaSelectionGroup?,
        ) : GroupLoad
    }

    // While a seek / re-prepare is landing, the raw player time briefly reads 0
    // (fresh item) or the old position; report this target until the player
    // actually reaches it so the seek bar doesn't flash to 0/end.
    private var pendingSeekTargetMs: Long? = null
    private val sequence = AtomicLong(0L)

    init {
        installPeriodicTimeObserver()
        startStatePolling()
    }

    override suspend fun prepareOffline(plan: PlaybackPlan): OfflinePrepareResult {
        // Apple does not provide a supported contract for reopening an
        // app-authored playlist/segment directory through AVPlayer. Converted
        // offline HLS is therefore a VLCKit-only session; fail before taking
        // an artifact lease or constructing an AVURLAsset so a wrong-backend
        // caller cannot turn a local package into a raw AVPlayer URL.
        if (plan.offlineArtifactKind == DownloadArtifactKind.LocalHlsPackage) {
            return OfflinePrepareResult.Unavailable(
                PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit),
            )
        }
        if (released) return OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.AVPlayer))
        val artifactResolver =
            offlineArtifactResolver
                ?: return OfflinePrepareResult.Unavailable(PlaybackError.OfflineArtifactUnavailable)
        stop()
        val lease =
            when (val resolution = artifactResolver.acquireForOfflinePlan(plan, session.accountIdentity())) {
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
            deferOfflineLeaseRelease(offlineLeaseHolder.detach())
            releaseDeferredOfflineLeasesIfIdle()
            OfflinePrepareResult.Unavailable(PlaybackError.OfflinePlayerUnavailable(PlayerBackend.AVPlayer))
        }
    }

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) {
        if (released) return warnReleased(PlayerOperation.Prepare)
        if (
            plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline &&
            plan.offlineArtifactKind == DownloadArtifactKind.LocalHlsPackage
        ) {
            // Keep this synchronous seam fail-closed as well. The normal
            // PlayerViewModel path uses prepareOffline(), but a direct retry
            // or platform caller must never hand AVFoundation an app-authored
            // local HLS directory.
            _playbackState.update { current ->
                current.copy(
                    status = PlaybackStatus.Failed,
                    error = PlaybackError.OfflinePlayerUnavailable(PlayerBackend.VlcKit),
                )
            }
            return
        }
        // A retry/replacement can arrive while an earlier sidecar composition is still loading.
        // Detach the outgoing lease now, but keep the lease installed by
        // prepareOffline attached to this generation. The latter may be read by
        // AVFoundation immediately after this method returns, including when no
        // sidecar composition is needed; detaching it here would make the
        // artifact deletable before the new native item has finished opening it.
        if (plan.streamMode != com.jellyscope.core.domain.playback.StreamMode.Offline) {
            deferOfflineLeaseRelease(offlineLeaseHolder.detach())
        }
        val currentPrepareEpoch = prepareGeneration.addAndFetch(1L)
        audioGroupLoad = GroupLoad.NotLoaded
        subtitleGroupLoad = GroupLoad.NotLoaded
        awaitingSidecarComposition = false
        videoTracksLoadStarted = false
        loadedVideoTracks = null
        firstVideoOutputObservedGeneration = null
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY.copy(prepareEpoch = currentPrepareEpoch)
        runtimeDiagnosticsPeriodicTick = true
        trackResolutionDiagnostics.reset()
        ensurePlaybackAudioSession()
        lastPlan = plan
        player.pause()
        // seekTo() disables wait-to-minimize-stalling to favor fast post-seek
        // recovery; that must not leak into the next prepared item (the AVPlayer
        // instance is reused across prepares), so restore the platform default.
        player.setAutomaticallyWaitsToMinimizeStalling(true)
        // Interruption intent is item-session-scoped: an interruption ending
        // after a queue switch must not resume the replacement item.
        interruptionIntent.reset()
        playWhenReady = false
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        selectedAudioOption = null
        selectedSubtitleOption = null
        expectedSubtitleTarget = plan.subtitleActivationTarget
        val initialAudioActivation = initialAudioActivationFor(plan)
        initialAudioGate = initialAudioActivation is InitialAudioActivation.AwaitNativeMapping
        audioActivationConfirmation.applyInitial(initialAudioActivation)
        externalSubtitleTrackInserted = false
        subtitleActivationConfirmation.beginForPlan(plan, subtitleAsset)
        pendingSeekTargetMs = plan.resumeSeekPositionMs()
        requestedPlaybackSpeed = plan.playbackSpeed
        playbackStalled = false
        waitingStateEmitCount = 0

        emitPlaybackState(reason = "prepare-loading") { current ->
            current.copy(
                status = PlaybackStatus.Loading,
                positionMs = plan.clampedStartPositionMs(),
                durationMs = null,
                bufferedPositionMs = 0L,
                playbackSpeed = plan.playbackSpeed,
                subtitleStyle = plan.subtitleStyle,
                error = null,
            )
        }

        val offline = plan.streamMode == com.jellyscope.core.domain.playback.StreamMode.Offline
        val url =
            if (offline) {
                val path = offlinePath ?: return failPlayback("AVPlayer offline artifact is unavailable")
                NSURL.fileURLWithPath(path)
            } else {
                val streamUrlValue =
                    plan.streamUrl.takeIf { streamUrl -> streamUrl.isNotBlank() }
                        ?: return failPlayback("AVPlayer prepare failed because the stream URL was invalid")
                // AVFoundation exposes no supported per-asset redirect hook. Apply the
                // shared decision to the initial asset URL without claiming later hops
                // can be re-evaluated here.
                val resourceDecision = credentialOriginGuard.decideResourceCredentials(streamUrlValue)
                NSURL(string = resourceDecision.sanitizedUrl)
                    ?: return failPlayback("AVPlayer prepare failed because the stream URL was invalid")
            }

        val asset =
            runCatching {
                AVURLAsset(
                    uRL = url,
                    options =
                        if (!offline && credentialOriginGuard.decideResourceCredentials(plan.streamUrl).attachCredentials) {
                            assetOptions()
                        } else {
                            null
                        },
                )
            }.getOrElse { throwable ->
                failPlayback("AVPlayer prepare failed while creating the player item", throwable)
                return
            }
        val sidecarSubtitleAsset =
            if (offline) {
                offlineSidecarPath?.let { path -> AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null) }
            } else {
                subtitleAsset?.let { subtitle -> createSidecarSubtitleAsset(subtitle) }
            }
        if (sidecarSubtitleAsset == null) {
            installPreparedItem(
                plan = plan,
                subtitleAsset = subtitleAsset,
                playbackAsset = asset,
                sidecarTrackInserted = false,
            )
            return
        }

        // Assembling the sidecar composition reads the remote asset's track list and
        // every inserted track's timeRange (plus preferredTransform on iOS) —
        // synchronous property loads that block until they are fetched over the
        // network. Load them asynchronously and install the item in the completion.
        // The outgoing item is detached now so a poll cannot map it under this
        // prepare's generation, and updateStateFromPlayer holds the Loading publish
        // above until the composition resolves.
        val generation = prepareGeneration.load()
        clearItemNotifications()
        player.replaceCurrentItemWithPlayerItem(null)
        awaitingSidecarComposition = true
        pendingSidecarLoads += 1
        loadSidecarComposition(asset, sidecarSubtitleAsset, generation) { composition ->
            if (!released && prepareGeneration.load() == generation) {
                awaitingSidecarComposition = false
            }
            pendingSidecarLoads = (pendingSidecarLoads - 1).coerceAtLeast(0)
            releaseDeferredOfflineLeasesIfIdle()
            if (released || prepareGeneration.load() != generation) return@loadSidecarComposition
            installPreparedItem(
                plan = plan,
                subtitleAsset = subtitleAsset,
                playbackAsset = composition ?: asset,
                sidecarTrackInserted = composition != null,
            )
        }
    }

    override fun recordVideoOutputObservation(
        generation: Long,
        observedAtMs: Long,
    ) {
        if (
            !released &&
            generation == prepareGeneration.load() &&
            firstVideoOutputObservedGeneration != generation
        ) {
            firstVideoOutputObservedGeneration = generation
            videoOutputObservationsChannel.trySend(
                VideoOutputObservation(
                    generation = generation,
                    presented = true,
                    observedAtMs = observedAtMs,
                ),
            )
        }
    }

    /** Installs the prepared item on main; the sole item-install path for [prepare]. */
    private fun installPreparedItem(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
        playbackAsset: AVAsset,
        sidecarTrackInserted: Boolean,
    ) {
        val item =
            runCatching { createPlayerItem(playbackAsset, plan.subtitleStyle) }
                .getOrElse { throwable ->
                    failPlayback("AVPlayer prepare failed while creating the player item", throwable)
                    return
                }

        externalSubtitleTrackInserted = sidecarTrackInserted
        val target = plan.subtitleActivationTarget
        if (
            target?.kind == LocalSubtitleKind.ExternalText &&
            subtitleAsset != null &&
            !externalSubtitleTrackInserted
        ) {
            subtitleActivationConfirmation.fail(
                target,
                reason = SubtitleActivationFailureReason.AvfoundationSidecarCompositionUnavailable,
            )
        }
        clearItemNotifications()
        observeItemNotifications(item)

        runCatching {
            player.replaceCurrentItemWithPlayerItem(item)
            releaseDeferredOfflineLeasesIfIdle()
            loadVideoTracksIfNeeded()
            selectMediaOption(characteristic = AVMediaCharacteristicLegible, ordinal = null)
            updatePendingAudioActivation(item)
            updatePendingSubtitleActivation(item)
            // pendingSeekTargetMs is prepare()'s start position, or a seek requested
            // while the composition was loading (the player had no item to take it).
            player.seekToTime((pendingSeekTargetMs ?: plan.clampedStartPositionMs()).toCMTime())
            updateStateFromPlayer()
        }.onFailure { throwable ->
            failPlayback("AVPlayer prepare failed while starting playback", throwable)
        }
    }

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) {
        if (released) return warnReleased(PlayerOperation.SelectEmbeddedAudio)
        pendingEmbeddedAudioSelection = selection
        if ((_playbackState.value.audioActivation as? AudioActivationState.Pending)?.target != selection.target) {
            audioActivationConfirmation.begin(selection.target)
        }
        updatePendingAudioActivation(player.currentItem)
    }

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) {
        if (released) return warnReleased(PlayerOperation.SelectEmbeddedSubtitle)
        pendingEmbeddedSubtitleSelection = selection
        if (selection == null) {
            expectedSubtitleTarget = null
            selectedSubtitleOption = null
            selectMediaOption(characteristic = AVMediaCharacteristicLegible, ordinal = null)
            subtitleActivationConfirmation.clear()
            return
        }
        expectedSubtitleTarget = selection.target
        subtitleActivationConfirmation.begin(selection.target)
        updatePendingSubtitleActivation(player.currentItem)
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return warnReleased(PlayerOperation.SetPlaybackSpeed)
        requestedPlaybackSpeed = speed
        if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) {
            player.setRate(speed)
        }
        emitPlaybackState { current -> current.copy(playbackSpeed = speed) }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        if (released) return warnReleased(PlayerOperation.SetSubtitleStyle)
        player.currentItem?.applySubtitleStyle(style)
        emitPlaybackState { current -> current.copy(subtitleStyle = style) }
    }

    override fun play() {
        if (released) return warnReleased(PlayerOperation.Play)
        playWhenReady = true
        if (!resumeNativePlaybackIfAudioReady()) {
            updateStateFromPlayer()
            return
        }
        updateStateFromPlayer()
    }

    override fun pause() {
        if (released) return warnReleased(PlayerOperation.Pause)
        interruptionIntent.revoke()
        pausePreservingInterruptionIntent()
    }

    private fun pausePreservingInterruptionIntent() {
        playWhenReady = false
        pauseGeneration += 1L
        player.pause()
        updateStateFromPlayer(statusOverride = PlaybackStatus.Paused)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return warnReleased(PlayerOperation.SeekTo)
        val seekSequence = sequence.addAndFetch(1L)
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        pendingSeekTargetMs = targetPositionMs
        val wasPlaying =
            playWhenReady &&
                (
                    player.timeControlStatus == AVPlayerTimeControlStatusPlaying ||
                        _playbackState.value.status == PlaybackStatus.Playing ||
                        _playbackState.value.status == PlaybackStatus.Buffering
                )
        val pauseGenerationAtStart = pauseGeneration
        val tolerance = SEEK_TOLERANCE_SECONDS.toCMTimeSeconds()

        logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.SeekStarted)
        // Keep startup defaults intact; after an explicit seek, favor faster recovery over a fuller buffer.
        player.setAutomaticallyWaitsToMinimizeStalling(false)
        emitPlaybackState(reason = "seek-start") { current ->
            current.copy(
                status = PlaybackStatus.Buffering,
                positionMs = targetPositionMs,
                playbackSpeed = requestedPlaybackSpeed,
            )
        }
        player.seekToTime(targetPositionMs.toCMTime(), tolerance, tolerance) { finished ->
            logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.SeekCompleted)
            if (!finished || released || sequence.load() != seekSequence) return@seekToTime
            // AVFoundation delivers this completion on its own queue. Resume the
            // player mutation and updateStateFromPlayer on main so the
            // media-selection group-load state machine stays serialized with
            // prepare()/stop()/release() (see startStatePolling). Both guards are
            // re-checked after the hop: a newer seek, a pause, a prepare or a stop
            // can land between the native completion and this block.
            dispatch_async(dispatch_get_main_queue()) {
                if (released || sequence.load() != seekSequence) return@dispatch_async
                if (wasPlaying && pauseGeneration == pauseGenerationAtStart) {
                    resumeNativePlaybackIfAudioReady()
                }
                updateStateFromPlayer()
            }
        }
    }

    override fun stop() {
        if (released) return warnReleased(PlayerOperation.Stop)
        val hadPendingSidecarLoad = pendingSidecarLoads > 0
        prepareGeneration.addAndFetch(1L)
        firstVideoOutputObservedGeneration = null
        playWhenReady = false
        interruptionIntent.reset()
        initialAudioGate = false
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        selectedAudioOption = null
        selectedSubtitleOption = null
        expectedSubtitleTarget = null
        audioActivationConfirmation.clear()
        externalSubtitleTrackInserted = false
        awaitingSidecarComposition = false
        subtitleActivationConfirmation.clear()
        player.pause()
        clearItemNotifications()
        player.replaceCurrentItemWithPlayerItem(null)
        deferOfflineLeaseRelease(offlineLeaseHolder.detach())
        if (!hadPendingSidecarLoad) releaseDeferredOfflineLeasesIfIdle()
        offlinePath = null
        offlineSidecarPath = null
        playbackStalled = false
        waitingStateEmitCount = 0
        pendingSeekTargetMs = null
        emitPlaybackState(reason = "stop") { current ->
            current.copy(
                status = PlaybackStatus.Idle,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
                error = null,
            )
        }
    }

    override fun retry() {
        if (released) return warnReleased(PlayerOperation.Retry)
        val embeddedSelection = pendingEmbeddedSubtitleSelection
        val audioSelection = pendingEmbeddedAudioSelection
        val shouldPlay = playWhenReady
        lastPlan?.let { plan ->
            val selectionDecision =
                retrySelectionDecision(
                    audioSelection = audioSelection,
                    subtitleIntent =
                        embeddedSelection
                            ?.let(RetrySubtitleIntent::Selection)
                            ?: RetrySubtitleIntent.Unspecified,
                    planSubtitleTarget = plan.subtitleActivationTarget,
                )
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
    }

    override fun release() {
        if (released) return
        val hadPendingSidecarLoad = pendingSidecarLoads > 0
        prepareGeneration.addAndFetch(1L)
        firstVideoOutputObservedGeneration = null
        videoOutputObservationsChannel.close()
        playWhenReady = false
        interruptionIntent.reset()
        initialAudioGate = false
        pendingEmbeddedAudioSelection = null
        pendingEmbeddedSubtitleSelection = null
        selectedAudioOption = null
        selectedSubtitleOption = null
        expectedSubtitleTarget = null
        audioActivationConfirmation.clear()
        externalSubtitleTrackInserted = false
        awaitingSidecarComposition = false
        subtitleActivationConfirmation.clear()
        released = true
        _runtimeDiagnostics.value = PlaybackRuntimeDiagnostics.EMPTY
        stopStatePolling()
        removePeriodicTimeObserver()
        clearItemNotifications()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        deferOfflineLeaseRelease(offlineLeaseHolder.detach())
        if (!hadPendingSidecarLoad) releaseDeferredOfflineLeasesIfIdle()
        PlaybackAudioSession.release(owner = this)
        offlinePath = null
        offlineSidecarPath = null
    }

    private fun warnReleased(operation: PlayerOperation) {
        logDiagnostic(
            PlaybackDiagnosticStage.Release,
            PlaybackDiagnosticEvent.Rejected,
            operation = operation,
        )
    }

    private fun ensurePlaybackAudioSession() {
        // Session configuration/activation runs off-main inside the shared
        // coordinator (the first setActive after launch can block for 100s of
        // ms); calling on every prepare keeps this controller registered as the
        // current interruption handler.
        PlaybackAudioSession.activate(owner = this, platform = diagnosticPlatform)
    }

    override fun onAudioSessionInterruptionBegan() {
        if (released) return
        interruptionIntent.onInterruptionBegan(playWhenReady)
        pausePreservingInterruptionIntent()
    }

    override fun onAudioSessionInterruptionEnded(shouldResume: Boolean) {
        if (released) return
        val resume = interruptionIntent.onInterruptionEnded(shouldResume)
        if (resume) play()
    }

    override fun onAudioSessionOutputRouteLost() {
        if (released) return
        pause()
    }

    private fun failPlayback(
        message: String,
        throwable: Throwable? = null,
    ) {
        logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Failed, throwable)
        logPlayerDiagnostics(reason = "fail-playback")
        player.replaceCurrentItemWithPlayerItem(null)
        awaitingSidecarComposition = false
        deferOfflineLeaseRelease(offlineLeaseHolder.detach())
        releaseDeferredOfflineLeasesIfIdle()
        offlinePath = null
        offlineSidecarPath = null
        updateStateFromPlayer(
            statusOverride = PlaybackStatus.Failed,
            errorOverride = classifyPlaybackError(message = message, throwable = throwable),
        )
    }

    private fun observeItemNotifications(item: AVPlayerItem) {
        notificationObservers +=
            notificationCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = NSOperationQueue.mainQueue,
            ) {
                logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Ended)
                updateStateFromPlayer(statusOverride = PlaybackStatus.Completed)
            }
        notificationObservers +=
            notificationCenter.addObserverForName(
                name = AVPlayerItemFailedToPlayToEndTimeNotification,
                `object` = item,
                queue = NSOperationQueue.mainQueue,
            ) {
                logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Failed)
                logPlayerDiagnostics(reason = "failed-to-end-notification")
                updateStateFromPlayer(
                    statusOverride = PlaybackStatus.Failed,
                    errorOverride = classifyPlaybackError(message = "AVPlayerItem failed while playing to end", item = item),
                )
            }
        notificationObservers +=
            notificationCenter.addObserverForName(
                name = AVPlayerItemPlaybackStalledNotification,
                `object` = item,
                queue = NSOperationQueue.mainQueue,
            ) {
                playbackStalled = true
                logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.Stalled)
                logPlayerDiagnostics(reason = "stall-notification")
                val statusOverride =
                    if (item.status == AVPlayerItemStatusFailed) {
                        PlaybackStatus.Failed
                    } else {
                        PlaybackStatus.Buffering
                    }
                updateStateFromPlayer(
                    statusOverride = statusOverride,
                    errorOverride =
                        if (statusOverride == PlaybackStatus.Failed) {
                            classifyPlaybackError(message = "AVPlayerItem playback stalled and failed", item = item)
                        } else {
                            null
                        },
                )
            }
        notificationObservers +=
            notificationCenter.addObserverForName(
                name = AVPlayerItemNewErrorLogEntryNotification,
                `object` = item,
                queue = NSOperationQueue.mainQueue,
            ) {
                logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.ErrorLog)
                logPlayerDiagnostics(reason = "new-error-log-entry")
                updateStateFromPlayer()
            }
        notificationObservers +=
            notificationCenter.addObserverForName(
                name = AVPlayerItemNewAccessLogEntryNotification,
                `object` = item,
                queue = NSOperationQueue.mainQueue,
            ) {
                updateRuntimeDiagnostics(item)
            }
    }

    private fun clearItemNotifications() {
        notificationObservers.forEach { observer -> notificationCenter.removeObserver(observer) }
        notificationObservers.clear()
    }

    private fun installPeriodicTimeObserver() {
        if (timeObserver != null) return
        runCatching {
            timeObserver =
                player.addPeriodicTimeObserverForInterval(
                    interval = POSITION_OBSERVER_INTERVAL_SECONDS.toCMTimeSeconds(),
                    queue = dispatch_get_main_queue(),
                ) {
                    updateStateFromPlayer()
                    if (runtimeDiagnosticsPeriodicTick) {
                        updateRuntimeDiagnostics(player.currentItem)
                    }
                    runtimeDiagnosticsPeriodicTick = !runtimeDiagnosticsPeriodicTick
                }
        }.onFailure { throwable ->
            logDiagnostic(PlaybackDiagnosticStage.TimeObserver, PlaybackDiagnosticEvent.Failed, throwable)
        }
    }

    private fun startStatePolling() {
        if (statePollingJob != null) return
        statePollingJob =
            stateScope.launch {
                while (!released) {
                    // F4: poll fast only while active (buffering/seek recovery needs it);
                    // slow the idle/paused/completed churn. Never stops, so transitions
                    // are still picked up (and play/pause/seek/prepare emit directly).
                    delay(pollIntervalForStatus(_playbackState.value.status))
                    // Marshal the poll's state read + media-selection load/mapping onto
                    // the main queue so the load state machine (NotLoaded→Loading→Loaded,
                    // generation/current-item reads, cache writes) is serialized with
                    // prepare()/stop()/release() and the async load completions — all of
                    // which run on main (AVPlayer requires main-thread mutation anyway).
                    // Without this, a Default-thread poll could interleave with a
                    // concurrent prepare() and load one item's group under another's
                    // session, or resurrect stale Loading/videoTracksLoadStarted state.
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!released) updateStateFromPlayer()
                    }
                }
            }
    }

    private fun pollIntervalForStatus(status: PlaybackStatus): Long =
        when (status) {
            PlaybackStatus.Playing, PlaybackStatus.Buffering, PlaybackStatus.Loading -> STATE_POLL_INTERVAL_MS
            else -> IDLE_STATE_POLL_INTERVAL_MS
        }

    private fun stopStatePolling() {
        statePollingJob?.cancel()
        statePollingJob = null
    }

    private fun removePeriodicTimeObserver() {
        timeObserver?.let { observer ->
            runCatching { player.removeTimeObserver(observer) }
                .onFailure { throwable ->
                    logDiagnostic(PlaybackDiagnosticStage.TimeObserver, PlaybackDiagnosticEvent.Failed, throwable)
                }
        }
        timeObserver = null
    }

    private fun updateStateFromPlayer(
        statusOverride: PlaybackStatus? = null,
        errorOverride: PlaybackError? = null,
    ) {
        if (released) return
        // prepare() deliberately leaves the player without an item while the sidecar
        // composition loads: there is nothing to read, and a poll landing in that
        // window must not demote prepare()'s Loading publish to Idle.
        if (awaitingSidecarComposition) return
        val item = player.currentItem
        val previous = _playbackState.value
        val durationMs = item?.safeDurationMs()
        val rawPositionMs = player.safePositionMs()
        // Hold the reported position at the seek target until the player actually
        // lands there (a fresh re-prepared item reads 0 for a moment), so the seek
        // bar doesn't flash to 0/end during a seek. Clear once within tolerance.
        val pendingTarget = pendingSeekTargetMs
        if (pendingTarget != null && abs(rawPositionMs - pendingTarget) <= SEEK_SETTLE_TOLERANCE_MS) {
            pendingSeekTargetMs = null
        }
        val positionMs =
            when {
                statusOverride == PlaybackStatus.Completed && durationMs != null -> durationMs
                pendingSeekTargetMs != null -> pendingTarget ?: rawPositionMs
                else -> rawPositionMs
            }
        if (item?.playbackLikelyToKeepUp == true && !item.playbackBufferEmpty) {
            playbackStalled = false
        }
        if (item?.status == AVPlayerItemStatusReadyToPlay) {
            updatePendingAudioActivation(item)
            updatePendingSubtitleActivation(item)
        }
        if (statusOverride == null) {
            resumePlayWhenReadyIfNeeded(
                item = item,
                positionMs = positionMs,
                durationMs = durationMs,
            )
        }

        emitPlaybackState(reason = "player-update") { current ->
            val status = resolveStatus(item, previous.status, statusOverride)
            current.copy(
                status = status,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = item?.safeBufferedPositionMs() ?: 0L,
                playbackSpeed = requestedPlaybackSpeed,
                error =
                    when {
                        errorOverride != null -> errorOverride
                        status == PlaybackStatus.Failed -> current.error ?: PlaybackError.Unknown
                        else -> null
                    },
            )
        }
    }

    private fun loadMediaSelectionGroupIfNeeded(characteristic: String?) {
        // NEVER compare AVPlayerItem wrapper identity (`player.currentItem !== item`):
        // Kotlin/Native hands back a fresh ObjC wrapper per access, so `!==` is
        // spuriously true for the SAME underlying item and would reject every load
        // kickoff/completion — audio then times out and falls back to transcode.
        // The prepare generation is the stable identity: the current item only
        // changes in prepare()/stop()/release(), each of which bumps it.
        //
        // Re-read the CURRENT item + generation here (not a caller-passed item):
        // a stale poll on Dispatchers.Default could otherwise start loading a
        // previous item's asset under the new generation and apply its group to
        // the new session. The generation double-check pins item↔generation
        // consistency against a concurrent prepare().
        if (released) return
        if (mediaSelectionGroupLoad(characteristic) != GroupLoad.NotLoaded) return

        val generation = prepareGeneration.load()
        val item = player.currentItem ?: return
        if (prepareGeneration.load() != generation) return
        setMediaSelectionGroupLoad(characteristic, GroupLoad.Loading)
        logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Waiting)
        item.asset.loadMediaSelectionGroupForMediaCharacteristic(characteristic) { group, error ->
            dispatch_async(dispatch_get_main_queue()) {
                if (released || prepareGeneration.load() != generation) return@dispatch_async
                setMediaSelectionGroupLoad(
                    characteristic,
                    GroupLoad.Loaded(group = if (error == null) group else null),
                )
                logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Resolved)
                val currentItem = player.currentItem
                when (characteristic) {
                    AVMediaCharacteristicAudible -> updatePendingAudioActivation(currentItem)
                    AVMediaCharacteristicLegible -> {
                        selectMediaOption(characteristic = AVMediaCharacteristicLegible, ordinal = null)
                        updatePendingSubtitleActivation(currentItem)
                    }
                }
            }
        }
    }

    private fun loadVideoTracksIfNeeded() {
        // Generation-guarded (not wrapper identity), current-item re-read — see
        // loadMediaSelectionGroupIfNeeded.
        if (released || videoTracksLoadStarted) return
        val generation = prepareGeneration.load()
        val item = player.currentItem ?: return
        if (prepareGeneration.load() != generation) return
        videoTracksLoadStarted = true
        item.asset.loadTracksWithMediaType(AVMediaTypeVideo) { tracks, error ->
            val videoTracks =
                if (error == null) {
                    tracks?.filterIsInstance<AVAssetTrack>().orEmpty()
                } else {
                    emptyList()
                }
            val publish = {
                dispatch_async(dispatch_get_main_queue()) {
                    if (!released && prepareGeneration.load() == generation) {
                        loadedVideoTracks = videoTracks
                        updateRuntimeDiagnostics(player.currentItem)
                    }
                }
            }
            // loadTracksWithMediaType loads the track list but NOT each track's
            // properties; reading nominalFrameRate directly warns ("accessed
            // synchronously before being loaded") and blocks other asset property
            // loads. Preload that key async, then publish so diagnostics read it
            // warning-free. Frame rate is debug-overlay only.
            val firstTrack = videoTracks.firstOrNull()
            if (firstTrack == null) {
                publish()
            } else {
                firstTrack.loadValuesAsynchronouslyForKeys(listOf("nominalFrameRate")) { publish() }
            }
        }
    }

    private fun mediaSelectionGroupForMapping(characteristic: String?): GroupLoad {
        val state = mediaSelectionGroupLoad(characteristic)
        if (state.action() == MediaSelectionGroupLoadAction.Wait) {
            loadMediaSelectionGroupIfNeeded(characteristic)
        }
        return state
    }

    private fun mediaSelectionGroupLoad(characteristic: String?): GroupLoad =
        when (characteristic) {
            AVMediaCharacteristicAudible -> audioGroupLoad
            AVMediaCharacteristicLegible -> subtitleGroupLoad
            else -> GroupLoad.NotLoaded
        }

    private fun setMediaSelectionGroupLoad(
        characteristic: String?,
        state: GroupLoad,
    ) {
        when (characteristic) {
            AVMediaCharacteristicAudible -> audioGroupLoad = state
            AVMediaCharacteristicLegible -> subtitleGroupLoad = state
        }
    }

    private fun GroupLoad.action(): MediaSelectionGroupLoadAction =
        resolveMediaSelectionGroupLoadAction(
            when (this) {
                GroupLoad.NotLoaded -> MediaSelectionGroupLoadState.NotLoaded
                GroupLoad.Loading -> MediaSelectionGroupLoadState.Loading
                is GroupLoad.Loaded ->
                    if (group == null) {
                        MediaSelectionGroupLoadState.LoadedAbsent
                    } else {
                        MediaSelectionGroupLoadState.LoadedPresent
                    }
            },
        )

    private fun updateRuntimeDiagnostics(item: AVPlayerItem?) {
        if (released || item == null) return
        loadVideoTracksIfNeeded()
        val events =
            item
                .accessLog()
                ?.events
                .orEmpty()
                .filterIsInstance<AVPlayerItemAccessLogEvent>()
        val presentation =
            item.presentationSize.useContents {
                width.toInt() to height.toInt()
            }
        val frameRate =
            loadedVideoTracks
                ?.firstOrNull()
                ?.nominalFrameRate
                ?.toDouble()
                ?.takeIf { value -> value.isFinite() && value > 0.0 }
        val bandwidthEstimate =
            events
                .lastOrNull()
                ?.observedBitrate
                ?.takeIf { value -> value.isFinite() && value > 0.0 }
                ?.toLong()
        val droppedFrames = sumValidDroppedFrames(events.map { event -> event.numberOfDroppedVideoFrames })
        _runtimeDiagnostics.update { current ->
            current.copy(
                videoWidth = presentation.first.takeIf { value -> value > 0 },
                videoHeight = presentation.second.takeIf { value -> value > 0 },
                videoFrameRate = frameRate,
                droppedVideoFrames = droppedFrames,
                outputDroppedVideoFrames = droppedFrames,
                bandwidthEstimateBps = bandwidthEstimate,
            )
        }
    }

    private fun resumePlayWhenReadyIfNeeded(
        item: AVPlayerItem?,
        positionMs: Long,
        durationMs: Long?,
    ) {
        if (!playWhenReady) return
        if (item?.status != AVPlayerItemStatusReadyToPlay) return
        val playerPaused =
            player.timeControlStatus == AVPlayerTimeControlStatusPaused &&
                player.rate == 0.0f
        if (!playerPaused || isAtEnd(positionMs = positionMs, durationMs = durationMs)) return

        resumeNativePlaybackIfAudioReady()
    }

    private fun resumeNativePlaybackIfAudioReady(): Boolean {
        if (initialAudioGate) return false
        player.play()
        if (requestedPlaybackSpeed != DEFAULT_PLAYBACK_SPEED) {
            player.setRate(requestedPlaybackSpeed)
        }
        return true
    }

    private fun isAtEnd(
        positionMs: Long,
        durationMs: Long?,
    ): Boolean {
        val duration = durationMs?.takeIf { value -> value > 0L } ?: return false
        return positionMs >= (duration - END_POSITION_TOLERANCE_MS).coerceAtLeast(0L)
    }

    private fun resolveStatus(
        item: AVPlayerItem?,
        previousStatus: PlaybackStatus,
        statusOverride: PlaybackStatus?,
    ): PlaybackStatus {
        statusOverride?.let { return it }
        if (item == null) return PlaybackStatus.Idle
        if (item.status == AVPlayerItemStatusFailed) return PlaybackStatus.Failed
        if (item.status != AVPlayerItemStatusReadyToPlay) return PlaybackStatus.Loading
        if (previousStatus == PlaybackStatus.Completed) return PlaybackStatus.Completed
        if (
            playbackStalled ||
            item.playbackBufferEmpty ||
            (!item.playbackLikelyToKeepUp && previousStatus != PlaybackStatus.Paused)
        ) {
            return PlaybackStatus.Buffering
        }

        return when (player.timeControlStatus) {
            AVPlayerTimeControlStatusPlaying -> PlaybackStatus.Playing
            AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> PlaybackStatus.Buffering
            AVPlayerTimeControlStatusPaused -> PlaybackStatus.Paused
            else -> previousStatus
        }
    }

    private fun selectMediaOption(
        characteristic: String?,
        ordinal: Int?,
    ): Boolean {
        val mediaCharacteristic = characteristic ?: return false
        val item =
            player.currentItem
                ?: run {
                    logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
                    return false
                }
        val groupLoad = mediaSelectionGroupForMapping(mediaCharacteristic)
        when (groupLoad.action()) {
            // Clearing a selection (ordinal == null) is trivially satisfied when the
            // group is absent/not-yet-loaded — there is nothing selected to clear, so
            // it is a no-op success, NOT a rejection (a file with no legible group
            // would otherwise log a spurious `mapping event=rejected` on every clear).
            MediaSelectionGroupLoadAction.Wait -> return ordinal == null
            MediaSelectionGroupLoadAction.Recover -> {
                if (ordinal == null) return true
                logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
                return false
            }
            MediaSelectionGroupLoadAction.Map -> Unit
        }
        val group = (groupLoad as GroupLoad.Loaded).group ?: return ordinal == null
        val selectedOption = item.selectedMediaOptionInMediaSelectionGroup(group)
        if (ordinal == null) {
            if (selectedOption == null) return true
            item.selectMediaOption(mediaSelectionOption = null, inMediaSelectionGroup = group)
            return item.selectedMediaOptionInMediaSelectionGroup(group) == null
        }
        val option =
            group.options.getOrNull(ordinal) as? AVMediaSelectionOption
                ?: run {
                    logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
                    return false
                }
        if (selectedOption == option) return true
        item.selectMediaOption(mediaSelectionOption = option, inMediaSelectionGroup = group)
        return item.selectedMediaOptionInMediaSelectionGroup(group) == option
    }

    private fun updatePendingAudioActivation(item: AVPlayerItem?) {
        val selection = pendingEmbeddedAudioSelection ?: return
        if (item?.status != AVPlayerItemStatusReadyToPlay) return
        val groupLoad = mediaSelectionGroupForMapping(AVMediaCharacteristicAudible)
        if (groupLoad.action() == MediaSelectionGroupLoadAction.Wait) {
            // The media-selection group is loading asynchronously (iOS 18 returns
            // nil synchronously). Arm the 3s activation deadline now so that if the
            // loader completion never fires, activation resolves to Unavailable →
            // DirectPlay-disabled recovery while initialAudioGate remains held.
            // armTimeout is idempotent and a successful
            // load re-runs this mapping, which confirm()s and cancels the deadline.
            // Use the longer LOAD-wait timeout: on-device the async asset load can
            // exceed the 3s activation deadline, and a merely-slow load must not be
            // false-failed into a needless transcode.
            audioActivationConfirmation.armTimeout(
                selection.target,
                timeoutMs = MEDIA_SELECTION_LOAD_TIMEOUT_MS,
            )
            return
        }
        val candidateCount = item.mediaSelectionCandidateCount(AVMediaCharacteristicAudible)
        if (candidateCount == 0) {
            // AVFoundation only populates an audible media-selection group when the
            // asset has ALTERNATIVE audio tracks to choose among. A direct-play file
            // with a single (default) audio track exposes an empty/absent audible
            // group — the one track plays by default. There is nothing to select and
            // nothing a transcode could fix, so the default track IS the active audio:
            // confirm it instead of falling through to Unsupported → DirectPlay
            // recovery → a needless transcode. Multi-track assets have candidateCount
            // > 0 and still go through real selection below.
            audioActivationConfirmation.confirm(selection.target)
            return
        }
        val resolution = resolveMediaSelectionOption(item, AVMediaCharacteristicAudible, selection.descriptor)
        logTrackResolution(
            kind = PlaybackDiagnosticTrackKind.Audio,
            target = selection.target,
            candidateCount = candidateCount,
            resolution = resolution,
        )
        when (resolution.result) {
            NativeTrackMappingResult.Active -> {
                val option = resolution.candidate ?: return
                val group = (groupLoad as? GroupLoad.Loaded)?.group ?: return
                item.selectMediaOption(option, group)
                selectedAudioOption = option
                if (item.selectedMediaOptionInMediaSelectionGroup(group) == selectedAudioOption) {
                    audioActivationConfirmation.confirm(selection.target)
                } else {
                    audioActivationConfirmation.armTimeout(selection.target, candidateCount)
                }
            }
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingResult.Unsupported,
            -> audioActivationConfirmation.fail(selection.target, resolution.result, candidateCount)
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingResult.Timeout,
            -> audioActivationConfirmation.armTimeout(selection.target, candidateCount)
        }
    }

    private fun releasePlayIntentAfterAudioActivation() {
        initialAudioGate = false
        if (playWhenReady && !released) {
            resumeNativePlaybackIfAudioReady()
            updateStateFromPlayer()
        }
    }

    private fun resolveMediaSelectionOption(
        item: AVPlayerItem,
        characteristic: String?,
        descriptor: com.jellyscope.core.domain.playback.PlannedEmbeddedTrack,
    ): NativeTrackResolution<AVMediaSelectionOption> {
        val mediaCharacteristic = characteristic ?: return NativeTrackResolution(null, NativeTrackMappingResult.Unsupported)
        val groupLoad = mediaSelectionGroupForMapping(mediaCharacteristic)
        val group =
            when (groupLoad.action()) {
                MediaSelectionGroupLoadAction.Wait ->
                    return NativeTrackResolution(null, NativeTrackMappingResult.NotFound)
                MediaSelectionGroupLoadAction.Recover ->
                    return NativeTrackResolution(null, NativeTrackMappingResult.Unsupported)
                MediaSelectionGroupLoadAction.Map ->
                    (groupLoad as GroupLoad.Loaded).group ?: return NativeTrackResolution(
                        null,
                        NativeTrackMappingResult.Unsupported,
                    )
            }
        val options = group.options.filterIsInstance<AVMediaSelectionOption>()
        val trackKind =
            if (mediaCharacteristic == AVMediaCharacteristicLegible) {
                EmbeddedTrackKind.Subtitle
            } else {
                EmbeddedTrackKind.Audio
            }
        return resolveEmbeddedTrack(
            descriptor = descriptor,
            orderedCandidates =
                options.map { option ->
                    NativeTrackCandidate(
                        value = option,
                        stableSourceIndex = null,
                        codec = null,
                        language = option.locale?.toString(),
                        // Comparable identity is the raw container track title
                        // for BOTH kinds — descriptors carry Jellyfin's source
                        // Title, and AVFoundation's localized displayName lives
                        // in a different namespace (it would force
                        // TitleConflict on every titled audio track).
                        label = option.comparableTrackTitle(),
                    )
                },
            trackKind = trackKind,
        )
    }

    private fun AVMediaSelectionOption.comparableTrackTitle(): String? =
        commonMetadata
            .filterIsInstance<AVMetadataItem>()
            .firstOrNull { item -> item.commonKey == AVMetadataCommonKeyTitle }
            ?.stringValue
            ?.takeIf(String::isNotBlank)

    private fun AVPlayerItem.mediaSelectionCandidateCount(characteristic: String?): Int {
        val mediaCharacteristic = characteristic ?: return 0
        val groupLoad = mediaSelectionGroupForMapping(mediaCharacteristic)
        return (groupLoad as? GroupLoad.Loaded)
            ?.group
            ?.options
            ?.filterIsInstance<AVMediaSelectionOption>()
            ?.size
            ?: 0
    }

    private fun updatePendingSubtitleActivation(item: AVPlayerItem?) {
        val target = expectedSubtitleTarget
        if (target == null) {
            subtitleActivationConfirmation.clear()
            return
        }
        val ready = item?.status == AVPlayerItemStatusReadyToPlay
        val currentItem = item ?: return
        val needsMediaSelectionGroup =
            target.kind != LocalSubtitleKind.ExternalText || externalSubtitleTrackInserted
        val groupLoad =
            if (needsMediaSelectionGroup) {
                mediaSelectionGroupForMapping(AVMediaCharacteristicLegible)
            } else {
                null
            }
        if (groupLoad?.action() == MediaSelectionGroupLoadAction.Wait) {
            // Same async-load deadline as audio (longer LOAD-wait, not the short
            // activation timeout): a legible-group load that never completes resolves
            // the pending subtitle to Unavailable instead of hanging in "pending"
            // forever. Only after ready, matching the arm condition below.
            if (ready) {
                subtitleActivationConfirmation.armTimeout(
                    target,
                    timeoutMsOverride = MEDIA_SELECTION_LOAD_TIMEOUT_MS,
                )
            }
            return
        }
        if (
            target.kind == LocalSubtitleKind.ExternalText &&
            groupLoad is GroupLoad.Loaded &&
            groupLoad.group == null
        ) {
            subtitleActivationConfirmation.fail(
                target,
                result = NativeTrackMappingResult.Unsupported,
                candidateCount = 0,
            )
            return
        }
        val active =
            when (target.kind) {
                LocalSubtitleKind.EmbeddedText,
                LocalSubtitleKind.EmbeddedBitmap,
                LocalSubtitleKind.HlsText,
                -> {
                    val selection = pendingEmbeddedSubtitleSelection?.takeIf { pending -> pending.target == target }
                    selection?.let { pending ->
                        val resolution =
                            resolveMediaSelectionOption(
                                currentItem,
                                AVMediaCharacteristicLegible,
                                pending.descriptor,
                            )
                        val candidateCount = currentItem.mediaSelectionCandidateCount(AVMediaCharacteristicLegible)
                        logTrackResolution(
                            kind = PlaybackDiagnosticTrackKind.Subtitle,
                            target = pending.target,
                            candidateCount = candidateCount,
                            resolution = resolution,
                        )
                        if (
                            resolution.result == NativeTrackMappingResult.Ambiguous ||
                            resolution.result == NativeTrackMappingResult.Unsupported
                        ) {
                            subtitleActivationConfirmation.fail(
                                pending.target,
                                result = resolution.result,
                                candidateCount = candidateCount,
                            )
                            return@let false
                        }
                        val option = resolution.candidate ?: return@let false
                        val group = (groupLoad as? GroupLoad.Loaded)?.group ?: return@let false
                        currentItem.selectMediaOption(option, group)
                        selectedSubtitleOption = option
                        currentItem.selectedMediaOptionInMediaSelectionGroup(group) == selectedSubtitleOption
                    } == true
                }
                LocalSubtitleKind.ExternalText ->
                    externalSubtitleTrackInserted &&
                        selectMediaOption(
                            characteristic = AVMediaCharacteristicLegible,
                            ordinal = 0,
                        )
            }
        if (active) {
            subtitleActivationConfirmation.confirm(target)
        } else if (ready) {
            subtitleActivationConfirmation.armTimeout(
                target,
                item?.mediaSelectionCandidateCount(AVMediaCharacteristicLegible),
            )
        }
    }

    private fun createPlayerItem(
        playbackAsset: AVAsset,
        subtitleStyle: SubtitleStyle,
    ): AVPlayerItem = AVPlayerItem(playbackAsset).apply { applySubtitleStyle(subtitleStyle) }

    /**
     * Resolves the sidecar subtitle asset without touching the network — an
     * unsupported MIME type or an unresolvable URL is rejected here, exactly as
     * before the composition load became asynchronous.
     */
    private fun createSidecarSubtitleAsset(subtitle: SubtitleAsset): AVURLAsset? {
        if (!subtitle.mimeType.isAVFoundationSidecarSubtitleMimeType()) {
            logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
            return null
        }

        val remoteSubtitleDecision =
            (subtitle as? SubtitleAsset.JellyfinRemote)
                ?.url
                ?.takeIf(String::isNotBlank)
                ?.let(credentialOriginGuard::decideResourceCredentials)
        val subtitleUrl =
            when (subtitle) {
                is SubtitleAsset.JellyfinRemote ->
                    remoteSubtitleDecision?.sanitizedUrl?.let { value -> NSURL(string = value) }
                is SubtitleAsset.LocalFile ->
                    localSubtitleFileStore?.resolvePath(subtitle.fileId)?.let { path -> NSURL.fileURLWithPath(path) }
            } ?: run {
                logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
                return null
            }
        val subtitleOptions =
            remoteSubtitleDecision
                ?.takeIf { decision -> decision.attachCredentials }
                ?.let { assetOptions() }
        return AVURLAsset(uRL = subtitleUrl, options = subtitleOptions)
    }

    private fun loadSidecarComposition(
        asset: AVURLAsset,
        sidecarSubtitleAsset: AVURLAsset,
        generation: Long,
        onResolved: (AVMutableComposition?) -> Unit,
    ) {
        val trackListLoads: List<(() -> Unit) -> Unit> =
            listOf(
                { loaded -> asset.loadValuesAsynchronouslyForKeys(listOf(ASSET_TRACKS_KEY), loaded) },
                { loaded -> sidecarSubtitleAsset.loadValuesAsynchronouslyForKeys(listOf(ASSET_TRACKS_KEY), loaded) },
            )
        awaitAsyncValueLoads(trackListLoads) {
            val mediaTracks =
                asset.loadedTracksWithMediaType(AVMediaTypeVideo) + asset.loadedTracksWithMediaType(AVMediaTypeAudio)
            val subtitleTracks =
                (
                    sidecarSubtitleAsset.loadedTracksWithMediaType(AVMediaTypeSubtitle) +
                        sidecarSubtitleAsset.loadedTracksWithMediaType(AVMediaTypeText)
                ).take(1)
            // Loading the track LIST is not enough: assembly reads each track's
            // timeRange (and preferredTransform on iOS) synchronously, which would
            // move the network block from tracksWithMediaType into insertFullTrack.
            val trackValueLoads: List<(() -> Unit) -> Unit> =
                (mediaTracks + subtitleTracks).map { track ->
                    { loaded -> track.loadValuesAsynchronouslyForKeys(track.compositionValueKeys(), loaded) }
                }
            awaitAsyncValueLoads(trackValueLoads) {
                dispatch_async(dispatch_get_main_queue()) {
                    // Always notify the caller, including a stale generation. The caller drains
                    // the detached lease only after these asset reads finish, then ignores stale
                    // composition output so it cannot install an old item over a replacement.
                    val composition =
                        if (released || prepareGeneration.load() != generation) {
                            null
                        } else {
                            buildSidecarComposition(mediaTracks, subtitleTracks)
                        }
                    onResolved(composition)
                }
            }
        }
    }

    private fun buildSidecarComposition(
        mediaTracks: List<AVAssetTrack>,
        subtitleTracks: List<AVAssetTrack>,
    ): AVMutableComposition? {
        if (mediaTracks.isEmpty() || subtitleTracks.isEmpty()) {
            logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
            return null
        }
        val composition = AVMutableComposition.composition()
        val assembled = (mediaTracks + subtitleTracks).all { track -> composition.insertFullTrack(track) }
        if (!assembled) {
            logDiagnostic(PlaybackDiagnosticStage.Mapping, PlaybackDiagnosticEvent.Rejected)
            return null
        }
        return composition
    }

    private fun AVURLAsset.loadedTracksWithMediaType(mediaType: String?): List<AVAssetTrack> =
        if (statusOfValueForKey(ASSET_TRACKS_KEY, error = null) != AVKeyValueStatusLoaded) {
            emptyList()
        } else {
            tracksWithMediaType(mediaType).filterIsInstance<AVAssetTrack>()
        }

    /**
     * Runs [onLoaded] once every start has reported completion. AVFoundation
     * delivers each completion on an arbitrary queue, so the outstanding count is
     * atomic and the caller re-dispatches to main where that matters.
     */
    private fun awaitAsyncValueLoads(
        starts: List<(() -> Unit) -> Unit>,
        onLoaded: () -> Unit,
    ) {
        if (starts.isEmpty()) {
            onLoaded()
            return
        }
        val outstanding = AtomicLong(starts.size.toLong())
        starts.forEach { start ->
            start {
                if (outstanding.addAndFetch(-1L) == 0L) onLoaded()
            }
        }
    }

    private fun assetOptions(): Map<Any?, *> =
        mapOf<Any?, Any>(
            AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY to
                mapOf(
                    HttpHeaders.Authorization to
                        AuthHeaderBuilder.build(
                            deviceName = deviceInfoProvider.deviceName,
                            deviceId = session.deviceId,
                            clientInfo = clientInfo,
                            token = session.accessToken,
                        ),
                ),
        )

    private fun emitPlaybackState(
        reason: String = "state",
        transform: (PlaybackState) -> PlaybackState,
    ) {
        if (released) return
        // P4: _playbackState.update is an atomic (thread-safe CAS) non-suspending
        // op, so update directly instead of allocating a coroutine per emit
        // (several emits/sec). Safe from any caller thread — the time observer and
        // notifications fire on the main queue, the wall-clock poll on the state
        // scope (Dispatchers.Default); all are fine for an atomic StateFlow update.
        // The transform must stay side-effect free: `update` re-runs it on CAS
        // contention, so the waiting-emit counter and the ObjC diagnostic log walks
        // are driven once, from the settled published value.
        val published = _playbackState.updateAndGet(transform)
        logPlaybackState(reason, published)
    }

    private fun AVPlayer.safePositionMs(): Long = currentTime().toMillisecondsOrNull() ?: 0L

    private fun AVPlayerItem.safeDurationMs(): Long? = duration.toMillisecondsOrNull()

    private fun AVPlayerItem.safeBufferedPositionMs(): Long {
        val current = player.currentTime()
        val loadedRange =
            loadedTimeRanges
                .asSequence()
                .mapNotNull { value -> (value as? NSValue)?.CMTimeRangeValue }
                .firstOrNull { range -> CMTimeRangeContainsTime(range, current) }
                ?: return 0L
        return CMTimeRangeGetEnd(loadedRange).toMillisecondsOrNull() ?: 0L
    }

    private fun logPlaybackState(
        reason: String,
        state: PlaybackState,
    ) {
        val waiting =
            state.status == PlaybackStatus.Buffering ||
                player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        if (waiting) {
            waitingStateEmitCount += 1
        } else {
            waitingStateEmitCount = 0
        }

        when {
            state.status == PlaybackStatus.Failed ->
                logPlayerDiagnostics(reason = "status-failed")
            waiting &&
                (
                    waitingStateEmitCount == WAITING_DIAGNOSTIC_FIRST_EMIT ||
                        waitingStateEmitCount % WAITING_DIAGNOSTIC_REPEAT_EMITS == 0
                ) ->
                logPlayerDiagnostics(reason = "waiting-persisted-$waitingStateEmitCount")
        }
    }

    private fun logPlayerDiagnostics(reason: String) {
        val item = player.currentItem
        player.error?.code?.let { code ->
            logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.ErrorLog, nativeCode = code)
        }
        item?.error?.code?.let { code ->
            logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.ErrorLog, nativeCode = code)
        }

        item
            ?.errorLog()
            ?.events
            .orEmpty()
            .filterIsInstance<AVPlayerItemErrorLogEvent>()
            .takeLast(LOG_EVENT_LIMIT)
            .forEach { event ->
                logDiagnostic(
                    PlaybackDiagnosticStage.NativePlayer,
                    PlaybackDiagnosticEvent.ErrorLog,
                    nativeCode = event.errorStatusCode,
                )
            }

        item
            ?.accessLog()
            ?.events
            .orEmpty()
            .filterIsInstance<AVPlayerItemAccessLogEvent>()
            .takeLast(LOG_EVENT_LIMIT)
            .forEach {
                logDiagnostic(PlaybackDiagnosticStage.NativePlayer, PlaybackDiagnosticEvent.AccessLog)
            }
    }

    private fun logDiagnostic(
        stage: PlaybackDiagnosticStage,
        event: PlaybackDiagnosticEvent,
        throwable: Throwable? = null,
        nativeCode: Long? = null,
        operation: PlaybackDiagnosticOperation? = null,
    ) {
        controllerLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = stage,
                    event = event,
                    platform = diagnosticPlatform,
                    exceptionType = throwable?.playbackExceptionType(),
                    nativeCode = nativeCode,
                    operation = operation,
                ),
            )
        }
    }

    private fun logTrackResolution(
        kind: PlaybackDiagnosticTrackKind,
        target: Any?,
        candidateCount: Int,
        resolution: NativeTrackResolution<AVMediaSelectionOption>,
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
        controllerLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Mapping,
                    event = PlaybackDiagnosticEvent.Resolved,
                    platform = diagnosticPlatform,
                    trackKind = kind,
                    candidateCount = candidateCount,
                    mappingResult = resolution.result,
                    mappingReason = resolution.reason,
                ),
            )
        }
    }

    private fun classifyPlaybackError(
        message: String,
        throwable: Throwable? = null,
        item: AVPlayerItem? = player.currentItem,
    ): PlaybackError {
        val errorEvents =
            item
                ?.errorLog()
                ?.events
                .orEmpty()
                .filterIsInstance<AVPlayerItemErrorLogEvent>()
                .takeLast(LOG_EVENT_LIMIT)
        val statusCodes = errorEvents.map { event -> event.errorStatusCode.toLong() }
        val text =
            (
                listOfNotNull(
                    message,
                    throwable?.toString(),
                    player.error?.toString(),
                    item?.error?.toString(),
                ) +
                    errorEvents.flatMap { event ->
                        listOfNotNull(
                            event.errorDomain?.toString(),
                            event.errorComment?.toString(),
                            event.errorStatusCode.toString(),
                        )
                    }
            ).joinToString(" ")
                .lowercase()

        return when {
            text.containsAny("drm", "fairplay", "content key", "protected content") ->
                PlaybackError.Drm
            statusCodes.any { code -> code.isAVNetworkStatusCode() } ||
                text.containsAny(
                    "network",
                    "http",
                    "timed out",
                    "timeout",
                    "connection",
                    "offline",
                    "server",
                    "unauthorized",
                    "forbidden",
                    "not found",
                    "access",
                ) -> PlaybackError.Network
            text.containsAny("audio output", "audio session", "audio route") ->
                PlaybackError.AudioOutput
            text.containsAny("decode", "decoder") ->
                PlaybackError.Decoder
            text.containsAny("unsupported", "format", "codec", "media type", "cannot open") ->
                PlaybackError.UnsupportedMedia
            else -> PlaybackError.Unknown
        }
    }

    private fun Long.toCMTime(): CValue<platform.CoreMedia.CMTime> =
        CMTimeMakeWithSeconds(coerceAtLeast(0L).toDouble() / MILLISECONDS_PER_SECOND, MILLISECOND_TIME_SCALE)

    private fun Double.toCMTimeSeconds(): CValue<platform.CoreMedia.CMTime> = CMTimeMakeWithSeconds(this, MILLISECOND_TIME_SCALE)

    private fun CValue<platform.CoreMedia.CMTime>.toMillisecondsOrNull(): Long? {
        val seconds = CMTimeGetSeconds(this)
        return seconds
            .takeIf { value -> value.isFinite() && value > 0.0 }
            ?.let { value -> (value * MILLISECONDS_PER_SECOND).toLong().coerceAtLeast(0L) }
    }
}
