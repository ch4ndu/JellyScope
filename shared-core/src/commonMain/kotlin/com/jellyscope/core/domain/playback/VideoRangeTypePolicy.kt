// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Derives Jellyfin's supported `VideoRangeType` allowlist for one video codec. */
internal object VideoRangeTypePolicy {
    fun supportedRangeTypes(
        capabilities: VideoRangeCapabilities,
        preferSdr: Boolean,
        unsupportedRangeTypes: Set<String> = emptySet(),
    ): List<String> {
        val supported =
            if (preferSdr) {
                listOf(SDR, DOLBY_VISION_WITH_SDR)
            } else {
                val supportsHdr10Plus = capabilities.supportsHdr10 || capabilities.supportsHdr10Plus
                buildList {
                    add(SDR)
                    if (capabilities.supportsHdr10) add(HDR10)
                    if (supportsHdr10Plus) add(HDR10_PLUS)
                    if (capabilities.supportsHlg) add(HLG)
                    if (capabilities.supportsDolbyVision) add(DOLBY_VISION)

                    // Each DOVIWith* variant plays through EITHER route: a DV decoder
                    // renders the DV layer, or a non-DV device plays the fallback base
                    // range — so DV support alone must advertise the whole family.
                    val dovi = capabilities.supportsDolbyVision
                    if (dovi || capabilities.supportsHdr10) add(DOLBY_VISION_WITH_HDR10)
                    if (dovi || supportsHdr10Plus) add(DOLBY_VISION_WITH_HDR10_PLUS)
                    if (dovi || capabilities.supportsHlg) add(DOLBY_VISION_WITH_HLG)
                    add(DOLBY_VISION_WITH_SDR)

                    if (capabilities.supportsDolbyVisionWithEL) {
                        add(DOLBY_VISION_WITH_EL)
                        add(DOLBY_VISION_WITH_EL_HDR10_PLUS)
                    }
                }
            }

        val canonicalUnsupported = unsupportedRangeTypes.mapNotNullTo(mutableSetOf(), ::canonicalRangeType)
        return supported.filterNot { rangeType -> canonicalRangeType(rangeType) in canonicalUnsupported }
    }

    fun unsupportedRangeTypesForCodec(
        codec: String?,
        unsupportedRangeTypesByCodec: Map<String, Set<String>>,
    ): Set<String> {
        val canonicalCodec = canonicalVideoCodec(codec) ?: return emptySet()
        return unsupportedRangeTypesByCodec
            .filterKeys { candidate -> canonicalVideoCodec(candidate) == canonicalCodec }
            .values
            .flatten()
            .toSet()
    }

    fun isExplicitlyUnsupported(
        codec: String?,
        rangeType: String?,
        unsupportedRangeTypesByCodec: Map<String, Set<String>>,
    ): Boolean {
        val canonicalSourceRangeType = canonicalRangeType(rangeType) ?: return false
        return unsupportedRangeTypesForCodec(codec, unsupportedRangeTypesByCodec)
            .any { excluded -> canonicalRangeType(excluded) == canonicalSourceRangeType }
    }

    private fun canonicalRangeType(value: String?): String? =
        value
            ?.filter(Char::isLetterOrDigit)
            ?.lowercase()
            ?.takeIf(String::isNotBlank)

    private const val SDR = "SDR"
    private const val HDR10 = "HDR10"
    private const val HDR10_PLUS = "HDR10Plus"
    private const val HLG = "HLG"
    private const val DOLBY_VISION = "DOVI"
    private const val DOLBY_VISION_WITH_HDR10 = "DOVIWithHDR10"
    private const val DOLBY_VISION_WITH_HDR10_PLUS = "DOVIWithHDR10Plus"
    private const val DOLBY_VISION_WITH_HLG = "DOVIWithHLG"
    private const val DOLBY_VISION_WITH_SDR = "DOVIWithSDR"
    private const val DOLBY_VISION_WITH_EL = "DOVIWithEL"
    private const val DOLBY_VISION_WITH_EL_HDR10_PLUS = "DOVIWithELHDR10Plus"
}
