// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibVlcPlaybackCompatibilityTest {
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
