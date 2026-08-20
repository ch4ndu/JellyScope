// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DiagnosticDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseFile =
        File.createTempFile("diagnostic-database-test", ".db").also { file -> file.delete() }
    private var database: DiagnosticDatabase? = null

    @AfterTest
    fun tearDown() {
        database?.close()
        database = null
        deleteDatabaseFiles(databaseFile)
    }

    @Test
    fun reopensRowsAndRecreatesOnlyTheDiagnosticSchemaFromAFutureVersion() =
        runTest {
            val initial = openDatabase()
            initial
                .diagnosticBreadcrumbDao()
                .appendAndTrim(
                    records =
                        listOf(
                            DiagnosticBreadcrumbEntity(
                                recordId = "record-1",
                                line = "INFO PlaybackInfoPlanner stage=planner event=resolved",
                                byteCount = 57,
                            ),
                        ),
                    ingressOverflowed = false,
                )
            initial.close()
            database = null

            val futureConnection = AndroidSQLiteDriver().open(databaseFile.path)
            futureConnection.execSQL(
                "CREATE TABLE future_diagnostic_rows (id INTEGER PRIMARY KEY NOT NULL)",
            )
            futureConnection.execSQL("PRAGMA user_version = 2")
            futureConnection.close()

            val recovered = openDatabase()
            val state = recovered.diagnosticBreadcrumbDao().storageState()
            recovered.close()
            database = null

            val verificationConnection = AndroidSQLiteDriver().open(databaseFile.path)
            val tableNames = mutableListOf<String>()
            verificationConnection
                .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
                .use { statement ->
                    while (statement.step()) tableNames += statement.getText(0)
                }
            verificationConnection.close()

            assertTrue(state.records.isEmpty())
            assertTrue(tableNames.containsAll(listOf("diagnostic_breadcrumb_meta", "diagnostic_breadcrumbs", "room_master_table")))
            assertFalse(tableNames.contains("future_diagnostic_rows"))
        }

    @Test
    fun purgeDeletesOnlyDiagnosticDatabaseAndItsWalShmSidecars() =
        runTest {
            val store = createDiagnosticBreadcrumbStore(context, driver = AndroidSQLiteDriver())
            val diagnosticFile = File(context.filesDir, DIAGNOSTIC_DATABASE_FILE_NAME)
            val walFile = File("${diagnosticFile.absolutePath}-wal")
            val shmFile = File("${diagnosticFile.absolutePath}-shm")
            val neighborFile = File(context.filesDir, "$DIAGNOSTIC_DATABASE_FILE_NAME.backup")
            val applicationMarker = File(context.filesDir, "jellyscope-application.db")
            deleteDatabaseFiles(diagnosticFile)
            neighborFile.writeText("neighbor")
            applicationMarker.writeText("application")

            store.appendAndTrim(
                records =
                    listOf(
                        DiagnosticBreadcrumbRecord(
                            recordId = "record-1",
                            line = "INFO PlaybackInfoPlanner stage=planner event=resolved",
                            byteCount = 57,
                        ),
                    ),
                ingressOverflowed = false,
            )
            walFile.writeText("wal")
            shmFile.writeText("shm")

            store.purge()

            assertFalse(diagnosticFile.exists())
            assertFalse(walFile.exists())
            assertFalse(shmFile.exists())
            assertTrue(neighborFile.exists())
            assertTrue(applicationMarker.exists())
            neighborFile.delete()
            applicationMarker.delete()
        }

    @Test
    fun daoIsIdempotentAndEnforcesEntryAndByteCaps() =
        runTest {
            val dao = openDatabase().diagnosticBreadcrumbDao()
            val duplicate =
                DiagnosticBreadcrumbEntity(
                    recordId = "duplicate",
                    line = "INFO PlaybackInfoPlanner stage=planner event=duplicate",
                    byteCount = 58,
                )
            dao.appendAndTrim(listOf(duplicate), ingressOverflowed = false)
            val duplicateState = dao.appendAndTrim(listOf(duplicate), ingressOverflowed = false)
            assertEquals(1, duplicateState.records.count { record -> record.recordId == "duplicate" })

            val capped =
                (0 until 4_100).map { index ->
                    DiagnosticBreadcrumbEntity(
                        recordId = "cap-$index",
                        line = "INFO PlaybackInfoPlanner stage=planner event=cap-$index",
                        byteCount = 64,
                    )
                }
            val state = dao.appendAndTrim(capped, ingressOverflowed = false)

            assertEquals(4_000, state.records.size)
            assertTrue(state.records.sumOf { record -> record.byteCount } <= DIAGNOSTIC_MAX_BYTES)
            assertTrue(state.truncationRevision > 0L)

            dao.clearAll()
            val byteCapped =
                (0 until 300).map { index ->
                    DiagnosticBreadcrumbEntity(
                        recordId = "bytes-$index",
                        line = "x".repeat(2_000),
                        byteCount = 2_000,
                    )
                }
            val byteState = dao.appendAndTrim(byteCapped, ingressOverflowed = false)

            assertTrue(byteState.records.size < byteCapped.size)
            assertTrue(byteState.records.sumOf { record -> record.byteCount } <= DIAGNOSTIC_MAX_BYTES)
        }

    @Test
    fun clearThroughDeletesExactFallbackIdsWithoutClearingANewerRevision() =
        runTest {
            val dao = openDatabase().diagnosticBreadcrumbDao()
            val old =
                dao.appendAndTrim(
                    listOf(
                        DiagnosticBreadcrumbEntity(
                            recordId = "old",
                            line = "INFO PlaybackInfoPlanner stage=planner event=old",
                            byteCount = 50,
                        ),
                    ),
                    ingressOverflowed = false,
                )
            dao.appendAndTrim(
                listOf(
                    DiagnosticBreadcrumbEntity(
                        recordId = "fallback-id",
                        line = "INFO PlaybackInfoPlanner stage=planner event=fallback",
                        byteCount = 55,
                    ),
                ),
                ingressOverflowed = false,
            )
            val newer =
                dao.appendAndTrim(
                    listOf(
                        DiagnosticBreadcrumbEntity(
                            recordId = "newer",
                            line = "INFO PlaybackInfoPlanner stage=planner event=newer",
                            byteCount = 52,
                        ),
                    ),
                    ingressOverflowed = true,
                )
            val oldSequence = assertNotNull(old.records.single { record -> record.recordId == "old" }).sequence

            dao.clearThrough(
                maxSequence = oldSequence,
                representedTruncationRevision = old.truncationRevision,
                fallbackRecordIds = listOf("fallback-id"),
            )
            val remaining = dao.storageState()

            assertEquals(listOf("newer"), remaining.records.map { record -> record.recordId })
            assertEquals(newer.truncationRevision, remaining.truncationRevision)
        }

    @Test
    fun clearAllReplacesTruncationMetadataBeforeAReconstruction() =
        runTest {
            val dao = openDatabase().diagnosticBreadcrumbDao()
            val truncated =
                dao.appendAndTrim(
                    records =
                        listOf(
                            DiagnosticBreadcrumbEntity(
                                recordId = "overflow",
                                line = "INFO PlaybackInfoPlanner stage=planner event=overflow",
                                byteCount = 55,
                            ),
                        ),
                    ingressOverflowed = true,
                )
            assertTrue(truncated.truncationRevision > 0L)

            dao.clearAll()

            val reconstructed = dao.storageState()
            assertTrue(reconstructed.records.isEmpty())
            assertEquals(0L, reconstructed.truncationRevision)
        }

    private fun openDatabase(): DiagnosticDatabase {
        val opened =
            Room
                .databaseBuilder<DiagnosticDatabase>(context, databaseFile.path)
                .buildDiagnosticDatabase(driver = AndroidSQLiteDriver())
        database = opened
        return opened
    }

    private fun deleteDatabaseFiles(file: File) {
        listOf(file, File("${file.absolutePath}-wal"), File("${file.absolutePath}-shm")).forEach { candidate ->
            candidate.delete()
        }
    }
}
