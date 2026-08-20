// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.domain.action.SavePlaybackPreferencesAction
import com.jellyscope.core.domain.action.SendClientLogsAction
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.usecase.GetLogCollectionStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.util.LogBufferStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSettingsPresenterTest {
    @Test
    fun diagnosticToggleAndSendExposeSafeCollectionState() =
        runTest {
            val preferenceStore = TestLogCollectionPreferenceStore()
            val presenter = presenter(FakePlaybackPreferencesStore(), preferenceStore = preferenceStore)
            runCurrent()

            presenter.setDiagnosticCollectionEnabled(false)
            runCurrent()
            assertTrue(!presenter.state.value.diagnosticCollectionEnabled)
            assertEquals(preferenceStore.expectedDispatcher, preferenceStore.observedSetEnabledDispatcher)

            presenter.sendClientLogs()
            runCurrent()
            assertEquals(TvDiagnosticsSendResult.Failure, presenter.state.value.diagnosticSendResult)
            presenter.close()
        }

    @Test
    fun diagnosticPreferenceFailureKeepsTheAuthoritativeToggle() =
        runTest {
            val preferenceStore = TestLogCollectionPreferenceStore()
            preferenceStore.failNextEnabledWrite = true
            val presenter = presenter(FakePlaybackPreferencesStore(), preferenceStore = preferenceStore)
            runCurrent()

            presenter.setDiagnosticCollectionEnabled(false)
            runCurrent()

            assertTrue(presenter.state.value.diagnosticPreferenceError)
            assertTrue(presenter.state.value.diagnosticCollectionEnabled)
            assertTrue(preferenceStore.enabled.value)
            assertEquals(preferenceStore.expectedDispatcher, preferenceStore.observedSetEnabledDispatcher)
            presenter.close()
        }

    @Test
    fun diagnosticSendMapsSuccessUploadDisallowedAndFailure() =
        runTest {
            listOf(
                SendClientLogsResult.Success("client.log") to TvDiagnosticsSendResult.Success,
                SendClientLogsResult.UploadDisallowed to TvDiagnosticsSendResult.UploadDisallowed,
                SendClientLogsResult.Failure to TvDiagnosticsSendResult.Failure,
            ).forEach { (result, expected) ->
                val repository = FakeTvMediaRepository(uploadClientLogsResult = result)
                val presenter = presenter(FakePlaybackPreferencesStore(), repository = repository)
                runCurrent()

                presenter.sendClientLogs()
                runCurrent()

                assertEquals(expected, presenter.state.value.diagnosticSendResult)
                assertEquals(repository.expectedUploadDispatcher, repository.observedUploadDispatcher)
                presenter.close()
            }
        }

    @Test
    fun mutationsPersistInOrderThroughTheSerializedWriter() =
        runTest {
            val store = FakePlaybackPreferencesStore()
            val presenter = presenter(store)
            runCurrent()

            presenter.setDefaultMaxBitrate(20_000_000L)
            presenter.setPreferredSubtitleLanguage("eng")
            presenter.setSegmentPolicy(MediaSegmentType.Intro, SegmentSkipPolicy.AutoSkip)
            runCurrent()

            val persisted = store.preferences
            assertEquals(20_000_000L, persisted.defaultMaxBitrateBps)
            assertEquals("eng", persisted.preferredSubtitleLanguage)
            assertEquals(SegmentSkipPolicy.AutoSkip, persisted.introSkip)
            // The conflated writer may coalesce, but the final snapshot always
            // reflects every mutation (whole-object copies compose).
            assertEquals(store.expectedDispatcher, store.observedGetDispatcher)
            assertTrue(store.savedSnapshots.isNotEmpty())
            assertEquals(store.expectedDispatcher, store.observedSaveDispatcher)
            presenter.close()
        }

    @Test
    fun saveFailureDropsPendingReloadsTruthAndFlagsError() =
        runTest {
            val store = FakePlaybackPreferencesStore(PlaybackPreferences(defaultMaxBitrateBps = 10_000_000L))
            val presenter = presenter(store)
            runCurrent()

            store.failNextSave = true
            presenter.setDefaultMaxBitrate(20_000_000L)
            runCurrent()

            val state = presenter.state.value
            assertTrue(state.saveError)
            // Reverted to store truth after the failed write.
            assertEquals(10_000_000L, state.preferences.defaultMaxBitrateBps)
            assertTrue(store.savedSnapshots.isEmpty())
            assertEquals(store.expectedDispatcher, store.observedSaveDispatcher)

            // A subsequent mutation clears the flag and persists normally.
            presenter.setPreferredAudioLanguage("fre")
            runCurrent()
            assertTrue(!presenter.state.value.saveError)
            assertEquals("fre", store.preferences.preferredAudioLanguage)
            presenter.close()
        }

    @Test
    fun bitrateChoicesUseTheSharedLadderAndMarkTheSelectedRung() =
        runTest {
            val store = FakePlaybackPreferencesStore()
            val presenter = presenter(store)
            runCurrent()

            // Choices come from the shared quality ladder (plus Auto), so a
            // default picked here always matches an in-player Quality rung.
            val choices = presenter.state.value.bitrateChoices
            val sharedLadder =
                com.jellyscope.core.domain.playback
                    .qualityOptions(null)
                    .mapNotNull { option -> option.maxBitrateBps }
            assertEquals(sharedLadder, choices.mapNotNull { choice -> choice.maxBitrateBps })
            assertTrue(choices.first().selected) // Auto selected by default.

            val firstRung =
                requireNotNull(
                    choices.first { choice -> choice.mode == PlaybackQualityMode.Fixed }.maxBitrateBps,
                )
            presenter.setDefaultMaxBitrate(firstRung)
            runCurrent()
            assertEquals(
                firstRung,
                presenter.state.value.bitrateChoices
                    .single { choice -> choice.selected }
                    .maxBitrateBps,
            )
            presenter.close()
        }

    @Test
    fun offLadderQualityStaysExactAndWholeSnapshotPreservesVlcBudget() =
        runTest {
            val store =
                FakePlaybackPreferencesStore(
                    PlaybackPreferences(
                        defaultMaxBitrateBps = 10_000_000L,
                        vlcTranscodeMaxBitrateBps = 8_000_000L,
                    ),
                )
            val presenter = presenter(store)
            runCurrent()

            val selectedQuality =
                presenter.state.value.bitrateChoices
                    .single { choice -> choice.selected }
            assertEquals(10_000_000L, selectedQuality.maxBitrateBps)
            assertTrue(selectedQuality.isCustom)

            presenter.setPreferredSubtitleLanguage("eng")
            runCurrent()
            assertEquals(8_000_000L, store.preferences.vlcTranscodeMaxBitrateBps)
            presenter.close()
        }

    @Test
    fun closingDuringLoadDoesNotPublishFallbackPreferences() =
        runTest {
            val store = FakePlaybackPreferencesStore()
            store.blockGets = true
            val presenter = presenter(store)
            runCurrent()
            assertTrue(presenter.state.value.isLoading)

            presenter.close()
            runCurrent()

            assertTrue(presenter.state.value.isLoading)
        }

    private fun TestScope.presenter(
        store: FakePlaybackPreferencesStore,
        repository: FakeTvMediaRepository = FakeTvMediaRepository(),
        preferenceStore: TestLogCollectionPreferenceStore = TestLogCollectionPreferenceStore(),
    ): TvSettingsPresenter =
        run {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val workDispatcher = StandardTestDispatcher(testScheduler)
            store.expectedDispatcher = workDispatcher
            preferenceStore.expectedDispatcher = workDispatcher
            repository.expectedUploadDispatcher = workDispatcher
            val logBufferStore =
                LogBufferStore(
                    preferenceStore = preferenceStore,
                    ownerScope = backgroundScope,
                    actorDispatcher = workDispatcher,
                )
            TvSettingsPresenter(
                session = testSession(),
                getPlaybackPreferences = GetPlaybackPreferencesUseCase(store),
                savePlaybackPreferences = SavePlaybackPreferencesAction(store),
                getLogCollectionState = GetLogCollectionStateUseCase(preferenceStore, logBufferStore),
                setLogCollectionEnabled = SetLogCollectionEnabledAction(preferenceStore, logBufferStore),
                sendClientLogsAction =
                    SendClientLogsAction(
                        logBufferStore = logBufferStore,
                        mediaRepository = repository,
                        diagnosticsEnvironment = TestDiagnosticsEnvironment,
                        deviceProfileProvider = TestDeviceProfileProvider,
                        playbackDiagnosticsContext = PlaybackDiagnosticsContext(),
                    ),
                dispatchers = TvosDispatchers(main = mainDispatcher, work = workDispatcher),
            )
        }
}

private class TestLogCollectionPreferenceStore : LogCollectionPreferenceStore {
    private val _enabled = MutableStateFlow(true)
    private val _verboseLogcatEnabled = MutableStateFlow(false)
    private val _playbackInfoAtStartEnabled = MutableStateFlow(false)
    var failNextEnabledWrite = false
    var expectedDispatcher: ContinuationInterceptor? = null
    var observedSetEnabledDispatcher: ContinuationInterceptor? = null

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val verboseLogcatEnabled: StateFlow<Boolean> = _verboseLogcatEnabled.asStateFlow()
    override val playbackInfoAtStartEnabled: StateFlow<Boolean> = _playbackInfoAtStartEnabled.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        observedSetEnabledDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        if (failNextEnabledWrite) {
            failNextEnabledWrite = false
            error("diagnostic preference save failed")
        }
        _enabled.value = enabled
    }

    override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
        _verboseLogcatEnabled.value = enabled
    }

    override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        _playbackInfoAtStartEnabled.value = enabled
    }
}

private object TestDiagnosticsEnvironment : DiagnosticsEnvironment {
    override val platform: String = "tvos"
    override val osVersion: String = "test"
    override val appVersion: String = "test"
    override val deviceModel: String = "test"
}

private object TestDeviceProfileProvider : DeviceProfileProvider {
    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
        DeviceDecodingCapabilities(
            videoCodecs = emptyList(),
            audioCodecs = emptyList(),
            supportsDolbyVision = false,
        )
}
