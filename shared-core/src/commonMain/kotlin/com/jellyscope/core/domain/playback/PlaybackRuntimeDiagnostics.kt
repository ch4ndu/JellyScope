// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** mpv's hwdec-current reports the active driver, "no" for software, or unavailable. */
internal fun mpvVideoDecodingMode(hardwareDecoder: String?): PlaybackVideoDecodingMode? =
    when {
        hardwareDecoder.isNullOrBlank() -> null
        hardwareDecoder == "no" -> PlaybackVideoDecodingMode.Software
        hardwareDecoder.endsWith("-copy") -> PlaybackVideoDecodingMode.HardwareCopyBack
        else -> PlaybackVideoDecodingMode.Hardware
    }

/** Returns the cumulative AVFoundation drop count, ignoring its negative unknown sentinel. */
fun sumValidDroppedFrames(eventCounts: List<Long>): Long? =
    eventCounts
        .filter { count -> count >= 0L }
        .takeIf { counts -> counts.isNotEmpty() }
        ?.sum()

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
