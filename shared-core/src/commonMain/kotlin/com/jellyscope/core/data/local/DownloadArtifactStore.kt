// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadSubtitleSelection

/**
 * Maximum media chunk accepted by [DownloadArtifactWriter.write].
 *
 * The transfer owner streams through this bounded buffer contract. A complete media file or
 * localized package must never be materialized as one `ByteArray`.
 */
const val DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES: Int = 64 * 1024

/** Maximum bounded in-place writer rewrite size for recovery truncation. */
const val DOWNLOAD_ARTIFACT_MAX_REWRITE_BYTES: Int = 1 * 1024 * 1024

/** Maximum bounded atomic replacement size for an HLS checkpoint manifest. */
const val DOWNLOAD_ARTIFACT_MAX_STAGING_METADATA_REPLACEMENT_BYTES: Int = 2 * 1024 * 1024

/** A validated, opaque, single-segment name inside one artifact package. */
class DownloadArtifactPartKey private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is DownloadArtifactPartKey && value == other.value

    override fun hashCode(): Int = value.hashCode()

    /** Avoid accidentally turning a future opaque key into a diagnostic value. */
    override fun toString(): String = "DownloadArtifactPartKey"

    companion object {
        fun from(value: String): DownloadArtifactPartKey {
            require(isValidDownloadArtifactPartKey(value)) { "Invalid download artifact part key." }
            return DownloadArtifactPartKey(value)
        }

        fun fromOrNull(value: String): DownloadArtifactPartKey? =
            value.takeIf(::isValidDownloadArtifactPartKey)?.let(::DownloadArtifactPartKey)
    }
}

private val DOWNLOAD_ARTIFACT_PART_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private fun isValidDownloadArtifactPartKey(value: String): Boolean =
    value.matches(DOWNLOAD_ARTIFACT_PART_KEY) && value != "." && value != ".."

/** Canonical Original-file package member shared by transfer and trusted playback. */
internal val DOWNLOAD_ORIGINAL_PART_KEY: DownloadArtifactPartKey = DownloadArtifactPartKey.from("original.bin")
internal val DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY: DownloadArtifactPartKey = DownloadArtifactPartKey.from("sidecar.vtt")

enum class DownloadArtifactArea {
    Staging,
    Completed,
}

sealed interface DownloadArtifactWriteMode {
    /** Creates a new part and fails if that part already exists. */
    data object Create : DownloadArtifactWriteMode

    /** Opens an existing part only when its exact durable length still matches. */
    data class Resume(
        val expectedLengthBytes: Long,
    ) : DownloadArtifactWriteMode {
        init {
            require(expectedLengthBytes >= 0L) { "Expected artifact length must not be negative." }
        }
    }
}

data class DownloadArtifactPartCheckpoint(
    val partKey: DownloadArtifactPartKey,
    val lengthBytes: Long,
) {
    init {
        require(lengthBytes >= 0L) { "Artifact checkpoint length must not be negative." }
    }
}

data class DownloadArtifactCheckpoint(
    val parts: List<DownloadArtifactPartCheckpoint>,
) {
    init {
        require(parts.map { part -> part.partKey }.distinct().size == parts.size) {
            "Artifact checkpoint part keys must be unique."
        }
    }
}

data class DownloadArtifactPartInspection(
    val partKey: DownloadArtifactPartKey,
    val lengthBytes: Long,
) {
    init {
        require(lengthBytes >= 0L) { "Artifact part length must not be negative." }
    }
}

data class DownloadArtifactInspection(
    val artifactKey: DownloadArtifactKey,
    val area: DownloadArtifactArea,
    val parts: List<DownloadArtifactPartInspection>,
) {
    init {
        require(parts.map { part -> part.partKey }.distinct().size == parts.size) {
            "Artifact inspection part keys must be unique."
        }
    }

    val totalBytes: Long =
        parts.fold(0L) { total, part ->
            checkedArtifactLengthAfterWrite(total, part.lengthBytes)
        }
}

/**
 * Credential-free durable completeness check shared by the offline launch gate and the trusted
 * path resolver.  It deliberately proves only the package contract; platform stores perform the
 * additional regular-file, link, and private-root checks when a path is requested.
 */
internal fun DownloadArtifactInspection.isCompleteOriginalArtifact(record: DownloadRecord): Boolean {
    if (record.request.artifactKind != DownloadArtifactKind.OriginalFile) return false
    if (artifactKey != record.request.artifactKey || area != DownloadArtifactArea.Completed) return false
    if (totalBytes <= 0L || totalBytes != record.physicalBytes) return false
    val expectedSourceBytes = record.request.expectedSourceBytes ?: return false
    if (expectedSourceBytes <= 0L || expectedSourceBytes > record.physicalBytes) return false
    val expectedPartKeys =
        buildList {
            add(DOWNLOAD_ORIGINAL_PART_KEY)
            if (
                record.request.subtitleSelection is DownloadSubtitleSelection.ExternalTextSidecar ||
                record.request.subtitleSelection is DownloadSubtitleSelection.ExternalServerTextSidecar
            ) {
                add(DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY)
            }
        }.sortedBy { partKey -> partKey.value }
    val main = parts.firstOrNull { part -> part.partKey == DOWNLOAD_ORIGINAL_PART_KEY }
    if (
        parts.map { part -> part.partKey }.sortedBy { partKey -> partKey.value } != expectedPartKeys ||
        parts.any { part -> part.lengthBytes <= 0L } ||
        main?.lengthBytes != expectedSourceBytes
    ) {
        return false
    }
    val expectedSidecarBytes = record.physicalBytes - expectedSourceBytes
    val sidecar = parts.firstOrNull { part -> part.partKey == DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY }
    return if (sidecar == null) {
        expectedPartKeys.size == 1 && expectedSidecarBytes == 0L
    } else {
        expectedPartKeys.size == 2 &&
            expectedSidecarBytes > 0L &&
            sidecar.lengthBytes == expectedSidecarBytes &&
            checkedArtifactLengthAfterWrite(expectedSourceBytes, sidecar.lengthBytes) == record.physicalBytes
    }
}

data class DownloadArtifactCapacity(
    val availableBytes: Long,
    val totalBytes: Long,
) {
    init {
        require(availableBytes >= 0L) { "Available capacity must not be negative." }
        require(totalBytes >= 0L) { "Total capacity must not be negative." }
    }
}

interface DownloadArtifactWriter {
    val partKey: DownloadArtifactPartKey

    /** Current durable-candidate length, including bytes written by this open writer. */
    val lengthBytes: Long

    /**
     * Writes one bounded slice. Implementations reject a slice larger than
     * [DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES].
     */
    suspend fun write(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    )

    /** Flushes this part to stable storage and returns its exact length. */
    suspend fun checkpoint(): DownloadArtifactPartCheckpoint

    /** Replaces or truncates one bounded open part in place during transfer recovery. */
    suspend fun rewrite(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ): DownloadArtifactPartCheckpoint = throw UnsupportedOperationException("This artifact writer does not support metadata rewrite.")

    /** Flushes and closes the writer. Repeated calls are safe. */
    suspend fun close()
}

/**
 * Private artifact storage for supported download graphs.
 *
 * Artifact and part keys are opaque relative identities. Implementations must root-contain every
 * operation, reject links or traversal, stream through bounded chunks, and promote by an atomic
 * same-root rename. This boundary exposes no path to UI or domain code.
 */
internal interface DownloadArtifactStore {
    suspend fun openStagingWriter(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        mode: DownloadArtifactWriteMode,
    ): DownloadArtifactWriter

    suspend fun inspect(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    ): DownloadArtifactInspection?

    /**
     * Returns one already-inspected completed part to the trusted offline resolver.
     *
     * This is intentionally an internal store-to-resolver seam. UI, route models, and ordinary
     * domain code never receive the result; platform player controllers receive it only through
     * [com.jellyscope.core.playback.OfflineArtifactLease]. Implementations must repeat their
     * regular-file, link, and private-root checks immediately before returning the path.
     */
    suspend fun completedPartPath(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
    ): String?

    /**
     * Reads one bounded private package member for checkpoint recovery.  This is intentionally
     * not a path-returning API and is used only for the small HLS checkpoint manifest; media bytes
     * continue to stream through [DownloadArtifactWriter].
     */
    suspend fun readPart(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
        partKey: DownloadArtifactPartKey,
        maxBytes: Int,
    ): ByteArray?

    /**
     * Atomically replaces one small HLS checkpoint manifest in staging.
     *
     * Implementations use a fixed temporary location beside the staging and completed roots, so
     * an interrupted replacement can never become a package member or an enumerated artifact.
     */
    suspend fun replaceStagingMetadata(
        artifactKey: DownloadArtifactKey,
        partKey: DownloadArtifactPartKey,
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ): DownloadArtifactPartCheckpoint

    /** Exact checkpoint validation; unexpected, missing, or differently sized parts fail. */
    suspend fun validateStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean

    /**
     * Crash-recovery normalization. Every existing staging part must be at least the durable
     * checkpoint length; uncheckpointed trailing bytes are truncated, and unexpected parts fail
     * closed. Missing parts are valid only when their durable checkpoint length is zero (including
     * a package directory that has not yet created any zero-length parts).
     */
    suspend fun normalizeStagingCheckpoint(
        artifactKey: DownloadArtifactKey,
        checkpoint: DownloadArtifactCheckpoint,
    ): Boolean

    /** Atomically moves the complete staging directory to the completed directory. */
    suspend fun promote(artifactKey: DownloadArtifactKey): DownloadArtifactInspection

    suspend fun delete(
        artifactKey: DownloadArtifactKey,
        area: DownloadArtifactArea,
    )

    suspend fun enumerate(area: DownloadArtifactArea): List<DownloadArtifactInspection>

    suspend fun capacity(): DownloadArtifactCapacity
}

internal fun requireValidWriteSlice(
    bufferSize: Int,
    offset: Int,
    length: Int,
) {
    require(offset >= 0 && length >= 0 && offset <= bufferSize - length) { "Invalid artifact write slice." }
    require(length <= DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES) { "Artifact write chunk exceeds the bounded limit." }
}

internal fun requireValidRewriteSlice(
    bufferSize: Int,
    offset: Int,
    length: Int,
) {
    require(offset >= 0 && length >= 0 && offset <= bufferSize - length) { "Invalid artifact rewrite slice." }
    require(length <= DOWNLOAD_ARTIFACT_MAX_REWRITE_BYTES) {
        "Artifact rewrite exceeds the bounded recovery limit."
    }
}

internal fun requireValidStagingMetadataReplacementSlice(
    bufferSize: Int,
    offset: Int,
    length: Int,
) {
    require(offset >= 0 && length >= 0 && offset <= bufferSize - length) {
        "Invalid artifact staging metadata replacement slice."
    }
    require(length <= DOWNLOAD_ARTIFACT_MAX_STAGING_METADATA_REPLACEMENT_BYTES) {
        "Artifact staging metadata replacement exceeds the HLS checkpoint limit."
    }
}

internal fun checkedArtifactLengthAfterWrite(
    currentLengthBytes: Long,
    additionalBytes: Long,
): Long {
    require(currentLengthBytes >= 0L && additionalBytes >= 0L) { "Artifact lengths must not be negative." }
    check(currentLengthBytes <= Long.MAX_VALUE - additionalBytes) { "Artifact length exceeds the supported range." }
    return currentLengthBytes + additionalBytes
}
