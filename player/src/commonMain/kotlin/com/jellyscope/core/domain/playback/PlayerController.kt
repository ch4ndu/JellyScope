// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface PlayerController {
    val playbackState: StateFlow<PlaybackState>
    val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics>
    val droppedFrameMeasurements: Flow<DroppedFrameMeasurement>
        get() = emptyFlow()

    /** Emits positive presentation facts for the current prepare generation. */
    val videoOutputObservations: Flow<VideoOutputObservation>
        get() = emptyFlow()
    val playbackTransitionObservations: Flow<PlaybackTransitionObservation>
        get() = emptyFlow()
    val videoOutputMeasurementCapabilities: VideoOutputMeasurementCapabilities
        get() = VideoOutputMeasurementCapabilities.Unsupported
    val platformPlayer: Any?

    /** The concrete engine behind this controller; Auto means the platform did not expose one. */
    val activeBackend: PlayerBackend
        get() = PlayerBackend.Auto
    val timingController: PlayerTimingController?
        get() = null

    // When true, a seek on a transcoded stream to a position outside the already-produced
    // window must restart the transcode at the target instead of seeking in-stream. Only
    // AVPlayer (iOS) needs this: it drops the HLS variant and wedges when a not-yet-produced
    // segment times out. ExoPlayer (Android) tolerates the server reposition, so it stays
    // false there and seek behavior is unchanged.
    val transcodeSeekRestartsStream: Boolean
        get() = false

    // False when the backend cannot apply subtitle appearance to its own rendering.
    val appliesSubtitleStyle: Boolean
        get() = true

    /** Whether the output path can apply Fit/Fill/Zoom presentation changes. */
    val supportsVideoSizing: Boolean
        get() = true

    /** Facts this native controller can measure reliably; never UI policy. */
    val playbackHealthMeasurementCapabilities: PlaybackHealthMeasurementCapabilities
        get() = PlaybackHealthMeasurementCapabilities.None

    fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset? = plan.subtitleAsset,
    )

    /**
     * Starts an explicitly trusted offline file launch without exposing a filesystem path in
     * [PlaybackPlan]. Platform controllers override this to acquire the generation/account-bound
     * lease before native prepare. The default is fail-closed so an unsupported backend can never
     * accidentally treat an Offline plan as a remote URL.
     */
    suspend fun prepareOffline(plan: PlaybackPlan): OfflinePrepareResult =
        OfflinePrepareResult.Unavailable(PlaybackError.OfflineArtifactUnavailable)

    /** Supplies the shared launch duration to the matching native prepare epoch. */
    fun recordLaunchToFirstFrame(
        prepareEpoch: Long,
        durationMs: Long,
    ) = Unit

    /** Optional host bridge for platforms whose player view owns readiness. */
    fun recordVideoOutputObservation(
        generation: Long,
        observedAtMs: Long = 0L,
    ) = Unit

    /** Records explicit native-host transport intent without issuing another native command. */
    fun recordHostPlaybackIntent(
        generation: Long,
        isPlaying: Boolean,
    ): Boolean = false

    fun selectEmbeddedAudio(selection: EmbeddedAudioSelection)

    fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?)

    fun setPlaybackSpeed(speed: Float)

    fun setSubtitleStyle(style: SubtitleStyle)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun stop()

    fun retry()

    fun release()
}

sealed interface OfflinePrepareResult {
    data object Started : OfflinePrepareResult

    data class Unavailable(
        val error: PlaybackError,
    ) : OfflinePrepareResult
}

data class PlaybackHealthMeasurementCapabilities(
    val hasReliableBufferingTransitions: Boolean,
    val hasDroppedFrameMeasurements: Boolean,
    val hasReliableFirstVideoOutput: Boolean = false,
) {
    companion object {
        val None =
            PlaybackHealthMeasurementCapabilities(
                hasReliableBufferingTransitions = false,
                hasDroppedFrameMeasurements = false,
                hasReliableFirstVideoOutput = false,
            )

        val BufferingOnly =
            PlaybackHealthMeasurementCapabilities(
                hasReliableBufferingTransitions = true,
                hasDroppedFrameMeasurements = false,
                hasReliableFirstVideoOutput = false,
            )

        val BufferingAndDroppedFrames =
            PlaybackHealthMeasurementCapabilities(
                hasReliableBufferingTransitions = true,
                hasDroppedFrameMeasurements = true,
                hasReliableFirstVideoOutput = false,
            )
    }
}

data class VideoOutputObservation(
    val generation: Long,
    val presented: Boolean,
    val observedAtMs: Long,
)

data class PlaybackTransitionObservation(
    val prepareEpoch: Long,
    val transitionSequence: Long,
    val outcome: PlaybackTransitionOutcome,
    val observedAtMs: Long,
)

enum class PlaybackTransitionOutcome {
    Started,
    Presented,
    TimedOut,
}

data class VideoOutputMeasurementCapabilities(
    val isSupported: Boolean,
    val evidence: VideoOutputEvidence,
) {
    companion object {
        val Unsupported = VideoOutputMeasurementCapabilities(false, VideoOutputEvidence.Unsupported)
        val NativeFirstOutput = VideoOutputMeasurementCapabilities(true, VideoOutputEvidence.NativeFirstOutput)
        val DisplayedPictureCounter = VideoOutputMeasurementCapabilities(true, VideoOutputEvidence.DisplayedPictureCounter)
        val ReadyForDisplayBridge = VideoOutputMeasurementCapabilities(true, VideoOutputEvidence.ReadyForDisplayBridge)
    }
}

enum class VideoOutputEvidence {
    Unsupported,
    NativeFirstOutput,
    DisplayedPictureCounter,
    ReadyForDisplayBridge,
}

data class PlaybackState(
    val status: PlaybackStatus,
    val positionMs: Long,
    val durationMs: Long?,
    val bufferedPositionMs: Long,
    val currentSegment: MediaSegment? = null,
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val audioActivation: AudioActivationState = AudioActivationState.None,
    val subtitleActivation: SubtitleActivationState = SubtitleActivationState.None,
    val error: PlaybackError? = null,
    val audioUnavailable: Boolean = false,
)

sealed interface PlaybackError {
    data object AudioOutput : PlaybackError

    data object Decoder : PlaybackError

    data object Network : PlaybackError

    data object UnsupportedMedia : PlaybackError

    data object Drm : PlaybackError

    data object Unknown : PlaybackError

    /** The explicit local row/artifact cannot be used; never trigger remote fallback. */
    data object OfflineArtifactUnavailable : PlaybackError

    data class OfflinePlayerUnavailable(
        val requiredBackend: PlayerBackend,
    ) : PlaybackError {
        init {
            require(requiredBackend != PlayerBackend.Auto) { "Offline unavailable error requires a concrete backend." }
        }
    }
}

/** Stable, allowlisted diagnostic label; never rely on a runtime class or native error string. */
fun PlaybackError.diagnosticName(): String =
    when (this) {
        PlaybackError.AudioOutput -> "audio-output"
        PlaybackError.Decoder -> "decoder"
        PlaybackError.Network -> "network"
        PlaybackError.UnsupportedMedia -> "unsupported-media"
        PlaybackError.Drm -> "drm"
        PlaybackError.Unknown -> "unknown"
        PlaybackError.OfflineArtifactUnavailable -> "offline-artifact-unavailable"
        is PlaybackError.OfflinePlayerUnavailable -> "offline-player-unavailable"
    }

enum class PlaybackStatus {
    Idle,
    Loading,
    Playing,
    Paused,
    Buffering,
    Failed,
    Completed,
}
