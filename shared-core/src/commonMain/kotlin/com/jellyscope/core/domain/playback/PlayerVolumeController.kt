// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlinx.coroutines.flow.StateFlow

interface PlayerVolumeController {
    val volumeState: StateFlow<PlayerVolumeState>

    fun setVolume(percent: Int)

    fun setMuted(muted: Boolean)
}

data class PlayerVolumeState(
    val volumePercent: Int = DEFAULT_VOLUME_PERCENT,
    val muted: Boolean = false,
) {
    init {
        require(volumePercent in MIN_VOLUME_PERCENT..MAX_VOLUME_PERCENT)
    }
}

const val DEFAULT_VOLUME_PERCENT = 100
const val MIN_VOLUME_PERCENT = 0
const val MAX_VOLUME_PERCENT = 100
