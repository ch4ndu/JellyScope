// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context

internal class AndroidPreviousRunFailureStore(
    context: Context,
) : PreviousRunFailureStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREVIOUS_RUN_FAILURE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

    override fun write(
        throwable: Throwable,
        platform: PreviousRunFailurePlatform,
    ) {
        val marker = previousRunFailureMarker(throwable, platform)
        runCatching {
            preferences
                .edit()
                .putInt(PREVIOUS_RUN_FAILURE_SCHEMA_KEY, PREVIOUS_RUN_FAILURE_SCHEMA_VERSION)
                .putString(PREVIOUS_RUN_FAILURE_EXCEPTION_TYPE_KEY, marker.exceptionType)
                .putString(PREVIOUS_RUN_FAILURE_PLATFORM_KEY, marker.platform.wireValue)
                .commit()
        }
    }

    override fun consume(): PreviousRunFailureMarker? {
        val marker =
            decodePreviousRunFailureMarker(
                schemaVersion = preferences.getInt(PREVIOUS_RUN_FAILURE_SCHEMA_KEY, -1),
                exceptionType = preferences.getString(PREVIOUS_RUN_FAILURE_EXCEPTION_TYPE_KEY, null),
                platform = preferences.getString(PREVIOUS_RUN_FAILURE_PLATFORM_KEY, null),
            )
        clear()
        return marker
    }

    override fun clear() {
        runCatching { preferences.edit().clear().commit() }
    }
}

private const val PREVIOUS_RUN_FAILURE_PREFS_NAME = "previous_run_failure"
private const val PREVIOUS_RUN_FAILURE_SCHEMA_KEY = "schema_version"
private const val PREVIOUS_RUN_FAILURE_EXCEPTION_TYPE_KEY = "exception_type"
private const val PREVIOUS_RUN_FAILURE_PLATFORM_KEY = "platform"
