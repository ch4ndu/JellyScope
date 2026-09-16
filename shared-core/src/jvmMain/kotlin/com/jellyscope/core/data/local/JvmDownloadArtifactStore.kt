// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.OfflineArtworkRole
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class JvmDownloadArtifactStore(
    rootDirectory: File = jvmDownloadsRootDirectory(),
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
    private val capacityProvider: (File) -> DownloadArtifactCapacity = ::jvmDownloadArtifactCapacity,
) : DownloadArtifactStore {
    private val root = rootDirectory.apply { mkdirs() }.canonicalFile
    private val stagingRoot = areaDirectory(DownloadArtifactArea.Staging)
    private val completedRoot = areaDirectory(DownloadArtifactArea.Completed)
    private val stagingMetadataReplacementRoot =
        containedChild(root, "staging-metadata-replacement").also(::ensureDirectory)
    private val presentationRoot = containedChild(root, "presentation").also(::ensureDirectory)
    private val presentationTemporaryRoot = containedChild(root, "presentation-temporary").also(::ensureDirectory)

    override suspend fun openStagingWriter(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        mode: DownloadArtifactWriteMode,
    ): DownloadArtifactWriter =
        withContext(ioDispatcher) {
            val artifactDirectory = artifactDirectory(artifactKey, DownloadArtifactArea.Staging)
            ensureDirectory(artifactDirectory)
            val target = containedChild(artifactDirectory, partKey.value)
            when (mode) {
                DownloadArtifactWriteMode.Create -> {
                    check(target.createNewFile()) { "Artifact staging part already exists." }
                }

                is DownloadArtifactWriteMode.Resume -> {
                    checkRegularContainedFile(target)
                    check(target.length() == mode.expectedLengthBytes) { "Artifact checkpoint length changed." }
                }
            }
            val file = RandomAccessFile(target, "rw")
            file.seek(
                when (mode) {
                    DownloadArtifactWriteMode.Create -> 0L
                    is DownloadArtifactWriteMode.Resume -> mode.expectedLengthBytes
                },
            )
            JvmDownloadArtifactWriter(
                partKey = partKey,
                file = file,
                initialLengthBytes = file.filePointer,
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
            val file = containedChild(artifactDirectory(artifactKey, DownloadArtifactArea.Completed), partKey.value)
            checkRegularContainedFile(file)
            if (file.length() != part.lengthBytes) return@withContext null
            file.canonicalPath
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
            val file = containedChild(artifactDirectory(artifactKey, area), partKey.value)
            checkRegularContainedFile(file)
            if (file.length() != part.lengthBytes) return@withContext null
            val bytes = ByteArray(part.lengthBytes.toInt())
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) return@use
                    offset += read
                }
                if (offset != bytes.size) return@withContext null
            }
            bytes
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
            val target = containedChild(staging, partKey.value)
            if (target.exists()) checkRegularContainedFile(target)
            val replacement = stagingMetadataReplacementFile(artifactKey, partKey)
            if (replacement.exists()) {
                checkRegularContainedFile(replacement)
                check(replacement.delete()) { "Unable to remove stale staging metadata replacement." }
            }
            check(replacement.createNewFile()) { "Unable to create staging metadata replacement." }
            RandomAccessFile(replacement, "rw").use { file ->
                file.write(buffer, offset, length)
                file.fd.sync()
            }
            try {
                Files.move(
                    replacement.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                error("Atomic staging metadata replacement is unavailable for this storage root.")
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
            if (!staging.exists()) return@withContext checkpoint.parts.all { part -> part.lengthBytes == 0L }
            checkContainedDirectory(staging)
            val expected = checkpoint.parts.associate { part -> part.partKey.value to part.lengthBytes }
            val children = checkNotNull(staging.listFiles()) { "Unable to inspect artifact package." }
            if (children.any { child -> child.name !in expected } ||
                expected.any { (name, requiredLength) ->
                    children.none { child -> child.name == name } && requiredLength != 0L
                }
            ) {
                return@withContext false
            }
            children.forEach { child ->
                checkRegularContainedFile(child)
                val requiredLength = expected.getValue(child.name)
                if (child.length() < requiredLength) return@withContext false
                if (child.length() > requiredLength) {
                    RandomAccessFile(child, "rw").use { file ->
                        file.setLength(requiredLength)
                        file.fd.sync()
                    }
                }
            }
            true
        }

    override suspend fun promote(artifactKey: DownloadArtifactKey): DownloadArtifactInspection =
        withContext(ioDispatcher) {
            val staging = artifactDirectory(artifactKey, DownloadArtifactArea.Staging)
            checkContainedDirectory(staging)
            val completed = artifactDirectory(artifactKey, DownloadArtifactArea.Completed)
            check(!completed.exists()) { "Completed artifact already exists." }
            try {
                Files.move(staging.toPath(), completed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                error("Atomic artifact promotion is unavailable for this storage root.")
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
            if (artifact.exists()) deleteContainedTree(artifact)
            if (area == DownloadArtifactArea.Staging) {
                deleteStagingMetadataReplacementDirectory(artifactKey)
            }
        }
    }

    override suspend fun enumerate(area: DownloadArtifactArea): List<DownloadArtifactInspection> =
        withContext(ioDispatcher) {
            val areaRoot = rootFor(area)
            areaRoot
                .listFiles()
                .orEmpty()
                .map { child ->
                    val key = DownloadArtifactKey(child.name)
                    checkNotNull(inspectBlocking(key, area)) { "Artifact package disappeared during inspection." }
                }.sortedBy { inspection -> inspection.artifactKey.value }
        }

    override suspend fun capacity(): DownloadArtifactCapacity = withContext(ioDispatcher) { capacityProvider(root) }

    override suspend fun stagePresentation(
        artifactKey: DownloadArtifactKey,
        role: OfflineArtworkRole,
        bytes: ByteArray,
    ): Long =
        withContext(ioDispatcher) {
            require(bytes.size in 1..DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES)
            val temporaryDirectory = presentationTemporaryDirectory(artifactKey).also(::ensureDirectory)
            val temporary = containedChild(temporaryDirectory, role.presentationTemporaryFileName())
            if (temporary.exists()) {
                checkRegularContainedFile(temporary)
                check(temporary.delete()) { "Unable to replace temporary presentation image." }
            }
            RandomAccessFile(temporary, "rw").use { file ->
                file.write(bytes)
                file.fd.sync()
            }
            bytes.size.toLong()
        }

    override suspend fun publishPresentation(
        artifactKey: DownloadArtifactKey,
        role: OfflineArtworkRole,
    ): DownloadPresentationInspection? =
        withContext(ioDispatcher) {
            val temporary = containedChild(presentationTemporaryDirectory(artifactKey), role.presentationTemporaryFileName())
            if (!temporary.exists()) return@withContext null
            checkRegularContainedFile(temporary)
            if (temporary.length() !in 1L..DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES.toLong()) return@withContext null
            val target = containedChild(presentationDirectory(artifactKey).also(::ensureDirectory), role.presentationFileName())
            if (target.exists()) checkRegularContainedFile(target)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                error("Atomic presentation replacement is unavailable for this storage root.")
            }
            removeEmptyPresentationTemporaryDirectory(artifactKey)
            presentationInspectionBlocking(artifactKey)
        }

    override suspend fun readPresentation(
        artifactKey: DownloadArtifactKey,
        role: OfflineArtworkRole,
        maxBytes: Int,
    ): ByteArray? =
        withContext(ioDispatcher) {
            require(maxBytes in 0..DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES)
            val file = containedChild(presentationDirectory(artifactKey), role.presentationFileName())
            if (!file.exists()) return@withContext null
            checkRegularContainedFile(file)
            if (file.length() !in 1L..maxBytes.toLong()) return@withContext null
            FileInputStream(file).use { input -> input.readBytes() }
        }

    override suspend fun reconcilePresentation(artifactKey: DownloadArtifactKey): DownloadPresentationInspection =
        withContext(ioDispatcher) {
            deletePresentationTemporaryDirectory(artifactKey)
            val directory = presentationDirectory(artifactKey)
            if (!directory.exists()) return@withContext DownloadPresentationInspection(emptyMap())
            checkContainedDirectory(directory)
            directory.listFiles().orEmpty().forEach { file ->
                val recognized = OfflineArtworkRole.entries.any { role -> role.presentationFileName() == file.name }
                if (!recognized || !file.isFile || file.length() !in 1L..DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES.toLong()) {
                    deleteContainedTree(file)
                } else {
                    checkRegularContainedFile(file)
                }
            }
            presentationInspectionBlocking(artifactKey)
        }

    override suspend fun deletePresentation(artifactKey: DownloadArtifactKey) {
        withContext(ioDispatcher) {
            deletePresentationTemporaryDirectory(artifactKey)
            val directory = presentationDirectory(artifactKey)
            if (directory.exists()) deleteContainedTree(directory)
        }
    }

    private fun inspectBlocking(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): DownloadArtifactInspection? {
        val artifact = artifactDirectory(artifactKey, area)
        if (!artifact.exists()) return null
        checkContainedDirectory(artifact)
        val parts =
            checkNotNull(artifact.listFiles()) { "Unable to inspect artifact package." }
                .map { child ->
                    checkRegularContainedFile(child)
                    val partKey =
                        checkNotNull(DownloadArtifactPartKey.fromOrNull(child.name)) {
                            "Artifact package contains an invalid part key."
                        }
                    DownloadArtifactPartInspection(partKey, child.length())
                }.sortedBy { part -> part.partKey.value }
        return DownloadArtifactInspection(artifactKey, area, parts)
    }

    private fun areaDirectory(area: DownloadArtifactArea): File = containedChild(root, area.directoryName).also(::ensureDirectory)

    private fun presentationDirectory(artifactKey: DownloadArtifactKey): File = containedChild(presentationRoot, artifactKey.value)

    private fun presentationTemporaryDirectory(artifactKey: DownloadArtifactKey): File =
        containedChild(presentationTemporaryRoot, artifactKey.value)

    private fun removeEmptyPresentationTemporaryDirectory(artifactKey: DownloadArtifactKey) {
        val directory = presentationTemporaryDirectory(artifactKey)
        if (directory.exists() && directory.listFiles()?.isEmpty() == true) check(directory.delete())
    }

    private fun deletePresentationTemporaryDirectory(artifactKey: DownloadArtifactKey) {
        val directory = presentationTemporaryDirectory(artifactKey)
        if (directory.exists()) deleteContainedTree(directory)
    }

    private fun presentationInspectionBlocking(artifactKey: DownloadArtifactKey): DownloadPresentationInspection {
        val directory = presentationDirectory(artifactKey)
        if (!directory.exists()) return DownloadPresentationInspection(emptyMap())
        checkContainedDirectory(directory)
        val roleBytes =
            OfflineArtworkRole.entries
                .mapNotNull { role ->
                    val file = containedChild(directory, role.presentationFileName())
                    if (!file.exists()) {
                        null
                    } else {
                        checkRegularContainedFile(file)
                        role to file.length().takeIf { length -> length in 1L..DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES.toLong() }
                    }
                }.associate { (role, length) -> role to checkNotNull(length) }
        return DownloadPresentationInspection(roleBytes).also { inspection ->
            check(inspection.totalBytes <= DOWNLOAD_PRESENTATION_TOTAL_MAX_BYTES)
        }
    }

    private fun artifactDirectory(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): File = containedChild(rootFor(area), artifactKey.value)

    private fun rootFor(area: DownloadArtifactArea): File =
        when (area) {
            DownloadArtifactArea.Staging -> stagingRoot
            DownloadArtifactArea.Completed -> completedRoot
        }

    private fun stagingMetadataReplacementFile(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
    ): File {
        val artifactReplacementDirectory =
            stagingMetadataReplacementDirectory(artifactKey).also(::ensureDirectory)
        return containedChild(artifactReplacementDirectory, "${partKey.value}.tmp")
    }

    private fun stagingMetadataReplacementDirectory(artifactKey: DownloadArtifactKey): File =
        containedChild(stagingMetadataReplacementRoot, artifactKey.value)

    private fun removeEmptyStagingMetadataReplacementDirectory(artifactKey: DownloadArtifactKey) {
        val directory = stagingMetadataReplacementDirectory(artifactKey)
        if (!directory.exists()) return
        checkContainedDirectory(directory)
        if (directory.listFiles()?.isEmpty() == true) {
            check(directory.delete()) { "Unable to remove empty staging metadata replacement directory." }
        }
    }

    private fun deleteStagingMetadataReplacementDirectory(artifactKey: DownloadArtifactKey) {
        val directory = stagingMetadataReplacementDirectory(artifactKey)
        if (!directory.exists()) return
        checkContainedDirectory(directory)
        checkNotNull(directory.listFiles()) { "Unable to inspect staging metadata replacement directory." }
            .forEach { replacement ->
                checkRegularContainedFile(replacement)
                check(replacement.delete()) { "Unable to delete staging metadata replacement." }
            }
        check(directory.delete()) { "Unable to delete staging metadata replacement directory." }
    }

    private fun ensureDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create artifact storage directory." }
        checkContainedDirectory(directory)
    }

    private fun checkContainedDirectory(directory: File) {
        check(directory.isDirectory) { "Artifact package is not a directory." }
        checkNotLink(directory)
        requireContained(directory)
    }

    private fun checkRegularContainedFile(file: File) {
        check(file.isFile) { "Artifact part is not a regular file." }
        checkNotLink(file)
        requireContained(file)
    }

    private fun containedChild(
        parent: File,
        childName: String,
    ): File = File(parent, childName).also(::requireContained)

    private fun requireContained(file: File) {
        val canonical = file.canonicalFile
        val rootPath = root.toPath()
        check(canonical.toPath().startsWith(rootPath) && canonical != root) {
            "Artifact path escaped its private root."
        }
    }

    private fun checkNotLink(file: File) {
        val normalized = file.absoluteFile.toPath().normalize()
        check(normalized == file.canonicalFile.toPath()) { "Artifact links are not supported." }
    }

    private fun deleteContainedTree(file: File) {
        checkNotLink(file)
        requireContained(file)
        if (file.isDirectory) {
            checkNotNull(file.listFiles()) { "Unable to inspect artifact package for deletion." }
                .forEach(::deleteContainedTree)
        }
        check(file.delete()) { "Unable to delete artifact storage entry." }
    }
}

private class JvmDownloadArtifactWriter(
    override val partKey: DownloadArtifactPartKey,
    private val file: RandomAccessFile,
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
            withContext(ioDispatcher) {
                val nextLength = checkedArtifactLengthAfterWrite(currentLength.value, length.toLong())
                file.write(buffer, offset, length)
                currentLength.value = nextLength
            }
        }
    }

    override suspend fun checkpoint(): DownloadArtifactPartCheckpoint =
        operationMutex.withLock {
            check(!closed.value) { "Artifact writer is closed." }
            withContext(ioDispatcher) {
                file.fd.sync()
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
                file.setLength(0L)
                file.seek(0L)
                file.write(buffer, offset, length)
                file.fd.sync()
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
                            file.fd.sync()
                        } finally {
                            try {
                                file.close()
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

private val DownloadArtifactArea.directoryName: String
    get() =
        when (this) {
            DownloadArtifactArea.Staging -> "staging"
            DownloadArtifactArea.Completed -> "completed"
        }

internal fun jvmDownloadsRootDirectory(): File = File(jellyscopeDataDirectory(), "downloads").apply { mkdirs() }

private fun jvmDownloadArtifactCapacity(root: File): DownloadArtifactCapacity =
    DownloadArtifactCapacity(
        availableBytes = root.usableSpace.coerceAtLeast(0L),
        totalBytes = root.totalSpace.coerceAtLeast(0L),
    )
