// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.math.ceil

internal data class MpvPresentationMetricsSnapshot(
    val renderP95Ms: Double?,
    val presentedFrameRate: Double?,
    val presentationGapCount: Long,
)

/**
 * Bounded, synchronized observations for the JVM-only mpv presentation bridge.
 *
 * Sorting happens only when the controller takes its low-rate diagnostics
 * snapshot. Frame callbacks only append to fixed-size rings and update counters.
 */
internal class MpvPresentationMetrics(
    private val renderCapacity: Int = MPV_RENDER_DURATION_CAPACITY,
    private val publicationCapacity: Int = MPV_PUBLICATION_CAPACITY,
) {
    private val renderDurations = LongArray(renderCapacity)
    private val publicationTimes = LongArray(publicationCapacity)
    private var renderCount = 0
    private var renderWriteIndex = 0
    private var publicationCount = 0
    private var publicationWriteIndex = 0
    private var lastPublicationNanos: Long? = null
    private var installedFrameRate: Double? = null
    private var gapCount = 0L

    init {
        require(renderCapacity > 0)
        require(publicationCapacity > 1)
    }

    @Synchronized
    fun setInstalledFrameRate(frameRate: Double?) {
        installedFrameRate = frameRate?.takeIf { value -> value.isFinite() && value > 0.0 }
    }

    @Synchronized
    fun recordRenderDuration(durationNanos: Long) {
        if (durationNanos < 0L) return
        renderDurations[renderWriteIndex] = durationNanos
        renderWriteIndex = (renderWriteIndex + 1) % renderCapacity
        renderCount = (renderCount + 1).coerceAtMost(renderCapacity)
    }

    @Synchronized
    fun recordPublication(timestampNanos: Long) {
        val previous = lastPublicationNanos
        if (previous != null && timestampNanos > previous) {
            val interval = timestampNanos - previous
            if (interval > presentationGapThresholdNanos(installedFrameRate)) {
                gapCount += 1L
            }
        }
        lastPublicationNanos = timestampNanos
        publicationTimes[publicationWriteIndex] = timestampNanos
        publicationWriteIndex = (publicationWriteIndex + 1) % publicationCapacity
        publicationCount = (publicationCount + 1).coerceAtMost(publicationCapacity)
    }

    @Synchronized
    fun resetPublicationCadence() {
        publicationCount = 0
        publicationWriteIndex = 0
        lastPublicationNanos = null
    }

    @Synchronized
    fun reset() {
        renderCount = 0
        renderWriteIndex = 0
        resetPublicationCadence()
        installedFrameRate = null
        gapCount = 0L
    }

    @Synchronized
    fun snapshot(nowNanos: Long = System.nanoTime()): MpvPresentationMetricsSnapshot {
        val sortedRenderDurations =
            List(renderCount) { index -> renderDurations[index] }
                .sorted()
        val p95Nanos =
            sortedRenderDurations
                .takeIf { values -> values.isNotEmpty() }
                ?.let { values ->
                    val index = (ceil(values.size * MPV_P95_QUANTILE).toInt() - 1).coerceIn(values.indices)
                    values[index]
                }
        val recentPublications =
            List(publicationCount) { offset ->
                val index = (publicationWriteIndex - publicationCount + offset).floorMod(publicationCapacity)
                publicationTimes[index]
            }.filter { timestamp ->
                val ageNanos = nowNanos - timestamp
                ageNanos in 0L..MPV_PRESENTED_RATE_WINDOW_NANOS
            }
        val presentedRate =
            if (recentPublications.size >= 2) {
                val elapsedNanos = recentPublications.last() - recentPublications.first()
                if (elapsedNanos > 0L) {
                    (recentPublications.size - 1).toDouble() * NANOS_PER_SECOND / elapsedNanos.toDouble()
                } else {
                    null
                }
            } else {
                null
            }
        return MpvPresentationMetricsSnapshot(
            renderP95Ms = p95Nanos?.toDouble()?.div(NANOS_PER_MILLISECOND),
            presentedFrameRate = presentedRate?.takeIf(Double::isFinite),
            presentationGapCount = gapCount,
        )
    }
}

internal fun presentationGapThresholdNanos(frameRate: Double?): Long {
    val validFrameRate =
        frameRate?.takeIf { value -> value.isFinite() && value > 0.0 }
            ?: return MPV_PRESENTATION_GAP_FALLBACK_NANOS
    val twoFrameIntervals = (2.0 * NANOS_PER_SECOND / validFrameRate).toLong()
    return (twoFrameIntervals + MPV_PRESENTATION_GAP_TOLERANCE_NANOS)
        .coerceAtLeast(MPV_PRESENTATION_GAP_MINIMUM_NANOS)
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

internal const val MPV_RENDER_DURATION_CAPACITY = 120
internal const val MPV_PUBLICATION_CAPACITY = 180
internal const val MPV_PRESENTED_RATE_WINDOW_NANOS = 2_000_000_000L
internal const val MPV_PRESENTATION_GAP_TOLERANCE_NANOS = 8_000_000L
internal const val MPV_PRESENTATION_GAP_MINIMUM_NANOS = 24_000_000L
internal const val MPV_PRESENTATION_GAP_FALLBACK_NANOS = 75_000_000L
private const val MPV_P95_QUANTILE = 0.95
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val NANOS_PER_MILLISECOND = 1_000_000.0
