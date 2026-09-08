// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.room3.Room
import java.io.File

internal class AndroidDownloadDatabaseFactory(
    context: Context,
) : DownloadDatabaseFactory {
    private val applicationContext = context.applicationContext

    override fun create(): DownloadDatabase {
        val root = androidDownloadsRootDirectory(applicationContext)
        val path = File(root, DOWNLOAD_DATABASE_FILE_NAME).absolutePath
        return Room
            .databaseBuilder<DownloadDatabase>(
                context = applicationContext,
                name = path,
            ).buildDownloadDatabase()
    }
}
