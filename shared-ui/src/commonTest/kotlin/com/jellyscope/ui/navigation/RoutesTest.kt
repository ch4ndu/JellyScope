// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutesTest {
    @Test
    fun topLevelNavItemsExposeHomeLibraryDiscoverFindAndDownloads() {
        // Settings was moved off the bottom bar to a top-right gear on root
        // screens; the Library tab took its place. Downloads is the durable
        // local-only account shelf.
        assertEquals(
            listOf(Routes.Home, Routes.LibraryRoot, Routes.Discover, Routes.Find, Routes.Downloads),
            TopLevelNavItems.map { item -> item.route },
        )
    }

    @Test
    fun downloadsNavigationFollowsTheEffectiveSessionPermission() {
        assertFalse(topLevelNavItems(enableContentDownloading = false).any { item -> item.route == Routes.Downloads })
        assertTrue(topLevelNavItems(enableContentDownloading = true).any { item -> item.route == Routes.Downloads })
    }

    @Test
    fun mobileRouteBuildersEncodeNestedDestinations() {
        assertEquals("series-graph/series-1/overview", Routes.series("series-1"))
        assertEquals("series-graph/series-1/season/season-1", Routes.season("series-1", "season-1"))
        assertEquals("collection/collection-1?title=Favorites", Routes.collection("collection-1", "Favorites"))
        assertEquals("person/person-1", Routes.person("person-1"))
        assertEquals("library-filter?title=Sci%20Fi&genre=Sci%20Fi", Routes.filteredLibraryForGenre("Sci Fi"))
        assertEquals(
            "library-filter?title=Studio%201&studioId=studio-1",
            Routes.filteredLibraryForStudio("studio-1", "Studio 1"),
        )
        assertEquals(
            "library/library-1?title=TV%20Shows&libraryType=TvShows",
            Routes.library("library-1", "TV Shows", LibraryCollectionType.TvShows),
        )
        assertEquals("grid/Favorites", Routes.grid("Favorites"))
        assertEquals("grid/Favorites", Routes.viewAll("Favorites"))
        assertEquals(
            "player/item-1?startTicks=12&queue=item-1,item-2",
            Routes.player(
                itemId = "item-1",
                startTicks = 12L,
                mediaSourceId = null,
                queue = listOf("item-1", "item-2"),
            ),
        )
        assertEquals(
            "player/item-1?startTicks=12&msId=source-1&audioStreamIndex=3&subtitleStreamIndex=4",
            Routes.player(
                itemId = "item-1",
                startTicks = 12L,
                mediaSourceId = "source-1",
                initialAudioStreamIndex = 3,
                initialSubtitleSelection = SubtitleSelectionIntent.Track(4),
            ),
        )
        assertEquals(
            "player/item-1?startTicks=12&subtitleStreamIndex=-1",
            Routes.player(
                itemId = "item-1",
                startTicks = 12L,
                mediaSourceId = null,
                initialSubtitleSelection = SubtitleSelectionIntent.Off,
            ),
        )
        assertEquals(
            "player/item-1?startTicks=12&msId=source-1&subtitleAssetId=asset%2F1",
            Routes.player(
                itemId = "item-1",
                startTicks = 12L,
                mediaSourceId = "source-1",
                initialSubtitleSelection = SubtitleSelectionIntent.LocalAsset("asset/1"),
            ),
        )
        assertEquals(
            "player/item-1?startTicks=0&msId=source-1&offlineDownloadId=download_1",
            Routes.player(
                itemId = "item-1",
                startTicks = 0L,
                mediaSourceId = "source-1",
                offlineDownloadId = DownloadId("download_1"),
            ),
        )
        assertEquals(DownloadId("download_1"), Routes.offlineDownloadId("download_1"))
        assertEquals(null, Routes.offlineDownloadId("not a valid id"))
    }

    @Test
    fun initialDetailDeepLinkUsesTheNormalDetailRouteAndWinsOverPlaybackShortcut() {
        assertEquals(
            "detail/item-1",
            resolveInitialLoggedInRoute(
                initialDetailItemId = " item-1 ",
                initialPlaybackItemId = "item-2",
            ),
        )
        assertEquals(
            "player/item-2?startTicks=0",
            resolveInitialLoggedInRoute(
                initialDetailItemId = null,
                initialPlaybackItemId = "item-2",
            ),
        )
        assertEquals(
            null,
            resolveInitialLoggedInRoute(
                initialDetailItemId = " ",
                initialPlaybackItemId = null,
            ),
        )
    }
}
