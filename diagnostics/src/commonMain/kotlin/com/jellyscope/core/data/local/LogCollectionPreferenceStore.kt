// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.flow.StateFlow

/** App-global preferences for buffered diagnostics and raw platform logging. */
interface LogCollectionPreferenceStore {
    val enabled: StateFlow<Boolean>
    val verboseLogcatEnabled: StateFlow<Boolean>

    /** Show the playback info overlay automatically when playback starts. */
    val playbackInfoAtStartEnabled: StateFlow<Boolean>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setVerboseLogcatEnabled(enabled: Boolean)

    suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean)
}
