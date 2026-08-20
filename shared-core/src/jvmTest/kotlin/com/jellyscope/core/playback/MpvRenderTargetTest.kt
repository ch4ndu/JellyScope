// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvRenderTargetTest {
    @Test
    fun acceptsOnlyTheCurrentSizedGenerationWithEnoughStride() {
        val target =
            MpvRenderTarget(
                pixelAddress = 42L,
                width = 2504,
                height = 1409,
                stride = 2504 * BYTES_PER_PIXEL,
                generation = 7L,
            )

        assertTrue(target.isValidFor(expectedWidth = 2504, expectedHeight = 1409, expectedGeneration = 7L))
        assertFalse(target.copy(generation = 6L).isValidFor(2504, 1409, 7L))
        assertFalse(target.copy(width = 1920).isValidFor(2504, 1409, 7L))
        assertFalse(target.copy(stride = 2504 * BYTES_PER_PIXEL - 1).isValidFor(2504, 1409, 7L))
        assertFalse(target.copy(pixelAddress = 0L).isValidFor(2504, 1409, 7L))
    }

    @Test
    fun rejectsDimensionsWhoseMinimumStrideExceedsTheNativeIntContract() {
        val target =
            MpvRenderTarget(
                pixelAddress = 42L,
                width = Int.MAX_VALUE,
                height = 1,
                stride = Int.MAX_VALUE,
                generation = 1L,
            )

        assertFalse(target.isValidFor(Int.MAX_VALUE, 1, 1L))
    }
}
