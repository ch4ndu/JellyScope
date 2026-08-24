// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.action.SessionRemovalScope
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.download.DownloadCleanupCoordinator
import kotlinx.coroutines.flow.Flow

/** The durable current-account download list; no server or transfer is started here. */
class ObserveDownloadsUseCase(
    private val repository: DownloadRepository,
) {
    operator fun invoke(accountIdentity: AccountIdentity): Flow<List<DownloadRecord>> = repository.observeDownloads(accountIdentity)
}

class GetDownloadUseCase(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord? = repository.getDownload(accountIdentity, downloadId)
}

/** Loads a completed, account-qualified offline row whose artifact is present. */
class GetOfflinePlaybackPlanUseCase(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): DownloadRecord? =
        repository
            .getDownload(accountIdentity, downloadId)
            ?.takeIf { record -> record.state == DownloadState.Completed }
            ?.takeIf { record ->
                repository.hasCompletedArtifact(
                    accountIdentity = accountIdentity,
                    downloadId = record.downloadId,
                    attemptGeneration = record.attemptGeneration,
                )
            }
}

/** Read-only lease hint for disabling a destructive UI action before the guarded command. */
class IsDownloadArtifactLeasedUseCase(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(
        accountIdentity: AccountIdentity,
        downloadId: DownloadId,
    ): Boolean = repository.isArtifactLeased(accountIdentity, downloadId)
}

class GetDownloadSettingsUseCase(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(): DownloadSettings = repository.getDownloadSettings()
}

class GetDownloadUsageUseCase(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(accountIdentity: AccountIdentity): DownloadUsage = repository.getDownloadUsage(accountIdentity)
}

/** Narrow UI-facing seam for a fresh, participant-owned removal snapshot. */
interface DownloadRemovalPreviewReader {
    suspend operator fun invoke(scope: SessionRemovalScope): DownloadRemovalPreview
}

/** A fresh, participant-owned account/full-logout count-and-bytes snapshot. */
class GetDownloadRemovalPreviewUseCase(
    private val cleanupCoordinator: DownloadCleanupCoordinator,
) : DownloadRemovalPreviewReader {
    override suspend operator fun invoke(scope: SessionRemovalScope): DownloadRemovalPreview = cleanupCoordinator.previewRemoval(scope)
}

/** Issues an opaque value only; it neither mutates credentials nor creates the removal saga. */
interface DownloadRemovalAuthorizationIssuer {
    suspend operator fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): SessionRemovalAuthorization
}

/** Issues an opaque value only; it neither mutates credentials nor creates the removal saga. */
class IssueDownloadRemovalAuthorizationUseCase(
    private val cleanupCoordinator: DownloadCleanupCoordinator,
) : DownloadRemovalAuthorizationIssuer {
    override suspend operator fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): SessionRemovalAuthorization = cleanupCoordinator.issueRemovalAuthorization(scope, preview)
}

/** Releases a dismissed destructive preview and makes its checkpointed rows FIFO-eligible again. */
interface DownloadRemovalPreviewReleaser {
    suspend operator fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): Boolean
}

/** Releases a dismissed destructive preview and makes its checkpointed rows FIFO-eligible again. */
class ReleaseDownloadRemovalPreviewUseCase(
    private val cleanupCoordinator: DownloadCleanupCoordinator,
) : DownloadRemovalPreviewReleaser {
    override suspend operator fun invoke(
        scope: SessionRemovalScope,
        preview: DownloadRemovalPreview,
    ): Boolean = cleanupCoordinator.releaseRemovalPreview(scope, preview)
}
