// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.platform.DeviceInfoProvider

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
