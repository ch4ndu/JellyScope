// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OpenSubtitlesSettingsStore(
    private val secureStore: SecureStore,
) {
    private val preferenceMutex = Mutex()
    private var preferenceInitialized = false
    private var cachedPreference = OpenSubtitleResultPreference.NoPreference

    suspend fun apiKey(): String? = secureStore.read(API_KEY)?.trim()?.takeIf(String::isNotBlank)

    suspend fun setApiKey(value: String) {
        value.trim().takeIf(String::isNotBlank)?.let { secureStore.write(API_KEY, it) } ?: secureStore.remove(API_KEY)
    }

    suspend fun resultPreference(): OpenSubtitleResultPreference =
        preferenceMutex.withLock {
            if (!preferenceInitialized) {
                cachedPreference = decodePreference(secureStore.read(RESULT_PREFERENCE))
                preferenceInitialized = true
            }
            cachedPreference
        }

    suspend fun setResultPreference(preference: OpenSubtitleResultPreference) {
        preferenceMutex.withLock {
            if (preference == OpenSubtitleResultPreference.NoPreference) {
                secureStore.remove(RESULT_PREFERENCE)
            } else {
                secureStore.write(RESULT_PREFERENCE, preference.name)
            }
            cachedPreference = preference
            preferenceInitialized = true
        }
    }
}

private const val API_KEY = "opensubtitles_api_key"
private const val RESULT_PREFERENCE = "opensubtitles_result_preference"

private fun decodePreference(value: String?): OpenSubtitleResultPreference =
    OpenSubtitleResultPreference.entries.firstOrNull { preference -> preference.name == value?.trim() }
        ?: OpenSubtitleResultPreference.NoPreference
