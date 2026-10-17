// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui

import androidx.compose.runtime.staticCompositionLocalOf

val LocalPlayerFullscreenChanged = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
