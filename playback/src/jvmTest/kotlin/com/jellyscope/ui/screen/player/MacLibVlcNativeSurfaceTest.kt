// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MacLibVlcNativeSurfaceTest {
    @Test
    fun hostUsesBlackNonInteractiveAwtFallback() {
        val host =
            MacLibVlcNativeHost(
                onSurfaceReady = {},
                onSurfaceUnavailable = {},
                onSurfaceResized = { _, _, _ -> },
                requestDetach = { _, onDetached -> onDetached() },
            )

        assertEquals(java.awt.Color.BLACK, host.background)
        assertFalse(host.isFocusable)
        assertFalse(host.contains(10, 10))
    }

    @Test
    fun awtTopLeftCoordinatesConvertToBoundedAppKitFrame() {
        val frame =
            calculateMacAppKitViewFrame(
                contentX = 24,
                contentY = 40,
                contentHeight = 600,
                width = 960,
                height = 400,
            )

        assertEquals(24.0, frame.origin.x)
        assertEquals(160.0, frame.origin.y)
        assertEquals(960.0, frame.size.width)
        assertEquals(400.0, frame.size.height)
    }

    @Test
    fun invalidGeometryClampsInsideTheContentOrigin() {
        val frame =
            calculateMacAppKitViewFrame(
                contentX = -10,
                contentY = 700,
                contentHeight = 600,
                width = -1,
                height = 200,
            )

        assertEquals(0.0, frame.origin.x)
        assertEquals(0.0, frame.origin.y)
        assertEquals(0.0, frame.size.width)
        assertEquals(200.0, frame.size.height)
    }
}
