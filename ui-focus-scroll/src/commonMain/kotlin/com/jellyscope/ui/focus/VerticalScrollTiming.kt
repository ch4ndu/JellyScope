// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.focus

import kotlin.math.abs
import kotlin.math.roundToInt

// A standard poster-row pitch of 282dp takes approximately 320ms.
fun verticalScrollDurationMs(
    distancePx: Float,
    density: Float,
): Int = (abs(distancePx) / density / 0.88f).roundToInt().coerceIn(160, 700)
