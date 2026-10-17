// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.DEFAULT_PLAYBACK_SPEED

fun playbackSpeeds(): List<Float> = listOf(0.5f, DEFAULT_PLAYBACK_SPEED, 1.25f, 1.5f, 2f)

fun Float.speedLabel(): String =
    if (this % 1f == 0f) {
        "${toInt()}x"
    } else {
        "${this}x"
    }
