// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.PLAYBACK_SELECTION_SCHEMA_VERSION
import com.jellyscope.core.domain.model.PLAYBACK_TIMING_SCHEMA_VERSION
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.model.PlaybackTimingOffset
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LEGACY_PLAYBACK_PREFERENCES_USER_ID = ""

@Entity(
    tableName = "playback_preferences",
    primaryKeys = ["serverId", "userId"],
)
internal data class PlaybackPreferencesEntity(
    val serverId: String,
    val userId: String,
    @ColumnInfo(defaultValue = "Auto")
    val defaultPlayerBackend: String? = PlayerBackend.Auto.name,
    val defaultMaxBitrateBps: Long?,
    val vlcTranscodeMaxBitrateBps: Long? = null,
    val preferredAudioLanguage: String?,
    val preferredSubtitleLanguage: String?,
    @ColumnInfo(defaultValue = "1")
    val autoPlayNext: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val stillWatchingPrompt: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val playbackWarningsEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val allowInsecureDesktopTls: Boolean = false,
    @ColumnInfo(defaultValue = "10")
    val autoPlayNextDelaySeconds: Int = 10,
    val introSkip: String? = null,
    val outroSkip: String? = null,
    val recapSkip: String? = null,
    val previewSkip: String? = null,
    val commercialSkip: String? = null,
    @ColumnInfo(defaultValue = "Auto")
    val defaultQualityMode: String? = PlaybackQualityMode.Auto.name,
    val defaultQualityBitrateBps: Long? = null,
)

@Entity(
    tableName = "playback_selections",
    primaryKeys = ["serverId", "userId", "itemId", "mediaSourceId"],
    indices = [Index(value = ["serverId", "userId", "itemId"])],
)
internal data class PlaybackSelectionEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val audioStreamIndex: Int?,
    val maxStreamingBitrateBps: Long?,
    @ColumnInfo(defaultValue = "1")
    val schemaVersion: Int = PLAYBACK_SELECTION_SCHEMA_VERSION,
    @ColumnInfo(defaultValue = "0")
    val qualityIsOriginal: Boolean = false,
    val qualityMode: String? = null,
    val qualityBitrateBps: Long? = null,
)

@Entity(
    tableName = "playback_timing_offsets",
    primaryKeys = ["serverId", "userId", "itemId", "mediaSourceId", "trackId", "kind"],
    indices = [Index(value = ["serverId", "userId", "itemId"])],
)
internal data class PlaybackTimingOffsetEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val trackId: String,
    val kind: String,
    val offsetMs: Long,
    @ColumnInfo(defaultValue = "1")
    val schemaVersion: Int = PLAYBACK_TIMING_SCHEMA_VERSION,
)

@Entity(
    tableName = "player_backend_overrides",
    primaryKeys = ["serverId", "itemId"],
)
internal data class PlayerBackendOverrideEntity(
    val serverId: String,
    val itemId: String,
    val backend: String?,
)

@Entity(
    tableName = "recent_searches",
    primaryKeys = ["serverId", "userId", "normalizedQuery"],
)
internal data class RecentSearchEntity(
    val serverId: String,
    val userId: String,
    val normalizedQuery: String,
    val displayQuery: String,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "watch_next_sync",
    primaryKeys = ["serverId", "userId", "itemId"],
)
internal data class WatchNextSyncEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val playbackPositionTicks: Long?,
    val played: Boolean,
    val lastSyncedAtEpochMs: Long,
)

@Entity(
    tableName = "player_device_settings",
    primaryKeys = ["id"],
)
internal data class PlayerDeviceSettingsEntity(
    val id: String,
    val audioMode: String,
    val hdrMode: String,
    @ColumnInfo(defaultValue = "0")
    val matchDisplayRefreshRate: Boolean = false,
    @ColumnInfo(defaultValue = "'Unlimited'")
    val maxVideoResolution: String = PlayerVideoResolutionLimit.Unlimited.name,
    @ColumnInfo(defaultValue = "'Standard'")
    val iosPlaybackCompatibilityMode: String = IosPlaybackCompatibilityMode.Standard.name,
)

@Entity(
    tableName = "subtitle_selections",
    primaryKeys = ["serverId", "userId", "itemId", "mediaSourceId"],
)
internal data class SubtitleSelectionEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val selectionType: String,
    val streamIndex: Int?,
    val localAssetId: String? = null,
)

@Entity(
    tableName = "local_subtitle_assets",
    indices = [
        Index(
            value = ["serverId", "userId", "itemId", "mediaSourceId", "provider", "providerFileId"],
            unique = true,
        ),
    ],
)
internal data class LocalSubtitleAssetEntity(
    @androidx.room.PrimaryKey val id: String,
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val provider: String,
    val providerSubtitleId: String,
    val providerFileId: String,
    val language: String,
    val label: String,
    val releaseName: String?,
    val originalFormat: String,
    val mimeType: String,
    val fileId: String,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val trusted: Boolean,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
    val syncState: String,
    val confirmedStreamIndex: Int?,
    val uploadBaseline: String?,
)

@Dao
internal interface JellyfinStoreDao {
    @Query("SELECT * FROM playback_preferences WHERE serverId = :serverId AND userId = :userId LIMIT 1")
    suspend fun playbackPreferences(
        serverId: String,
        userId: String,
    ): PlaybackPreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackPreferences(entity: PlaybackPreferencesEntity)

    @Query("DELETE FROM playback_preferences WHERE serverId = :serverId AND userId = :userId")
    suspend fun clearPlaybackPreferences(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM playback_preferences WHERE serverId = :serverId")
    suspend fun clearPlaybackPreferencesForServer(serverId: String)

    @Query("DELETE FROM playback_preferences")
    suspend fun clearAllPlaybackPreferences()

    @Transaction
    suspend fun playbackPreferencesForAccount(accountIdentity: AccountIdentity): PlaybackPreferencesEntity? {
        playbackPreferences(accountIdentity.serverId, accountIdentity.userId)?.let { return it }
        // This transaction permits exactly one authenticated account to claim a legacy server row.
        val legacy = playbackPreferences(accountIdentity.serverId, LEGACY_PLAYBACK_PREFERENCES_USER_ID) ?: return null
        val claimed = legacy.copy(userId = accountIdentity.userId)
        upsertPlaybackPreferences(claimed)
        clearPlaybackPreferences(accountIdentity.serverId, LEGACY_PLAYBACK_PREFERENCES_USER_ID)
        return claimed
    }

    @Query(
        "SELECT * FROM playback_selections WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId = :itemId AND mediaSourceId = :mediaSourceId LIMIT 1",
    )
    suspend fun playbackSelection(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
    ): PlaybackSelectionEntity?

    @Query(
        "SELECT * FROM playback_selections WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId IN (:itemIds)",
    )
    suspend fun playbackSelectionsForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): List<PlaybackSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackSelection(entity: PlaybackSelectionEntity)

    @Query(
        "DELETE FROM playback_selections WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId = :itemId AND mediaSourceId = :mediaSourceId",
    )
    suspend fun deletePlaybackSelection(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
    )

    @Query("DELETE FROM playback_selections WHERE serverId = :serverId")
    suspend fun clearPlaybackSelectionsForServer(serverId: String)

    @Query("DELETE FROM playback_selections WHERE serverId = :serverId AND userId = :userId")
    suspend fun clearPlaybackSelectionsForAccount(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM playback_selections")
    suspend fun clearAllPlaybackSelections()

    @Query(
        "SELECT * FROM playback_timing_offsets WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId = :itemId AND mediaSourceId = :mediaSourceId AND trackId = :trackId " +
            "AND kind = :kind LIMIT 1",
    )
    suspend fun playbackTimingOffset(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
        trackId: String,
        kind: String,
    ): PlaybackTimingOffsetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackTimingOffset(entity: PlaybackTimingOffsetEntity)

    @Query(
        "DELETE FROM playback_timing_offsets WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId = :itemId AND mediaSourceId = :mediaSourceId AND trackId = :trackId " +
            "AND kind = :kind",
    )
    suspend fun deletePlaybackTimingOffset(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
        trackId: String,
        kind: String,
    )

    @Query("DELETE FROM playback_timing_offsets WHERE serverId = :serverId")
    suspend fun clearPlaybackTimingOffsetsForServer(serverId: String)

    @Query("DELETE FROM playback_timing_offsets WHERE serverId = :serverId AND userId = :userId")
    suspend fun clearPlaybackTimingOffsetsForAccount(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM playback_timing_offsets")
    suspend fun clearAllPlaybackTimingOffsets()

    @Query("SELECT * FROM player_backend_overrides WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun playerBackendOverride(
        serverId: String,
        itemId: String,
    ): PlayerBackendOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayerBackendOverride(entity: PlayerBackendOverrideEntity)

    @Query("DELETE FROM player_backend_overrides WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun deletePlayerBackendOverride(
        serverId: String,
        itemId: String,
    )

    @Query("DELETE FROM player_backend_overrides WHERE serverId = :serverId")
    suspend fun clearPlayerBackendOverridesForServer(serverId: String)

    @Query("DELETE FROM player_backend_overrides")
    suspend fun clearAllPlayerBackendOverrides()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecentSearch(entity: RecentSearchEntity)

    @Query("SELECT COALESCE(MAX(updatedAtMs), 0) FROM recent_searches WHERE serverId = :serverId AND userId = :userId")
    suspend fun latestRecentSearchOrder(
        serverId: String,
        userId: String,
    ): Long

    @Query("SELECT * FROM recent_searches WHERE serverId = :serverId AND userId = :userId ORDER BY updatedAtMs DESC LIMIT :limit")
    suspend fun recentSearches(
        serverId: String,
        userId: String,
        limit: Int,
    ): List<RecentSearchEntity>

    @Query(
        """
        DELETE FROM recent_searches
        WHERE serverId = :serverId AND userId = :userId
        AND normalizedQuery NOT IN (
            SELECT normalizedQuery FROM recent_searches
            WHERE serverId = :serverId AND userId = :userId
            ORDER BY updatedAtMs DESC
            LIMIT :limit
        )
        """,
    )
    suspend fun trimRecentSearches(
        serverId: String,
        userId: String,
        limit: Int,
    )

    @Transaction
    suspend fun addRecentSearch(
        serverId: String,
        userId: String,
        normalizedQuery: String,
        displayQuery: String,
        limit: Int,
    ) {
        // The legacy column name remains part of the durable per-server ordering
        // contract; it stores a sequence, not wall-clock time.
        val nextOrder = latestRecentSearchOrder(serverId, userId) + 1L
        upsertRecentSearch(
            RecentSearchEntity(
                serverId = serverId,
                userId = userId,
                normalizedQuery = normalizedQuery,
                displayQuery = displayQuery,
                updatedAtMs = nextOrder,
            ),
        )
        trimRecentSearches(serverId = serverId, userId = userId, limit = limit)
    }

    @Query("DELETE FROM recent_searches WHERE serverId = :serverId AND userId = :userId")
    suspend fun clearRecentSearches(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM recent_searches WHERE serverId = :serverId")
    suspend fun clearRecentSearchesForServer(serverId: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearAllRecentSearches()

    @Query("SELECT * FROM watch_next_sync WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId LIMIT 1")
    suspend fun watchNextSync(
        serverId: String,
        userId: String,
        itemId: String,
    ): WatchNextSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchNextSync(entity: WatchNextSyncEntity)

    @Query("SELECT * FROM watch_next_sync WHERE serverId = :serverId AND userId = :userId ORDER BY lastSyncedAtEpochMs DESC")
    suspend fun watchNextSyncStates(
        serverId: String,
        userId: String,
    ): List<WatchNextSyncEntity>

    @Query("DELETE FROM watch_next_sync WHERE serverId = :serverId AND userId = :userId")
    suspend fun clearWatchNextSync(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM watch_next_sync WHERE serverId = :serverId")
    suspend fun clearWatchNextSyncForServer(serverId: String)

    @Query("DELETE FROM watch_next_sync")
    suspend fun clearAllWatchNextSync()

    @Query("SELECT * FROM player_device_settings WHERE id = :id LIMIT 1")
    suspend fun playerDeviceSettings(id: String): PlayerDeviceSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayerDeviceSettings(entity: PlayerDeviceSettingsEntity)

    @Query("DELETE FROM player_device_settings WHERE id = :id")
    suspend fun clearPlayerDeviceSettings(id: String)

    @Query(
        """
        SELECT * FROM subtitle_selections
        WHERE serverId = :serverId AND userId = :userId
        AND itemId = :itemId AND mediaSourceId = :mediaSourceId
        LIMIT 1
        """,
    )
    suspend fun subtitleSelection(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
    ): SubtitleSelectionEntity?

    // Bulk read for a set of items in one round trip (callers chunk itemIds to a
    // safe size). Avoids N sequential single-key reads when pre-seeding a whole
    // season's episode strip.
    @Query(
        """
        SELECT * FROM subtitle_selections
        WHERE serverId = :serverId AND userId = :userId
        AND itemId IN (:itemIds)
        """,
    )
    suspend fun subtitleSelectionsForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): List<SubtitleSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubtitleSelection(entity: SubtitleSelectionEntity)

    @Query(
        """
        DELETE FROM subtitle_selections
        WHERE serverId = :serverId AND userId = :userId
        AND itemId = :itemId AND mediaSourceId = :mediaSourceId
        """,
    )
    suspend fun deleteSubtitleSelection(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
    )

    @Query("DELETE FROM subtitle_selections WHERE serverId = :serverId")
    suspend fun clearSubtitleSelections(serverId: String)

    @Query("DELETE FROM subtitle_selections")
    suspend fun clearAllSubtitleSelections()

    @Query(
        "SELECT * FROM local_subtitle_assets WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId = :itemId AND mediaSourceId = :mediaSourceId ORDER BY createdAtEpochMs DESC",
    )
    fun observeLocalSubtitleAssets(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
    ): Flow<List<LocalSubtitleAssetEntity>>

    @Query("SELECT * FROM local_subtitle_assets WHERE syncState IN ('Pending', 'Uploading', 'Reconciling') ORDER BY createdAtEpochMs")
    fun observePendingLocalSubtitleAssets(): Flow<List<LocalSubtitleAssetEntity>>

    @Query("SELECT * FROM local_subtitle_assets WHERE id = :assetId LIMIT 1")
    suspend fun localSubtitleAsset(assetId: String): LocalSubtitleAssetEntity?

    @Query(
        "SELECT * FROM local_subtitle_assets WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId = :itemId AND mediaSourceId = :mediaSourceId AND provider = :provider " +
            "AND providerFileId = :providerFileId LIMIT 1",
    )
    suspend fun localSubtitleAssetByProviderFile(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalSubtitleAsset(entity: LocalSubtitleAssetEntity)

    @Query("DELETE FROM local_subtitle_assets WHERE id = :assetId")
    suspend fun deleteLocalSubtitleAsset(assetId: String)

    @Query("SELECT * FROM local_subtitle_assets")
    suspend fun allLocalSubtitleAssets(): List<LocalSubtitleAssetEntity>

    @Query("DELETE FROM local_subtitle_assets")
    suspend fun clearAllLocalSubtitleAssets()

    @Query("DELETE FROM subtitle_selections WHERE selectionType != 'LocalAsset' AND serverId = :serverId")
    suspend fun clearNonLocalSubtitleSelections(serverId: String)

    @Query("DELETE FROM subtitle_selections WHERE selectionType != 'LocalAsset' AND serverId = :serverId AND userId = :userId")
    suspend fun clearNonLocalSubtitleSelectionsForAccount(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM subtitle_selections WHERE selectionType != 'LocalAsset'")
    suspend fun clearAllNonLocalSubtitleSelections()

    @Query("DELETE FROM subtitle_selections WHERE selectionType = 'LocalAsset'")
    suspend fun clearAllLocalAssetSubtitleSelections()
}

@Database(
    entities = [
        PlaybackPreferencesEntity::class,
        PlaybackSelectionEntity::class,
        PlaybackTimingOffsetEntity::class,
        PlayerBackendOverrideEntity::class,
        RecentSearchEntity::class,
        WatchNextSyncEntity::class,
        PlayerDeviceSettingsEntity::class,
        SubtitleSelectionEntity::class,
        LocalSubtitleAssetEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
@ConstructedBy(JellyfinStoreDatabaseConstructor::class)
internal abstract class JellyfinStoreDatabase : RoomDatabase() {
    abstract fun jellyfinStoreDao(): JellyfinStoreDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object JellyfinStoreDatabaseConstructor : RoomDatabaseConstructor<JellyfinStoreDatabase> {
    override fun initialize(): JellyfinStoreDatabase
}

internal const val JELLYFIN_STORE_SCHEMA_VERSION = 11

// Copied from the exported schema (schemas/.../11.json).
internal const val JELLYFIN_STORE_IDENTITY_HASH = "1425a4f44d81f644419dd22d5806242a"

private val JELLYFIN_STORE_MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `playback_preferences` ADD COLUMN `autoPlayNext` INTEGER NOT NULL DEFAULT 1")
            connection.execSQL(
                "ALTER TABLE `playback_preferences` ADD COLUMN `autoPlayNextDelaySeconds` INTEGER NOT NULL DEFAULT 10",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playback_selections` (
                    `serverId` TEXT NOT NULL,
                    `userId` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `mediaSourceId` TEXT NOT NULL,
                    `audioStreamIndex` INTEGER,
                    `maxStreamingBitrateBps` INTEGER,
                    `schemaVersion` INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(`serverId`, `userId`, `itemId`, `mediaSourceId`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_selections_serverId_userId_itemId` " +
                    "ON `playback_selections` (`serverId`, `userId`, `itemId`)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playback_timing_offsets` (
                    `serverId` TEXT NOT NULL,
                    `userId` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `mediaSourceId` TEXT NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `offsetMs` INTEGER NOT NULL,
                    `schemaVersion` INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(`serverId`, `userId`, `itemId`, `mediaSourceId`, `trackId`, `kind`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_timing_offsets_serverId_userId_itemId` " +
                    "ON `playback_timing_offsets` (`serverId`, `userId`, `itemId`)",
            )
        }
    }

private val JELLYFIN_STORE_MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            // Distinguishes an explicit "Original" quality pick (null bitrate +
            // flag) from "nothing remembered"; existing rows default to false.
            connection.execSQL(
                "ALTER TABLE `playback_selections` ADD COLUMN `qualityIsOriginal` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

private val JELLYFIN_STORE_MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE `playback_preferences_new` (
                    `serverId` TEXT NOT NULL,
                    `defaultPlayerBackend` TEXT DEFAULT 'Auto',
                    `defaultMaxBitrateBps` INTEGER,
                    `preferredAudioLanguage` TEXT,
                    `preferredSubtitleLanguage` TEXT,
                    `autoPlayNext` INTEGER NOT NULL DEFAULT 1,
                    `stillWatchingPrompt` INTEGER NOT NULL DEFAULT 1,
                    `autoPlayNextDelaySeconds` INTEGER NOT NULL DEFAULT 10,
                    `introSkip` TEXT,
                    `outroSkip` TEXT,
                    `recapSkip` TEXT,
                    `previewSkip` TEXT,
                    `commercialSkip` TEXT,
                    PRIMARY KEY(`serverId`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO `playback_preferences_new` (
                    `serverId`,
                    `defaultPlayerBackend`,
                    `defaultMaxBitrateBps`,
                    `preferredAudioLanguage`,
                    `preferredSubtitleLanguage`,
                    `autoPlayNext`,
                    `stillWatchingPrompt`,
                    `autoPlayNextDelaySeconds`,
                    `introSkip`,
                    `outroSkip`,
                    `recapSkip`,
                    `previewSkip`,
                    `commercialSkip`
                )
                SELECT
                    `serverId`,
                    `defaultPlayerBackend`,
                    `defaultMaxBitrateBps`,
                    `preferredAudioLanguage`,
                    `preferredSubtitleLanguage`,
                    `autoPlayNext`,
                    1,
                    `autoPlayNextDelaySeconds`,
                    `introSkip`,
                    `outroSkip`,
                    `recapSkip`,
                    `previewSkip`,
                    `commercialSkip`
                FROM `playback_preferences`
                """.trimIndent(),
            )
            connection.execSQL("DROP TABLE `playback_preferences`")
            connection.execSQL("ALTER TABLE `playback_preferences_new` RENAME TO `playback_preferences`")
        }
    }

private val JELLYFIN_STORE_MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `player_device_settings_new` (
                    `id` TEXT NOT NULL,
                    `audioMode` TEXT NOT NULL,
                    `hdrMode` TEXT NOT NULL,
                    `matchDisplayRefreshRate` INTEGER NOT NULL DEFAULT 0,
                    `maxVideoResolution` TEXT NOT NULL DEFAULT 'Unlimited',
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO `player_device_settings_new` (
                    `id`,
                    `audioMode`,
                    `hdrMode`,
                    `matchDisplayRefreshRate`,
                    `maxVideoResolution`
                )
                SELECT
                    `id`,
                    `audioMode`,
                    `hdrMode`,
                    `matchDisplayRefreshRate`,
                    'Unlimited'
                FROM `player_device_settings`
                """.trimIndent(),
            )
            connection.execSQL("DROP TABLE `player_device_settings`")
            connection.execSQL("ALTER TABLE `player_device_settings_new` RENAME TO `player_device_settings`")
        }
    }

private val JELLYFIN_STORE_MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `playback_preferences` ADD COLUMN `vlcTranscodeMaxBitrateBps` INTEGER",
            )
        }
    }

private val JELLYFIN_STORE_MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `playback_preferences` ADD COLUMN `defaultQualityMode` TEXT DEFAULT 'Auto'",
            )
            connection.execSQL(
                "ALTER TABLE `playback_preferences` ADD COLUMN `defaultQualityBitrateBps` INTEGER",
            )
            connection.execSQL(
                "ALTER TABLE `playback_selections` ADD COLUMN `qualityMode` TEXT",
            )
            connection.execSQL(
                "ALTER TABLE `playback_selections` ADD COLUMN `qualityBitrateBps` INTEGER",
            )
            connection.execSQL(
                "UPDATE `playback_preferences` SET `defaultQualityMode` = " +
                    "CASE WHEN `defaultMaxBitrateBps` > 0 THEN 'Fixed' ELSE 'Auto' END, " +
                    "`defaultQualityBitrateBps` = CASE WHEN `defaultMaxBitrateBps` > 0 " +
                    "THEN `defaultMaxBitrateBps` ELSE NULL END",
            )
            connection.execSQL(
                "UPDATE `playback_selections` SET `qualityMode` = " +
                    "CASE WHEN `maxStreamingBitrateBps` > 0 THEN 'Fixed' " +
                    "WHEN `qualityIsOriginal` = 1 THEN 'Original' ELSE NULL END, " +
                    "`qualityBitrateBps` = CASE WHEN `maxStreamingBitrateBps` > 0 " +
                    "THEN `maxStreamingBitrateBps` ELSE NULL END",
            )
        }
    }

private val JELLYFIN_STORE_MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `playback_preferences` ADD COLUMN `playbackWarningsEnabled` INTEGER NOT NULL DEFAULT 1",
            )
        }
    }

/**
 * Data-only migration: playback warnings flip to the new OFF default for
 * every existing install (re-enable is one Settings toggle), and platforms
 * that pass [seedVlcDefaultBps] (Android
 * only) seed the VLC default quality where no value is stored. NULL is
 * ambiguous — an explicit "Use playback default" choice is indistinguishable
 * from never-chosen and is deliberately overwritten (crash-safety default).
 */
internal fun jellyfinStoreMigration8To9(seedVlcDefaultBps: Long?) =
    object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE `playback_preferences` SET `playbackWarningsEnabled` = 0")
            if (seedVlcDefaultBps != null) {
                connection.execSQL(
                    "UPDATE `playback_preferences` SET `vlcTranscodeMaxBitrateBps` = $seedVlcDefaultBps " +
                        "WHERE `vlcTranscodeMaxBitrateBps` IS NULL",
                )
            }
        }
    }

private val JELLYFIN_STORE_MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `player_device_settings` ADD COLUMN " +
                    "`iosPlaybackCompatibilityMode` TEXT NOT NULL DEFAULT 'Standard'",
            )
        }
    }

private val JELLYFIN_STORE_MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE `playback_preferences_new` (
                    `serverId` TEXT NOT NULL,
                    `userId` TEXT NOT NULL,
                    `defaultPlayerBackend` TEXT DEFAULT 'Auto',
                    `defaultMaxBitrateBps` INTEGER,
                    `vlcTranscodeMaxBitrateBps` INTEGER,
                    `preferredAudioLanguage` TEXT,
                    `preferredSubtitleLanguage` TEXT,
                    `autoPlayNext` INTEGER NOT NULL DEFAULT 1,
                    `stillWatchingPrompt` INTEGER NOT NULL DEFAULT 1,
                    `playbackWarningsEnabled` INTEGER NOT NULL DEFAULT 1,
                    `allowInsecureDesktopTls` INTEGER NOT NULL DEFAULT 0,
                    `autoPlayNextDelaySeconds` INTEGER NOT NULL DEFAULT 10,
                    `introSkip` TEXT,
                    `outroSkip` TEXT,
                    `recapSkip` TEXT,
                    `previewSkip` TEXT,
                    `commercialSkip` TEXT,
                    `defaultQualityMode` TEXT DEFAULT 'Auto',
                    `defaultQualityBitrateBps` INTEGER,
                    PRIMARY KEY(`serverId`, `userId`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO `playback_preferences_new` (
                    `serverId`, `userId`, `defaultPlayerBackend`, `defaultMaxBitrateBps`,
                    `vlcTranscodeMaxBitrateBps`, `preferredAudioLanguage`, `preferredSubtitleLanguage`,
                    `autoPlayNext`, `stillWatchingPrompt`, `playbackWarningsEnabled`,
                    `allowInsecureDesktopTls`, `autoPlayNextDelaySeconds`, `introSkip`, `outroSkip`,
                    `recapSkip`, `previewSkip`, `commercialSkip`, `defaultQualityMode`,
                    `defaultQualityBitrateBps`
                )
                SELECT
                    `serverId`, '', `defaultPlayerBackend`, `defaultMaxBitrateBps`,
                    `vlcTranscodeMaxBitrateBps`, `preferredAudioLanguage`, `preferredSubtitleLanguage`,
                    `autoPlayNext`, `stillWatchingPrompt`, `playbackWarningsEnabled`,
                    0, `autoPlayNextDelaySeconds`, `introSkip`, `outroSkip`, `recapSkip`,
                    `previewSkip`, `commercialSkip`, `defaultQualityMode`, `defaultQualityBitrateBps`
                FROM `playback_preferences`
                """.trimIndent(),
            )
            connection.execSQL("DROP TABLE `playback_preferences`")
            connection.execSQL("ALTER TABLE `playback_preferences_new` RENAME TO `playback_preferences`")
        }
    }

/**
 * The shipped open path: the downgrade-repair driver wrapper plus every migration.
 * [driver] is injectable only so host tests can supply a driver whose native
 * library exists off-device; production always uses the bundled one.
 */
internal fun RoomDatabase.Builder<JellyfinStoreDatabase>.buildJellyfinStore(
    driver: SQLiteDriver = BundledSQLiteDriver(),
    seedVlcDefaultBps: Long? = null,
): JellyfinStoreDatabase {
    val configuredBuilder = setDriver(CacheScopedDowngradeDriver(driver))
    configuredBuilder.addMigrations(
        JELLYFIN_STORE_MIGRATION_1_2,
        JELLYFIN_STORE_MIGRATION_2_3,
        JELLYFIN_STORE_MIGRATION_3_4,
        JELLYFIN_STORE_MIGRATION_4_5,
        JELLYFIN_STORE_MIGRATION_5_6,
        JELLYFIN_STORE_MIGRATION_6_7,
        JELLYFIN_STORE_MIGRATION_7_8,
        jellyfinStoreMigration8To9(seedVlcDefaultBps),
        JELLYFIN_STORE_MIGRATION_9_10,
        JELLYFIN_STORE_MIGRATION_10_11,
    )
    configuredBuilder.setQueryCoroutineContext(platformIoDispatcher())
    return configuredBuilder.build()
}

/**
 * Check and repair a future on-disk version before Room validates schema 11 so a downgrade can
 * discard only refetchable account-boundary caches.
 */
private class CacheScopedDowngradeDriver(
    private val delegate: SQLiteDriver,
) : SQLiteDriver {
    override val hasConnectionPool: Boolean
        get() = delegate.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        if (connection.readUserVersion() > JELLYFIN_STORE_SCHEMA_VERSION) {
            connection.recreateRefetchableCacheTables()
            connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            connection.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, '$JELLYFIN_STORE_IDENTITY_HASH')",
            )
            connection.execSQL("PRAGMA user_version = $JELLYFIN_STORE_SCHEMA_VERSION")
        }
        return connection
    }
}

private fun SQLiteConnection.readUserVersion(): Int {
    val statement = prepare("PRAGMA user_version")
    return try {
        check(statement.step()) { "SQLite user_version query returned no row" }
        statement.getLong(0).toInt()
    } finally {
        statement.close()
    }
}

private fun SQLiteConnection.recreateRefetchableCacheTables() {
    execSQL("DROP TABLE IF EXISTS `recent_searches`")
    execSQL(
        """
        CREATE TABLE `recent_searches` (
            `serverId` TEXT NOT NULL,
            `userId` TEXT NOT NULL,
            `normalizedQuery` TEXT NOT NULL,
            `displayQuery` TEXT NOT NULL,
            `updatedAtMs` INTEGER NOT NULL,
            PRIMARY KEY(`serverId`, `userId`, `normalizedQuery`)
        )
        """.trimIndent(),
    )
    execSQL("DROP TABLE IF EXISTS `watch_next_sync`")
    execSQL(
        """
        CREATE TABLE `watch_next_sync` (
            `serverId` TEXT NOT NULL,
            `userId` TEXT NOT NULL,
            `itemId` TEXT NOT NULL,
            `playbackPositionTicks` INTEGER,
            `played` INTEGER NOT NULL,
            `lastSyncedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`serverId`, `userId`, `itemId`)
        )
        """.trimIndent(),
    )
}

internal class RoomPlaybackPreferencesStore(
    private val dao: JellyfinStoreDao,
    /** Android binds 8 Mbps so fresh installs get the VLC crash-safety default; other platforms inherit. */
    private val defaultVlcTranscodeBitrateBps: Long? = null,
) : PlaybackPreferencesStore {
    override suspend fun get(accountIdentity: AccountIdentity): PlaybackPreferences =
        (
            dao.playbackPreferencesForAccount(accountIdentity)?.toModel()
                ?: PlaybackPreferences(vlcTranscodeMaxBitrateBps = defaultVlcTranscodeBitrateBps)
        ).normalized()

    override suspend fun save(
        accountIdentity: AccountIdentity,
        preferences: PlaybackPreferences,
    ) {
        dao.upsertPlaybackPreferences(preferences.normalized().toEntity(accountIdentity))
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        dao.clearPlaybackPreferences(accountIdentity.serverId, accountIdentity.userId)
    }

    override suspend fun clearServerScoped(serverId: String) {
        dao.clearPlaybackPreferencesForServer(serverId)
    }

    override suspend fun clearServerScoped() {
        dao.clearAllPlaybackPreferences()
    }
}

internal class RoomPlaybackSelectionStore(
    private val dao: JellyfinStoreDao,
) : PlaybackSelectionStore {
    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? =
        dao
            .playbackSelection(key.serverId, key.userId, key.itemId, key.mediaSourceId)
            ?.toModel()

    override suspend fun getForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, PlaybackSelection> =
        itemIds
            .distinct()
            .chunked(PLAYBACK_SELECTION_QUERY_CHUNK)
            .flatMap { chunk -> dao.playbackSelectionsForItems(serverId, userId, chunk) }
            .map { entity -> (entity.itemId to entity.mediaSourceId) to entity.toModel() }
            .toMap()

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) {
        dao.upsertPlaybackSelection(selection.normalized().toEntity(key))
    }

    override suspend fun delete(key: PlaybackSelectionKey) {
        dao.deletePlaybackSelection(key.serverId, key.userId, key.itemId, key.mediaSourceId)
    }

    override suspend fun clearServerScoped() = dao.clearAllPlaybackSelections()

    override suspend fun clearServerScoped(serverId: String) = dao.clearPlaybackSelectionsForServer(serverId)

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) = dao.clearPlaybackSelectionsForAccount(serverId, userId)
}

internal class RoomPlaybackTimingStore(
    private val dao: JellyfinStoreDao,
) : PlaybackTimingStore {
    override suspend fun get(key: PlaybackTimingKey): PlaybackTimingOffset? =
        dao
            .playbackTimingOffset(
                serverId = key.serverId,
                userId = key.userId,
                itemId = key.itemId,
                mediaSourceId = key.mediaSourceId,
                trackId = key.trackId,
                kind = key.kind.name,
            )?.toModel()

    override suspend fun save(offset: PlaybackTimingOffset) {
        dao.upsertPlaybackTimingOffset(offset.normalized().toEntity())
    }

    override suspend fun delete(key: PlaybackTimingKey) {
        dao.deletePlaybackTimingOffset(
            serverId = key.serverId,
            userId = key.userId,
            itemId = key.itemId,
            mediaSourceId = key.mediaSourceId,
            trackId = key.trackId,
            kind = key.kind.name,
        )
    }

    override suspend fun clearServerScoped() = dao.clearAllPlaybackTimingOffsets()

    override suspend fun clearServerScoped(serverId: String) = dao.clearPlaybackTimingOffsetsForServer(serverId)

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) = dao.clearPlaybackTimingOffsetsForAccount(serverId, userId)
}

internal class RoomPlayerBackendOverrideStore(
    private val dao: JellyfinStoreDao,
) : PlayerBackendOverrideStore {
    override suspend fun get(
        serverId: String,
        itemId: String,
    ): PlayerBackend? = dao.playerBackendOverride(serverId, itemId)?.backend.toPlayerBackendOrNull()

    override suspend fun save(
        serverId: String,
        itemId: String,
        backend: PlayerBackend?,
    ) {
        if (backend == null) {
            delete(serverId, itemId)
        } else {
            dao.upsertPlayerBackendOverride(
                PlayerBackendOverrideEntity(
                    serverId = serverId,
                    itemId = itemId,
                    backend = backend.name,
                ),
            )
        }
    }

    override suspend fun delete(
        serverId: String,
        itemId: String,
    ) {
        dao.deletePlayerBackendOverride(serverId, itemId)
    }

    override suspend fun clearServerScoped() {
        dao.clearAllPlayerBackendOverrides()
    }

    override suspend fun clearServerScoped(serverId: String) {
        dao.clearPlayerBackendOverridesForServer(serverId)
    }
}

internal class RoomRecentSearchStore(
    private val dao: JellyfinStoreDao,
) : RecentSearchStore {
    override suspend fun add(
        serverId: String,
        query: String,
    ) {
        val trimmed = query.trim().takeIf { value -> value.isNotBlank() } ?: return
        dao.addRecentSearch(
            serverId = serverId,
            userId = "",
            normalizedQuery = trimmed.lowercase(),
            displayQuery = trimmed,
            limit = RECENT_SEARCH_LIMIT,
        )
    }

    override suspend fun add(
        serverId: String,
        userId: String,
        query: String,
    ) {
        val trimmed = query.trim().takeIf { value -> value.isNotBlank() } ?: return
        dao.addRecentSearch(
            serverId = serverId,
            userId = userId,
            normalizedQuery = trimmed.lowercase(),
            displayQuery = trimmed,
            limit = RECENT_SEARCH_LIMIT,
        )
    }

    override suspend fun list(serverId: String): List<String> =
        dao
            .recentSearches(serverId = serverId, userId = "", limit = RECENT_SEARCH_LIMIT)
            .map { search -> search.displayQuery }

    override suspend fun list(
        serverId: String,
        userId: String,
    ): List<String> =
        dao
            .recentSearches(serverId = serverId, userId = userId, limit = RECENT_SEARCH_LIMIT)
            .map { search -> search.displayQuery }

    override suspend fun clear(serverId: String) {
        dao.clearRecentSearchesForServer(serverId)
    }

    override suspend fun clear(
        serverId: String,
        userId: String,
    ) {
        dao.clearRecentSearches(serverId, userId)
    }

    override suspend fun clearServerScoped() {
        dao.clearAllRecentSearches()
    }
}

internal class RoomWatchNextSyncStore(
    private val dao: JellyfinStoreDao,
) : WatchNextSyncStore {
    override suspend fun get(
        serverId: String,
        itemId: String,
    ): WatchNextSyncState? = dao.watchNextSync(serverId = serverId, userId = "", itemId = itemId)?.toModel()

    override suspend fun get(
        serverId: String,
        userId: String,
        itemId: String,
    ): WatchNextSyncState? = dao.watchNextSync(serverId = serverId, userId = userId, itemId = itemId)?.toModel()

    override suspend fun upsert(
        serverId: String,
        state: WatchNextSyncState,
    ) {
        dao.upsertWatchNextSync(state.toEntity(serverId, ""))
    }

    override suspend fun upsert(
        serverId: String,
        userId: String,
        state: WatchNextSyncState,
    ) {
        dao.upsertWatchNextSync(state.toEntity(serverId, userId))
    }

    override suspend fun list(serverId: String): List<WatchNextSyncState> =
        dao.watchNextSyncStates(serverId, "").map { entity -> entity.toModel() }

    override suspend fun list(
        serverId: String,
        userId: String,
    ): List<WatchNextSyncState> = dao.watchNextSyncStates(serverId, userId).map { entity -> entity.toModel() }

    override suspend fun clear(serverId: String) {
        dao.clearWatchNextSyncForServer(serverId)
    }

    override suspend fun clear(
        serverId: String,
        userId: String,
    ) {
        dao.clearWatchNextSync(serverId, userId)
    }

    override suspend fun clearServerScoped() {
        dao.clearAllWatchNextSync()
    }
}

internal class RoomPlayerDeviceSettingsStore(
    private val dao: JellyfinStoreDao,
    scope: CoroutineScope,
    private val readStoredSettings: suspend () -> PlayerDeviceSettingsEntity? = {
        dao.playerDeviceSettings(PLAYER_DEVICE_SETTINGS_ID)
    },
    private val writeStoredSettings: suspend (PlayerDeviceSettingsEntity) -> Unit = { entity ->
        dao.upsertPlayerDeviceSettings(entity)
    },
) : PlayerDeviceSettingsStore {
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(PlayerDeviceSettings())
    private var wroteSettings = false

    override val settings: StateFlow<PlayerDeviceSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            val stored =
                readStoredSettings()
                    ?.toModel()
                    ?: PlayerDeviceSettings()

            mutex.withLock {
                if (!wroteSettings) {
                    _settings.value = stored
                }
            }
        }
    }

    override suspend fun setSettings(settings: PlayerDeviceSettings) {
        mutex.withLock {
            writeStoredSettings(settings.toEntity())
            wroteSettings = true
            _settings.value = settings
        }
    }
}

internal class RoomSubtitleSelectionStore(
    private val dao: JellyfinStoreDao,
) : SubtitleSelectionStore {
    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? {
        val entity =
            dao.subtitleSelection(
                serverId = key.serverId,
                userId = key.userId,
                itemId = key.itemId,
                mediaSourceId = key.mediaSourceId,
            ) ?: return null
        return entity.toModel()
    }

    // Malformed rows decode as absent; reads never bypass the mutation owner to repair storage.
    override suspend fun getForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, SubtitleSelectionIntent> =
        itemIds
            .distinct()
            .chunked(SUBTITLE_SELECTION_QUERY_CHUNK)
            .flatMap { chunk -> dao.subtitleSelectionsForItems(serverId, userId, chunk) }
            .mapNotNull { entity ->
                entity.toModel()?.let { intent -> (entity.itemId to entity.mediaSourceId) to intent }
            }.toMap()

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) {
        when (selection) {
            SubtitleSelectionIntent.Unspecified -> delete(key)
            SubtitleSelectionIntent.Off,
            is SubtitleSelectionIntent.Track,
            is SubtitleSelectionIntent.LocalAsset,
            -> dao.upsertSubtitleSelection(selection.toEntity(key))
        }
    }

    override suspend fun delete(key: SubtitleSelectionKey) {
        dao.deleteSubtitleSelection(
            serverId = key.serverId,
            userId = key.userId,
            itemId = key.itemId,
            mediaSourceId = key.mediaSourceId,
        )
    }

    override suspend fun clearLocalAssetSelections() {
        dao.clearAllLocalAssetSubtitleSelections()
    }

    override suspend fun clearServerScoped(serverId: String) {
        dao.clearNonLocalSubtitleSelections(serverId)
    }

    override suspend fun clearNonLocalAccountSelections(
        serverId: String,
        userId: String,
    ) {
        dao.clearNonLocalSubtitleSelectionsForAccount(serverId, userId)
    }

    override suspend fun clearServerScoped() {
        dao.clearAllNonLocalSubtitleSelections()
    }
}

internal class RoomLocalSubtitleAssetStore(
    private val dao: JellyfinStoreDao,
) : LocalSubtitleAssetStore {
    override fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> =
        dao
            .observeLocalSubtitleAssets(context.serverId, context.userId, context.itemId, context.mediaSourceId)
            .map { list -> list.map(LocalSubtitleAssetEntity::toModel) }

    override fun observePendingSync(): Flow<List<LocalSubtitleAsset>> =
        dao
            .observePendingLocalSubtitleAssets()
            .map { list -> list.map(LocalSubtitleAssetEntity::toModel) }

    override suspend fun get(assetId: String): LocalSubtitleAsset? = dao.localSubtitleAsset(assetId)?.toModel()

    override suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset? =
        dao
            .localSubtitleAssetByProviderFile(
                context.serverId,
                context.userId,
                context.itemId,
                context.mediaSourceId,
                provider,
                providerFileId,
            )?.toModel()

    override suspend fun upsert(asset: LocalSubtitleAsset) = dao.upsertLocalSubtitleAsset(asset.toEntity())

    override suspend fun delete(assetId: String) = dao.deleteLocalSubtitleAsset(assetId)

    override suspend fun all(): List<LocalSubtitleAsset> = dao.allLocalSubtitleAssets().map(LocalSubtitleAssetEntity::toModel)

    override suspend fun clearAll() = dao.clearAllLocalSubtitleAssets()
}

private fun SubtitleSelectionEntity.toModel(): SubtitleSelectionIntent? =
    when (selectionType) {
        SUBTITLE_SELECTION_OFF -> SubtitleSelectionIntent.Off
        SUBTITLE_SELECTION_TRACK -> streamIndex?.takeIf { index -> index >= 0 }?.let(SubtitleSelectionIntent::Track)
        SUBTITLE_SELECTION_LOCAL_ASSET -> localAssetId?.takeIf(String::isNotBlank)?.let(SubtitleSelectionIntent::LocalAsset)
        else -> null
    }

private fun SubtitleSelectionIntent.toEntity(key: SubtitleSelectionKey): SubtitleSelectionEntity =
    SubtitleSelectionEntity(
        serverId = key.serverId,
        userId = key.userId,
        itemId = key.itemId,
        mediaSourceId = key.mediaSourceId,
        selectionType =
            when (this) {
                is SubtitleSelectionIntent.Track -> SUBTITLE_SELECTION_TRACK
                is SubtitleSelectionIntent.LocalAsset -> SUBTITLE_SELECTION_LOCAL_ASSET
                else -> SUBTITLE_SELECTION_OFF
            },
        streamIndex = (this as? SubtitleSelectionIntent.Track)?.streamIndex,
        localAssetId = (this as? SubtitleSelectionIntent.LocalAsset)?.assetId,
    )

private const val SUBTITLE_SELECTION_OFF = "Off"
private const val SUBTITLE_SELECTION_TRACK = "Track"
private const val SUBTITLE_SELECTION_LOCAL_ASSET = "LocalAsset"

// Chunk size for the `itemId IN (...)` bulk read. Well under SQLite's host-parameter
// limit on every driver (legacy 999 and modern 32766), so a future driver swap can't
// silently reintroduce the cap.
private const val SUBTITLE_SELECTION_QUERY_CHUNK = 500

private fun LocalSubtitleAssetEntity.toModel(): LocalSubtitleAsset =
    LocalSubtitleAsset(
        id = id,
        serverId = serverId,
        userId = userId,
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        provider = provider,
        providerSubtitleId = providerSubtitleId,
        providerFileId = providerFileId,
        language = language,
        label = label,
        releaseName = releaseName,
        originalFormat = originalFormat,
        mimeType = mimeType,
        fileId = fileId,
        hearingImpaired = hearingImpaired,
        forced = forced,
        trusted = trusted,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedAtEpochMs = lastUsedAtEpochMs,
        syncState = syncState.toLocalSubtitleSyncState(confirmedStreamIndex),
        confirmedStreamIndex = confirmedStreamIndex,
        uploadBaseline = uploadBaseline,
    )

private fun LocalSubtitleAsset.toEntity(): LocalSubtitleAssetEntity =
    LocalSubtitleAssetEntity(
        id = id,
        serverId = serverId,
        userId = userId,
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        provider = provider,
        providerSubtitleId = providerSubtitleId,
        providerFileId = providerFileId,
        language = language,
        label = label,
        releaseName = releaseName,
        originalFormat = originalFormat,
        mimeType = mimeType,
        fileId = fileId,
        hearingImpaired = hearingImpaired,
        forced = forced,
        trusted = trusted,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedAtEpochMs = lastUsedAtEpochMs,
        syncState = syncState.storageName(),
        confirmedStreamIndex = (syncState as? LocalSubtitleSyncState.Confirmed)?.streamIndex ?: confirmedStreamIndex,
        uploadBaseline = uploadBaseline,
    )

private fun String.toLocalSubtitleSyncState(confirmedStreamIndex: Int?): LocalSubtitleSyncState =
    when (this) {
        "Pending" -> LocalSubtitleSyncState.Pending
        "Uploading" -> LocalSubtitleSyncState.Uploading
        "Reconciling" -> LocalSubtitleSyncState.Reconciling
        "Confirmed" -> confirmedStreamIndex?.let(LocalSubtitleSyncState::Confirmed) ?: LocalSubtitleSyncState.Reconciling
        "UploadedUnconfirmed" -> LocalSubtitleSyncState.UploadedUnconfirmed
        "LocalOnlyAlternateSource" -> LocalSubtitleSyncState.LocalOnlyAlternateSource
        "PermissionDenied" -> LocalSubtitleSyncState.PermissionDenied
        else -> LocalSubtitleSyncState.FailedPermanent
    }

private fun LocalSubtitleSyncState.storageName(): String =
    when (this) {
        LocalSubtitleSyncState.Pending -> "Pending"
        LocalSubtitleSyncState.Uploading -> "Uploading"
        LocalSubtitleSyncState.Reconciling -> "Reconciling"
        is LocalSubtitleSyncState.Confirmed -> "Confirmed"
        LocalSubtitleSyncState.UploadedUnconfirmed -> "UploadedUnconfirmed"
        LocalSubtitleSyncState.LocalOnlyAlternateSource -> "LocalOnlyAlternateSource"
        LocalSubtitleSyncState.PermissionDenied -> "PermissionDenied"
        LocalSubtitleSyncState.FailedPermanent -> "FailedPermanent"
    }

private fun PlaybackPreferencesEntity.toModel(): PlaybackPreferences =
    PlaybackPreferences(
        defaultPlayerBackend = defaultPlayerBackend.toPlayerBackendOrAuto(),
        defaultQualityPolicy = defaultQualityPolicy(defaultQualityMode, defaultQualityBitrateBps, defaultMaxBitrateBps),
        defaultMaxBitrateBps = defaultMaxBitrateBps,
        vlcTranscodeMaxBitrateBps = vlcTranscodeMaxBitrateBps,
        preferredAudioLanguage = preferredAudioLanguage,
        preferredSubtitleLanguage = preferredSubtitleLanguage,
        autoPlayNext = autoPlayNext,
        stillWatchingPrompt = stillWatchingPrompt,
        playbackWarningsEnabled = playbackWarningsEnabled,
        allowInsecureDesktopTls = allowInsecureDesktopTls,
        autoPlayNextDelaySeconds = autoPlayNextDelaySeconds,
        introSkip = introSkip.toSegmentSkipPolicy(),
        outroSkip = outroSkip.toSegmentSkipPolicy(),
        recapSkip = recapSkip.toSegmentSkipPolicy(),
        previewSkip = previewSkip.toSegmentSkipPolicy(),
        commercialSkip = commercialSkip.toSegmentSkipPolicy(),
    )

// Unknown persisted names fall back to the Ask default rather than crashing
// startup.
private fun String?.toSegmentSkipPolicy(): SegmentSkipPolicy = toTolerantEnumOrNull<SegmentSkipPolicy>() ?: SegmentSkipPolicy.Ask

private fun PlaybackPreferences.toEntity(accountIdentity: AccountIdentity): PlaybackPreferencesEntity =
    PlaybackPreferencesEntity(
        serverId = accountIdentity.serverId,
        userId = accountIdentity.userId,
        defaultPlayerBackend = defaultPlayerBackend.name,
        defaultMaxBitrateBps = effectiveDefaultQualityPolicy().maxBitrateBps,
        vlcTranscodeMaxBitrateBps = vlcTranscodeMaxBitrateBps,
        preferredAudioLanguage = preferredAudioLanguage,
        preferredSubtitleLanguage = preferredSubtitleLanguage,
        autoPlayNext = autoPlayNext,
        stillWatchingPrompt = stillWatchingPrompt,
        playbackWarningsEnabled = playbackWarningsEnabled,
        allowInsecureDesktopTls = allowInsecureDesktopTls,
        autoPlayNextDelaySeconds = autoPlayNextDelaySeconds,
        introSkip = introSkip.name,
        outroSkip = outroSkip.name,
        recapSkip = recapSkip.name,
        previewSkip = previewSkip.name,
        commercialSkip = commercialSkip.name,
        defaultQualityMode = effectiveDefaultQualityPolicy().mode.name,
        defaultQualityBitrateBps = effectiveDefaultQualityPolicy().maxBitrateBps,
    )

private fun defaultQualityPolicy(
    modeName: String?,
    bitrateBps: Long?,
    legacyBitrateBps: Long?,
): PlaybackQualityPolicy {
    val legacyPolicy =
        legacyBitrateBps
            ?.takeIf { bitrate -> bitrate > 0L }
            ?.let { bitrate -> PlaybackQualityPolicy.fixed(bitrate) }
    if (legacyPolicy != null) return legacyPolicy
    val mode = modeName.toTolerantEnumOrNull<PlaybackQualityMode>() ?: PlaybackQualityMode.Auto
    return PlaybackQualityPolicy(mode, bitrateBps).normalized()
}

private fun String?.toPlayerBackendOrAuto(): PlayerBackend {
    val value = this?.trim().orEmpty()
    return value.toTolerantEnumOrNull<PlayerBackend>() ?: PlayerBackend.Auto
}

private fun String?.toPlayerBackendOrNull(): PlayerBackend? {
    val value = this?.trim().orEmpty()
    return value.toTolerantEnumOrNull<PlayerBackend>()
}

private fun PlaybackSelectionEntity.toModel(): PlaybackSelection =
    PlaybackSelection(
        audioStreamIndex = audioStreamIndex,
        schemaVersion = schemaVersion,
    ).normalized()

private fun PlaybackSelection.toEntity(key: PlaybackSelectionKey): PlaybackSelectionEntity =
    normalized().let { selection ->
        PlaybackSelectionEntity(
            serverId = key.serverId,
            userId = key.userId,
            itemId = key.itemId,
            mediaSourceId = key.mediaSourceId,
            audioStreamIndex = selection.audioStreamIndex,
            // Legacy quality columns remain in the database schema so existing
            // installs can open without a destructive migration.
            // Quality choices are session-only and are never read or written.
            maxStreamingBitrateBps = null,
            schemaVersion = selection.schemaVersion,
            qualityIsOriginal = false,
            qualityMode = null,
            qualityBitrateBps = null,
        )
    }

private fun PlaybackTimingOffsetEntity.toModel(): PlaybackTimingOffset =
    PlaybackTimingOffset(
        key =
            PlaybackTimingKey(
                serverId = serverId,
                userId = userId,
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                trackId = trackId,
                kind = kind.toTolerantEnumOrNull<PlaybackTimingKind>() ?: PlaybackTimingKind.Subtitle,
            ),
        offsetMs = offsetMs,
        schemaVersion = schemaVersion,
    ).normalized()

private fun PlaybackTimingOffset.toEntity(): PlaybackTimingOffsetEntity =
    PlaybackTimingOffsetEntity(
        serverId = key.serverId,
        userId = key.userId,
        itemId = key.itemId,
        mediaSourceId = key.mediaSourceId,
        trackId = key.trackId,
        kind = key.kind.name,
        offsetMs = offsetMs,
        schemaVersion = schemaVersion,
    )

private fun WatchNextSyncEntity.toModel(): WatchNextSyncState =
    WatchNextSyncState(
        itemId = itemId,
        playbackPositionTicks = playbackPositionTicks,
        played = played,
        lastSyncedAtEpochMs = lastSyncedAtEpochMs,
    )

private fun WatchNextSyncState.toEntity(
    serverId: String,
    userId: String,
): WatchNextSyncEntity =
    WatchNextSyncEntity(
        serverId = serverId,
        userId = userId,
        itemId = itemId,
        playbackPositionTicks = playbackPositionTicks,
        played = played,
        lastSyncedAtEpochMs = lastSyncedAtEpochMs,
    )

private fun PlayerDeviceSettingsEntity.toModel(): PlayerDeviceSettings =
    PlayerDeviceSettings(
        audioMode =
            audioMode.toTolerantEnumOrNull<PlayerAudioMode>() ?: PlayerAudioMode.Auto,
        hdrMode =
            hdrMode.toTolerantEnumOrNull<PlayerHdrMode>() ?: PlayerHdrMode.Auto,
        matchDisplayRefreshRate = matchDisplayRefreshRate,
        maxVideoResolution =
            maxVideoResolution.toTolerantEnumOrNull<PlayerVideoResolutionLimit>()
                ?: PlayerVideoResolutionLimit.Unlimited,
        iosPlaybackCompatibilityMode =
            iosPlaybackCompatibilityMode.toTolerantEnumOrNull<IosPlaybackCompatibilityMode>()
                ?: IosPlaybackCompatibilityMode.Standard,
    )

private fun PlayerDeviceSettings.toEntity(): PlayerDeviceSettingsEntity =
    PlayerDeviceSettingsEntity(
        id = PLAYER_DEVICE_SETTINGS_ID,
        audioMode = audioMode.name,
        hdrMode = hdrMode.name,
        matchDisplayRefreshRate = matchDisplayRefreshRate,
        maxVideoResolution = maxVideoResolution.name,
        iosPlaybackCompatibilityMode = iosPlaybackCompatibilityMode.name,
    )

internal const val PLAYER_DEVICE_SETTINGS_ID = "global"
internal const val JELLYFIN_STORE_DATABASE_NAME = "jellyfin-compose-store.db"

private const val PLAYBACK_SELECTION_QUERY_CHUNK = 500
