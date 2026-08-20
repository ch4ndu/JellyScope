// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent

class PlayerKeyCommandBridge {
    var handler: ((KeyEvent) -> Boolean)? = null
}

val LocalPlayerKeyCommandBridge = staticCompositionLocalOf<PlayerKeyCommandBridge?> { null }
