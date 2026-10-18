// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.PlaybackPreferences

interface PlaybackPreferencesStore : AccountScopedClearableStore {
    suspend fun get(accountIdentity: AccountIdentity): PlaybackPreferences

    suspend fun save(
        accountIdentity: AccountIdentity,
        preferences: PlaybackPreferences,
    )
}
