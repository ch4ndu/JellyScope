// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.adaptive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveLayoutTest {
    @Test
    fun onlyIntermediateTiersUseTheSharedContentCap() {
        assertFalse(WindowWidthTier.Compact.usesCappedContentPane())
        assertTrue(WindowWidthTier.Medium.usesCappedContentPane())
        assertTrue(WindowWidthTier.Expanded.usesCappedContentPane())
        assertFalse(WindowWidthTier.XLarge.usesCappedContentPane())
    }
}
