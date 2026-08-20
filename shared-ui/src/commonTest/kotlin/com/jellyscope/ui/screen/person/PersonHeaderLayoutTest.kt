// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.person

import androidx.compose.ui.unit.dp
import com.jellyscope.ui.adaptive.WindowWidthTier
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonHeaderLayoutTest {
    @Test
    fun compactUsesStackedBackdropWhileEveryLargerTierUsesPortrait() {
        assertEquals(PersonHeaderLayout.StackedBackdrop, WindowWidthTier.Compact.personHeaderLayout())
        assertEquals(PersonHeaderLayout.Portrait, WindowWidthTier.Medium.personHeaderLayout())
        assertEquals(PersonHeaderLayout.Portrait, WindowWidthTier.Expanded.personHeaderLayout())
        assertEquals(PersonHeaderLayout.Portrait, WindowWidthTier.XLarge.personHeaderLayout())
        assertEquals(
            PersonHeaderLayout.StackedBackdrop,
            WindowWidthTier.fromAvailableWidth(599.dp).personHeaderLayout(),
        )
        assertEquals(
            PersonHeaderLayout.Portrait,
            WindowWidthTier.fromAvailableWidth(600.dp).personHeaderLayout(),
        )
    }
}
