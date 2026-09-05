// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.data.local

import androidx.room3.Room
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

internal class IosDownloadDatabaseFactory(
    private val rootDirectory: NSURL = iosDownloadsRootDirectory(),
) : DownloadDatabaseFactory {
    override fun create(): DownloadDatabase {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(requireNotNull(rootDirectory.path))) {
            check(fileManager.createDirectoryAtURL(rootDirectory, true, null, null)) {
                "Unable to create private download database storage."
            }
        }
        check(rootDirectory.setResourceValue(true, NSURLIsExcludedFromBackupKey, null)) {
            "Unable to exclude the download database from backup."
        }
        val databaseUrl = requireNotNull(rootDirectory.URLByAppendingPathComponent(DOWNLOAD_DATABASE_FILE_NAME))
        return Room
            .databaseBuilder<DownloadDatabase>(name = requireNotNull(databaseUrl.path))
            .buildDownloadDatabase()
    }
}
