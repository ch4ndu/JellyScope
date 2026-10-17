// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaCardSemanticStateTest {
    @Test
    fun exposesCombinedMediaStateAndClampsProgress() {
        assertEquals(
            MediaCardSemanticState(
                watched = true,
                unsupported = true,
                progressPercent = 100,
            ),
            mediaCardSemanticState(
                watched = true,
                supported = false,
                progressFraction = 1.4f,
            ),
        )
        assertEquals(
            0,
            mediaCardSemanticState(
                watched = false,
                supported = true,
                progressFraction = -0.2f,
            ).progressPercent,
        )
        assertEquals(
            null,
            mediaCardSemanticState(
                watched = false,
                supported = true,
                progressFraction = Float.NaN,
            ).progressPercent,
        )
    }
}
