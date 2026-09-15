// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlaybackTransitionRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackTransitionRuntimeKind
import com.jellyscope.core.domain.playback.PlaybackTransitionRuntimeState
import com.jellyscope.core.domain.playback.PlayerBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerDebugProjectionTest {
    private val mpvLabels =
        PlayerDebugMpvLabels(
            codecDescription = "Codec description",
            decoding = "Video decoding",
            decodedSize = "Decoded size",
            hardware = "Hardware",
            hardwareCopyBack = "Hardware (copy-back)",
            software = "Software",
            unknown = "Unknown",
        )

    @Test
    fun absentStaticInfoReturnsCompactPlaybackFallbackWithLiveStatus() {
        val sections =
            playerDebugSections(
                mpvLabels = mpvLabels,
                debugInfo = null,
                playbackState = playbackState(),
                runtimeDiagnostics = PlaybackRuntimeDiagnostics.EMPTY,
            )

        assertEquals(
            listOf(
                PlayerDebugSection(
                    title = "Playback",
                    rows =
                        listOf(
                            PlayerDebugRowModel("Playback info", "Unavailable"),
                            PlayerDebugRowModel("Status", "Playing"),
                        ),
                ),
            ),
            sections,
        )
    }

    @Test
    fun genericProjectionOwnsExactCompactRowOrderAndExcludesNoisyDiagnostics() {
        val sections =
            playerDebugSections(
                mpvLabels = mpvLabels,
                debugInfo =
                    PlayerDebugInfo(
                        playMethod = "Transcode",
                        backend = PlayerBackend.Mpv,
                        transcodeReasons = listOf("VideoCodecNotSupported"),
                        container = "mkv",
                        qualityPolicyMode = "Auto",
                        qualityPolicyOrigin = PlaybackQualityPolicyOrigin.SessionAutoRecovery,
                        requestCapBitrateBps = 8_000_000L,
                        playSessionId = "PRIVATE-PLAY-SESSION",
                    ),
                playbackState = playbackState(),
                runtimeDiagnostics =
                    PlaybackRuntimeDiagnostics.EMPTY.copy(
                        videoDecoderName = "videotoolbox",
                        presentationPath = "IOSurface Render API",
                        droppedVideoFrames = 9,
                        droppedVideoFramesPerSecond = 1.25,
                        decoderDroppedVideoFrames = 2,
                        outputDroppedVideoFrames = 7,
                        recentVideoRenderP95Ms = 14.5,
                        recentPresentedFrameRate = 59.94,
                        presentationGapCount = 3,
                        playbackTransition =
                            PlaybackTransitionRuntimeDiagnostics(
                                kind = PlaybackTransitionRuntimeKind.Resume,
                                state = PlaybackTransitionRuntimeState.Pending,
                                targetPositionMs = 215_000L,
                                nativePositionMs = 221_000L,
                                targetArrived = true,
                                clockAdvanced = true,
                                pictureAdvances = 0,
                                requiredPictureAdvances = 2,
                            ),
                    ),
            )

        assertEquals(listOf("Playback", "Runtime", "Policy"), sections.map(PlayerDebugSection::title))
        assertEquals(
            listOf(
                "Backend",
                "Play method",
                "Transcode reasons",
                "Container",
                "Video",
                "Audio",
                "Subtitle render",
                "Subtitle styleable",
                "Launch / native first frame",
                "Rebuffers",
                "Audio underruns",
                "Status",
            ),
            sections.single { section -> section.title == "Playback" }.rows.map(PlayerDebugRowModel::label),
        )
        assertEquals(
            listOf(
                "Codec description",
                "Video decoding",
                "Decoded size",
                "Dropped frames",
                "Buffer policy",
                "Target / allocated",
                "Buffered ahead",
                "Bandwidth estimate",
            ),
            sections.single { section -> section.title == "Runtime" }.rows.map(PlayerDebugRowModel::label),
        )
        assertEquals(
            listOf(
                "Source bitrate",
                "Request cap",
                "Cap origin",
                "Quality policy",
                "Capability result",
                "First video output",
                "Effective transcode cap",
            ),
            sections.single { section -> section.title == "Policy" }.rows.map(PlayerDebugRowModel::label),
        )

        val excludedLabels =
            setOf(
                "App trigger",
                "Health signal",
                "Health threshold",
                "Position / duration",
                "Buffered position",
                "Transition readiness",
                "Speed",
                "Presentation",
                "Dropped-frame rate",
                "Decoder drops",
                "Output drops",
                "Render p95",
                "Presented rate",
                "Presentation gaps",
                "Low-RAM device",
                "Policy origin",
                "Client limiter",
                "Capability reason",
                "Configured VLC default",
                "Recovery",
                "Recovery reason",
                "Recovery budget",
                "Play session",
            )
        val labels = sections.flatMap(PlayerDebugSection::rows).map(PlayerDebugRowModel::label).toSet()
        assertTrue(labels.intersect(excludedLabels).isEmpty())
        assertTrue(
            labels
                .intersect(
                    setOf(
                        "LibVLC cache",
                        "Effective quality",
                        "Wire bitrate",
                        "Display active",
                        "Display requested",
                        "Display tier",
                        "Display result",
                        "Display switch",
                    ),
                ).isEmpty(),
        )

        assertTrue(sections.row("Transcode reasons").emphasize)

        val sectionsWithoutContainer =
            playerDebugSections(
                mpvLabels = mpvLabels,
                debugInfo = PlayerDebugInfo(playMethod = "Direct Play", container = "   "),
                playbackState = playbackState(),
                runtimeDiagnostics = PlaybackRuntimeDiagnostics.EMPTY,
            )
        assertFalse(
            sectionsWithoutContainer
                .flatMap(PlayerDebugSection::rows)
                .any { row -> row.label == "Container" },
        )
    }

    @Test
    fun projectionCannotExposeTheIdentityBearingPlaySessionField() {
        val secret = "PRIVATE-PLAY-SESSION"
        val rows =
            playerDebugSections(
                mpvLabels = mpvLabels,
                debugInfo = PlayerDebugInfo(playMethod = "Direct Play", playSessionId = secret),
                playbackState = playbackState(),
                runtimeDiagnostics = PlaybackRuntimeDiagnostics.EMPTY,
            ).flatMap(PlayerDebugSection::rows)

        assertFalse(rows.any { row -> secret in row.label || secret in row.value })
        val forbiddenLabels = setOf("token", "url", "path", "title", "server", "user", "item", "session id")
        assertFalse(
            rows.any { row ->
                row.label.lowercase() in forbiddenLabels
            },
        )
    }

    @Test
    fun zeroMeasurementsRemainDistinctFromUnavailableMeasurements() {
        val zeroSections =
            playerDebugSections(
                mpvLabels = mpvLabels,
                debugInfo = PlayerDebugInfo(playMethod = "Direct Play"),
                playbackState = playbackState(),
                runtimeDiagnostics =
                    PlaybackRuntimeDiagnostics.EMPTY.copy(
                        droppedVideoFrames = 0,
                    ),
            )
        val unavailableSections =
            playerDebugSections(
                mpvLabels = mpvLabels,
                debugInfo = PlayerDebugInfo(playMethod = "Direct Play"),
                playbackState = playbackState(),
                runtimeDiagnostics = PlaybackRuntimeDiagnostics.EMPTY,
            )

        assertEquals("0", zeroSections.row("Dropped frames").value)
        assertEquals(PLAYER_DEBUG_UNAVAILABLE, unavailableSections.row("Dropped frames").value)
    }

    private fun playbackState() =
        PlaybackState(
            status = PlaybackStatus.Playing,
            positionMs = 65_000L,
            durationMs = 130_000L,
            bufferedPositionMs = 80_000L,
        )
}

private fun List<PlayerDebugSection>.row(label: String): PlayerDebugRowModel =
    flatMap(PlayerDebugSection::rows).single { row -> row.label == label }
