// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibVlcPlaybackCompatibilityTest {
    private val noHdr = VideoRangeCapabilities()
    private val hdr10 = VideoRangeCapabilities(supportsHdr10 = true)
    private val dolbyVision = VideoRangeCapabilities(supportsDolbyVision = true)

    @Test
    fun sdrAndUnknownRangesAreAlwaysSupported() {
        assertTrue(isVideoRangeSupported("SDR", noHdr))
        // C7d permissive default: an unrecognized/blank/null range must NOT be
        // capped. Deriving this from the profile table would flip the default to
        // fail-closed and cause spurious bitrate caps.
        assertTrue(isVideoRangeSupported(null, noHdr))
        assertTrue(isVideoRangeSupported("SomeFutureRange", noHdr))
    }

    @Test
    fun hdrRangesAreGatedOnCapabilities() {
        assertFalse(isVideoRangeSupported("HDR10", noHdr))
        assertTrue(isVideoRangeSupported("HDR10", hdr10))
        assertFalse(isVideoRangeSupported("DOVI", noHdr))
        assertTrue(isVideoRangeSupported("DOVI", dolbyVision))
        // Dolby Vision with an SDR base layer is always playable.
        assertTrue(isVideoRangeSupported("DOVIWithSDR", noHdr))
    }

    @Test
    fun canonicalVideoCodecMapsAliasesAndDropsBlanks() {
        assertEquals("hevc", canonicalVideoCodec("h265"))
        assertEquals("hevc", canonicalVideoCodec("x265"))
        assertEquals("av1", canonicalVideoCodec("av01"))
        assertEquals("h264", canonicalVideoCodec("H264"))
        assertEquals("vp9", canonicalVideoCodec("vp9"))
        assertNull(canonicalVideoCodec("   "))
        assertNull(canonicalVideoCodec(null))
    }
}
