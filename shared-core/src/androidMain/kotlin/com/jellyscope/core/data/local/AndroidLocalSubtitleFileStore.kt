// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import com.jellyscope.core.coroutines.platformIoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class AndroidLocalSubtitleFileStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : LocalSubtitleFileStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "subtitles").apply { mkdirs() }

    override suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    ) {
        withContext(ioDispatcher) {
            val target = file(fileId)
            val temporary = File(directory, ".$fileId.tmp")
            temporary.writeBytes(bytes)
            val replaced =
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.isSuccess
            if (!replaced) {
                runCatching {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.getOrElse { throw IllegalStateException("Unable to install local subtitle file.", it) }
            }
        }
    }

    override suspend fun read(fileId: String): ByteArray? = withContext(ioDispatcher) { file(fileId).takeIf(File::isFile)?.readBytes() }

    override suspend fun readBounded(
        fileId: String,
        maxBytes: Int,
    ): ByteArray? =
        withContext(ioDispatcher) {
            require(maxBytes >= 0) { "Maximum subtitle bytes must be non-negative." }
            val source = file(fileId).takeIf(File::isFile) ?: return@withContext null
            if (source.length() > maxBytes.toLong()) throw LocalSubtitlePayloadTooLargeException
            val bounded = ByteArray(maxBytes)
            source.inputStream().use { input ->
                var offset = 0
                while (offset < bounded.size) {
                    val read = input.read(bounded, offset, bounded.size - offset)
                    if (read < 0) break
                    if (read == 0) continue
                    offset += read
                }
                if (input.read() >= 0) throw LocalSubtitlePayloadTooLargeException
                bounded.copyOf(offset)
            }
        }

    override suspend fun exists(fileId: String): Boolean = withContext(ioDispatcher) { file(fileId).isFile }

    override suspend fun delete(fileId: String) {
        withContext(ioDispatcher) {
            val target = file(fileId)
            check(target.delete() || !target.exists()) { LOCAL_SUBTITLE_DELETE_FAILURE }
        }
    }

    override suspend fun listFileIds(): Set<String> =
        withContext(ioDispatcher) {
            directory
                .listFiles()
                ?.filter(File::isFile)
                ?.map(File::getName)
                ?.toSet()
                .orEmpty()
        }

    override fun resolvePath(fileId: String): String? = file(fileId).takeIf(File::isFile)?.absolutePath

    private fun file(fileId: String): File = File(directory, requireSafeLocalSubtitleFileId(fileId))
}

private const val LOCAL_SUBTITLE_DELETE_FAILURE = "Unable to delete local subtitle file."
