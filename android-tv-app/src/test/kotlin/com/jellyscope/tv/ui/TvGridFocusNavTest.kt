// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TvGridFocusNavTest {
    @Test
    fun stalePendingFocusDoesNotRequestTheSupersededTarget() =
        runTest {
            val requested = mutableListOf<FocusRequester>()
            val requester = FocusRequester()
            val nav =
                TvGridFocusNav(
                    gridState = LazyGridState(),
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    requestFocus = { target ->
                        requested += target
                        true
                    },
                )
            nav.update(
                itemCount = 20,
                columnCount = 4,
                hasMore = false,
                revealComposedTargets = false,
                onLoadMore = {},
            )

            nav.requestFocus(5)
            nav.register(5, requester)
            nav.requestFocus(11)
            advanceUntilIdle()

            assertEquals(0, requested.size)
            assertEquals(11, nav.pendingFocusIndex)
        }

    @Test
    fun freshPendingFocusRequestsTheRegisteredTargetOnce() =
        runTest {
            val requested = mutableListOf<FocusRequester>()
            val requester = FocusRequester()
            val nav =
                TvGridFocusNav(
                    gridState = LazyGridState(),
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    requestFocus = { target ->
                        requested += target
                        true
                    },
                )
            nav.update(
                itemCount = 20,
                columnCount = 4,
                hasMore = false,
                revealComposedTargets = false,
                onLoadMore = {},
            )

            nav.requestFocus(5)
            nav.register(5, requester)
            advanceUntilIdle()

            assertEquals(listOf(requester), requested)
            assertNull(nav.pendingFocusIndex)
        }
}
