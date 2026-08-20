// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

/**
 * Untrusted user input for a fixed-quality download.  The server-derived duration, estimate,
 * transcode URL, and reservation are intentionally absent; those facts are established by the
 * authenticated fixed-download admission boundary.
 */
data class FixedDownloadDraft(
    val downloadId: DownloadId,
    val businessKey: DownloadBusinessKey,
    val quality: DownloadQuality.Fixed,
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
        require(
            subtitleSelection is DownloadSubtitleSelection.Off ||
                (subtitleSelection as? DownloadSubtitleSelection.Embedded)?.burnInConfirmed == true,
        ) { "Fixed download subtitles require Off or explicit permanent burn-in confirmation." }
    }
}
