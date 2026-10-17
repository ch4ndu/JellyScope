// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.ui.theme.Dimensions
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailActionChromeTest {
    @Test
    fun touchActionsUseTheSharedMinimumWithoutChangingDpadHeight() {
        assertEquals(Dimensions.minTouchTarget, detailActionMinimumHeight(dpad = false))
        assertEquals(DetailDimens.detailActionHeight, detailActionMinimumHeight(dpad = true))
    }
}
