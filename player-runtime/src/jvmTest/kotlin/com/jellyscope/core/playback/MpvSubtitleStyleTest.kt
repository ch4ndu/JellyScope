// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.SubtitleEdgeStyle
import com.jellyscope.core.domain.playback.SubtitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvSubtitleStyleTest {
    @Test
    fun acceptsCallerSuppliedScaledPixelMargin() {
        assertEquals(
            "72",
            SubtitleStyle().toMpvSubtitleProperties(scaledPixelMargin = 72)["sub-margin-y"],
        )
    }

    @Test
    fun mapsScaleColorsAndOutline() {
        val properties =
            SubtitleStyle(
                fontScale = 0.05f,
                foregroundColor = "#80ff0000",
                backgroundColor = "#40010203",
                edgeStyle = SubtitleEdgeStyle.Outline,
            ).toMpvSubtitleProperties()

        assertEquals("force", properties["sub-ass-override"])
        assertEquals("34", properties["sub-margin-y"])
        assertEquals("0.1", properties["sub-scale"])
        assertEquals("#80FF0000", properties["sub-color"])
        assertEquals("#40010203", properties["sub-back-color"])
        assertEquals("#FF000000", properties["sub-border-color"])
        assertEquals("outline-and-shadow", properties["sub-border-style"])
        assertEquals("2", properties["sub-border-size"])
        assertEquals("0", properties["sub-shadow-offset"])
    }

    @Test
    fun mapsDropShadowWithoutBackgroundToBlackShadow() {
        val properties =
            SubtitleStyle(
                fontScale = 6f,
                edgeStyle = SubtitleEdgeStyle.DropShadow,
            ).toMpvSubtitleProperties()

        assertEquals("5.0", properties["sub-scale"])
        assertEquals("#FFFFFFFF", properties["sub-color"])
        assertEquals("#FF000000", properties["sub-back-color"])
        assertEquals("0", properties["sub-border-size"])
        assertEquals("2", properties["sub-shadow-offset"])
    }

    @Test
    fun mapsBackgroundWithoutEdgeToBackgroundBox() {
        val properties =
            SubtitleStyle(
                backgroundColor = "#223344",
                edgeStyle = SubtitleEdgeStyle.None,
            ).toMpvSubtitleProperties()

        assertEquals("#FF223344", properties["sub-back-color"])
        assertEquals("background-box", properties["sub-border-style"])
        assertEquals("0", properties["sub-border-size"])
        assertEquals("0", properties["sub-shadow-offset"])
    }

    @Test
    fun rejectsInvalidMpvColors() {
        assertNull("#12345".toMpvColorOrNull())
        assertNull("#GG000000".toMpvColorOrNull())
        assertNull(null.toMpvColorOrNull())
    }
}

class MpvPlaybackErrorClassifierTest {
    @Test
    fun classifiesCommonMpvFailures() {
        assertEquals(
            PlaybackError.Network,
            classifyMpvPlaybackError("mpv prepare failed while loading media", "HTTP error 404 not found"),
        )
        assertEquals(
            PlaybackError.Decoder,
            classifyMpvPlaybackError("mpv playback failed", "decoder init failed"),
        )
        assertEquals(
            PlaybackError.UnsupportedMedia,
            classifyMpvPlaybackError("mpv prepare failed", "unsupported format"),
        )
        assertEquals(
            PlaybackError.AudioOutput,
            classifyMpvPlaybackError("mpv initialization failed", "Audio output coreaudio failed"),
        )
        assertEquals(
            PlaybackError.Unknown,
            classifyMpvPlaybackError("mpv initialization failed", "unexpected"),
        )
    }
}
