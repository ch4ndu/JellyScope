// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_PART_KEY
import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactCapacity
import com.jellyscope.core.data.local.DownloadArtifactCheckpoint
import com.jellyscope.core.data.local.DownloadArtifactInspection
import com.jellyscope.core.data.local.DownloadArtifactPartInspection
import com.jellyscope.core.data.local.DownloadArtifactPartKey
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadArtifactWriteMode
import com.jellyscope.core.data.local.DownloadArtifactWriter
import com.jellyscope.core.data.local.DownloadAttemptInvalidationResult
import com.jellyscope.core.data.local.DownloadSettingsStore
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.download.DownloadHlsPackage
import com.jellyscope.core.download.DownloadHlsParseResult
import com.jellyscope.core.download.DownloadHlsPartNames
import com.jellyscope.core.playback.OfflineArtifactLeaseRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalHlsFinalizingRecoveryTest {
    private val account = AccountIdentity("server", "user")

    @Test
    fun validCompletedLocalHlsFinalizingSettlesCompleted() =
        runTest {
            val fixture = fixture(DownloadArtifactArea.Completed)
            val result = fixture.repository.reconcileFinalizingForBoundary(account)

            assertIs<DownloadAttemptInvalidationResult.Finalizing>(result)
            assertEquals(
                DownloadState.Completed,
                fixture.records.records
                    .single()
                    .state,
            )
            assertEquals(0, fixture.artifacts.promoteCalls)
        }

    @Test
    fun validCompletedLocalHlsPassesOfflinePlaybackPreflight() =
        runTest {
            val fixture = fixture(DownloadArtifactArea.Completed, DownloadState.Completed)

            assertNotNull(
                GetOfflinePlaybackPlanUseCase(fixture.repository)(
                    accountIdentity = account,
                    downloadId = DownloadId("download_hls"),
                ),
            )
        }

    @Test
    fun malformedCompletedLocalHlsFailsOfflinePlaybackPreflight() =
        runTest {
            val fixture = fixture(DownloadArtifactArea.Completed, DownloadState.Completed)
            fixture.artifacts.corruptCompletedPart(DownloadHlsPartNames.MEDIA)

            assertNull(
                GetOfflinePlaybackPlanUseCase(fixture.repository)(
                    accountIdentity = account,
                    downloadId = DownloadId("download_hls"),
                ),
            )
        }

    @Test
    fun validStagingLocalHlsFinalizingPromotesBeforeSettlingCompleted() =
        runTest {
            val fixture = fixture(DownloadArtifactArea.Staging)
            val result = fixture.repository.reconcileFinalizingForBoundary(account)

            assertIs<DownloadAttemptInvalidationResult.Finalizing>(result)
            assertEquals(
                DownloadState.Completed,
                fixture.records.records
                    .single()
                    .state,
            )
            assertEquals(1, fixture.artifacts.promoteCalls)
        }

    @Test
    fun validOriginalStagingFinalizingPromotesBeforeSettlingCompleted() =
        runTest {
            val bytes = ByteArray(100) { 3 }
            val record =
                DownloadRecord(
                    request =
                        DownloadRequest(
                            downloadId = DownloadId("download_original"),
                            businessKey = DownloadBusinessKey(account, "item_original", "source_original"),
                            quality = DownloadQuality.Original,
                            artifactKind = DownloadArtifactKind.OriginalFile,
                            selectedAudioStreamIndex = null,
                            subtitleSelection = DownloadSubtitleSelection.Off,
                            admissionEstimateBytes = bytes.size.toLong(),
                            initialReservationBytes = bytes.size.toLong() + 100L,
                            expectedSourceBytes = bytes.size.toLong(),
                            artifactKey = DownloadArtifactKey("artifact_original"),
                            snapshot =
                                OfflineMediaSnapshot(
                                    title = "Original title",
                                    itemKind = MediaKind.Movie,
                                    backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                                ),
                            createdAtEpochMs = 1L,
                        ),
                    fifoSequence = 1L,
                    state = DownloadState.Finalizing,
                    reservationBytes = bytes.size.toLong() + 100L,
                    physicalBytes = bytes.size.toLong(),
                    checkpointBytes = bytes.size.toLong(),
                    attemptGeneration = 3L,
                    updatedAtEpochMs = 2L,
                )
            val records = FakeRecordStore(mutableListOf(record))
            val artifacts =
                HlsRecoveryArtifactStore(
                    artifactKey = record.request.artifactKey,
                    initialParts = mapOf(DOWNLOAD_ORIGINAL_PART_KEY to bytes),
                    initialArea = DownloadArtifactArea.Staging,
                )
            val repository =
                DefaultDownloadRepository(
                    settingsStore = HlsRecoverySettingsStore(),
                    recordStore = records,
                    removalStore = FakeRemovalStore(FakeQueueRepository(mutableListOf())),
                    artifactStore = artifacts,
                    artifactLeaseRegistry = OfflineArtifactLeaseRegistry(),
                    removalMutex = DownloadRemovalMutex(),
                    nowEpochMilliseconds = { 10L },
                )

            val result = repository.reconcileFinalizingForBoundary(account)

            assertIs<DownloadAttemptInvalidationResult.Finalizing>(result)
            assertEquals(DownloadState.Completed, records.records.single().state)
            assertEquals(1, artifacts.promoteCalls)
        }

    private fun fixture(
        initialArea: DownloadArtifactArea,
        state: DownloadState = DownloadState.Finalizing,
    ): Fixture {
        val packageFixture = packageFixture()
        val reservationBytes =
            if (state == DownloadState.Completed) {
                packageFixture.totalBytes
            } else {
                packageFixture.totalBytes + 100L
            }
        val record =
            DownloadRecord(
                request =
                    DownloadRequest(
                        downloadId = DownloadId("download_hls"),
                        businessKey = DownloadBusinessKey(account, "item_hls", "source_hls"),
                        quality = DownloadQuality.Fixed(maxBitrateBps = 2_000_000L),
                        artifactKind = DownloadArtifactKind.LocalHlsPackage,
                        selectedAudioStreamIndex = null,
                        subtitleSelection = DownloadSubtitleSelection.Off,
                        admissionEstimateBytes = packageFixture.totalBytes,
                        initialReservationBytes = packageFixture.totalBytes + 100L,
                        expectedSourceBytes = null,
                        artifactKey = DownloadArtifactKey("artifact_hls"),
                        snapshot =
                            OfflineMediaSnapshot(
                                title = "HLS title",
                                itemKind = MediaKind.Movie,
                                backendSource = BackendSourceDescriptor("hls", "h264", "aac", false),
                            ),
                        createdAtEpochMs = 1L,
                    ),
                fifoSequence = 1L,
                state = state,
                reservationBytes = reservationBytes,
                physicalBytes = packageFixture.totalBytes,
                checkpointBytes = packageFixture.totalBytes,
                attemptGeneration = 9L,
                updatedAtEpochMs = 2L,
            )
        val records = FakeRecordStore(mutableListOf(record))
        val artifacts = HlsRecoveryArtifactStore(record.request.artifactKey, packageFixture.parts, initialArea)
        val repository =
            DefaultDownloadRepository(
                settingsStore = HlsRecoverySettingsStore(),
                recordStore = records,
                removalStore = FakeRemovalStore(FakeQueueRepository(mutableListOf())),
                artifactStore = artifacts,
                artifactLeaseRegistry = OfflineArtifactLeaseRegistry(),
                removalMutex = DownloadRemovalMutex(),
                nowEpochMilliseconds = { 10L },
            )
        return Fixture(repository, records, artifacts)
    }

    private fun packageFixture(): PackageFixture {
        val packageValue =
            assertIs<DownloadHlsParseResult.Package>(
                DownloadHlsPackage.parse(
                    masterText =
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=2000000
                        media.m3u8
                        """.trimIndent(),
                    mediaText =
                        """
                        #EXTM3U
                        #EXT-X-VERSION:3
                        #EXT-X-TARGETDURATION:10
                        #EXT-X-MEDIA-SEQUENCE:0
                        #EXT-X-PLAYLIST-TYPE:VOD
                        #EXTINF:10.000,
                        segment-remote.ts
                        #EXT-X-ENDLIST
                        """.trimIndent(),
                ),
            ).value
        val master = packageValue.masterText().encodeToByteArray()
        val media = packageValue.mediaText().encodeToByteArray()
        val segment = ByteArray(2_048) { 7 }
        val checkpoint =
            packageValue
                .initialCheckpoint()
                .copy(
                    master = packageValue.initialCheckpoint().master.copy(lengthBytes = master.size.toLong(), complete = true),
                    media = packageValue.initialCheckpoint().media.copy(lengthBytes = media.size.toLong(), complete = true),
                    segments =
                        packageValue.initialCheckpoint().segments.map { part ->
                            part.copy(lengthBytes = segment.size.toLong(), complete = true)
                        },
                ).encode()
                .encodeToByteArray()
        val parts =
            linkedMapOf(
                DownloadHlsPartNames.MASTER to master,
                DownloadHlsPartNames.MEDIA to media,
                DownloadHlsPartNames.segment(0) to segment,
                DownloadHlsPartNames.CHECKPOINT to checkpoint,
            )
        return PackageFixture(parts, parts.values.sumOf { bytes -> bytes.size.toLong() })
    }

    private data class PackageFixture(
        val parts: Map<DownloadArtifactPartKey, ByteArray>,
        val totalBytes: Long,
    )

    private data class Fixture(
        val repository: DefaultDownloadRepository,
        val records: FakeRecordStore,
        val artifacts: HlsRecoveryArtifactStore,
    )
}

private class HlsRecoveryArtifactStore(
    private val artifactKey: DownloadArtifactKey,
    initialParts: Map<DownloadArtifactPartKey, ByteArray>,
    initialArea: DownloadArtifactArea,
) : DownloadArtifactStore {
    private var staging: Map<DownloadArtifactPartKey, ByteArray> =
        if (initialArea == DownloadArtifactArea.Staging) initialParts else emptyMap()
    private var completed: Map<DownloadArtifactPartKey, ByteArray> =
        if (initialArea == DownloadArtifactArea.Completed) initialParts else emptyMap()
    var promoteCalls: Int = 0

    fun corruptCompletedPart(partKey: DownloadArtifactPartKey) {
        val bytes = completed[partKey]?.copyOf() ?: error("Missing completed test part.")
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        completed = completed + (partKey to bytes)
    }

    override suspend fun openStagingWriter(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        mode: DownloadArtifactWriteMode,
    ): DownloadArtifactWriter = error("unused")

    override suspend fun inspect(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): DownloadArtifactInspection? {
        val parts = if (area == DownloadArtifactArea.Staging) staging else completed
        return parts
            .takeIf { values -> values.isNotEmpty() }
            ?.let { values ->
                DownloadArtifactInspection(
                    artifactKey = artifactKey,
                    area = area,
                    parts =
                        values.map { (partKey, bytes) ->
                            DownloadArtifactPartInspection(partKey, bytes.size.toLong())
                        },
                )
            }
    }

    override suspend fun completedPartPath(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
    ): String? = null

    override suspend fun readPart(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
        partKey: DownloadArtifactPartKey,
        maxBytes: Int,
    ): ByteArray? =
        (if (area == DownloadArtifactArea.Staging) staging else completed)[partKey]
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

    override suspend fun promote(artifactKey: DownloadArtifactKey): DownloadArtifactInspection {
        promoteCalls += 1
        completed = staging
        staging = emptyMap()
        return requireNotNull(inspect(artifactKey, DownloadArtifactArea.Completed))
    }

    override suspend fun delete(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ) = Unit

    override suspend fun enumerate(area: DownloadArtifactArea): List<DownloadArtifactInspection> = emptyList()

    override suspend fun capacity(): DownloadArtifactCapacity = DownloadArtifactCapacity(Long.MAX_VALUE, Long.MAX_VALUE)
}

private class HlsRecoverySettingsStore : DownloadSettingsStore {
    private var settings = DownloadSettings(quotaBytes = null, nextFifoSequence = 1L, membershipRevision = 0L)

    override suspend fun get(): DownloadSettings = settings

    override suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings {
        settings = settings.copy(quotaBytes = quotaBytes)
        return settings
    }
}
