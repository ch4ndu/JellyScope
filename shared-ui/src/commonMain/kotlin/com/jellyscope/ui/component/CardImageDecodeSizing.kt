// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Which aspect a card's image is, and therefore what shape to decode it at.
 *
 * `Precision.EXACT` requests these target bounds while Coil preserves the source
 * aspect ratio. Keeping compatible bounds still avoids decoding excess pixels for
 * a card that will crop the result.
 */
enum class CardImageAspect(
    val widthUnits: Int,
    val heightUnits: Int,
) {
    /** Poster art, 2:3. */
    Poster(widthUnits = 2, heightUnits = 3),

    /** Wide/backdrop art, 16:9. */
    Wide(widthUnits = 16, heightUnits = 9),
}

/**
 * Computes a bounded, bucketed decode size for a card drawn [widthPx] wide.
 * Callers provide resolved tile-token widths, not per-item measurements, so the
 * bucketed result keeps cache cardinality small. Detail and hero surfaces use
 * their canonical size; [adaptiveCell] adds headroom for a wide adaptive cell.
 */
fun cardImageDecode(
    widthPx: Float,
    aspect: CardImageAspect,
    adaptiveCell: Boolean = false,
): ImageDecode {
    val canonical = aspect.canonicalDecode()
    if (widthPx <= 0f) {
        return canonical
    }
    val headroom = if (adaptiveCell) ADAPTIVE_CELL_HEADROOM else 1f
    val bucketed = bucketUpPx(widthPx * headroom)
    val width = bucketed.coerceIn(MIN_DECODE_WIDTH_PX, canonical.width)
    return ImageDecode(width = width, height = aspect.heightForWidth(width))
}

/** Canonical decode size used by detail and hero surfaces. */
fun CardImageAspect.canonicalDecode(): ImageDecode =
    when (this) {
        CardImageAspect.Poster -> ImageDecode.Poster
        CardImageAspect.Wide -> ImageDecode.Thumb
    }

fun CardImageAspect.heightForWidth(width: Int): Int = (width.toFloat() * heightUnits / widthUnits).roundToInt()

private fun bucketUpPx(px: Float): Int = ceil(px / DECODE_BUCKET_PX).toInt() * DECODE_BUCKET_PX

/**
 * Decode widths are rounded up to a multiple of this, so a small difference in
 * density or tile scale does not mint a separate cache entry. Small enough that
 * the rounding waste stays under one bucket, large enough that the whole
 * tier x tile-size x density matrix collapses to a handful of values.
 */
const val DECODE_BUCKET_PX = 40

/** Floor for absurdly small tokens; also the smallest bucket worth caching. */
const val MIN_DECODE_WIDTH_PX = 120

/**
 * Adaptive grid cells range from one to two token widths. 1.5 is the midpoint:
 * cards in a wide cell are decoded close to their drawn size, and none of them
 * decode more than the canonical size thanks to the clamp.
 */
const val ADAPTIVE_CELL_HEADROOM = 1.5f

/**
 * [cardImageDecode] for a card drawn [width] wide, resolved against the current
 * display density.
 *
 * [width] must be a resolved tile token (`Dimensions.listThumbnailWidth.tileScaled()`
 * and the like), not a measured per-item width — see the bucketing note above.
 */
@Composable
fun rememberCardImageDecode(
    width: Dp,
    aspect: CardImageAspect,
    adaptiveCell: Boolean = false,
): ImageDecode {
    val density = LocalDensity.current
    return remember(width, density, aspect, adaptiveCell) {
        cardImageDecode(
            widthPx = with(density) { width.toPx() },
            aspect = aspect,
            adaptiveCell = adaptiveCell,
        )
    }
}

/**
 * [rememberCardImageDecode] for a card with explicit resolved token bounds.
 *
 * This is for the few noncanonical rectangles and squares whose displayed ratio
 * does not exactly match [CardImageAspect]. It still buckets the token width and
 * caps both axes at the canonical poster maximum, rather than measuring each
 * item independently.
 */
@Composable
fun rememberCardImageDecode(
    width: Dp,
    height: Dp,
): ImageDecode {
    val density = LocalDensity.current
    return remember(width, height, density) {
        val widthPx = with(density) { width.toPx() }
        val heightPx = with(density) { height.toPx() }
        if (widthPx <= 0f || heightPx <= 0f) {
            ImageDecode.Poster
        } else {
            val ratio = heightPx / widthPx
            val maximumWidth =
                minOf(
                    ImageDecode.Poster.width.toFloat(),
                    ImageDecode.Poster.height / ratio,
                ).toInt().coerceAtLeast(1)
            val minimumWidth = minOf(MIN_DECODE_WIDTH_PX, maximumWidth)
            val decodeWidth = bucketUpPx(widthPx).coerceIn(minimumWidth, maximumWidth)
            ImageDecode(
                width = decodeWidth,
                height = (decodeWidth * ratio).roundToInt().coerceIn(1, ImageDecode.Poster.height),
            )
        }
    }
}
