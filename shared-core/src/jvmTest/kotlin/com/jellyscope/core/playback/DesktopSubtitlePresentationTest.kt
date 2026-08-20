// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.SubtitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopSubtitlePresentationTest {
    @Test
    fun vlcInstanceUsesItsDefaultSubtitlePlacement() {
        assertFalse(desktopVlcInstanceArguments().any { argument -> argument.startsWith("--sub-margin") })
    }

    @Test
    fun mpvUsesItsNativeDefaultWhenClearanceIsInactive() {
        assertEquals(
            MPV_SUBTITLE_BASELINE_MARGIN_PX.toString(),
            SubtitleStyle().toMpvSubtitleProperties()["sub-margin-y"],
        )
    }

    @Test
    fun activeClearanceAddsOnlyTheControlsOffsetToTheNativeDefault() {
        assertEquals(
            MPV_SUBTITLE_BASELINE_MARGIN_PX + MPV_SUBTITLE_CONTROLS_OFFSET_PX,
            resolveMpvSubtitleBottomMargin(clearanceActive = true),
        )
        assertEquals(180, resolveMpvSubtitleBottomMargin(clearanceActive = true))
        assertEquals(34, resolveMpvSubtitleBottomMargin(clearanceActive = false))
    }
}
