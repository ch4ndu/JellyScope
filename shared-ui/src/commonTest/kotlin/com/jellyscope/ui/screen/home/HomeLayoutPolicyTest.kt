// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeLayoutPolicyTest {
    @Test
    fun xlargeOmitsFeaturedCarouselWhileSmallerTouchLayoutsKeepIt() {
        assertFalse(shouldShowFeaturedHero(WindowWidthTier.XLarge, featuredAvailable = true))
        assertTrue(shouldShowFeaturedHero(WindowWidthTier.Expanded, featuredAvailable = true))
        assertTrue(shouldShowFeaturedHero(WindowWidthTier.Compact, featuredAvailable = true))
        assertFalse(shouldShowFeaturedHero(WindowWidthTier.Compact, featuredAvailable = false))
    }

    @Test
    fun visibleRowsDropEmptyShelvesButKeepLoadingErrorAndContentInRenderOrder() {
        val state =
            HomeUiState(
                nextUp = RowState.Content(listOf(mediaCard("n"))),
                continueWatching = RowState.Empty,
                recentlyAdded = RowState.Loading,
                favorites = RowState.Error,
            )

        assertEquals(
            listOf(HomeRow.NextUp, HomeRow.RecentlyAdded, HomeRow.Favorites),
            state.visibleHomeRows(),
        )
    }

    @Test
    fun emptyHomeOnlyWhenEveryShelfIsEmptyAndNoFeaturedHero() {
        val allEmpty =
            HomeUiState(
                featured = RowState.Empty,
                nextUp = RowState.Empty,
                continueWatching = RowState.Empty,
                recentlyAdded = RowState.Empty,
                favorites = RowState.Empty,
            )

        assertTrue(allEmpty.visibleHomeRows().isEmpty())
        assertTrue(allEmpty.shouldShowEmptyHome(hasFeaturedHeroSlot = false))
        // A featured hero is still something to show, so no empty-home fallback.
        assertFalse(allEmpty.shouldShowEmptyHome(hasFeaturedHeroSlot = true))
    }

    @Test
    fun stillLoadingOrErroredShelvesSuppressTheEmptyHomeFallback() {
        // Default state is all-Loading: during load the screen must not flash the
        // empty-home message.
        assertFalse(HomeUiState().shouldShowEmptyHome(hasFeaturedHeroSlot = false))

        val onlyError =
            HomeUiState(
                featured = RowState.Empty,
                nextUp = RowState.Empty,
                continueWatching = RowState.Empty,
                recentlyAdded = RowState.Empty,
                favorites = RowState.Error,
            )
        assertFalse(onlyError.shouldShowEmptyHome(hasFeaturedHeroSlot = false))
    }

    private fun mediaCard(id: String): MediaCardUi =
        MediaCardUi(
            id = id,
            title = id,
            subtitle = null,
            progressFraction = null,
            watched = false,
            unplayedCount = null,
            imageUrl = null,
            kind = MediaCardKind.Movie,
        )
}
