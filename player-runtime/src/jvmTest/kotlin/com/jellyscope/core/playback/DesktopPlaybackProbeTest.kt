// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.util.LogScrubber
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlaybackProbeTest {
    @AfterTest
    fun resetPreferenceGate() {
        DesktopPlaybackProbe.setPreferenceEnabled(false)
    }

    @Test
    fun preferenceGateCanChangeDuringTheProcessLifetime() {
        DesktopPlaybackProbe.setPreferenceEnabled(true)
        assertTrue(DesktopPlaybackProbe.isEnabled)

        DesktopPlaybackProbe.setPreferenceEnabled(false)
        val cliForced = System.getProperty(DESKTOP_PLAYBACK_PROBE_PROPERTY)?.toBooleanStrictOrNull() == true
        assertEquals(cliForced, DesktopPlaybackProbe.isEnabled)
    }

    @Test
    fun typedProbeRecordSurvivesTheClosedDiagnosticSchema() {
        val message =
            MpvStatusProbeRecord(
                status = DesktopProbeToken.from("Playing"),
                positionMs = 12_345,
                durationMs = 98_765,
                streamMode = DesktopProbeToken.from("DirectPlay"),
                presentation = DesktopProbeToken.from("IOSurface Render API"),
                hardwareDecodeRequested = DesktopProbeToken.from("auto-safe"),
                hardwareDecodeResolved = DesktopProbeToken.from("videotoolbox"),
                decoder = DesktopProbeToken.from("Google VP9"),
                width = 3_840,
                height = 2_160,
                frameRate = 59.94,
                droppedFrames = 1,
                decoderDroppedFrames = 0,
                outputDroppedFrames = 1,
            ).serialized

        assertEquals(message, LogScrubber.capture("JellyScopePlaybackProbe", message))
        assertTrue(message.contains("presentation=IOSurface_Render_API"))

        val timingMessage =
            DesktopSurfaceTimingProbeRecord(
                frames = 48,
                renderMaxMicros = 56_000,
                presentMaxMicros = 2_000,
            ).serialized
        assertEquals(timingMessage, LogScrubber.capture("JellyScopePlaybackProbe", timingMessage))
        assertTrue(timingMessage.contains("renderMaxMicros=56000"))
        assertTrue(timingMessage.contains("presentMaxMicros=2000"))
    }

    @Test
    fun malformedOrSensitiveProbeLikeMessagesAreRejected() {
        val valid =
            DesktopWindowProbeRecord(
                windowEvent = DesktopProbeToken.from("FOCUS"),
                placement = DesktopProbeToken.from("unchanged"),
                active = true,
                focused = true,
            ).serialized

        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", "$valid title=PrivateTitle"))
        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", "$valid username=demo-user"))
        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", valid.replace("unchanged", "https://server/path")))
        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", valid.replace("unchanged", "/Users/test/video.mkv")))
        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", valid.replace("unchanged", "Bearer")))
        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", "$valid focused=true"))
        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", valid.replace("event=window ", "")))
        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", valid.replace("event=window", "event=unknown")))
        assertNull(LogScrubber.capture("JellyScopePlaybackProbe", valid.replace("unchanged", "JellyScopePlaybackProbe")))
    }
}
