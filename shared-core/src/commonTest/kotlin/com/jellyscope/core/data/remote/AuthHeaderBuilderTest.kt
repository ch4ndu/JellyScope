// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthHeaderBuilderTest {
    @Test
    fun buildsTokenOnlyHeaderForNonblankToken() {
        assertEquals(
            "MediaBrowser Token=\"token-1\"",
            AuthHeaderBuilder.buildTokenOnly("token-1"),
        )
    }

    @Test
    fun suppressesTokenOnlyHeaderForMissingOrBlankToken() {
        assertNull(AuthHeaderBuilder.buildTokenOnly(null))
        assertNull(AuthHeaderBuilder.buildTokenOnly(""))
        assertNull(AuthHeaderBuilder.buildTokenOnly("   "))
    }

    @Test
    fun buildsHeaderWithoutToken() {
        val header =
            AuthHeaderBuilder.build(
                deviceName = "Pixel",
                deviceId = "device-1",
                clientInfo = ClientInfo(versionName = "0.1.0-alpha1"),
                token = null,
            )

        assertEquals(
            "MediaBrowser Client=\"JellyScope\", " +
                "Device=\"Pixel\", " +
                "DeviceId=\"device-1\", " +
                "Version=\"0.1.0-alpha1\"",
            header,
        )
    }

    @Test
    fun buildsHeaderWithToken() {
        val header =
            AuthHeaderBuilder.build(
                deviceName = "Pixel",
                deviceId = "device-1",
                clientInfo = ClientInfo(versionName = "0.1.0-alpha1"),
                token = "token-1",
            )

        assertEquals(
            "MediaBrowser Client=\"JellyScope\", " +
                "Device=\"Pixel\", " +
                "DeviceId=\"device-1\", " +
                "Version=\"0.1.0-alpha1\", " +
                "Token=\"token-1\"",
            header,
        )
    }
}
