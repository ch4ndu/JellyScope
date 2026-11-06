// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PLAYBACK_SPEED_CHOICES

fun playbackSpeeds(): List<Float> = PLAYBACK_SPEED_CHOICES

fun Float.speedLabel(): String =
    if (this % 1f == 0f) {
        "${toInt()}x"
    } else {
        "${this}x"
    }
