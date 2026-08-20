// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.consumePreviousRunFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviousRunFailureStoreTest {
    @Test
    fun markerKeepsOnlyAClosedExceptionAndPlatformToken() {
        val marker =
            previousRunFailureMarker(
                throwable = IllegalStateException("server=https://example.invalid/token=secret"),
                platform = PreviousRunFailurePlatform.TvOs,
            )

        assertEquals("IllegalStateException", marker.exceptionType)
        assertEquals(PreviousRunFailurePlatform.TvOs, marker.platform)
        assertEquals(
            marker,
            decodePreviousRunFailureMarker(
                schemaVersion = PREVIOUS_RUN_FAILURE_SCHEMA_VERSION,
                exceptionType = marker.exceptionType,
                platform = marker.platform.wireValue,
            ),
        )
    }

    @Test
    fun malformedOrFutureMarkersFailClosed() {
        assertNull(
            decodePreviousRunFailureMarker(
                schemaVersion = PREVIOUS_RUN_FAILURE_SCHEMA_VERSION + 1,
                exceptionType = "IllegalStateException",
                platform = PreviousRunFailurePlatform.Android.wireValue,
            ),
        )
        assertNull(
            decodePreviousRunFailureMarker(
                schemaVersion = PREVIOUS_RUN_FAILURE_SCHEMA_VERSION,
                exceptionType = "IllegalStateException message=secret",
                platform = PreviousRunFailurePlatform.Android.wireValue,
            ),
        )
        assertNull(
            decodePreviousRunFailureMarker(
                schemaVersion = PREVIOUS_RUN_FAILURE_SCHEMA_VERSION,
                exceptionType = "IllegalStateException",
                platform = "unknown-platform",
            ),
        )
    }

    @Test
    fun enabledStartupConsumesAndAppendsOneSafeMarker() =
        runTest {
            val preferenceStore = TestPreferenceStore(enabled = true)
            val buffer = diagnosticBuffer(preferenceStore)
            val markerStore = RecordingPreviousRunFailureStore()
            runCurrent()

            consumePreviousRunFailure(markerStore, preferenceStore, buffer)
            runCurrent()

            assertEquals(1, markerStore.consumeCalls)
            val snapshot = buffer.snapshot()
            assertEquals(1, snapshot.acknowledgement.maxGeneration)
            assertTrue(snapshot.text.contains("stage=process-exit event=unhandled"))
            assertTrue(snapshot.text.contains("operation=previousRunFailure"))
            assertTrue(snapshot.text.contains("platform=android"))
        }

    @Test
    fun disabledStartupClearsTheMarkerWithoutAppendingHistory() =
        runTest {
            val preferenceStore = TestPreferenceStore(enabled = false)
            val buffer = diagnosticBuffer(preferenceStore)
            val markerStore = RecordingPreviousRunFailureStore()
            runCurrent()

            consumePreviousRunFailure(markerStore, preferenceStore, buffer)
            runCurrent()

            assertEquals(0, markerStore.consumeCalls)
            assertEquals(1, markerStore.clearCalls)
            assertTrue(buffer.snapshot().text.isEmpty())
        }

    private fun TestScope.diagnosticBuffer(preferenceStore: TestPreferenceStore): LogBufferStore =
        LogBufferStore(
            preferenceStore = preferenceStore,
            ownerScope = backgroundScope,
            actorDispatcher = StandardTestDispatcher(testScheduler),
        )

    private class RecordingPreviousRunFailureStore : PreviousRunFailureStore {
        var consumeCalls = 0
        var clearCalls = 0

        override fun write(
            throwable: Throwable,
            platform: PreviousRunFailurePlatform,
        ) = Unit

        override fun consume(): PreviousRunFailureMarker? {
            consumeCalls += 1
            return PreviousRunFailureMarker(
                exceptionType = "IllegalStateException",
                platform = PreviousRunFailurePlatform.Android,
            )
        }

        override fun clear() {
            clearCalls += 1
        }
    }

    private class TestPreferenceStore(
        enabled: Boolean,
    ) : LogCollectionPreferenceStore {
        private val _enabled = MutableStateFlow(enabled)
        private val _verboseLogcatEnabled = MutableStateFlow(false)
        private val _playbackInfoAtStartEnabled = MutableStateFlow(false)

        override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
        override val verboseLogcatEnabled: StateFlow<Boolean> = _verboseLogcatEnabled.asStateFlow()
        override val playbackInfoAtStartEnabled: StateFlow<Boolean> = _playbackInfoAtStartEnabled.asStateFlow()

        override suspend fun setEnabled(enabled: Boolean) {
            _enabled.value = enabled
        }

        override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
            _verboseLogcatEnabled.value = enabled
        }

        override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
            _playbackInfoAtStartEnabled.value = enabled
        }
    }
}
