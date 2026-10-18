// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlin.test.Test
import kotlin.test.assertEquals

class TolerantEnumParserTest {
    private enum class Example {
        First,
        Second,
    }

    @Test
    fun tolerantEnumParserPreservesExactMatchesAndReturnsNullForUnreadableNames() {
        val cases =
            listOf(
                "First" to Example.First,
                "Second" to Example.Second,
                "Unknown" to null,
                "first" to null,
                "" to null,
                null to null,
            )

        cases.forEach { (storedName, expected) ->
            assertEquals(expected, storedName.toTolerantEnumOrNull<Example>(), storedName)
        }
    }
}
