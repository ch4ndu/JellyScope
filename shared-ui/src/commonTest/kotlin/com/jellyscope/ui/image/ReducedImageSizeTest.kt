// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.image

import coil3.size.Dimension
import coil3.size.Size
import com.jellyscope.ui.component.limitedTo
import kotlin.test.Test
import kotlin.test.assertEquals

class ReducedImageSizeTest {
    @Test
    fun reducedTargetsNeverExceedHighRequest() {
        assertEquals(
            "https://server/Items/1/Images/Backdrop?maxWidth=960&quality=90",
            lowerImageMaxWidth("https://server/Items/1/Images/Backdrop?maxWidth=1280&quality=90", 960),
        )
        assertEquals(
            "https://server/Items/1/Images/Backdrop?maxWidth=1280",
            lowerImageMaxWidth("https://server/Items/1/Images/Backdrop?maxWidth=1280", 1440),
        )
        assertEquals(Size(1280, 720), Size(1440, 810).limitedTo(Size(1280, 720)))
        assertEquals(Size(810, 456), Size(810, 456).limitedTo(Size(1280, 720)))
        assertEquals(
            Size(Dimension.Pixels(600), Dimension.Pixels(720)),
            Size(Dimension.Pixels(600), Dimension.Undefined).limitedTo(Size(1280, 720)),
        )
    }
}
