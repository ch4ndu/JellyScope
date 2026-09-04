// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.TrickplayInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The regression guard for the whole projection-memoization change.
 *
 * What matters is instance **identity**: under strong skipping an equal-but-newly
 * allocated list is still a changed parameter, so every publish used to invalidate
 * every consumer of these lists. `assertSame` is therefore the assertion — an
 * `assertEquals` here would pass even if the cache were removed entirely.
 */
class PlayerProjectionCacheTest {
    private val streams =
        listOf(
            stream(index = 0, type = "Video"),
            stream(index = 1, type = "Audio", language = "eng", isDefault = true),
            stream(index = 2, type = "Audio", language = "spa"),
            stream(index = 3, type = "Subtitle", codec = "subrip", language = "eng"),
        )

    @Test
    fun repeatedCallsWithTheSameInputsReturnTheSameInstances() {
        val cache = PlayerProjectionCache()

        val audio = cache.audioTrackOptions("item-1", streams)
        val subtitles = cache.subtitleTrackOptions("item-1", streams)
        val quality = cache.qualityRungs("item-1", 8_000_000L)

        assertSame(audio, cache.audioTrackOptions("item-1", streams))
        assertSame(subtitles, cache.subtitleTrackOptions("item-1", streams))
        assertSame(quality, cache.qualityRungs("item-1", 8_000_000L))
    }

    @Test
    fun anEqualButDistinctStreamListStillReusesTheInstances() {
        // The ViewModel reassigns `mediaStreams` on paths that do not change the
        // tracks (a re-plan of the same source, for instance). A structurally equal
        // list must not invalidate the projections, or the memoization would do
        // nothing on exactly the publishes it exists for.
        val cache = PlayerProjectionCache()
        val audio = cache.audioTrackOptions("item-1", streams)

        assertSame(audio, cache.audioTrackOptions("item-1", streams.toList()))
    }

    @Test
    fun changedStreamsProduceNewInstances() {
        val cache = PlayerProjectionCache()
        val audio = cache.audioTrackOptions("item-1", streams)

        val withExtraAudio = streams + stream(index = 4, type = "Audio", language = "fra")
        val updated = cache.audioTrackOptions("item-1", withExtraAudio)

        assertNotSame(audio, updated)
        assertEquals(3, updated.size)
    }

    @Test
    fun aDifferentItemWithAnIdenticalTrackShapeStillProducesNewInstances() {
        // A queue advance must not hand the next item the previous item's lists:
        // selection state is matched against these, so silently reusing them would
        // make two different items share one identity.
        val cache = PlayerProjectionCache()
        val first = cache.audioTrackOptions("item-1", streams)
        val second = cache.audioTrackOptions("item-2", streams)

        assertNotSame(first, second)
        assertEquals(first, second)
    }

    @Test
    fun qualityRungsFollowTheSourceBitrate() {
        val cache = PlayerProjectionCache()
        val low = cache.qualityRungs("item-1", 2_000_000L)
        val high = cache.qualityRungs("item-1", 40_000_000L)

        assertNotSame(low, high)
        assertSame(high, cache.qualityRungs("item-1", 40_000_000L))
    }

    @Test
    fun trickplayTilesAreBuiltOnceForTheSameItemAndMetadata() {
        val cache = PlayerProjectionCache()
        var builds = 0
        val build = {
            builds++
            listOf("tile-0", "tile-1")
        }

        val tiles = cache.trickplayTileUrls("item-1", "https://server", "source-1", 320, 2, build)
        assertSame(tiles, cache.trickplayTileUrls("item-1", "https://server", "source-1", 320, 2, build))
        assertEquals(1, builds)

        // Any input the URLs are built from must invalidate them.
        cache.trickplayTileUrls("item-1", "https://server", "source-1", 320, 3, build)
        assertEquals(2, builds)
        cache.trickplayTileUrls("item-2", "https://server", "source-1", 320, 3, build)
        assertEquals(3, builds)
        cache.trickplayTileUrls("item-2", "https://other", "source-1", 320, 3, build)
        assertEquals(4, builds)
        cache.trickplayTileUrls("item-2", "https://other", "source-1", 480, 3, build)
        assertEquals(5, builds)
    }

    @Test
    fun nestedTrickplayManifestsUseTheActiveSourceAndInvalidateTiles() {
        val firstSource = trickplayInfo("source-a")
        val secondSource = trickplayInfo("source-b")
        val manifests = mapOf("source-a" to firstSource, "source-b" to secondSource)
        assertEquals(3, firstSource.tileCount)
        assertSame(firstSource, manifests.trickplayForMediaSource("source-a"))
        assertSame(secondSource, manifests.trickplayForMediaSource("source-b"))
        assertNull(manifests.trickplayForMediaSource("missing-source"))
        assertSame(
            firstSource,
            mapOf("source-a" to firstSource).trickplayForMediaSource("missing-source"),
        )

        val builder = JellyfinImageUrlBuilder()

        fun urlsFor(
            mediaSourceId: String,
            tileCount: Int,
        ) = List(tileCount) { index ->
            builder.trickplayTileUrl(
                serverUrl = "https://server",
                itemId = "item-1",
                mediaSourceId = mediaSourceId,
                width = 640,
                index = index,
            )
        }

        val cache = PlayerProjectionCache()
        var builds = 0
        val firstTiles =
            cache.trickplayTileUrls(
                "item-1",
                "https://server",
                firstSource.mediaSourceId,
                firstSource.width,
                firstSource.tileCount,
            ) {
                builds += 1
                urlsFor(firstSource.mediaSourceId, firstSource.tileCount)
            }
        assertEquals(3, firstTiles.size)
        assertEquals(
            "https://server/Videos/item-1/Trickplay/640/2.jpg?MediaSourceId=source-a",
            firstTiles.last(),
        )
        assertSame(
            firstTiles,
            cache.trickplayTileUrls(
                "item-1",
                "https://server",
                firstSource.mediaSourceId,
                firstSource.width,
                firstSource.tileCount,
            ) {
                error("Expected source-aware trickplay tiles to remain cached.")
            },
        )

        val secondTiles =
            cache.trickplayTileUrls(
                "item-1",
                "https://server",
                secondSource.mediaSourceId,
                secondSource.width,
                secondSource.tileCount,
            ) {
                builds += 1
                urlsFor(secondSource.mediaSourceId, secondSource.tileCount)
            }
        assertNotSame(firstTiles, secondTiles)
        assertEquals(
            "https://server/Videos/item-1/Trickplay/640/2.jpg?MediaSourceId=source-b",
            secondTiles.last(),
        )
        assertEquals(2, builds)
    }

    private fun trickplayInfo(mediaSourceId: String) =
        TrickplayInfo(
            mediaSourceId = mediaSourceId,
            resolutionKey = "640",
            width = 640,
            height = 360,
            tileWidth = 10,
            tileHeight = 10,
            thumbnailWidth = 640,
            thumbnailHeight = 360,
            thumbnailCount = 201,
            intervalMs = 10_000L,
        )

    @Test
    fun anItemWithoutTrickplayCachesItsEmptyResult() {
        val cache = PlayerProjectionCache()
        var builds = 0
        val build = {
            builds++
            emptyList<String>()
        }

        val tiles = cache.trickplayTileUrls("item-1", "https://server", null, null, null, build)

        assertTrue(tiles.isEmpty())
        assertSame(tiles, cache.trickplayTileUrls("item-1", "https://server", null, null, null, build))
        assertEquals(1, builds)
    }
}

private fun stream(
    index: Int? = null,
    type: String? = null,
    language: String? = null,
    codec: String? = null,
    isDefault: Boolean? = null,
) = PlaybackMediaStream(
    index = index,
    type = type,
    displayTitle = null,
    title = null,
    language = language,
    codec = codec,
    channelLayout = null,
    bitRate = null,
    height = null,
    isDefault = isDefault,
    isExternal = null,
    deliveryMethod = null,
    deliveryUrl = null,
)
