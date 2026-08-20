// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalSubtitleFileIdTest {
    @Test
    fun acceptsInstalledAssetFileIds() {
        val fileId = "a1b2-c3_d4.vtt"

        assertEquals(fileId, requireSafeLocalSubtitleFileId(fileId))
    }

    @Test
    fun rejectsPathSeparatorsAndTraversalSegments() {
        listOf(
            "../secrets.vtt",
            "nested/asset.vtt",
            "nested\\asset.vtt",
            "/absolute.vtt",
            "",
            " leading-space.vtt",
        ).forEach { fileId ->
            assertFailsWith<IllegalArgumentException>("expected $fileId to be rejected") {
                requireSafeLocalSubtitleFileId(fileId)
            }
        }
    }
}
