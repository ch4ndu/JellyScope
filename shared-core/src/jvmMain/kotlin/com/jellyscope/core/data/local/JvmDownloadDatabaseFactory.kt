// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import androidx.room3.Room
import java.io.File

internal class JvmDownloadDatabaseFactory(
    private val rootDirectory: File = jvmDownloadsRootDirectory(),
) : DownloadDatabaseFactory {
    override fun create(): DownloadDatabase {
        val root = rootDirectory.apply { mkdirs() }
        check(root.isDirectory) { "Unable to create the private download database directory." }
        val path = File(root, DOWNLOAD_DATABASE_FILE_NAME).absolutePath
        return Room.databaseBuilder<DownloadDatabase>(name = path).buildDownloadDatabase()
    }
}
