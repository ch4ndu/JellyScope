// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.desktopPlayerBackendPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerBackendAvailabilityTest {
    @Test
    fun desktopSettingsExposeMpvAndLibVlc() {
        assertEquals(
            listOf(PlayerBackend.Mpv, PlayerBackend.LibVlc),
            desktopPlayerBackendPolicy().visibleBackends,
        )
    }

    @Test
    fun platformChoicesStayAvailableUntilTheProbeResolves() {
        assertEquals(
            listOf(
                PlayerBackendChoice(PlayerBackend.Mpv, available = true),
                PlayerBackendChoice(PlayerBackend.LibVlc, available = true),
            ),
            playerBackendChoices(desktopPlayerBackendPolicy()),
        )
    }

    @Test
    fun domainAvailabilityIsAppliedToThePlatformOrdering() {
        assertEquals(
            listOf(
                PlayerBackendChoice(PlayerBackend.Mpv, available = true),
                PlayerBackendChoice(PlayerBackend.LibVlc, available = false),
            ),
            playerBackendChoices(
                policy = desktopPlayerBackendPolicy(),
                availableBackends = setOf(PlayerBackend.Mpv),
            ),
        )
    }
}
