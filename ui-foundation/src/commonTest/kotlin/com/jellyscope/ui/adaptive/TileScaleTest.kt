// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.adaptive

import com.jellyscope.core.domain.model.TileSizeId
import kotlin.test.Test
import kotlin.test.assertEquals

class TileScaleTest {
    @Test
    fun resolvesEveryWindowTierAndTileSize() {
        val expected =
            mapOf(
                WindowWidthTier.Compact to listOf(0.85f, 1f, 1.2f),
                WindowWidthTier.Medium to listOf(0.9f, 1.1f, 1.3f),
                WindowWidthTier.Expanded to listOf(1f, 1.2f, 1.45f),
                WindowWidthTier.XLarge to listOf(1.1f, 1.35f, 1.6f),
            )

        expected.forEach { (tier, scales) ->
            TileSizeId.entries.forEachIndexed { index, size ->
                assertEquals(scales[index], tileScaleFor(tier, size))
            }
        }
    }

    @Test
    fun resolvesConservativeTvRow() {
        assertEquals(0.85f, tvTileScaleFor(TileSizeId.Small))
        assertEquals(1f, tvTileScaleFor(TileSizeId.Medium))
        assertEquals(1.15f, tvTileScaleFor(TileSizeId.Large))
    }
}
