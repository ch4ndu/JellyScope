// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.ui.graphics.Color
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.LogScrubber
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TvHeroAmbientStateTest {
    @Test
    fun ownerChangeExposesNewOwnerWithoutPriorColor() {
        val ownerA = Any()
        val ownerB = Any()
        val stateA =
            applyTvHeroAmbientColor(
                state = selectTvHeroAmbientOwner(ownerA, transition = 1L),
                ownerKey = ownerA,
                transition = 1L,
                color = Color.Red,
            )

        val stateB = selectTvHeroAmbientOwner(ownerB, transition = 2L)

        assertSame(ownerB, stateB.ownerKey)
        assertNull(stateB.color)
        assertEquals(Color.Red, stateA.color)
    }

    @Test
    fun latePriorOwnerResultIsRejected() {
        val ownerA = Any()
        val ownerB = Any()
        val stateB = selectTvHeroAmbientOwner(ownerB, transition = 2L)

        val result =
            applyTvHeroAmbientColor(
                state = stateB,
                ownerKey = ownerA,
                transition = 1L,
                color = Color.Red,
            )

        assertSame(stateB, result)
        assertNull(result.color)
    }

    @Test
    fun matchingCurrentOwnerResultIsAccepted() {
        val ownerB = Any()
        val stateB = selectTvHeroAmbientOwner(ownerB, transition = 2L)

        val result =
            applyTvHeroAmbientColor(
                state = stateB,
                ownerKey = ownerB,
                transition = 2L,
                color = Color.Blue,
            )

        assertSame(ownerB, result.ownerKey)
        assertEquals(Color.Blue, result.color)
    }

    @Test
    fun failedAmbientExtractionIsAcceptedAsSafeStructuredDiagnostic() {
        val diagnostic = formatAmbientColorFailureDiagnostic(IllegalStateException("poster title and url"))
        val scrubbed = LogScrubber.capture(DiagnosticTag.AmbientColor.wireValue, diagnostic)

        assertEquals(diagnostic, scrubbed)
        assertEquals(true, diagnostic.contains("stage=ambient-color"))
        assertEquals(true, diagnostic.contains("event=failed"))
        assertEquals(true, diagnostic.contains("operation=ambientColorExtraction"))
        assertEquals(false, diagnostic.contains("poster title"))
        assertEquals(false, diagnostic.contains("url"))
    }
}
