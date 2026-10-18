// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import androidx.room3.Room
import java.io.File

internal fun createDiagnosticBreadcrumbStore(): DiagnosticBreadcrumbStore {
    val databaseFile = File(jellyscopeDataDirectory(), DIAGNOSTIC_DATABASE_FILE_NAME)
    databaseFile.parentFile?.mkdirs()
    return RoomDiagnosticBreadcrumbStore(
        openDatabase = {
            Room.databaseBuilder<DiagnosticDatabase>(name = databaseFile.absolutePath).buildDiagnosticDatabase()
        },
        purgeFiles = { deleteDiagnosticDatabaseFiles(databaseFile) },
    )
}

private fun deleteDiagnosticDatabaseFiles(databaseFile: File) {
    listOf(
        databaseFile,
        File("${databaseFile.absolutePath}-wal"),
        File("${databaseFile.absolutePath}-shm"),
    ).forEach { file ->
        check(!file.exists() || file.delete()) { "Unable to purge diagnostic database file." }
    }
}
