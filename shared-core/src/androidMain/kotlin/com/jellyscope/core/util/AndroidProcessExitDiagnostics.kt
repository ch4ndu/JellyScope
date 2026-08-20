// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Emits one sanitized summary of the latest prior process exit after a relaunch.
 *
 * Android keeps this history only on API 30+. Some Fire OS builds expose the API level but
 * not a usable implementation, so API and linkage failures intentionally leave no trace.
 */
fun reportLatestAndroidProcessExit(
    context: Context,
    preferenceStore: LogCollectionPreferenceStore,
    applicationScope: CoroutineScope,
) {
    applicationScope.launch(Dispatchers.IO) {
        if (!preferenceStore.enabled.value || !supportsAndroidProcessExitHistory(Build.VERSION.SDK_INT)) {
            return@launch
        }

        val exit = loadLatestAndroidProcessExit(context) ?: return@launch
        val marker =
            context.applicationContext.getSharedPreferences(
                ANDROID_PROCESS_EXIT_PREFS_NAME,
                Context.MODE_PRIVATE,
            )
        val lastReportedTimestamp = marker.getLong(ANDROID_PROCESS_EXIT_TIMESTAMP_KEY, NO_EXIT_TIMESTAMP)
        if (!shouldReportAndroidProcessExit(exit.timestampMillis, lastReportedTimestamp)) {
            return@launch
        }

        androidProcessExitLogger.w {
            formatAndroidProcessExitDiagnostic(
                exit = exit,
                nowMillis = System.currentTimeMillis(),
            )
        }
        marker
            .edit()
            .putLong(ANDROID_PROCESS_EXIT_TIMESTAMP_KEY, exit.timestampMillis)
            .apply()
    }
}

internal data class AndroidProcessExit(
    val timestampMillis: Long,
    val reason: Int,
    val importance: Int,
    val peakPssKb: Long,
    val peakRssKb: Long,
)

internal fun supportsAndroidProcessExitHistory(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R

internal fun shouldReportAndroidProcessExit(
    timestampMillis: Long,
    lastReportedTimestamp: Long,
): Boolean = timestampMillis > NO_EXIT_TIMESTAMP && timestampMillis != lastReportedTimestamp

internal fun formatAndroidProcessExitDiagnostic(
    exit: AndroidProcessExit,
    nowMillis: Long,
): String =
    listOf(
        "stage=process-exit",
        "event=reported",
        "platform=android",
        "exitReason=${androidProcessExitReasonCategory(exit.reason)}",
        "exitImportance=${androidProcessExitImportanceCategory(exit.importance)}",
        "peakPssMiB=${androidProcessExitMemoryMiB(exit.peakPssKb)}",
        "peakRssMiB=${androidProcessExitMemoryMiB(exit.peakRssKb)}",
        "exitAgeBucket=${androidProcessExitAgeBucket(exit.timestampMillis, nowMillis)}",
    ).joinToString(" ")

@Suppress("NewApi")
private fun loadLatestAndroidProcessExit(context: Context): AndroidProcessExit? =
    try {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit =
            activityManager
                .getHistoricalProcessExitReasons(null, 0, 1)
                .maxByOrNull(ApplicationExitInfo::getTimestamp)
                ?: return null
        AndroidProcessExit(
            timestampMillis = exit.timestamp,
            reason = exit.reason,
            importance = exit.importance,
            peakPssKb = exit.pss,
            peakRssKb = exit.rss,
        )
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }

private fun androidProcessExitReasonCategory(reason: Int): String =
    when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource-limit"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package-updated"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package-state-change"
        ApplicationExitInfo.REASON_EXIT_SELF -> "self-exit"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown"
    }

private fun androidProcessExitImportanceCategory(importance: Int): String =
    when {
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground"
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE_PRE_26 -> "perceptible"
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        // Android assigns the same integer to BACKGROUND and CACHED, so preserve the
        // platform's ambiguity instead of claiming a category it cannot distinguish.
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "background-or-cached"
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "cached"
        importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED &&
            importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "cached-or-empty"
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "top-sleeping"
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> "other"
    }

private fun androidProcessExitMemoryMiB(valueKb: Long): Long = valueKb.coerceIn(0L, MAX_PROCESS_EXIT_MEMORY_KIB) / KIB_PER_MIB

private fun androidProcessExitAgeBucket(
    timestampMillis: Long,
    nowMillis: Long,
): String {
    if (timestampMillis <= 0L || nowMillis < timestampMillis) {
        return "unavailable"
    }
    return when (nowMillis - timestampMillis) {
        in 0L..<MINUTE_MILLIS -> "under-1m"
        in MINUTE_MILLIS..<FIVE_MINUTES_MILLIS -> "1-to-5m"
        in FIVE_MINUTES_MILLIS..<HOUR_MILLIS -> "5-to-60m"
        in HOUR_MILLIS..<DAY_MILLIS -> "1-to-24h"
        else -> "over-24h"
    }
}

private val androidProcessExitLogger = diagnosticLogger(DiagnosticTag.AndroidProcessExitDiagnostics)

internal const val ANDROID_PROCESS_EXIT_DIAGNOSTICS_TAG = "AndroidProcessExitDiagnostics"

private const val ANDROID_PROCESS_EXIT_PREFS_NAME = "android_process_exit_diagnostics"
private const val ANDROID_PROCESS_EXIT_TIMESTAMP_KEY = "last_reported_exit_timestamp"
private const val NO_EXIT_TIMESTAMP = 0L
private const val KIB_PER_MIB = 1024L
private const val MAX_PROCESS_EXIT_MEMORY_MIB = 8_192L
private const val MAX_PROCESS_EXIT_MEMORY_KIB = MAX_PROCESS_EXIT_MEMORY_MIB * KIB_PER_MIB
private const val MINUTE_MILLIS = 60_000L
private const val FIVE_MINUTES_MILLIS = 5L * MINUTE_MILLIS
private const val HOUR_MILLIS = 60L * MINUTE_MILLIS
private const val DAY_MILLIS = 24L * HOUR_MILLIS
