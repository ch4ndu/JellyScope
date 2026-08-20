// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

data class NativeTrackCandidate<T>(
    val value: T,
    val stableSourceIndex: Int?,
    val codec: String?,
    val language: String?,
    val label: String?,
)

enum class NativeTrackMappingResult {
    Active,
    NotFound,
    Ambiguous,
    Unsupported,
    Timeout,
}

enum class NativeTrackMappingReason {
    StableIdentity,
    Ordinal,
    UniqueMetadata,
    AlreadySelected,
    NoCandidates,
    CodecConflict,
    LanguageConflict,
    TitleConflict,
    UnsupportedCandidate,
    AmbiguousIdentity,
    AmbiguousMetadata,
    NoComparableMetadata,
    Timeout,
}

internal enum class EmbeddedTrackKind {
    Audio,
    Subtitle,
}

data class NativeTrackResolution<T>(
    val candidate: T?,
    val result: NativeTrackMappingResult,
    val reason: NativeTrackMappingReason = result.defaultReason(),
)

internal fun <T> resolveEmbeddedTrack(
    descriptor: PlannedEmbeddedTrack,
    orderedCandidates: List<NativeTrackCandidate<T>>,
    trackKind: EmbeddedTrackKind,
): NativeTrackResolution<T> {
    if (orderedCandidates.isEmpty()) {
        return NativeTrackResolution(
            null,
            NativeTrackMappingResult.NotFound,
            NativeTrackMappingReason.NoCandidates,
        )
    }

    val stableMatches =
        orderedCandidates.filter { candidate ->
            candidate.stableSourceIndex == descriptor.jellyfinStreamIndex
        }
    if (stableMatches.size == 1) {
        return NativeTrackResolution(
            stableMatches.single().value,
            NativeTrackMappingResult.Active,
            NativeTrackMappingReason.StableIdentity,
        )
    }
    if (stableMatches.size > 1) {
        return NativeTrackResolution(
            null,
            NativeTrackMappingResult.Ambiguous,
            NativeTrackMappingReason.AmbiguousIdentity,
        )
    }

    val ordinalCandidate = orderedCandidates.getOrNull(descriptor.filteredContainerOrdinal)
    if (ordinalCandidate != null && !ordinalCandidate.metadataConflictsWith(descriptor, trackKind)) {
        return NativeTrackResolution(
            ordinalCandidate.value,
            NativeTrackMappingResult.Active,
            NativeTrackMappingReason.Ordinal,
        )
    }

    val metadataMatches =
        orderedCandidates.filter { candidate -> candidate.metadataMatches(descriptor, trackKind) }
    return when (metadataMatches.size) {
        1 ->
            NativeTrackResolution(
                metadataMatches.single().value,
                NativeTrackMappingResult.Active,
                NativeTrackMappingReason.UniqueMetadata,
            )
        0 ->
            NativeTrackResolution(
                null,
                NativeTrackMappingResult.NotFound,
                ordinalCandidate?.metadataConflictReason(descriptor, trackKind)
                    ?: orderedCandidates.firstNotNullOfOrNull { candidate ->
                        candidate.metadataConflictReason(descriptor, trackKind)
                    }
                    ?: NativeTrackMappingReason.NoComparableMetadata,
            )
        else ->
            NativeTrackResolution(
                null,
                NativeTrackMappingResult.Ambiguous,
                NativeTrackMappingReason.AmbiguousMetadata,
            )
    }
}

private fun <T> NativeTrackCandidate<T>.metadataConflictsWith(
    descriptor: PlannedEmbeddedTrack,
    trackKind: EmbeddedTrackKind,
): Boolean = metadataConflictReason(descriptor, trackKind) != null

private fun <T> NativeTrackCandidate<T>.metadataConflictReason(
    descriptor: PlannedEmbeddedTrack,
    trackKind: EmbeddedTrackKind,
): NativeTrackMappingReason? =
    metadataPairs(descriptor, trackKind)
        .firstOrNull { pair ->
            pair.expected != null && pair.actual != null && pair.expected != pair.actual
        }?.conflictReason

private fun <T> NativeTrackCandidate<T>.metadataMatches(
    descriptor: PlannedEmbeddedTrack,
    trackKind: EmbeddedTrackKind,
): Boolean {
    val pairs = metadataPairs(descriptor, trackKind)
    return pairs.any { pair -> pair.expected != null && pair.actual != null } &&
        pairs.none { pair -> pair.expected != null && pair.actual != null && pair.expected != pair.actual }
}

private fun <T> NativeTrackCandidate<T>.metadataPairs(
    descriptor: PlannedEmbeddedTrack,
    trackKind: EmbeddedTrackKind,
): List<NativeTrackMetadataPair> =
    listOf(
        NativeTrackMetadataPair(
            expected = descriptor.codec.forComparison(trackKind),
            actual = codec.forComparison(trackKind),
            conflictReason = NativeTrackMappingReason.CodecConflict,
        ),
        NativeTrackMetadataPair(
            expected = descriptor.normalizedLanguage,
            actual = normalizeTrackLanguage(language),
            conflictReason = NativeTrackMappingReason.LanguageConflict,
        ),
        NativeTrackMetadataPair(
            expected = descriptor.label.normalizedTrackMetadata(),
            actual = label.normalizedTrackMetadata(),
            conflictReason = NativeTrackMappingReason.TitleConflict,
        ),
    )

private data class NativeTrackMetadataPair(
    val expected: String?,
    val actual: String?,
    val conflictReason: NativeTrackMappingReason,
)

private fun String?.forComparison(trackKind: EmbeddedTrackKind): String? =
    when (trackKind) {
        EmbeddedTrackKind.Audio -> normalizeAudioCodecFamily(this)
        EmbeddedTrackKind.Subtitle -> normalizeSubtitleFormat(this)
    }

private fun NativeTrackMappingResult.defaultReason(): NativeTrackMappingReason =
    when (this) {
        NativeTrackMappingResult.Active -> NativeTrackMappingReason.UniqueMetadata
        NativeTrackMappingResult.NotFound -> NativeTrackMappingReason.NoCandidates
        NativeTrackMappingResult.Ambiguous -> NativeTrackMappingReason.AmbiguousMetadata
        NativeTrackMappingResult.Unsupported -> NativeTrackMappingReason.UnsupportedCandidate
        NativeTrackMappingResult.Timeout -> NativeTrackMappingReason.Timeout
    }
