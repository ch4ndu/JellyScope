// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.playback.PlayerVolumeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JvmFileVolumeStoreTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jellyscope-volume-store-test")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun volumeStateRestoresAcrossJvmStoreInstances() {
        val file = tempDir.resolve("preferences.json").toFile()
        val store = JvmFileVolumeStore(JvmFilePreferencesStore(file))
        val expected = PlayerVolumeState(volumePercent = 37, muted = true)

        store.submit(expected)

        waitUntil { JvmFileVolumeStore(JvmFilePreferencesStore(file)).restoredState == expected }

        assertEquals(expected, JvmFileVolumeStore(JvmFilePreferencesStore(file)).restoredState)
    }

    @Test
    fun restoredStateReflectsLatestSubmissionWithinTheSameProcess() {
        // A later controller in the same process (player close -> reopen) must
        // restore the value the user just set, even if the serialized disk
        // write has not finished yet — the shared store, not the disk, is the
        // in-process source of truth.
        val file = tempDir.resolve("preferences.json").toFile()
        val store = JvmFileVolumeStore(JvmFilePreferencesStore(file))
        val expected = PlayerVolumeState(volumePercent = 42, muted = false)

        store.submit(expected)

        assertEquals(expected, store.restoredState)
    }

    @Test
    fun serializedWriterKeepsNewestValueWhenControllersReleaseAroundAnInFlightWrite() =
        runTest {
            val writes = mutableListOf<Int>()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val allowFirstWriteToFinish = CompletableDeferred<Unit>()
            val writer: SerializedLatestValueWriter<Int> =
                SerializedLatestValueWriter(
                    scope = this,
                    monotonicTimeNanos = { testScheduler.currentTime * 1_000_000L },
                    write = { value ->
                        if (value == 20) {
                            firstWriteStarted.complete(Unit)
                            allowFirstWriteToFinish.await()
                        }
                        writes += value
                    },
                )

            // These represent the final snapshots submitted by two controller
            // instances as the older controller releases around an active write.
            writer.submit(20)
            runCurrent()
            testScheduler.advanceTimeBy(500L)
            runCurrent()
            firstWriteStarted.await()
            writer.submit(20)
            writer.submit(80)
            allowFirstWriteToFinish.complete(Unit)
            testScheduler.advanceTimeBy(500L)
            advanceUntilIdle()

            assertEquals(listOf(20, 80), writes)
        }

    @Test
    fun serializedWriterDoesNotWriteBeforeThe499MillisecondBoundary() =
        runTest {
            val writes = mutableListOf<Int>()
            val writer = testWriter(writes)

            writer.submit(42)
            runCurrent()
            testScheduler.advanceTimeBy(499L)
            runCurrent()

            assertTrue(writes.isEmpty())

            testScheduler.advanceTimeBy(1L)
            runCurrent()

            assertEquals(listOf(42), writes)
        }

    @Test
    fun serializedWriterRestartsTheDebounceAfterEachSubmission() =
        runTest {
            val writes = mutableListOf<Int>()
            val writer = testWriter(writes)

            writer.submit(20)
            runCurrent()
            testScheduler.advanceTimeBy(400L)
            runCurrent()
            writer.submit(80)
            runCurrent()

            testScheduler.advanceTimeBy(499L)
            runCurrent()
            assertTrue(writes.isEmpty())

            testScheduler.advanceTimeBy(1L)
            runCurrent()

            assertEquals(listOf(80), writes)
        }

    @Test
    fun serializedWriterCollapsesABurstToOneWriteOfTheNewestValue() =
        runTest {
            val writes = mutableListOf<Int>()
            val writer = testWriter(writes)

            writer.submit(20)
            writer.submit(50)
            writer.submit(80)
            runCurrent()
            testScheduler.advanceTimeBy(500L)
            runCurrent()

            assertEquals(listOf(80), writes)
        }

    @Test
    fun identicalResubmissionsDoNotAddAWriteOrRestartTheDebounce() =
        runTest {
            val writes = mutableListOf<PlayerVolumeState>()
            val store =
                JvmFileVolumeStore(
                    preferences = JvmFilePreferencesStore(tempDir.resolve("preferences.json").toFile()),
                    writerScope = this,
                    monotonicTimeNanos = { testScheduler.currentTime * 1_000_000L },
                    writeState = { state -> writes += state },
                )
            val state = PlayerVolumeState(volumePercent = 64, muted = false)

            store.submit(state)
            runCurrent()
            testScheduler.advanceTimeBy(499L)
            runCurrent()
            store.submit(state)
            runCurrent()
            testScheduler.advanceTimeBy(1L)
            runCurrent()

            assertEquals(listOf(state), writes)
        }

    @Test
    fun anIdenticalSubmissionRetriesAWriteThatFailed() =
        runTest {
            val writes = mutableListOf<PlayerVolumeState>()
            var failNextWrite = true
            val store =
                JvmFileVolumeStore(
                    preferences = JvmFilePreferencesStore(tempDir.resolve("preferences.json").toFile()),
                    writerScope = this,
                    monotonicTimeNanos = { testScheduler.currentTime * 1_000_000L },
                    writeState = { state ->
                        if (failNextWrite) {
                            failNextWrite = false
                            throw IllegalStateException("disk unavailable")
                        }
                        writes += state
                    },
                )
            val state = PlayerVolumeState(volumePercent = 64, muted = false)

            store.submit(state)
            testScheduler.advanceTimeBy(500L)
            runCurrent()
            assertTrue(writes.isEmpty())

            // Duplicate suppression must not swallow the *retry*. Submitting the same
            // value again after a failed write has to reach disk, or a cold start
            // restores the older value with no way for the user to correct it — they
            // would have to pick a different volume, then come back.
            store.submit(state)
            testScheduler.advanceTimeBy(500L)
            runCurrent()

            assertEquals(listOf(state), writes)
        }

    @Test
    fun clampedDragWritesOnlyOnce() =
        runTest {
            val writes = mutableListOf<PlayerVolumeState>()
            val store =
                JvmFileVolumeStore(
                    preferences = JvmFilePreferencesStore(tempDir.resolve("preferences.json").toFile()),
                    writerScope = this,
                    monotonicTimeNanos = { testScheduler.currentTime * 1_000_000L },
                    writeState = { state -> writes += state },
                )
            val clampedState = PlayerVolumeState(volumePercent = 100, muted = false)

            // Spread the repeats across the window on purpose: a held volume-up key
            // at 100% re-sends the same clamped value indefinitely, and without
            // suppression every repeat would restart the debounce and starve the
            // write for as long as the key is down. Submitting them all at one
            // instant would pass either way and prove nothing.
            repeat(20) {
                store.submit(clampedState)
                testScheduler.advanceTimeBy(100L)
                runCurrent()
            }

            assertEquals(listOf(clampedState), writes)
        }

    @Test
    fun muteIsDebouncedNotImmediate() =
        runTest {
            val writes = mutableListOf<PlayerVolumeState>()
            val store =
                JvmFileVolumeStore(
                    preferences = JvmFilePreferencesStore(tempDir.resolve("preferences.json").toFile()),
                    writerScope = this,
                    monotonicTimeNanos = { testScheduler.currentTime * 1_000_000L },
                    writeState = { state -> writes += state },
                )
            val mutedState = PlayerVolumeState(volumePercent = 47, muted = true)

            store.submit(mutedState)
            runCurrent()
            assertTrue(writes.isEmpty())
            testScheduler.advanceTimeBy(499L)
            runCurrent()
            assertTrue(writes.isEmpty())
            testScheduler.advanceTimeBy(1L)
            runCurrent()

            assertEquals(listOf(mutedState), writes)
        }

    @Test
    fun restoredStateUsesTheNewestSubmissionDuringThePendingWindow() =
        runTest {
            val writes = mutableListOf<PlayerVolumeState>()
            val store =
                JvmFileVolumeStore(
                    preferences = JvmFilePreferencesStore(tempDir.resolve("preferences.json").toFile()),
                    writerScope = this,
                    monotonicTimeNanos = { testScheduler.currentTime * 1_000_000L },
                    writeState = { state -> writes += state },
                )
            val expected = PlayerVolumeState(volumePercent = 42, muted = true)

            store.submit(expected)

            assertEquals(expected, store.restoredState)
            assertTrue(writes.isEmpty())
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> TestScope.testWriter(writes: MutableList<T>): SerializedLatestValueWriter<T> =
    SerializedLatestValueWriter(
        scope = this,
        monotonicTimeNanos = { testScheduler.currentTime * 1_000_000L },
        write = { value -> writes += value },
    )

private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for volume preferences" }
        Thread.sleep(20L)
    }
}
