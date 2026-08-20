// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore

class GetOpenSubtitlesApiKeyUseCase(
    private val store: OpenSubtitlesSettingsStore,
) {
    suspend operator fun invoke(): String? = store.apiKey()
}
