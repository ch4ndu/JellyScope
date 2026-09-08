// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.data.local

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

internal actual fun appleDownloadsRootDirectory(): NSURL {
    val caches =
        checkNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                NSCachesDirectory,
                NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ),
        ) { "Caches directory is unavailable." }
    return requireNotNull(caches.URLByAppendingPathComponent("downloads", isDirectory = true))
}
