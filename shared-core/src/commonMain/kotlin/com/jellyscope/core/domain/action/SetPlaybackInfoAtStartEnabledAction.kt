// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.LogCollectionPreferenceStore

class SetPlaybackInfoAtStartEnabledAction(
    private val preferenceStore: LogCollectionPreferenceStore,
) {
    suspend operator fun invoke(enabled: Boolean) {
        preferenceStore.setPlaybackInfoAtStartEnabled(enabled)
    }
}
