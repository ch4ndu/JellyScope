// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlayerDeviceSettingsVisibilityTest {
    @Test
    fun playbackCompatibilityIsVisibleOnlyOnMacOsDesktop() {
        listOf("Mac OS X", "macOS").forEach { osName ->
            assertTrue(isPlaybackCompatibilityVisibleOnJvm(osName), osName)
        }
        listOf("Linux", "Windows 11", "", "Darwin", "AmacOS").forEach { osName ->
            assertFalse(isPlaybackCompatibilityVisibleOnJvm(osName), osName)
        }
    }
}
