// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.data.local

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

internal actual fun appleDownloadsRootDirectory(): NSURL {
    val applicationSupport =
        checkNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ),
        ) { "Application Support is unavailable." }
    return requireNotNull(applicationSupport.URLByAppendingPathComponent("downloads", isDirectory = true))
}
