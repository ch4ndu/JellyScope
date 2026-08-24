// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppleLocalSubtitleFileStoreTest {
    @Test
    fun deletionPolicyTreatsAnAbsentTargetAsSuccessAndReportsAStillPresentFailure() {
        requireLocalSubtitleFileDeleted(
            removalSucceeded = false,
            targetStillExists = false,
        )

        val failure =
            assertFailsWith<IllegalStateException> {
                requireLocalSubtitleFileDeleted(
                    removalSucceeded = false,
                    targetStillExists = true,
                )
            }

        assertEquals("Unable to delete local subtitle file.", failure.message)
    }
}
