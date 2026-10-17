// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

val LocalDownloadNotificationPermissionRequester = staticCompositionLocalOf<() -> Unit> { {} }
