// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class AppleStaticCapabilityPolicyTest {
    @Test
    fun advertisesOnlyAvFoundationDecodableAudioCodecsAndSixChannels() {
        val capabilities = appleStaticDeviceCapabilities()

        assertEquals(listOf("aac", "mp3", "ac3", "eac3", "flac", "alac"), capabilities.audioCodecs)
        assertEquals(6, capabilities.maxAudioChannels)
    }
}
