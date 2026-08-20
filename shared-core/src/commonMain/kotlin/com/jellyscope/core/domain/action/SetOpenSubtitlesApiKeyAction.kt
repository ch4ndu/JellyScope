// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore

class SetOpenSubtitlesApiKeyAction(
    private val store: OpenSubtitlesSettingsStore,
) {
    suspend operator fun invoke(value: String) = store.setApiKey(value)
}
