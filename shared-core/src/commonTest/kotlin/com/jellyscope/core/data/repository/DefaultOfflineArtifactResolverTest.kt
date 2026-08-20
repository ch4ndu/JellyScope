// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_PART_KEY
import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY
import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactCapacity
import com.jellyscope.core.data.local.DownloadArtifactCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactInspection
import com.jellyscope.core.data.local.DownloadArtifactPartInspection
import com.jellyscope.core.data.local.DownloadArtifactPartKey
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadArtifactWriteMode
import com.jellyscope.core.data.local.DownloadArtifactWriter
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineArtifactRef
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.download.DownloadHlsPackage
import com.jellyscope.core.download.DownloadHlsParseResult
import com.jellyscope.core.download.DownloadHlsPartNames
import com.jellyscope.core.playback.DefaultOfflineArtifactResolver
import com.jellyscope.core.playback.OfflineArtifactLeaseRegistry
import com.jellyscope.core.playback.OfflineArtifactResolution
import com.jellyscope.core.playback.OfflineArtifactResolutionFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultOfflineArtifactResolverTest {
    private val account = AccountIdentity(serverId = "server-1", userId = "user-1")
    private val reference = OfflineArtifactRef(DownloadId("download_a"), attemptGeneration = 4L)

    @Test
    fun validCompletedOriginalReturnsLeaseWithTrustedResources() =
        runTest {
            val fixture = fixture()

            val result = fixture.resolver.acquire(account, reference)
            val lease = assertIs<OfflineArtifactResolution.Available>(result).lease

            assertEquals("/private/downloads/artifact_a/original.bin", lease.mainPathForController)
            assertTrue(
                fixture.registry.isLeased(
                    com.jellyscope.core.playback
                        .OfflineArtifactLeaseIdentity(reference),
                ),
            )
            lease.release()
            assertTrue(
                !fixture.registry.isLeased(
                    com.jellyscope.core.playback
                        .OfflineArtifactLeaseIdentity(reference),
                ),
            )
        }

    @Test
    fun wrongAccountDoesNotInspectArtifactOrExposeRecordFacts() =
        runTest {
            val fixture = fixture()

            val result = fixture.resolver.acquire(AccountIdentity("server-2", "user-2"), reference)

            assertEquals(
                OfflineArtifactResolutionFailure.UnauthorizedOrMissing,
                assertIs<OfflineArtifactResolution.Unavailable>(result).failure,
            )
            assertEquals(0, fixture.artifactStore.inspectCalls)
            assertEquals(0, fixture.artifactStore.pathCalls)
        }

    @Test
    fun staleGenerationIsRejectedBeforeArtifactInspection() =
        runTest {
            val fixture = fixture(record = fixtureRecord(generation = 5L))

            val result = fixture.resolver.acquire(account, reference)

            assertEquals(
                OfflineArtifactResolutionFailure.StaleGeneration,
                assertIs<OfflineArtifactResolution.Unavailable>(result).failure,
            )
            assertEquals(0, fixture.artifactStore.inspectCalls)
        }

    @Test
    fun corruptCompletedPackageIsRejectedBeforeLeaseAdmission() =
        runTest {
            val corruptStore = ResolverArtifactStore(parts = listOf(DownloadArtifactPartInspection(MAIN, 99L)))
            val fixture = fixture(artifactStore = corruptStore, record = fixtureRecord(physicalBytes = 100L))

            val result = fixture.resolver.acquire(account, reference)

            assertEquals(
                OfflineArtifactResolutionFailure.CorruptArtifact,
                assertIs<OfflineArtifactResolution.Unavailable>(result).failure,
            )
            assertTrue(
                !fixture.registry.isLeased(
                    com.jellyscope.core.playback
                        .OfflineArtifactLeaseIdentity(reference),
                ),
            )
        }

    @Test
    fun sameTotalBytesWithWrongOriginalSidecarSplitIsRejected() =
        runTest {
            val record =
                fixtureRecord(
                    physicalBytes = 100L,
                    expectedSourceBytes = 80L,
                    subtitleSelection = DownloadSubtitleSelection.ExternalTextSidecar("subtitle_a"),
                )
            val corruptStore =
                ResolverArtifactStore(
                    parts =
                        listOf(
                            DownloadArtifactPartInspection(MAIN, 70L),
                            DownloadArtifactPartInspection(SIDECAR, 30L),
                        ),
                )
            val fixture = fixture(record = record, artifactStore = corruptStore)

            val result = fixture.resolver.acquire(account, reference)

            assertEquals(
                OfflineArtifactResolutionFailure.CorruptArtifact,
                assertIs<OfflineArtifactResolution.Unavailable>(result).failure,
            )
            assertTrue(
                !fixture.registry.isLeased(
                    com.jellyscope.core.playback
                        .OfflineArtifactLeaseIdentity(reference),
                ),
            )
        }

    @Test
    fun localHlsIsFailClosedBeforePathResolution() =
        runTest {
            val record =
                fixtureRecord(
                    artifactKind = DownloadArtifactKind.LocalHlsPackage,
                    quality = DownloadQuality.Fixed(maxBitrateBps = 2_000_000L),
                )
            val fixture = fixture(record = record)

            val result = fixture.resolver.acquire(account, reference)

            assertEquals(
                OfflineArtifactResolutionFailure.CorruptArtifact,
                assertIs<OfflineArtifactResolution.Unavailable>(result).failure,
            )
            assertEquals(0, fixture.artifactStore.pathCalls)
        }

    @Test
    fun completeLocalHlsPackageReturnsMasterPlaylistLease() =
        runTest {
            val masterBytes =
                (
                    "#EXTM3U\n" +
                        "#EXT-X-STREAM-INF:BANDWIDTH=2000000\n" +
                        "media.m3u8\n"
                ).encodeToByteArray()
            val mediaBytes =
                (
                    "#EXTM3U\n" +
                        "#EXT-X-VERSION:3\n" +
                        "#EXT-X-TARGETDURATION:10\n" +
                        "#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXT-X-PLAYLIST-TYPE:VOD\n" +
                        "#EXTINF:10.000,\n" +
                        "segment-000000.ts\n" +
                        "#EXTINF:10.000,\n" +
                        "segment-000001.ts\n" +
                        "#EXT-X-ENDLIST\n"
                ).encodeToByteArray()
            val packageValue =
                assertIs<DownloadHlsParseResult.Package>(
                    DownloadHlsPackage.parse(
                        masterText = masterBytes.decodeToString(),
                        mediaText = mediaBytes.decodeToString(),
                    ),
                ).value
            val initialCheckpoint = packageValue.initialCheckpoint()
            val checkpointBytes =
                initialCheckpoint
                    .copy(
                        master =
                            initialCheckpoint.master.copy(
                                lengthBytes = masterBytes.size.toLong(),
                                complete = true,
                            ),
                        media =
                            initialCheckpoint.media.copy(
                                lengthBytes = mediaBytes.size.toLong(),
                                complete = true,
                            ),
                        segments =
                            initialCheckpoint.segments.mapIndexed { index, part ->
                                part.copy(lengthBytes = if (index == 0) 1_024L else 2_048L, complete = true)
                            },
                    ).encode()
                    .encodeToByteArray()
            val parts =
                listOf(
                    DownloadArtifactPartInspection(DownloadHlsPartNames.MASTER, masterBytes.size.toLong()),
                    DownloadArtifactPartInspection(DownloadHlsPartNames.MEDIA, mediaBytes.size.toLong()),
                    DownloadArtifactPartInspection(DownloadHlsPartNames.segment(0), 1_024L),
                    DownloadArtifactPartInspection(DownloadHlsPartNames.segment(1), 2_048L),
                    DownloadArtifactPartInspection(DownloadHlsPartNames.CHECKPOINT, checkpointBytes.size.toLong()),
                )
            val fixture =
                fixture(
                    record =
                        fixtureRecord(
                            artifactKind = DownloadArtifactKind.LocalHlsPackage,
                            quality = DownloadQuality.Fixed(maxBitrateBps = 2_000_000L),
                            physicalBytes = parts.sumOf { part -> part.lengthBytes },
                            expectedSourceBytes = null,
                        ),
                    artifactStore =
                        ResolverArtifactStore(
                            parts = parts,
                            partContents =
                                mapOf(
                                    DownloadHlsPartNames.MASTER to masterBytes,
                                    DownloadHlsPartNames.MEDIA to mediaBytes,
                                    DownloadHlsPartNames.CHECKPOINT to checkpointBytes,
                                ),
                        ),
                )

            val result = fixture.resolver.acquire(account, reference)
            val lease = assertIs<OfflineArtifactResolution.Available>(result).lease

            assertEquals(
                "/private/downloads/artifact_a/${DownloadHlsPartNames.MASTER.value}",
                lease.mainPathForController,
            )
            assertTrue(
                fixture.registry.isLeased(
                    com.jellyscope.core.playback
                        .OfflineArtifactLeaseIdentity(reference),
                ),
            )
            lease.release()
        }

    @Test
    fun localHlsWithRemotePlaylistReferenceDoesNotAcquireLease() =
        runTest {
            val masterBytes =
                (
                    "#EXTM3U\n" +
                        "#EXT-X-STREAM-INF:BANDWIDTH=2000000\n" +
                        "https://example.invalid/media.m3u8\n"
                ).encodeToByteArray()
            val mediaBytes =
                (
                    "#EXTM3U\n" +
                        "#EXT-X-VERSION:3\n" +
                        "#EXT-X-TARGETDURATION:10\n" +
                        "#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXT-X-PLAYLIST-TYPE:VOD\n" +
                        "#EXTINF:10.000,\n" +
                        "segment-000000.ts\n" +
                        "#EXT-X-ENDLIST\n"
                ).encodeToByteArray()
            val parts =
                listOf(
                    DownloadArtifactPartInspection(DownloadHlsPartNames.MASTER, masterBytes.size.toLong()),
                    DownloadArtifactPartInspection(DownloadHlsPartNames.MEDIA, mediaBytes.size.toLong()),
                    DownloadArtifactPartInspection(DownloadHlsPartNames.segment(0), 1_024L),
                )
            val fixture =
                fixture(
                    record =
                        fixtureRecord(
                            artifactKind = DownloadArtifactKind.LocalHlsPackage,
                            quality = DownloadQuality.Fixed(maxBitrateBps = 2_000_000L),
                            physicalBytes = parts.sumOf { part -> part.lengthBytes },
                            expectedSourceBytes = null,
                        ),
                    artifactStore =
                        ResolverArtifactStore(
                            parts = parts,
                            partContents =
                                mapOf(
                                    DownloadHlsPartNames.MASTER to masterBytes,
                                    DownloadHlsPartNames.MEDIA to mediaBytes,
                                ),
                        ),
                )

            val result = fixture.resolver.acquire(account, reference)

            assertEquals(
                OfflineArtifactResolutionFailure.CorruptArtifact,
                assertIs<OfflineArtifactResolution.Unavailable>(result).failure,
            )
            assertTrue(
                !fixture.registry.isLeased(
                    com.jellyscope.core.playback
                        .OfflineArtifactLeaseIdentity(reference),
                ),
            )
        }

    @Test
    fun incompleteLocalHlsPackageDoesNotAcquireLease() =
        runTest {
            val parts =
                listOf(
                    DownloadArtifactPartInspection(DownloadHlsPartNames.MASTER, 48L),
                    DownloadArtifactPartInspection(DownloadHlsPartNames.MEDIA, 96L),
                    // The normalized package requires contiguous zero-based segment parts.
                    DownloadArtifactPartInspection(DownloadHlsPartNames.segment(1), 1_024L),
                )
            val fixture =
                fixture(
                    record =
                        fixtureRecord(
                            artifactKind = DownloadArtifactKind.LocalHlsPackage,
                            quality = DownloadQuality.Fixed(maxBitrateBps = 2_000_000L),
                            physicalBytes = parts.sumOf { part -> part.lengthBytes },
                            expectedSourceBytes = null,
                        ),
                    artifactStore = ResolverArtifactStore(parts),
                )

            val result = fixture.resolver.acquire(account, reference)

            assertEquals(
                OfflineArtifactResolutionFailure.CorruptArtifact,
                assertIs<OfflineArtifactResolution.Unavailable>(result).failure,
            )
            assertTrue(
                !fixture.registry.isLeased(
                    com.jellyscope.core.playback
                        .OfflineArtifactLeaseIdentity(reference),
                ),
            )
        }

    private fun fixture(
        record: DownloadRecord = fixtureRecord(),
        artifactStore: ResolverArtifactStore = ResolverArtifactStore(),
    ): Fixture {
        val recordStore = FakeRecordStore(mutableListOf(record))
        val registry = OfflineArtifactLeaseRegistry()
        val resolver =
            DefaultOfflineArtifactResolver(
                recordStore = recordStore,
                removalStore = FakeRemovalStore(FakeQueueRepository(mutableListOf())),
                artifactStore = artifactStore,
                leaseRegistry = registry,
            )
        return Fixture(resolver, registry, artifactStore)
    }

    private fun fixtureRecord(
        generation: Long = 4L,
        physicalBytes: Long = 100L,
        expectedSourceBytes: Long? = 100L,
        artifactKind: DownloadArtifactKind = DownloadArtifactKind.OriginalFile,
        quality: DownloadQuality = DownloadQuality.Original,
        subtitleSelection: DownloadSubtitleSelection = DownloadSubtitleSelection.Off,
    ): DownloadRecord {
        val request =
            DownloadRequest(
                downloadId = reference.downloadId,
                businessKey = DownloadBusinessKey(account, itemId = "item-1", mediaSourceId = "source-1"),
                quality = quality,
                artifactKind = artifactKind,
                selectedAudioStreamIndex = null,
                subtitleSelection = subtitleSelection,
                admissionEstimateBytes = 100L,
                initialReservationBytes = physicalBytes,
                expectedSourceBytes = expectedSourceBytes,
                artifactKey = DownloadArtifactKey("artifact_a"),
                snapshot =
                    OfflineMediaSnapshot(
                        title = "Title",
                        itemKind = MediaKind.Movie,
                        backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                    ),
                createdAtEpochMs = 1L,
            )
        return DownloadRecord(
            request = request,
            fifoSequence = 1L,
            state = DownloadState.Completed,
            reservationBytes = physicalBytes,
            physicalBytes = physicalBytes,
            checkpointBytes = physicalBytes,
            attemptGeneration = generation,
            updatedAtEpochMs = 2L,
        )
    }

    private data class Fixture(
        val resolver: DefaultOfflineArtifactResolver,
        val registry: OfflineArtifactLeaseRegistry,
        val artifactStore: ResolverArtifactStore,
    )
}

private val MAIN = DOWNLOAD_ORIGINAL_PART_KEY
private val SIDECAR = DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY

private class ResolverArtifactStore(
    private val parts: List<DownloadArtifactPartInspection> = listOf(DownloadArtifactPartInspection(MAIN, 100L)),
    private val partContents: Map<DownloadArtifactPartKey, ByteArray> = emptyMap(),
) : DownloadArtifactStore {
    var inspectCalls: Int = 0
    var pathCalls: Int = 0

    override suspend fun openStagingWriter(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        mode: DownloadArtifactWriteMode,
    ): DownloadArtifactWriter = error("unused")

    override suspend fun inspect(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): DownloadArtifactInspection? {
        inspectCalls += 1
        return if (area == DownloadArtifactArea.Completed) {
            DownloadArtifactInspection(artifactKey, area, parts)
        } else {
            null
        }
    }

    override suspend fun completedPartPath(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
    ): String? {
        pathCalls += 1
        return parts
            .firstOrNull { part -> part.partKey == partKey }
            ?.let { "/private/downloads/${artifactKey.value}/${partKey.value}" }
    }

    override suspend fun readPart(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
        partKey: DownloadArtifactPartKey,
        maxBytes: Int,
    ): ByteArray? =
        partContents[partKey]
            ?.takeIf { bytes -> bytes.size <= maxBytes }
            ?.copyOf()

    override suspend fun validateStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean = false

    override suspend fun normalizeStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean = true

    override suspend fun promote(artifactKey: DownloadArtifactKey): DownloadArtifactInspection = error("unused")

    override suspend fun delete(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ) = Unit

    override suspend fun enumerate(area: DownloadArtifactArea): List<DownloadArtifactInspection> = emptyList()

    override suspend fun capacity(): DownloadArtifactCapacity = DownloadArtifactCapacity(Long.MAX_VALUE, Long.MAX_VALUE)
}
