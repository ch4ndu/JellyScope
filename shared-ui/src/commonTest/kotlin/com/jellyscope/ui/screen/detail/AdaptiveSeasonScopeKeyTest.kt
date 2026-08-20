// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AdaptiveSeasonScopeKeyTest {
    @Test
    fun seasonScopeKeysAreIsolatedAndStable() {
        assertNotEquals(seasonScopeKey("s1", "episodes"), seasonScopeKey("s2", "episodes"))
        assertEquals("season/s1/episodes", seasonScopeKey("s1", "episodes"))
        assertEquals("season/unselected/cast", seasonScopeKey(null, "cast"))
    }
}
