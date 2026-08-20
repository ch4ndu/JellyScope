// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class MediaCardMapperTest {
    @Test
    fun mediaItemMapperUsesHomeGridSupersetFields() {
        val card =
            MediaItem(
                id = "item-1",
                name = "The Episode",
                kind = MediaKind.Episode,
                seriesName = "The Series",
                parentIndexNumber = 2,
                indexNumber = 3,
                runtime = 45.minutes,
                productionYear = 2026,
                played = false,
                isFavorite = true,
                playedPercentage = 37.5,
                playbackPositionTicks = 123_000_000L,
                unplayedItemCount = 4,
                imageRefs =
                    ImageRefs(
                        primaryTag = "primary",
                        backdropTag = "backdrop",
                    ),
                overview = "Episode overview",
                genres = listOf("Drama", "Mystery"),
                officialRating = "TV-14",
                communityRating = 7.4,
            ).toMediaCardUi(
                session = session,
                imageUrlBuilder = JellyfinImageUrlBuilder(),
            )

        assertEquals("item-1", card.id)
        assertEquals("The Episode", card.title)
        assertEquals("The Series S2E3", card.subtitle)
        assertEquals("The Series", card.seriesName)
        assertEquals("2026", card.year)
        assertEquals("45 min", card.runtimeText)
        assertEquals("★ 7.4 · 2026 · TV-14 · Drama, Mystery", card.heroMetadataLine)
        assertEquals(
            TvHeroMetadata(
                seriesName = "The Series",
                episodeLabel = "S2E3",
                yearOrFallback = "2026",
                runtimeText = "45 min",
                progressPercent = 37,
                watched = false,
                unsupported = false,
            ),
            card.tvHeroMetadata(),
        )
        assertEquals("Episode overview", card.overview)
        assertEquals(listOf("Drama", "Mystery"), card.genres)
        assertEquals("Drama, Mystery", card.genresLine)
        assertEquals(0.375f, card.progressFraction)
        assertEquals(true, card.isFavorite)
        assertEquals(123_000_000L, card.resumePositionTicks)
        assertEquals(4, card.unplayedCount)
        assertEquals(
            "https://jellyfin.example/Items/item-1/Images/Primary?tag=primary&maxWidth=300&quality=90",
            card.imageUrl,
        )
        assertEquals(
            "https://jellyfin.example/Items/item-1/Images/Backdrop?tag=backdrop&maxWidth=1280&quality=90",
            card.backdropUrl,
        )
        assertEquals(MediaCardKind.Episode, card.kind)
    }
}

private val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Server",
        userId = "user-1",
        userName = "User",
        accessToken = "token",
        deviceId = "device-1",
    )
