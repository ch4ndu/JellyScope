// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.data.local

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.model.DownloadArtifactKey
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.fwrite
import platform.posix.rename

internal class IosDownloadArtifactStore(
    rootDirectory: NSURL = iosDownloadsRootDirectory(),
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : DownloadArtifactStore {
    private val fileManager = NSFileManager.defaultManager
    private val root = rootDirectory.also(::ensurePrivateDirectory)
    private val stagingRoot = areaDirectory(DownloadArtifactArea.Staging)
    private val completedRoot = areaDirectory(DownloadArtifactArea.Completed)
    private val stagingMetadataReplacementRoot =
        child(root, "staging-metadata-replacement", isDirectory = true).also(::ensureDirectory)

    override suspend fun openStagingWriter(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        mode: DownloadArtifactWriteMode,
    ): DownloadArtifactWriter =
        withContext(ioDispatcher) {
            val artifactDirectory = artifactDirectory(artifactKey, DownloadArtifactArea.Staging)
            ensureDirectory(artifactDirectory)
            val target = child(artifactDirectory, partKey.value, isDirectory = false)
            val targetPath = requireNotNull(target.path)
            val file =
                when (mode) {
                    DownloadArtifactWriteMode.Create -> {
                        check(attributes(target) == null) { "Artifact staging part already exists." }
                        checkNotNull(fopen(targetPath, "wbx")) { "Unable to create artifact staging part." }
                    }

                    is DownloadArtifactWriteMode.Resume -> {
                        val length = regularFileLength(target)
                        check(length == mode.expectedLengthBytes) { "Artifact checkpoint length changed." }
                        checkNotNull(fopen(targetPath, "ab")) { "Unable to resume artifact staging part." }
                    }
                }
            IosDownloadArtifactWriter(
                partKey = partKey,
                file = file,
                initialLengthBytes =
                    when (mode) {
                        DownloadArtifactWriteMode.Create -> 0L
                        is DownloadArtifactWriteMode.Resume -> mode.expectedLengthBytes
                    },
                ioDispatcher = ioDispatcher,
            )
        }

    override suspend fun inspect(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): DownloadArtifactInspection? = withContext(ioDispatcher) { inspectBlocking(artifactKey, area) }

    override suspend fun completedPartPath(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
    ): String? =
        withContext(ioDispatcher) {
            val inspection = inspectBlocking(artifactKey, DownloadArtifactArea.Completed) ?: return@withContext null
            val part =
                inspection.parts.firstOrNull { candidate -> candidate.partKey == partKey }
                    ?: return@withContext null
            if (part.lengthBytes <= 0L) return@withContext null
            val file = child(artifactDirectory(artifactKey, DownloadArtifactArea.Completed), partKey.value, isDirectory = false)
            if (regularFileLength(file) != part.lengthBytes) return@withContext null
            requireNotNull(file.path)
        }

    override suspend fun readPart(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
        partKey: DownloadArtifactPartKey,
        maxBytes: Int,
    ): ByteArray? =
        withContext(ioDispatcher) {
            require(maxBytes >= 0) { "Maximum artifact read must not be negative." }
            val inspection = inspectBlocking(artifactKey, area) ?: return@withContext null
            val part =
                inspection.parts.firstOrNull { candidate -> candidate.partKey == partKey }
                    ?: return@withContext null
            if (part.lengthBytes > maxBytes.toLong() || part.lengthBytes > Int.MAX_VALUE) {
                return@withContext null
            }
            val file = child(artifactDirectory(artifactKey, area), partKey.value, isDirectory = false)
            val actualLength = regularFileLength(file)
            if (actualLength != part.lengthBytes) return@withContext null
            if (actualLength == 0L) return@withContext ByteArray(0)
            val input = fopen(requireNotNull(file.path), "rb") ?: return@withContext null
            try {
                val bytes = ByteArray(actualLength.toInt())
                bytes.usePinned { pinned ->
                    val read = fread(pinned.addressOf(0), 1u, actualLength.toULong(), input)
                    check(read == actualLength.toULong()) { "Unable to read artifact part." }
                }
                bytes
            } finally {
                check(fclose(input) == 0) { "Unable to close artifact part." }
            }
        }

    override suspend fun replaceStagingMetadata(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): DownloadArtifactPartCheckpoint =
        withContext(ioDispatcher) {
            requireValidStagingMetadataReplacementSlice(buffer.size, offset, length)
            val staging = artifactDirectory(artifactKey, DownloadArtifactArea.Staging)
            ensureDirectory(staging)
            val target = child(staging, partKey.value, isDirectory = false)
            if (attributes(target) != null) regularFileLength(target)
            val replacement = stagingMetadataReplacementFile(artifactKey, partKey)
            if (attributes(replacement) != null) {
                regularFileLength(replacement)
                check(fileManager.removeItemAtURL(replacement, null)) {
                    "Unable to remove stale staging metadata replacement."
                }
            }
            val replacementPath = requireNotNull(replacement.path)
            val file = checkNotNull(fopen(replacementPath, "wbx")) { "Unable to create staging metadata replacement." }
            try {
                if (length > 0) {
                    val written =
                        buffer.usePinned { pinned ->
                            fwrite(pinned.addressOf(offset), 1u, length.toULong(), file)
                        }
                    check(written == length.toULong()) { "Unable to write staging metadata replacement." }
                }
                syncFile(file)
            } finally {
                check(fclose(file) == 0) { "Unable to close staging metadata replacement." }
            }
            check(rename(replacementPath, requireNotNull(target.path)) == 0) {
                "Unable to atomically replace staging metadata."
            }
            removeEmptyStagingMetadataReplacementDirectory(artifactKey)
            DownloadArtifactPartCheckpoint(partKey, length.toLong())
        }

    override suspend fun validateStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean =
        withContext(ioDispatcher) {
            val inspection = inspectBlocking(artifactKey, DownloadArtifactArea.Staging) ?: return@withContext false
            inspection.parts ==
                checkpoint.parts
                    .map { part -> DownloadArtifactPartInspection(part.partKey, part.lengthBytes) }
                    .sortedBy { part -> part.partKey.value }
        }

    override suspend fun normalizeStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean =
        withContext(ioDispatcher) {
            val staging = artifactDirectory(artifactKey, DownloadArtifactArea.Staging)
            if (attributes(staging) == null) return@withContext checkpoint.parts.all { part -> part.lengthBytes == 0L }
            requireDirectory(staging)
            val expected = checkpoint.parts.associate { part -> part.partKey.value to part.lengthBytes }
            val names = directoryNames(staging)
            if (
                names.any { name -> name !in expected } ||
                expected.any { (name, requiredLength) -> name !in names && requiredLength != 0L }
            ) {
                return@withContext false
            }
            names.forEach { name ->
                val part = child(staging, name, isDirectory = false)
                val actualLength = regularFileLength(part)
                val requiredLength = expected.getValue(name)
                if (actualLength < requiredLength) return@withContext false
                if (actualLength > requiredLength) {
                    val file = checkNotNull(fopen(requireNotNull(part.path), "r+"))
                    try {
                        check(ftruncate(fileno(file), requiredLength) == 0) { "Unable to normalize artifact checkpoint." }
                        check(fflush(file) == 0) { "Unable to flush normalized artifact checkpoint." }
                        check(fsync(fileno(file)) == 0) { "Unable to sync normalized artifact checkpoint." }
                    } finally {
                        check(fclose(file) == 0) { "Unable to close normalized artifact checkpoint." }
                    }
                }
            }
            true
        }

    override suspend fun promote(artifactKey: DownloadArtifactKey): DownloadArtifactInspection =
        withContext(ioDispatcher) {
            val staging = artifactDirectory(artifactKey, DownloadArtifactArea.Staging)
            requireDirectory(staging)
            val completed = artifactDirectory(artifactKey, DownloadArtifactArea.Completed)
            check(attributes(completed) == null) { "Completed artifact already exists." }
            check(rename(requireNotNull(staging.path), requireNotNull(completed.path)) == 0) {
                "Unable to atomically promote artifact."
            }
            checkNotNull(inspectBlocking(artifactKey, DownloadArtifactArea.Completed)) {
                "Promoted artifact is unavailable."
            }
        }

    override suspend fun delete(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ) {
        withContext(ioDispatcher) {
            val artifact = artifactDirectory(artifactKey, area)
            if (attributes(artifact) != null) deleteContainedPackage(artifact)
            if (area == DownloadArtifactArea.Staging) {
                deleteStagingMetadataReplacementDirectory(artifactKey)
            }
        }
    }

    override suspend fun enumerate(area: DownloadArtifactArea): List<DownloadArtifactInspection> =
        withContext(ioDispatcher) {
            directoryNames(rootFor(area))
                .map { name ->
                    val key = DownloadArtifactKey(name)
                    checkNotNull(inspectBlocking(key, area)) { "Artifact package disappeared during inspection." }
                }.sortedBy { inspection -> inspection.artifactKey.value }
        }

    override suspend fun capacity(): DownloadArtifactCapacity =
        withContext(ioDispatcher) {
            val rootPath = requireNotNull(root.path)
            val attributes =
                checkNotNull(fileManager.attributesOfFileSystemForPath(rootPath, error = null)) {
                    "Unable to inspect download storage capacity."
                }
            DownloadArtifactCapacity(
                availableBytes = attributes.number(requireNotNull(NSFileSystemFreeSize)),
                totalBytes = attributes.number(requireNotNull(NSFileSystemSize)),
            )
        }

    private fun inspectBlocking(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): DownloadArtifactInspection? {
        val artifact = artifactDirectory(artifactKey, area)
        if (attributes(artifact) == null) return null
        requireDirectory(artifact)
        val parts =
            directoryNames(artifact)
                .map { name ->
                    val partKey = DownloadArtifactPartKey.from(name)
                    val part = child(artifact, name, isDirectory = false)
                    DownloadArtifactPartInspection(partKey, regularFileLength(part))
                }.sortedBy { part -> part.partKey.value }
        return DownloadArtifactInspection(artifactKey, area, parts)
    }

    private fun areaDirectory(area: DownloadArtifactArea): NSURL =
        child(root, area.directoryName, isDirectory = true).also(::ensureDirectory)

    private fun artifactDirectory(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): NSURL = child(rootFor(area), artifactKey.value, isDirectory = true)

    private fun rootFor(area: DownloadArtifactArea): NSURL =
        when (area) {
            DownloadArtifactArea.Staging -> stagingRoot
            DownloadArtifactArea.Completed -> completedRoot
        }

    private fun stagingMetadataReplacementFile(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
    ): NSURL {
        val artifactReplacementDirectory =
            stagingMetadataReplacementDirectory(artifactKey).also(::ensureDirectory)
        return child(artifactReplacementDirectory, "${partKey.value}.tmp", isDirectory = false)
    }

    private fun stagingMetadataReplacementDirectory(artifactKey: DownloadArtifactKey): NSURL =
        child(stagingMetadataReplacementRoot, artifactKey.value, isDirectory = true)

    private fun removeEmptyStagingMetadataReplacementDirectory(artifactKey: DownloadArtifactKey) {
        val directory = stagingMetadataReplacementDirectory(artifactKey)
        if (attributes(directory) == null) return
        requireDirectory(directory)
        if (directoryNames(directory).isEmpty()) {
            check(fileManager.removeItemAtURL(directory, null)) {
                "Unable to remove empty staging metadata replacement directory."
            }
        }
    }

    private fun deleteStagingMetadataReplacementDirectory(artifactKey: DownloadArtifactKey) {
        val directory = stagingMetadataReplacementDirectory(artifactKey)
        if (attributes(directory) == null) return
        requireDirectory(directory)
        directoryNames(directory).forEach { name ->
            val replacement = child(directory, name, isDirectory = false)
            regularFileLength(replacement)
            check(fileManager.removeItemAtURL(replacement, null)) {
                "Unable to delete staging metadata replacement."
            }
        }
        check(fileManager.removeItemAtURL(directory, null)) {
            "Unable to delete staging metadata replacement directory."
        }
    }

    private fun ensurePrivateDirectory(directory: NSURL) {
        ensureDirectory(directory)
        check(directory.setResourceValue(true, NSURLIsExcludedFromBackupKey, null)) {
            "Unable to exclude download storage from backup."
        }
    }

    private fun ensureDirectory(directory: NSURL) {
        if (attributes(directory) == null) {
            check(fileManager.createDirectoryAtURL(directory, true, null, null)) {
                "Unable to create private download storage."
            }
        }
        requireDirectory(directory)
    }

    private fun requireDirectory(directory: NSURL) {
        check(attributes(directory)?.get(NSFileType) == NSFileTypeDirectory) {
            "Artifact package is not a contained directory."
        }
    }

    private fun regularFileLength(file: NSURL): Long {
        val attributes = checkNotNull(attributes(file)) { "Artifact part is missing." }
        check(attributes[NSFileType] == NSFileTypeRegular) { "Artifact part is not a contained regular file." }
        return attributes.number(requireNotNull(NSFileSize))
    }

    private fun attributes(url: NSURL): Map<Any?, *>? =
        fileManager.attributesOfItemAtPath(
            requireNotNull(url.path),
            error = null,
        )

    private fun directoryNames(directory: NSURL): List<String> =
        checkNotNull(fileManager.contentsOfDirectoryAtPath(requireNotNull(directory.path), error = null)) {
            "Unable to inspect artifact package."
        }.map { value -> checkNotNull(value as? String) { "Artifact package contained an invalid entry." } }

    private fun deleteContainedPackage(directory: NSURL) {
        requireDirectory(directory)
        directoryNames(directory).forEach { name ->
            val partKey = DownloadArtifactPartKey.from(name)
            val part = child(directory, partKey.value, isDirectory = false)
            regularFileLength(part)
            check(fileManager.removeItemAtURL(part, null)) { "Unable to delete artifact part." }
        }
        check(fileManager.removeItemAtURL(directory, null)) { "Unable to delete artifact package." }
    }

    private fun child(
        parent: NSURL,
        name: String,
        isDirectory: Boolean,
    ): NSURL = requireNotNull(parent.URLByAppendingPathComponent(name, isDirectory = isDirectory))
}

private class IosDownloadArtifactWriter(
    override val partKey: DownloadArtifactPartKey,
    private val file: CPointer<FILE>,
    initialLengthBytes: Long,
    private val ioDispatcher: CoroutineDispatcher,
) : DownloadArtifactWriter {
    private val operationMutex = Mutex()
    private val currentLength = atomic(initialLengthBytes)
    private val closed = atomic(false)

    override val lengthBytes: Long
        get() = currentLength.value

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        requireValidWriteSlice(buffer.size, offset, length)
        operationMutex.withLock {
            check(!closed.value) { "Artifact writer is closed." }
            if (length == 0) return@withLock
            withContext(ioDispatcher) {
                val nextLength = checkedArtifactLengthAfterWrite(currentLength.value, length.toLong())
                val written =
                    buffer.usePinned { pinned ->
                        fwrite(pinned.addressOf(offset), 1u, length.toULong(), file)
                    }
                check(written == length.toULong()) { "Unable to write artifact part." }
                currentLength.value = nextLength
            }
        }
    }

    override suspend fun checkpoint(): DownloadArtifactPartCheckpoint =
        operationMutex.withLock {
            check(!closed.value) { "Artifact writer is closed." }
            withContext(ioDispatcher) {
                syncFile(file)
                DownloadArtifactPartCheckpoint(partKey, currentLength.value)
            }
        }

    override suspend fun rewrite(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): DownloadArtifactPartCheckpoint =
        operationMutex.withLock {
            requireValidRewriteSlice(buffer.size, offset, length)
            check(!closed.value) { "Artifact writer is closed." }
            withContext(ioDispatcher) {
                check(ftruncate(fileno(file), 0L) == 0) { "Unable to truncate artifact metadata." }
                check(fseek(file, 0L, SEEK_SET) == 0) { "Unable to rewind artifact metadata." }
                if (length > 0) {
                    val written =
                        buffer.usePinned { pinned ->
                            fwrite(pinned.addressOf(offset), 1u, length.toULong(), file)
                        }
                    check(written == length.toULong()) { "Unable to rewrite artifact metadata." }
                }
                check(fflush(file) == 0) { "Unable to flush artifact metadata." }
                check(fsync(fileno(file)) == 0) { "Unable to sync artifact metadata." }
                currentLength.value = length.toLong()
                DownloadArtifactPartCheckpoint(partKey, currentLength.value)
            }
        }

    override suspend fun close() {
        withContext(NonCancellable) {
            operationMutex.withLock {
                if (!closed.value) {
                    withContext(ioDispatcher) {
                        try {
                            syncFile(file)
                        } finally {
                            try {
                                check(fclose(file) == 0) { "Unable to close artifact part." }
                            } finally {
                                closed.value = true
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun syncFile(file: CPointer<FILE>) {
    check(fflush(file) == 0) { "Unable to flush artifact part." }
    check(fsync(fileno(file)) == 0) { "Unable to checkpoint artifact part." }
}

private fun Map<Any?, *>.number(key: String): Long =
    checkNotNull(this[key] as? NSNumber) { "Download storage attribute is unavailable." }
        .longLongValue
        .coerceAtLeast(0L)

private val DownloadArtifactArea.directoryName: String
    get() =
        when (this) {
            DownloadArtifactArea.Staging -> "staging"
            DownloadArtifactArea.Completed -> "completed"
        }

internal fun iosDownloadsRootDirectory(): NSURL {
    val applicationSupport =
        checkNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ),
        ) { "Application Support is unavailable." }
    return requireNotNull(applicationSupport.URLByAppendingPathComponent("downloads", isDirectory = true))
}
