// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference

class GetOpenSubtitleResultPreferenceUseCase(
    private val store: OpenSubtitlesSettingsStore,
) {
    suspend operator fun invoke(): OpenSubtitleResultPreference = store.resultPreference()
}
