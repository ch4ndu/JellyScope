// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import androidx.room.Room
import java.io.File

internal fun jellyscopeDataDirectory(): File = File(System.getProperty("user.home"), ".jellyscope").apply { mkdirs() }

internal fun createJellyfinStoreDatabase(): JellyfinStoreDatabase {
    val path = File(jellyscopeDataDirectory(), JELLYFIN_STORE_DATABASE_NAME).absolutePath
    return Room.databaseBuilder<JellyfinStoreDatabase>(name = path).buildJellyfinStore()
}
