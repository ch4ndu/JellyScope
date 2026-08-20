// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import platform.Foundation.NSUserDefaults

internal class ApplePreviousRunFailureStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : PreviousRunFailureStore {
    override fun write(
        throwable: Throwable,
        platform: PreviousRunFailurePlatform,
    ) {
        val marker = previousRunFailureMarker(throwable, platform)
        runCatching {
            defaults.setObject(PREVIOUS_RUN_FAILURE_SCHEMA_VERSION.toString(), forKey = PREVIOUS_RUN_FAILURE_SCHEMA_KEY)
            defaults.setObject(marker.exceptionType, forKey = PREVIOUS_RUN_FAILURE_EXCEPTION_TYPE_KEY)
            defaults.setObject(marker.platform.wireValue, forKey = PREVIOUS_RUN_FAILURE_PLATFORM_KEY)
            defaults.synchronize()
        }
    }

    override fun consume(): PreviousRunFailureMarker? {
        val marker =
            decodePreviousRunFailureMarker(
                schemaVersion = defaults.stringForKey(PREVIOUS_RUN_FAILURE_SCHEMA_KEY)?.toIntOrNull(),
                exceptionType = defaults.stringForKey(PREVIOUS_RUN_FAILURE_EXCEPTION_TYPE_KEY),
                platform = defaults.stringForKey(PREVIOUS_RUN_FAILURE_PLATFORM_KEY),
            )
        clear()
        return marker
    }

    override fun clear() {
        runCatching {
            defaults.removeObjectForKey(PREVIOUS_RUN_FAILURE_SCHEMA_KEY)
            defaults.removeObjectForKey(PREVIOUS_RUN_FAILURE_EXCEPTION_TYPE_KEY)
            defaults.removeObjectForKey(PREVIOUS_RUN_FAILURE_PLATFORM_KEY)
            defaults.synchronize()
        }
    }
}

private const val PREVIOUS_RUN_FAILURE_SCHEMA_KEY = "previous_run_failure.schema"
private const val PREVIOUS_RUN_FAILURE_EXCEPTION_TYPE_KEY = "previous_run_failure.exception_type"
private const val PREVIOUS_RUN_FAILURE_PLATFORM_KEY = "previous_run_failure.platform"
