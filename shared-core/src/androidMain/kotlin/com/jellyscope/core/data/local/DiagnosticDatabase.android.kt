// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

internal fun createDiagnosticBreadcrumbStore(
    context: Context,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): DiagnosticBreadcrumbStore {
    val applicationContext = context.applicationContext
    val databaseFile = File(applicationContext.filesDir, DIAGNOSTIC_DATABASE_FILE_NAME)
    return RoomDiagnosticBreadcrumbStore(
        openDatabase = {
            Room
                .databaseBuilder<DiagnosticDatabase>(
                    context = applicationContext,
                    name = databaseFile.absolutePath,
                ).buildDiagnosticDatabase(driver = driver)
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
