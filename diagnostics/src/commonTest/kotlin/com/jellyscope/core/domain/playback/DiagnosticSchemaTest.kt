// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.util.LogScrubber
import kotlin.test.Test
import kotlin.test.assertTrue

class DiagnosticSchemaTest {
    @Test
    fun scrubberAllowedFieldsCoverEveryFormatterField() {
        val missingFields = playbackDiagnosticFieldNames - LogScrubber.allowedFields

        assertTrue(missingFields.isEmpty(), "LogScrubber is missing formatter fields: $missingFields")
    }
}
