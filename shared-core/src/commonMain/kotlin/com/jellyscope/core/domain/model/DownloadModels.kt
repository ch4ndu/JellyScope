// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import com.jellyscope.core.domain.playback.QualityRung
import com.jellyscope.core.domain.playback.qualityRungForBitrate
import kotlin.jvm.JvmInline

const val DOWNLOAD_SNAPSHOT_FORMAT_VERSION = 1
const val DOWNLOAD_ARTIFACT_FORMAT_VERSION = 1
const val DOWNLOAD_BYTES_PER_GIB = 1_073_741_824L
const val DOWNLOAD_MIN_QUOTA_BYTES = DOWNLOAD_BYTES_PER_GIB
const val DOWNLOAD_DEVICE_SAFETY_RESERVE_BYTES = DOWNLOAD_BYTES_PER_GIB

private const val MAX_OPAQUE_DOWNLOAD_KEY_LENGTH = 128
private const val MAX_PLATFORM_WORK_IDENTITY_LENGTH = 256
private val OPAQUE_DOWNLOAD_KEY = Regex("[A-Za-z0-9_-]+")

/** Opaque stable identity. It is safe as one private path segment but is never itself a path. */
@JvmInline
value class DownloadId(
    val value: String,
) {
    init {
        requireOpaqueDownloadKey(value, "downloadId")
    }
}

/** Opaque relative artifact-directory key. Absolute paths and traversal are unrepresentable. */
@JvmInline
value class DownloadArtifactKey(
    val value: String,
) {
    init {
        requireOpaqueDownloadKey(value, "artifactKey")
    }
}

@JvmInline
value class DownloadRemovalOperationId(
    val value: String,
) {
    init {
        requireOpaqueDownloadKey(value, "operationId")
    }
}

private fun requireOpaqueDownloadKey(
    value: String,
    field: String,
) {
    require(value.length in 1..MAX_OPAQUE_DOWNLOAD_KEY_LENGTH && OPAQUE_DOWNLOAD_KEY.matches(value)) {
        "$field must be a non-blank opaque key containing only letters, digits, underscore, or hyphen."
    }
}

/** Stable, non-null business identity for the one retained copy allowed by v1. */
data class DownloadBusinessKey(
    val accountIdentity: AccountIdentity,
    val itemId: String,
    val mediaSourceId: String,
) {
    init {
        require(itemId.isNotBlank()) { "itemId must not be blank." }
        require(mediaSourceId.isNotBlank()) { "mediaSourceId must not be blank." }
    }
}

/** Generation-bound reference carried across local playback boundaries; never a URI or path. */
data class OfflineArtifactRef(
    val downloadId: DownloadId,
    val attemptGeneration: Long,
) {
    init {
        require(attemptGeneration >= 0L) { "attemptGeneration must be non-negative." }
    }
}

sealed interface DownloadQuality {
    data object Original : DownloadQuality

    data class Fixed(
        val maxBitrateBps: Long,
    ) : DownloadQuality {
        init {
            require(qualityRungForBitrate(maxBitrateBps) != null) {
                "Fixed download quality must use a canonical quality rung."
            }
        }

        val rung: QualityRung
            get() = checkNotNull(qualityRungForBitrate(maxBitrateBps))
    }
}

enum class DownloadArtifactKind {
    OriginalFile,
    LocalHlsPackage,
}

enum class DownloadFailure(
    val retryable: Boolean,
) {
    PermissionDenied(false),
    SizeUnavailable(false),
    Network(true),
    ServerUnavailable(true),
    SourceChanged(false),
    UnsupportedArtifact(false),
    QuotaExceeded(true),
    DeviceStorageLow(true),
    MissingArtifact(false),
    ArtifactInUse(true),
}

sealed interface DownloadSubtitleSelection {
    data object Off : DownloadSubtitleSelection

    data class Embedded(
        val streamIndex: Int,
        val burnInConfirmed: Boolean = false,
    ) : DownloadSubtitleSelection {
        init {
            require(streamIndex >= 0) { "Subtitle stream index must be non-negative." }
        }
    }

    data class ExternalTextSidecar(
        val localAssetId: String,
    ) : DownloadSubtitleSelection {
        init {
            require(localAssetId.isNotBlank()) { "Local subtitle asset id must not be blank." }
        }
    }

    /**
     * A server-provided external text stream selected for this exact Original source.  The
     * stream is localized into the private artifact during transfer; only its stable stream index
     * is persisted, never a subtitle URL or credential.
     */
    data class ExternalServerTextSidecar(
        val streamIndex: Int,
    ) : DownloadSubtitleSelection {
        init {
            require(streamIndex >= 0) { "Subtitle stream index must be non-negative." }
        }
    }
}

enum class OfflineTrackKind {
    Audio,
    Subtitle,
}

/** Bounded, credential-free track description needed by explicit offline playback. */
data class OfflineTrackSnapshot(
    val kind: OfflineTrackKind,
    val streamIndex: Int?,
    val codec: String?,
    val language: String?,
    val label: String?,
    val isDefault: Boolean,
    val isExternal: Boolean,
) {
    init {
        require(streamIndex == null || streamIndex >= 0) { "Track stream index must be non-negative when present." }
    }
}

data class OfflineChapterSnapshot(
    val name: String,
    val startTicks: Long,
) {
    init {
        require(startTicks >= 0L) { "Chapter start must be non-negative." }
    }
}

/**
 * Small immutable snapshot sufficient to present and plan a completed item without a server read.
 * It deliberately contains no URL, credential, remote image, local path, or server DTO.
 */
data class OfflineMediaSnapshot(
    val formatVersion: Int = DOWNLOAD_SNAPSHOT_FORMAT_VERSION,
    val title: String,
    val itemKind: MediaKind,
    val seriesName: String? = null,
    val seasonLabel: String? = null,
    val episodeLabel: String? = null,
    val durationMs: Long? = null,
    val chapters: List<OfflineChapterSnapshot> = emptyList(),
    val sourcePresentation: String? = null,
    val embeddedTracks: List<OfflineTrackSnapshot> = emptyList(),
    val selectedAudioTrack: OfflineTrackSnapshot? = null,
    val selectedSubtitleTrack: OfflineTrackSnapshot? = null,
    val backendSource: BackendSourceDescriptor,
) {
    init {
        require(formatVersion == DOWNLOAD_SNAPSHOT_FORMAT_VERSION) { "Unsupported offline snapshot format." }
        require(title.isNotBlank()) { "Offline title must not be blank." }
        require(itemKind == MediaKind.Movie || itemKind == MediaKind.Episode) {
            "Offline downloads support only movies and episodes."
        }
        require(durationMs == null || durationMs > 0L) { "Duration must be positive when present." }
        require(selectedAudioTrack == null || selectedAudioTrack.kind == OfflineTrackKind.Audio) {
            "Selected audio description must be an audio track."
        }
        require(selectedSubtitleTrack == null || selectedSubtitleTrack.kind == OfflineTrackKind.Subtitle) {
            "Selected subtitle description must be a subtitle track."
        }
    }
}

enum class DownloadPlatformWorkKind {
    AndroidUserInitiatedJob,
    AndroidWorkManager,
}

data class DownloadPlatformWorkIdentity(
    val kind: DownloadPlatformWorkKind,
    val value: String,
) {
    init {
        require(
            value.isNotBlank() &&
                value.length <= MAX_PLATFORM_WORK_IDENTITY_LENGTH &&
                value.none(Char::isISOControl),
        ) { "Platform work identity must be bounded, non-blank, and contain no control characters." }
    }
}

/** Immutable admission facts. Runtime queue/attempt facts live on [DownloadRecord]. */
data class DownloadRequest(
    val downloadId: DownloadId,
    val businessKey: DownloadBusinessKey,
    val quality: DownloadQuality,
    val artifactKind: DownloadArtifactKind,
    val selectedAudioStreamIndex: Int?,
    val subtitleSelection: DownloadSubtitleSelection,
    val admissionEstimateBytes: Long,
    val initialReservationBytes: Long,
    val expectedSourceBytes: Long? = null,
    val sourceValidator: String? = null,
    val artifactKey: DownloadArtifactKey,
    val artifactFormatVersion: Int = DOWNLOAD_ARTIFACT_FORMAT_VERSION,
    val snapshot: OfflineMediaSnapshot,
    val createdAtEpochMs: Long,
) {
    init {
        require(selectedAudioStreamIndex == null || selectedAudioStreamIndex >= 0) {
            "Audio stream index must be non-negative when present."
        }
        require(admissionEstimateBytes > 0L) { "Admission estimate must be positive." }
        require(initialReservationBytes >= admissionEstimateBytes) {
            "Initial reservation must cover the admission estimate."
        }
        require(expectedSourceBytes == null || expectedSourceBytes > 0L) {
            "Expected source size must be positive when present."
        }
        require(sourceValidator == null || sourceValidator.isNotBlank()) {
            "Source validator must be non-blank when present."
        }
        require(artifactFormatVersion == DOWNLOAD_ARTIFACT_FORMAT_VERSION) {
            "Unsupported download artifact format."
        }
        require(createdAtEpochMs >= 0L) { "Creation time must be non-negative." }
        when (quality) {
            DownloadQuality.Original -> {
                require(artifactKind == DownloadArtifactKind.OriginalFile) {
                    "Original quality requires an original-file artifact."
                }
                require((subtitleSelection as? DownloadSubtitleSelection.Embedded)?.burnInConfirmed != true) {
                    "Original quality does not burn subtitles into the source."
                }
            }

            is DownloadQuality.Fixed -> {
                require(artifactKind == DownloadArtifactKind.LocalHlsPackage) {
                    "Fixed quality requires a localized HLS artifact."
                }
                require(
                    subtitleSelection !is DownloadSubtitleSelection.ExternalTextSidecar &&
                        subtitleSelection !is DownloadSubtitleSelection.ExternalServerTextSidecar,
                ) {
                    "Fixed quality does not retain an external subtitle sidecar."
                }
                require(
                    subtitleSelection !is DownloadSubtitleSelection.Embedded ||
                        subtitleSelection.burnInConfirmed,
                ) { "A selected fixed-quality subtitle requires explicit permanent burn-in confirmation." }
            }
        }
    }
}

/**
 * Untrusted, credential-free input used to request an Original download admission.
 *
 * The caller supplies only the user's selected identity/track choices and the local snapshot
 * metadata.  Server-derived size, validator, package estimate, and reservation facts are
 * intentionally absent; [DownloadRequest] is produced only after the authenticated admission
 * boundary has proved them.
 */
data class OriginalDownloadDraft(
    val downloadId: DownloadId,
    val businessKey: DownloadBusinessKey,
    val selectedAudioStreamIndex: Int?,
    val subtitleSelection: DownloadSubtitleSelection,
    val artifactKey: DownloadArtifactKey,
    val snapshot: OfflineMediaSnapshot,
    val createdAtEpochMs: Long,
) {
    init {
        require(selectedAudioStreamIndex == null || selectedAudioStreamIndex >= 0) {
            "Audio stream index must be non-negative when present."
        }
        require(createdAtEpochMs >= 0L) { "Creation time must be non-negative." }
    }
}

data class DownloadRecord(
    val request: DownloadRequest,
    val fifoSequence: Long,
    val state: DownloadState,
    val reservationBytes: Long,
    val physicalBytes: Long,
    val checkpointBytes: Long,
    val attemptGeneration: Long,
    val platformWorkIdentity: DownloadPlatformWorkIdentity? = null,
    val localResumePositionMs: Long = 0L,
    val localWatched: Boolean = false,
    val failure: DownloadFailure? = null,
    val updatedAtEpochMs: Long,
) {
    init {
        require(fifoSequence > 0L) { "FIFO sequence must be positive." }
        require(state != DownloadState.NotDownloaded) { "NotDownloaded is not a persisted record state." }
        require(reservationBytes >= 0L) { "Reservation bytes must be non-negative." }
        require(physicalBytes >= 0L) { "Physical bytes must be non-negative." }
        require(checkpointBytes in 0L..physicalBytes) { "Checkpoint bytes must be within physical bytes." }
        require(attemptGeneration >= 0L) { "Attempt generation must be non-negative." }
        require(localResumePositionMs >= 0L) { "Local resume position must be non-negative." }
        require(updatedAtEpochMs >= request.createdAtEpochMs) { "Update time must not predate creation." }
        require((state == DownloadState.Failed) == (failure != null)) {
            "Only Failed records carry a failure code, and every Failed record must carry one."
        }
        require(
            platformWorkIdentity == null ||
                state == DownloadState.Downloading ||
                state == DownloadState.Finalizing,
        ) { "Platform work identity may be retained only by an active attempt." }
        if (state == DownloadState.Completed) {
            require(reservationBytes == physicalBytes) {
                "Completed records release unused reservation in the completion transaction."
            }
        }
        if (state.retainsOutstandingReservation) {
            require(reservationBytes >= physicalBytes) {
                "Unfinished reserved records cannot have more physical bytes than their reservation."
            }
        }
    }

    val downloadId: DownloadId
        get() = request.downloadId

    val businessKey: DownloadBusinessKey
        get() = request.businessKey
}

data class DownloadSettings(
    val quotaBytes: Long?,
    val nextFifoSequence: Long,
    val membershipRevision: Long,
) {
    init {
        require(
            quotaBytes == null ||
                (quotaBytes >= DOWNLOAD_MIN_QUOTA_BYTES && quotaBytes % DOWNLOAD_BYTES_PER_GIB == 0L),
        ) { "Configured quota must be a positive whole-GiB allocation of at least 1 GiB." }
        require(nextFifoSequence > 0L) { "Next FIFO sequence must be positive." }
        require(membershipRevision >= 0L) { "Membership revision must be non-negative." }
    }
}

data class DownloadUsageEntry(
    val state: DownloadState,
    val physicalBytes: Long,
    val reservationBytes: Long,
) {
    init {
        require(state != DownloadState.NotDownloaded) { "NotDownloaded has no usage row." }
        require(physicalBytes >= 0L) { "Physical bytes must be non-negative." }
        require(reservationBytes >= 0L) { "Reservation bytes must be non-negative." }
    }
}

data class DownloadUsage(
    /** Device-global physical bytes; quota and capacity policy continue to use this total. */
    val physicalBytes: Long,
    /** Physical bytes owned by the account that requested this usage snapshot. */
    val currentAccountPhysicalBytes: Long,
    /** Opaque aggregate for every other account; no account identity or title accompanies it. */
    val otherAccountsPhysicalBytes: Long,
    val outstandingReservationBytes: Long,
    val projectedCommittedBytes: Long,
    val quotaBytes: Long?,
    val remainingQuotaBytes: Long?,
    val deviceAvailableBytes: Long,
    val safetyReserveBytes: Long,
    val maximumConfigurableQuotaBytes: Long,
    val overAllocation: Boolean,
) {
    init {
        require(physicalBytes >= 0L) { "Physical bytes must be non-negative." }
        require(currentAccountPhysicalBytes >= 0L) { "Current-account physical bytes must be non-negative." }
        require(otherAccountsPhysicalBytes >= 0L) { "Other-account physical bytes must be non-negative." }
        require(currentAccountPhysicalBytes <= physicalBytes) {
            "Current-account physical bytes cannot exceed the device-global total."
        }
        require(otherAccountsPhysicalBytes == physicalBytes - currentAccountPhysicalBytes) {
            "Account physical-byte aggregates must partition the device-global total."
        }
    }
}

enum class DownloadAdmissionDecision {
    Allowed,
    QuotaUnconfigured,
    QuotaExceeded,
    DeviceStorageLow,
    PermissionDenied,
    SizeUnavailable,
    SourceChanged,
    NetworkUnavailable,
    UnsupportedArtifact,
}

sealed interface DownloadEnqueueResult {
    data class Created(
        val record: DownloadRecord,
    ) : DownloadEnqueueResult

    data class Existing(
        val record: DownloadRecord,
    ) : DownloadEnqueueResult

    /** The durable Queued row remains; the platform rejected the follow-up execution wake. */
    data class SchedulingRejected(
        val record: DownloadRecord,
    ) : DownloadEnqueueResult

    data class Rejected(
        val decision: DownloadAdmissionDecision,
    ) : DownloadEnqueueResult

    data object RemovalInProgress : DownloadEnqueueResult
}

sealed interface DownloadReservationExtensionResult {
    data class Extended(
        val reservationBytes: Long,
    ) : DownloadReservationExtensionResult

    data class Unchanged(
        val reservationBytes: Long,
    ) : DownloadReservationExtensionResult

    data class Rejected(
        val decision: DownloadAdmissionDecision,
    ) : DownloadReservationExtensionResult

    data object StaleAttempt : DownloadReservationExtensionResult

    data object RemovalInProgress : DownloadReservationExtensionResult
}

enum class DownloadRemovalKind {
    SingleAccount,
    FullLogout,
}

data class DownloadRemovalConfirmation(
    val accountIdentity: AccountIdentity,
    val membershipRevision: Long,
    val recordCount: Long,
    val displayedBytes: Long,
) {
    init {
        require(membershipRevision >= 0L) { "Membership revision must be non-negative." }
        require(recordCount >= 0L) { "Record count must be non-negative." }
        require(displayedBytes >= 0L) { "Displayed bytes must be non-negative." }
    }
}

data class DownloadRemovalPreview(
    val membershipRevision: Long,
    val confirmations: List<DownloadRemovalConfirmation>,
) {
    init {
        require(membershipRevision >= 0L) { "Membership revision must be non-negative." }
        require(confirmations.all { confirmation -> confirmation.membershipRevision == membershipRevision }) {
            "Every removal confirmation must use the preview's device-global membership revision."
        }
        require(confirmations.map { confirmation -> confirmation.accountIdentity }.distinct().size == confirmations.size) {
            "Removal preview cannot contain the same account twice."
        }
    }
}

sealed interface BeginDownloadRemovalResult {
    data class Created(
        val operation: DownloadRemovalOperation,
    ) : BeginDownloadRemovalResult

    data object NoDownloads : BeginDownloadRemovalResult

    data object ConfirmationStale : BeginDownloadRemovalResult

    data object RemovalAlreadyInProgress : BeginDownloadRemovalResult
}

data class DownloadRemovalTarget(
    val operationId: DownloadRemovalOperationId,
    val accountIdentity: AccountIdentity,
    val observedMembershipRevision: Long,
    val cleanupGeneration: Long,
    val remainingArtifactKeys: List<DownloadArtifactKey>,
    val accountRemoved: Boolean,
    val downloadsRemoved: Boolean,
) {
    init {
        require(observedMembershipRevision >= 0L) { "Observed membership revision must be non-negative." }
        require(cleanupGeneration >= 0L) { "Cleanup generation must be non-negative." }
        require(remainingArtifactKeys.distinct().size == remainingArtifactKeys.size) {
            "Removal target artifact keys must be unique."
        }
        require(!downloadsRemoved || remainingArtifactKeys.isEmpty()) {
            "A downloads-settled target cannot retain artifact keys."
        }
    }

    val settled: Boolean
        get() = accountRemoved && downloadsRemoved && remainingArtifactKeys.isEmpty()
}

data class DownloadRemovalOperation(
    val operationId: DownloadRemovalOperationId,
    val kind: DownloadRemovalKind,
    val createdAtEpochMs: Long,
    val targets: List<DownloadRemovalTarget>,
) {
    init {
        require(createdAtEpochMs >= 0L) { "Removal operation creation time must be non-negative." }
        require(targets.isNotEmpty()) { "Removal operation must contain at least one target." }
        require(targets.all { target -> target.operationId == operationId }) {
            "Every removal target must belong to its header."
        }
        require(targets.map { target -> target.accountIdentity }.distinct().size == targets.size) {
            "Removal operation cannot contain the same account twice."
        }
        require(kind != DownloadRemovalKind.SingleAccount || targets.size == 1) {
            "Single-account removal must contain exactly one target."
        }
    }

    val settled: Boolean
        get() = targets.all(DownloadRemovalTarget::settled)
}
