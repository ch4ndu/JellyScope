// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.SubtitleSelectionKey
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackLaunchReadOutcome
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GetPlaybackLaunchContextUseCaseTest {
    @Test
    fun readsAndNormalizesAllFieldsForTheExactLaunchIdentity() =
        runTest {
            val preferencesStore = TestPlaybackPreferencesStore(PlaybackPreferences(autoPlayNextDelaySeconds = 999))
            val playbackStore = TestPlaybackSelectionStore(PlaybackSelection(audioStreamIndex = -7, schemaVersion = 99))
            val subtitleStore = TestSubtitleSelectionStore(SubtitleSelectionIntent.Track(4))
            val session = testSession()

            val context =
                useCase(preferencesStore, playbackStore, subtitleStore)(
                    session = session,
                    itemId = "item-exact",
                    mediaSourceId = "source-exact",
                )

            assertEquals(60, context.playbackPreferences.autoPlayNextDelaySeconds)
            assertNull(context.playbackSelection?.audioStreamIndex)
            assertEquals(SubtitleSelectionIntent.Track(4), context.subtitleSelection)
            assertEquals(PlaybackLaunchReadOutcome.Present, context.playbackPreferencesOutcome)
            assertEquals(PlaybackLaunchReadOutcome.Present, context.playbackSelectionOutcome)
            assertEquals(PlaybackLaunchReadOutcome.Present, context.subtitleSelectionOutcome)
            assertEquals("server-exact", preferencesStore.requestedServerId)
            assertEquals(
                PlaybackSelectionKey("server-exact", "user-exact", "item-exact", "source-exact"),
                playbackStore.requestedKey,
            )
            assertEquals(
                SubtitleSelectionKey("server-exact", "user-exact", "item-exact", "source-exact"),
                subtitleStore.requestedKey,
            )
        }

    @Test
    fun reportsMissingFailedAndUnavailableIndependently() =
        runTest {
            val context =
                GetPlaybackLaunchContextUseCase(
                    getPlaybackPreferences =
                        GetPlaybackPreferencesUseCase(
                            TestPlaybackPreferencesStore(failure = IllegalStateException("preferences unavailable")),
                        ),
                    getPlaybackSelection = GetPlaybackSelectionUseCase(TestPlaybackSelectionStore(value = null)),
                    getSubtitleSelection = null,
                )(testSession(), "item", "source")

            assertEquals(PlaybackPreferences(), context.playbackPreferences)
            assertNull(context.playbackSelection)
            assertNull(context.subtitleSelection)
            assertEquals(PlaybackLaunchReadOutcome.Failed, context.playbackPreferencesOutcome)
            assertEquals(PlaybackLaunchReadOutcome.Missing, context.playbackSelectionOutcome)
            assertEquals(PlaybackLaunchReadOutcome.Unavailable, context.subtitleSelectionOutcome)
        }

    @Test
    fun startsAllIndependentReadsBeforeAwaitingAnyResult() =
        runTest {
            val releaseReads = CompletableDeferred<Unit>()
            val preferencesEntered = CompletableDeferred<Unit>()
            val playbackEntered = CompletableDeferred<Unit>()
            val subtitleEntered = CompletableDeferred<Unit>()
            val context =
                async {
                    useCase(
                        TestPlaybackPreferencesStore(
                            onGet = {
                                preferencesEntered.complete(Unit)
                                releaseReads.await()
                            },
                        ),
                        TestPlaybackSelectionStore(
                            onGet = {
                                playbackEntered.complete(Unit)
                                releaseReads.await()
                            },
                        ),
                        TestSubtitleSelectionStore(
                            onGet = {
                                subtitleEntered.complete(Unit)
                                releaseReads.await()
                            },
                        ),
                    )(testSession(), "item", "source")
                }

            runCurrent()

            assertTrue(preferencesEntered.isCompleted)
            assertTrue(playbackEntered.isCompleted)
            assertTrue(subtitleEntered.isCompleted)
            releaseReads.complete(Unit)
            context.await()
        }

    @Test
    fun callerCancellationRemainsCancellationAndCancelsSiblingReads() =
        runTest {
            var cancelledReads = 0
            val entered = List(3) { CompletableDeferred<Unit>() }

            suspend fun block(index: Int) {
                entered[index].complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelledReads += 1
                }
            }
            val context =
                async {
                    useCase(
                        TestPlaybackPreferencesStore(onGet = { block(0) }),
                        TestPlaybackSelectionStore(onGet = { block(1) }),
                        TestSubtitleSelectionStore(onGet = { block(2) }),
                    )(testSession(), "item", "source")
                }
            runCurrent()
            assertTrue(entered.all { signal -> signal.isCompleted })

            context.cancel()
            runCurrent()

            assertTrue(context.isCancelled)
            assertEquals(3, cancelledReads)
        }

    private fun useCase(
        preferencesStore: PlaybackPreferencesStore,
        playbackStore: PlaybackSelectionStore,
        subtitleStore: SubtitleSelectionStore,
    ) = GetPlaybackLaunchContextUseCase(
        getPlaybackPreferences = GetPlaybackPreferencesUseCase(preferencesStore),
        getPlaybackSelection = GetPlaybackSelectionUseCase(playbackStore),
        getSubtitleSelection = GetSubtitleSelectionUseCase(subtitleStore),
    )

    private fun testSession() =
        Session(
            serverUrl = "https://server.example",
            serverId = "server-exact",
            serverName = "Server",
            userId = "user-exact",
            userName = "User",
            accessToken = "token",
            deviceId = "device",
        )
}

private class TestPlaybackPreferencesStore(
    private val value: PlaybackPreferences = PlaybackPreferences(),
    private val failure: Throwable? = null,
    private val onGet: suspend () -> Unit = {},
) : PlaybackPreferencesStore {
    var requestedServerId: String? = null

    override suspend fun get(serverId: String): PlaybackPreferences {
        requestedServerId = serverId
        onGet()
        failure?.let { throwable -> throw throwable }
        return value
    }

    override suspend fun save(
        serverId: String,
        preferences: PlaybackPreferences,
    ) = Unit

    override suspend fun clear(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private class TestPlaybackSelectionStore(
    private val value: PlaybackSelection? = PlaybackSelection(),
    private val onGet: suspend () -> Unit = {},
) : PlaybackSelectionStore {
    var requestedKey: PlaybackSelectionKey? = null

    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? {
        requestedKey = key
        onGet()
        return value
    }

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) = Unit

    override suspend fun delete(key: PlaybackSelectionKey) = Unit

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private class TestSubtitleSelectionStore(
    private val value: SubtitleSelectionIntent? = SubtitleSelectionIntent.Off,
    private val onGet: suspend () -> Unit = {},
) : SubtitleSelectionStore {
    var requestedKey: SubtitleSelectionKey? = null

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? {
        requestedKey = key
        onGet()
        return value
    }

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) = Unit

    override suspend fun delete(key: SubtitleSelectionKey) = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}
