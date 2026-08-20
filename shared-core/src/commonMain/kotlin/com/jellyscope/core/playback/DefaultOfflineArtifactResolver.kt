// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_PART_KEY
import com.jellyscope.core.data.local.DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY
import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactPartKey
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadRecordStore
import com.jellyscope.core.data.local.DownloadRemovalStore
import com.jellyscope.core.data.local.isCompleteOriginalArtifact
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.OfflineArtifactRef
import com.jellyscope.core.download.DownloadHlsPartNames
import com.jellyscope.core.download.isCanonicalLocalHlsArtifact
import kotlinx.coroutines.CancellationException

/** Opaque package members shared with the Original transfer writer. */
internal val OFFLINE_MAIN_PART_KEY: DownloadArtifactPartKey = DOWNLOAD_ORIGINAL_PART_KEY
internal val OFFLINE_SIDECAR_PART_KEY: DownloadArtifactPartKey = DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY

/**
 * Performs durable validation before leasing a path-bearing resource. Controllers
 * retain the lease until native teardown.
 */
internal class DefaultOfflineArtifactResolver(
    private val recordStore: DownloadRecordStore,
    private val removalStore: DownloadRemovalStore,
    private val artifactStore: DownloadArtifactStore,
    private val leaseRegistry: OfflineArtifactLeaseRegistry,
) : OfflineArtifactResolver {
    override suspend fun acquire(
        expectedAccountIdentity: AccountIdentity,
        reference: OfflineArtifactRef,
    ): OfflineArtifactResolution {
        val current =
            recordStore.get(reference.downloadId)
                ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.UnauthorizedOrMissing)

        // Check ownership first so opaque IDs reveal no cross-account metadata.
        if (current.businessKey.accountIdentity != expectedAccountIdentity) {
            return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.UnauthorizedOrMissing)
        }
        if (current.attemptGeneration != reference.attemptGeneration) {
            return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.StaleGeneration)
        }
        if (hasPendingRemoval(expectedAccountIdentity)) {
            return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.DeletionInProgress)
        }

        // Re-read the generation so a replaced row cannot lend the new attempt's artifact.
        val record =
            recordStore.get(reference.downloadId, reference.attemptGeneration)
                ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.StaleGeneration)
        if (record.businessKey.accountIdentity != expectedAccountIdentity) {
            return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.UnauthorizedOrMissing)
        }
        if (record.state != DownloadState.Completed) {
            return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.NotCompleted)
        }
        return try {
            when (record.request.artifactKind) {
                DownloadArtifactKind.OriginalFile -> resolveCompletedOriginal(record, reference)
                DownloadArtifactKind.LocalHlsPackage -> resolveCompletedLocalHls(record, reference)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.CorruptArtifact)
        }
    }

    private suspend fun resolveCompletedOriginal(
        record: DownloadRecord,
        reference: OfflineArtifactRef,
    ): OfflineArtifactResolution {
        val inspection =
            artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Completed)
                ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.MissingArtifact)
        if (!inspection.isCompleteOriginalArtifact(record)) {
            return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.CorruptArtifact)
        }
        val expectedPartKeys =
            buildList {
                add(OFFLINE_MAIN_PART_KEY)
                if (
                    record.request.subtitleSelection is DownloadSubtitleSelection.ExternalTextSidecar ||
                    record.request.subtitleSelection is DownloadSubtitleSelection.ExternalServerTextSidecar
                ) {
                    add(OFFLINE_SIDECAR_PART_KEY)
                }
            }

        // The store has verified containment, file type, links, and completed-area membership.
        val mainPath =
            artifactStore.completedPartPath(record.request.artifactKey, OFFLINE_MAIN_PART_KEY)
                ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.MissingArtifact)
        val sidecarPath =
            if (OFFLINE_SIDECAR_PART_KEY in expectedPartKeys) {
                artifactStore.completedPartPath(record.request.artifactKey, OFFLINE_SIDECAR_PART_KEY)
                    ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.MissingArtifact)
            } else {
                null
            }
        val identity = OfflineArtifactLeaseIdentity(reference)
        val lease =
            leaseRegistry.acquire(
                identity = identity,
                artifactKind = record.request.artifactKind,
                mainResource = TrustedOfflineArtifactResource(OFFLINE_MAIN_PART_KEY, mainPath),
                sidecarResources =
                    sidecarPath
                        ?.let { path ->
                            listOf(TrustedOfflineArtifactResource(OFFLINE_SIDECAR_PART_KEY, path))
                        }.orEmpty(),
            )
                ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.DeletionInProgress)
        return OfflineArtifactResolution.Available(lease)
    }

    private suspend fun resolveCompletedLocalHls(
        record: DownloadRecord,
        reference: OfflineArtifactRef,
    ): OfflineArtifactResolution {
        val inspection =
            artifactStore.inspect(record.request.artifactKey, DownloadArtifactArea.Completed)
                ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.MissingArtifact)
        if (!artifactStore.isCanonicalLocalHlsArtifact(record, inspection, DownloadArtifactArea.Completed)) {
            return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.CorruptArtifact)
        }
        // Only the validated playlist crosses the boundary; its references stay package-relative.
        val masterPath =
            artifactStore.completedPartPath(record.request.artifactKey, DownloadHlsPartNames.MASTER)
                ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.MissingArtifact)
        val lease =
            leaseRegistry.acquire(
                identity = OfflineArtifactLeaseIdentity(reference),
                artifactKind = DownloadArtifactKind.LocalHlsPackage,
                mainResource = TrustedOfflineArtifactResource(DownloadHlsPartNames.MASTER, masterPath),
            ) ?: return OfflineArtifactResolution.Unavailable(OfflineArtifactResolutionFailure.DeletionInProgress)
        return OfflineArtifactResolution.Available(lease)
    }

    private suspend fun hasPendingRemoval(accountIdentity: AccountIdentity): Boolean =
        removalStore.pending().any { operation ->
            operation.targets.any { target -> target.accountIdentity == accountIdentity }
        }
}
