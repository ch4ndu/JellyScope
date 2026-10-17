// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the sanitized diagnostics writer active in every build while allowing
 * an explicit device preference to opt release builds into Kermit's raw sink.
 */
fun configureApplicationLogWriters(
    diagnosticsWriter: LogWriter,
    preferenceStore: LogCollectionPreferenceStore,
    applicationScope: CoroutineScope,
    isDebugBuild: Boolean,
) {
    val rawPlatformWriter = platformLogWriter()

    fun applyWriters(verboseLogcatEnabled: Boolean) {
        Logger.setLogWriters(
            applicationLogWriters(
                diagnosticsWriter = diagnosticsWriter,
                platformWriter = rawPlatformWriter,
                isDebugBuild = isDebugBuild,
                verboseLogcatEnabled = verboseLogcatEnabled,
            ),
        )
    }

    applyWriters(preferenceStore.verboseLogcatEnabled.value)
    applicationScope.launch {
        preferenceStore.verboseLogcatEnabled.collect(::applyWriters)
    }
}

internal fun applicationLogWriters(
    diagnosticsWriter: LogWriter,
    platformWriter: LogWriter,
    isDebugBuild: Boolean,
    verboseLogcatEnabled: Boolean,
): List<LogWriter> =
    buildList {
        add(diagnosticsWriter)
        if (isDebugBuild || verboseLogcatEnabled) {
            add(platformWriter)
        } else {
            add(SanitizedPlatformLogWriter(platformWriter))
        }
    }

/**
 * Keeps allowlisted structured diagnostics visible in release logcat without
 * exposing regular application logs or throwable payloads.
 */
internal class SanitizedPlatformLogWriter(
    private val delegate: LogWriter,
) : LogWriter() {
    override fun log(
        severity: co.touchlab.kermit.Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val safeMessage = LogScrubber.capture(tag = tag, message = message) ?: return
        delegate.log(severity = severity, message = safeMessage, tag = tag, throwable = null)
    }
}
