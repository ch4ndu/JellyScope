// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import kotlinx.coroutines.flow.StateFlow

interface PlayerDeviceSettingsStore {
    val settings: StateFlow<PlayerDeviceSettings>

    suspend fun setSettings(settings: PlayerDeviceSettings)
}
