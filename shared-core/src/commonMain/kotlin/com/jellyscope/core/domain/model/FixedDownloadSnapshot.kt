// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import com.jellyscope.core.domain.playback.BackendSourceDescriptor

/**
 * Rewrites a UI-created snapshot to describe the trusted converted artifact.
 *
 * The detail screen snapshot is intentionally source-shaped because it is created before server
 * admission. A fixed download must not persist those source facts: the completed artifact is a
 * finite local HLS package with one AAC audio rendition and no selectable subtitle rendition (a
 * confirmed subtitle has already been burned into the video). Display metadata is safe to retain
 * because it contains no URL, credential, or local path.
 *
 * The effective audio index is supplied only by the authenticated fixed-download preflight. A
 * missing source audio track therefore makes the conversion snapshot unavailable instead of
 * inventing a playable track.
 */
fun OfflineMediaSnapshot.toFixedDownloadSnapshot(effectiveAudioStreamIndex: Int): OfflineMediaSnapshot? {
    val sourceAudio =
        selectedAudioTrack
            ?.takeIf { track -> track.kind == OfflineTrackKind.Audio }
            ?: embeddedTracks.firstOrNull { track -> track.kind == OfflineTrackKind.Audio }
            ?: return null
    val effectiveAudio =
        sourceAudio.copy(
            kind = OfflineTrackKind.Audio,
            streamIndex = effectiveAudioStreamIndex,
            codec = "aac",
            isExternal = false,
        )
    return copy(
        embeddedTracks = listOf(effectiveAudio),
        selectedAudioTrack = effectiveAudio,
        selectedSubtitleTrack = null,
        backendSource =
            BackendSourceDescriptor(
                container = "hls",
                videoCodec = "h264",
                audioCodec = "aac",
                isHdrOrDolbyVision = false,
            ),
    )
}
