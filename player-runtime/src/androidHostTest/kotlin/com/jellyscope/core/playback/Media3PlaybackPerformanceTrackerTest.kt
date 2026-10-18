// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Media3PlaybackPerformanceTrackerTest {
    @Test
    fun firstFrameIsOneShotAndStaleEpochsAreIgnored() {
        var nowMs = 100L
        val tracker = Media3PlaybackPerformanceTracker { nowMs }
        tracker.begin(7L)

        nowMs = 450L
        tracker.onRenderedFirstFrame(6L)
        assertNull(tracker.snapshot().nativePrepareToFirstFrameMs)

        tracker.onRenderedFirstFrame(7L)
        nowMs = 900L
        tracker.onRenderedFirstFrame(7L)

        assertEquals(350L, tracker.snapshot().nativePrepareToFirstFrameMs)
    }

    @Test
    fun initialLoadAndSeekBufferingAreExcludedFromRebuffers() {
        var nowMs = 0L
        val tracker = Media3PlaybackPerformanceTracker { nowMs }
        tracker.begin(1L)
        tracker.onPlaybackStatus(1L, PlaybackStatus.Buffering)
        nowMs = 100L
        tracker.onRenderedFirstFrame(1L)
        tracker.onPlaybackStatus(1L, PlaybackStatus.Playing)

        nowMs = 200L
        tracker.excludeBufferingUntilPlaying()
        tracker.onPlaybackStatus(1L, PlaybackStatus.Buffering)
        nowMs = 400L
        tracker.onPlaybackStatus(1L, PlaybackStatus.Playing)

        assertEquals(0, tracker.snapshot().rebufferCount)
    }

    @Test
    fun playingToBufferingRecordsBoundedAggregate() {
        var nowMs = 0L
        val tracker = Media3PlaybackPerformanceTracker { nowMs }
        tracker.begin(3L)
        tracker.onRenderedFirstFrame(3L)
        tracker.onPlaybackStatus(3L, PlaybackStatus.Playing)

        nowMs = 200L
        tracker.onPlaybackStatus(3L, PlaybackStatus.Buffering)
        nowMs = 500L
        tracker.onPlaybackStatus(3L, PlaybackStatus.Playing)
        nowMs = 800L
        tracker.onPlaybackStatus(3L, PlaybackStatus.Buffering)
        nowMs = 1_300L
        tracker.onPlaybackStatus(3L, PlaybackStatus.Paused)

        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot.rebufferCount)
        assertEquals(800L, snapshot.totalRebufferMs)
        assertEquals(500L, snapshot.maxRebufferMs)
    }

    @Test
    fun terminalFailureClosesOpenRebufferAtFailureBoundary() {
        var nowMs = 0L
        val tracker = Media3PlaybackPerformanceTracker { nowMs }
        tracker.begin(5L)
        tracker.onRenderedFirstFrame(5L)
        tracker.onPlaybackStatus(5L, PlaybackStatus.Playing)

        nowMs = 200L
        tracker.onPlaybackStatus(5L, PlaybackStatus.Buffering)
        nowMs = 500L
        tracker.onPlaybackStatus(5L, PlaybackStatus.Failed)
        nowMs = 5_000L

        val snapshot = tracker.finish()
        assertEquals(1, snapshot.rebufferCount)
        assertEquals(300L, snapshot.totalRebufferMs)
        assertEquals(300L, snapshot.maxRebufferMs)
    }

    @Test
    fun samplesAndUnderrunsRetainCurrentAndMaxima() {
        val tracker = Media3PlaybackPerformanceTracker { 0L }
        tracker.begin(4L)
        tracker.onRenderedFirstFrame(4L)
        tracker.onSample(4L, allocatedBufferBytes = 20L, bufferedAheadMs = 7_000L)
        tracker.onSample(4L, allocatedBufferBytes = 10L, bufferedAheadMs = 3_000L)
        tracker.onAudioUnderrun(4L, elapsedSinceLastFeedMs = 15L)
        tracker.onAudioUnderrun(4L, elapsedSinceLastFeedMs = 40L)

        val snapshot = tracker.snapshot()
        assertEquals(10L, snapshot.currentAllocatedBufferBytes)
        assertEquals(20L, snapshot.peakAllocatedBufferBytes)
        assertEquals(3_000L, snapshot.currentBufferedAheadMs)
        assertEquals(3_000L, snapshot.minBufferedAheadMs)
        assertEquals(7_000L, snapshot.maxBufferedAheadMs)
        assertEquals(2, snapshot.audioUnderrunCount)
        assertEquals(40L, snapshot.maxAudioFeedGapMs)
    }
}
