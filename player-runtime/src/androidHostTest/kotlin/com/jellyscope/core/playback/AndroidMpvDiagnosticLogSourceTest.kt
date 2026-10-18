// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse

class AndroidMpvDiagnosticLogSourceTest {
    private fun sourceIn(directory: File?): AndroidMpvDiagnosticLogSource = AndroidMpvDiagnosticLogSource { directory }

    private fun tempDirectory(): File = Files.createTempDirectory("mpv-log-source-test").toFile()

    @Test
    fun clearDeletesBothRetainedFiles() =
        runTest {
            val directory = tempDirectory()
            val current = File(directory, ANDROID_MPV_DIAGNOSTIC_LOG_NAME).apply { writeText("current") }
            val previous = File(directory, ANDROID_MPV_DIAGNOSTIC_LOG_PREVIOUS_NAME).apply { writeText("previous") }

            sourceIn(directory).clear()

            assertFalse(current.exists())
            assertFalse(previous.exists())
        }
}
