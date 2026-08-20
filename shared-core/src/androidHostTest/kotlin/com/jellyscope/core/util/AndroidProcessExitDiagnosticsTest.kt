// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidProcessExitDiagnosticsTest {
    @Test
    fun formatsLowMemoryExitWithOnlyBoundedValues() {
        val diagnostic =
            formatAndroidProcessExitDiagnostic(
                exit =
                    AndroidProcessExit(
                        timestampMillis = 1_000_000L,
                        reason = ApplicationExitInfo.REASON_LOW_MEMORY,
                        importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                        peakPssKb = 32L * 1024L,
                        peakRssKb = 300L * 1024L,
                    ),
                nowMillis = 1_120_000L,
            )

        assertEquals(
            "stage=process-exit event=reported platform=android exitReason=low-memory " +
                "exitImportance=background-or-cached peakPssMiB=32 " +
                "peakRssMiB=300 exitAgeBucket=1-to-5m",
            diagnostic,
        )
        assertFalse(diagnostic.contains("1000000"))
        assertFalse(diagnostic.contains("32768"))
        assertFalse(diagnostic.contains("307200"))
    }

    @Test
    fun safelyBucketsUnknownValuesAndFutureTimestamps() {
        val diagnostic =
            formatAndroidProcessExitDiagnostic(
                exit =
                    AndroidProcessExit(
                        timestampMillis = 10_001L,
                        reason = Int.MAX_VALUE,
                        importance = Int.MAX_VALUE,
                        peakPssKb = -1L,
                        peakRssKb = 0L,
                    ),
                nowMillis = 10_000L,
            )

        assertTrue(diagnostic.contains("exitReason=unknown"))
        assertTrue(diagnostic.contains("exitImportance=other"))
        assertTrue(diagnostic.contains("peakPssMiB=0"))
        assertTrue(diagnostic.contains("peakRssMiB=0"))
        assertTrue(diagnostic.contains("exitAgeBucket=unavailable"))
    }

    @Test
    fun clampsPeakMemoryToThePrivacyBound() {
        val diagnostic =
            formatAndroidProcessExitDiagnostic(
                exit =
                    AndroidProcessExit(
                        timestampMillis = 1L,
                        reason = ApplicationExitInfo.REASON_LOW_MEMORY,
                        importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                        peakPssKb = 9_000L * 1024L,
                        peakRssKb = Long.MAX_VALUE,
                    ),
                nowMillis = 2L,
            )

        assertTrue(diagnostic.contains("peakPssMiB=8192"))
        assertTrue(diagnostic.contains("peakRssMiB=8192"))
    }

    @Test
    fun onlyReportsAValidExitTimestampNotAlreadyMarked() {
        assertTrue(shouldReportAndroidProcessExit(timestampMillis = 7L, lastReportedTimestamp = 0L))
        assertTrue(shouldReportAndroidProcessExit(timestampMillis = 6L, lastReportedTimestamp = 7L))
        assertFalse(shouldReportAndroidProcessExit(timestampMillis = 7L, lastReportedTimestamp = 7L))
        assertFalse(shouldReportAndroidProcessExit(timestampMillis = 0L, lastReportedTimestamp = 0L))
    }

    @Test
    fun onlyUsesExitHistoryOnAndroidElevenAndLater() {
        assertFalse(supportsAndroidProcessExitHistory(29))
        assertTrue(supportsAndroidProcessExitHistory(30))
    }

    @Test
    fun scrubberAcceptsTheDedicatedClosedDiagnosticGrammar() {
        val safe =
            LogScrubber.capture(
                tag = ANDROID_PROCESS_EXIT_DIAGNOSTICS_TAG,
                message =
                    "stage=process-exit event=reported platform=android exitReason=low-memory " +
                        "exitImportance=background-or-cached peakPssMiB=32 " +
                        "peakRssMiB=300 exitAgeBucket=1-to-5m",
            )

        assertNotNull(safe)
        assertEquals("stage=process-exit", safe.substringBefore(' '))
    }
}
