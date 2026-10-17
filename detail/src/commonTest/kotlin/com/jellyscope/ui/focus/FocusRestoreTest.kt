// SPDX-License-Identifier: MPL-2.0
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jellyscope.ui.focus

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.BringIntoViewSpec
import com.jellyscope.ui.screen.detail.DetailFocusContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FocusRestoreTest {
    @Test
    fun restoreAwareSpecSuppressesOnlyTheOwnedHandoff() {
        var handoff = false
        val delegate =
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> = snap()

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float = offset + size + containerSize
            }
        val spec = RestoreAwareBringIntoViewSpec(delegate) { handoff }

        assertEquals(19f, spec.calculateScrollDistance(3f, 5f, 11f))
        assertEquals(delegate.scrollAnimationSpec, spec.scrollAnimationSpec)
        handoff = true
        assertEquals(0f, spec.calculateScrollDistance(3f, 5f, 11f))
        handoff = false
        assertEquals(19f, spec.calculateScrollDistance(3f, 5f, 11f))
    }

    @Test
    fun stableKeyWinsAndMissingKeyFallsBackWithinTheRibbon() {
        assertEquals(
            FocusRestoreTarget.Exact("c", 2),
            resolveFocusRestoreTarget(
                FocusRestoreRequest("c", fallbackSemanticIndex = 0),
                listOf("a", "b", "c"),
            ),
        )
        assertEquals(
            FocusRestoreTarget.Fallback("b", 1),
            resolveFocusRestoreTarget(
                FocusRestoreRequest("gone", fallbackSemanticIndex = 1),
                listOf("a", "b", "c"),
            ),
        )
    }

    @Test
    fun loadingDefersAndAnEmptyReadyRibbonIsUnavailable() {
        assertEquals(
            FocusRestoreTarget.Deferred,
            resolveFocusRestoreTarget(
                FocusRestoreRequest("later", fallbackSemanticIndex = 2),
                emptyList(),
                contentReady = false,
            ),
        )
        assertEquals(
            FocusRestoreTarget.Unavailable,
            resolveFocusRestoreTarget(
                FocusRestoreRequest("gone", fallbackSemanticIndex = 0),
                emptyList(),
            ),
        )
    }

    @Test
    fun cancellationClearsHandoffWithoutReportingFocus() =
        runTest {
            val container = DetailFocusContainer()

            assertFailsWith<CancellationException> {
                container.restoreFocus(
                    request = FocusRestoreRequest("target", 0),
                    semanticKeys = listOf("target"),
                    lazySlotIndex = { it },
                    revealCentered = { throw CancellationException("route left") },
                    retries = 0,
                )
            }
            assertFalse(container.restoreHandoffActive)
        }

    @Test
    fun failedFocusClearsHandoffAndReturnsFalse() =
        runTest {
            val container = DetailFocusContainer()

            val focused =
                container.restoreFocus(
                    request = FocusRestoreRequest("target", 0),
                    semanticKeys = listOf("target"),
                    lazySlotIndex = { it },
                    revealCentered = {},
                    retries = 0,
                )

            assertFalse(focused)
            assertFalse(container.restoreHandoffActive)
        }
}
