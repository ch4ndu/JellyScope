// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeTrackResolutionTest {
    @Test
    fun exactStableIdentityWinsBeforeOrdinalMetadata() {
        val result =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 0, language = "eng"),
                listOf(
                    candidate("ordinal", sourceIndex = 3, language = "eng"),
                    candidate("stable", sourceIndex = 7, language = "spa"),
                ),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingResult.Active, result.result)
        assertEquals("stable", result.candidate)
    }

    @Test
    fun conflictingOrdinalUsesOnlyUniqueMetadataMatch() {
        val result =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 0, language = "eng", codec = "aac"),
                listOf(
                    candidate("wrong", language = "spa", codec = "aac"),
                    candidate("right", language = "eng", codec = "aac"),
                ),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingResult.Active, result.result)
        assertEquals("right", result.candidate)
    }

    @Test
    fun duplicateMetadataIsAmbiguousInsteadOfClosestMatch() {
        val result =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 9, language = "eng"),
                listOf(
                    candidate("first", language = "eng"),
                    candidate("second", language = "eng"),
                ),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingResult.Ambiguous, result.result)
        assertNull(result.candidate)
        assertEquals(NativeTrackMappingReason.AmbiguousMetadata, result.reason)
    }

    @Test
    fun subtitleAndAudioCodecAliasesMatchAsFamilies() {
        val subtitle =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 0, codec = "srt"),
                listOf(candidate("subtitle", codec = "application/x-subrip")),
                trackKind = EmbeddedTrackKind.Subtitle,
            )
        val audio =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 0, codec = "aac"),
                listOf(candidate("audio", codec = "mp4a.40.2")),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingResult.Active, subtitle.result)
        assertEquals(NativeTrackMappingReason.Ordinal, subtitle.reason)
        assertEquals(NativeTrackMappingResult.Active, audio.result)
        assertEquals(NativeTrackMappingReason.Ordinal, audio.reason)
    }

    @Test
    fun atmosAndMimeVariantsResolveIntoTheEac3Family() {
        listOf("audio/eac3-joc", "eac3-joc", "ec-3", "ec+3", "audio/eac3").forEach { alias ->
            val result =
                resolveEmbeddedTrack(
                    descriptor(streamIndex = 1, ordinal = 0, codec = "eac3"),
                    listOf(candidate("audio", codec = alias)),
                    trackKind = EmbeddedTrackKind.Audio,
                )

            assertEquals(NativeTrackMappingResult.Active, result.result, "alias $alias should match eac3")
            assertEquals(NativeTrackMappingReason.Ordinal, result.reason)
        }
    }

    @Test
    fun isoLanguageVariantsResolveInsteadOfConflicting() {
        // The (Tillu)² DD+ 5.1 regression: Jellyfin descriptor language "tel"
        // vs Media3 candidate "te" must match; different languages still conflict.
        val variant =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 1, ordinal = 0, language = "tel", codec = "eac3"),
                listOf(candidate("audio", language = "te", codec = "eac3-joc")),
                trackKind = EmbeddedTrackKind.Audio,
            )
        val conflict =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 1, ordinal = 0, language = "tel", codec = "eac3"),
                listOf(candidate("audio", language = "ta", codec = "eac3")),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingResult.Active, variant.result)
        assertEquals(NativeTrackMappingReason.Ordinal, variant.reason)
        assertEquals(NativeTrackMappingResult.NotFound, conflict.result)
        assertEquals(NativeTrackMappingReason.LanguageConflict, conflict.reason)
    }

    @Test
    fun undeterminedLanguageDoesNotConflictWithARealLanguage() {
        val result =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 1, ordinal = 9, language = normalizeTrackLanguage("und"), codec = "aac"),
                listOf(candidate("audio", language = "eng", codec = "aac")),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingResult.Active, result.result)
        assertEquals(NativeTrackMappingReason.UniqueMetadata, result.reason)
    }

    @Test
    fun unknownAndCrossFamilyAudioCodecsStillConflict() {
        val unknown =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 1, ordinal = 0, codec = "opus"),
                listOf(candidate("audio", codec = "flac")),
                trackKind = EmbeddedTrackKind.Audio,
            )
        val dtsFamilies =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 1, ordinal = 0, codec = "dts"),
                listOf(candidate("audio", codec = "vnd.dts.hd")),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingResult.NotFound, unknown.result)
        assertEquals(NativeTrackMappingReason.CodecConflict, unknown.reason)
        assertEquals(NativeTrackMappingResult.NotFound, dtsFamilies.result)
        assertEquals(NativeTrackMappingReason.CodecConflict, dtsFamilies.reason)
    }

    @Test
    fun stableIdentityStillPrecedesAudioCodecComparison() {
        val result =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 4, ordinal = 0, codec = "eac3"),
                listOf(candidate("stable", sourceIndex = 4, codec = "flac")),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingResult.Active, result.result)
        assertEquals(NativeTrackMappingReason.StableIdentity, result.reason)
    }

    @Test
    fun exactOrdinalAcceptsNullableMetadataAndMissingComparableMetadataDoesNotClaimNoCandidates() {
        val ordinal =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 1, language = null, codec = null),
                listOf(candidate("first"), candidate("second")),
                trackKind = EmbeddedTrackKind.Subtitle,
            )
        val missing =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 9, language = "eng", codec = "srt"),
                listOf(candidate("candidate")),
                trackKind = EmbeddedTrackKind.Subtitle,
            )

        assertEquals("second", ordinal.candidate)
        assertEquals(NativeTrackMappingReason.Ordinal, ordinal.reason)
        assertEquals(NativeTrackMappingResult.NotFound, missing.result)
        assertEquals(NativeTrackMappingReason.NoComparableMetadata, missing.reason)
    }

    @Test
    fun stableIdentityAndConflictReasonsRemainExplicit() {
        val duplicateIdentity =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 0),
                listOf(candidate("first", sourceIndex = 7), candidate("second", sourceIndex = 7)),
                trackKind = EmbeddedTrackKind.Audio,
            )
        val languageConflict =
            resolveEmbeddedTrack(
                descriptor(streamIndex = 7, ordinal = 0, language = "eng"),
                listOf(candidate("candidate", language = "spa")),
                trackKind = EmbeddedTrackKind.Audio,
            )

        assertEquals(NativeTrackMappingReason.AmbiguousIdentity, duplicateIdentity.reason)
        assertEquals(NativeTrackMappingReason.LanguageConflict, languageConflict.reason)
    }

    private fun descriptor(
        streamIndex: Int,
        ordinal: Int,
        language: String? = null,
        codec: String? = null,
    ) = PlannedEmbeddedTrack(streamIndex, ordinal, codec, language, null)

    private fun candidate(
        value: String,
        sourceIndex: Int? = null,
        language: String? = null,
        codec: String? = null,
    ) = NativeTrackCandidate(value, sourceIndex, codec, language, null)
}
