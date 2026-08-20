// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.adaptive.tileScaleFor
import com.jellyscope.ui.adaptive.tvTileScaleFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runtime validation of the decode-size change covers one device and one
 * configuration, so the derivation is tested here across the whole finite matrix
 * of window tier x tile-size preference x density instead.
 */
class CardImageDecodeSizingTest {
    private val posterTokenDp = 132f // Dimensions.posterCardWidth
    private val tvPosterTokenDp = 105f // ChromeDimens.posterWidth
    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f)

    @Test
    fun decodeIsNeverLargerThanTheOldCanonicalSize() {
        // The guarantee that this change cannot regress any surface into decoding
        // more than it did before.
        forEveryMobileConfiguration { widthPx ->
            val decode = cardImageDecode(widthPx, CardImageAspect.Poster, adaptiveCell = true)
            assertTrue(
                decode.width <= ImageDecode.Poster.width && decode.height <= ImageDecode.Poster.height,
                "poster decode ${decode.width}x${decode.height} exceeds the canonical " +
                    "${ImageDecode.Poster.width}x${ImageDecode.Poster.height}",
            )
        }
    }

    @Test
    fun theNumberOfDistinctDecodeSizesStaysSmall() {
        // The load-bearing invariant. If sizes varied per item or per pixel, the same
        // poster would occupy many near-identical cache entries, the hit rate would
        // collapse, and scrolling would get worse from repeated decodes.
        val sizes = mutableSetOf<ImageDecode>()
        forEveryMobileConfiguration { widthPx ->
            sizes += cardImageDecode(widthPx, CardImageAspect.Poster)
            sizes += cardImageDecode(widthPx, CardImageAspect.Poster, adaptiveCell = true)
        }
        TileSizeId.entries.forEach { size ->
            densities.forEach { density ->
                sizes += cardImageDecode(tvPosterTokenDp * tvTileScaleFor(size) * density, CardImageAspect.Poster)
            }
        }

        // 4 tiers x 3 tile sizes x 6 densities x 2 cell modes = 144 configurations,
        // plus 18 TV ones. Bucketing has to collapse those to a handful.
        assertTrue(sizes.size <= 12, "expected a handful of distinct decode sizes, got ${sizes.size}: $sizes")
    }

    @Test
    fun aSingleDeviceSeesVeryFewSizesAcrossEveryTierAndTileSize() {
        // What actually matters for cache sharing: one device, every tier and tile
        // size a session can move through.
        val density = 2.625f
        val sizes = mutableSetOf<ImageDecode>()
        WindowWidthTier.entries.forEach { tier ->
            TileSizeId.entries.forEach { size ->
                sizes += cardImageDecode(posterTokenDp * tileScaleFor(tier, size) * density, CardImageAspect.Poster)
            }
        }

        assertTrue(sizes.size <= 6, "one device should see few sizes, got ${sizes.size}: $sizes")
    }

    @Test
    fun decodeSizesAreQuantisedSoNearlyEqualTokensShareAnEntry() {
        val a = cardImageDecode(301f, CardImageAspect.Poster)
        val b = cardImageDecode(319f, CardImageAspect.Poster)

        assertEquals(a, b)
        assertEquals(0, a.width % DECODE_BUCKET_PX)
    }

    @Test
    fun decodeIsNeverSmallerThanTheDisplayedWidth() {
        // Under-decoding is the visible failure mode: a bitmap smaller than the card
        // is drawn soft. Bucketing rounds up, so this must hold everywhere.
        forEveryMobileConfiguration { widthPx ->
            val decode = cardImageDecode(widthPx, CardImageAspect.Poster)
            val displayed = widthPx.toInt()
            assertTrue(
                decode.width >= displayed || decode.width == ImageDecode.Poster.width,
                "decode ${decode.width} is narrower than the displayed $displayed without being clamped",
            )
        }
    }

    @Test
    fun aspectIsPreservedForBothCardShapes() {
        val poster = cardImageDecode(240f, CardImageAspect.Poster)
        assertEquals(360, poster.height) // 2:3

        val wide = cardImageDecode(320f, CardImageAspect.Wide)
        assertEquals(180, wide.height) // 16:9
    }

    @Test
    fun wideCardsClampToTheWideCanonicalSizeNotThePosterOne() {
        // A 16:9 card decoded at a 2:3 shape would be squashed by EXACT precision.
        val wide = cardImageDecode(4000f, CardImageAspect.Wide)

        assertEquals(ImageDecode.Thumb, wide)
    }

    @Test
    fun anUnmeasuredCardFallsBackToTheCanonicalSize() {
        assertEquals(ImageDecode.Poster, cardImageDecode(0f, CardImageAspect.Poster))
        assertEquals(ImageDecode.Thumb, cardImageDecode(-1f, CardImageAspect.Wide))
    }

    @Test
    fun tinyTokensDoNotProduceUselesslySmallBitmaps() {
        assertEquals(MIN_DECODE_WIDTH_PX, cardImageDecode(10f, CardImageAspect.Poster).width)
    }

    @Test
    fun theTvGridPosterDecodesFarSmallerThanTheOldCanonicalSize() {
        // The case that motivated this: a Fire TV Cube at 1080p, density 2.0,
        // medium tiles. Regression guard on the actual win.
        val widthPx = tvPosterTokenDp * tvTileScaleFor(TileSizeId.Medium) * 2f

        val decode = cardImageDecode(widthPx, CardImageAspect.Poster)

        assertEquals(240, decode.width)
        assertEquals(360, decode.height)
        val oldPixels = ImageDecode.Poster.width * ImageDecode.Poster.height
        val newPixels = decode.width * decode.height
        assertTrue(newPixels * 3 < oldPixels, "expected a >3x pixel reduction, got $oldPixels -> $newPixels")
    }

    private fun forEveryMobileConfiguration(assertion: (Float) -> Unit) {
        WindowWidthTier.entries.forEach { tier ->
            TileSizeId.entries.forEach { size ->
                densities.forEach { density ->
                    assertion(posterTokenDp * tileScaleFor(tier, size) * density)
                }
            }
        }
    }
}
