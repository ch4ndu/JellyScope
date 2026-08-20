// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import org.junit.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertEquals

class TvPlaybackHealthGuidancePolicyTest {
    @Test
    fun tvAppRootResolvesActionablePlaybackHealthGuidance() {
        val application = koinApplication { modules(tvAppModule) }
        try {
            assertEquals(
                PlaybackHealthGuidancePolicy.Actionable,
                application.koin.get<PlaybackHealthGuidancePolicy>(),
            )
        } finally {
            application.close()
        }
    }
}
