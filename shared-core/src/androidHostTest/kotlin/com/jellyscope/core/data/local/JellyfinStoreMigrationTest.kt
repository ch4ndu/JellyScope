// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Schema-evolution coverage for the JellyfinStore database. The fixture builds a
 * real on-disk v3 database from the exported v3 schema, then opens it through the
 * production builder so the shipped migrations and the downgrade driver run
 * exactly as they do on a device.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinStoreMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseFile = File.createTempFile("jellyfin-store-migration", ".db").also { it.delete() }
    private var database: JellyfinStoreDatabase? = null

    @AfterTest
    fun tearDown() {
        database?.close()
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
    }

    @Test
    fun migratingFromVersionThreePreservesPreferencesAndDefaultsStillWatchingOn() =
        runTest {
            withV3Connection { connection ->
                connection.execSQL(
                    "INSERT INTO `player_device_settings` (`id`, `audioMode`, `hdrMode`, `matchDisplayRefreshRate`) " +
                        "VALUES ('global', 'Auto', 'Auto', 0)",
                )
                connection.execSQL(
                    "INSERT INTO `playback_preferences` (`serverId`, `defaultPlayerBackend`, `defaultMaxBitrateBps`, " +
                        "`preferredAudioLanguage`, `preferredSubtitleLanguage`, `resumeBehavior`, `autoPlayNext`, " +
                        "`autoPlayNextDelaySeconds`, `introSkip`, `outroSkip`, `recapSkip`, `previewSkip`, `commercialSkip`) " +
                        "VALUES ('server-1', 'LibVlc', 8000000, 'eng', 'spa', 'Restart', 0, 30, " +
                        "'AutoSkip', 'Ask', 'Ignore', 'Ask', 'Ask')",
                )
                connection.execSQL(
                    "INSERT INTO `playback_preferences` (`serverId`, `defaultPlayerBackend`, `defaultMaxBitrateBps`, " +
                        "`preferredAudioLanguage`, `preferredSubtitleLanguage`, `resumeBehavior`, `autoPlayNext`, " +
                        "`autoPlayNextDelaySeconds`, `introSkip`, `outroSkip`, `recapSkip`, `previewSkip`, `commercialSkip`) " +
                        "VALUES ('server-2', NULL, NULL, NULL, NULL, 'Resume', 1, 10, NULL, NULL, NULL, NULL, NULL)",
                )
            }

            val store = RoomPlaybackPreferencesStore(openProductionDatabase().jellyfinStoreDao())

            val first = store.get("server-1")
            assertEquals(8_000_000L, first.defaultMaxBitrateBps)
            assertEquals(PlaybackQualityMode.Fixed, first.defaultQualityPolicy.mode)
            assertEquals(8_000_000L, first.defaultQualityPolicy.maxBitrateBps)
            assertEquals(null, first.vlcTranscodeMaxBitrateBps)
            assertEquals("eng", first.preferredAudioLanguage)
            assertEquals("spa", first.preferredSubtitleLanguage)
            assertEquals(false, first.autoPlayNext)
            assertEquals(30, first.autoPlayNextDelaySeconds)
            // The dropped Resume choice must not resurface as a behavior change, and
            // the new column defaults to the previous always-prompt behavior.
            assertTrue(first.stillWatchingPrompt)
            assertTrue(store.get("server-2").stillWatchingPrompt)
            readOnlyConnection { connection ->
                connection.prepare("SELECT maxVideoResolution FROM player_device_settings WHERE id = 'global'").use { statement ->
                    assertTrue(statement.step())
                    assertEquals("Unlimited", statement.getText(0))
                }
            }
        }

    @Test
    fun migratingLegacyQualityRowsPreservesAudioButIgnoresRetiredQualityValues() =
        runTest {
            withV3Connection { connection ->
                connection.execSQL(
                    "INSERT INTO `playback_preferences` (`serverId`, `defaultPlayerBackend`, `defaultMaxBitrateBps`, " +
                        "`preferredAudioLanguage`, `preferredSubtitleLanguage`, `resumeBehavior`, `autoPlayNext`, " +
                        "`autoPlayNextDelaySeconds`, `introSkip`, `outroSkip`, `recapSkip`, `previewSkip`, `commercialSkip`) " +
                        "VALUES ('server-1', 'LibVlc', NULL, NULL, NULL, 'Resume', 1, 10, NULL, NULL, NULL, NULL, NULL)",
                )
                connection.execSQL(
                    "INSERT INTO `playback_selections` (`serverId`, `userId`, `itemId`, `mediaSourceId`, " +
                        "`audioStreamIndex`, `maxStreamingBitrateBps`, `schemaVersion`, `qualityIsOriginal`) " +
                        "VALUES ('server-1', 'user-1', 'item-original', 'source-1', 2, NULL, 1, 1)",
                )
                connection.execSQL(
                    "INSERT INTO `playback_selections` (`serverId`, `userId`, `itemId`, `mediaSourceId`, " +
                        "`audioStreamIndex`, `maxStreamingBitrateBps`, `schemaVersion`, `qualityIsOriginal`) " +
                        "VALUES ('server-1', 'user-1', 'item-fixed', 'source-1', 3, 10000001, 1, 1)",
                )
            }

            val opened = openProductionDatabase()
            val selectionStore = RoomPlaybackSelectionStore(opened.jellyfinStoreDao())
            val original = selectionStore.get(PlaybackSelectionKey("server-1", "user-1", "item-original", "source-1"))
            val fixed = selectionStore.get(PlaybackSelectionKey("server-1", "user-1", "item-fixed", "source-1"))

            assertEquals(2, original?.audioStreamIndex)
            assertEquals(3, fixed?.audioStreamIndex)
        }

    @Test
    fun migratingFromVersionThreeDropsTheResumeBehaviorColumn() =
        runTest {
            withV3Connection { }

            openProductionDatabase()

            val columns = mutableListOf<String>()
            readOnlyConnection { connection ->
                connection.prepare("SELECT name FROM pragma_table_info('playback_preferences')").use { statement ->
                    while (statement.step()) {
                        columns += statement.getText(0)
                    }
                }
            }
            assertFalse(columns.contains("resumeBehavior"))
            assertTrue(columns.contains("stillWatchingPrompt"))
            assertTrue(columns.contains("vlcTranscodeMaxBitrateBps"))
        }

    @Test
    fun migratingHistoricalVersionFourDropsRetiredDesktopControlsAndAddsResolution() =
        runTest {
            withV3Connection { }
            openProductionDatabase().close()
            database = null
            writableConnection { connection ->
                // Recreate the preference table at its historical v4 shape so
                // the later 5-to-6 migration owns adding the VLC budget column.
                connection.execSQL("DROP TABLE `playback_preferences`")
                connection.execSQL(
                    "CREATE TABLE `playback_preferences` (`serverId` TEXT NOT NULL, " +
                        "`defaultPlayerBackend` TEXT DEFAULT 'Auto', `defaultMaxBitrateBps` INTEGER, " +
                        "`preferredAudioLanguage` TEXT, `preferredSubtitleLanguage` TEXT, " +
                        "`autoPlayNext` INTEGER NOT NULL DEFAULT 1, `stillWatchingPrompt` INTEGER NOT NULL DEFAULT 1, " +
                        "`autoPlayNextDelaySeconds` INTEGER NOT NULL DEFAULT 10, `introSkip` TEXT, `outroSkip` TEXT, " +
                        "`recapSkip` TEXT, `previewSkip` TEXT, `commercialSkip` TEXT, PRIMARY KEY(`serverId`))",
                )
                connection.execSQL("DROP TABLE `playback_selections`")
                connection.execSQL(
                    "CREATE TABLE `playback_selections` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, `mediaSourceId` TEXT NOT NULL, `audioStreamIndex` INTEGER, " +
                        "`maxStreamingBitrateBps` INTEGER, `schemaVersion` INTEGER NOT NULL DEFAULT 1, " +
                        "`qualityIsOriginal` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`serverId`, `userId`, `itemId`, `mediaSourceId`))",
                )
                connection.execSQL(
                    "CREATE INDEX `index_playback_selections_serverId_userId_itemId` " +
                        "ON `playback_selections` (`serverId`, `userId`, `itemId`)",
                )
                connection.execSQL("DROP TABLE `player_device_settings`")
                connection.execSQL(
                    "CREATE TABLE `player_device_settings` (`id` TEXT NOT NULL, `audioMode` TEXT NOT NULL, " +
                        "`hdrMode` TEXT NOT NULL, `matchDisplayRefreshRate` INTEGER NOT NULL DEFAULT 0, " +
                        "`desktopInSceneControls` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                )
                connection.execSQL(
                    "INSERT INTO `player_device_settings` " +
                        "(`id`, `audioMode`, `hdrMode`, `matchDisplayRefreshRate`, `desktopInSceneControls`) " +
                        "VALUES ('global', 'Stereo', 'ForceSdr', 1, 1)",
                )
                connection.execSQL("PRAGMA user_version = 4")
            }

            openProductionDatabase()

            val columns = mutableListOf<String>()
            readOnlyConnection { connection ->
                connection.prepare("SELECT name FROM pragma_table_info('player_device_settings')").use { statement ->
                    while (statement.step()) {
                        columns += statement.getText(0)
                    }
                }
                connection
                    .prepare(
                        "SELECT audioMode, hdrMode, matchDisplayRefreshRate, maxVideoResolution, " +
                            "iosPlaybackCompatibilityMode " +
                            "FROM player_device_settings WHERE id = 'global'",
                    ).use { statement ->
                        assertTrue(statement.step())
                        assertEquals("Stereo", statement.getText(0))
                        assertEquals("ForceSdr", statement.getText(1))
                        assertEquals(1, statement.getInt(2))
                        assertEquals("Unlimited", statement.getText(3))
                        assertEquals("Standard", statement.getText(4))
                    }
            }
            assertFalse(columns.contains("desktopInSceneControls"))
            assertTrue(columns.contains("maxVideoResolution"))
            assertTrue(columns.contains("iosPlaybackCompatibilityMode"))
        }

    @Test
    fun openingANewerDatabaseRepairsItToTheCurrentSchemaWithoutLosingDurableRows() =
        runTest {
            withV3Connection { connection ->
                connection.execSQL(
                    "INSERT INTO `playback_preferences` (`serverId`, `defaultPlayerBackend`, `defaultMaxBitrateBps`, " +
                        "`preferredAudioLanguage`, `preferredSubtitleLanguage`, `resumeBehavior`, `autoPlayNext`, " +
                        "`autoPlayNextDelaySeconds`, `introSkip`, `outroSkip`, `recapSkip`, `previewSkip`, `commercialSkip`) " +
                        "VALUES ('server-1', NULL, 4000000, NULL, NULL, 'Resume', 1, 10, NULL, NULL, NULL, NULL, NULL)",
                )
            }
            // Migrate to the current version first, then pretend a newer build wrote
            // the file so the downgrade driver has to repair it.
            openProductionDatabase().close()
            database = null
            writableConnection { connection ->
                connection.execSQL(
                    "INSERT INTO `recent_searches` " +
                        "(`serverId`, `userId`, `normalizedQuery`, `displayQuery`, `updatedAtMs`) " +
                        "VALUES ('server-1', 'user-1', 'dune', 'Dune', 1)",
                )
                connection.execSQL("PRAGMA user_version = 11")
            }

            val store = RoomPlaybackPreferencesStore(openProductionDatabase().jellyfinStoreDao())

            // Durable preference rows survive the repair; refetchable caches are recreated.
            assertEquals(4_000_000L, store.get("server-1").defaultMaxBitrateBps)
            assertEquals(emptyList(), RoomRecentSearchStore(requireNotNull(database).jellyfinStoreDao()).list("server-1", "user-1"))
            readOnlyConnection { connection ->
                connection.prepare("PRAGMA user_version").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(JELLYFIN_STORE_TEST_SCHEMA_VERSION, statement.getInt(0))
                }
                connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(JELLYFIN_STORE_IDENTITY_HASH, statement.getText(0))
                }
            }
        }

    @Test
    fun migrationNineFlipsPlaybackWarningsOffAndLeavesVlcNullWithoutSeed() =
        runTest {
            withV3Connection { connection ->
                connection.execSQL(
                    "INSERT INTO `playback_preferences` (`serverId`, `defaultPlayerBackend`, `defaultMaxBitrateBps`, " +
                        "`preferredAudioLanguage`, `preferredSubtitleLanguage`, `resumeBehavior`, `autoPlayNext`, " +
                        "`autoPlayNextDelaySeconds`, `introSkip`, `outroSkip`, `recapSkip`, `previewSkip`, `commercialSkip`) " +
                        "VALUES ('server-1', NULL, NULL, NULL, NULL, 'Resume', 1, 10, NULL, NULL, NULL, NULL, NULL)",
                )
            }

            val store = RoomPlaybackPreferencesStore(openProductionDatabase().jellyfinStoreDao())

            val preferences = store.get("server-1")
            assertFalse(preferences.playbackWarningsEnabled)
            assertEquals(null, preferences.vlcTranscodeMaxBitrateBps)
        }

    @Test
    fun migrationNineSeedsVlcDefaultWhenTheBuilderPassesOne() =
        runTest {
            withV3Connection { connection ->
                connection.execSQL(
                    "INSERT INTO `playback_preferences` (`serverId`, `defaultPlayerBackend`, `defaultMaxBitrateBps`, " +
                        "`preferredAudioLanguage`, `preferredSubtitleLanguage`, `resumeBehavior`, `autoPlayNext`, " +
                        "`autoPlayNextDelaySeconds`, `introSkip`, `outroSkip`, `recapSkip`, `previewSkip`, `commercialSkip`) " +
                        "VALUES ('server-1', NULL, NULL, NULL, NULL, 'Resume', 1, 10, NULL, NULL, NULL, NULL, NULL)",
                )
            }

            val store =
                RoomPlaybackPreferencesStore(
                    openProductionDatabase(seedVlcDefaultBps = 8_000_000L).jellyfinStoreDao(),
                )

            val preferences = store.get("server-1")
            assertFalse(preferences.playbackWarningsEnabled)
            assertEquals(8_000_000L, preferences.vlcTranscodeMaxBitrateBps)
        }

    @Test
    fun vlcSeedSqlTargetsOnlyNullRowsAndWarningsFlipTargetsAll() {
        writableConnection { connection ->
            connection.execSQL(
                "CREATE TABLE `playback_preferences` (`serverId` TEXT NOT NULL PRIMARY KEY, " +
                    "`playbackWarningsEnabled` INTEGER NOT NULL DEFAULT 1, `vlcTranscodeMaxBitrateBps` INTEGER)",
            )
            connection.execSQL("INSERT INTO `playback_preferences` VALUES ('inherit', 1, NULL)")
            connection.execSQL("INSERT INTO `playback_preferences` VALUES ('explicit', 1, 12000000)")

            jellyfinStoreMigration8To9(8_000_000L).migrate(connection)

            connection
                .prepare(
                    "SELECT `playbackWarningsEnabled`, `vlcTranscodeMaxBitrateBps` FROM `playback_preferences` " +
                        "WHERE `serverId` = 'inherit'",
                ).use { statement ->
                    assertTrue(statement.step())
                    assertEquals(0, statement.getInt(0))
                    assertEquals(8_000_000L, statement.getLong(1))
                }
            connection
                .prepare(
                    "SELECT `playbackWarningsEnabled`, `vlcTranscodeMaxBitrateBps` FROM `playback_preferences` " +
                        "WHERE `serverId` = 'explicit'",
                ).use { statement ->
                    assertTrue(statement.step())
                    assertEquals(0, statement.getInt(0))
                    assertEquals(12_000_000L, statement.getLong(1))
                }
        }
    }

    private suspend fun openProductionDatabase(seedVlcDefaultBps: Long? = null): JellyfinStoreDatabase {
        val instance =
            Room
                .databaseBuilder<JellyfinStoreDatabase>(context, databaseFile.path)
                .buildJellyfinStore(driver = AndroidSQLiteDriver(), seedVlcDefaultBps = seedVlcDefaultBps)
        database = instance
        // One real read forces the connection open, so migrations and downgrade
        // repair have run before any assertion inspects the file.
        RoomPlaybackPreferencesStore(instance.jellyfinStoreDao()).get("__open_probe__")
        return instance
    }

    private fun withV3Connection(block: (SQLiteConnection) -> Unit) {
        writableConnection { connection ->
            V3_SCHEMA.forEach { statement -> connection.execSQL(statement) }
            connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            connection.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V3_IDENTITY_HASH')",
            )
            connection.execSQL("PRAGMA user_version = 3")
            block(connection)
        }
    }

    private fun writableConnection(block: (SQLiteConnection) -> Unit) {
        val connection = AndroidSQLiteDriver().open(databaseFile.path)
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun readOnlyConnection(block: (SQLiteConnection) -> Unit) {
        database?.close()
        database = null
        writableConnection(block)
    }

    private companion object {
        const val V3_IDENTITY_HASH = "d19455b8e12331ccf3a96f3b19bf2af1"
        const val JELLYFIN_STORE_TEST_SCHEMA_VERSION = 10

        val V3_SCHEMA =
            listOf(
                "CREATE TABLE IF NOT EXISTS `playback_preferences` (`serverId` TEXT NOT NULL, `defaultPlayerBackend` TEXT DEFAULT 'Auto', `defaultMaxBitrateBps` INTEGER, `preferredAudioLanguage` TEXT, `preferredSubtitleLanguage` TEXT, `resumeBehavior` TEXT NOT NULL, `autoPlayNext` INTEGER NOT NULL DEFAULT 1, `autoPlayNextDelaySeconds` INTEGER NOT NULL DEFAULT 10, `introSkip` TEXT, `outroSkip` TEXT, `recapSkip` TEXT, `previewSkip` TEXT, `commercialSkip` TEXT, PRIMARY KEY(`serverId`))",
                "CREATE TABLE IF NOT EXISTS `playback_selections` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `mediaSourceId` TEXT NOT NULL, `audioStreamIndex` INTEGER, `maxStreamingBitrateBps` INTEGER, `schemaVersion` INTEGER NOT NULL DEFAULT 1, `qualityIsOriginal` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`serverId`, `userId`, `itemId`, `mediaSourceId`))",
                "CREATE INDEX IF NOT EXISTS `index_playback_selections_serverId_userId_itemId` ON `playback_selections` (`serverId`, `userId`, `itemId`)",
                "CREATE TABLE IF NOT EXISTS `playback_timing_offsets` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `mediaSourceId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `kind` TEXT NOT NULL, `offsetMs` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`serverId`, `userId`, `itemId`, `mediaSourceId`, `trackId`, `kind`))",
                "CREATE INDEX IF NOT EXISTS `index_playback_timing_offsets_serverId_userId_itemId` ON `playback_timing_offsets` (`serverId`, `userId`, `itemId`)",
                "CREATE TABLE IF NOT EXISTS `player_backend_overrides` (`serverId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `backend` TEXT, PRIMARY KEY(`serverId`, `itemId`))",
                "CREATE TABLE IF NOT EXISTS `recent_searches` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `normalizedQuery` TEXT NOT NULL, `displayQuery` TEXT NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `normalizedQuery`))",
                "CREATE TABLE IF NOT EXISTS `watch_next_sync` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `playbackPositionTicks` INTEGER, `played` INTEGER NOT NULL, `lastSyncedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `itemId`))",
                "CREATE TABLE IF NOT EXISTS `player_device_settings` (`id` TEXT NOT NULL, `audioMode` TEXT NOT NULL, `hdrMode` TEXT NOT NULL, `matchDisplayRefreshRate` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `subtitle_selections` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `mediaSourceId` TEXT NOT NULL, `selectionType` TEXT NOT NULL, `streamIndex` INTEGER, `localAssetId` TEXT, PRIMARY KEY(`serverId`, `userId`, `itemId`, `mediaSourceId`))",
                "CREATE TABLE IF NOT EXISTS `local_subtitle_assets` (`id` TEXT NOT NULL, `serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `mediaSourceId` TEXT NOT NULL, `provider` TEXT NOT NULL, `providerSubtitleId` TEXT NOT NULL, `providerFileId` TEXT NOT NULL, `language` TEXT NOT NULL, `label` TEXT NOT NULL, `releaseName` TEXT, `originalFormat` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `fileId` TEXT NOT NULL, `hearingImpaired` INTEGER NOT NULL, `forced` INTEGER NOT NULL, `trusted` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `lastUsedAtEpochMs` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `confirmedStreamIndex` INTEGER, `uploadBaseline` TEXT, PRIMARY KEY(`id`))",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_subtitle_assets_serverId_userId_itemId_mediaSourceId_provider_providerFileId` ON `local_subtitle_assets` (`serverId`, `userId`, `itemId`, `mediaSourceId`, `provider`, `providerFileId`)",
            )
    }
}
