// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackMediaStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
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

        val tiles = cache.trickplayTileUrls("item-1", "https://server", 320, 2, build)
        assertSame(tiles, cache.trickplayTileUrls("item-1", "https://server", 320, 2, build))
        assertEquals(1, builds)

        // Any input the URLs are built from must invalidate them.
        cache.trickplayTileUrls("item-1", "https://server", 320, 3, build)
        assertEquals(2, builds)
        cache.trickplayTileUrls("item-2", "https://server", 320, 3, build)
        assertEquals(3, builds)
        cache.trickplayTileUrls("item-2", "https://other", 320, 3, build)
        assertEquals(4, builds)
        cache.trickplayTileUrls("item-2", "https://other", 480, 3, build)
        assertEquals(5, builds)
    }

    @Test
    fun anItemWithoutTrickplayCachesItsEmptyResult() {
        val cache = PlayerProjectionCache()
        var builds = 0
        val build = {
            builds++
            emptyList<String>()
        }

        val tiles = cache.trickplayTileUrls("item-1", "https://server", null, null, build)

        assertTrue(tiles.isEmpty())
        assertSame(tiles, cache.trickplayTileUrls("item-1", "https://server", null, null, build))
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
