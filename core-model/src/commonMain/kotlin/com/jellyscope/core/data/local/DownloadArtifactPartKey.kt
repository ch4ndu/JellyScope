// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

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
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val DOWNLOAD_ORIGINAL_PART_KEY: DownloadArtifactPartKey = DownloadArtifactPartKey.from("original.bin")

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val DOWNLOAD_ORIGINAL_SIDECAR_PART_KEY: DownloadArtifactPartKey = DownloadArtifactPartKey.from("sidecar.vtt")
