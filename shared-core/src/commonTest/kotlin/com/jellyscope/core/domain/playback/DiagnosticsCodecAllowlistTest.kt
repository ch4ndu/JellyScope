// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsCodecAllowlistTest {
    @Test
    fun everyProviderDeclaredCodecIsAllowlisted() {
        diagnosticsDeviceProfileDeclarations.forEach { declaration ->
            (declaration.videoCodecs + declaration.audioCodecs).forEach { codec ->
                val canonicalCodec = requireNotNull(canonicalVideoCodec(codec))
                assertTrue(
                    canonicalCodec in DiagnosticsCodecAllowlist.allowedCodecs,
                    "$codec from $declaration is missing from the diagnostics allowlist",
                )
            }
        }
    }

    @Test
    fun titleShapedCodecRendersUnrecognized() {
        assertEquals(DiagnosticsCodecAllowlist.UNRECOGNIZED, DiagnosticsCodecAllowlist.renderCodec("Jugnu"))
        assertEquals("hevc", DiagnosticsCodecAllowlist.renderCodec("H265"))
    }

    @Test
    fun titleShapedContainerRendersUnrecognized() {
        assertEquals(DiagnosticsCodecAllowlist.UNRECOGNIZED, DiagnosticsCodecAllowlist.renderContainer("Jugnu"))
        assertEquals("mkv", DiagnosticsCodecAllowlist.renderContainer("MKV"))
    }

    @Test
    fun titleShapedChannelLayoutRendersUnrecognized() {
        assertEquals(DiagnosticsCodecAllowlist.UNRECOGNIZED, DiagnosticsCodecAllowlist.renderChannelLayout("Jugnu"))
        assertEquals("5.1", DiagnosticsCodecAllowlist.renderChannelLayout("5.1"))
    }
}
