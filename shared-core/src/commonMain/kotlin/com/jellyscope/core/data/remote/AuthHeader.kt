// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.platform.DeviceInfoProvider

interface AuthHeaderProvider {
    suspend fun authHeader(token: String?): String
}

interface ImageAuthHeaderProvider {
    fun authHeader(
        deviceId: String,
        token: String?,
    ): String
}

data class ClientInfo(
    val versionName: String,
    val clientName: String = "JellyScope",
)

object AuthHeaderBuilder {
    fun buildTokenOnly(token: String?): String? =
        token
            ?.takeIf { it.isNotBlank() }
            ?.let { """MediaBrowser Token="$it"""" }

    fun build(
        deviceName: String,
        deviceId: String,
        clientInfo: ClientInfo,
        token: String?,
    ): String {
        val tokenPart =
            token
                ?.takeIf { it.isNotBlank() }
                ?.let { """, Token="$it"""" }
                .orEmpty()

        return "MediaBrowser Client=\"${clientInfo.clientName}\", " +
            "Device=\"$deviceName\", " +
            "DeviceId=\"$deviceId\", " +
            "Version=\"${clientInfo.versionName}\"" +
            tokenPart
    }
}

class DefaultImageAuthHeaderProvider(
    private val deviceInfoProvider: DeviceInfoProvider,
    private val clientInfo: ClientInfo,
) : ImageAuthHeaderProvider {
    override fun authHeader(
        deviceId: String,
        token: String?,
    ): String =
        AuthHeaderBuilder.build(
            deviceName = deviceInfoProvider.deviceName,
            deviceId = deviceId,
            clientInfo = clientInfo,
            token = token,
        )
}

class SessionAuthHeaderProvider(
    private val sessionStore: com.jellyscope.core.data.local.SessionStore,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val clientInfo: ClientInfo,
) : AuthHeaderProvider {
    override suspend fun authHeader(token: String?): String =
        AuthHeaderBuilder.build(
            deviceName = deviceInfoProvider.deviceName,
            deviceId = sessionStore.readOrCreateDeviceId(deviceInfoProvider::newDeviceId),
            clientInfo = clientInfo,
            token = token,
        )
}
