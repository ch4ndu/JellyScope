// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.playback.MpvPresentationPreference
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCoreModuleTest {
    @Test
    fun macOsUsesOpenGlPresentationByDefault() {
        assertEquals(
            MpvPresentationPreference.MacOsOpenGl,
            desktopMpvPresentationPreference(osName = "Mac OS X"),
        )
    }

    @Test
    fun nonMacOsUsesTheSoftwareCompatibilityPresentation() {
        assertEquals(
            MpvPresentationPreference.Software,
            desktopMpvPresentationPreference(osName = "Linux"),
        )
    }
}
