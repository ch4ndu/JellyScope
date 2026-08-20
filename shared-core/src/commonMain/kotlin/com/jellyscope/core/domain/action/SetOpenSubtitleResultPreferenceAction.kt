// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference

class SetOpenSubtitleResultPreferenceAction(
    private val store: OpenSubtitlesSettingsStore,
) {
    suspend operator fun invoke(preference: OpenSubtitleResultPreference) {
        store.setResultPreference(preference)
    }
}
