// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class JvmPlainFileSecureStore(
    private val file: File,
    private val json: Json = Json,
) : SecureStore {
    private val mutex = Mutex()

    override suspend fun read(key: String): String? =
        mutex.withLock {
            readMap()[key]
        }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        mutex.withLock {
            val updated = readMap().toMutableMap()
            updated[key] = value
            writeMap(updated)
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            val updated = readMap().toMutableMap()
            updated.remove(key)
            writeMap(updated)
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            writeMap(emptyMap())
        }
    }

    private suspend fun readMap(): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                return@withContext emptyMap()
            }

            runCatching {
                json.decodeFromString<Map<String, String>>(Files.readString(file.toPath(), StandardCharsets.UTF_8))
            }.getOrDefault(emptyMap())
        }

    private suspend fun writeMap(values: Map<String, String>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()

            if (values.isEmpty()) {
                Files.deleteIfExists(file.toPath())
                return@withContext
            }

            val tempFile = File(file.parentFile ?: File("."), "${file.name}.tmp")
            Files.writeString(tempFile.toPath(), json.encodeToString(values), StandardCharsets.UTF_8)
            restrictOwnerAccess(tempFile)
            moveReplacing(tempFile, file)
            restrictOwnerAccess(file)
        }
    }

    private fun moveReplacing(
        source: File,
        target: File,
    ) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        val moved =
            runCatching {
                Files.move(
                    sourcePath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess

        if (!moved) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restrictOwnerAccess(target: File) {
        runCatching {
            target.setReadable(false, false)
            target.setWritable(false, false)
            target.setExecutable(false, false)
            target.setReadable(true, true)
            target.setWritable(true, true)
        }
    }
}
