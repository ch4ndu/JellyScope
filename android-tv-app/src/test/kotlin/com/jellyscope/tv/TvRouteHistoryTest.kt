// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import com.jellyscope.tv.ui.focus.TvRouteEntryId
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TvRouteHistoryTest {
    @Test
    fun nestedDetailPersonHistoryPopsInExactReverseOrder() {
        val home = TvRouteSnapshot(route = TvRoute.Home.name)
        val detailA = TvRouteSnapshot(route = TvRoute.Detail.name, detailItemId = "detail-a")
        val personX = TvRouteSnapshot(route = TvRoute.Person.name, personItemId = "person-x")
        val history =
            TvRouteHistory()
                .push(home)
                .push(detailA)
                .push(personX)

        val personPop = assertNotNull(history.pop())
        assertEquals(personX, personPop.snapshot)
        val detailPop = assertNotNull(personPop.history.pop())
        assertEquals(detailA, detailPop.snapshot)
        val homePop = assertNotNull(detailPop.history.pop())
        assertEquals(home, homePop.snapshot)
        assertEquals(null, homePop.history.pop())
    }

    @Test
    fun routePayloadSurvivesSaveAndRestore() {
        val expected =
            TvRouteHistory(
                entries =
                    listOf(
                        TvRouteSnapshot(
                            route = TvRoute.Series.name,
                            seriesItemId = "series-1",
                            seasonItemId = "season-2",
                        ),
                        TvRouteSnapshot(
                            route = TvRoute.Collection.name,
                            collectionItemId = "collection-3",
                            focusPath =
                                com.jellyscope.tv.ui.focus.TvFocusPath(
                                    scopes = listOf("collection", "grid"),
                                    targetKind = com.jellyscope.tv.ui.focus.TvFocusTargetKind.Item,
                                    targetKey = "item:collection-item-3",
                                    fallbackIndex = 3,
                                ),
                        ),
                        TvRouteSnapshot(
                            route = TvRoute.Player.name,
                            playerItemId = "episode-3",
                            playerMediaSourceId = "source-4",
                            playerInitialAudioStreamIndex = 5,
                            playerInitialSubtitleStreamIndex = 6,
                            playerInitialSubtitleAssetId = "local-asset-7",
                            playerStartTicks = 7L,
                            playerRouteKey = 8,
                            playerQueueIds = listOf("episode-3", "episode-4"),
                        ),
                    ),
            )
        val bytes =
            ByteArrayOutputStream().use { output ->
                ObjectOutputStream(output).use { stream -> stream.writeObject(expected) }
                output.toByteArray()
            }

        val restored =
            ObjectInputStream(ByteArrayInputStream(bytes)).use { stream ->
                stream.readObject() as TvRouteHistory
            }

        assertEquals(expected, restored)
    }

    @Test
    fun seriesViewModelIsRetainedOnlyWhileSeriesFlowIsCurrentOrInHistory() {
        val seriesHistory =
            TvRouteHistory().push(
                TvRouteSnapshot(
                    route = TvRoute.Series.name,
                    seriesItemId = "series-1",
                ),
            )

        assertTrue(shouldRetainTvSeriesSeasonViewModel(TvRoute.Season.name, TvRouteHistory()))
        assertTrue(shouldRetainTvSeriesSeasonViewModel(TvRoute.Detail.name, seriesHistory))
        assertFalse(shouldRetainTvSeriesSeasonViewModel(TvRoute.Home.name, TvRouteHistory()))
    }

    @Test
    fun topLevelResetClearsNestedHistory() {
        val nestedHistory =
            TvRouteHistory()
                .push(TvRouteSnapshot(route = TvRoute.Home.name))
                .push(TvRouteSnapshot(route = TvRoute.Detail.name, detailItemId = "detail-1"))

        assertEquals(emptyList(), nestedHistory.clear().entries)
    }

    @Test
    fun retainedDetailEntriesIncludeActiveAndHistoricalDetailsOnly() {
        val activeId = TvRouteEntryId(4)
        val history =
            TvRouteHistory(
                entries =
                    listOf(
                        TvRouteSnapshot(route = TvRoute.Home.name, routeEntryId = TvRouteEntryId(1)),
                        TvRouteSnapshot(route = TvRoute.Detail.name, routeEntryId = TvRouteEntryId(2), detailItemId = "parent"),
                        TvRouteSnapshot(route = TvRoute.Person.name, routeEntryId = TvRouteEntryId(3)),
                    ),
            )

        assertEquals(
            setOf(TvRouteEntryId(2), activeId),
            retainedDetailEntryIds(TvRoute.Detail.name, activeId, history),
        )
        assertEquals(
            setOf(TvRouteEntryId(2)),
            retainedDetailEntryIds(TvRoute.Player.name, activeId, history),
        )
    }

    @Test
    fun detailRenderKeyCapturesItemPayload() {
        val parent = TvRouteRenderKey(TvRoute.Detail.name, TvRouteEntryId(7), detailItemId = "parent")
        val child = TvRouteRenderKey(TvRoute.Detail.name, TvRouteEntryId(8), detailItemId = "child")

        assertEquals("parent", parent.detailItemId)
        assertEquals("child", child.detailItemId)
        assertFalse(parent == child)
    }

    @Test
    fun seriesSeasonRenderKeyCapturesPayloadAndSeasonTabDoesNotChangeContentIdentity() {
        val a =
            TvRouteRenderKey(
                TvRoute.Season.name,
                TvRouteEntryId(3),
                seriesItemId = "series-1",
                seasonItemId = "s1",
            )
        val tabSwitch = a.copy(seasonItemId = "s2")
        val otherSeries = a.copy(entryId = TvRouteEntryId(4), seriesItemId = "series-2")

        assertNotEquals(a, tabSwitch)
        assertEquals(a.contentIdentity(), tabSwitch.contentIdentity())
        assertNotEquals(a.contentIdentity(), otherSeries.contentIdentity())
        assertNotEquals(a.contentIdentity(), a.copy(route = TvRoute.Series.name).contentIdentity())
    }
}
