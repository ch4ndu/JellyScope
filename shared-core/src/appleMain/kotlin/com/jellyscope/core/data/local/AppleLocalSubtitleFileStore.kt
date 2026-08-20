// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.data.local

import com.jellyscope.core.coroutines.platformIoDispatcher
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rename
import platform.posix.rewind

internal class AppleLocalSubtitleFileStore(
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : LocalSubtitleFileStore {
    private val directory =
        requireNotNull(
            NSFileManager.defaultManager
                .URLForDirectory(
                    subtitleAssetsSearchPath,
                    NSUserDomainMask,
                    appropriateForURL = null,
                    create = true,
                    error = null,
                )?.URLByAppendingPathComponent("subtitles", isDirectory = true),
        ).also { url ->
            NSFileManager.defaultManager.createDirectoryAtURL(url, true, null, null)
            url.setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
        }

    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) {
        withContext(ioDispatcher) {
            val target = path(fileId)
            val temporary = "$target.tmp"
            val file = checkNotNull(fopen(temporary, "wb")) { "Unable to create local subtitle file." }
            try {
                val written = bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file) }
                check(written == bytes.size.toULong()) { "Unable to write local subtitle file." }
            } finally {
                fclose(file)
            }
            check(rename(temporary, target) == 0) { "Unable to install local subtitle file." }
        }
    }

    override suspend fun read(fileId: String): ByteArray? =
        withContext(ioDispatcher) {
            val file = fopen(path(fileId), "rb") ?: return@withContext null
            try {
                check(fseek(file, 0, SEEK_END) == 0) { "Unable to read local subtitle file." }
                val length = ftell(file).takeIf { value -> value >= 0 } ?: return@withContext null
                rewind(file)
                ByteArray(length.toInt()).also { bytes ->
                    val read = bytes.usePinned { pinned -> fread(pinned.addressOf(0), 1u, length.toULong(), file) }
                    check(read == length.toULong()) { "Unable to read local subtitle file." }
                }
            } finally {
                fclose(file)
            }
        }

    override suspend fun readBounded(
        fileId: String,
        maxBytes: Int,
    ): ByteArray? =
        withContext(ioDispatcher) {
            require(maxBytes >= 0) { "Maximum subtitle bytes must be non-negative." }
            val file = fopen(path(fileId), "rb") ?: return@withContext null
            try {
                check(fseek(file, 0, SEEK_END) == 0) { "Unable to read local subtitle file." }
                val length = ftell(file).takeIf { value -> value >= 0 } ?: return@withContext null
                if (length > maxBytes.toLong()) throw LocalSubtitlePayloadTooLargeException
                rewind(file)
                ByteArray(length.toInt()).also { bytes ->
                    val read = bytes.usePinned { pinned -> fread(pinned.addressOf(0), 1u, length.toULong(), file) }
                    check(read == length.toULong()) { "Unable to read local subtitle file." }
                }
            } finally {
                fclose(file)
            }
        }

    override suspend fun exists(fileId: String): Boolean =
        withContext(ioDispatcher) { NSFileManager.defaultManager.fileExistsAtPath(path(fileId)) }

    override suspend fun delete(fileId: String) {
        withContext(ioDispatcher) { NSFileManager.defaultManager.removeItemAtPath(path(fileId), null) }
    }

    override suspend fun listFileIds(): Set<String> =
        withContext(ioDispatcher) {
            NSFileManager.defaultManager
                .contentsOfDirectoryAtPath(requireNotNull(directory.path), null)
                ?.mapNotNull { value -> value as? String }
                ?.toSet()
                .orEmpty()
        }

    override fun resolvePath(fileId: String): String? = path(fileId).takeIf(NSFileManager.defaultManager::fileExistsAtPath)

    private fun path(fileId: String): String =
        requireNotNull(directory.URLByAppendingPathComponent(requireSafeLocalSubtitleFileId(fileId))?.path)
}
