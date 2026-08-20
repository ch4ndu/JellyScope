// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import com.jellyscope.core.data.local.DownloadArtifactArea
import com.jellyscope.core.data.local.DownloadArtifactInspection
import com.jellyscope.core.data.local.DownloadArtifactPartInspection
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord

/**
 * Proves the one canonical, fully localized HLS package shape used by both offline playback and
 * Finalizing recovery. The caller supplies the inspection it already owns so a recovery decision
 * never silently validates a different area or a newer filesystem view.
 *
 * This intentionally validates metadata and package membership, not media-file contents. The
 * bounded transfer owns the authenticated segment response and records each complete segment
 * length in the checkpoint before entering Finalizing.
 */
internal suspend fun DownloadArtifactStore.isCanonicalLocalHlsArtifact(
    record: DownloadRecord,
    inspection: DownloadArtifactInspection?,
    area: DownloadArtifactArea,
): Boolean {
    if (!record.hasCanonicalLocalHlsRecordFacts(inspection, area)) return false
    val current = inspection ?: return false
    val masterBytes =
        readPart(
            artifactKey = record.request.artifactKey,
            area = area,
            partKey = DownloadHlsPartNames.MASTER,
            maxBytes = MAX_HLS_PLAYLIST_READ_BYTES,
        ) ?: return false
    val mediaBytes =
        readPart(
            artifactKey = record.request.artifactKey,
            area = area,
            partKey = DownloadHlsPartNames.MEDIA,
            maxBytes = MAX_HLS_PLAYLIST_READ_BYTES,
        ) ?: return false
    val checkpointBytes =
        readPart(
            artifactKey = record.request.artifactKey,
            area = area,
            partKey = DownloadHlsPartNames.CHECKPOINT,
            maxBytes = MAX_HLS_CHECKPOINT_READ_BYTES,
        ) ?: return false

    val packageValue =
        (
            DownloadHlsPackage.parse(masterBytes.decodeToString(), mediaBytes.decodeToString())
                as? DownloadHlsParseResult.Package
        )?.value
            ?: return false
    if (packageValue.master.childPlaylistUri != DownloadHlsPartNames.MEDIA.value) return false
    if (packageValue.media.segments.indices.any { index ->
            packageValue.media.segments[index].remoteUri != DownloadHlsPartNames.segment(index).value
        }
    ) {
        return false
    }

    // The transfer writes these exact canonical forms. Rejecting a merely parseable alternate
    // representation keeps recovery and playback bound to the package format we own.
    if (masterBytes.decodeToString() != packageValue.masterText()) return false
    if (mediaBytes.decodeToString() != packageValue.mediaText()) return false

    val checkpoint = DownloadHlsCheckpoint.decode(checkpointBytes.decodeToString()) ?: return false
    if (!checkpoint.isCompatible(packageValue) || !checkpoint.isComplete()) return false
    val expectedParts =
        checkpoint.artifactCheckpoint() +
            com.jellyscope.core.data.local.DownloadArtifactPartCheckpoint(
                partKey = DownloadHlsPartNames.CHECKPOINT,
                lengthBytes = checkpointBytes.size.toLong(),
            )
    return current.parts.sortedBy { part -> part.partKey.value } ==
        expectedParts
            .map { part -> DownloadArtifactPartInspection(part.partKey, part.lengthBytes) }
            .sortedBy { part -> part.partKey.value }
}

private fun DownloadRecord.hasCanonicalLocalHlsRecordFacts(
    inspection: DownloadArtifactInspection?,
    area: DownloadArtifactArea,
): Boolean {
    if (request.artifactKind != DownloadArtifactKind.LocalHlsPackage) return false
    if (request.quality !is DownloadQuality.Fixed) return false
    if (inspection == null || inspection.artifactKey != request.artifactKey || inspection.area != area) return false
    if (physicalBytes <= 0L || checkpointBytes != physicalBytes || inspection.totalBytes != physicalBytes) return false
    return reservationBytes >= physicalBytes
}

private const val MAX_HLS_PLAYLIST_READ_BYTES = 1_048_576
private const val MAX_HLS_CHECKPOINT_READ_BYTES = 1_048_576
