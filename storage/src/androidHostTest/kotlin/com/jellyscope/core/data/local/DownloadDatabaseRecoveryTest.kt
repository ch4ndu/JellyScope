// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.BeginDownloadRemovalResult
import com.jellyscope.core.domain.model.DOWNLOAD_BYTES_PER_GB
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadPlatformWorkKind
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRemovalKind
import com.jellyscope.core.domain.model.DownloadRemovalOperationId
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DownloadDatabaseRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseFile = File.createTempFile("download-database-recovery", ".db").also { file -> file.delete() }
    private var database: DownloadDatabase? = null

    @AfterTest
    fun tearDown() {
        database?.close()
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-journal").delete()
    }

    @Test
    fun reopenPreservesUniquenessFinalizingAndNormalizedMultiAccountRemoval() =
        runTest {
            val accountA = AccountIdentity("server-a", "user-a")
            val accountB = AccountIdentity("server-b", "user-b")
            val zeroDownloadAccount = AccountIdentity("server-c", "user-c")
            val fullLogoutAccounts = listOf(accountA, accountB, zeroDownloadAccount)
            val availableBytes = 4L * DOWNLOAD_BYTES_PER_GB

            var opened = openDatabase()
            var settingsStore = RoomDownloadSettingsStore(opened.downloadDao())
            var recordStore = RoomDownloadRecordStore(opened.downloadDao())
            var removalStore = RoomDownloadRemovalStore(opened.downloadDao())
            settingsStore.setQuotaBytes(2L * DOWNLOAD_BYTES_PER_GB)

            val requestA = request("a", accountA, reservationBytes = 256L, createdAtEpochMs = 1L)
            val requestB = request("b", accountB, reservationBytes = 512L, createdAtEpochMs = 2L)
            val createdA = assertIs<DownloadEnqueueResult.Created>(recordStore.enqueue(requestA, availableBytes)).record
            val createdB = assertIs<DownloadEnqueueResult.Created>(recordStore.enqueue(requestB, availableBytes)).record
            val duplicate =
                request(
                    suffix = "duplicate",
                    accountIdentity = accountA,
                    reservationBytes = 300L,
                    createdAtEpochMs = 3L,
                    itemId = requestA.businessKey.itemId,
                    mediaSourceId = requestA.businessKey.mediaSourceId,
                )

            val existing = assertIs<DownloadEnqueueResult.Existing>(recordStore.enqueue(duplicate, availableBytes)).record
            assertEquals(createdA.downloadId, existing.downloadId)
            assertEquals(1L, createdA.fifoSequence)
            assertEquals(2L, createdB.fifoSequence)
            assertEquals(2L, settingsStore.get().membershipRevision)
            assertEquals(3L, settingsStore.get().nextFifoSequence)
            assertEquals(1L, opened.downloadDao().recordCountForAccount(accountA.serverId, accountA.userId))
            assertEquals(listOf(0L), opened.downloadDao().physicalByteValuesForAccount(accountA.serverId, accountA.userId))

            val claimedA =
                assertNotNull(
                    recordStore.claimOldest(
                        accountIdentity = accountA,
                        platformWorkIdentity = null,
                        deviceAvailableBytes = availableBytes,
                        updatedAtEpochMs = 10L,
                    ),
                )
            assertEquals(DownloadState.Downloading, claimedA.state)
            assertEquals(1L, claimedA.attemptGeneration)
            assertNull(
                recordStore.claimOldest(
                    accountIdentity = accountB,
                    platformWorkIdentity = null,
                    deviceAvailableBytes = availableBytes,
                    updatedAtEpochMs = 11L,
                ),
            )
            assertFalse(recordStore.updateProgress(createdA.downloadId, 0L, 100L, 80L, 12L))
            assertTrue(recordStore.updateProgress(createdA.downloadId, 1L, 100L, 80L, 12L))
            assertEquals(
                DownloadReservationExtensionResult.Extended(300L),
                recordStore.extendReservation(createdA.downloadId, 1L, 300L, availableBytes),
            )
            assertTrue(
                recordStore.transition(
                    downloadId = createdA.downloadId,
                    expectedAttemptGeneration = 1L,
                    nextState = DownloadState.Finalizing,
                    updatedAtEpochMs = 13L,
                ),
            )
            assertEquals(300L, recordStore.get(createdA.downloadId)?.reservationBytes)

            opened.close()
            opened = openDatabase()
            settingsStore = RoomDownloadSettingsStore(opened.downloadDao())
            recordStore = RoomDownloadRecordStore(opened.downloadDao())
            removalStore = RoomDownloadRemovalStore(opened.downloadDao())

            val recoveredFinalizing = assertNotNull(recordStore.get(createdA.downloadId))
            assertEquals(DownloadState.Finalizing, recoveredFinalizing.state)
            assertEquals(256L, recoveredFinalizing.request.initialReservationBytes)
            assertEquals(300L, recoveredFinalizing.reservationBytes)
            assertNull(
                recordStore.claimOldest(
                    accountIdentity = accountB,
                    platformWorkIdentity = null,
                    deviceAvailableBytes = availableBytes,
                    updatedAtEpochMs = 14L,
                ),
            )
            assertTrue(recordStore.completeFinalizing(createdA.downloadId, 1L, 15L))
            val completedA = assertNotNull(recordStore.get(createdA.downloadId))
            assertEquals(DownloadState.Completed, completedA.state)
            assertEquals(100L, completedA.reservationBytes)
            assertTrue(
                recordStore.updateLocalPlayback(
                    downloadId = createdA.downloadId,
                    expectedAttemptGeneration = 1L,
                    resumePositionMs = 5_000L,
                    watched = false,
                    updatedAtEpochMs = 16L,
                ),
            )

            val claimedB =
                assertNotNull(
                    recordStore.claimOldest(
                        accountIdentity = accountB,
                        platformWorkIdentity = null,
                        deviceAvailableBytes = availableBytes,
                        updatedAtEpochMs = 17L,
                    ),
                )
            assertEquals(1L, claimedB.attemptGeneration)

            val stalePreview = removalStore.previewAll(fullLogoutAccounts)
            val requestA2 = request("a2", accountA, reservationBytes = 128L, createdAtEpochMs = 18L)
            assertIs<DownloadEnqueueResult.Created>(recordStore.enqueue(requestA2, availableBytes))
            assertIs<BeginDownloadRemovalResult.ConfirmationStale>(
                removalStore.begin(
                    operationId = DownloadRemovalOperationId("stale_removal"),
                    kind = DownloadRemovalKind.FullLogout,
                    accountIdentities = fullLogoutAccounts,
                    confirmations = stalePreview.confirmations,
                    createdAtEpochMs = 19L,
                ),
            )
            assertEquals(emptyList(), removalStore.pending())

            val confirmedPreview = removalStore.previewAll(fullLogoutAccounts)
            assertEquals(listOf(2L, 1L, 0L), confirmedPreview.confirmations.map { it.recordCount })
            val operationId = DownloadRemovalOperationId("full_logout")
            val createdOperation =
                assertIs<BeginDownloadRemovalResult.Created>(
                    removalStore.begin(
                        operationId = operationId,
                        kind = DownloadRemovalKind.FullLogout,
                        accountIdentities = fullLogoutAccounts,
                        confirmations = confirmedPreview.confirmations,
                        createdAtEpochMs = 20L,
                    ),
                ).operation
            assertEquals(3, createdOperation.targets.size)
            assertEquals(0L, createdOperation.targets.single { it.accountIdentity == zeroDownloadAccount }.cleanupGeneration)
            assertTrue(
                createdOperation.targets
                    .single { it.accountIdentity == zeroDownloadAccount }
                    .remainingArtifactKeys
                    .isEmpty(),
            )
            assertFalse(recordStore.updateProgress(createdB.downloadId, 1L, 10L, 10L, 21L))
            assertFalse(
                recordStore.updateLocalPlayback(
                    downloadId = createdA.downloadId,
                    expectedAttemptGeneration = 2L,
                    resumePositionMs = 6_000L,
                    watched = false,
                    updatedAtEpochMs = 21L,
                ),
            )
            assertEquals(DownloadState.Paused, recordStore.get(createdB.downloadId)?.state)
            assertEquals(2L, recordStore.get(createdB.downloadId)?.attemptGeneration)
            assertNull(
                recordStore.claimOldest(
                    accountIdentity = accountB,
                    platformWorkIdentity = null,
                    deviceAvailableBytes = availableBytes,
                    updatedAtEpochMs = 21L,
                ),
            )

            opened.close()
            opened = openDatabase()
            settingsStore = RoomDownloadSettingsStore(opened.downloadDao())
            recordStore = RoomDownloadRecordStore(opened.downloadDao())
            removalStore = RoomDownloadRemovalStore(opened.downloadDao())

            var persistedOperation = removalStore.pending().single()
            val targetA = persistedOperation.targets.single { it.accountIdentity == accountA }
            val targetB = persistedOperation.targets.single { it.accountIdentity == accountB }
            val targetC = persistedOperation.targets.single { it.accountIdentity == zeroDownloadAccount }
            assertTrue(removalStore.markAccountRemoved(operationId, accountA, targetA.cleanupGeneration))
            assertTrue(removalStore.updateRemainingArtifactKeys(operationId, accountB, targetB.cleanupGeneration, emptyList()))
            assertTrue(removalStore.markDownloadsRemoved(operationId, accountB, targetB.cleanupGeneration))
            assertTrue(removalStore.markAccountRemoved(operationId, zeroDownloadAccount, targetC.cleanupGeneration))
            assertTrue(removalStore.markDownloadsRemoved(operationId, zeroDownloadAccount, targetC.cleanupGeneration))
            assertFalse(removalStore.deleteIfSettled(operationId))

            opened.close()
            opened = openDatabase()
            settingsStore = RoomDownloadSettingsStore(opened.downloadDao())
            recordStore = RoomDownloadRecordStore(opened.downloadDao())
            removalStore = RoomDownloadRemovalStore(opened.downloadDao())

            persistedOperation = removalStore.pending().single()
            val persistedA = persistedOperation.targets.single { it.accountIdentity == accountA }
            val persistedB = persistedOperation.targets.single { it.accountIdentity == accountB }
            val persistedC = persistedOperation.targets.single { it.accountIdentity == zeroDownloadAccount }
            assertTrue(persistedA.accountRemoved)
            assertFalse(persistedA.downloadsRemoved)
            assertTrue(persistedA.remainingArtifactKeys.isNotEmpty())
            assertFalse(persistedB.accountRemoved)
            assertTrue(persistedB.downloadsRemoved)
            assertTrue(persistedB.remainingArtifactKeys.isEmpty())
            assertTrue(persistedC.settled)
            assertNull(recordStore.get(createdB.downloadId))
            assertNotNull(recordStore.get(createdA.downloadId))

            assertTrue(
                removalStore.updateRemainingArtifactKeys(
                    operationId,
                    accountA,
                    persistedA.cleanupGeneration,
                    emptyList(),
                ),
            )
            assertTrue(removalStore.markDownloadsRemoved(operationId, accountA, persistedA.cleanupGeneration))
            assertTrue(removalStore.markAccountRemoved(operationId, accountB, persistedB.cleanupGeneration))
            assertTrue(removalStore.deleteIfSettled(operationId))
            assertEquals(emptyList(), removalStore.pending())
            assertEquals(emptyList(), recordStore.all())
            assertEquals(5L, settingsStore.get().membershipRevision)
        }

    @Test
    fun migratingVersionOneQuotaConvertsLegacyWholeUnitsWithoutChangingDownloadByteFacts() =
        runTest {
            val account = AccountIdentity("server-migration", "user-migration")
            val availableBytes = 4L * DOWNLOAD_BYTES_PER_GB
            val request =
                request("migration", account, reservationBytes = 700_000_000L, createdAtEpochMs = 1L).copy(
                    admissionEstimateBytes = 600_000_000L,
                    expectedSourceBytes = 650_000_000L,
                )
            val physicalBytes = 123_456_789L
            val checkpointBytes = 123_450_000L

            val opened = openDatabase()
            val settingsStore = RoomDownloadSettingsStore(opened.downloadDao())
            val recordStore = RoomDownloadRecordStore(opened.downloadDao())
            settingsStore.setQuotaBytes(2L * DOWNLOAD_BYTES_PER_GB)
            val created = assertIs<DownloadEnqueueResult.Created>(recordStore.enqueue(request, availableBytes)).record
            assertNotNull(
                recordStore.claimOldest(
                    accountIdentity = account,
                    platformWorkIdentity = null,
                    deviceAvailableBytes = availableBytes,
                    updatedAtEpochMs = 2L,
                ),
            )
            assertTrue(recordStore.updateProgress(created.downloadId, 1L, physicalBytes, checkpointBytes, 3L))

            opened.close()
            database = null
            // Remove the v3 presentation column before restoring the historical v1 metadata.
            // The data-only v1-to-v2 migration leaves those two table shapes identical.
            val legacyConnection = AndroidSQLiteDriver().open(databaseFile.path)
            try {
                // Android's legacy SQLite driver cannot DROP COLUMN; rebuild the v1 table shape.
                legacyConnection.execSQL("ALTER TABLE `download_records` RENAME TO `current_download_records`")
                legacyConnection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_records` (`downloadId` TEXT NOT NULL, `serverId` TEXT NOT NULL,
                    `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `mediaSourceId` TEXT NOT NULL, `itemKindKey` TEXT
                    NOT NULL, `qualityKey` TEXT NOT NULL, `qualityBitrateBps` INTEGER, `artifactKindKey` TEXT NOT NULL,
                    `selectedAudioStreamIndex` INTEGER, `subtitleSelectionKey` TEXT NOT NULL, `subtitleStreamIndex`
                    INTEGER, `subtitleLocalAssetId` TEXT, `subtitleBurnInConfirmed` INTEGER NOT NULL,
                    `admissionEstimateBytes` INTEGER NOT NULL, `initialReservationBytes` INTEGER NOT NULL,
                    `expectedSourceBytes` INTEGER, `sourceValidator` TEXT, `artifactKey` TEXT NOT NULL,
                    `artifactFormatVersion` INTEGER NOT NULL, `snapshotEncoding` TEXT NOT NULL, `fifoSequence` INTEGER
                    NOT NULL, `stateKey` TEXT NOT NULL, `activeSlot` TEXT, `reservationBytes` INTEGER NOT NULL,
                    `physicalBytes` INTEGER NOT NULL, `checkpointBytes` INTEGER NOT NULL, `attemptGeneration` INTEGER
                    NOT NULL, `platformWorkKindKey` TEXT, `platformWorkIdentity` TEXT, `localResumePositionMs` INTEGER
                    NOT NULL, `localWatched` INTEGER NOT NULL, `failureKey` TEXT, `createdAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`downloadId`))
                    """.trimIndent(),
                )
                val legacyColumns =
                    legacyConnection.prepare("PRAGMA table_info(`download_records`)").use { statement ->
                        buildList {
                            while (statement.step()) add("`${statement.getText(1)}`")
                        }.joinToString(", ")
                    }
                legacyConnection.execSQL(
                    "INSERT INTO `download_records` ($legacyColumns) " +
                        "SELECT $legacyColumns FROM `current_download_records`",
                )
                legacyConnection.execSQL("DROP TABLE `current_download_records`")
                legacyConnection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_records_serverId_userId_itemId_mediaSourceId`" +
                        " ON `download_records` (`serverId`, `userId`, `itemId`, `mediaSourceId`)",
                )
                legacyConnection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_records_fifoSequence`" +
                        " ON `download_records` (`fifoSequence`)",
                )
                legacyConnection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_records_artifactKey`" +
                        " ON `download_records` (`artifactKey`)",
                )
                legacyConnection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_records_activeSlot`" +
                        " ON `download_records` (`activeSlot`)",
                )
                legacyConnection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_records_serverId_userId_fifoSequence`" +
                        " ON `download_records` (`serverId`, `userId`, `fifoSequence`)",
                )
                legacyConnection.execSQL("UPDATE `download_settings` SET `quotaBytes` = 2147483648 WHERE `id` = 1")
                legacyConnection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                legacyConnection.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '5bb3da797f706e1172804ce791352a2b')",
                )
                legacyConnection.execSQL("PRAGMA user_version = 1")
            } finally {
                legacyConnection.close()
            }

            val migrated = openDatabase()
            val migratedSettings = RoomDownloadSettingsStore(migrated.downloadDao()).get()
            val migratedRecord = assertNotNull(RoomDownloadRecordStore(migrated.downloadDao()).get(created.downloadId))

            assertEquals(2L * DOWNLOAD_BYTES_PER_GB, migratedSettings.quotaBytes)
            assertEquals(created.downloadId, migratedRecord.downloadId)
            assertEquals(DownloadState.Downloading, migratedRecord.state)
            assertEquals(request.expectedSourceBytes, migratedRecord.request.expectedSourceBytes)
            assertEquals(request.initialReservationBytes, migratedRecord.request.initialReservationBytes)
            assertEquals(request.admissionEstimateBytes, migratedRecord.request.admissionEstimateBytes)
            assertEquals(physicalBytes, migratedRecord.physicalBytes)
            assertEquals(checkpointBytes, migratedRecord.checkpointBytes)
            assertEquals(0L, migratedRecord.presentationBytes)
            assertEquals(700_000_000L, migratedRecord.reservationBytes)
        }

    @Test
    fun wallClockRollbackKeepsDownloadReadableAndProgressingAcrossAttemptMutations() =
        runTest {
            val account = AccountIdentity("server-clock", "user-clock")
            val availableBytes = 4L * DOWNLOAD_BYTES_PER_GB
            var opened = openDatabase()
            var settingsStore = RoomDownloadSettingsStore(opened.downloadDao())
            var recordStore = RoomDownloadRecordStore(opened.downloadDao())
            settingsStore.setQuotaBytes(2L * DOWNLOAD_BYTES_PER_GB)

            val created =
                assertIs<DownloadEnqueueResult.Created>(
                    recordStore.enqueue(
                        request("clock", account, reservationBytes = 256L, createdAtEpochMs = 1_000L),
                        availableBytes,
                    ),
                ).record

            val claimed =
                assertNotNull(
                    recordStore.claimOldest(
                        accountIdentity = account,
                        platformWorkIdentity = null,
                        deviceAvailableBytes = availableBytes,
                        updatedAtEpochMs = 900L,
                    ),
                )
            assertEquals(DownloadState.Downloading, claimed.state)
            assertEquals(1_000L, claimed.updatedAtEpochMs)

            assertTrue(recordStore.updateProgress(created.downloadId, 1L, 64L, 48L, 800L))
            assertEquals(1_000L, assertNotNull(recordStore.get(created.downloadId)).updatedAtEpochMs)

            // Reopening while the record is active must not make a rollback look stale or
            // strand the sole active attempt.
            opened.close()
            opened = openDatabase()
            recordStore = RoomDownloadRecordStore(opened.downloadDao())
            val recoveredActive = assertNotNull(recordStore.get(created.downloadId))
            assertEquals(DownloadState.Downloading, recoveredActive.state)
            assertEquals(1L, recoveredActive.attemptGeneration)
            assertEquals(1_000L, recoveredActive.updatedAtEpochMs)
            assertEquals(created.downloadId.value, opened.downloadDao().activeRecord()?.downloadId)

            val invalidated =
                assertIs<DownloadAttemptInvalidationResult.Invalidated>(
                    recordStore.checkpointAndInvalidateAttempt(
                        accountIdentity = account,
                        downloadId = created.downloadId,
                        expectedAttemptGeneration = 1L,
                        physicalBytes = 64L,
                        checkpointBytes = 48L,
                        nextState = DownloadState.Paused,
                        updatedAtEpochMs = 700L,
                    ),
                ).record
            assertEquals(DownloadState.Paused, invalidated.state)
            assertEquals(2L, invalidated.attemptGeneration)
            assertEquals(1_000L, invalidated.updatedAtEpochMs)

            assertTrue(
                recordStore.transition(
                    downloadId = created.downloadId,
                    expectedAttemptGeneration = 2L,
                    nextState = DownloadState.Queued,
                    updatedAtEpochMs = 600L,
                ),
            )
            val reclaimed =
                assertNotNull(
                    recordStore.claimOldest(
                        accountIdentity = account,
                        platformWorkIdentity = null,
                        deviceAvailableBytes = availableBytes,
                        updatedAtEpochMs = 500L,
                    ),
                )
            assertEquals(3L, reclaimed.attemptGeneration)
            assertEquals(1_000L, reclaimed.updatedAtEpochMs)
            assertTrue(recordStore.updateProgress(created.downloadId, 3L, 64L, 64L, 400L))
            assertTrue(
                recordStore.transition(
                    downloadId = created.downloadId,
                    expectedAttemptGeneration = 3L,
                    nextState = DownloadState.Finalizing,
                    updatedAtEpochMs = 300L,
                ),
            )
            assertTrue(recordStore.completeFinalizing(created.downloadId, 3L, 200L))
            assertTrue(
                recordStore.updateLocalPlayback(
                    downloadId = created.downloadId,
                    expectedAttemptGeneration = 3L,
                    resumePositionMs = 5_000L,
                    watched = false,
                    updatedAtEpochMs = 100L,
                ),
            )

            val completed = assertNotNull(recordStore.get(created.downloadId))
            assertEquals(DownloadState.Completed, completed.state)
            assertEquals(1_000L, completed.request.createdAtEpochMs)
            assertEquals(1_000L, completed.updatedAtEpochMs)
            assertEquals(5_000L, completed.localResumePositionMs)
        }

    @Test
    fun boundaryCheckpointAtomicallyInvalidatesGenerationAndRejectsRemoval() =
        runTest {
            val account = AccountIdentity("server-boundary", "user-boundary")
            val otherAccount = AccountIdentity("server-boundary", "user-other")
            val availableBytes = 4L * DOWNLOAD_BYTES_PER_GB
            val opened = openDatabase()
            val settingsStore = RoomDownloadSettingsStore(opened.downloadDao())
            val recordStore = RoomDownloadRecordStore(opened.downloadDao())
            val removalStore = RoomDownloadRemovalStore(opened.downloadDao())
            settingsStore.setQuotaBytes(2L * DOWNLOAD_BYTES_PER_GB)

            val created =
                assertIs<DownloadEnqueueResult.Created>(
                    recordStore.enqueue(
                        request("boundary", account, reservationBytes = 256L, createdAtEpochMs = 1L),
                        availableBytes,
                    ),
                ).record
            val claimed =
                assertNotNull(
                    recordStore.claimOldest(
                        accountIdentity = account,
                        platformWorkIdentity =
                            DownloadPlatformWorkIdentity(
                                DownloadPlatformWorkKind.AndroidWorkManager,
                                "work-boundary",
                            ),
                        deviceAvailableBytes = availableBytes,
                        updatedAtEpochMs = 2L,
                    ),
                )
            assertEquals(1L, claimed.attemptGeneration)
            assertTrue(recordStore.updateProgress(created.downloadId, 1L, 40L, 32L, 3L))

            assertIs<DownloadAttemptInvalidationResult.StaleAttempt>(
                recordStore.checkpointAndInvalidateAttempt(
                    accountIdentity = otherAccount,
                    downloadId = created.downloadId,
                    expectedAttemptGeneration = 1L,
                    physicalBytes = 64L,
                    checkpointBytes = 48L,
                    nextState = DownloadState.Queued,
                    updatedAtEpochMs = 4L,
                ),
            )
            assertEquals(DownloadState.Downloading, recordStore.get(created.downloadId)?.state)

            val invalidated =
                assertIs<DownloadAttemptInvalidationResult.Invalidated>(
                    recordStore.checkpointAndInvalidateAttempt(
                        accountIdentity = account,
                        downloadId = created.downloadId,
                        expectedAttemptGeneration = 1L,
                        physicalBytes = 64L,
                        checkpointBytes = 48L,
                        nextState = DownloadState.Queued,
                        updatedAtEpochMs = 5L,
                    ),
                ).record
            assertEquals(DownloadState.Queued, invalidated.state)
            assertEquals(2L, invalidated.attemptGeneration)
            assertEquals(64L, invalidated.physicalBytes)
            assertEquals(48L, invalidated.checkpointBytes)
            assertNull(invalidated.platformWorkIdentity)
            assertFalse(recordStore.updateProgress(created.downloadId, 1L, 65L, 49L, 6L))

            val reclaimed =
                assertNotNull(
                    recordStore.claimOldest(
                        accountIdentity = account,
                        platformWorkIdentity = null,
                        deviceAvailableBytes = availableBytes,
                        updatedAtEpochMs = 6L,
                    ),
                )
            assertEquals(3L, reclaimed.attemptGeneration)
            assertTrue(
                recordStore.transition(
                    downloadId = created.downloadId,
                    expectedAttemptGeneration = 3L,
                    nextState = DownloadState.Finalizing,
                    updatedAtEpochMs = 7L,
                ),
            )
            assertIs<DownloadAttemptInvalidationResult.Finalizing>(
                recordStore.checkpointAndInvalidateAttempt(
                    accountIdentity = account,
                    downloadId = created.downloadId,
                    expectedAttemptGeneration = 3L,
                    physicalBytes = 64L,
                    checkpointBytes = 48L,
                    nextState = DownloadState.Paused,
                    updatedAtEpochMs = 8L,
                ),
            )

            val preview = removalStore.preview(account)
            assertIs<BeginDownloadRemovalResult.Created>(
                removalStore.begin(
                    operationId = DownloadRemovalOperationId("boundary_removal"),
                    kind = DownloadRemovalKind.SingleAccount,
                    accountIdentities = listOf(account),
                    confirmations = preview.confirmations,
                    createdAtEpochMs = 9L,
                ),
            )
            assertIs<DownloadAttemptInvalidationResult.RemovalInProgress>(
                recordStore.checkpointAndInvalidateAttempt(
                    accountIdentity = account,
                    downloadId = created.downloadId,
                    expectedAttemptGeneration = 4L,
                    physicalBytes = 64L,
                    checkpointBytes = 48L,
                    nextState = DownloadState.Queued,
                    updatedAtEpochMs = 10L,
                ),
            )
            assertFalse(recordStore.updateProgress(created.downloadId, 3L, 65L, 49L, 10L))
        }

    private suspend fun openDatabase(): DownloadDatabase {
        val instance =
            Room
                .databaseBuilder<DownloadDatabase>(context, databaseFile.path)
                .buildDownloadDatabase(driver = AndroidSQLiteDriver())
        database = instance
        RoomDownloadSettingsStore(instance.downloadDao()).get()
        return instance
    }

    private fun request(
        suffix: String,
        accountIdentity: AccountIdentity,
        reservationBytes: Long,
        createdAtEpochMs: Long,
        itemId: String = "item_$suffix",
        mediaSourceId: String = "source_$suffix",
    ): DownloadRequest =
        DownloadRequest(
            downloadId = DownloadId("download_$suffix"),
            businessKey = DownloadBusinessKey(accountIdentity, itemId, mediaSourceId),
            quality = DownloadQuality.Original,
            artifactKind = DownloadArtifactKind.OriginalFile,
            selectedAudioStreamIndex = null,
            subtitleSelection = DownloadSubtitleSelection.Off,
            admissionEstimateBytes = reservationBytes,
            initialReservationBytes = reservationBytes,
            expectedSourceBytes = reservationBytes,
            sourceValidator = "validator-$suffix",
            artifactKey = DownloadArtifactKey("artifact_$suffix"),
            snapshot =
                OfflineMediaSnapshot(
                    title = "Title $suffix",
                    itemKind = MediaKind.Movie,
                    backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                ),
            createdAtEpochMs = createdAtEpochMs,
        )
}
