// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.platform

interface DiagnosticsEnvironment {
    val platform: String
    val osVersion: String
    val appVersion: String
    val deviceModel: String
}
