// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

actual fun isDesktopPlayerDeviceSettingsVisible(): Boolean = true

actual fun isPlaybackCompatibilityVisible(): Boolean = isPlaybackCompatibilityVisibleOnJvm(System.getProperty("os.name").orEmpty())

actual fun isDesktopTlsExceptionVisible(): Boolean = true

internal fun isPlaybackCompatibilityVisibleOnJvm(osName: String): Boolean = osName.trim().startsWith("mac", ignoreCase = true)
