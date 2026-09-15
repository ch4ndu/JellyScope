// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/**
 * Observational native-player data for the in-player debug overlay.
 *
 * Every field is nullable: null means the active platform or stream cannot
 * provide it. Counters are cumulative for the prepared item. Native sentinel
 * and invalid values are represented as null rather than zero; a reported zero
 * dropped-frame count therefore means the platform has diagnostics and no
 * frames have been dropped. The dropped-frame rate is the last-known value for
 * the debug overlay and is null when the platform cannot supply an interval.
 * The quality detector consumes one-shot measurements from the controller's
 * measurement flow instead. Decoder/output drops and presentation timing are
 * debug-only observations; they never feed stream planning, backend selection,
 * quality policy, or transcode decisions. Active software-decoding mode and
 * prepare identity additionally qualify the mandatory Android mpv software-playback
 * recovery dialog; they are never a failure or capability limit by themselves.
 * Bandwidth is always bits per second.
 */
data class PlaybackRuntimeDiagnostics(
    val videoDecoderName: String?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val videoFrameRate: Double?,
    val droppedVideoFrames: Long?,
    val droppedVideoFramesPerSecond: Double? = null,
    val bandwidthEstimateBps: Long?,
    val prepareEpoch: Long? = null,
    val bufferPolicy: PlaybackBufferPolicy? = null,
    val lowRamDevice: Boolean? = null,
    val targetBufferBytes: Long? = null,
    val allocatedBufferBytes: Long? = null,
    val peakAllocatedBufferBytes: Long? = null,
    val bufferedAheadMs: Long? = null,
    val nativePrepareToFirstFrameMs: Long? = null,
    val rebufferCount: Int? = null,
    val totalRebufferMs: Long? = null,
    val maxRebufferMs: Long? = null,
    val audioUnderrunCount: Int? = null,
    val maxAudioFeedGapMs: Long? = null,
    val decoderDroppedVideoFrames: Long? = null,
    val outputDroppedVideoFrames: Long? = null,
    val recentVideoRenderP95Ms: Double? = null,
    val recentPresentedFrameRate: Double? = null,
    val presentationGapCount: Long? = null,
    val presentationPath: String? = null,
    /** Raw LibVLC `MediaPlayer.Event.Buffering` cache-fill percentage. */
    val libVlcCachePercent: Float? = null,
    /** Bounded readiness facts for the active native resume/seek transition. */
    val playbackTransition: PlaybackTransitionRuntimeDiagnostics? = null,
    /** Active native decoding, never inferred from a configured preference or renderer. */
    val videoDecodingMode: PlaybackVideoDecodingMode? = null,
) {
    companion object {
        val EMPTY =
            PlaybackRuntimeDiagnostics(
                videoDecoderName = null,
                videoWidth = null,
                videoHeight = null,
                videoFrameRate = null,
                droppedVideoFrames = null,
                droppedVideoFramesPerSecond = null,
                bandwidthEstimateBps = null,
            )
    }
}

enum class PlaybackVideoDecodingMode {
    Software,
    Hardware,
    HardwareCopyBack,
}

enum class PlaybackNativeVideoFormatStage {
    DecoderCrop,
    ImportedTexture,
    ImageReader,
}

enum class PlaybackNativeVideoQueryResult {
    Success,
    Failed,
    Unavailable,
}

/**
 * Numerical native evidence. ImageReader width/height describe the buffer allocation;
 * image dimensions and mapper dimensions remain separate. DecoderCrop endpoints are
 * inclusive, while ImageReader crop right/bottom are exclusive.
 */
data class PlaybackNativeVideoFormat(
    val stage: PlaybackNativeVideoFormatStage,
    val width: Int,
    val height: Int,
    val cropTop: Int? = null,
    val cropBottom: Int? = null,
    val cropLeft: Int? = null,
    val cropRight: Int? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val mapperSourceWidth: Int? = null,
    val mapperSourceHeight: Int? = null,
    val mapperDestinationWidth: Int? = null,
    val mapperDestinationHeight: Int? = null,
    val imageWidthQuery: PlaybackNativeVideoQueryResult? = null,
    val imageHeightQuery: PlaybackNativeVideoQueryResult? = null,
    val imageCropQuery: PlaybackNativeVideoQueryResult? = null,
)

/** mpv's hwdec-current reports the active driver, "no" for software, or unavailable. */
internal fun mpvVideoDecodingMode(hardwareDecoder: String?): PlaybackVideoDecodingMode? =
    when {
        hardwareDecoder.isNullOrBlank() -> null
        hardwareDecoder == "no" -> PlaybackVideoDecodingMode.Software
        hardwareDecoder.endsWith("-copy") -> PlaybackVideoDecodingMode.HardwareCopyBack
        else -> PlaybackVideoDecodingMode.Hardware
    }

data class PlaybackTransitionRuntimeDiagnostics(
    val kind: PlaybackTransitionRuntimeKind,
    val state: PlaybackTransitionRuntimeState,
    val targetPositionMs: Long,
    val nativePositionMs: Long,
    val targetArrived: Boolean,
    val clockAdvanced: Boolean,
    val pictureAdvances: Int,
    val requiredPictureAdvances: Int,
)

enum class PlaybackTransitionRuntimeKind {
    Prepare,
    Resume,
    Seek,
}

enum class PlaybackTransitionRuntimeState {
    Pending,
    Ready,
    TimedOut,
}

enum class PlaybackBufferPolicy {
    LowRam16MiB,
    Regular16MiB,
    RegularCandidate,
}

/**
 * One dropped-frame report over the interval measured by the native player.
 * Invalid reports are rejected by [create] so a measurement cannot carry a
 * payload and rate that disagree. [ConsistentCopyVisibility] keeps the generated
 * `copy()` private too — without it, callers could rewrite the rate to something
 * the payload does not support and reintroduce exactly that desync.
 */
@ConsistentCopyVisibility
data class DroppedFrameMeasurement private constructor(
    val droppedFrames: Long,
    val intervalMs: Long,
    val ratePerSecond: Double,
) {
    companion object {
        fun create(
            droppedFrames: Long,
            intervalMs: Long,
        ): DroppedFrameMeasurement? {
            val ratePerSecond = droppedFrameRatePerSecond(droppedFrames, intervalMs) ?: return null
            return DroppedFrameMeasurement(
                droppedFrames = droppedFrames,
                intervalMs = intervalMs,
                ratePerSecond = ratePerSecond,
            )
        }
    }
}

/** Returns the cumulative AVFoundation drop count, ignoring its negative unknown sentinel. */
fun sumValidDroppedFrames(eventCounts: List<Long>): Long? =
    eventCounts
        .filter { count -> count >= 0L }
        .takeIf { counts -> counts.isNotEmpty() }
        ?.sum()

/**
 * Average dropped-frame rate over one reported interval, or null when the
 * interval cannot support a rate.
 *
 * Both Android backends report drops in bursts rather than continuously — Media3
 * notifies once per 50-frame batch and carries the accumulation duration, while
 * LibVLC exposes a cumulative counter on its own poll — so the rate is always
 * "this many frames over this measured interval", never a wall-clock sample. A
 * non-positive interval, a negative count, or a non-finite result yields null so
 * callers cannot mistake a missing measurement for a calm one.
 */
internal fun droppedFrameRatePerSecond(
    droppedFrames: Long,
    elapsedMs: Long,
): Double? {
    if (droppedFrames < 0L || elapsedMs <= 0L) return null
    val rate = droppedFrames.toDouble() / (elapsedMs.toDouble() / 1_000.0)
    return rate.takeIf { value -> value.isFinite() }
}

/**
 * One poll of a cumulative dropped-frame counter, reduced to a rate plus the
 * baseline the next poll should compare against.
 *
 * A backend that only exposes a running total (LibVLC's `lostPictures`) has to
 * derive its own interval, and every failure mode must yield null rather than a
 * misleading zero: no counter at all, a counter that went backwards (a reset or a
 * fresh media item), and a non-advancing clock. The first poll of a session can
 * only establish a baseline, so it reports no rate either.
 */
internal data class DroppedFramePoll(
    val ratePerSecond: Double?,
    val baselineCount: Long?,
    val baselineTimeMs: Long?,
)

internal fun droppedFramePoll(
    previousCount: Long?,
    previousTimeMs: Long?,
    currentCount: Long?,
    nowMs: Long,
): DroppedFramePoll {
    if (currentCount == null) return DroppedFramePoll(null, null, null)
    if (previousCount == null || previousTimeMs == null) {
        return DroppedFramePoll(null, currentCount, nowMs)
    }
    val elapsedMs = nowMs - previousTimeMs
    if (currentCount < previousCount || elapsedMs <= 0L) {
        return DroppedFramePoll(null, null, null)
    }
    return DroppedFramePoll(
        ratePerSecond = droppedFrameRatePerSecond(currentCount - previousCount, elapsedMs),
        baselineCount = currentCount,
        baselineTimeMs = nowMs,
    )
}

/**
 * One mpv poll of its two cumulative drop counters. Output drops and decoder
 * drops are separate views of the same pipeline, so a qualifying interval uses
 * the greater delta rather than summing both counters.
 */
internal data class MpvDroppedFramePoll(
    val measurement: DroppedFrameMeasurement?,
    val outputBaselineCount: Long?,
    val decoderBaselineCount: Long?,
    val baselineTimeNanos: Long?,
)

internal fun mpvDroppedFramePoll(
    previousOutputCount: Long?,
    previousDecoderCount: Long?,
    previousTimeNanos: Long?,
    currentOutputCount: Long?,
    currentDecoderCount: Long?,
    nowNanos: Long,
): MpvDroppedFramePoll {
    if (
        currentOutputCount == null ||
        currentOutputCount < 0L ||
        currentDecoderCount == null ||
        currentDecoderCount < 0L
    ) {
        return MpvDroppedFramePoll(null, null, null, null)
    }
    if (previousOutputCount == null || previousDecoderCount == null || previousTimeNanos == null) {
        return MpvDroppedFramePoll(null, currentOutputCount, currentDecoderCount, nowNanos)
    }
    val elapsedNanos = nowNanos - previousTimeNanos
    if (
        currentOutputCount < previousOutputCount ||
        currentDecoderCount < previousDecoderCount ||
        elapsedNanos <= 0L
    ) {
        return MpvDroppedFramePoll(null, null, null, null)
    }
    val elapsedMs = elapsedNanos / 1_000_000L
    if (elapsedMs <= 0L) {
        return MpvDroppedFramePoll(null, currentOutputCount, currentDecoderCount, nowNanos)
    }
    val outputDelta = currentOutputCount - previousOutputCount
    val decoderDelta = currentDecoderCount - previousDecoderCount
    return MpvDroppedFramePoll(
        measurement = DroppedFrameMeasurement.create(maxOf(outputDelta, decoderDelta), elapsedMs),
        outputBaselineCount = currentOutputCount,
        decoderBaselineCount = currentDecoderCount,
        baselineTimeNanos = nowNanos,
    )
}
