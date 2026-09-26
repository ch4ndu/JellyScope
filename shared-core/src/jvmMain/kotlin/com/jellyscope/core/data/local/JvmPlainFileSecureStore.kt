// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.UUID

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
            val bytes =
                try {
                    Files.readAllBytes(file.toPath())
                } catch (_: NoSuchFileException) {
                    return@withContext emptyMap()
                }
            val text =
                try {
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: CharacterCodingException) {
                    quarantineMalformedFile()
                    return@withContext emptyMap()
                }
            try {
                json.decodeFromString<Map<String, String>>(text)
            } catch (_: SerializationException) {
                quarantineMalformedFile()
                emptyMap()
            }
        }

    private suspend fun writeMap(values: Map<String, String>) {
        withContext(Dispatchers.IO) {
            if (values.isEmpty()) {
                Files.deleteIfExists(file.toPath())
                return@withContext
            }

            val parent = secureParentDirectory()
            val tempPath = createSecureTempFile(parent)
            try {
                Files.writeString(
                    tempPath,
                    json.encodeToString(values),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                verifyOwnerOnly(tempPath, directory = false)
                moveReplacing(tempPath.toFile(), file)
                verifyOwnerOnly(file.toPath(), directory = false)
            } finally {
                Files.deleteIfExists(tempPath)
            }
        }
    }

    private fun quarantineMalformedFile() {
        val source = file.toPath()
        establishOwnerOnly(source, directory = false)
        val target =
            source.resolveSibling(
                "${file.name}.corrupt-${UUID.randomUUID()}",
            )
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Secure-store quarantine path already exists." }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun secureParentDirectory(): java.nio.file.Path {
        val parent = (file.parentFile ?: File(".")).toPath()
        if (!Files.exists(parent)) {
            val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            try {
                Files.createDirectories(parent, permissions)
            } catch (_: UnsupportedOperationException) {
                Files.createDirectories(parent)
                establishOwnerOnly(parent, directory = true)
            }
        }
        return parent
    }

    private fun createSecureTempFile(parent: java.nio.file.Path): java.nio.file.Path {
        val posix = Files.getFileAttributeView(parent, PosixFileAttributeView::class.java)
        if (posix != null) {
            return Files.createTempFile(
                parent,
                ".${file.name}-",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        }
        val temp = Files.createTempFile(parent, ".${file.name}-", ".tmp")
        return try {
            establishOwnerOnly(temp, directory = false)
            temp
        } catch (throwable: Throwable) {
            Files.deleteIfExists(temp)
            throw throwable
        }
    }

    private fun moveReplacing(
        source: File,
        target: File,
    ) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        try {
            Files.move(
                sourcePath,
                targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun establishOwnerOnly(
        path: java.nio.file.Path,
        directory: Boolean,
    ) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            val permissions =
                if (directory) {
                    PosixFilePermissions.fromString("rwx------")
                } else {
                    PosixFilePermissions.fromString("rw-------")
                }
            Files.setPosixFilePermissions(path, permissions)
            verifyOwnerOnly(path, directory)
            return
        }
        val acl =
            Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: throw IllegalStateException("Secure storage requires POSIX permissions or an ACL.")
        val owner = Files.getOwner(path)
        val ownerEntry =
            AclEntry
                .newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .build()
        acl.acl = listOf(ownerEntry)
        verifyOwnerOnly(path, directory)
    }

    private fun verifyOwnerOnly(
        path: java.nio.file.Path,
        directory: Boolean,
    ) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            val actual = Files.getPosixFilePermissions(path)
            val expected =
                if (directory) {
                    PosixFilePermissions.fromString("rwx------")
                } else {
                    PosixFilePermissions.fromString("rw-------")
                }
            check(actual == expected) { "Secure storage permissions are not owner-only." }
            return
        }
        val acl =
            Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: throw IllegalStateException("Secure storage requires POSIX permissions or an ACL.")
        val owner = Files.getOwner(path)
        val entries = acl.acl
        check(entries.isNotEmpty()) { "Secure storage ACL is empty." }
        check(entries.all { entry -> entry.principal() == owner }) {
            "Secure storage ACL grants access outside the owner."
        }
        check(entries.any { entry -> entry.type() == AclEntryType.ALLOW }) {
            "Secure storage ACL does not grant owner access."
        }
    }
}
