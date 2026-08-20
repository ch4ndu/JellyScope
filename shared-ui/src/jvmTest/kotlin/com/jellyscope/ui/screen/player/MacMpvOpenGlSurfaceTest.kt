// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertFalse

class MacMpvOpenGlSurfaceTest {
    @Test
    fun hostDoesNotParticipateInAwtHitTestingOrFocus() {
        val host =
            MacMpvOpenGlHost(
                onSurfaceReady = {},
                onSurfaceUnavailable = { _, _ -> },
                onSurfaceResized = {},
                requestDetach = { _, onDetached -> onDetached() },
            )

        assertFalse(host.isFocusable)
        assertFalse(host.contains(10, 10))
    }
}
