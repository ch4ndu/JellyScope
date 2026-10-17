// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.platform

class DesktopDiagnosticsEnvironment : DiagnosticsEnvironment {
    override val platform: String = "desktop"
    override val osVersion: String = System.getProperty("os.version", "unknown").ifBlank { "unknown" }
    override val appVersion: String = System.getProperty(DESKTOP_VERSION_PROPERTY, "dev").ifBlank { "dev" }
    override val deviceModel: String = System.getProperty("os.arch", "unknown").ifBlank { "unknown" }
}

private const val DESKTOP_VERSION_PROPERTY = "jellyscope.version"
