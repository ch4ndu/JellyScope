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
    fun buildTokenOnly(token: String?): String? {
        val encodedToken = token?.let(::encodeAuthorizationParameter)?.takeIf(String::isNotEmpty)
        return encodedToken?.let { value -> """MediaBrowser Token="$value"""" }
    }

    fun build(
        deviceName: String,
        deviceId: String,
        clientInfo: ClientInfo,
        token: String?,
    ): String {
        val encodedToken = token?.let(::encodeAuthorizationParameter)?.takeIf(String::isNotEmpty)
        val tokenPart =
            encodedToken
                ?.let { value -> """, Token="$value"""" }
                .orEmpty()

        return "MediaBrowser Client=\"${encodeAuthorizationParameter(clientInfo.clientName)}\", " +
            "Device=\"${encodeAuthorizationParameter(deviceName)}\", " +
            "DeviceId=\"${encodeAuthorizationParameter(deviceId)}\", " +
            "Version=\"${encodeAuthorizationParameter(clientInfo.versionName)}\"" +
            tokenPart
    }
}

internal fun encodeAuthorizationParameter(value: String): String {
    val normalized = value.trim().replace("\r", "").replace("\n", "")
    return buildString {
        normalized.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            if (unsigned.isRfc3986Unreserved()) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(UPPERCASE_HEX[unsigned ushr 4])
                append(UPPERCASE_HEX[unsigned and 0x0f])
            }
        }
    }
}

private fun Int.isRfc3986Unreserved(): Boolean =
    this in 'A'.code..'Z'.code ||
        this in 'a'.code..'z'.code ||
        this in '0'.code..'9'.code ||
        this == '-'.code ||
        this == '.'.code ||
        this == '_'.code ||
        this == '~'.code

private const val UPPERCASE_HEX = "0123456789ABCDEF"

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
