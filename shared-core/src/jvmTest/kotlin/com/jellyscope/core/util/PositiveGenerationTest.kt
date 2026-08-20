// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PositiveGenerationTest {
    @Test
    fun wrapsOnlyAfterThePositiveRange() {
        assertEquals(1L, nextPositiveGeneration(0L))
        assertEquals(42L, nextPositiveGeneration(41L))
        assertEquals(1L, nextPositiveGeneration(Long.MAX_VALUE))
    }

    @Test
    fun rejectsNegativeGenerations() {
        assertFailsWith<IllegalArgumentException> {
            nextPositiveGeneration(-1L)
        }
    }
}
