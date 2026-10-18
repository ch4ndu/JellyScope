// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathDirectory

internal actual val storeDatabaseSearchPath: NSSearchPathDirectory = NSDocumentDirectory

internal actual val subtitleAssetsSearchPath: NSSearchPathDirectory = NSApplicationSupportDirectory
