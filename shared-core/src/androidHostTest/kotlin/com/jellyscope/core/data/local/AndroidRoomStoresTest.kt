// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AndroidRoomStoresTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder(context, JellyfinStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val dao = database.jellyfinStoreDao()

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun missingRowUsesTheConstructorVlcDefaultAndStoredRowsWin() =
        runTest {
            val store = RoomPlaybackPreferencesStore(dao, defaultVlcTranscodeBitrateBps = 8_000_000L)

            assertEquals(8_000_000L, store.get("server-1").vlcTranscodeMaxBitrateBps)

            store.save("server-1", store.get("server-1").copy(vlcTranscodeMaxBitrateBps = null))
            assertEquals(null, store.get("server-1").vlcTranscodeMaxBitrateBps)
        }

    @Test
    fun daoRoundTripsPlaybackPreferencesAndWatchNextState() =
        runTest {
            val preferencesStore = RoomPlaybackPreferencesStore(dao)
            val watchNextSyncStore = RoomWatchNextSyncStore(dao)

            preferencesStore.save(
                serverId = "server-1",
                preferences =
                    PlaybackPreferences(
                        defaultMaxBitrateBps = 8_000_000L,
                        vlcTranscodeMaxBitrateBps = 12_000_000L,
                        defaultPlayerBackend = PlayerBackend.VlcKit,
                        preferredAudioLanguage = "eng",
                        preferredSubtitleLanguage = "spa",
                        stillWatchingPrompt = false,
                    ),
            )
            watchNextSyncStore.upsert(
                serverId = "server-1",
                state =
                    WatchNextSyncState(
                        itemId = "item-1",
                        playbackPositionTicks = 12_345L,
                        played = false,
                        lastSyncedAtEpochMs = 100L,
                    ),
            )

            assertEquals(8_000_000L, preferencesStore.get("server-1").defaultMaxBitrateBps)
            assertEquals(12_000_000L, preferencesStore.get("server-1").vlcTranscodeMaxBitrateBps)
            assertEquals("eng", preferencesStore.get("server-1").preferredAudioLanguage)
            assertEquals("spa", preferencesStore.get("server-1").preferredSubtitleLanguage)
            assertEquals(false, preferencesStore.get("server-1").stillWatchingPrompt)
            assertEquals(PlayerBackend.VlcKit, preferencesStore.get("server-1").defaultPlayerBackend)
            assertEquals("item-1", watchNextSyncStore.get(serverId = "server-1", itemId = "item-1")?.itemId)
            assertEquals(12_345L, watchNextSyncStore.list("server-1").single().playbackPositionTicks)
        }

    @Test
    fun recentAndWatchNextRowsAreIsolatedByUserAndAccountClearPreservesSibling() =
        runTest {
            val recent = RoomRecentSearchStore(dao)
            val watchNext = RoomWatchNextSyncStore(dao)
            recent.add("server-1", "user-1", "first")
            recent.add("server-1", "user-2", "second")
            watchNext.upsert("server-1", "user-1", WatchNextSyncState("item", 1L, false, 1L))
            watchNext.upsert("server-1", "user-2", WatchNextSyncState("item", 2L, false, 2L))

            recent.clear("server-1", "user-1")
            watchNext.clear("server-1", "user-1")

            assertEquals(emptyList(), recent.list("server-1", "user-1"))
            assertEquals(listOf("second"), recent.list("server-1", "user-2"))
            assertNull(watchNext.get("server-1", "user-1", "item"))
            assertEquals(2L, watchNext.get("server-1", "user-2", "item")?.playbackPositionTicks)
        }

    @Test
    fun stillWatchingPromptDefaultsToEnabledForRowsThatNeverStoredIt() =
        runTest {
            dao.upsertPlaybackPreferences(
                PlaybackPreferencesEntity(
                    serverId = "server-1",
                    defaultMaxBitrateBps = null,
                    preferredAudioLanguage = null,
                    preferredSubtitleLanguage = null,
                ),
            )

            val preferencesStore = RoomPlaybackPreferencesStore(dao)

            assertEquals(true, preferencesStore.get("server-1").stillWatchingPrompt)
        }

    @Test
    fun persistedDefaultPlayerBackendUsesAvPlayerForMissingAndAutoForMalformedValues() =
        runTest {
            val store = RoomPlaybackPreferencesStore(dao)

            assertEquals(PlayerBackend.AVPlayer, store.get("missing-server").defaultPlayerBackend)

            dao.upsertPlaybackPreferences(
                PlaybackPreferencesEntity(
                    serverId = "unknown-server",
                    defaultPlayerBackend = " Unknown ",
                    defaultMaxBitrateBps = null,
                    preferredAudioLanguage = null,
                    preferredSubtitleLanguage = null,
                ),
            )
            dao.upsertPlaybackPreferences(
                PlaybackPreferencesEntity(
                    serverId = "blank-server",
                    defaultPlayerBackend = " \t ",
                    defaultMaxBitrateBps = null,
                    preferredAudioLanguage = null,
                    preferredSubtitleLanguage = null,
                ),
            )
            dao.upsertPlaybackPreferences(
                PlaybackPreferencesEntity(
                    serverId = "null-server",
                    defaultPlayerBackend = null,
                    defaultMaxBitrateBps = null,
                    preferredAudioLanguage = null,
                    preferredSubtitleLanguage = null,
                ),
            )

            assertEquals(PlayerBackend.Auto, store.get("unknown-server").defaultPlayerBackend)
            assertEquals(PlayerBackend.Auto, store.get("blank-server").defaultPlayerBackend)
            assertEquals(PlayerBackend.Auto, store.get("null-server").defaultPlayerBackend)
        }

    @Test
    fun playerBackendOverridesRoundTripPreserveAutoAndIsolateServerAndItem() =
        runTest {
            val store = RoomPlayerBackendOverrideStore(dao)

            store.save("server-1", "item-1", PlayerBackend.VlcKit)
            store.save("server-1", "item-2", PlayerBackend.Auto)
            store.save("server-2", "item-1", PlayerBackend.AVPlayer)

            assertEquals(PlayerBackend.VlcKit, store.get("server-1", "item-1"))
            assertEquals(PlayerBackend.Auto, store.get("server-1", "item-2"))
            assertEquals(PlayerBackend.AVPlayer, store.get("server-2", "item-1"))

            store.delete("server-1", "item-1")
            assertNull(store.get("server-1", "item-1"))
            assertEquals(PlayerBackend.Auto, store.get("server-1", "item-2"))
        }

    @Test
    fun malformedPlayerBackendOverridesDecodeAsNoOverride() =
        runTest {
            val store = RoomPlayerBackendOverrideStore(dao)
            dao.upsertPlayerBackendOverride(PlayerBackendOverrideEntity("server-1", "unknown", " Unknown "))
            dao.upsertPlayerBackendOverride(PlayerBackendOverrideEntity("server-1", "blank", " \t "))
            dao.upsertPlayerBackendOverride(PlayerBackendOverrideEntity("server-1", "null", null))

            assertNull(store.get("server-1", "unknown"))
            assertNull(store.get("server-1", "blank"))
            assertNull(store.get("server-1", "null"))
        }

    @Test
    fun playerBackendOverridesClearByServerAndAll() =
        runTest {
            val store = RoomPlayerBackendOverrideStore(dao)
            store.save("server-1", "item-1", PlayerBackend.VlcKit)
            store.save("server-2", "item-1", PlayerBackend.AVPlayer)

            store.clearServerScoped("server-1")

            assertNull(store.get("server-1", "item-1"))
            assertEquals(PlayerBackend.AVPlayer, store.get("server-2", "item-1"))

            store.clearServerScoped()
            assertNull(store.get("server-2", "item-1"))
        }

    @Test
    fun playerDeviceSettingsPersistAcrossStoreInstances() =
        runTest {
            val store = RoomPlayerDeviceSettingsStore(dao = dao, scope = this)
            val settings =
                PlayerDeviceSettings(
                    audioMode = PlayerAudioMode.PassthroughWhenSupported,
                    hdrMode = PlayerHdrMode.PreferSdr,
                    matchDisplayRefreshRate = true,
                    maxVideoResolution = PlayerVideoResolutionLimit.Height2160,
                    iosPlaybackCompatibilityMode = IosPlaybackCompatibilityMode.Unrestricted,
                )

            store.setSettings(settings)

            assertEquals(settings.audioMode.name, dao.playerDeviceSettings(PLAYER_DEVICE_SETTINGS_ID)?.audioMode)
            assertEquals(settings.hdrMode.name, dao.playerDeviceSettings(PLAYER_DEVICE_SETTINGS_ID)?.hdrMode)
            assertEquals(true, dao.playerDeviceSettings(PLAYER_DEVICE_SETTINGS_ID)?.matchDisplayRefreshRate)
            assertEquals(
                PlayerVideoResolutionLimit.Height2160.name,
                dao.playerDeviceSettings(PLAYER_DEVICE_SETTINGS_ID)?.maxVideoResolution,
            )
            assertEquals(
                IosPlaybackCompatibilityMode.Unrestricted.name,
                dao.playerDeviceSettings(PLAYER_DEVICE_SETTINGS_ID)?.iosPlaybackCompatibilityMode,
            )

            val restoreJob = SupervisorJob()
            val restoreScope = CoroutineScope(restoreJob + Dispatchers.Default)
            val restoredSettings: PlayerDeviceSettings
            try {
                val restoredStore = RoomPlayerDeviceSettingsStore(dao = dao, scope = restoreScope)
                restoredSettings =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            restoredStore.settings.first { restoredSettings -> restoredSettings == settings }
                        }
                    }
            } finally {
                restoreJob.cancelAndJoin()
            }

            assertEquals(settings, restoredSettings)
        }

    @Test
    fun unknownPersistedPlayerDeviceSettingsFallBackToDefaults() =
        runTest {
            dao.upsertPlayerDeviceSettings(
                PlayerDeviceSettingsEntity(
                    id = PLAYER_DEVICE_SETTINGS_ID,
                    audioMode = "Unknown",
                    hdrMode = "Unknown",
                    maxVideoResolution = "Unknown",
                ),
            )

            val store = RoomPlayerDeviceSettingsStore(dao = dao, scope = this)

            assertEquals(PlayerDeviceSettings(), store.settings.value)
        }

    @Test
    fun recentSearchesAreCappedPerServerAndDeduplicated() =
        runTest {
            val store = RoomRecentSearchStore(dao)

            (1..10).forEach { index ->
                store.add(serverId = "server-1", query = " query $index ")
            }
            store.add(serverId = "server-1", query = "QUERY 9")
            store.add(serverId = "server-2", query = "other")

            assertEquals(
                listOf("QUERY 9", "query 10", "query 8", "query 7", "query 6", "query 5", "query 4", "query 3"),
                store.list("server-1"),
            )
            assertEquals(listOf("other"), store.list("server-2"))
        }

    @Test
    fun recentSearchOrderingSurvivesStoreRecreation() =
        runTest {
            RoomRecentSearchStore(dao).add(serverId = "server-1", query = "older")

            val recreatedStore = RoomRecentSearchStore(dao)
            recreatedStore.add(serverId = "server-1", query = "newer")

            assertEquals(listOf("newer", "older"), recreatedStore.list("server-1"))
        }

    @Test
    fun registryClearServerScopedClearsAllStores() =
        runTest {
            val registry = ServerScopedStoreRegistry()
            val preferencesStore =
                RoomPlaybackPreferencesStore(dao).also { store ->
                    registry.register(store)
                }
            val recentSearchStore =
                RoomRecentSearchStore(dao).also { store ->
                    registry.register(store)
                }
            val watchNextSyncStore =
                RoomWatchNextSyncStore(dao).also { store ->
                    registry.register(store)
                }

            preferencesStore.save(serverId = "server-1", preferences = PlaybackPreferences(defaultMaxBitrateBps = 1L))
            recentSearchStore.add(serverId = "server-1", query = "matrix")
            watchNextSyncStore.upsert(
                serverId = "server-1",
                state =
                    WatchNextSyncState(
                        itemId = "item-1",
                        playbackPositionTicks = 1L,
                        played = false,
                        lastSyncedAtEpochMs = 1L,
                    ),
            )

            registry.clearAll()

            assertEquals(PlaybackPreferences(), preferencesStore.get("server-1"))
            assertEquals(emptyList(), recentSearchStore.list("server-1"))
            assertNull(watchNextSyncStore.get(serverId = "server-1", itemId = "item-1"))
        }

    @Test
    fun subtitleSelectionsAreIsolatedByServerUserItemAndSourceAndClearedOnLogout() =
        runTest {
            val store = RoomSubtitleSelectionStore(dao)
            val selected = SubtitleSelectionKey("server-1", "user-1", "item-1", "source-1")
            val otherUser = selected.copy(userId = "user-2")
            val otherSource = selected.copy(mediaSourceId = "source-2")

            store.save(selected, SubtitleSelectionIntent.Track(4))
            store.save(otherUser, SubtitleSelectionIntent.Off)
            store.save(otherSource, SubtitleSelectionIntent.Track(7))

            assertEquals(SubtitleSelectionIntent.Track(4), store.get(selected))
            assertEquals(SubtitleSelectionIntent.Off, store.get(otherUser))
            assertEquals(SubtitleSelectionIntent.Track(7), store.get(otherSource))

            store.clearServerScoped("server-1")
            assertNull(store.get(selected))
            assertNull(store.get(otherUser))
            assertNull(store.get(otherSource))
        }

    @Test
    fun getForItemsBatchesSelectionsKeyedByItemAndSourceScopedToServerAndUser() =
        runTest {
            val store = RoomSubtitleSelectionStore(dao)
            store.save(SubtitleSelectionKey("server-1", "user-1", "item-1", "source-1"), SubtitleSelectionIntent.Track(4))
            store.save(SubtitleSelectionKey("server-1", "user-1", "item-2", "source-1"), SubtitleSelectionIntent.Off)
            // Other server / other user rows must not leak into the batch.
            store.save(SubtitleSelectionKey("server-2", "user-1", "item-1", "source-1"), SubtitleSelectionIntent.Track(9))
            store.save(SubtitleSelectionKey("server-1", "user-2", "item-1", "source-1"), SubtitleSelectionIntent.Track(1))

            val result =
                store.getForItems(
                    serverId = "server-1",
                    userId = "user-1",
                    itemIds = listOf("item-1", "item-2", "item-missing"),
                )

            assertEquals(2, result.size)
            assertEquals(SubtitleSelectionIntent.Track(4), result["item-1" to "source-1"])
            assertEquals(SubtitleSelectionIntent.Off, result["item-2" to "source-1"])
            assertNull(result["item-missing" to "source-1"])
        }

    @Test
    fun invalidStoredTrackIsDeleted() =
        runTest {
            val key = SubtitleSelectionKey("server-1", "user-1", "item-1", "source-1")
            dao.upsertSubtitleSelection(
                SubtitleSelectionEntity(
                    serverId = key.serverId,
                    userId = key.userId,
                    itemId = key.itemId,
                    mediaSourceId = key.mediaSourceId,
                    selectionType = "Track",
                    streamIndex = -3,
                ),
            )

            assertNull(RoomSubtitleSelectionStore(dao).get(key))
            assertNull(dao.subtitleSelection(key.serverId, key.userId, key.itemId, key.mediaSourceId))
        }

    @Test
    fun localSubtitleAssetsRoundTripAndSurviveServerLogoutCleanup() =
        runTest {
            val context = LocalSubtitleContext("server-1", "user-1", "item-1", "source-1")
            val asset = localSubtitleAsset(context)
            val assetStore = RoomLocalSubtitleAssetStore(dao)
            val selectionStore = RoomSubtitleSelectionStore(dao)
            val key = SubtitleSelectionKey(context.serverId, context.userId, context.itemId, context.mediaSourceId)

            assetStore.upsert(asset)
            selectionStore.save(key, SubtitleSelectionIntent.LocalAsset(asset.id))
            selectionStore.clearServerScoped(context.serverId)

            assertEquals(asset, assetStore.get(asset.id))
            assertEquals(listOf(asset), assetStore.observe(context).first())
            assertEquals(SubtitleSelectionIntent.LocalAsset(asset.id), selectionStore.get(key))

            selectionStore.clearLocalAssetSelections()
            assetStore.clearAll()
            assertNull(selectionStore.get(key))
            assertNull(assetStore.get(asset.id))
        }

    @Test
    fun freshV1OpenExposesAllCurrentTables() =
        runTest {
            val context = LocalSubtitleContext("server-1", "user-1", "item-1", "source-1")
            val asset = localSubtitleAsset(context)
            dao.upsertPlaybackPreferences(
                PlaybackPreferencesEntity(
                    serverId = context.serverId,
                    defaultPlayerBackend = PlayerBackend.Auto.name,
                    defaultMaxBitrateBps = 8_000_000L,
                    preferredAudioLanguage = "eng",
                    preferredSubtitleLanguage = "spa",
                ),
            )
            dao.upsertPlayerBackendOverride(PlayerBackendOverrideEntity("server-1", "item-1", PlayerBackend.VlcKit.name))
            dao.upsertRecentSearch(RecentSearchEntity("server-1", "user-1", "query", "Query", 1L))
            dao.upsertWatchNextSync(WatchNextSyncEntity("server-1", "user-1", "item-1", 12L, false, 1L))
            dao.upsertPlayerDeviceSettings(
                PlayerDeviceSettingsEntity(
                    id = PLAYER_DEVICE_SETTINGS_ID,
                    audioMode = PlayerAudioMode.PassthroughWhenSupported.name,
                    hdrMode = PlayerHdrMode.PreferSdr.name,
                ),
            )
            dao.upsertSubtitleSelection(
                SubtitleSelectionEntity(
                    serverId = context.serverId,
                    userId = context.userId,
                    itemId = context.itemId,
                    mediaSourceId = context.mediaSourceId,
                    selectionType = "Track",
                    streamIndex = 4,
                ),
            )
            RoomLocalSubtitleAssetStore(dao).upsert(asset)

            assertEquals(8_000_000L, dao.playbackPreferences("server-1")?.defaultMaxBitrateBps)
            assertEquals(PlayerBackend.Auto.name, dao.playbackPreferences("server-1")?.defaultPlayerBackend)
            assertEquals(PlayerBackend.VlcKit.name, dao.playerBackendOverride("server-1", "item-1")?.backend)
            assertEquals("Query", dao.recentSearches("server-1", "user-1", 1).single().displayQuery)
            assertEquals("item-1", dao.watchNextSync("server-1", "user-1", "item-1")?.itemId)
            assertEquals(
                PlayerAudioMode.PassthroughWhenSupported.name,
                dao.playerDeviceSettings(PLAYER_DEVICE_SETTINGS_ID)?.audioMode,
            )
            assertEquals(
                SubtitleSelectionIntent.Track(4),
                RoomSubtitleSelectionStore(dao).get(
                    SubtitleSelectionKey("server-1", "user-1", "item-1", "source-1"),
                ),
            )
            assertEquals(asset, RoomLocalSubtitleAssetStore(dao).get(asset.id))
        }

    @Test
    fun identityHashMatchesGeneratedSchema() {
        // The Room test task's working dir is the module dir; fall back to the
        // repo-root-relative path so the test is robust to either.
        assertEquals(10, JELLYFIN_STORE_SCHEMA_VERSION)
        val relative =
            "schemas/com.jellyscope.core.data.local.JellyfinStoreDatabase/" +
                "$JELLYFIN_STORE_SCHEMA_VERSION.json"
        val schema =
            listOf(java.io.File(relative), java.io.File("shared-core/$relative"))
                .first { file -> file.exists() }
        val identityHash = Regex("\"identityHash\"\\s*:\\s*\"([^\"]+)\"").find(schema.readText())?.groupValues?.get(1)

        assertEquals(identityHash, JELLYFIN_STORE_IDENTITY_HASH)
    }
}

private fun localSubtitleAsset(context: LocalSubtitleContext) =
    LocalSubtitleAsset(
        id = "asset-1",
        serverId = context.serverId,
        userId = context.userId,
        itemId = context.itemId,
        mediaSourceId = context.mediaSourceId,
        provider = "OpenSubtitles",
        providerSubtitleId = "subtitle-1",
        providerFileId = "file-1",
        language = "en",
        label = "English",
        releaseName = "Movie.1080p",
        originalFormat = "srt",
        mimeType = "text/vtt",
        fileId = "asset-1.vtt",
        hearingImpaired = false,
        forced = false,
        trusted = true,
        createdAtEpochMs = 1L,
        lastUsedAtEpochMs = 1L,
        syncState = LocalSubtitleSyncState.Pending,
    )
