// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

const val PLAYBACK_HEALTH_STARTUP_THRESHOLD_MS = 10_000L
const val PLAYBACK_HEALTH_LONG_BUFFERING_THRESHOLD_MS = 5_000L
const val PLAYBACK_HEALTH_CUMULATIVE_BUFFERING_THRESHOLD_MS = 10_000L
const val PLAYBACK_HEALTH_REPEATED_STALL_THRESHOLD = 3
const val PLAYBACK_HEALTH_DROPPED_FRAME_RATE_THRESHOLD = 2.0
const val PLAYBACK_HEALTH_DROPPED_FRAME_THRESHOLD_MS = 20_000L
const val PLAYBACK_HEALTH_WINDOW_MS = 60_000L
const val PLAYBACK_HEALTH_EXCLUSION_MS = 2_000L
const val PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS = 5_000L
const val PLAYBACK_HEALTH_VLC_NO_VIDEO_OUTPUT_THRESHOLD_MS = 20_000L

fun playbackHealthNoVideoOutputThresholdMs(backend: PlayerBackend): Long =
    if (backend.isVlcFamily()) {
        PLAYBACK_HEALTH_VLC_NO_VIDEO_OUTPUT_THRESHOLD_MS
    } else {
        PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS
    }

enum class PlaybackHealthSignalKind {
    SlowStartup,
    LongBuffering,
    CumulativeBuffering,
    RepeatedStalls,
    DroppedFrames,
    NoVideoOutput,
}

enum class PlaybackHealthExclusionReason {
    Seek,
    Prepare,
    Replan,
    Resume,
    BackendReplacement,
    SessionReset,
    ItemChange,
}

sealed interface PlaybackHealthSignal {
    val kind: PlaybackHealthSignalKind
    val observedAtMs: Long
    val durationMs: Long?
    val count: Int?

    data class SlowStartup(
        override val observedAtMs: Long,
        override val durationMs: Long,
    ) : PlaybackHealthSignal {
        override val kind: PlaybackHealthSignalKind = PlaybackHealthSignalKind.SlowStartup
        override val count: Int? = null
    }

    data class LongBuffering(
        override val observedAtMs: Long,
        override val durationMs: Long,
    ) : PlaybackHealthSignal {
        override val kind: PlaybackHealthSignalKind = PlaybackHealthSignalKind.LongBuffering
        override val count: Int? = null
    }

    data class CumulativeBuffering(
        override val observedAtMs: Long,
        override val durationMs: Long,
        override val count: Int,
    ) : PlaybackHealthSignal {
        override val kind: PlaybackHealthSignalKind = PlaybackHealthSignalKind.CumulativeBuffering
    }

    data class RepeatedStalls(
        override val observedAtMs: Long,
        override val count: Int,
    ) : PlaybackHealthSignal {
        override val kind: PlaybackHealthSignalKind = PlaybackHealthSignalKind.RepeatedStalls
        override val durationMs: Long? = null
    }

    data class DroppedFrames(
        override val observedAtMs: Long,
        override val durationMs: Long,
        override val count: Int,
    ) : PlaybackHealthSignal {
        override val kind: PlaybackHealthSignalKind = PlaybackHealthSignalKind.DroppedFrames
    }

    data class NoVideoOutput(
        override val observedAtMs: Long,
        val thresholdMs: Long = PLAYBACK_HEALTH_NO_VIDEO_OUTPUT_THRESHOLD_MS,
    ) : PlaybackHealthSignal {
        override val kind: PlaybackHealthSignalKind = PlaybackHealthSignalKind.NoVideoOutput
        override val durationMs: Long? = null
        override val count: Int? = null
    }
}

data class PlaybackHealthSummary(
    val generation: Long,
    val firstPlayingObserved: Boolean,
    val bufferingDurationMs: Long,
    val bufferingIntervalCount: Int,
    val stallCount: Int,
    val droppedFrameDurationMs: Long,
    val droppedFrameSampleCount: Int,
    val emittedSignals: List<PlaybackHealthSignalKind>,
    val firstVideoOutputMeasurementAvailable: Boolean = false,
    val firstVideoOutputObserved: Boolean = false,
    val postStartGuidanceShown: Boolean = false,
    val noVideoOutputGuidanceShown: Boolean = false,
    val guidancePolicy: PlaybackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Disabled,
    val guidancePublishable: Boolean = false,
    val bufferingIntervalOpenedSinceEvidenceRestart: Boolean = false,
)

/**
 * Pure playback-health state machine. All timestamps are supplied by the
 * caller; this class owns no coroutine, platform, or player-controller state.
 */
class PlaybackHealthEvaluator(
    private val windowMs: Long = PLAYBACK_HEALTH_WINDOW_MS,
    private val exclusionMs: Long = PLAYBACK_HEALTH_EXCLUSION_MS,
) {
    private data class Exclusion(
        val startMs: Long,
        val endMs: Long,
    )

    private data class BufferingInterval(
        val startMs: Long,
        var endMs: Long?,
    )

    private data class DroppedFrameSpan(
        val startMs: Long,
        val endMs: Long,
    )

    private var currentGeneration = 0L
    private var launchAtMs = 0L
    private var lastObservedAtMs = 0L
    private var lastStatus = PlaybackStatus.Idle
    private var firstPlayingAtMs: Long? = null
    private var activeBuffering: BufferingInterval? = null
    private var bufferingIntervalOpenedSinceEvidenceRestart = false
    private var droppedEvidenceFromMs = 0L
    private val exclusions = ArrayDeque<Exclusion>()
    private val bufferingIntervals = ArrayDeque<BufferingInterval>()
    private val stallEvents = ArrayDeque<Long>()
    private val droppedFrameSpans = ArrayDeque<DroppedFrameSpan>()
    private val emittedSignals = linkedSetOf<PlaybackHealthSignalKind>()

    val generation: Long
        get() = currentGeneration
    val startedAtMs: Long
        get() = launchAtMs
    val hasStartedPlaying: Boolean
        get() = firstPlayingAtMs != null
    val currentStatus: PlaybackStatus
        get() = lastStatus
    val bufferingStartedAtMs: Long?
        get() = activeBuffering?.startMs

    fun hasEmitted(kind: PlaybackHealthSignalKind): Boolean = kind in emittedSignals

    /**
     * Delay until an active post-start buffering interval can satisfy the long
     * buffering threshold. The calculation includes exclusion windows, so a
     * timer scheduled before a seek/re-plan cannot fire the warning early.
     */
    fun nextBufferingEvaluationDelayMs(atMs: Long): Long? {
        if (lastStatus != PlaybackStatus.Buffering || firstPlayingAtMs == null) return null
        val now = normalizeTimestamp(atMs)
        val interval = activeBuffering ?: return null

        fun delayUntil(
            threshold: Long,
            measured: (Long) -> Long,
        ): Long {
            var candidate = now
            repeat(MAX_THRESHOLD_SEARCH_STEPS) {
                val eligible = measured(candidate)
                if (eligible >= threshold) {
                    return (candidate - now).coerceAtLeast(0L)
                }
                candidate += (threshold - eligible).coerceAtLeast(1L)
            }
            return (candidate - now).coerceAtLeast(0L)
        }
        val delays = mutableListOf<Long>()
        if (PlaybackHealthSignalKind.LongBuffering !in emittedSignals) {
            delays +=
                delayUntil(PLAYBACK_HEALTH_LONG_BUFFERING_THRESHOLD_MS) { candidate ->
                    eligibleDuration(interval.startMs, candidate)
                }
        }
        if (PlaybackHealthSignalKind.CumulativeBuffering !in emittedSignals) {
            delays +=
                delayUntil(PLAYBACK_HEALTH_CUMULATIVE_BUFFERING_THRESHOLD_MS) { candidate ->
                    cumulativeBufferingDuration(candidate)
                }
        }
        return delays.minOrNull()
    }

    fun reset(
        generation: Long,
        launchAtMs: Long,
    ) {
        currentGeneration = generation
        this.launchAtMs = launchAtMs
        lastObservedAtMs = launchAtMs
        lastStatus = PlaybackStatus.Idle
        firstPlayingAtMs = null
        activeBuffering = null
        bufferingIntervalOpenedSinceEvidenceRestart = false
        droppedEvidenceFromMs = launchAtMs
        exclusions.clear()
        bufferingIntervals.clear()
        stallEvents.clear()
        droppedFrameSpans.clear()
        emittedSignals.clear()
    }

    /**
     * Clears evidence for the current window without restarting the playback
     * session. Session timing, startup state, status, and one-shot signal
     * latches intentionally survive this operation.
     */
    fun restartEvidenceWindow() {
        activeBuffering = null
        bufferingIntervals.clear()
        stallEvents.clear()
        droppedFrameSpans.clear()
        droppedEvidenceFromMs = maxOf(droppedEvidenceFromMs, lastObservedAtMs)
        bufferingIntervalOpenedSinceEvidenceRestart = false
        // A restart while still Buffering must REOPEN the interval at the boundary.
        // lastStatus is deliberately preserved, and onStatusChanged() returns early
        // for an unchanged status, so without this the ongoing wait would never
        // reopen: the episode would accrue no duration, arm no timer (
        // nextBufferingEvaluationDelayMs needs an active interval), and produce no
        // warning, recovery, or honest summary. A second seek while already
        // buffering takes exactly this path.
        if (lastStatus == PlaybackStatus.Buffering && firstPlayingAtMs != null) {
            activeBuffering = BufferingInterval(lastObservedAtMs, null)
            bufferingIntervalOpenedSinceEvidenceRestart = true
        }
    }

    fun markExclusion(
        atMs: Long,
        reason: PlaybackHealthExclusionReason,
    ) {
        val now = normalizeTimestamp(atMs)
        exclusions.addLast(Exclusion(now, now + exclusionMs.coerceAtLeast(0L)))
        trimToBound(exclusions)
        prune(now)
        // A dropped-frame report can arrive after a seek/reset. Its measured
        // interval is stale even when only the final two seconds are excluded.
        droppedFrameSpans.clear()
        droppedEvidenceFromMs = now + exclusionMs.coerceAtLeast(0L)
    }

    fun onStatusChanged(
        status: PlaybackStatus,
        atMs: Long,
    ): List<PlaybackHealthSignal> {
        val now = normalizeTimestamp(atMs)
        if (status == lastStatus) {
            return evaluateAt(now)
        }
        if (lastStatus == PlaybackStatus.Buffering) {
            closeBufferingInterval(now)
        }
        if (status != PlaybackStatus.Playing) {
            droppedFrameSpans.clear()
            droppedEvidenceFromMs = now
        }
        if (status == PlaybackStatus.Playing && firstPlayingAtMs == null) {
            firstPlayingAtMs = now
        }
        if (status == PlaybackStatus.Buffering) {
            activeBuffering = BufferingInterval(now, null)
            bufferingIntervalOpenedSinceEvidenceRestart = true
            if (
                firstPlayingAtMs != null &&
                lastStatus == PlaybackStatus.Playing &&
                !isExcluded(now)
            ) {
                stallEvents.addLast(now)
                trimToBound(stallEvents)
            }
        }
        lastStatus = status
        return evaluateAt(now)
    }

    fun recordDroppedFrameMeasurement(
        measurement: DroppedFrameMeasurement,
        atMs: Long,
    ): List<PlaybackHealthSignal> {
        val now = normalizeTimestamp(atMs)
        if (lastStatus != PlaybackStatus.Playing || firstPlayingAtMs == null) return emptyList()
        if (measurement.ratePerSecond < PLAYBACK_HEALTH_DROPPED_FRAME_RATE_THRESHOLD) return emptyList()
        val intervalStart = now - measurement.intervalMs
        // A native batch is one aggregate measurement. If any of its interval
        // predates the latest reset/exclusion boundary, none of the aggregate
        // can be attributed safely to the current playback conditions.
        if (intervalStart < maxOf(droppedEvidenceFromMs, firstPlayingAtMs ?: now)) return emptyList()
        if (intervalStart >= now) return emptyList()
        droppedFrameSpans.addLast(DroppedFrameSpan(intervalStart, now))
        trimToBound(droppedFrameSpans)
        return evaluateAt(now)
    }

    fun evaluateAt(atMs: Long): List<PlaybackHealthSignal> {
        val now = normalizeTimestamp(atMs)
        prune(now)
        val signals = mutableListOf<PlaybackHealthSignal>()
        if (
            firstPlayingAtMs == null &&
            lastStatus in setOf(PlaybackStatus.Loading, PlaybackStatus.Buffering) &&
            now - launchAtMs >= PLAYBACK_HEALTH_STARTUP_THRESHOLD_MS
        ) {
            emitOnce(PlaybackHealthSignalKind.SlowStartup) {
                signals += PlaybackHealthSignal.SlowStartup(now, (now - launchAtMs).coerceAtLeast(0L))
            }
        }
        if (firstPlayingAtMs != null) {
            val activeDuration = activeBufferingDuration(now)
            if (lastStatus == PlaybackStatus.Buffering && activeDuration >= PLAYBACK_HEALTH_LONG_BUFFERING_THRESHOLD_MS) {
                emitOnce(PlaybackHealthSignalKind.LongBuffering) {
                    signals += PlaybackHealthSignal.LongBuffering(now, activeDuration)
                }
            }
            val cumulativeDuration = cumulativeBufferingDuration(now)
            if (cumulativeDuration >= PLAYBACK_HEALTH_CUMULATIVE_BUFFERING_THRESHOLD_MS) {
                emitOnce(PlaybackHealthSignalKind.CumulativeBuffering) {
                    signals +=
                        PlaybackHealthSignal.CumulativeBuffering(
                            observedAtMs = now,
                            durationMs = cumulativeDuration,
                            count = bufferingIntervals.size + if (activeBuffering != null) 1 else 0,
                        )
                }
            }
            if (stallEvents.size >= PLAYBACK_HEALTH_REPEATED_STALL_THRESHOLD) {
                emitOnce(PlaybackHealthSignalKind.RepeatedStalls) {
                    signals += PlaybackHealthSignal.RepeatedStalls(now, stallEvents.size)
                }
            }
            val droppedDuration = droppedFrameDuration(now)
            if (droppedDuration >= PLAYBACK_HEALTH_DROPPED_FRAME_THRESHOLD_MS) {
                emitOnce(PlaybackHealthSignalKind.DroppedFrames) {
                    signals += PlaybackHealthSignal.DroppedFrames(now, droppedDuration, droppedFrameSpans.size)
                }
            }
        }
        return signals
    }

    fun summary(atMs: Long = lastObservedAtMs): PlaybackHealthSummary {
        val now = normalizeTimestamp(atMs)
        prune(now)
        return PlaybackHealthSummary(
            generation = currentGeneration,
            firstPlayingObserved = firstPlayingAtMs != null,
            bufferingDurationMs = cumulativeBufferingDuration(now),
            bufferingIntervalCount = bufferingIntervals.size + if (activeBuffering != null) 1 else 0,
            stallCount = stallEvents.size,
            droppedFrameDurationMs = droppedFrameDuration(now),
            droppedFrameSampleCount = droppedFrameSpans.size,
            emittedSignals = emittedSignals.toList(),
            bufferingIntervalOpenedSinceEvidenceRestart = bufferingIntervalOpenedSinceEvidenceRestart,
        )
    }

    private fun emitOnce(
        kind: PlaybackHealthSignalKind,
        emit: () -> Unit,
    ) {
        if (emittedSignals.add(kind)) emit()
    }

    private fun closeBufferingInterval(now: Long) {
        val interval = activeBuffering ?: return
        interval.endMs = now
        if (interval.startMs < now && firstPlayingAtMs != null) {
            bufferingIntervals.addLast(interval)
            trimToBound(bufferingIntervals)
        }
        activeBuffering = null
    }

    private fun activeBufferingDuration(now: Long): Long {
        val interval = activeBuffering ?: return 0L
        return eligibleDuration(interval.startMs, now)
    }

    private fun cumulativeBufferingDuration(now: Long): Long {
        val windowStart = now - windowMs
        var total = 0L
        bufferingIntervals.forEach { interval ->
            total += eligibleDuration(maxOf(interval.startMs, windowStart), interval.endMs ?: now)
        }
        activeBuffering?.let { interval ->
            total += eligibleDuration(maxOf(interval.startMs, windowStart), now)
        }
        return total.coerceAtLeast(0L)
    }

    private fun droppedFrameDuration(now: Long): Long {
        val windowStart = now - windowMs
        val orderedSpans =
            droppedFrameSpans
                .map { span ->
                    DroppedFrameSpan(
                        startMs = maxOf(span.startMs, windowStart),
                        endMs = span.endMs,
                    )
                }.sortedBy { span -> span.startMs }
        var total = 0L
        var coveredUntil = Long.MIN_VALUE
        orderedSpans.forEach { span ->
            val start = maxOf(span.startMs, coveredUntil)
            if (span.endMs > start) {
                total += eligibleDuration(start, span.endMs)
                coveredUntil = maxOf(coveredUntil, span.endMs)
            }
        }
        return total.coerceAtLeast(0L)
    }

    private fun eligibleDuration(
        rawStartMs: Long,
        rawEndMs: Long,
    ): Long {
        val start = minOf(rawStartMs, rawEndMs)
        val end = maxOf(rawStartMs, rawEndMs)
        if (start == end) return 0L
        var cursor = start
        var eligible = 0L
        exclusions
            .asSequence()
            .filter { exclusion -> exclusion.endMs > start && exclusion.startMs < end }
            .sortedBy { exclusion -> exclusion.startMs }
            .forEach { exclusion ->
                val excludedStart = maxOf(start, exclusion.startMs)
                val excludedEnd = minOf(end, exclusion.endMs)
                if (excludedEnd <= cursor) return@forEach
                if (excludedStart > cursor) eligible += excludedStart - cursor
                cursor = maxOf(cursor, excludedEnd)
            }
        return (eligible + (end - cursor).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun isExcluded(atMs: Long): Boolean = exclusions.any { exclusion -> atMs in exclusion.startMs until exclusion.endMs }

    private fun prune(now: Long) {
        val windowStart = now - windowMs
        while (exclusions.isNotEmpty() && exclusions.first().endMs <= windowStart) exclusions.removeFirst()
        while (bufferingIntervals.isNotEmpty() && (bufferingIntervals.first().endMs ?: now) <= windowStart) bufferingIntervals.removeFirst()
        while (stallEvents.isNotEmpty() && stallEvents.first() < windowStart) stallEvents.removeFirst()
        while (droppedFrameSpans.isNotEmpty() && droppedFrameSpans.first().endMs <= windowStart) droppedFrameSpans.removeFirst()
    }

    private fun <T> trimToBound(values: ArrayDeque<T>) {
        while (values.size > MAX_HEALTH_SAMPLES) values.removeFirst()
    }

    private fun normalizeTimestamp(atMs: Long): Long {
        lastObservedAtMs = maxOf(lastObservedAtMs, atMs)
        return lastObservedAtMs
    }
}

private const val MAX_THRESHOLD_SEARCH_STEPS = 8
private const val MAX_HEALTH_SAMPLES = 128
