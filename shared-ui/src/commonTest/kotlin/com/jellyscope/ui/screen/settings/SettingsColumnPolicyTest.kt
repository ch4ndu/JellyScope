// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsColumnPolicyTest {
    @Test
    fun contentWidthUsesTheOneToFourColumnBoundaries() {
        assertEquals(1, settingsColumnCount(659.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 4))
        assertEquals(2, settingsColumnCount(660.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 4))
        assertEquals(2, settingsColumnCount(999.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 4))
        assertEquals(3, settingsColumnCount(1_000.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 4))
        assertEquals(3, settingsColumnCount(1_339.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 4))
        assertEquals(4, settingsColumnCount(1_340.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 4))
    }

    @Test
    fun columnCountRespectsTheMaximum() {
        assertEquals(1, settingsColumnCount(2_000.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 1))
        assertEquals(2, settingsColumnCount(2_000.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 2))
        assertEquals(3, settingsColumnCount(1_000.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 3))
    }

    @Test
    fun zeroAndUnboundedWidthUseOneColumn() {
        assertEquals(1, settingsColumnCount(0.dp, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 2))
        assertEquals(
            1,
            settingsColumnCount(Dp(Float.POSITIVE_INFINITY), minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 2),
        )
        // Dp.Unspecified is NaN-backed, so it must be rejected by the same guard rather
        // than falling through the comparison and producing a nonsense count.
        assertEquals(1, settingsColumnCount(Dp.Unspecified, minColumnWidth = 320.dp, gap = 20.dp, maxColumns = 2))
    }
}
