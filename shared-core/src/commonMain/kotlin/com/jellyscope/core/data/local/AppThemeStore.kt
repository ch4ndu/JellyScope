// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AppColorThemeId
import kotlinx.coroutines.flow.StateFlow

interface AppThemeStore {
    val theme: StateFlow<AppColorThemeId>

    suspend fun setTheme(theme: AppColorThemeId)
}
