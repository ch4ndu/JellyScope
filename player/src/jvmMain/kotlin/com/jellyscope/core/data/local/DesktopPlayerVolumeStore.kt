// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.playback.PlayerVolumeState

interface DesktopPlayerVolumeStore {
    val restoredState: PlayerVolumeState

    fun submit(state: PlayerVolumeState)
}
