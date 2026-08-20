// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmbientPaletteTest {
    @Test
    fun emptyAndTransparentSamplesHaveNoAmbientColor() {
        assertNull(ambientColorFromArgbSamples(emptyList()))
        assertNull(ambientColorFromArgbSamples(listOf(0x00ff0000)))
    }

    @Test
    fun frequentColorWinsAndIsNormalizedForDarkBackdrop() {
        val color =
            assertNotNull(
                ambientColorFromArgbSamples(
                    List(12) { 0xffff2020.toInt() } + List(2) { 0xff2060ff.toInt() },
                ),
            )

        assertTrue(color.red > color.blue)
        assertTrue(color.red > color.green)
        assertTrue(color.red in 0.30f..0.60f)
        assertEquals(1f, color.alpha)
    }
}
