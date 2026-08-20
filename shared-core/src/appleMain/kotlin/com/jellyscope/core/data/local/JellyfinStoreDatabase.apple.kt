// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.data.local

import androidx.room.Room
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

internal fun createJellyfinStoreDatabase(): JellyfinStoreDatabase {
    val directory =
        requireNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                storeDatabaseSearchPath,
                NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ),
        )
    val databaseUrl = requireNotNull(directory.URLByAppendingPathComponent(JELLYFIN_STORE_DATABASE_NAME))
    val path = requireNotNull(databaseUrl.path)
    return Room.databaseBuilder<JellyfinStoreDatabase>(name = path).buildJellyfinStore()
}
