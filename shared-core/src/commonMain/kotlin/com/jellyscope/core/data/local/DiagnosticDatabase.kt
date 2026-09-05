// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import androidx.room3.ColumnInfo
import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Transaction
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.jellyscope.core.coroutines.platformIoDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val DIAGNOSTIC_DATABASE_FILE_NAME = "jellyscope-diagnostics.db"
internal const val DIAGNOSTIC_DATABASE_SCHEMA_VERSION = 1
internal const val DIAGNOSTIC_MAX_ENTRIES = 4_000
internal const val DIAGNOSTIC_MAX_BYTES = 500_000

@Entity(
    tableName = "diagnostic_breadcrumbs",
    indices = [androidx.room3.Index(value = ["recordId"], unique = true)],
)
internal data class DiagnosticBreadcrumbEntity(
    @androidx.room3.PrimaryKey(autoGenerate = true)
    val sequence: Long = 0L,
    val recordId: String,
    val line: String,
    val byteCount: Int,
)

@Entity(tableName = "diagnostic_breadcrumb_meta")
internal data class DiagnosticBreadcrumbMetaEntity(
    @androidx.room3.PrimaryKey
    val id: Int = 1,
    @ColumnInfo(defaultValue = "0")
    val truncationRevision: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val highestPrunedSequence: Long = 0L,
)

@Dao
internal interface DiagnosticBreadcrumbDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DiagnosticBreadcrumbEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMeta(entity: DiagnosticBreadcrumbMetaEntity)

    @Query("SELECT * FROM diagnostic_breadcrumbs ORDER BY sequence")
    suspend fun allRecords(): List<DiagnosticBreadcrumbEntity>

    @Query("SELECT * FROM diagnostic_breadcrumb_meta WHERE id = 1 LIMIT 1")
    suspend fun meta(): DiagnosticBreadcrumbMetaEntity?

    @Query("SELECT COUNT(*) FROM diagnostic_breadcrumbs")
    suspend fun count(): Long

    @Query("SELECT COALESCE(SUM(byteCount), 0) FROM diagnostic_breadcrumbs")
    suspend fun byteCount(): Long

    @Query("SELECT * FROM diagnostic_breadcrumbs ORDER BY sequence LIMIT 1")
    suspend fun oldest(): DiagnosticBreadcrumbEntity?

    @Query("DELETE FROM diagnostic_breadcrumbs WHERE sequence = :sequence")
    suspend fun delete(sequence: Long)

    @Query("DELETE FROM diagnostic_breadcrumbs WHERE sequence <= :maxSequence")
    suspend fun deleteThrough(maxSequence: Long)

    @Query("DELETE FROM diagnostic_breadcrumbs WHERE recordId IN (:recordIds)")
    suspend fun deleteByRecordIds(recordIds: List<String>)

    @Query("DELETE FROM diagnostic_breadcrumbs")
    suspend fun deleteAll()

    @Query("DELETE FROM diagnostic_breadcrumb_meta")
    suspend fun deleteMeta()

    @Query(
        "UPDATE diagnostic_breadcrumb_meta SET truncationRevision = :revision, " +
            "highestPrunedSequence = :highestPrunedSequence WHERE id = 1",
    )
    suspend fun updateMeta(
        revision: Long,
        highestPrunedSequence: Long,
    )

    @Transaction
    suspend fun appendAndTrim(
        records: List<DiagnosticBreadcrumbEntity>,
        ingressOverflowed: Boolean,
        targetTruncationRevision: Long? = null,
    ): DiagnosticBreadcrumbStorageState {
        insertMeta(DiagnosticBreadcrumbMetaEntity())
        records.forEach { record -> insert(record) }

        var highestPrunedSequence = 0L
        var pruned = false
        while (count() > DIAGNOSTIC_MAX_ENTRIES || byteCount() > DIAGNOSTIC_MAX_BYTES) {
            val oldest = oldest() ?: break
            delete(oldest.sequence)
            highestPrunedSequence = oldest.sequence
            pruned = true
        }

        val currentMeta = meta() ?: DiagnosticBreadcrumbMetaEntity()
        val revision =
            if (targetTruncationRevision != null) {
                maxOf(targetTruncationRevision, currentMeta.truncationRevision)
            } else if (ingressOverflowed || pruned) {
                currentMeta.truncationRevision + 1L
            } else {
                currentMeta.truncationRevision
            }
        if (revision != currentMeta.truncationRevision || pruned) {
            updateMeta(
                revision = revision,
                highestPrunedSequence = maxOf(currentMeta.highestPrunedSequence, highestPrunedSequence),
            )
        }
        return storageState()
    }

    @Transaction
    suspend fun clearThrough(
        maxSequence: Long,
        representedTruncationRevision: Long,
        fallbackRecordIds: List<String>,
    ) {
        insertMeta(DiagnosticBreadcrumbMetaEntity())
        if (fallbackRecordIds.isNotEmpty()) deleteByRecordIds(fallbackRecordIds)
        if (maxSequence > 0L) deleteThrough(maxSequence)
        val currentMeta = meta() ?: return
        if (currentMeta.truncationRevision == representedTruncationRevision) {
            updateMeta(revision = 0L, highestPrunedSequence = 0L)
        }
    }

    @Transaction
    suspend fun clearAll() {
        deleteAll()
        deleteMeta()
        insertMeta(DiagnosticBreadcrumbMetaEntity())
    }

    suspend fun storageState(): DiagnosticBreadcrumbStorageState {
        val metadata = meta() ?: DiagnosticBreadcrumbMetaEntity()
        return DiagnosticBreadcrumbStorageState(
            records =
                allRecords().map { entity ->
                    DiagnosticBreadcrumbStoredRecord(
                        recordId = entity.recordId,
                        line = entity.line,
                        byteCount = entity.byteCount,
                        sequence = entity.sequence,
                    )
                },
            truncationRevision = metadata.truncationRevision,
        )
    }
}

@Database(
    entities = [DiagnosticBreadcrumbEntity::class, DiagnosticBreadcrumbMetaEntity::class],
    version = DIAGNOSTIC_DATABASE_SCHEMA_VERSION,
    exportSchema = true,
)
@ConstructedBy(DiagnosticDatabaseConstructor::class)
internal abstract class DiagnosticDatabase : RoomDatabase() {
    abstract fun diagnosticBreadcrumbDao(): DiagnosticBreadcrumbDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object DiagnosticDatabaseConstructor : RoomDatabaseConstructor<DiagnosticDatabase> {
    override fun initialize(): DiagnosticDatabase
}

internal fun androidx.room3.RoomDatabase.Builder<DiagnosticDatabase>.buildDiagnosticDatabase(
    driver: SQLiteDriver = BundledSQLiteDriver(),
): DiagnosticDatabase =
    setDriver(DiagnosticDowngradeDriver(driver))
        // This database contains only disposable, scrubbed breadcrumbs. A schema mismatch may
        // recreate this database, but the application and download databases never use this path.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setQueryCoroutineContext(platformIoDispatcher())
        .build()

/**
 * A future diagnostic build is disposable, so a newer on-disk version is recreated before Room
 * validates the v1 delegate. This wrapper is deliberately owned by this database and cannot see
 * either application database path or schema.
 */
private class DiagnosticDowngradeDriver(
    private val delegate: SQLiteDriver,
) : SQLiteDriver {
    override val hasConnectionPool: Boolean
        get() = delegate.hasConnectionPool

    override fun open(fileName: String): androidx.sqlite.SQLiteConnection {
        val connection = delegate.open(fileName)
        return try {
            if (connection.readUserVersion() > DIAGNOSTIC_DATABASE_SCHEMA_VERSION) {
                connection.recreateDiagnosticSchema()
            }
            connection
        } catch (throwable: Throwable) {
            runCatching { connection.close() }
            throw throwable
        }
    }
}

private fun androidx.sqlite.SQLiteConnection.readUserVersion(): Int {
    val statement = prepare("PRAGMA user_version")
    return try {
        check(statement.step()) { "SQLite user_version query returned no row" }
        statement.getLong(0).toInt()
    } finally {
        statement.close()
    }
}

private fun androidx.sqlite.SQLiteConnection.recreateDiagnosticSchema() {
    val existingTables = mutableListOf<String>()
    prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { statement ->
        while (statement.step()) existingTables += statement.getText(0)
    }
    existingTables.forEach { tableName ->
        execSQL("DROP TABLE IF EXISTS `${tableName.replace("`", "``")}`")
    }
    execSQL(
        "CREATE TABLE IF NOT EXISTS `diagnostic_breadcrumbs` " +
            "(`sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recordId` TEXT NOT NULL, " +
            "`line` TEXT NOT NULL, `byteCount` INTEGER NOT NULL)",
    )
    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_diagnostic_breadcrumbs_recordId` " +
            "ON `diagnostic_breadcrumbs` (`recordId`)",
    )
    execSQL(
        "CREATE TABLE IF NOT EXISTS `diagnostic_breadcrumb_meta` " +
            "(`id` INTEGER NOT NULL, `truncationRevision` INTEGER NOT NULL DEFAULT 0, " +
            "`highestPrunedSequence` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
    )
    execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
    execSQL(
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
            "VALUES(42, '$DIAGNOSTIC_DATABASE_IDENTITY_HASH')",
    )
    execSQL("PRAGMA user_version = $DIAGNOSTIC_DATABASE_SCHEMA_VERSION")
}

/**
 * Common Room-backed implementation. Platform factories provide a fresh builder and an exact
 * file-sidecar purge callback; ambiguous Room failures are deliberately propagated to the actor.
 */
internal class RoomDiagnosticBreadcrumbStore(
    private val openDatabase: () -> DiagnosticDatabase,
    private val purgeFiles: () -> Unit,
) : DiagnosticBreadcrumbStore {
    private val mutex = Mutex()
    private var database: DiagnosticDatabase? = null

    override suspend fun hydrate(): DiagnosticBreadcrumbStorageState =
        mutex.withLock {
            withDatabase { dao -> dao.storageState() }
        }

    override suspend fun appendAndTrim(
        records: List<DiagnosticBreadcrumbRecord>,
        ingressOverflowed: Boolean,
        targetTruncationRevision: Long?,
    ): DiagnosticBreadcrumbStorageState =
        mutex.withLock {
            withDatabase { dao ->
                dao.appendAndTrim(
                    records =
                        records.map { record ->
                            DiagnosticBreadcrumbEntity(
                                recordId = record.recordId,
                                line = record.line,
                                byteCount = record.byteCount,
                            )
                        },
                    ingressOverflowed = ingressOverflowed,
                    targetTruncationRevision = targetTruncationRevision,
                )
            }
        }

    override suspend fun clearThrough(
        maxSequence: Long,
        representedTruncationRevision: Long,
        fallbackRecordIds: Set<String>,
    ) {
        mutex.withLock {
            withDatabase { dao ->
                dao.clearThrough(
                    maxSequence = maxSequence,
                    representedTruncationRevision = representedTruncationRevision,
                    fallbackRecordIds = fallbackRecordIds.toList(),
                )
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            withDatabase { dao -> dao.clearAll() }
        }
    }

    override suspend fun purge() {
        mutex.withLock {
            database?.close()
            database = null
            purgeFiles()
        }
    }

    private suspend fun <T> withDatabase(block: suspend (DiagnosticBreadcrumbDao) -> T): T {
        val current = database ?: openDatabase().also { opened -> database = opened }
        return try {
            block(current.diagnosticBreadcrumbDao())
        } catch (throwable: Throwable) {
            // Closing permits a later command to retry opening, but it never authorizes deleting
            // files. Only the explicit durable opt-out calls purgeFiles().
            current.close()
            database = null
            throw throwable
        }
    }
}

private const val DIAGNOSTIC_DATABASE_IDENTITY_HASH = "b4670a778b99e9a6df9689be22c50fef"
