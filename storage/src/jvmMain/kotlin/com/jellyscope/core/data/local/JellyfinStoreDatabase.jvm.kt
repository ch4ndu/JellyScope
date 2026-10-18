// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import androidx.room3.Room
import java.io.File

fun createJellyfinStoreDatabase(): JellyfinStoreDatabase {
    val path = File(jellyscopeDataDirectory(), JELLYFIN_STORE_DATABASE_NAME).absolutePath
    return Room.databaseBuilder<JellyfinStoreDatabase>(name = path).buildJellyfinStore()
}
