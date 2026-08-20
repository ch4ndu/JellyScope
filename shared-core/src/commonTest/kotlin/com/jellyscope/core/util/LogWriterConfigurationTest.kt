// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LogWriterConfigurationTest {
    private val diagnosticsWriter = TestLogWriter()
    private val platformWriter = TestLogWriter()

    @Test
    fun writerCompositionKeepsDiagnosticsAndGatesThePlatformWriter() {
        val releaseWriters = writers(isDebugBuild = false, verboseLogcatEnabled = false)
        assertEquals(diagnosticsWriter, releaseWriters.first())
        assertIs<SanitizedPlatformLogWriter>(releaseWriters.last())
        assertEquals(
            listOf(diagnosticsWriter, platformWriter),
            writers(isDebugBuild = false, verboseLogcatEnabled = true),
        )
        assertEquals(
            listOf(diagnosticsWriter, platformWriter),
            writers(isDebugBuild = true, verboseLogcatEnabled = false),
        )
        assertEquals(
            listOf(diagnosticsWriter, platformWriter),
            writers(isDebugBuild = true, verboseLogcatEnabled = true),
        )
    }

    @Test
    fun releasePlatformWriterEmitsOnlySanitizedStructuredDiagnostics() {
        val writer = SanitizedPlatformLogWriter(platformWriter)

        writer.log(
            severity = Severity.Info,
            message = "stage=planner event=requested requestPolicy=auto",
            tag = "PlaybackInfoPlanner",
            throwable = IllegalStateException("must not escape"),
        )
        writer.log(
            severity = Severity.Info,
            message = "ordinary message",
            tag = "PlaybackInfoPlanner",
            throwable = null,
        )

        assertEquals(
            listOf("PlaybackInfoPlanner stage=planner event=requested requestPolicy=auto throwable=false"),
            platformWriter.entries,
        )
    }

    private fun writers(
        isDebugBuild: Boolean,
        verboseLogcatEnabled: Boolean,
    ): List<LogWriter> =
        applicationLogWriters(
            diagnosticsWriter = diagnosticsWriter,
            platformWriter = platformWriter,
            isDebugBuild = isDebugBuild,
            verboseLogcatEnabled = verboseLogcatEnabled,
        )
}

private class TestLogWriter : LogWriter() {
    val entries = mutableListOf<String>()

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        entries += "$tag $message throwable=${throwable != null}"
    }
}
