// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.DiagnosticBreadcrumbRecord
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStorageState
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStoredRecord
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PreviousRunFailureMarker
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.platform.NativeDiagnosticLogSource
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerBackendPolicy
import com.jellyscope.core.domain.playback.androidPlayerBackendPolicy
import com.jellyscope.core.domain.playback.applePlayerBackendPolicy
import com.jellyscope.core.domain.usecase.GetVerboseLogcatStateUseCase
import com.jellyscope.core.playback.DiagnosticsSourceDescriptor
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.util.LogBufferStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientLogsActionsTest {
    @Test
    fun verboseLogcatUseCaseAndActionExposeThePreference() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = false)
            val getState = GetVerboseLogcatStateUseCase(preferenceStore)

            assertFalse(getState().value)

            SetVerboseLogcatEnabledAction(preferenceStore)(true)

            assertTrue(getState().value)
        }

    @Test
    fun disablingCollectionClearsTheMemoryBuffer() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val buffer = LogBufferStore(preferenceStore)
            buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved")

            SetLogCollectionEnabledAction(preferenceStore, buffer)(false)

            assertEquals(false, preferenceStore.enabled.value)
            assertEquals(0, buffer.size.value.entryCount)
        }

    @Test
    fun failedDurableDisableLeavesPreferenceEvidenceAndPersistentRowsUntouched() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            preferenceStore.failEnabledWrite = true
            val persistence = RecordingDiagnosticBreadcrumbStore()
            val buffer = LogBufferStore(preferenceStore, breadcrumbStore = persistence)
            buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved")
            buffer.snapshot()

            assertFailsWith<IllegalStateException> {
                SetLogCollectionEnabledAction(preferenceStore, buffer)(false)
            }

            assertTrue(preferenceStore.enabled.value)
            assertTrue(buffer.snapshot().text.contains("event=resolved"))
            assertEquals(0, persistence.purgeCalls)
        }

    @Test
    fun reenableRetriesAFailedDisabledPurgeBeforePublishingTrue() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = false)
            val persistence = RecordingDiagnosticBreadcrumbStore(failPurge = true)
            val buffer = LogBufferStore(preferenceStore, breadcrumbStore = persistence)
            val action = SetLogCollectionEnabledAction(preferenceStore, buffer)

            assertFailsWith<IllegalStateException> { action(true) }
            assertFalse(preferenceStore.enabled.value)

            persistence.failPurge = false
            action(true)

            assertTrue(preferenceStore.enabled.value)
            assertTrue(persistence.purgeCalls >= 2)
            assertTrue(buffer.snapshot().text.isEmpty())
        }

    @Test
    fun disablingCollectionClearsRetainedNativeLogs() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val buffer = LogBufferStore(preferenceStore)
            var clearCalls = 0
            val source =
                object : NativeDiagnosticLogSource {
                    override suspend fun clear() {
                        clearCalls += 1
                    }
                }
            val action = SetLogCollectionEnabledAction(preferenceStore, buffer, source)

            action(true)
            assertEquals(0, clearCalls)

            action(false)
            assertEquals(1, clearCalls)
        }

    @Test
    fun failedBreadcrumbPurgeStillAttemptsNativeCleanupAndSurfacesPurgeError() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val persistence = RecordingDiagnosticBreadcrumbStore(failPurge = true)
            val buffer = LogBufferStore(preferenceStore, breadcrumbStore = persistence)
            val markerStore = RecordingPreviousRunFailureStore()
            var clearCalls = 0
            val source =
                object : NativeDiagnosticLogSource {
                    override suspend fun clear() {
                        clearCalls += 1
                    }
                }

            val failure =
                assertFailsWith<IllegalStateException> {
                    SetLogCollectionEnabledAction(
                        preferenceStore = preferenceStore,
                        logBufferStore = buffer,
                        nativeDiagnosticLogSource = source,
                        previousRunFailureStore = markerStore,
                    )(false)
                }

            assertEquals("simulated diagnostic purge failure", failure.message)
            assertFalse(preferenceStore.enabled.value)
            assertEquals(1, clearCalls)
            assertEquals(1, markerStore.clearCalls)
            assertTrue(persistence.purgeCalls >= 1)
        }

    @Test
    fun successfulDisableClearsThePreviousRunFailureMarker() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val markerStore = RecordingPreviousRunFailureStore()
            val action =
                SetLogCollectionEnabledAction(
                    preferenceStore = preferenceStore,
                    logBufferStore = LogBufferStore(preferenceStore),
                    previousRunFailureStore = markerStore,
                )

            action(false)

            assertEquals(1, markerStore.clearCalls)
        }

    @Test
    fun successfulUploadClearsOnlyTheUploadedGeneration() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val buffer = LogBufferStore(preferenceStore)
            buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved")
            val repository =
                ClientLogRepository {
                    buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=fallback")
                    SendClientLogsResult.Success("client.log")
                }

            val result = sendClientLogsAction(buffer, repository)()

            assertEquals(SendClientLogsResult.Success("client.log"), result)
            assertEquals(1, repository.uploads.size)
            assertEquals(1, buffer.size.value.entryCount)
            assertTrue(buffer.snapshot().text.contains("event=fallback"))
        }

    @Test
    fun disallowedUploadPreservesTheSnapshotForRetry() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val buffer = LogBufferStore(preferenceStore)
            buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved")

            val result =
                sendClientLogsAction(
                    buffer,
                    ClientLogRepository { SendClientLogsResult.UploadDisallowed },
                )()

            assertEquals(SendClientLogsResult.UploadDisallowed, result)
            assertEquals(1, buffer.size.value.entryCount)
        }

    @Test
    fun snapshotOnlyUploadRunsWithCollectionDisabledAndAnEmptyBuffer() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }

            val result = sendClientLogsAction(buffer, repository)()

            assertEquals(SendClientLogsResult.Success("client.log"), result)
            assertEquals(1, repository.uploads.size)
            assertTrue(repository.uploads.single().contains("capabilities.backend=avplayer"))
            assertTrue(repository.uploads.single().contains("playbackFailure.status=no recent playback failure"))
        }

    @Test
    fun disabledCollectionRetriesNativeCleanupAndNeverSnapshotsNativeEvidence() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = false)
            val persistence = RecordingDiagnosticBreadcrumbStore()
            persistence.appendAndTrim(
                records =
                    listOf(
                        DiagnosticBreadcrumbRecord(
                            recordId = "cold-start-row",
                            line = "WARN PlaybackInfoPlanner stage=planner event=old",
                            byteCount = "WARN PlaybackInfoPlanner stage=planner event=old".encodeToByteArray().size,
                        ),
                    ),
                ingressOverflowed = false,
                targetTruncationRevision = null,
            )
            val buffer = LogBufferStore(preferenceStore, breadcrumbStore = persistence)
            assertTrue(buffer.snapshot().text.isEmpty())
            assertTrue(persistence.purgeCalls >= 1)
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val source =
                object : NativeDiagnosticLogSource {
                    var clearCalls = 0

                    override suspend fun clear() {
                        clearCalls += 1
                    }
                }

            SetLogCollectionEnabledAction(
                preferenceStore = preferenceStore,
                logBufferStore = buffer,
                nativeDiagnosticLogSource = source,
            )(false)
            val result = sendClientLogsAction(buffer, repository)()

            assertEquals(SendClientLogsResult.Success("client.log"), result)
            assertEquals(1, source.clearCalls)
            assertFalse(repository.uploads.single().contains("native log:"))
        }

    @Test
    fun snapshotOnlyUploadDoesNotClearALineCapturedDuringTheUpload() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = true))
            val repository =
                ClientLogRepository {
                    buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=fallback")
                    SendClientLogsResult.Success("client.log")
                }

            sendClientLogsAction(buffer, repository)()

            buffer.snapshot()
            assertEquals(1, buffer.size.value.entryCount)
            assertTrue(buffer.snapshot().text.contains("event=fallback"))
        }

    @Test
    fun uploadStillRunsWhenTheCapabilityProbeIsUnavailable() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val unavailableProvider =
                object : DeviceProfileProvider {
                    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = error("probe unavailable")
                }

            val result = sendClientLogsAction(buffer, repository, unavailableProvider)()

            assertEquals(SendClientLogsResult.Success("client.log"), result)
            assertTrue(repository.uploads.single().contains("capabilities.status=capabilities unavailable"))
        }

    @Test
    fun explicitLibVlcPreferenceProbesAndReportsLibVlc() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val provider = RecordingAndroidDeviceProfileProvider()

            sendClientLogsAction(buffer, repository, provider)(PlayerBackend.LibVlc)

            assertEquals(listOf(PlayerBackend.LibVlc), provider.capabilityCalls)
            assertTrue(repository.uploads.single().contains("capabilities.backend=libvlc"))
        }

    @Test
    fun staleFailureFromAnotherBackendIsNotBlendedIntoTheReport() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val provider = RecordingAndroidDeviceProfileProvider()
            val context = PlaybackDiagnosticsContext()
            context.recordFailure(
                backend = PlayerBackend.ExoPlayer,
                capabilities = deviceCapabilities("h264"),
                source = diagnosticsSource("vp9"),
            )

            sendClientLogsAction(buffer, repository, provider, context)(PlayerBackend.LibVlc)

            val upload = repository.uploads.single()
            assertEquals(listOf(PlayerBackend.LibVlc), provider.capabilityCalls)
            assertTrue(upload.contains("capabilities.backend=libvlc"))
            assertTrue(upload.contains("playbackFailure.status=no recent playback failure"))
            assertFalse(upload.contains("playbackFailure.videoCodec=vp9"))
        }

    @Test
    fun matchingFailureSnapshotKeepsItsCapabilitiesAndSource() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val provider = RecordingAndroidDeviceProfileProvider()
            val context = PlaybackDiagnosticsContext()
            context.recordFailure(
                backend = PlayerBackend.LibVlc,
                capabilities = deviceCapabilities("hevc"),
                source = diagnosticsSource("hevc"),
            )

            sendClientLogsAction(buffer, repository, provider, context)(PlayerBackend.LibVlc)

            val upload = repository.uploads.single()
            assertTrue(provider.capabilityCalls.isEmpty())
            assertTrue(upload.contains("capabilities.backend=libvlc"))
            assertTrue(upload.contains("capabilities.videoCodecs=hevc"))
            assertTrue(upload.contains("playbackFailure.videoCodec=hevc"))
        }

    @Test
    fun androidAutoNormalizesToExoPlayerInsteadOfUsingALibVlcFailure() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val provider = RecordingAndroidDeviceProfileProvider()
            val context = PlaybackDiagnosticsContext()
            context.recordFailure(
                backend = PlayerBackend.LibVlc,
                capabilities = deviceCapabilities("hevc"),
                source = diagnosticsSource("hevc"),
            )

            sendClientLogsAction(buffer, repository, provider, context)(PlayerBackend.Auto)

            assertEquals(listOf(PlayerBackend.ExoPlayer), provider.capabilityCalls)
            assertTrue(repository.uploads.single().contains("capabilities.backend=exoplayer"))
        }

    @Test
    fun appleAutoUsesTheMatchingRecentConcreteBackend() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val provider = RecordingAppleDeviceProfileProvider()
            val context = PlaybackDiagnosticsContext()
            context.recordFailure(
                backend = PlayerBackend.VlcKit,
                capabilities = deviceCapabilities("hevc"),
                source = diagnosticsSource("hevc"),
            )

            sendClientLogsAction(buffer, repository, provider, context)(PlayerBackend.Auto)

            assertTrue(provider.capabilityCalls.isEmpty())
            assertTrue(repository.uploads.single().contains("capabilities.backend=vlckit"))
            assertTrue(repository.uploads.single().contains("playbackFailure.videoCodec=hevc"))
        }

    @Test
    fun foreignAndroidPreferenceNormalizesToExoPlayer() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val provider = RecordingAndroidDeviceProfileProvider()

            sendClientLogsAction(buffer, repository, provider)(PlayerBackend.AVPlayer)

            assertEquals(listOf(PlayerBackend.ExoPlayer), provider.capabilityCalls)
            assertTrue(repository.uploads.single().contains("capabilities.backend=exoplayer"))
        }

    @Test
    fun capabilityProbeCancellationIsRethrown() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = false))
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val provider =
                object : DeviceProfileProvider {
                    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = throw CancellationException("cancelled")
                }

            assertFailsWith<CancellationException> {
                sendClientLogsAction(buffer, repository, provider)(PlayerBackend.AVPlayer)
            }
            assertTrue(repository.uploads.isEmpty())
        }

    @Test
    fun truncationMarkerIsAppendedAfterTheSnapshotHeader() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = true))
            repeat(4_001) { index ->
                buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved nativeCode=$index")
            }
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }

            sendClientLogsAction(buffer, repository)()

            val upload = repository.uploads.single()
            assertTrue(
                upload.indexOf("=== JellyScope Diagnostics Snapshot ===") < upload.indexOf("[earlier diagnostic lines were dropped]"),
            )
        }

    @Test
    fun uploadsContainOnlySnapshotAndAdmittedHistory() =
        runTest {
            val buffer = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = true))
            buffer.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved")
            listOf(
                "stage=planner event=failed title=PrivateMovie",
                "stage=planner event=failed username=private-user",
                "stage=planner event=failed sourceUrl=https://server.example/video",
                "stage=planner event=failed authorization=BearerSecret",
                "stage=planner event=failed itemId=123e4567-e89b-12d3-a456-426614174000",
                "stage=planner event=failed localPath=/Users/private/movie.mkv",
                "stage=planner event=failed message=private-message",
                "stage=planner event=failed stack=private-stack",
            ).forEach { unsafeRecord ->
                buffer.append("PlaybackInfoPlanner", Severity.Warn, unsafeRecord)
            }
            val repository = ClientLogRepository { SendClientLogsResult.Success("client.log") }
            val provider = RecordingAndroidDeviceProfileProvider()
            sendClientLogsAction(buffer, repository, provider)()

            val upload = repository.uploads.single()
            assertTrue("=== JellyScope Diagnostics Snapshot ===" in upload)
            assertTrue("stage=planner event=resolved" in upload)
            assertFalse("native log:" in upload)
            listOf(
                "PrivateMovie",
                "private-user",
                "server.example",
                "BearerSecret",
                "123e4567-e89b-12d3-a456-426614174000",
                "/Users/private/movie.mkv",
                "private-message",
                "private-stack",
            ).forEach { secret ->
                assertFalse(secret in upload)
            }
        }
}

private fun sendClientLogsAction(
    buffer: LogBufferStore,
    repository: MediaRepository,
    provider: DeviceProfileProvider = TestDeviceProfileProvider,
    playbackDiagnosticsContext: PlaybackDiagnosticsContext = PlaybackDiagnosticsContext(),
): SendClientLogsAction =
    SendClientLogsAction(
        logBufferStore = buffer,
        mediaRepository = repository,
        diagnosticsEnvironment = TestDiagnosticsEnvironment,
        deviceProfileProvider = provider,
        playbackDiagnosticsContext = playbackDiagnosticsContext,
    )

private class RecordingAndroidDeviceProfileProvider : DeviceProfileProvider {
    val capabilityCalls = mutableListOf<PlayerBackend>()
    override val backendPolicy: PlayerBackendPolicy = androidPlayerBackendPolicy()

    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities {
        capabilityCalls += backend
        return deviceCapabilities(if (backend == PlayerBackend.LibVlc) "hevc" else "h264")
    }
}

private class RecordingAppleDeviceProfileProvider : DeviceProfileProvider {
    val capabilityCalls = mutableListOf<PlayerBackend>()
    override val backendPolicy: PlayerBackendPolicy = applePlayerBackendPolicy()

    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities {
        capabilityCalls += backend
        return deviceCapabilities("h264")
    }
}

private fun deviceCapabilities(codec: String): DeviceDecodingCapabilities =
    DeviceDecodingCapabilities(
        videoCodecs = listOf(codec),
        audioCodecs = listOf("aac"),
        supportsDolbyVision = false,
    )

private fun diagnosticsSource(codec: String): DiagnosticsSourceDescriptor =
    DiagnosticsSourceDescriptor(
        videoCodec = codec,
        width = 1920,
        height = 1080,
        frameRate = 24.0,
        bitRate = 8_000_000,
        bitDepth = 8,
        videoRangeType = "sdr",
        container = "mkv",
        audioCodec = "aac",
        channelLayout = "stereo",
    )

private object TestDiagnosticsEnvironment : DiagnosticsEnvironment {
    override val platform = "test"
    override val osVersion = "1"
    override val appVersion = "dev"
    override val deviceModel = "test-device"
}

private object TestDeviceProfileProvider : DeviceProfileProvider {
    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
        DeviceDecodingCapabilities(
            videoCodecs = listOf("h264"),
            audioCodecs = listOf("aac"),
            supportsDolbyVision = false,
        )
}

private class FakeLogCollectionPreferenceStore(
    enabled: Boolean,
) : LogCollectionPreferenceStore {
    var failEnabledWrite: Boolean = false
    override val enabled = MutableStateFlow(enabled)
    override val verboseLogcatEnabled = MutableStateFlow(false)
    override val playbackInfoAtStartEnabled = MutableStateFlow(false)

    override suspend fun setEnabled(enabled: Boolean) {
        check(!failEnabledWrite) { "simulated durable preference failure" }
        this.enabled.value = enabled
    }

    override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
        verboseLogcatEnabled.value = enabled
    }

    override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        playbackInfoAtStartEnabled.value = enabled
    }
}

private class RecordingDiagnosticBreadcrumbStore(
    var failPurge: Boolean = false,
) : DiagnosticBreadcrumbStore {
    private val records = mutableListOf<DiagnosticBreadcrumbStoredRecord>()
    private var nextSequence = 1L
    var purgeCalls: Int = 0
        private set

    override suspend fun hydrate(): DiagnosticBreadcrumbStorageState = state()

    override suspend fun appendAndTrim(
        records: List<DiagnosticBreadcrumbRecord>,
        ingressOverflowed: Boolean,
        targetTruncationRevision: Long?,
    ): DiagnosticBreadcrumbStorageState {
        records.forEach { record ->
            if (this.records.none { existing -> existing.recordId == record.recordId }) {
                this.records +=
                    DiagnosticBreadcrumbStoredRecord(
                        recordId = record.recordId,
                        line = record.line,
                        byteCount = record.byteCount,
                        sequence = nextSequence++,
                    )
            }
        }
        return state()
    }

    override suspend fun clearThrough(
        maxSequence: Long,
        representedTruncationRevision: Long,
        fallbackRecordIds: Set<String>,
    ) {
        records.removeAll { record -> record.sequence <= maxSequence }
        records.removeAll { record -> record.recordId in fallbackRecordIds }
    }

    override suspend fun clear() {
        records.clear()
    }

    override suspend fun purge() {
        purgeCalls += 1
        check(!failPurge) { "simulated diagnostic purge failure" }
        records.clear()
    }

    private fun state(): DiagnosticBreadcrumbStorageState =
        DiagnosticBreadcrumbStorageState(
            records = records.toList(),
        )
}

private class RecordingPreviousRunFailureStore : PreviousRunFailureStore {
    var clearCalls = 0

    override fun write(
        throwable: Throwable,
        platform: PreviousRunFailurePlatform,
    ) = Unit

    override fun consume(): PreviousRunFailureMarker? = null

    override fun clear() {
        clearCalls += 1
    }
}

private class ClientLogRepository(
    private val result: suspend (String) -> SendClientLogsResult,
) : MediaRepository {
    val uploads = mutableListOf<String>()

    override suspend fun uploadClientLogs(content: String): SendClientLogsResult {
        uploads += content
        return result(content)
    }

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(
        seriesId: String?,
        includeResumable: Boolean,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> = Result.failure(UnsupportedOperationException())

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getFavorites(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun search(query: FindQuery): Result<FindResults> = Result.success(FindResults())

    override suspend fun findPersons(term: String): Result<List<Person>> = Result.success(emptyList())

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> = Result.failure(UnsupportedOperationException())

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> = Result.failure(UnsupportedOperationException())
}
