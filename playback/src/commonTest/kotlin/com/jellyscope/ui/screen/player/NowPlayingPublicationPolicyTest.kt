// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class NowPlayingPublicationPolicyTest {
    @Test
    fun ordinaryTickWithoutMetadataChangeReturnsNoChange() {
        val previous = snapshot(positionMs = 10_000L)

        assertEquals(
            NowPlayingPublication.NoChange,
            nowPlayingPublicationDecision(previous, previous.copy(positionMs = 11_000L)),
        )
    }

    @Test
    fun positionDriftUsesStrictGreaterThanFiveSeconds() {
        val previous = snapshot(positionMs = 10_000L)

        assertEquals(
            NowPlayingPublication.NoChange,
            nowPlayingPublicationDecision(previous, previous.copy(positionMs = 14_999L)),
        )
        assertEquals(
            NowPlayingPublication.NoChange,
            nowPlayingPublicationDecision(previous, previous.copy(positionMs = 15_000L)),
        )
        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(previous, previous.copy(positionMs = 15_001L)),
        )
    }

    @Test
    fun neverPushedSentinelForcesFullPublication() {
        val previous = snapshot(positionMs = Long.MIN_VALUE)

        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(previous, previous.copy(positionMs = 0L)),
        )
    }

    @Test
    fun eachMetadataEdgeForcesFullPublication() {
        val previous = snapshot()

        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(previous, previous.copy(title = "New title")),
        )
        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(previous, previous.copy(artist = "New artist")),
        )
        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(previous, previous.copy(durationMs = 20_000L)),
        )
        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(previous, previous.copy(status = PlaybackStatus.Paused)),
        )
    }

    @Test
    fun speedOnlyChangesForceFullPublicationInBothDirections() {
        val previous = snapshot()

        assertEquals(
            1.5,
            nowPlayingPlaybackRate(PlaybackStatus.Playing, playbackSpeed = 1.5f),
        )
        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(previous, previous.copy(playbackSpeed = 1.5f)),
        )
        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(previous.copy(playbackSpeed = 1.5f), previous),
        )
    }

    @Test
    fun pausedSpeedChangePublishesButExportsZeroRate() {
        val paused = snapshot(status = PlaybackStatus.Paused)

        assertEquals(
            NowPlayingPublication.PublishFull,
            nowPlayingPublicationDecision(paused, paused.copy(playbackSpeed = 1.5f)),
        )
        assertEquals(0.0, nowPlayingPlaybackRate(PlaybackStatus.Paused, playbackSpeed = 1.5f))
    }

    @Test
    fun clearTakesPrecedenceWhenContentIsDropped() {
        assertEquals(
            NowPlayingPublication.Clear,
            nowPlayingPublicationDecision(previous = snapshot(), next = null),
        )
    }

    private fun snapshot(
        title: String = "Title",
        artist: String? = "Artist",
        durationMs: Long? = 10_000L,
        status: PlaybackStatus = PlaybackStatus.Playing,
        positionMs: Long = 10_000L,
        playbackSpeed: Float = 1.0f,
    ): NowPlayingSnapshot =
        NowPlayingSnapshot(
            title = title,
            artist = artist,
            durationMs = durationMs,
            status = status,
            positionMs = positionMs,
            playbackSpeed = playbackSpeed,
        )
}
