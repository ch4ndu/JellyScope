// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveSeriesEpisodesTest {
    @Test
    fun effectiveFocusedEpisodeKeepsValidSelection() {
        assertEquals(
            "episode-2",
            effectiveFocusedEpisodeId(
                focusedEpisodeId = "episode-2",
                validEpisodeIds = setOf("episode-1", "episode-2"),
                defaultEpisodeId = "episode-1",
            ),
        )
    }

    @Test
    fun effectiveFocusedEpisodeFallsBackForMissingOrStaleSelection() {
        val episodeIds = setOf("episode-1", "episode-2")

        assertEquals("episode-1", effectiveFocusedEpisodeId(null, episodeIds, "episode-1"))
        assertEquals("episode-1", effectiveFocusedEpisodeId("old-season-episode", episodeIds, "episode-1"))
        assertEquals(null, effectiveFocusedEpisodeId("old-season-episode", episodeIds, null))
    }
}
