// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.platform

import android.os.Build

class AndroidDiagnosticsEnvironment(
    override val appVersion: String,
) : DiagnosticsEnvironment {
    override val platform: String = "android"
    override val osVersion: String = Build.VERSION.SDK_INT.toString()
    override val deviceModel: String = Build.MODEL
}
