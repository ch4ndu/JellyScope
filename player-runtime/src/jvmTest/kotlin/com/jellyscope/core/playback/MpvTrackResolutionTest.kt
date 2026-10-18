// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.NativeTrackMappingResult
import com.jellyscope.core.domain.playback.PlannedEmbeddedTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvTrackResolutionTest {
    @Test
    fun candidatesAreScopedToTheRequestedTrackType() {
        val resolution =
            resolveMpvTrackDescriptor(
                descriptor(streamIndex = 50, ordinal = 0),
                listOf(
                    track(selectorId = 8, ffIndex = null),
                    track(selectorId = 12, ffIndex = null, type = MPV_TRACK_TYPE_SUBTITLE),
                ),
                MPV_TRACK_TYPE_AUDIO,
            )

        assertEquals(NativeTrackMappingResult.Active, resolution.result)
        assertEquals(8L, resolution.candidate?.selectorId)
    }

    @Test
    fun exactFfIndexUsesActualMpvSelectorId() {
        val resolution =
            resolveMpvTrackDescriptor(
                descriptor(streamIndex = 7, ordinal = 0),
                listOf(track(selectorId = 41, ffIndex = 2), track(selectorId = 99, ffIndex = 7)),
                MPV_TRACK_TYPE_AUDIO,
            )

        assertEquals(NativeTrackMappingResult.Active, resolution.result)
        assertEquals(99L, resolution.candidate?.selectorId)
    }

    @Test
    fun externalTracksDoNotShiftEmbeddedOrdinal() {
        val resolution =
            resolveMpvTrackDescriptor(
                descriptor(streamIndex = 50, ordinal = 1, language = "jpn"),
                listOf(
                    track(selectorId = 4, ffIndex = null, external = true),
                    track(selectorId = 8, ffIndex = null, language = "eng"),
                    track(selectorId = 12, ffIndex = null, language = "jpn"),
                ),
                MPV_TRACK_TYPE_AUDIO,
            )

        assertEquals(12L, resolution.candidate?.selectorId)
    }

    @Test
    fun duplicateMetadataIsAmbiguousWhenOrdinalConflicts() {
        val resolution =
            resolveMpvTrackDescriptor(
                descriptor(streamIndex = 50, ordinal = 0, language = "jpn"),
                listOf(
                    track(selectorId = 8, ffIndex = null, language = "eng"),
                    track(selectorId = 12, ffIndex = null, language = "jpn"),
                    track(selectorId = 16, ffIndex = null, language = "jpn"),
                ),
                MPV_TRACK_TYPE_AUDIO,
            )

        assertEquals(NativeTrackMappingResult.Ambiguous, resolution.result)
        assertEquals(null, resolution.candidate)
    }

    @Test
    fun subtitleAndAudioCodecAliasesResolveAsFamilies() {
        val subtitle =
            resolveMpvTrackDescriptor(
                descriptor(streamIndex = 50, ordinal = 0, codec = "srt"),
                listOf(track(selectorId = 8, ffIndex = null, codec = "subrip", type = MPV_TRACK_TYPE_SUBTITLE)),
                MPV_TRACK_TYPE_SUBTITLE,
            )
        // Audio codec identity is family-based since the eac3-joc fix: an
        // mp4a codec-string candidate matches an "aac" descriptor while a
        // genuinely different family still conflicts.
        val audioAlias =
            resolveMpvTrackDescriptor(
                descriptor(streamIndex = 50, ordinal = 0, codec = "aac"),
                listOf(track(selectorId = 12, ffIndex = null, codec = "mp4a.40.2")),
                MPV_TRACK_TYPE_AUDIO,
            )
        val audioConflict =
            resolveMpvTrackDescriptor(
                descriptor(streamIndex = 50, ordinal = 0, codec = "aac"),
                listOf(track(selectorId = 12, ffIndex = null, codec = "ac3")),
                MPV_TRACK_TYPE_AUDIO,
            )

        assertEquals(NativeTrackMappingResult.Active, subtitle.result)
        assertEquals(8L, subtitle.candidate?.selectorId)
        assertEquals(NativeTrackMappingResult.Active, audioAlias.result)
        assertEquals(12L, audioAlias.candidate?.selectorId)
        assertEquals(NativeTrackMappingResult.NotFound, audioConflict.result)
    }

    @Test
    fun audioLabelMappingRemainsLiteral() {
        val resolution =
            resolveMpvTrackDescriptor(
                descriptor(streamIndex = 50, ordinal = 0, label = "English AAC 5.1"),
                listOf(track(selectorId = 8, ffIndex = null, label = "English Stereo")),
                MPV_TRACK_TYPE_AUDIO,
            )

        assertEquals(NativeTrackMappingResult.NotFound, resolution.result)
    }

    private fun descriptor(
        streamIndex: Int,
        ordinal: Int,
        language: String? = null,
        codec: String? = null,
        label: String? = null,
    ) = PlannedEmbeddedTrack(
        jellyfinStreamIndex = streamIndex,
        filteredContainerOrdinal = ordinal,
        codec = codec,
        normalizedLanguage = language,
        label = label,
    )

    private fun track(
        selectorId: Long,
        ffIndex: Int?,
        language: String? = null,
        external: Boolean = false,
        codec: String? = null,
        label: String? = null,
        type: String = MPV_TRACK_TYPE_AUDIO,
    ) = MpvTrackDescriptor(
        selectorId = selectorId,
        type = type,
        external = external,
        ffIndex = ffIndex,
        codec = codec,
        language = language,
        label = label,
    )
}
