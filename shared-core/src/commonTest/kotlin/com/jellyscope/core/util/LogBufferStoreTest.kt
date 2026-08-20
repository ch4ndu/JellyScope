// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.DiagnosticBreadcrumbRecord
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStorageState
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStoredRecord
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.playback.NativeTrackMappingReason
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.PlaybackBackendAbi
import com.jellyscope.core.domain.playback.PlaybackBackendAvailability
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionResult
import com.jellyscope.core.domain.playback.PlaybackBackendConstructionStage
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackDiagnosticTrackKind
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackNativeCommandShape
import com.jellyscope.core.domain.playback.PlaybackNativePlayerMilestone
import com.jellyscope.core.domain.playback.PlaybackTerminalOutcome
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerOperation
import com.jellyscope.core.domain.playback.StreamMode
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogBufferStoreTest {
    @Test
    fun onlyCapturesEnabledAllowlistedStructuredDiagnostics() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore()
            val store = LogBufferStore(preferenceStore)

            store.logWriter.log(Severity.Warn, "stage=planner event=failed", "PlaybackInfoPlanner", null)
            store.append("PlaybackInfoPlanner", Severity.Warn, "free-form title=TheShow")
            store.append("HttpClient", Severity.Warn, "stage=planner event=failed")

            assertEquals(0, store.size.value.entryCount)

            preferenceStore.enabled.value = true
            store.append("PlaybackInfoPlanner", Severity.Warn, "stage=planner event=failed")
            store.append("PlaybackInfoPlanner", Severity.Warn, "stage=planner event=failed title=TheShow")

            val snapshot = store.snapshot()
            assertEquals(1, store.size.value.entryCount)
            assertTrue(snapshot.text.contains("stage=planner event=failed"))
        }

    @Test
    fun capturesVlcFamilyControllerDiagnostics() =
        runTest {
            // Both VLC controllers must remain in the allowlist so their
            // structured diagnostics reach the upload path.
            val store = enabledStore()

            store.append(
                "VlcKitPlayerController",
                Severity.Warn,
                "stage=playback-transition event=timed-out generation=4 transitionSequence=2 " +
                    "kind=Resume state=TimedOut targetPositionMs=27000 nativePositionMs=27000 " +
                    "publishedPositionMs=27000 targetArrived=true clockAdvanced=false pictureAdvances=0 " +
                    "requiredPictureAdvances=2 decodedVideo=600 displayedPictures=0 lostPictures=0 " +
                    "hasVideoOut=true videoWidth=1920 videoHeight=1080 nativePlaying=true " +
                    "nativeState=Playing nativeBuffering=false nativeBufferingProgressMilli=1000 " +
                    "playIntent=true pendingInitialSeek=false playbackEverProgressed=false",
            )
            store.append("LibVlcPlayerController", Severity.Warn, "stage=prepare event=failed")

            store.snapshot()
            assertEquals(2, store.size.value.entryCount)
        }

    @Test
    fun capturesSanitizedAndroidBackendConstructionDiagnostics() =
        runTest {
            val store = enabledStore()
            val diagnostic =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.BackendConstruction,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.Mpv,
                        requestedBackend = PlayerBackend.Mpv,
                        backendAvailability = PlaybackBackendAvailability.Bundled,
                        backendAbi = PlaybackBackendAbi.ArmeabiV7a,
                        backendConstructionStage = PlaybackBackendConstructionStage.CreateController,
                        backendConstructionResult = PlaybackBackendConstructionResult.Failed,
                        exceptionType = "UnsatisfiedLinkError",
                    ),
                )

            store.append("AndroidPlaybackModule", Severity.Info, diagnostic)

            val snapshot = store.snapshot().text
            assertEquals(1, store.size.value.entryCount)
            assertTrue(snapshot.contains("event=backend-construction"))
            assertTrue(snapshot.contains("backendConstructionStage=CreateController"))
            assertTrue(snapshot.contains("exceptionType=UnsatisfiedLinkError"))
        }

    @Test
    fun capturesSanitizedOfflineVlcKitConstructionDiagnosticsWithoutArtifactDetails() =
        runTest {
            val store = enabledStore()
            val diagnostic =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.BackendConstruction,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        backend = PlayerBackend.VlcKit,
                        requestedBackend = PlayerBackend.VlcKit,
                        backendAvailability = PlaybackBackendAvailability.Unavailable,
                        backendConstructionStage = PlaybackBackendConstructionStage.CreateController,
                        backendConstructionResult = PlaybackBackendConstructionResult.Failed,
                        exceptionType = "IllegalStateException",
                        streamMode = StreamMode.Offline,
                    ),
                )

            store.append("PlayerViewModel", Severity.Warn, diagnostic)

            val snapshot = store.snapshot().text
            assertEquals(1, store.size.value.entryCount)
            assertTrue(snapshot.contains("event=backend-construction"))
            assertTrue(snapshot.contains("backend=vlckit"))
            assertTrue(snapshot.contains("backendAvailability=Unavailable"))
            assertTrue(snapshot.contains("backendConstructionStage=CreateController"))
            assertTrue(snapshot.contains("streamMode=Offline"))
            assertFalse(snapshot.contains("download"))
            assertFalse(snapshot.contains("m3u8"))
            assertFalse(snapshot.contains("/"))
        }

    @Test
    fun capturesSanitizedAndroidMpvLifecycleDiagnostics() =
        runTest {
            val store = enabledStore()
            val diagnostic =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.NativeLifecycle,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.Mpv,
                        prepareSequence = 3L,
                        nativePlayerMilestone = PlaybackNativePlayerMilestone.LoadCommandDispatched,
                        nativeCommandShape = PlaybackNativeCommandShape.LoadFileUrlFlagsIndexOptions,
                        nativeReady = true,
                        nativeLoadOutstanding = true,
                        nativeStartObserved = false,
                        nativeFileLoaded = false,
                        nativeSurfaceAttached = true,
                        nativeSurfaceWidthPx = 1_080,
                        nativeSurfaceHeightPx = 2_400,
                        nativePlayIntent = true,
                        nativeAudioGatePending = false,
                    ),
                )

            store.append("AndroidMpvPlayerController", Severity.Info, diagnostic)

            val snapshot = store.snapshot().text
            assertEquals(1, store.size.value.entryCount)
            assertTrue(snapshot.contains("event=native-lifecycle"))
            assertTrue(snapshot.contains("nativeCommandShape=LoadFileUrlFlagsIndexOptions"))
            assertTrue(snapshot.contains("nativeSurfaceWidthPx=1080"))
            assertTrue(snapshot.contains("nativeSurfaceHeightPx=2400"))
            assertFalse(snapshot.contains("https://"))
        }

    @Test
    fun capturesBoundedAndroidMpvSeekDiagnostics() =
        runTest {
            val store = enabledStore()
            val diagnostic =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.SeekCompleted,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.Mpv,
                        prepareSequence = 5L,
                        seekOriginPositionMs = 10_000L,
                        seekTargetPositionMs = 20_000L,
                        seekObservedPositionMs = 19_900L,
                    ),
                )

            store.append("AndroidMpvPlayerController", Severity.Info, diagnostic)

            val snapshot = store.snapshot().text
            assertEquals(1, store.size.value.entryCount)
            assertTrue(snapshot.contains("event=seekcompleted"))
            assertTrue(snapshot.contains("seekOriginPositionMs=10000"))
            assertTrue(snapshot.contains("seekTargetPositionMs=20000"))
            assertTrue(snapshot.contains("seekObservedPositionMs=19900"))
            assertFalse(snapshot.contains("https://"))
        }

    @Test
    fun capturesAndroidMpvAlreadySelectedMappingDiagnostics() =
        runTest {
            val store = enabledStore()
            val diagnostic =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Mapping,
                        event = PlaybackDiagnosticEvent.Resolved,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.Mpv,
                        prepareSequence = 5L,
                        trackKind = PlaybackDiagnosticTrackKind.Audio,
                        candidateCount = 1,
                        mappingResult = NativeTrackMappingResult.Active,
                        mappingReason = NativeTrackMappingReason.AlreadySelected,
                        operation = PlayerOperation.SelectEmbeddedAudio,
                    ),
                )

            store.append("AndroidMpvPlayerController", Severity.Info, diagnostic)

            val snapshot = store.snapshot().text
            assertEquals(1, store.size.value.entryCount)
            assertTrue(snapshot.contains("stage=mapping event=resolved"))
            assertTrue(snapshot.contains("trackKind=audio"))
            assertTrue(snapshot.contains("candidateCount=1"))
            assertTrue(snapshot.contains("mappingResult=active"))
            assertTrue(snapshot.contains("mappingReason=alreadyselected"))
        }

    @Test
    fun retainsStructuredTerminalDiagnosticsAndRejectsRawNativePayloads() =
        runTest {
            val store = enabledStore()
            val terminalDiagnostic =
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.NativePlayer,
                        event = PlaybackDiagnosticEvent.TerminalError,
                        platform = PlaybackDiagnosticPlatform.Android,
                        backend = PlayerBackend.ExoPlayer,
                        exceptionType = "PlaybackException",
                        errorCategory = PlaybackError.Decoder,
                        terminalOutcome = PlaybackTerminalOutcome.Failed,
                        prepareSequence = 4L,
                        sessionSequence = 2L,
                        retryAttempted = false,
                        degradationAttempted = false,
                    ),
                )

            store.append("Media3PlayerController", Severity.Warn, terminalDiagnostic)
            store.append("LibVlcPlayerController", Severity.Warn, terminalDiagnostic)
            store.append(
                "LibVlcPlayerController",
                Severity.Warn,
                "stage=nativePlayer event=terminal-error nativeMessage=https://server.example/video?api_key=secret",
            )

            val snapshot = store.snapshot().text
            assertEquals(2, store.size.value.entryCount)
            assertTrue(snapshot.contains("event=terminal-error"))
            assertTrue(snapshot.contains("errorCategory=decoder"))
            assertFalse(snapshot.contains("server.example"))
            assertFalse(snapshot.contains("api_key"))
            assertFalse(snapshot.contains("secret"))
        }

    @Test
    fun logWriterDiscardsThrowablePayloadFromStructuredDiagnostic() =
        runTest {
            val store = enabledStore()
            val secret = "https://server.example/video?api_key=token Authorization=secret /Users/example/video.mkv"

            store.logWriter.log(
                severity = Severity.Warn,
                message = "stage=repository event=failed exceptionType=IllegalStateException",
                tag = "MediaRepository",
                throwable = IllegalStateException(secret),
            )

            val snapshot = store.snapshot().text
            assertTrue(snapshot.contains("exceptionType=IllegalStateException"))
            assertFalse(snapshot.contains("server.example"))
            assertFalse(snapshot.contains("Authorization"))
            assertFalse(snapshot.contains("token"))
            assertFalse(snapshot.contains("/Users/"))
        }

    @Test
    fun clearThroughRetainsLinesAppendedDuringUpload() =
        runTest {
            val store = enabledStore()
            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved")
            val snapshot = store.snapshot()

            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=fallback")
            store.clearThrough(snapshot.maxGeneration)

            assertEquals(1, store.size.value.entryCount)
            assertTrue(store.snapshot().text.contains("event=fallback"))
            assertFalse(store.snapshot().text.contains("event=resolved"))
        }

    @Test
    fun dropsOldestLinesAndAddsOneTruncationMarker() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val actorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    ownerScope = actorScope,
                    actorDispatcher = dispatcher,
                )

            runCurrent()
            repeat(8) { batch ->
                repeat(500) { index ->
                    val nativeCode = batch * 500 + index
                    store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved nativeCode=$nativeCode")
                }
                val barrier = async { store.snapshot() }
                runCurrent()
                barrier.await()
            }
            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved nativeCode=4000")
            val barrier = async { store.snapshot() }
            runCurrent()
            val snapshot = barrier.await()

            assertEquals(4_000, store.size.value.entryCount)
            assertEquals(1, snapshot.text.lines().count { it == "[earlier diagnostic lines were dropped]" })
            assertFalse(snapshot.text.contains("nativeCode=0"))
            assertTrue(snapshot.text.contains("nativeCode=4000"))
            actorScope.cancel()
        }

    @Test
    fun keepsTheTruncationMarkerWhenUploadClearingRetainsAnOverflowedNewGeneration() =
        runTest {
            val store = enabledStore()
            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=initial")
            val snapshot = store.snapshot()

            repeat(4_001) { index ->
                store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved nativeCode=$index")
            }
            store.snapshot()
            store.clearThrough(snapshot.maxGeneration)

            assertTrue(store.snapshot().text.startsWith("[earlier diagnostic lines were dropped]"))
        }

    @Test
    fun concurrentAppendsRemainLinearizable() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val actorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    ownerScope = actorScope,
                    actorDispatcher = dispatcher,
                )

            runCurrent()

            coroutineScope {
                repeat(8) { worker ->
                    launch {
                        repeat(200) { index ->
                            store.append(
                                "PlaybackInfoPlanner",
                                Severity.Info,
                                "stage=planner event=resolved nativeCode=${worker * 200 + index}",
                            )
                        }
                    }
                }
            }

            val barrier = async { store.snapshot() }
            runCurrent()
            val snapshot = barrier.await()
            val lines = snapshot.text.lines()
            val marker = "[earlier diagnostic lines were dropped]"
            val markerCount = lines.count { line -> line == marker }
            val records = lines.filter { line -> line.isNotEmpty() && line != marker }
            val nativeCodes =
                records.map { line ->
                    assertTrue(
                        line.startsWith("Info PlaybackInfoPlanner stage=planner event=resolved nativeCode="),
                        "unexpected retained diagnostic line: $line",
                    )
                    line.substringAfter("nativeCode=").toIntOrNull()
                        ?: error("retained diagnostic line was not a complete native-code record: $line")
                }

            assertTrue(nativeCodes.isNotEmpty())
            assertEquals(nativeCodes.size, nativeCodes.toSet().size)
            assertTrue(nativeCodes.all { code -> code in 0 until 1_600 })
            assertTrue(nativeCodes.size <= minOf(1_600, 4_000))
            if (nativeCodes.size < 1_600) {
                assertEquals(1, markerCount)
            } else {
                assertEquals(0, markerCount)
            }
            nativeCodes
                .groupBy { code -> code / 200 }
                .values
                .forEach { workerCodes ->
                    assertTrue(workerCodes.zipWithNext().all { (previous, next) -> previous < next })
                }
            actorScope.cancel()
        }

    @Test
    fun concurrentAppendSnapshotAndClearRemainBounded() =
        runTest {
            val store = enabledStore()

            coroutineScope {
                launch {
                    repeat(1_000) { index ->
                        store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=resolved nativeCode=$index")
                    }
                }
                launch {
                    repeat(1_000) { store.snapshot() }
                }
                launch {
                    repeat(100) { store.clear() }
                }
            }

            assertTrue(store.size.value.entryCount in 0..1_000)
            assertTrue(store.size.value.byteCount in 0..500_000)
        }

    @Test
    fun durableBreadcrumbsHydrateAndClearThroughOnlyTheUploadedToken() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val persistence = FakeDiagnosticBreadcrumbStore()
            val store = LogBufferStore(preferenceStore, breadcrumbStore = persistence)

            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=initial")
            val uploaded = store.snapshot()
            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=fallback")
            store.snapshot()

            store.clearThrough(uploaded.acknowledgement)

            val remaining = store.snapshot().text
            assertFalse(remaining.contains("event=initial"))
            assertTrue(remaining.contains("event=fallback"))

            val restored =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )
            assertTrue(restored.snapshot().text.contains("event=fallback"))
        }

    @Test
    fun storageFailureKeepsBoundedFallbackAndRetriesWithoutDeleting() =
        runTest {
            val persistence = FakeDiagnosticBreadcrumbStore(failWrites = true)
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )

            store.append("PlaybackInfoPlanner", Severity.Warn, "stage=planner event=failed")
            assertTrue(store.snapshot().text.contains("event=failed"))
            assertEquals(0, persistence.records.size)

            persistence.failWrites = false
            assertTrue(store.snapshot().text.contains("event=failed"))
            assertEquals(1, persistence.records.size)
            assertEquals(0, persistence.purgeCalls)
        }

    @Test
    fun fallbackOverflowPersistsItsTruncationMarkerAfterStorageRecovers() =
        runTest {
            val persistence = FakeDiagnosticBreadcrumbStore(failWrites = true)
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )

            repeat(4_001) { index ->
                store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=overflow nativeCode=$index")
            }
            assertTrue(store.snapshot().text.startsWith("[earlier diagnostic lines were dropped]"))
            assertTrue(persistence.records.isEmpty())

            persistence.failWrites = false
            val recovered = store.snapshot()
            assertTrue(recovered.text.startsWith("[earlier diagnostic lines were dropped]"))
            assertTrue(persistence.truncationRevisionValue > 0L)

            val restored =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )
            assertTrue(restored.snapshot().text.startsWith("[earlier diagnostic lines were dropped]"))
        }

    @Test
    fun multipleFallbackTrimRevisionsReconcileAndClearWithOneAcknowledgement() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val actorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val persistence = FakeDiagnosticBreadcrumbStore(failWrites = true)
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                    ownerScope = actorScope,
                    actorDispatcher = dispatcher,
                )

            runCurrent()
            repeat(64) { batch ->
                repeat(64) { index ->
                    store.append(
                        "PlaybackInfoPlanner",
                        Severity.Info,
                        "stage=planner event=fallback nativeCode=${batch * 64 + index}",
                    )
                }
                runCurrent()
            }

            val failedSnapshot = async { store.snapshot() }
            runCurrent()
            assertTrue(failedSnapshot.await().text.startsWith("[earlier diagnostic lines were dropped]"))
            assertTrue(persistence.records.isEmpty())

            persistence.failWrites = false
            val recoveredSnapshot = async { store.snapshot() }
            runCurrent()
            val uploaded = recoveredSnapshot.await()
            assertTrue(uploaded.text.startsWith("[earlier diagnostic lines were dropped]"))
            assertTrue(uploaded.acknowledgement.representedTruncationRevision > 1L)
            assertEquals(uploaded.acknowledgement.representedTruncationRevision, persistence.truncationRevisionValue)
            assertEquals(uploaded.acknowledgement.representedTruncationRevision, persistence.lastTargetTruncationRevision)

            store.clearThrough(uploaded.acknowledgement)
            assertTrue(store.snapshot().text.isEmpty())

            val restored =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )
            assertTrue(restored.snapshot().text.isEmpty())
            actorScope.cancel()
        }

    @Test
    fun clearAfterTruncationRemovesMarkerForReconstruction() =
        runTest {
            val persistence = FakeDiagnosticBreadcrumbStore()
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )

            repeat(4_001) { index ->
                store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=overflow nativeCode=$index")
            }
            assertTrue(store.snapshot().text.startsWith("[earlier diagnostic lines were dropped]"))

            store.clear()

            assertTrue(store.snapshot().text.isEmpty())
            val restored =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )
            assertTrue(restored.snapshot().text.isEmpty())
        }

    @Test
    fun fallbackAcknowledgementDeletesExactIdsAfterRecoveryAndKeepsNewerRows() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val persistence = FakeDiagnosticBreadcrumbStore(failWrites = true)
            val store = LogBufferStore(preferenceStore, breadcrumbStore = persistence)

            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=old")
            val uploaded = store.snapshot()
            assertEquals(1, uploaded.acknowledgement.fallbackRecordIds.size)
            assertTrue(persistence.records.isEmpty())

            persistence.failWrites = false
            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=new")
            store.snapshot()
            assertEquals(2, persistence.records.size)

            store.clearThrough(uploaded.acknowledgement)

            assertTrue(persistence.records.none { record -> record.recordId in uploaded.acknowledgement.fallbackRecordIds })
            assertTrue(store.snapshot().text.contains("event=new"))
            assertFalse(store.snapshot().text.contains("event=old"))

            val restored =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )
            val restoredText = restored.snapshot().text
            assertFalse(restoredText.contains("event=old"))
            assertTrue(restoredText.contains("event=new"))
        }

    @Test
    fun committedThenThrownStorageRetryDoesNotDuplicateRows() =
        runTest {
            val persistence = FakeDiagnosticBreadcrumbStore(commitThenThrowOnce = true)
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )

            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=committed")
            assertTrue(store.snapshot().text.contains("event=committed"))
            assertEquals(1, persistence.records.size)

            assertTrue(store.snapshot().text.contains("event=committed"))
            assertEquals(1, persistence.records.size)
        }

    @Test
    fun snapshotRetriesAFailedHydrationWithoutANewAppend() =
        runTest {
            val persistence = FakeDiagnosticBreadcrumbStore(failHydrate = true)
            persistence.records +=
                DiagnosticBreadcrumbStoredRecord(
                    recordId = "persisted-before-retry",
                    line = "INFO PlaybackInfoPlanner stage=planner event=persisted",
                    byteCount = 65,
                    sequence = 1L,
                )
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )

            assertTrue(store.snapshot().text.isEmpty())

            persistence.failHydrate = false
            val retry = store.snapshot().text
            assertTrue(retry.contains("event=persisted"))
        }

    @Test
    fun reconciliationPlacesOlderDurableRowsBeforeNewerFallbackRows() =
        runTest {
            val persistence = FakeDiagnosticBreadcrumbStore(failHydrate = true, failWrites = true)
            val store =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )
            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=fallback")
            assertTrue(store.snapshot().text.contains("event=fallback"))

            persistence.records +=
                DiagnosticBreadcrumbStoredRecord(
                    recordId = "persisted-older",
                    line = "INFO PlaybackInfoPlanner stage=planner event=persisted",
                    byteCount = 66,
                    sequence = 1L,
                )
            persistence.failHydrate = false
            persistence.failWrites = false

            val lines = store.snapshot().text.lines()
            assertTrue(
                lines.indexOfFirst { line -> line.contains("event=persisted") } <
                    lines.indexOfFirst { line -> line.contains("event=fallback") },
            )
        }

    @Test
    fun saturatedIngressEventuallyHonorsDisabledPreference() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val actorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val appendGate = CompletableDeferred<Unit>()
            val persistence = FakeDiagnosticBreadcrumbStore(appendGate = appendGate)
            val store = LogBufferStore(preferenceStore, persistence, actorScope, dispatcher)

            runCurrent()
            assertTrue(persistence.hydrateStarted)

            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=started")
            runCurrent()
            assertTrue(persistence.appendStarted)

            repeat(512) { index ->
                store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=queued nativeCode=$index")
            }
            preferenceStore.enabled.value = false
            runCurrent()

            appendGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(false, preferenceStore.enabled.value)
            assertEquals(1, persistence.purgeCalls)
            assertEquals(0, store.size.value.entryCount)
            assertEquals(0, store.size.value.byteCount)
            val snapshot = store.snapshot()
            assertTrue(snapshot.text.isEmpty())
            actorScope.cancel()
        }

    @Test
    fun delayedPreferenceSignalCannotPurgeRowsAfterReenable() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val actorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val persistence = FakeDiagnosticBreadcrumbStore(hydrateGate = CompletableDeferred())
            val store = LogBufferStore(preferenceStore, persistence, actorScope, dispatcher)
            val initialSnapshot = async { store.snapshot() }
            runCurrent()
            assertTrue(persistence.hydrateStarted)

            preferenceStore.enabled.value = false
            runCurrent()
            preferenceStore.enabled.value = true
            runCurrent()
            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=new")
            checkNotNull(persistence.hydrateGate).complete(Unit)
            initialSnapshot.await()
            advanceUntilIdle()

            assertEquals(0, persistence.purgeCalls)
            assertTrue(store.snapshot().text.contains("event=new"))
            actorScope.cancel()
        }

    @Test
    fun oldAcknowledgementCannotClearRowsFromAReenabledHistory() =
        runTest {
            val preferenceStore = FakeLogCollectionPreferenceStore(enabled = true)
            val persistence = FakeDiagnosticBreadcrumbStore()
            val store = LogBufferStore(preferenceStore, breadcrumbStore = persistence)
            store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=old")
            val oldSnapshot = store.snapshot()
            val action = SetLogCollectionEnabledAction(preferenceStore, store)

            action(false)
            action(true)
            repeat(4_001) { index ->
                store.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=new nativeCode=$index")
            }
            val newSnapshot = store.snapshot()

            store.clearThrough(oldSnapshot.acknowledgement)

            assertTrue(newSnapshot.text.contains("[earlier diagnostic lines were dropped]"))
            assertTrue(store.snapshot().text.contains("nativeCode=1"))
            assertTrue(store.snapshot().text.contains("[earlier diagnostic lines were dropped]"))
            val restored =
                LogBufferStore(
                    FakeLogCollectionPreferenceStore(enabled = true),
                    breadcrumbStore = persistence,
                )
            assertTrue(restored.snapshot().text.contains("nativeCode=1"))
            assertTrue(restored.snapshot().text.contains("[earlier diagnostic lines were dropped]"))
        }

    @Test
    fun disabledStartupPurgesOnlyDiagnosticRowsAndReenableStartsEmpty() =
        runTest {
            val persistence = FakeDiagnosticBreadcrumbStore()
            val enabledPreference = FakeLogCollectionPreferenceStore(enabled = true)
            val initial = LogBufferStore(enabledPreference, breadcrumbStore = persistence)
            initial.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=old")
            initial.snapshot()
            assertEquals(1, persistence.records.size)

            val disabledPreference = FakeLogCollectionPreferenceStore(enabled = false)
            val disabled = LogBufferStore(disabledPreference, breadcrumbStore = persistence)
            assertEquals("", disabled.snapshot().text)
            assertTrue(persistence.purgeCalls > 0)
            assertEquals(0, persistence.records.size)

            disabledPreference.setEnabled(true)
            disabled.append("PlaybackInfoPlanner", Severity.Info, "stage=planner event=new")
            assertTrue(disabled.snapshot().text.contains("event=new"))
            assertFalse(disabled.snapshot().text.contains("event=old"))
        }

    @Test
    fun eachRetainedLineIsBoundedByUtf8BytesWithoutSplittingCharacters() =
        runTest {
            val store = enabledStore()
            store.append(
                "PlaybackInfoPlanner",
                Severity.Info,
                "stage=planner event=resolved nativeCode=${"😀".repeat(5_000)}",
            )

            val line = store.snapshot().text
            assertTrue(line.encodeToByteArray().size <= 4_096)
            assertTrue(line.endsWith(" ...[truncated]"))
            assertFalse(line.contains('\uFFFD'))
        }

    private fun enabledStore(): LogBufferStore = LogBufferStore(FakeLogCollectionPreferenceStore(enabled = true))

    private class FakeLogCollectionPreferenceStore(
        enabled: Boolean = false,
    ) : LogCollectionPreferenceStore {
        override val enabled = MutableStateFlow(enabled)
        override val verboseLogcatEnabled = MutableStateFlow(false)
        override val playbackInfoAtStartEnabled = MutableStateFlow(false)

        override suspend fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
            verboseLogcatEnabled.value = enabled
        }

        override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
            playbackInfoAtStartEnabled.value = enabled
        }
    }

    private class FakeDiagnosticBreadcrumbStore(
        var failWrites: Boolean = false,
        var failHydrate: Boolean = false,
        var commitThenThrowOnce: Boolean = false,
        val hydrateGate: CompletableDeferred<Unit>? = null,
        val appendGate: CompletableDeferred<Unit>? = null,
    ) : DiagnosticBreadcrumbStore {
        val records = mutableListOf<DiagnosticBreadcrumbStoredRecord>()
        var purgeCalls: Int = 0
            private set
        var hydrateStarted: Boolean = false
            private set
        var appendStarted: Boolean = false
            private set
        var lastTargetTruncationRevision: Long? = null
            private set

        val truncationRevisionValue: Long
            get() = truncationRevision

        private var nextSequence = 1L
        private var truncationRevision = 0L

        override suspend fun hydrate(): DiagnosticBreadcrumbStorageState {
            hydrateStarted = true
            hydrateGate?.await()
            check(!failHydrate) { "simulated diagnostic hydration failure" }
            return state()
        }

        override suspend fun appendAndTrim(
            records: List<DiagnosticBreadcrumbRecord>,
            ingressOverflowed: Boolean,
            targetTruncationRevision: Long?,
        ): DiagnosticBreadcrumbStorageState {
            appendStarted = true
            lastTargetTruncationRevision = targetTruncationRevision
            appendGate?.await()
            check(!failWrites) { "simulated diagnostic storage failure" }
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
            var pruned = false
            while (this.records.size > 4_000 || this.records.sumOf { record -> record.byteCount } > 500_000) {
                this.records.removeAt(0)
                pruned = true
            }
            truncationRevision =
                if (targetTruncationRevision != null) {
                    maxOf(targetTruncationRevision, truncationRevision)
                } else if (ingressOverflowed || pruned) {
                    truncationRevision + 1L
                } else {
                    truncationRevision
                }
            if (commitThenThrowOnce) {
                commitThenThrowOnce = false
                error("simulated committed diagnostic storage uncertainty")
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
            if (truncationRevision == representedTruncationRevision) truncationRevision = 0L
        }

        override suspend fun clear() {
            records.clear()
            truncationRevision = 0L
        }

        override suspend fun purge() {
            purgeCalls += 1
            records.clear()
            nextSequence = 1L
            truncationRevision = 0L
        }

        private fun state(): DiagnosticBreadcrumbStorageState =
            DiagnosticBreadcrumbStorageState(
                records = records.toList(),
                truncationRevision = truncationRevision,
            )
    }
}

class LogScrubberTest {
    @Test
    fun redactsSecretsUrlsPathsAndServerAddresses() {
        val safe =
            LogScrubber.capture(
                tag = "PlaybackInfoPlanner",
                message =
                    "stage=planner event=failed exceptionType=https://jellyfin.example/path?api_key=secret " +
                        "mappingReason=token=abc123 nativeCode=/Users/test/video.mkv httpCode=192.168.1.2",
            )

        requireNotNull(safe)
        assertFalse(safe.contains("jellyfin.example"))
        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("/Users/test"))
        assertFalse(safe.contains("192.168.1.2"))
    }

    @Test
    fun rejectsHttpUnknownAndFreeFormOrIdentityShapedFields() {
        assertNull(LogScrubber.capture("HttpClient", "stage=planner event=failed"))
        assertNull(LogScrubber.capture("PlaybackInfoPlanner", "request failed stage=planner event=failed"))
        assertNull(LogScrubber.capture("PlaybackInfoPlanner", "stage=planner event=failed title=TheShow"))
        assertNull(LogScrubber.capture("PlaybackInfoPlanner", "stage=planner event=failed username=demo-user"))
        assertNull(LogScrubber.capture("PlaybackInfoPlanner", "stage=planner event=failed itemId=123e4567-e89b"))
    }
}
