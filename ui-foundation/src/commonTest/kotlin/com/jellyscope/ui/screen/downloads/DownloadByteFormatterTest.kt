// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadByteFormatterTest {
    @Test
    fun storageDisplayUsesFriendlyMbAndGbUnits() {
        assertEquals(IntegerByteDisplay(512L, IntegerByteUnit.MB), integerByteDisplay(512L * DOWNLOAD_BYTES_PER_MB))
        assertEquals(IntegerByteDisplay(4L, IntegerByteUnit.GB), integerByteDisplay(4L * DOWNLOAD_BYTES_PER_GB))
    }
}
