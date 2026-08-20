// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.data.local

import androidx.room.Room
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

internal fun createDiagnosticBreadcrumbStore(): DiagnosticBreadcrumbStore {
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
    val databaseUrl = requireNotNull(directory.URLByAppendingPathComponent(DIAGNOSTIC_DATABASE_FILE_NAME))
    val databasePath = requireNotNull(databaseUrl.path)
    return RoomDiagnosticBreadcrumbStore(
        openDatabase = {
            Room.databaseBuilder<DiagnosticDatabase>(name = databasePath).buildDiagnosticDatabase()
        },
        purgeFiles = { deleteDiagnosticDatabaseFiles(databasePath) },
    )
}

private fun deleteDiagnosticDatabaseFiles(databasePath: String) {
    val fileManager = NSFileManager.defaultManager
    listOf(databasePath, "$databasePath-wal", "$databasePath-shm").forEach { path ->
        check(!fileManager.fileExistsAtPath(path) || fileManager.removeItemAtPath(path, null)) {
            "Unable to purge diagnostic database file."
        }
    }
}
