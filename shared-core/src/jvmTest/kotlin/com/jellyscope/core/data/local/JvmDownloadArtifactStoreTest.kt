// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.DownloadArtifactKey
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmDownloadArtifactStoreTest {
    private lateinit var tempDirectory: Path

    @BeforeTest
    fun setUp() {
        tempDirectory = Files.createTempDirectory("jellyscope-download-artifact-test")
    }

    @AfterTest
    fun tearDown() {
        tempDirectory.toFile().deleteRecursively()
    }

    @Test
    fun checkpointResumePromoteEnumerateAndDeletePreserveExactBytes() =
        runTest {
            val store = JvmDownloadArtifactStore(rootDirectory = tempDirectory.toFile())
            val artifactKey = DownloadArtifactKey("artifact_a")
            val partKey = DownloadArtifactPartKey.from("main.bin")
            val firstWriter =
                store.openStagingWriter(
                    artifactKey,
                    partKey,
                    DownloadArtifactWriteMode.Create,
                )
            firstWriter.write(byteArrayOf(1, 2, 3))
            assertEquals(DownloadArtifactPartCheckpoint(partKey, 3L), firstWriter.checkpoint())
            firstWriter.close()

            val resumedWriter =
                store.openStagingWriter(
                    artifactKey,
                    partKey,
                    DownloadArtifactWriteMode.Resume(expectedLengthBytes = 3L),
                )
            resumedWriter.write(byteArrayOf(4, 5, 6), offset = 1, length = 2)
            assertEquals(5L, resumedWriter.checkpoint().lengthBytes)
            resumedWriter.close()

            val expectedCheckpoint =
                DownloadArtifactCheckpoint(
                    listOf(DownloadArtifactPartCheckpoint(partKey, 5L)),
                )
            assertTrue(store.validateStagingCheckpoint(artifactKey, expectedCheckpoint))
            assertFalse(
                store.validateStagingCheckpoint(
                    artifactKey,
                    DownloadArtifactCheckpoint(listOf(DownloadArtifactPartCheckpoint(partKey, 4L))),
                ),
            )
            assertFalse(
                store.validateStagingCheckpoint(
                    artifactKey,
                    DownloadArtifactCheckpoint(emptyList()),
                ),
            )
            assertFalse(
                store.validateStagingCheckpoint(
                    artifactKey,
                    DownloadArtifactCheckpoint(
                        listOf(
                            DownloadArtifactPartCheckpoint(partKey, 5L),
                            DownloadArtifactPartCheckpoint(DownloadArtifactPartKey.from("missing.bin"), 1L),
                        ),
                    ),
                ),
            )

            val promoted = store.promote(artifactKey)
            assertEquals(DownloadArtifactArea.Completed, promoted.area)
            assertEquals(5L, promoted.totalBytes)
            assertNull(store.inspect(artifactKey, DownloadArtifactArea.Staging))
            assertEquals(listOf(promoted), store.enumerate(DownloadArtifactArea.Completed))

            val installedBytes =
                Files
                    .newInputStream(
                        tempDirectory
                            .resolve("completed")
                            .resolve(artifactKey.value)
                            .resolve(partKey.value),
                    ).use { input ->
                        ByteArray(5).also { bytes ->
                            assertEquals(bytes.size, input.read(bytes))
                            assertEquals(-1, input.read())
                        }
                    }
            assertTrue(installedBytes.contentEquals(byteArrayOf(1, 2, 3, 5, 6)))

            store.delete(artifactKey, DownloadArtifactArea.Completed)
            assertNull(store.inspect(artifactKey, DownloadArtifactArea.Completed))
            assertTrue(store.enumerate(DownloadArtifactArea.Completed).isEmpty())
        }

    @Test
    fun resumeRejectsCheckpointLengthDriftAndWriterRejectsUnboundedChunks() =
        runTest {
            val store = JvmDownloadArtifactStore(rootDirectory = tempDirectory.toFile())
            val artifactKey = DownloadArtifactKey("artifact_b")
            val partKey = DownloadArtifactPartKey.from("main.bin")
            val writer = store.openStagingWriter(artifactKey, partKey, DownloadArtifactWriteMode.Create)
            writer.write(byteArrayOf(1))
            writer.close()

            assertFailsWith<IllegalStateException> {
                store.openStagingWriter(
                    artifactKey,
                    partKey,
                    DownloadArtifactWriteMode.Resume(expectedLengthBytes = 2L),
                )
            }

            val otherPart = DownloadArtifactPartKey.from("other.bin")
            val otherWriter = store.openStagingWriter(artifactKey, otherPart, DownloadArtifactWriteMode.Create)
            assertFailsWith<IllegalArgumentException> {
                otherWriter.write(ByteArray(DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES + 1))
            }
            otherWriter.close()
        }

    @Test
    fun crashNormalizationTruncatesUncheckpointedTailAndAllowsOneZeroPartToBeMissing() =
        runTest {
            val store = JvmDownloadArtifactStore(rootDirectory = tempDirectory.toFile())
            val artifactKey = DownloadArtifactKey("artifact_normalize")
            val main = DownloadArtifactPartKey.from("original.bin")
            val sidecar = DownloadArtifactPartKey.from("sidecar.vtt")
            val writer = store.openStagingWriter(artifactKey, main, DownloadArtifactWriteMode.Create)
            writer.write(byteArrayOf(1, 2))
            writer.checkpoint()
            writer.write(byteArrayOf(3, 4))
            writer.close()

            assertTrue(
                store.normalizeStagingCheckpoint(
                    artifactKey,
                    DownloadArtifactCheckpoint(
                        listOf(
                            DownloadArtifactPartCheckpoint(main, 2L),
                            DownloadArtifactPartCheckpoint(sidecar, 0L),
                        ),
                    ),
                ),
            )
            assertEquals(
                2L,
                store
                    .inspect(artifactKey, DownloadArtifactArea.Staging)
                    ?.parts
                    ?.firstOrNull { part -> part.partKey == main }
                    ?.lengthBytes,
            )

            val emptySidecarCheckpoint =
                DownloadArtifactCheckpoint(
                    listOf(
                        DownloadArtifactPartCheckpoint(main, 0L),
                        DownloadArtifactPartCheckpoint(sidecar, 0L),
                    ),
                )
            store.delete(artifactKey, DownloadArtifactArea.Staging)
            val mainOnly = store.openStagingWriter(artifactKey, main, DownloadArtifactWriteMode.Create)
            mainOnly.close()
            assertTrue(store.normalizeStagingCheckpoint(artifactKey, emptySidecarCheckpoint))
        }

    @Test
    fun inspectionRejectsAFileThatEscapesTheRootThroughASymbolicLink() =
        runTest {
            val artifactKey = DownloadArtifactKey("artifact_c")
            val packageDirectory = tempDirectory.resolve("staging").resolve(artifactKey.value)
            Files.createDirectories(packageDirectory)
            val outside = Files.createTempFile("jellyscope-download-outside", ".bin")
            try {
                Files.write(outside, byteArrayOf(9, 9, 9))
                Files.createSymbolicLink(packageDirectory.resolve("main.bin"), outside)
                val store = JvmDownloadArtifactStore(rootDirectory = tempDirectory.toFile())

                assertFailsWith<IllegalStateException> {
                    store.inspect(artifactKey, DownloadArtifactArea.Staging)
                }
            } finally {
                Files.deleteIfExists(outside)
            }
        }

    @Test
    fun capacityComesFromTheInjectedPrivateRootProbe() =
        runTest {
            val expected = DownloadArtifactCapacity(availableBytes = 7_000L, totalBytes = 11_000L)
            var probedRoot: Path? = null
            val store =
                JvmDownloadArtifactStore(
                    rootDirectory = tempDirectory.toFile(),
                    capacityProvider = { root ->
                        probedRoot = root.toPath()
                        expected
                    },
                )

            assertEquals(expected, store.capacity())
            assertEquals(tempDirectory.toFile().canonicalFile.toPath(), probedRoot)
        }
}
