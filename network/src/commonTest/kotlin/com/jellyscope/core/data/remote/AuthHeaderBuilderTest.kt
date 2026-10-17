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
        assertNull(AuthHeaderBuilder.buildTokenOnly(" \r\n \t"))
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

    @Test
    fun encodesEveryFullHeaderValueWithTheSharedRfc3986Policy() {
        val header =
            AuthHeaderBuilder.build(
                deviceName = " Living Room + TV\r\n ",
                deviceId = " id/one ",
                clientInfo =
                    ClientInfo(
                        versionName = " 1.0 café ",
                        clientName = " Jelly'Scope \"β\"\r\n ",
                    ),
                token = " tok+en's\"雪\r\n ",
            )

        assertEquals(
            "MediaBrowser Client=\"Jelly%27Scope%20%22%CE%B2%22\", " +
                "Device=\"Living%20Room%20%2B%20TV\", " +
                "DeviceId=\"id%2Fone\", " +
                "Version=\"1.0%20caf%C3%A9\", " +
                "Token=\"tok%2Ben%27s%22%E9%9B%AA\"",
            header,
        )
    }

    @Test
    fun tokenOnlyUsesTheSameWhitespaceCrLfAndEncodingPolicy() {
        assertEquals(
            "MediaBrowser Token=\"A%20%2B%20%27%20%22%20caf%C3%A9B\"",
            AuthHeaderBuilder.buildTokenOnly("  A + ' \" café\r\nB  "),
        )
    }
}
