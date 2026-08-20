// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathDirectory

internal actual val storeDatabaseSearchPath: NSSearchPathDirectory = NSCachesDirectory

internal actual val subtitleAssetsSearchPath: NSSearchPathDirectory = NSCachesDirectory
