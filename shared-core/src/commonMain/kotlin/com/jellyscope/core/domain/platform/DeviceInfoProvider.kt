// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.platform

interface DeviceInfoProvider {
    val deviceName: String

    fun newDeviceId(): String
}
