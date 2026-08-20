// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchNextCandidatesTest {
    @Test
    fun itemInBothLegsAppearsOnceTaggedContinueWatching() {
        val item = item("shared", playbackPositionTicks = 1L)

        val candidates = selectWatchNextCandidates(listOf(item), listOf(item("shared")), limit = 20)

        assertEquals(1, candidates.size)
        assertEquals(WatchNextOrigin.ContinueWatching, candidates.single().origin)
        assertEquals("shared", candidates.single().item.id)
    }

    @Test
    fun zeroOrNullResumePositionsAreExcludedFromContinueWatching() {
        val candidates =
            selectWatchNextCandidates(
                continueWatching =
                    listOf(
                        item("zero", playbackPositionTicks = 0L),
                        item("null", playbackPositionTicks = null),
                    ),
                nextUp = emptyList(),
                limit = 20,
            )

        assertEquals(emptyList(), candidates)
    }

    @Test
    fun playedAndNonPlayableItemsAreExcludedFromBothLegs() {
        val candidates =
            selectWatchNextCandidates(
                continueWatching =
                    listOf(
                        item("played", playbackPositionTicks = 1L, played = true),
                        item("series", kind = MediaKind.Series, playbackPositionTicks = 1L),
                    ),
                nextUp =
                    listOf(
                        item("played-next", played = true),
                        item("other", kind = MediaKind.Other),
                    ),
                limit = 20,
            )

        assertEquals(emptyList(), candidates)
    }

    @Test
    fun capIsAppliedAfterDeduplication() {
        val candidates =
            selectWatchNextCandidates(
                continueWatching = listOf(item("first", playbackPositionTicks = 1L)),
                nextUp = listOf(item("first"), item("second"), item("third")),
                limit = 2,
            )

        assertEquals(listOf("first", "second"), candidates.map { candidate -> candidate.item.id })
    }

    @Test
    fun orderingPreservesEachSourceAndContinueWatchingPrecedence() {
        val candidates =
            selectWatchNextCandidates(
                continueWatching =
                    listOf(
                        item("continue-1", playbackPositionTicks = 1L),
                        item("continue-2", playbackPositionTicks = 1L),
                    ),
                nextUp = listOf(item("next-1"), item("next-2")),
                limit = 20,
            )

        assertEquals(
            listOf("continue-1", "continue-2", "next-1", "next-2"),
            candidates.map { candidate -> candidate.item.id },
        )
        assertEquals(
            listOf(
                WatchNextOrigin.ContinueWatching,
                WatchNextOrigin.ContinueWatching,
                WatchNextOrigin.NextUp,
                WatchNextOrigin.NextUp,
            ),
            candidates.map { candidate -> candidate.origin },
        )
    }

    private fun item(
        id: String,
        kind: MediaKind = MediaKind.Movie,
        played: Boolean = false,
        playbackPositionTicks: Long? = null,
    ): MediaItem =
        MediaItem(
            id = id,
            name = id,
            kind = kind,
            played = played,
            playbackPositionTicks = playbackPositionTicks,
        )
}
