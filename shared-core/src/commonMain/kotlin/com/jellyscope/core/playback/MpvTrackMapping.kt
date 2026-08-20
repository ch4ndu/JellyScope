// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.EmbeddedTrackKind
import com.jellyscope.core.domain.playback.NativeTrackCandidate
import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.NativeTrackResolution
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import com.jellyscope.core.domain.playback.normalizeSubtitleFormat
import com.jellyscope.core.domain.playback.resolveEmbeddedTrack

/** Vendor-free snapshot of the fields used to match an mpv track-list entry. */
internal data class MpvTrackDescriptor(
    val selectorId: Long,
    val type: String?,
    val external: Boolean,
    val ffIndex: Int?,
    val codec: String?,
    val language: String?,
    val label: String?,
    val selected: Boolean = false,
)

internal fun resolveMpvTrackDescriptor(
    descriptor: PlannedEmbeddedTrack,
    tracks: List<MpvTrackDescriptor>,
    type: String,
): NativeTrackResolution<MpvTrackDescriptor> {
    val embeddedTracks = tracks.filter { track -> !track.external && track.type.equals(type, ignoreCase = true) }
    if (embeddedTracks.isEmpty()) return NativeTrackResolution(null, NativeTrackMappingResult.NotFound)

    val trackKind =
        if (type.equals(MPV_TRACK_TYPE_SUBTITLE, ignoreCase = true)) {
            EmbeddedTrackKind.Subtitle
        } else {
            EmbeddedTrackKind.Audio
        }
    return resolveEmbeddedTrack(
        descriptor = descriptor,
        orderedCandidates =
            embeddedTracks.map { track ->
                NativeTrackCandidate(
                    value = track,
                    stableSourceIndex = track.ffIndex,
                    codec =
                        if (trackKind == EmbeddedTrackKind.Subtitle) {
                            normalizeSubtitleFormat(track.codec)
                        } else {
                            track.codec
                        },
                    language = track.language,
                    label = track.label,
                )
            },
        trackKind = trackKind,
    )
}

internal const val MPV_TRACK_TYPE_AUDIO = "audio"
internal const val MPV_TRACK_TYPE_VIDEO = "video"
internal const val MPV_TRACK_TYPE_SUBTITLE = "sub"
