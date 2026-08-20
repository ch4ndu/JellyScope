// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.os.Build
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import java.util.UUID

class AndroidDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName: String =
        Build.MODEL
            .takeIf { it.isNotBlank() }
            ?: "Android"

    override fun newDeviceId(): String = UUID.randomUUID().toString()
}
