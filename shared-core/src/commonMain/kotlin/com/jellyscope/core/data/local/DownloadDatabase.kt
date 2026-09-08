// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Transaction
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.BeginDownloadRemovalResult
import com.jellyscope.core.domain.model.DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES
import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadRemovalConfirmation
import com.jellyscope.core.domain.model.DownloadRemovalKind
import com.jellyscope.core.domain.model.DownloadRemovalOperation
import com.jellyscope.core.domain.model.DownloadRemovalOperationId
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.DownloadRemovalTarget
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.calculateDownloadUsage
import com.jellyscope.core.domain.model.canTransitionTo
import com.jellyscope.core.domain.model.evaluateDownloadAdmission
import com.jellyscope.core.domain.model.nextDownloadAttemptGeneration
import com.jellyscope.core.domain.model.ownsActiveDownloadSlot
import com.jellyscope.core.domain.model.retainsOutstandingReservation
import com.jellyscope.core.domain.model.saturatingAddNonNegative
import kotlinx.coroutines.flow.Flow

internal const val DOWNLOAD_DATABASE_FILE_NAME = "download-store.db"
internal const val DOWNLOAD_DATABASE_SCHEMA_VERSION = 2

private val DOWNLOAD_DATABASE_MIGRATION_1_2 =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "UPDATE `download_settings` SET `quotaBytes` = CASE " +
                    "WHEN `quotaBytes` IS NULL THEN NULL " +
                    "WHEN `quotaBytes` >= 1073741824 AND `quotaBytes` % 1073741824 = 0 " +
                    "THEN (`quotaBytes` / 1073741824) * 1000000000 " +
                    "ELSE NULL END",
            )
        }
    }

@Dao
internal interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSettings(entity: DownloadSettingsEntity): Long

    @Query("SELECT * FROM download_settings WHERE id = 1 LIMIT 1")
    suspend fun settings(): DownloadSettingsEntity?

    @Query("UPDATE download_settings SET quotaBytes = :quotaBytes WHERE id = 1")
    suspend fun updateQuota(quotaBytes: Long?): Int

    @Query(
        "UPDATE download_settings SET nextFifoSequence = :nextFifoSequence, " +
            "membershipRevision = :nextMembershipRevision WHERE id = 1 " +
            "AND nextFifoSequence = :expectedFifoSequence AND membershipRevision = :expectedMembershipRevision",
    )
    suspend fun advanceFifoAndMembership(
        expectedFifoSequence: Long,
        expectedMembershipRevision: Long,
        nextFifoSequence: Long,
        nextMembershipRevision: Long,
    ): Int

    @Query(
        "UPDATE download_settings SET membershipRevision = :nextMembershipRevision WHERE id = 1 " +
            "AND membershipRevision = :expectedMembershipRevision",
    )
    suspend fun advanceMembership(
        expectedMembershipRevision: Long,
        nextMembershipRevision: Long,
    ): Int

    @Transaction
    suspend fun ensureSettings(): DownloadSettingsEntity {
        insertSettings(
            DownloadSettingsEntity(
                quotaBytes = null,
                nextFifoSequence = 1L,
                membershipRevision = 0L,
            ),
        )
        return checkNotNull(settings()) { "Download settings singleton could not be created." }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecord(entity: DownloadRecordEntity)

    @Query("SELECT * FROM download_records WHERE downloadId = :downloadId LIMIT 1")
    suspend fun record(downloadId: String): DownloadRecordEntity?

    @Query(
        "SELECT * FROM download_records WHERE downloadId = :downloadId " +
            "AND attemptGeneration = :attemptGeneration LIMIT 1",
    )
    suspend fun record(
        downloadId: String,
        attemptGeneration: Long,
    ): DownloadRecordEntity?

    @Query(
        "SELECT * FROM download_records WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId = :itemId AND mediaSourceId = :mediaSourceId LIMIT 1",
    )
    suspend fun recordByBusinessKey(
        serverId: String,
        userId: String,
        itemId: String,
        mediaSourceId: String,
    ): DownloadRecordEntity?

    @Query("SELECT * FROM download_records ORDER BY fifoSequence")
    suspend fun allRecords(): List<DownloadRecordEntity>

    @Query(
        "SELECT * FROM download_records WHERE serverId = :serverId AND userId = :userId " +
            "ORDER BY fifoSequence",
    )
    suspend fun recordsForAccount(
        serverId: String,
        userId: String,
    ): List<DownloadRecordEntity>

    @Query("SELECT COUNT(*) FROM download_records WHERE serverId = :serverId AND userId = :userId")
    suspend fun recordCountForAccount(
        serverId: String,
        userId: String,
    ): Long

    @Query("SELECT physicalBytes FROM download_records WHERE serverId = :serverId AND userId = :userId")
    suspend fun physicalByteValuesForAccount(
        serverId: String,
        userId: String,
    ): List<Long>

    @Query(
        "SELECT * FROM download_records WHERE serverId = :serverId AND userId = :userId " +
            "ORDER BY fifoSequence",
    )
    fun observeRecordsForAccount(
        serverId: String,
        userId: String,
    ): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM download_records WHERE activeSlot = 'active' LIMIT 1")
    suspend fun activeRecord(): DownloadRecordEntity?

    @Query(
        "SELECT * FROM download_records WHERE serverId = :serverId AND userId = :userId " +
            "AND stateKey IN ('queued-v1', 'blocked-by-quota-v1') ORDER BY fifoSequence LIMIT 1",
    )
    suspend fun oldestEligibleRecord(
        serverId: String,
        userId: String,
    ): DownloadRecordEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM download_removal_targets " +
            "WHERE serverId = :serverId AND userId = :userId)",
    )
    suspend fun hasPendingRemovalForAccount(
        serverId: String,
        userId: String,
    ): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM download_records AS record " +
            "INNER JOIN download_removal_targets AS target " +
            "ON target.serverId = record.serverId AND target.userId = record.userId " +
            "WHERE record.downloadId = :downloadId)",
    )
    suspend fun hasPendingRemovalForRecord(downloadId: String): Boolean

    @Transaction
    suspend fun enqueueRecord(
        entity: DownloadRecordEntity,
        deviceAvailableBytes: Long,
        safetyReserveBytes: Long = DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES,
    ): DownloadEnqueueResult {
        require(deviceAvailableBytes >= 0L)
        require(safetyReserveBytes >= 0L)
        if (hasPendingRemovalForAccount(entity.serverId, entity.userId)) {
            return DownloadEnqueueResult.RemovalInProgress
        }
        recordByBusinessKey(entity.serverId, entity.userId, entity.itemId, entity.mediaSourceId)?.let { existing ->
            val record = checkNotNull(existing.toModelOrNull()) { "Existing download row is unreadable." }
            return DownloadEnqueueResult.Existing(record)
        }

        val currentSettings = ensureSettings()
        val usage = currentUsage(currentSettings, deviceAvailableBytes, safetyReserveBytes)
        val decision = evaluateDownloadAdmission(usage, entity.reservationBytes)
        if (decision != DownloadAdmissionDecision.Allowed) {
            return DownloadEnqueueResult.Rejected(decision)
        }
        check(currentSettings.nextFifoSequence < Long.MAX_VALUE) { "Download FIFO sequence exhausted." }
        check(currentSettings.membershipRevision < Long.MAX_VALUE) { "Download membership revision exhausted." }
        val inserted = entity.copy(fifoSequence = currentSettings.nextFifoSequence)
        insertRecord(inserted)
        check(
            advanceFifoAndMembership(
                expectedFifoSequence = currentSettings.nextFifoSequence,
                expectedMembershipRevision = currentSettings.membershipRevision,
                nextFifoSequence = currentSettings.nextFifoSequence + 1L,
                nextMembershipRevision = currentSettings.membershipRevision + 1L,
            ) == 1,
        ) { "Download sequence changed during enqueue." }
        return DownloadEnqueueResult.Created(
            checkNotNull(inserted.toModelOrNull()) { "New download row is unreadable." },
        )
    }

    @Query(
        "UPDATE download_records SET stateKey = 'downloading-v1', activeSlot = 'active', " +
            "attemptGeneration = :nextAttemptGeneration, platformWorkKindKey = :platformWorkKindKey, " +
            "platformWorkIdentity = :platformWorkIdentity, failureKey = NULL, " +
            "updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) " +
            "WHERE downloadId = :downloadId AND attemptGeneration = :expectedAttemptGeneration " +
            "AND stateKey = 'queued-v1'",
    )
    suspend fun claimQueuedRecord(
        downloadId: String,
        expectedAttemptGeneration: Long,
        nextAttemptGeneration: Long,
        platformWorkKindKey: String?,
        platformWorkIdentity: String?,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        "UPDATE download_records SET stateKey = 'downloading-v1', activeSlot = 'active', " +
            "attemptGeneration = :nextAttemptGeneration, platformWorkKindKey = :platformWorkKindKey, " +
            "platformWorkIdentity = :platformWorkIdentity, failureKey = NULL, " +
            "updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) " +
            "WHERE downloadId = :downloadId AND attemptGeneration = :expectedAttemptGeneration " +
            "AND stateKey = 'blocked-by-quota-v1'",
    )
    suspend fun claimBlockedRecord(
        downloadId: String,
        expectedAttemptGeneration: Long,
        nextAttemptGeneration: Long,
        platformWorkKindKey: String?,
        platformWorkIdentity: String?,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        "UPDATE download_records SET stateKey = 'blocked-by-quota-v1', activeSlot = NULL, " +
            "platformWorkKindKey = NULL, platformWorkIdentity = NULL, failureKey = NULL, " +
            "updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) WHERE downloadId = :downloadId " +
            "AND attemptGeneration = :expectedAttemptGeneration AND stateKey = 'queued-v1'",
    )
    suspend fun blockQueuedRecord(
        downloadId: String,
        expectedAttemptGeneration: Long,
        updatedAtEpochMs: Long,
    ): Int

    @Transaction
    suspend fun claimOldestQueuedRecord(
        serverId: String,
        userId: String,
        platformWorkKindKey: String?,
        platformWorkIdentity: String?,
        deviceAvailableBytes: Long,
        updatedAtEpochMs: Long,
    ): DownloadRecordEntity? {
        require(deviceAvailableBytes >= 0L)
        if ((platformWorkKindKey == null) != (platformWorkIdentity == null)) return null
        if (hasPendingRemovalForAccount(serverId, userId)) return null
        if (activeRecord() != null) return null
        val oldest = oldestEligibleRecord(serverId, userId) ?: return null

        val usage = currentUsage(ensureSettings(), deviceAvailableBytes, DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES)
        val quotaAllowsClaim =
            usage.quotaBytes != null &&
                !usage.overAllocation &&
                usage.projectedCommittedBytes <= usage.maximumConfigurableQuotaBytes
        if (!quotaAllowsClaim) {
            blockQueuedRecord(oldest.downloadId, oldest.attemptGeneration, updatedAtEpochMs)
            return null
        }

        val nextGeneration = nextDownloadAttemptGeneration(oldest.attemptGeneration) ?: return null
        if (
            (
                if (oldest.stateKey == STATE_BLOCKED_BY_QUOTA) {
                    claimBlockedRecord(
                        downloadId = oldest.downloadId,
                        expectedAttemptGeneration = oldest.attemptGeneration,
                        nextAttemptGeneration = nextGeneration,
                        platformWorkKindKey = platformWorkKindKey,
                        platformWorkIdentity = platformWorkIdentity,
                        updatedAtEpochMs = updatedAtEpochMs,
                    )
                } else {
                    claimQueuedRecord(
                        downloadId = oldest.downloadId,
                        expectedAttemptGeneration = oldest.attemptGeneration,
                        nextAttemptGeneration = nextGeneration,
                        platformWorkKindKey = platformWorkKindKey,
                        platformWorkIdentity = platformWorkIdentity,
                        updatedAtEpochMs = updatedAtEpochMs,
                    )
                }
            ) != 1
        ) {
            return null
        }
        return record(oldest.downloadId, nextGeneration)
    }

    @Query(
        "UPDATE download_records SET physicalBytes = :physicalBytes, checkpointBytes = :checkpointBytes, " +
            "updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) WHERE downloadId = :downloadId " +
            "AND attemptGeneration = :expectedAttemptGeneration " +
            "AND stateKey IN ('downloading-v1', 'finalizing-v1') " +
            "AND :checkpointBytes >= 0 AND :checkpointBytes <= :physicalBytes " +
            "AND :physicalBytes >= physicalBytes AND :physicalBytes <= reservationBytes",
    )
    suspend fun updateProgressForAttempt(
        downloadId: String,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        updatedAtEpochMs: Long,
    ): Int

    @Transaction
    suspend fun updateProgressForCurrentAttempt(
        downloadId: String,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        updatedAtEpochMs: Long,
    ): Int {
        if (hasPendingRemovalForRecord(downloadId)) return 0
        return updateProgressForAttempt(
            downloadId,
            expectedAttemptGeneration,
            physicalBytes,
            checkpointBytes,
            updatedAtEpochMs,
        )
    }

    /**
     * Persists the exact Original source facts proved by the current authenticated preflight.
     * These facts are stable, credential-free validators used by the next resume; they never
     * include the request URL or an access token.
     */
    @Query(
        "UPDATE download_records SET expectedSourceBytes = :expectedSourceBytes, " +
            "sourceValidator = :sourceValidator WHERE downloadId = :downloadId " +
            "AND attemptGeneration = :expectedAttemptGeneration " +
            "AND stateKey = 'downloading-v1' AND :expectedSourceBytes > 0 " +
            "AND :sourceValidator != ''",
    )
    suspend fun updateOriginalSourceFactsRaw(
        downloadId: String,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Int

    @Transaction
    suspend fun updateOriginalSourceFacts(
        downloadId: String,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean {
        require(expectedSourceBytes > 0L)
        require(sourceValidator.isNotBlank())
        if (hasPendingRemovalForRecord(downloadId)) return false
        return updateOriginalSourceFactsRaw(
            downloadId = downloadId,
            expectedAttemptGeneration = expectedAttemptGeneration,
            expectedSourceBytes = expectedSourceBytes,
            sourceValidator = sourceValidator,
        ) == 1
    }

    @Query(
        "UPDATE download_records SET physicalBytes = :physicalBytes, checkpointBytes = :checkpointBytes, " +
            "attemptGeneration = :nextAttemptGeneration, stateKey = :nextStateKey, activeSlot = NULL, " +
            "platformWorkKindKey = NULL, platformWorkIdentity = NULL, failureKey = NULL, " +
            "updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) WHERE downloadId = :downloadId " +
            "AND serverId = :serverId AND userId = :userId " +
            "AND attemptGeneration = :expectedAttemptGeneration AND stateKey = 'downloading-v1' " +
            "AND :checkpointBytes >= checkpointBytes AND :checkpointBytes <= :physicalBytes " +
            "AND :physicalBytes >= physicalBytes AND :physicalBytes <= reservationBytes",
    )
    suspend fun checkpointAndInvalidateDownloadingAttemptRaw(
        serverId: String,
        userId: String,
        downloadId: String,
        expectedAttemptGeneration: Long,
        nextAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        nextStateKey: String,
        updatedAtEpochMs: Long,
    ): Int

    /**
     * Atomically persists the durable artifact checkpoint and revokes the
     * active generation before a session boundary publishes a new account.
     */
    @Transaction
    suspend fun checkpointAndInvalidateDownloadingAttempt(
        serverId: String,
        userId: String,
        downloadId: String,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        nextState: DownloadState,
        updatedAtEpochMs: Long,
    ): DownloadAttemptInvalidationResult {
        require(nextState == DownloadState.Queued || nextState == DownloadState.Paused)
        require(expectedAttemptGeneration >= 0L)
        require(checkpointBytes in 0L..physicalBytes)
        require(updatedAtEpochMs >= 0L)
        if (hasPendingRemovalForRecord(downloadId)) {
            return DownloadAttemptInvalidationResult.RemovalInProgress
        }
        val current =
            record(downloadId, expectedAttemptGeneration)
                ?: return DownloadAttemptInvalidationResult.StaleAttempt
        if (current.serverId != serverId || current.userId != userId) {
            return DownloadAttemptInvalidationResult.StaleAttempt
        }
        val currentModel =
            current.toModelOrNull()
                ?: return DownloadAttemptInvalidationResult.StaleAttempt
        if (currentModel.state == DownloadState.Finalizing) {
            return DownloadAttemptInvalidationResult.Finalizing(currentModel)
        }
        if (currentModel.state != DownloadState.Downloading) {
            return DownloadAttemptInvalidationResult.StaleAttempt
        }
        val nextGeneration =
            nextDownloadAttemptGeneration(expectedAttemptGeneration)
                ?: return DownloadAttemptInvalidationResult.StaleAttempt
        if (
            checkpointAndInvalidateDownloadingAttemptRaw(
                serverId = serverId,
                userId = userId,
                downloadId = downloadId,
                expectedAttemptGeneration = expectedAttemptGeneration,
                nextAttemptGeneration = nextGeneration,
                physicalBytes = physicalBytes,
                checkpointBytes = checkpointBytes,
                nextStateKey = nextState.toStateKeyForMutation(),
                updatedAtEpochMs = updatedAtEpochMs,
            ) != 1
        ) {
            return DownloadAttemptInvalidationResult.StaleAttempt
        }
        val invalidated =
            record(downloadId, nextGeneration)?.toModelOrNull()
                ?: return DownloadAttemptInvalidationResult.StaleAttempt
        return DownloadAttemptInvalidationResult.Invalidated(invalidated)
    }

    @Query(
        "UPDATE download_records SET reservationBytes = :requiredReservationBytes " +
            "WHERE downloadId = :downloadId AND attemptGeneration = :expectedAttemptGeneration " +
            "AND reservationBytes = :expectedReservationBytes " +
            "AND stateKey IN ('queued-v1', 'downloading-v1', 'paused-v1', 'blocked-by-quota-v1', 'finalizing-v1') " +
            "AND :requiredReservationBytes >= physicalBytes",
    )
    suspend fun updateReservationForAttempt(
        downloadId: String,
        expectedAttemptGeneration: Long,
        expectedReservationBytes: Long,
        requiredReservationBytes: Long,
    ): Int

    @Transaction
    suspend fun extendReservationForCurrentAttempt(
        downloadId: String,
        expectedAttemptGeneration: Long,
        requiredReservationBytes: Long,
        deviceAvailableBytes: Long,
    ): DownloadReservationExtensionResult {
        require(requiredReservationBytes > 0L)
        require(deviceAvailableBytes >= 0L)
        val current =
            record(downloadId, expectedAttemptGeneration)
                ?: return DownloadReservationExtensionResult.StaleAttempt
        val state =
            current.stateKey.toDownloadStateOrNull()
                ?: return DownloadReservationExtensionResult.StaleAttempt
        if (!state.retainsOutstandingReservation) return DownloadReservationExtensionResult.StaleAttempt
        if (hasPendingRemovalForRecord(downloadId)) return DownloadReservationExtensionResult.RemovalInProgress
        val usage =
            currentUsage(
                ensureSettings(),
                deviceAvailableBytes,
                DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES,
            )
        // A progress checkpoint may be validating an existing reservation rather than extending
        // it. Lowering or removing either limit must stop the active writer at that bounded
        // checkpoint instead of returning Unchanged from stale admission facts.
        if (usage.quotaBytes == null) {
            return DownloadReservationExtensionResult.Rejected(DownloadAdmissionDecision.QuotaUnconfigured)
        }
        if (usage.overAllocation) {
            return DownloadReservationExtensionResult.Rejected(DownloadAdmissionDecision.QuotaExceeded)
        }
        if (current.reservationBytes > usage.maximumConfigurableQuotaBytes) {
            return DownloadReservationExtensionResult.Rejected(DownloadAdmissionDecision.DeviceStorageLow)
        }
        if (requiredReservationBytes <= current.reservationBytes) {
            return DownloadReservationExtensionResult.Unchanged(current.reservationBytes)
        }
        val additionalBytes = requiredReservationBytes - current.reservationBytes
        val decision = evaluateDownloadAdmission(usage, additionalBytes)
        if (decision != DownloadAdmissionDecision.Allowed) {
            return DownloadReservationExtensionResult.Rejected(decision)
        }
        if (
            updateReservationForAttempt(
                downloadId = downloadId,
                expectedAttemptGeneration = expectedAttemptGeneration,
                expectedReservationBytes = current.reservationBytes,
                requiredReservationBytes = requiredReservationBytes,
            ) != 1
        ) {
            return DownloadReservationExtensionResult.StaleAttempt
        }
        return DownloadReservationExtensionResult.Extended(requiredReservationBytes)
    }

    @Query(
        "UPDATE download_records SET stateKey = :nextStateKey, activeSlot = :activeSlot, " +
            "platformWorkKindKey = :platformWorkKindKey, platformWorkIdentity = :platformWorkIdentity, " +
            "failureKey = :failureKey, updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) " +
            "WHERE downloadId = :downloadId AND attemptGeneration = :expectedAttemptGeneration " +
            "AND stateKey = :expectedStateKey",
    )
    suspend fun updateAttemptState(
        downloadId: String,
        expectedAttemptGeneration: Long,
        expectedStateKey: String,
        nextStateKey: String,
        activeSlot: String?,
        platformWorkKindKey: String?,
        platformWorkIdentity: String?,
        failureKey: String?,
        updatedAtEpochMs: Long,
    ): Int

    @Transaction
    suspend fun transitionAttempt(
        downloadId: String,
        expectedAttemptGeneration: Long,
        nextState: DownloadState,
        platformWorkKindKey: String?,
        platformWorkIdentity: String?,
        failureKey: String?,
        updatedAtEpochMs: Long,
    ): Boolean {
        if (hasPendingRemovalForRecord(downloadId)) return false
        val current = record(downloadId, expectedAttemptGeneration) ?: return false
        val currentState = current.stateKey.toDownloadStateOrNull() ?: return false
        if (nextState == DownloadState.NotDownloaded || !currentState.canTransitionTo(nextState)) return false
        if ((nextState == DownloadState.Failed) != (failureKey != null)) return false
        if (!nextState.ownsActiveDownloadSlot && (platformWorkKindKey != null || platformWorkIdentity != null)) return false
        if ((platformWorkKindKey == null) != (platformWorkIdentity == null)) return false
        return updateAttemptState(
            downloadId = downloadId,
            expectedAttemptGeneration = expectedAttemptGeneration,
            expectedStateKey = current.stateKey,
            nextStateKey = nextState.toStateKeyForMutation(),
            activeSlot = if (nextState.ownsActiveDownloadSlot) DOWNLOAD_ACTIVE_SLOT else null,
            platformWorkKindKey = platformWorkKindKey,
            platformWorkIdentity = platformWorkIdentity,
            failureKey = failureKey,
            updatedAtEpochMs = updatedAtEpochMs,
        ) == 1
    }

    @Query(
        "UPDATE download_records SET stateKey = 'completed-v1', activeSlot = NULL, " +
            "reservationBytes = physicalBytes, platformWorkKindKey = NULL, platformWorkIdentity = NULL, " +
            "failureKey = NULL, updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) " +
            "WHERE downloadId = :downloadId AND attemptGeneration = :expectedAttemptGeneration " +
            "AND stateKey = 'finalizing-v1'",
    )
    suspend fun completeFinalizingAttemptRaw(
        downloadId: String,
        expectedAttemptGeneration: Long,
        updatedAtEpochMs: Long,
    ): Int

    @Transaction
    suspend fun completeFinalizingAttempt(
        downloadId: String,
        expectedAttemptGeneration: Long,
        updatedAtEpochMs: Long,
    ): Int {
        if (hasPendingRemovalForRecord(downloadId)) return 0
        return completeFinalizingAttemptRaw(downloadId, expectedAttemptGeneration, updatedAtEpochMs)
    }

    @Query(
        "UPDATE download_records SET localResumePositionMs = :localResumePositionMs, " +
            "localWatched = COALESCE(:localWatched, localWatched), updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) " +
            "WHERE downloadId = :downloadId AND attemptGeneration = :expectedAttemptGeneration " +
            "AND stateKey = 'completed-v1' AND :localResumePositionMs >= 0",
    )
    suspend fun updateLocalPlaybackForAttemptRaw(
        downloadId: String,
        expectedAttemptGeneration: Long,
        localResumePositionMs: Long,
        localWatched: Boolean?,
        updatedAtEpochMs: Long,
    ): Int

    @Transaction
    suspend fun updateLocalPlaybackForAttempt(
        downloadId: String,
        expectedAttemptGeneration: Long,
        localResumePositionMs: Long,
        localWatched: Boolean?,
        updatedAtEpochMs: Long,
    ): Int {
        if (hasPendingRemovalForRecord(downloadId)) return 0
        return updateLocalPlaybackForAttemptRaw(
            downloadId,
            expectedAttemptGeneration,
            localResumePositionMs,
            localWatched,
            updatedAtEpochMs,
        )
    }

    @Query("DELETE FROM download_records WHERE downloadId = :downloadId")
    suspend fun deleteRecord(downloadId: String): Int

    @Query("DELETE FROM download_records WHERE serverId = :serverId AND userId = :userId")
    suspend fun deleteRecordsForAccount(
        serverId: String,
        userId: String,
    ): Int

    @Transaction
    suspend fun deleteRecordAndAdvanceMembership(downloadId: String): Boolean {
        ensureSettings()
        if (hasPendingRemovalForRecord(downloadId)) return false
        if (deleteRecord(downloadId) != 1) return false
        advanceMembershipAfterMutation()
        return true
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRemovalOperationHeader(entity: DownloadRemovalOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRemovalTargets(entities: List<DownloadRemovalTargetEntity>)

    @Query("SELECT * FROM download_removal_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun removalOperationHeader(operationId: String): DownloadRemovalOperationEntity?

    @Query("SELECT * FROM download_removal_operations ORDER BY createdAtEpochMs, operationId")
    suspend fun removalOperationHeaders(): List<DownloadRemovalOperationEntity>

    @Query(
        "SELECT * FROM download_removal_targets WHERE operationId = :operationId " +
            "ORDER BY serverId, userId",
    )
    suspend fun removalTargets(operationId: String): List<DownloadRemovalTargetEntity>

    @Query(
        "SELECT * FROM download_removal_targets WHERE operationId = :operationId " +
            "AND serverId = :serverId AND userId = :userId LIMIT 1",
    )
    suspend fun removalTarget(
        operationId: String,
        serverId: String,
        userId: String,
    ): DownloadRemovalTargetEntity?

    @Transaction
    suspend fun removalPreview(
        serverId: String,
        userId: String,
    ): DownloadRemovalPreview {
        val currentSettings = ensureSettings()
        val account = AccountIdentity(serverId, userId)
        return DownloadRemovalPreview(
            membershipRevision = currentSettings.membershipRevision,
            confirmations = listOf(accountConfirmation(account, currentSettings.membershipRevision)),
        )
    }

    @Transaction
    suspend fun removalPreviewAll(accountIdentities: List<AccountIdentity>): DownloadRemovalPreview {
        require(accountIdentities.distinct().size == accountIdentities.size) {
            "Full-logout account scope cannot contain duplicates."
        }
        val currentSettings = ensureSettings()
        val confirmations =
            accountIdentities
                .sortedWith(compareBy(AccountIdentity::serverId, AccountIdentity::userId))
                .map { account -> accountConfirmation(account, currentSettings.membershipRevision) }
        return DownloadRemovalPreview(currentSettings.membershipRevision, confirmations)
    }

    @Query(
        "UPDATE download_records SET attemptGeneration = attemptGeneration + 1, " +
            "stateKey = CASE WHEN stateKey IN ('downloading-v1', 'finalizing-v1') THEN 'paused-v1' ELSE stateKey END, " +
            "activeSlot = NULL, platformWorkKindKey = NULL, platformWorkIdentity = NULL, " +
            "failureKey = CASE WHEN stateKey IN ('downloading-v1', 'finalizing-v1') THEN NULL ELSE failureKey END, " +
            "updatedAtEpochMs = MAX(updatedAtEpochMs, :updatedAtEpochMs) " +
            "WHERE serverId = :serverId AND userId = :userId",
    )
    suspend fun invalidateAccountAttempts(
        serverId: String,
        userId: String,
        updatedAtEpochMs: Long,
    ): Int

    @Transaction
    suspend fun beginRemovalOperation(
        operationId: String,
        kind: DownloadRemovalKind,
        accountIdentities: List<AccountIdentity>,
        confirmations: List<DownloadRemovalConfirmation>,
        createdAtEpochMs: Long,
    ): BeginDownloadRemovalResult {
        require(createdAtEpochMs >= 0L)
        if (removalOperationHeader(operationId) != null) {
            return BeginDownloadRemovalResult.RemovalAlreadyInProgress
        }
        if (
            accountIdentities.distinct().size != accountIdentities.size ||
            confirmations.map { it.accountIdentity }.distinct().size != confirmations.size
        ) {
            return BeginDownloadRemovalResult.ConfirmationStale
        }
        if (
            kind == DownloadRemovalKind.SingleAccount &&
            (accountIdentities.size != 1 || confirmations.size != 1)
        ) {
            return BeginDownloadRemovalResult.ConfirmationStale
        }
        val accountScope = accountIdentities.toSet()
        val confirmedAccounts = confirmations.map { confirmation -> confirmation.accountIdentity }.toSet()
        if (accountScope != confirmedAccounts) return BeginDownloadRemovalResult.ConfirmationStale

        val currentSettings = ensureSettings()
        if (confirmations.any { confirmation -> confirmation.membershipRevision != currentSettings.membershipRevision }) {
            return BeginDownloadRemovalResult.ConfirmationStale
        }

        val currentRecords = allRecords()
        val currentAccounts = currentRecords.map { record -> record.accountIdentity }.toSet()
        if (kind == DownloadRemovalKind.FullLogout && !accountScope.containsAll(currentAccounts)) {
            return BeginDownloadRemovalResult.ConfirmationStale
        }
        confirmations.forEach { confirmation ->
            val matching = currentRecords.filter { record -> record.accountIdentity == confirmation.accountIdentity }
            val current = confirmationFor(confirmation.accountIdentity, currentSettings.membershipRevision, matching)
            if (current.recordCount != confirmation.recordCount || current.displayedBytes != confirmation.displayedBytes) {
                return BeginDownloadRemovalResult.ConfirmationStale
            }
            if (hasPendingRemovalForAccount(confirmation.accountIdentity.serverId, confirmation.accountIdentity.userId)) {
                return BeginDownloadRemovalResult.RemovalAlreadyInProgress
            }
        }
        if (confirmations.all { confirmation -> confirmation.recordCount == 0L }) {
            return BeginDownloadRemovalResult.NoDownloads
        }

        val targets =
            confirmations.map { confirmation ->
                val matching = currentRecords.filter { record -> record.accountIdentity == confirmation.accountIdentity }
                check(matching.all { record -> record.attemptGeneration < Long.MAX_VALUE }) {
                    "Download attempt generation exhausted during removal."
                }
                val cleanupGeneration =
                    matching.maxOfOrNull { record -> record.attemptGeneration + 1L } ?: 0L
                DownloadRemovalTarget(
                    operationId = DownloadRemovalOperationId(operationId),
                    accountIdentity = confirmation.accountIdentity,
                    observedMembershipRevision = currentSettings.membershipRevision,
                    cleanupGeneration = cleanupGeneration,
                    remainingArtifactKeys = matching.map { record -> DownloadArtifactKey(record.artifactKey) },
                    accountRemoved = false,
                    downloadsRemoved = false,
                )
            }
        val operation =
            DownloadRemovalOperation(
                operationId = DownloadRemovalOperationId(operationId),
                kind = kind,
                createdAtEpochMs = createdAtEpochMs,
                targets = targets,
            )
        insertRemovalOperationHeader(operation.toEntity())
        insertRemovalTargets(targets.map(DownloadRemovalTarget::toEntity))
        targets.forEach { target ->
            val invalidated =
                invalidateAccountAttempts(
                    target.accountIdentity.serverId,
                    target.accountIdentity.userId,
                    createdAtEpochMs,
                )
            val expectedCount = confirmations.single { confirmation -> confirmation.accountIdentity == target.accountIdentity }.recordCount
            check(invalidated.toLong() == expectedCount) { "Download membership changed during removal preparation." }
        }
        return BeginDownloadRemovalResult.Created(operation)
    }

    @Query(
        "UPDATE download_removal_targets SET accountRemoved = 1 WHERE operationId = :operationId " +
            "AND serverId = :serverId AND userId = :userId AND cleanupGeneration = :cleanupGeneration",
    )
    suspend fun markRemovalTargetAccountRemoved(
        operationId: String,
        serverId: String,
        userId: String,
        cleanupGeneration: Long,
    ): Int

    @Query(
        "UPDATE download_removal_targets SET remainingArtifactKeysEncoding = :artifactKeysEncoding, " +
            "downloadsRemoved = 0 WHERE operationId = :operationId AND serverId = :serverId " +
            "AND userId = :userId AND cleanupGeneration = :cleanupGeneration AND downloadsRemoved = 0",
    )
    suspend fun updateRemovalTargetArtifactKeys(
        operationId: String,
        serverId: String,
        userId: String,
        cleanupGeneration: Long,
        artifactKeysEncoding: String,
    ): Int

    @Query(
        "UPDATE download_removal_targets SET remainingArtifactKeysEncoding = 'artifact-keys-v1:', " +
            "downloadsRemoved = 1 WHERE operationId = :operationId AND serverId = :serverId " +
            "AND userId = :userId AND cleanupGeneration = :cleanupGeneration " +
            "AND remainingArtifactKeysEncoding = 'artifact-keys-v1:'",
    )
    suspend fun markRemovalTargetDownloadsRemoved(
        operationId: String,
        serverId: String,
        userId: String,
        cleanupGeneration: Long,
    ): Int

    @Transaction
    suspend fun settleRemovalTargetDownloads(
        operationId: String,
        serverId: String,
        userId: String,
        cleanupGeneration: Long,
    ): Boolean {
        val target = removalTarget(operationId, serverId, userId) ?: return false
        if (target.cleanupGeneration != cleanupGeneration) return false
        if (target.remainingArtifactKeysEncoding != DownloadArtifactKeyCodec.EMPTY_ENCODING) return false
        val deleted = deleteRecordsForAccount(serverId, userId)
        if (deleted > 0) advanceMembershipAfterMutation()
        return markRemovalTargetDownloadsRemoved(operationId, serverId, userId, cleanupGeneration) == 1
    }

    @Query(
        "SELECT COUNT(*) FROM download_removal_targets WHERE operationId = :operationId " +
            "AND (accountRemoved = 0 OR downloadsRemoved = 0 OR remainingArtifactKeysEncoding != 'artifact-keys-v1:')",
    )
    suspend fun unsettledRemovalTargetCount(operationId: String): Long

    @Query("DELETE FROM download_removal_operations WHERE operationId = :operationId")
    suspend fun deleteRemovalOperation(operationId: String): Int

    @Transaction
    suspend fun deleteRemovalOperationIfSettled(operationId: String): Boolean {
        if (removalTargets(operationId).isEmpty()) return false
        if (unsettledRemovalTargetCount(operationId) != 0L) return false
        return deleteRemovalOperation(operationId) == 1
    }

    private suspend fun accountConfirmation(
        accountIdentity: AccountIdentity,
        membershipRevision: Long,
    ): DownloadRemovalConfirmation {
        val count = recordCountForAccount(accountIdentity.serverId, accountIdentity.userId)
        val physicalByteValues = physicalByteValuesForAccount(accountIdentity.serverId, accountIdentity.userId)
        check(count == physicalByteValues.size.toLong()) { "Download membership changed while taking an account snapshot." }
        return confirmationFor(accountIdentity, membershipRevision, count, physicalByteValues)
    }

    private suspend fun currentUsage(
        settings: DownloadSettingsEntity,
        deviceAvailableBytes: Long,
        safetyReserveBytes: Long,
    ): DownloadUsage {
        val records = allRecords()
        val entries = records.mapNotNull(DownloadRecordEntity::toUsageEntryOrNull)
        check(entries.size == records.size) { "Download usage cannot ignore an unreadable record." }
        return calculateDownloadUsage(entries, settings.quotaBytes, deviceAvailableBytes, safetyReserveBytes)
    }

    private suspend fun advanceMembershipAfterMutation() {
        val currentSettings = ensureSettings()
        check(currentSettings.membershipRevision < Long.MAX_VALUE) { "Download membership revision exhausted." }
        check(
            advanceMembership(
                expectedMembershipRevision = currentSettings.membershipRevision,
                nextMembershipRevision = currentSettings.membershipRevision + 1L,
            ) == 1,
        ) { "Download membership revision changed during mutation." }
    }
}

@Database(
    entities = [
        DownloadSettingsEntity::class,
        DownloadRecordEntity::class,
        DownloadRemovalOperationEntity::class,
        DownloadRemovalTargetEntity::class,
    ],
    version = DOWNLOAD_DATABASE_SCHEMA_VERSION,
    exportSchema = true,
)
@ConstructedBy(DownloadDatabaseConstructor::class)
internal abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object DownloadDatabaseConstructor : RoomDatabaseConstructor<DownloadDatabase> {
    override fun initialize(): DownloadDatabase
}

internal fun RoomDatabase.Builder<DownloadDatabase>.buildDownloadDatabase(driver: SQLiteDriver = BundledSQLiteDriver()): DownloadDatabase =
    setDriver(driver)
        .addMigrations(DOWNLOAD_DATABASE_MIGRATION_1_2)
        .setQueryCoroutineContext(platformIoDispatcher())
        .build()

private val DownloadRecordEntity.accountIdentity: AccountIdentity
    get() = AccountIdentity(serverId, userId)

private fun confirmationFor(
    accountIdentity: AccountIdentity,
    membershipRevision: Long,
    records: List<DownloadRecordEntity>,
): DownloadRemovalConfirmation =
    confirmationFor(
        accountIdentity = accountIdentity,
        membershipRevision = membershipRevision,
        recordCount = records.size.toLong(),
        physicalByteValues = records.map { record -> record.physicalBytes },
    )

private fun confirmationFor(
    accountIdentity: AccountIdentity,
    membershipRevision: Long,
    recordCount: Long,
    physicalByteValues: List<Long>,
): DownloadRemovalConfirmation {
    var displayedBytes = 0L
    physicalByteValues.forEach { bytes -> displayedBytes = saturatingAddNonNegative(displayedBytes, bytes) }
    return DownloadRemovalConfirmation(
        accountIdentity = accountIdentity,
        membershipRevision = membershipRevision,
        recordCount = recordCount,
        displayedBytes = displayedBytes,
    )
}

private fun DownloadState.toStateKeyForMutation(): String =
    when (this) {
        DownloadState.Queued -> STATE_QUEUED
        DownloadState.Downloading -> STATE_DOWNLOADING
        DownloadState.BlockedByQuota -> STATE_BLOCKED_BY_QUOTA
        DownloadState.Finalizing -> STATE_FINALIZING
        DownloadState.Completed -> STATE_COMPLETED
        DownloadState.Paused -> "paused-v1"
        DownloadState.Failed -> "failed-v1"
        DownloadState.NotDownloaded -> "not-downloaded-v1"
    }
