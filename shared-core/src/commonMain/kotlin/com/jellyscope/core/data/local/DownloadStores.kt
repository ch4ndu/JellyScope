// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.BeginDownloadRemovalResult
import com.jellyscope.core.domain.model.DOWNLOAD_SNAPSHOT_FORMAT_VERSION
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadEnqueueResult
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadPlatformWorkKind
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRemovalConfirmation
import com.jellyscope.core.domain.model.DownloadRemovalKind
import com.jellyscope.core.domain.model.DownloadRemovalOperation
import com.jellyscope.core.domain.model.DownloadRemovalOperationId
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.DownloadRemovalTarget
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadReservationExtensionResult
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.DownloadUsageEntry
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineChapterSnapshot
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.model.ownsActiveDownloadSlot
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val DOWNLOAD_SETTINGS_SINGLETON_ID = 1
internal const val DOWNLOAD_ACTIVE_SLOT = "active"

@Entity(tableName = "download_settings")
internal data class DownloadSettingsEntity(
    @PrimaryKey val id: Int = DOWNLOAD_SETTINGS_SINGLETON_ID,
    val quotaBytes: Long?,
    val nextFifoSequence: Long,
    val membershipRevision: Long,
)

@Entity(
    tableName = "download_records",
    indices = [
        Index(value = ["serverId", "userId", "itemId", "mediaSourceId"], unique = true),
        Index(value = ["fifoSequence"], unique = true),
        Index(value = ["artifactKey"], unique = true),
        // SQLite permits multiple NULL values in a unique index. Exactly the
        // Downloading/Finalizing row receives DOWNLOAD_ACTIVE_SLOT.
        Index(value = ["activeSlot"], unique = true),
        Index(value = ["serverId", "userId", "fifoSequence"]),
    ],
)
internal data class DownloadRecordEntity(
    @PrimaryKey val downloadId: String,
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val itemKindKey: String,
    val qualityKey: String,
    val qualityBitrateBps: Long?,
    val artifactKindKey: String,
    val selectedAudioStreamIndex: Int?,
    val subtitleSelectionKey: String,
    val subtitleStreamIndex: Int?,
    val subtitleLocalAssetId: String?,
    val subtitleBurnInConfirmed: Boolean,
    val admissionEstimateBytes: Long,
    val initialReservationBytes: Long,
    val expectedSourceBytes: Long?,
    val sourceValidator: String?,
    val artifactKey: String,
    val artifactFormatVersion: Int,
    val snapshotEncoding: String,
    val fifoSequence: Long,
    val stateKey: String,
    val activeSlot: String?,
    val reservationBytes: Long,
    val physicalBytes: Long,
    val checkpointBytes: Long,
    val attemptGeneration: Long,
    val platformWorkKindKey: String?,
    val platformWorkIdentity: String?,
    val localResumePositionMs: Long,
    val localWatched: Boolean,
    val failureKey: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "download_removal_operations")
internal data class DownloadRemovalOperationEntity(
    @PrimaryKey val operationId: String,
    val kindKey: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "download_removal_targets",
    primaryKeys = ["operationId", "serverId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadRemovalOperationEntity::class,
            parentColumns = ["operationId"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationId"]),
        Index(value = ["serverId", "userId"], unique = true),
    ],
)
internal data class DownloadRemovalTargetEntity(
    val operationId: String,
    val serverId: String,
    val userId: String,
    val observedMembershipRevision: Long,
    val cleanupGeneration: Long,
    val remainingArtifactKeysEncoding: String,
    val accountRemoved: Boolean,
    val downloadsRemoved: Boolean,
)

internal interface DownloadSettingsStore {
    suspend fun get(): DownloadSettings

    suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings
}

internal class RoomDownloadSettingsStore(
    private val dao: DownloadDao,
) : DownloadSettingsStore {
    override suspend fun get(): DownloadSettings = dao.ensureSettings().toModel()

    override suspend fun setQuotaBytes(quotaBytes: Long?): DownloadSettings {
        DownloadSettings(quotaBytes = quotaBytes, nextFifoSequence = 1L, membershipRevision = 0L)
        dao.ensureSettings()
        check(dao.updateQuota(quotaBytes) == 1) { "Download settings singleton is unavailable." }
        return dao.ensureSettings().toModel()
    }
}

internal interface DownloadRecordStore {
    fun observeAccount(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>>

    suspend fun all(): List<DownloadRecord>

    suspend fun get(downloadId: DownloadId): DownloadRecord?

    suspend fun get(
        downloadId: DownloadId,
        attemptGeneration: Long,
    ): DownloadRecord?

    suspend fun enqueue(
        request: DownloadRequest,
        deviceAvailableBytes: Long,
    ): DownloadEnqueueResult

    suspend fun claimOldest(
        accountIdentity: AccountIdentity,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
        deviceAvailableBytes: Long,
        updatedAtEpochMs: Long,
    ): DownloadRecord?

    suspend fun updateProgress(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        updatedAtEpochMs: Long,
    ): Boolean

    suspend fun updateOriginalSourceFacts(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean

    suspend fun checkpointAndInvalidateAttempt(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        nextState: DownloadState,
        updatedAtEpochMs: Long,
    ): DownloadAttemptInvalidationResult

    suspend fun extendReservation(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        requiredReservationBytes: Long,
        deviceAvailableBytes: Long,
    ): DownloadReservationExtensionResult

    suspend fun transition(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        nextState: DownloadState,
        platformWorkIdentity: DownloadPlatformWorkIdentity? = null,
        failure: DownloadFailure? = null,
        updatedAtEpochMs: Long,
    ): Boolean

    suspend fun completeFinalizing(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        updatedAtEpochMs: Long,
    ): Boolean

    suspend fun updateLocalPlayback(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean?,
        updatedAtEpochMs: Long,
    ): Boolean

    suspend fun delete(downloadId: DownloadId): Boolean
}

internal sealed interface DownloadAttemptInvalidationResult {
    data class Invalidated(
        val record: DownloadRecord,
    ) : DownloadAttemptInvalidationResult

    data class Finalizing(
        val record: DownloadRecord,
    ) : DownloadAttemptInvalidationResult

    data object RemovalInProgress : DownloadAttemptInvalidationResult

    data object StaleAttempt : DownloadAttemptInvalidationResult
}

internal class RoomDownloadRecordStore(
    private val dao: DownloadDao,
) : DownloadRecordStore {
    override fun observeAccount(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> =
        dao
            .observeRecordsForAccount(accountIdentity.serverId, accountIdentity.userId)
            .map { entities -> entities.mapNotNull(DownloadRecordEntity::toModelOrNull) }

    override suspend fun all(): List<DownloadRecord> = dao.allRecords().mapNotNull(DownloadRecordEntity::toModelOrNull)

    override suspend fun get(downloadId: DownloadId): DownloadRecord? = dao.record(downloadId.value)?.toModelOrNull()

    override suspend fun get(
        downloadId: DownloadId,
        attemptGeneration: Long,
    ): DownloadRecord? = dao.record(downloadId.value, attemptGeneration)?.toModelOrNull()

    override suspend fun enqueue(
        request: DownloadRequest,
        deviceAvailableBytes: Long,
    ): DownloadEnqueueResult =
        dao.enqueueRecord(
            entity = request.toEntity(fifoSequence = 0L),
            deviceAvailableBytes = deviceAvailableBytes,
        )

    override suspend fun claimOldest(
        accountIdentity: AccountIdentity,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
        deviceAvailableBytes: Long,
        updatedAtEpochMs: Long,
    ): DownloadRecord? =
        dao
            .claimOldestQueuedRecord(
                serverId = accountIdentity.serverId,
                userId = accountIdentity.userId,
                platformWorkKindKey = platformWorkIdentity?.kind?.toKey(),
                platformWorkIdentity = platformWorkIdentity?.value,
                deviceAvailableBytes = deviceAvailableBytes,
                updatedAtEpochMs = updatedAtEpochMs,
            )?.toModelOrNull()

    override suspend fun updateProgress(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        updatedAtEpochMs: Long,
    ): Boolean {
        require(expectedAttemptGeneration >= 0L)
        require(checkpointBytes in 0L..physicalBytes)
        return dao.updateProgressForCurrentAttempt(
            downloadId = downloadId.value,
            expectedAttemptGeneration = expectedAttemptGeneration,
            physicalBytes = physicalBytes,
            checkpointBytes = checkpointBytes,
            updatedAtEpochMs = updatedAtEpochMs,
        ) == 1
    }

    override suspend fun updateOriginalSourceFacts(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        expectedSourceBytes: Long,
        sourceValidator: String,
    ): Boolean =
        dao.updateOriginalSourceFacts(
            downloadId = downloadId.value,
            expectedAttemptGeneration = expectedAttemptGeneration,
            expectedSourceBytes = expectedSourceBytes,
            sourceValidator = sourceValidator,
        )

    override suspend fun checkpointAndInvalidateAttempt(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        physicalBytes: Long,
        checkpointBytes: Long,
        nextState: DownloadState,
        updatedAtEpochMs: Long,
    ): DownloadAttemptInvalidationResult =
        dao.checkpointAndInvalidateDownloadingAttempt(
            serverId = accountIdentity.serverId,
            userId = accountIdentity.userId,
            downloadId = downloadId.value,
            expectedAttemptGeneration = expectedAttemptGeneration,
            physicalBytes = physicalBytes,
            checkpointBytes = checkpointBytes,
            nextState = nextState,
            updatedAtEpochMs = updatedAtEpochMs,
        )

    override suspend fun extendReservation(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        requiredReservationBytes: Long,
        deviceAvailableBytes: Long,
    ): DownloadReservationExtensionResult =
        dao.extendReservationForCurrentAttempt(
            downloadId = downloadId.value,
            expectedAttemptGeneration = expectedAttemptGeneration,
            requiredReservationBytes = requiredReservationBytes,
            deviceAvailableBytes = deviceAvailableBytes,
        )

    override suspend fun transition(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        nextState: DownloadState,
        platformWorkIdentity: DownloadPlatformWorkIdentity?,
        failure: DownloadFailure?,
        updatedAtEpochMs: Long,
    ): Boolean =
        dao.transitionAttempt(
            downloadId = downloadId.value,
            expectedAttemptGeneration = expectedAttemptGeneration,
            nextState = nextState,
            platformWorkKindKey = platformWorkIdentity?.kind?.toKey(),
            platformWorkIdentity = platformWorkIdentity?.value,
            failureKey = failure?.let(DownloadFailureCodec::encode),
            updatedAtEpochMs = updatedAtEpochMs,
        )

    override suspend fun completeFinalizing(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        updatedAtEpochMs: Long,
    ): Boolean =
        dao.completeFinalizingAttempt(
            downloadId = downloadId.value,
            expectedAttemptGeneration = expectedAttemptGeneration,
            updatedAtEpochMs = updatedAtEpochMs,
        ) == 1

    override suspend fun updateLocalPlayback(
        downloadId: DownloadId,
        expectedAttemptGeneration: Long,
        resumePositionMs: Long,
        watched: Boolean?,
        updatedAtEpochMs: Long,
    ): Boolean {
        require(resumePositionMs >= 0L)
        return dao.updateLocalPlaybackForAttempt(
            downloadId = downloadId.value,
            expectedAttemptGeneration = expectedAttemptGeneration,
            localResumePositionMs = resumePositionMs,
            localWatched = watched,
            updatedAtEpochMs = updatedAtEpochMs,
        ) == 1
    }

    override suspend fun delete(downloadId: DownloadId): Boolean = dao.deleteRecordAndAdvanceMembership(downloadId.value)
}

internal interface DownloadRemovalStore {
    suspend fun preview(accountIdentity: AccountIdentity): DownloadRemovalPreview

    suspend fun previewAll(accountIdentities: List<AccountIdentity>): DownloadRemovalPreview

    suspend fun begin(
        operationId: DownloadRemovalOperationId,
        kind: DownloadRemovalKind,
        accountIdentities: List<AccountIdentity>,
        confirmations: List<DownloadRemovalConfirmation>,
        createdAtEpochMs: Long,
    ): BeginDownloadRemovalResult

    suspend fun pending(): List<DownloadRemovalOperation>

    suspend fun markAccountRemoved(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
    ): Boolean

    suspend fun updateRemainingArtifactKeys(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
        remainingArtifactKeys: List<DownloadArtifactKey>,
    ): Boolean

    suspend fun markDownloadsRemoved(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
    ): Boolean

    suspend fun deleteIfSettled(operationId: DownloadRemovalOperationId): Boolean
}

internal class RoomDownloadRemovalStore(
    private val dao: DownloadDao,
) : DownloadRemovalStore {
    override suspend fun preview(accountIdentity: AccountIdentity): DownloadRemovalPreview =
        dao.removalPreview(accountIdentity.serverId, accountIdentity.userId)

    override suspend fun previewAll(accountIdentities: List<AccountIdentity>): DownloadRemovalPreview =
        dao.removalPreviewAll(accountIdentities)

    override suspend fun begin(
        operationId: DownloadRemovalOperationId,
        kind: DownloadRemovalKind,
        accountIdentities: List<AccountIdentity>,
        confirmations: List<DownloadRemovalConfirmation>,
        createdAtEpochMs: Long,
    ): BeginDownloadRemovalResult =
        dao.beginRemovalOperation(
            operationId = operationId.value,
            kind = kind,
            accountIdentities = accountIdentities,
            confirmations = confirmations,
            createdAtEpochMs = createdAtEpochMs,
        )

    override suspend fun pending(): List<DownloadRemovalOperation> =
        dao.removalOperationHeaders().mapNotNull { header ->
            header.toModelOrNull(dao.removalTargets(header.operationId))
        }

    override suspend fun markAccountRemoved(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
    ): Boolean =
        dao.markRemovalTargetAccountRemoved(
            operationId.value,
            accountIdentity.serverId,
            accountIdentity.userId,
            cleanupGeneration,
        ) == 1

    override suspend fun updateRemainingArtifactKeys(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
        remainingArtifactKeys: List<DownloadArtifactKey>,
    ): Boolean =
        dao.updateRemovalTargetArtifactKeys(
            operationId.value,
            accountIdentity.serverId,
            accountIdentity.userId,
            cleanupGeneration,
            DownloadArtifactKeyCodec.encode(remainingArtifactKeys),
        ) == 1

    override suspend fun markDownloadsRemoved(
        operationId: DownloadRemovalOperationId,
        accountIdentity: AccountIdentity,
        cleanupGeneration: Long,
    ): Boolean =
        dao.settleRemovalTargetDownloads(
            operationId.value,
            accountIdentity.serverId,
            accountIdentity.userId,
            cleanupGeneration,
        )

    override suspend fun deleteIfSettled(operationId: DownloadRemovalOperationId): Boolean =
        dao.deleteRemovalOperationIfSettled(operationId.value)
}

internal fun DownloadSettingsEntity.toModel(): DownloadSettings =
    DownloadSettings(
        quotaBytes = quotaBytes,
        nextFifoSequence = nextFifoSequence,
        membershipRevision = membershipRevision,
    )

internal fun DownloadRequest.toEntity(fifoSequence: Long): DownloadRecordEntity =
    DownloadRecordEntity(
        downloadId = downloadId.value,
        serverId = businessKey.accountIdentity.serverId,
        userId = businessKey.accountIdentity.userId,
        itemId = businessKey.itemId,
        mediaSourceId = businessKey.mediaSourceId,
        itemKindKey = snapshot.itemKind.toSnapshotKey(),
        qualityKey = quality.toKey(),
        qualityBitrateBps = (quality as? DownloadQuality.Fixed)?.maxBitrateBps,
        artifactKindKey = artifactKind.toKey(),
        selectedAudioStreamIndex = selectedAudioStreamIndex,
        subtitleSelectionKey = subtitleSelection.toKey(),
        subtitleStreamIndex =
            when (subtitleSelection) {
                is DownloadSubtitleSelection.Embedded -> subtitleSelection.streamIndex
                is DownloadSubtitleSelection.ExternalServerTextSidecar -> subtitleSelection.streamIndex
                DownloadSubtitleSelection.Off,
                is DownloadSubtitleSelection.ExternalTextSidecar,
                -> null
            },
        subtitleLocalAssetId =
            (subtitleSelection as? DownloadSubtitleSelection.ExternalTextSidecar)?.localAssetId,
        subtitleBurnInConfirmed = (subtitleSelection as? DownloadSubtitleSelection.Embedded)?.burnInConfirmed == true,
        admissionEstimateBytes = admissionEstimateBytes,
        initialReservationBytes = initialReservationBytes,
        expectedSourceBytes = expectedSourceBytes,
        sourceValidator = sourceValidator,
        artifactKey = artifactKey.value,
        artifactFormatVersion = artifactFormatVersion,
        snapshotEncoding = DownloadSnapshotCodec.encode(snapshot),
        fifoSequence = fifoSequence,
        stateKey = DownloadState.Queued.toKey(),
        activeSlot = null,
        reservationBytes = initialReservationBytes,
        physicalBytes = 0L,
        checkpointBytes = 0L,
        attemptGeneration = 0L,
        platformWorkKindKey = null,
        platformWorkIdentity = null,
        localResumePositionMs = 0L,
        localWatched = false,
        failureKey = null,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = createdAtEpochMs,
    )

internal fun DownloadRecordEntity.toModelOrNull(): DownloadRecord? {
    val state = stateKey.toDownloadStateOrNull() ?: return null
    val failure = failureKey?.let(DownloadFailureCodec::decode)
    if ((state == DownloadState.Failed) != (failure != null)) return null
    val itemKind = itemKindKey.toSnapshotMediaKindOrNull() ?: return null
    val quality = qualityKey.toDownloadQualityOrNull(qualityBitrateBps) ?: return null
    val artifactKind = artifactKindKey.toDownloadArtifactKindOrNull() ?: return null
    val subtitle =
        subtitleSelectionKey.toDownloadSubtitleSelectionOrNull(
            streamIndex = subtitleStreamIndex,
            localAssetId = subtitleLocalAssetId,
            burnInConfirmed = subtitleBurnInConfirmed,
        ) ?: return null
    val persistedWorkKindKey = platformWorkKindKey
    val persistedWorkIdentity = platformWorkIdentity
    val workIdentity =
        when {
            persistedWorkKindKey == null && persistedWorkIdentity == null -> null
            persistedWorkKindKey == null || persistedWorkIdentity == null -> return null
            else -> {
                val kind = persistedWorkKindKey.toDownloadPlatformWorkKindOrNull() ?: return null
                runCatching { DownloadPlatformWorkIdentity(kind, persistedWorkIdentity) }.getOrNull() ?: return null
            }
        }
    if (activeSlot != state.activeSlotValue()) return null

    return runCatching {
        DownloadRecord(
            request =
                DownloadRequest(
                    downloadId = DownloadId(downloadId),
                    businessKey =
                        DownloadBusinessKey(
                            accountIdentity = AccountIdentity(serverId, userId),
                            itemId = itemId,
                            mediaSourceId = mediaSourceId,
                        ),
                    quality = quality,
                    artifactKind = artifactKind,
                    selectedAudioStreamIndex = selectedAudioStreamIndex,
                    subtitleSelection = subtitle,
                    admissionEstimateBytes = admissionEstimateBytes,
                    initialReservationBytes = initialReservationBytes,
                    expectedSourceBytes = expectedSourceBytes,
                    sourceValidator = sourceValidator,
                    artifactKey = DownloadArtifactKey(artifactKey),
                    artifactFormatVersion = artifactFormatVersion,
                    snapshot =
                        (DownloadSnapshotCodec.decode(snapshotEncoding) ?: return null)
                            .takeIf { snapshot -> snapshot.itemKind == itemKind }
                            ?: return null,
                    createdAtEpochMs = createdAtEpochMs,
                ),
            fifoSequence = fifoSequence,
            state = state,
            reservationBytes = reservationBytes,
            physicalBytes = physicalBytes,
            checkpointBytes = checkpointBytes,
            attemptGeneration = attemptGeneration,
            platformWorkIdentity = workIdentity,
            localResumePositionMs = localResumePositionMs,
            localWatched = localWatched,
            failure = failure,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }.getOrNull()
}

internal fun DownloadRecordEntity.toUsageEntryOrNull(): DownloadUsageEntry? =
    stateKey.toDownloadStateOrNull()?.let { state ->
        runCatching {
            DownloadUsageEntry(
                state = state,
                physicalBytes = physicalBytes,
                reservationBytes = reservationBytes,
            )
        }.getOrNull()
    }

internal fun DownloadRemovalOperation.toEntity(): DownloadRemovalOperationEntity =
    DownloadRemovalOperationEntity(
        operationId = operationId.value,
        kindKey = kind.toKey(),
        createdAtEpochMs = createdAtEpochMs,
    )

internal fun DownloadRemovalTarget.toEntity(): DownloadRemovalTargetEntity =
    DownloadRemovalTargetEntity(
        operationId = operationId.value,
        serverId = accountIdentity.serverId,
        userId = accountIdentity.userId,
        observedMembershipRevision = observedMembershipRevision,
        cleanupGeneration = cleanupGeneration,
        remainingArtifactKeysEncoding = DownloadArtifactKeyCodec.encode(remainingArtifactKeys),
        accountRemoved = accountRemoved,
        downloadsRemoved = downloadsRemoved,
    )

private fun DownloadRemovalOperationEntity.toModelOrNull(targetEntities: List<DownloadRemovalTargetEntity>): DownloadRemovalOperation? =
    runCatching {
        val id = DownloadRemovalOperationId(operationId)
        DownloadRemovalOperation(
            operationId = id,
            kind = kindKey.toDownloadRemovalKindOrNull() ?: return null,
            createdAtEpochMs = createdAtEpochMs,
            targets = targetEntities.mapNotNull { entity -> entity.toModelOrNull(id) },
        ).takeIf { operation -> operation.targets.size == targetEntities.size }
    }.getOrNull()

private fun DownloadRemovalTargetEntity.toModelOrNull(operationId: DownloadRemovalOperationId): DownloadRemovalTarget? =
    runCatching {
        DownloadRemovalTarget(
            operationId = operationId,
            accountIdentity = AccountIdentity(serverId, userId),
            observedMembershipRevision = observedMembershipRevision,
            cleanupGeneration = cleanupGeneration,
            remainingArtifactKeys = DownloadArtifactKeyCodec.decode(remainingArtifactKeysEncoding) ?: return null,
            accountRemoved = accountRemoved,
            downloadsRemoved = downloadsRemoved,
        )
    }.getOrNull()

private fun DownloadQuality.toKey(): String =
    when (this) {
        DownloadQuality.Original -> QUALITY_ORIGINAL
        is DownloadQuality.Fixed -> QUALITY_FIXED
    }

private fun String.toDownloadQualityOrNull(bitrateBps: Long?): DownloadQuality? =
    when (this) {
        QUALITY_ORIGINAL -> DownloadQuality.Original.takeIf { bitrateBps == null }
        QUALITY_FIXED -> bitrateBps?.let { bitrate -> runCatching { DownloadQuality.Fixed(bitrate) }.getOrNull() }
        else -> null
    }

private fun DownloadArtifactKind.toKey(): String =
    when (this) {
        DownloadArtifactKind.OriginalFile -> ARTIFACT_ORIGINAL_FILE
        DownloadArtifactKind.LocalHlsPackage -> ARTIFACT_LOCAL_HLS
    }

private fun String.toDownloadArtifactKindOrNull(): DownloadArtifactKind? =
    when (this) {
        ARTIFACT_ORIGINAL_FILE -> DownloadArtifactKind.OriginalFile
        ARTIFACT_LOCAL_HLS -> DownloadArtifactKind.LocalHlsPackage
        else -> null
    }

private fun DownloadState.toKey(): String =
    when (this) {
        DownloadState.NotDownloaded -> STATE_NOT_DOWNLOADED
        DownloadState.Queued -> STATE_QUEUED
        DownloadState.Downloading -> STATE_DOWNLOADING
        DownloadState.Paused -> STATE_PAUSED
        DownloadState.BlockedByQuota -> STATE_BLOCKED_BY_QUOTA
        DownloadState.Finalizing -> STATE_FINALIZING
        DownloadState.Completed -> STATE_COMPLETED
        DownloadState.Failed -> STATE_FAILED
    }

internal fun String.toDownloadStateOrNull(): DownloadState? =
    when (this) {
        STATE_QUEUED -> DownloadState.Queued
        STATE_DOWNLOADING -> DownloadState.Downloading
        STATE_PAUSED -> DownloadState.Paused
        STATE_BLOCKED_BY_QUOTA -> DownloadState.BlockedByQuota
        STATE_FINALIZING -> DownloadState.Finalizing
        STATE_COMPLETED -> DownloadState.Completed
        STATE_FAILED -> DownloadState.Failed
        else -> null
    }

private fun DownloadState.activeSlotValue(): String? = if (ownsActiveDownloadSlot) DOWNLOAD_ACTIVE_SLOT else null

internal object DownloadFailureCodec {
    fun encode(failure: DownloadFailure): String =
        when (failure) {
            DownloadFailure.PermissionDenied -> FAILURE_PERMISSION_DENIED
            DownloadFailure.SizeUnavailable -> FAILURE_SIZE_UNAVAILABLE
            DownloadFailure.Network -> FAILURE_NETWORK
            DownloadFailure.ServerUnavailable -> FAILURE_SERVER_UNAVAILABLE
            DownloadFailure.SourceChanged -> FAILURE_SOURCE_CHANGED
            DownloadFailure.UnsupportedArtifact -> FAILURE_UNSUPPORTED_ARTIFACT
            DownloadFailure.QuotaExceeded -> FAILURE_QUOTA_EXCEEDED
            DownloadFailure.DeviceStorageLow -> FAILURE_DEVICE_STORAGE_LOW
            DownloadFailure.MissingArtifact -> FAILURE_MISSING_ARTIFACT
            DownloadFailure.ArtifactInUse -> FAILURE_ARTIFACT_IN_USE
        }

    fun decode(encoded: String): DownloadFailure? =
        when (encoded) {
            FAILURE_PERMISSION_DENIED -> DownloadFailure.PermissionDenied
            FAILURE_SIZE_UNAVAILABLE -> DownloadFailure.SizeUnavailable
            FAILURE_NETWORK -> DownloadFailure.Network
            FAILURE_SERVER_UNAVAILABLE -> DownloadFailure.ServerUnavailable
            FAILURE_SOURCE_CHANGED -> DownloadFailure.SourceChanged
            FAILURE_UNSUPPORTED_ARTIFACT -> DownloadFailure.UnsupportedArtifact
            FAILURE_QUOTA_EXCEEDED -> DownloadFailure.QuotaExceeded
            FAILURE_DEVICE_STORAGE_LOW -> DownloadFailure.DeviceStorageLow
            FAILURE_MISSING_ARTIFACT -> DownloadFailure.MissingArtifact
            FAILURE_ARTIFACT_IN_USE -> DownloadFailure.ArtifactInUse
            else -> null
        }
}

private fun DownloadSubtitleSelection.toKey(): String =
    when (this) {
        DownloadSubtitleSelection.Off -> SUBTITLE_OFF
        is DownloadSubtitleSelection.Embedded -> SUBTITLE_EMBEDDED
        is DownloadSubtitleSelection.ExternalTextSidecar -> SUBTITLE_EXTERNAL_TEXT_SIDECAR
        is DownloadSubtitleSelection.ExternalServerTextSidecar -> SUBTITLE_EXTERNAL_SERVER_TEXT_SIDECAR
    }

private fun String.toDownloadSubtitleSelectionOrNull(
    streamIndex: Int?,
    localAssetId: String?,
    burnInConfirmed: Boolean,
): DownloadSubtitleSelection? =
    when (this) {
        SUBTITLE_OFF ->
            DownloadSubtitleSelection.Off.takeIf {
                streamIndex == null && localAssetId == null && !burnInConfirmed
            }

        SUBTITLE_EMBEDDED ->
            streamIndex
                ?.let { index ->
                    runCatching { DownloadSubtitleSelection.Embedded(index, burnInConfirmed) }.getOrNull()
                }?.takeIf { localAssetId == null }

        SUBTITLE_EXTERNAL_TEXT_SIDECAR ->
            localAssetId
                ?.let { id ->
                    runCatching { DownloadSubtitleSelection.ExternalTextSidecar(id) }.getOrNull()
                }?.takeIf { streamIndex == null && !burnInConfirmed }

        SUBTITLE_EXTERNAL_SERVER_TEXT_SIDECAR ->
            streamIndex
                ?.let { index ->
                    runCatching { DownloadSubtitleSelection.ExternalServerTextSidecar(index) }.getOrNull()
                }?.takeIf { localAssetId == null && !burnInConfirmed }

        else -> null
    }

private fun DownloadPlatformWorkKind.toKey(): String =
    when (this) {
        DownloadPlatformWorkKind.AndroidUserInitiatedJob -> WORK_ANDROID_UIDT
        DownloadPlatformWorkKind.AndroidWorkManager -> WORK_ANDROID_MANAGER
    }

private fun String.toDownloadPlatformWorkKindOrNull(): DownloadPlatformWorkKind? =
    when (this) {
        WORK_ANDROID_UIDT -> DownloadPlatformWorkKind.AndroidUserInitiatedJob
        WORK_ANDROID_MANAGER -> DownloadPlatformWorkKind.AndroidWorkManager
        else -> null
    }

private fun DownloadRemovalKind.toKey(): String =
    when (this) {
        DownloadRemovalKind.SingleAccount -> REMOVAL_SINGLE_ACCOUNT
        DownloadRemovalKind.FullLogout -> REMOVAL_FULL_LOGOUT
    }

private fun String.toDownloadRemovalKindOrNull(): DownloadRemovalKind? =
    when (this) {
        REMOVAL_SINGLE_ACCOUNT -> DownloadRemovalKind.SingleAccount
        REMOVAL_FULL_LOGOUT -> DownloadRemovalKind.FullLogout
        else -> null
    }

internal object DownloadArtifactKeyCodec {
    const val EMPTY_ENCODING = "artifact-keys-v1:"
    private const val PREFIX = "artifact-keys-v1:"

    fun encode(keys: List<DownloadArtifactKey>): String {
        require(keys.distinct().size == keys.size) { "Artifact keys must be unique." }
        return PREFIX + keys.joinToString(",") { key -> key.value }
    }

    fun decode(encoded: String): List<DownloadArtifactKey>? {
        if (!encoded.startsWith(PREFIX)) return null
        val payload = encoded.removePrefix(PREFIX)
        if (payload.isEmpty()) return emptyList()
        return runCatching { payload.split(',').map(::DownloadArtifactKey) }
            .getOrNull()
            ?.takeIf { keys -> keys.distinct().size == keys.size }
    }
}

internal object DownloadSnapshotCodec {
    private const val PREFIX = "offline-snapshot-v1:"
    private val json = Json { encodeDefaults = true }

    fun encode(snapshot: OfflineMediaSnapshot): String = PREFIX + json.encodeToString(snapshot.toPayload())

    fun decode(encoded: String): OfflineMediaSnapshot? {
        if (!encoded.startsWith(PREFIX)) return null
        return runCatching {
            json.decodeFromString<OfflineSnapshotPayloadV1>(encoded.removePrefix(PREFIX)).toModel()
        }.getOrNull()
    }
}

@Serializable
private data class OfflineSnapshotPayloadV1(
    val formatVersion: Int,
    val title: String,
    val itemKind: String,
    val seriesName: String?,
    val seasonLabel: String?,
    val episodeLabel: String?,
    val durationMs: Long?,
    val chapters: List<OfflineChapterPayloadV1>,
    val sourcePresentation: String?,
    val embeddedTracks: List<OfflineTrackPayloadV1>,
    val selectedAudioTrack: OfflineTrackPayloadV1?,
    val selectedSubtitleTrack: OfflineTrackPayloadV1?,
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val hdrOrDolbyVision: Boolean,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Double? = null,
)

@Serializable
private data class OfflineChapterPayloadV1(
    val name: String,
    val startTicks: Long,
)

@Serializable
private data class OfflineTrackPayloadV1(
    val kind: String,
    val streamIndex: Int?,
    val codec: String?,
    val language: String?,
    val label: String?,
    val defaultTrack: Boolean,
    val external: Boolean,
)

private fun OfflineMediaSnapshot.toPayload(): OfflineSnapshotPayloadV1 =
    OfflineSnapshotPayloadV1(
        formatVersion = formatVersion,
        title = title,
        itemKind = itemKind.toSnapshotKey(),
        seriesName = seriesName,
        seasonLabel = seasonLabel,
        episodeLabel = episodeLabel,
        durationMs = durationMs,
        chapters = chapters.map { chapter -> OfflineChapterPayloadV1(chapter.name, chapter.startTicks) },
        sourcePresentation = sourcePresentation,
        embeddedTracks = embeddedTracks.map(OfflineTrackSnapshot::toPayload),
        selectedAudioTrack = selectedAudioTrack?.toPayload(),
        selectedSubtitleTrack = selectedSubtitleTrack?.toPayload(),
        container = backendSource.container,
        videoCodec = backendSource.videoCodec,
        audioCodec = backendSource.audioCodec,
        hdrOrDolbyVision = backendSource.isHdrOrDolbyVision,
        videoWidth = backendSource.videoWidth,
        videoHeight = backendSource.videoHeight,
        videoFrameRate = backendSource.videoFrameRate,
    )

private fun OfflineSnapshotPayloadV1.toModel(): OfflineMediaSnapshot =
    OfflineMediaSnapshot(
        formatVersion = formatVersion.also { version -> require(version == DOWNLOAD_SNAPSHOT_FORMAT_VERSION) },
        title = title,
        itemKind = itemKind.toSnapshotMediaKindOrNull() ?: error("Unknown offline item kind."),
        seriesName = seriesName,
        seasonLabel = seasonLabel,
        episodeLabel = episodeLabel,
        durationMs = durationMs,
        chapters = chapters.map { chapter -> OfflineChapterSnapshot(chapter.name, chapter.startTicks) },
        sourcePresentation = sourcePresentation,
        embeddedTracks = embeddedTracks.map(OfflineTrackPayloadV1::toModel),
        selectedAudioTrack = selectedAudioTrack?.toModel(),
        selectedSubtitleTrack = selectedSubtitleTrack?.toModel(),
        backendSource =
            BackendSourceDescriptor(
                container = container,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                isHdrOrDolbyVision = hdrOrDolbyVision,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                videoFrameRate = videoFrameRate,
            ),
    )

private fun OfflineTrackSnapshot.toPayload(): OfflineTrackPayloadV1 =
    OfflineTrackPayloadV1(
        kind = kind.toSnapshotKey(),
        streamIndex = streamIndex,
        codec = codec,
        language = language,
        label = label,
        defaultTrack = isDefault,
        external = isExternal,
    )

private fun OfflineTrackPayloadV1.toModel(): OfflineTrackSnapshot =
    OfflineTrackSnapshot(
        kind = kind.toSnapshotTrackKindOrNull() ?: error("Unknown offline track kind."),
        streamIndex = streamIndex,
        codec = codec,
        language = language,
        label = label,
        isDefault = defaultTrack,
        isExternal = external,
    )

private fun MediaKind.toSnapshotKey(): String =
    when (this) {
        MediaKind.Movie -> SNAPSHOT_MOVIE
        MediaKind.Episode -> SNAPSHOT_EPISODE
        else -> error("Unsupported offline media kind.")
    }

private fun String.toSnapshotMediaKindOrNull(): MediaKind? =
    when (this) {
        SNAPSHOT_MOVIE -> MediaKind.Movie
        SNAPSHOT_EPISODE -> MediaKind.Episode
        else -> null
    }

private fun OfflineTrackKind.toSnapshotKey(): String =
    when (this) {
        OfflineTrackKind.Audio -> SNAPSHOT_AUDIO
        OfflineTrackKind.Subtitle -> SNAPSHOT_SUBTITLE
    }

private fun String.toSnapshotTrackKindOrNull(): OfflineTrackKind? =
    when (this) {
        SNAPSHOT_AUDIO -> OfflineTrackKind.Audio
        SNAPSHOT_SUBTITLE -> OfflineTrackKind.Subtitle
        else -> null
    }

private const val QUALITY_ORIGINAL = "original-v1"
private const val QUALITY_FIXED = "fixed-v1"
private const val ARTIFACT_ORIGINAL_FILE = "original-file-v1"
private const val ARTIFACT_LOCAL_HLS = "local-hls-package-v1"
private const val STATE_NOT_DOWNLOADED = "not-downloaded-v1"
internal const val STATE_QUEUED = "queued-v1"
internal const val STATE_DOWNLOADING = "downloading-v1"
private const val STATE_PAUSED = "paused-v1"
internal const val STATE_BLOCKED_BY_QUOTA = "blocked-by-quota-v1"
internal const val STATE_FINALIZING = "finalizing-v1"
internal const val STATE_COMPLETED = "completed-v1"
private const val STATE_FAILED = "failed-v1"
private const val FAILURE_PERMISSION_DENIED = "permission-denied-v1"
private const val FAILURE_SIZE_UNAVAILABLE = "size-unavailable-v1"
private const val FAILURE_NETWORK = "network-v1"
private const val FAILURE_SERVER_UNAVAILABLE = "server-unavailable-v1"
private const val FAILURE_SOURCE_CHANGED = "source-changed-v1"
private const val FAILURE_UNSUPPORTED_ARTIFACT = "unsupported-artifact-v1"
private const val FAILURE_QUOTA_EXCEEDED = "quota-exceeded-v1"
private const val FAILURE_DEVICE_STORAGE_LOW = "device-storage-low-v1"
private const val FAILURE_MISSING_ARTIFACT = "missing-artifact-v1"
private const val FAILURE_ARTIFACT_IN_USE = "artifact-in-use-v1"
private const val SUBTITLE_OFF = "off-v1"
private const val SUBTITLE_EMBEDDED = "embedded-v1"
private const val SUBTITLE_EXTERNAL_TEXT_SIDECAR = "external-text-sidecar-v1"
private const val SUBTITLE_EXTERNAL_SERVER_TEXT_SIDECAR = "external-server-text-sidecar-v1"
private const val WORK_ANDROID_UIDT = "android-uidt-v1"
private const val WORK_ANDROID_MANAGER = "android-work-manager-v1"
private const val REMOVAL_SINGLE_ACCOUNT = "single-account-v1"
private const val REMOVAL_FULL_LOGOUT = "full-logout-v1"
private const val SNAPSHOT_MOVIE = "movie-v1"
private const val SNAPSHOT_EPISODE = "episode-v1"
private const val SNAPSHOT_AUDIO = "audio-v1"
private const val SNAPSHOT_SUBTITLE = "subtitle-v1"
