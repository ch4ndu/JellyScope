// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.DownloadArtifactKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DownloadArtifactContractTest {
    @Test
    fun opaqueArtifactAndPartKeysRejectPathsAndTraversal() {
        assertEquals("artifact_01-A", DownloadArtifactKey("artifact_01-A").value)

        listOf(
            "",
            ".",
            "..",
            "../outside",
            "nested/part",
            "nested\\part",
            "/absolute",
            " leading",
            "artifact.txt",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>("expected artifact key '$value' to be rejected") {
                DownloadArtifactKey(value)
            }
        }

        listOf("", ".", "..", "../outside", "nested/part", "nested\\part", "/absolute", " leading")
            .forEach { value ->
                assertNull(DownloadArtifactPartKey.fromOrNull(value))
            }
    }

    @Test
    fun partKeysAcceptOnlyBoundedOpaqueSingleSegments() {
        listOf("main.bin", "index.m3u8", "segment-001.ts", "sidecar_2.vtt").forEach { value ->
            assertEquals(value, DownloadArtifactPartKey.from(value).value)
        }

        assertNull(DownloadArtifactPartKey.fromOrNull("a".repeat(129)))
        assertEquals("a".repeat(128), DownloadArtifactPartKey.from("a".repeat(128)).value)
    }

    @Test
    fun writerSlicesAreStrictlyBounded() {
        requireValidWriteSlice(bufferSize = DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES, offset = 0, length = 0)
        requireValidWriteSlice(
            bufferSize = DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES,
            offset = 0,
            length = DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES,
        )

        assertFailsWith<IllegalArgumentException> {
            requireValidWriteSlice(
                bufferSize = DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES + 1,
                offset = 0,
                length = DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            requireValidWriteSlice(bufferSize = 4, offset = 3, length = 2)
        }
    }

    @Test
    fun checkpointsRequireUniquePartsAndNonNegativeLengths() {
        val part = DownloadArtifactPartKey.from("main.bin")

        assertFailsWith<IllegalArgumentException> {
            DownloadArtifactCheckpoint(
                listOf(
                    DownloadArtifactPartCheckpoint(part, 1L),
                    DownloadArtifactPartCheckpoint(part, 2L),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DownloadArtifactPartCheckpoint(part, -1L)
        }
    }

    @Test
    fun inspectionTotalFailsClosedInsteadOfWrappingOnOverflow() {
        assertFailsWith<IllegalStateException> {
            DownloadArtifactInspection(
                artifactKey = DownloadArtifactKey("artifact_a"),
                area = DownloadArtifactArea.Staging,
                parts =
                    listOf(
                        DownloadArtifactPartInspection(DownloadArtifactPartKey.from("main.bin"), Long.MAX_VALUE),
                        DownloadArtifactPartInspection(DownloadArtifactPartKey.from("sidecar.vtt"), 1L),
                    ),
            )
        }
    }
}
