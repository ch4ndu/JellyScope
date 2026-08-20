// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackStatus

internal data class Media3PlaybackPerformanceSnapshot(
    val prepareEpoch: Long,
    val nativePrepareToFirstFrameMs: Long?,
    val launchToFirstFrameMs: Long?,
    val rebufferCount: Int,
    val totalRebufferMs: Long,
    val maxRebufferMs: Long,
    val audioUnderrunCount: Int,
    val maxAudioFeedGapMs: Long,
    val currentAllocatedBufferBytes: Long?,
    val peakAllocatedBufferBytes: Long?,
    val currentBufferedAheadMs: Long?,
    val minBufferedAheadMs: Long?,
    val maxBufferedAheadMs: Long?,
)

internal class Media3PlaybackPerformanceTracker(
    private val elapsedRealtimeMs: () -> Long,
) {
    private var epoch = 0L
    private var prepareStartedAtMs = 0L
    private var nativePrepareToFirstFrameMs: Long? = null
    private var launchToFirstFrameMs: Long? = null
    private var lastStatus = PlaybackStatus.Idle
    private var rebufferStartedAtMs: Long? = null
    private var excludeBufferingUntilPlaying = false
    private var rebufferCount = 0
    private var totalRebufferMs = 0L
    private var maxRebufferMs = 0L
    private var audioUnderrunCount = 0
    private var maxAudioFeedGapMs = 0L
    private var currentAllocatedBufferBytes: Long? = null
    private var peakAllocatedBufferBytes: Long? = null
    private var currentBufferedAheadMs: Long? = null
    private var minBufferedAheadMs: Long? = null
    private var maxBufferedAheadMs: Long? = null

    fun begin(prepareEpoch: Long) {
        epoch = prepareEpoch
        prepareStartedAtMs = elapsedRealtimeMs()
        nativePrepareToFirstFrameMs = null
        launchToFirstFrameMs = null
        lastStatus = PlaybackStatus.Loading
        rebufferStartedAtMs = null
        excludeBufferingUntilPlaying = false
        rebufferCount = 0
        totalRebufferMs = 0L
        maxRebufferMs = 0L
        audioUnderrunCount = 0
        maxAudioFeedGapMs = 0L
        currentAllocatedBufferBytes = null
        peakAllocatedBufferBytes = null
        currentBufferedAheadMs = null
        minBufferedAheadMs = null
        maxBufferedAheadMs = null
    }

    fun onRenderedFirstFrame(prepareEpoch: Long) {
        if (prepareEpoch != epoch || nativePrepareToFirstFrameMs != null) return
        nativePrepareToFirstFrameMs = (elapsedRealtimeMs() - prepareStartedAtMs).coerceAtLeast(0L)
    }

    fun recordLaunchToFirstFrame(
        prepareEpoch: Long,
        durationMs: Long,
    ) {
        if (prepareEpoch != epoch || launchToFirstFrameMs != null || durationMs < 0L) return
        launchToFirstFrameMs = durationMs
    }

    fun excludeBufferingUntilPlaying() {
        closeRebuffer(elapsedRealtimeMs())
        excludeBufferingUntilPlaying = true
        lastStatus = PlaybackStatus.Paused
    }

    fun onPlaybackStatus(
        prepareEpoch: Long,
        status: PlaybackStatus,
    ) {
        if (prepareEpoch != epoch) return
        val nowMs = elapsedRealtimeMs()
        if (status == PlaybackStatus.Playing) {
            closeRebuffer(nowMs)
            excludeBufferingUntilPlaying = false
        } else if (
            status == PlaybackStatus.Buffering &&
            lastStatus == PlaybackStatus.Playing &&
            nativePrepareToFirstFrameMs != null &&
            !excludeBufferingUntilPlaying
        ) {
            rebufferStartedAtMs = nowMs
            rebufferCount += 1
        } else if (status != PlaybackStatus.Buffering) {
            closeRebuffer(nowMs)
        }
        lastStatus = status
    }

    fun onAudioUnderrun(
        prepareEpoch: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        if (prepareEpoch != epoch) return
        audioUnderrunCount += 1
        maxAudioFeedGapMs = maxOf(maxAudioFeedGapMs, elapsedSinceLastFeedMs.coerceAtLeast(0L))
    }

    fun onSample(
        prepareEpoch: Long,
        allocatedBufferBytes: Long,
        bufferedAheadMs: Long,
    ) {
        if (prepareEpoch != epoch) return
        val allocated = allocatedBufferBytes.coerceAtLeast(0L)
        val ahead = bufferedAheadMs.coerceAtLeast(0L)
        currentAllocatedBufferBytes = allocated
        peakAllocatedBufferBytes = maxOf(peakAllocatedBufferBytes ?: 0L, allocated)
        currentBufferedAheadMs = ahead
        if (nativePrepareToFirstFrameMs != null) {
            minBufferedAheadMs = minOf(minBufferedAheadMs ?: ahead, ahead)
            maxBufferedAheadMs = maxOf(maxBufferedAheadMs ?: ahead, ahead)
        }
    }

    fun snapshot(): Media3PlaybackPerformanceSnapshot =
        Media3PlaybackPerformanceSnapshot(
            prepareEpoch = epoch,
            nativePrepareToFirstFrameMs = nativePrepareToFirstFrameMs,
            launchToFirstFrameMs = launchToFirstFrameMs,
            rebufferCount = rebufferCount,
            totalRebufferMs = totalRebufferMs,
            maxRebufferMs = maxRebufferMs,
            audioUnderrunCount = audioUnderrunCount,
            maxAudioFeedGapMs = maxAudioFeedGapMs,
            currentAllocatedBufferBytes = currentAllocatedBufferBytes,
            peakAllocatedBufferBytes = peakAllocatedBufferBytes,
            currentBufferedAheadMs = currentBufferedAheadMs,
            minBufferedAheadMs = minBufferedAheadMs,
            maxBufferedAheadMs = maxBufferedAheadMs,
        )

    fun finish(): Media3PlaybackPerformanceSnapshot {
        closeRebuffer(elapsedRealtimeMs())
        return snapshot()
    }

    private fun closeRebuffer(nowMs: Long) {
        val startedAtMs = rebufferStartedAtMs ?: return
        val durationMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        totalRebufferMs += durationMs
        maxRebufferMs = maxOf(maxRebufferMs, durationMs)
        rebufferStartedAtMs = null
    }
}
