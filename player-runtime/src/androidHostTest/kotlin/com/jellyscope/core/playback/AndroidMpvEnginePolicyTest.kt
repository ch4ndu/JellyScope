// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidMpvEnginePolicyTest {
    @Test
    fun deviceClassesUseTheirPinnedDecodeAndVideoOutputPaths() {
        assertEquals(
            "no",
            androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = false, isEmulator = true)).hardwareDecoder,
        )
        assertEquals(
            "mediacodec-copy",
            androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = false, isEmulator = false)).hardwareDecoder,
        )
        assertEquals(
            "mediacodec",
            androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = true, isEmulator = false)).hardwareDecoder,
        )
        assertEquals(
            "gpu-next",
            androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = false, isEmulator = false)).videoOutput,
        )
        assertEquals(
            "gpu",
            androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = true, isEmulator = false)).videoOutput,
        )
        assertEquals(
            "fast",
            androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = true, isEmulator = false))
                .options("/cache/mpv/trust.pem")["profile"],
        )
        assertEquals(
            null,
            androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = false, isEmulator = false))
                .options("/cache/mpv/trust.pem")["profile"],
        )
    }

    @Test
    fun optionsDisableImplicitResourcesAndKeepTlsAndCacheDeterministic() {
        val options =
            androidMpvEnginePolicy(AndroidMpvDeviceFacts(isTelevision = false, isEmulator = false))
                .options("/cache/mpv/trust.pem")

        assertEquals("yes", options["tls-verify"])
        assertEquals("/cache/mpv/trust.pem", options["tls-ca-file"])
        assertEquals("no", options["config"])
        assertEquals("", options["scripts"])
        assertTrue("script" !in options)
        assertEquals("no", options["ytdl"])
        assertEquals("10", options["cache-secs"])
        assertEquals("64MiB", options["demuxer-max-bytes"])
        assertEquals("16MiB", options["demuxer-max-back-bytes"])
        assertEquals("always", options["keep-open"])
        assertTrue(options["hwdec-codecs"].orEmpty().contains("vp9"))
    }
}
