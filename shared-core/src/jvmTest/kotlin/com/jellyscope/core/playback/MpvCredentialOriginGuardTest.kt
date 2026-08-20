// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvCredentialOriginGuardTest {
    @Test
    fun externalResourceGuardRetainsOriginPortAndBasePathChecks() {
        val serverUrl = "https://jellyfin.example:443/base"

        assertTrue("https://jellyfin.example/base/subtitle.vtt".isUnderServer(serverUrl))
        assertFalse("https://attacker.example/base/subtitle.vtt".isUnderServer(serverUrl))
        assertFalse("https://jellyfin.example:444/base/subtitle.vtt".isUnderServer(serverUrl))
        assertFalse("http://jellyfin.example/base/subtitle.vtt".isUnderServer(serverUrl))
        assertFalse("https://jellyfin.example/outside/subtitle.vtt".isUnderServer(serverUrl))
    }

    @Test
    fun resourceQueryStrippingRetainsNonCredentialParametersAndFragment() {
        assertEquals(
            "https://jellyfin.example/subtitle.vtt?language=eng#track",
            "https://jellyfin.example/subtitle.vtt?api_key=secret&language=eng&TOKEN=other#track"
                .stripAuthQueryParams(),
        )
    }
}
