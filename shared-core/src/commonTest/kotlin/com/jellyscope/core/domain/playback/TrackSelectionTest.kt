// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackSelectionTest {
    @Test
    fun everyCappedQualityOptionRendersItsResolutionLabel() {
        // Quality options expose structured display-resolution data to every
        // consumer; each capped rung must produce one.
        val options = qualityOptions(null)

        assertEquals(null, options.first().displayResolution) // Original has no rung label
        val cappedLabels = options.drop(1).map { option -> option.displayResolution }
        assertEquals(
            listOf("2160p", "2160p", "1080p", "1080p", "1080p", "720p", "720p", "480p"),
            cappedLabels,
        )
    }

    @Test
    fun trackOptionsUseTypeRelativeOrdinalsWithoutSpeculativeDeliveryLabels() {
        val streams =
            listOf(
                stream(index = 0, type = "Video"),
                stream(index = 1, type = "Audio", isDefault = true),
                stream(index = 2, type = "Subtitle", codec = "pgssub", isDefault = true),
                stream(index = 3, type = "Audio"),
                stream(index = 4, type = "Subtitle", codec = "ass"),
            )

        assertEquals(listOf(0, 1), audioOptions(streams).map { it.ordinal })
        assertEquals(listOf(1, 3), audioOptions(streams).map { it.streamIndex })
        assertEquals(listOf(0, 1), subtitleOptions(streams).map { it.ordinal })
        assertEquals(listOf(2, 4), subtitleOptions(streams).map { it.streamIndex })
        assertTrue(subtitleOptions(streams).hasSelectableSubtitleChoice())
    }

    @Test
    fun preferredTrackSelectionMatchesLanguageCodesAndDisplayNames() {
        val audio = audioOptions(listOf(stream(1, "Audio", "English", "eng"), stream(3, "Audio", "Spanish", "spa")))
        val subtitles = subtitleOptions(listOf(stream(4, "Subtitle", "English SDH", "eng"), stream(5, "Subtitle", "Spanish", "spa")))

        assertEquals(3, audio.preferredAudioStreamIndex("es"))
        assertEquals(4, subtitles.preferredSubtitleStreamIndex("English"))
    }

    @Test
    fun installedDeliveryDeterminesRenderingInsteadOfStreamMode() {
        val options = subtitleOptions(listOf(stream(2, "Subtitle", "English", codec = "srt")))
        val target = subtitleTarget(2, LocalSubtitleKind.EmbeddedText)
        val local =
            subtitleRenderInfo(
                options,
                PlannedSubtitle.Track(2, embeddedTrack(2, 0), SubtitleDeliveryMethod.Embed, SubtitleKind.Text, activationTarget = target),
                SubtitleActivationState.Active(target),
            )
        val encoded =
            subtitleRenderInfo(
                options,
                PlannedSubtitle.Track(2, null, SubtitleDeliveryMethod.Encode, SubtitleKind.Text),
                SubtitleActivationState.None,
            )

        assertEquals(SubtitleRenderMode.LocalEmbeddedText, local.mode)
        assertTrue(local.styleable)
        assertEquals(SubtitleRenderMode.ServerBurnedIn, encoded.mode)
        assertFalse(encoded.styleable)
    }

    @Test
    fun localBitmapAndHlsAreTrackedButOnlyTextIsStyleable() {
        val options = subtitleOptions(listOf(stream(2, "Subtitle", "English PGS", codec = "pgs")))
        val bitmapTarget = subtitleTarget(2, LocalSubtitleKind.EmbeddedBitmap)
        val bitmap =
            subtitleRenderInfo(
                options,
                PlannedSubtitle.Track(
                    2,
                    embeddedTrack(2, 0),
                    SubtitleDeliveryMethod.Embed,
                    SubtitleKind.Bitmap,
                    activationTarget = bitmapTarget,
                ),
                SubtitleActivationState.Active(bitmapTarget),
            )

        assertEquals(SubtitleRenderMode.LocalEmbeddedBitmap, bitmap.mode)
        assertFalse(bitmap.styleable)
    }

    @Test
    fun staleActivationStaysPendingAndExactFailureIsUnavailable() {
        val options = subtitleOptions(listOf(stream(2, "Subtitle", codec = "srt")))
        val target = subtitleTarget(2, LocalSubtitleKind.EmbeddedText)
        val planned =
            PlannedSubtitle.Track(
                2,
                embeddedTrack(2, 0),
                SubtitleDeliveryMethod.Embed,
                SubtitleKind.Text,
                activationTarget = target,
            )

        assertEquals(
            SubtitleRenderStatus.Pending,
            subtitleRenderInfo(options, planned, SubtitleActivationState.Active(target.copy(requestId = 9))).status,
        )
        assertEquals(
            SubtitleRenderStatus.Unavailable,
            subtitleRenderInfo(options, planned, SubtitleActivationState.Unavailable(target)).status,
        )
    }

    @Test
    fun offAndUnavailableAreExplicitInstalledStates() {
        val options = subtitleOptions(listOf(stream(2, "Subtitle", codec = "srt")))

        assertEquals(SubtitleRenderStatus.Off, subtitleRenderInfo(options, PlannedSubtitle.Off, SubtitleActivationState.None).status)
        assertEquals(
            SubtitleRenderStatus.Unavailable,
            subtitleRenderInfo(
                options,
                PlannedSubtitle.Unavailable(2, SubtitleKind.Text, reason = "unsupported"),
                SubtitleActivationState.None,
            ).status,
        )
    }

    @Test
    fun unknownExternalMimeIsUnsupportedInsteadOfSubRip() {
        assertNull(externalSubtitleMimeType(stream(type = "Subtitle", codec = "unknown")))
        assertEquals("text/vtt", externalSubtitleMimeType(stream(type = "Subtitle", deliveryUrl = "/subtitles/1.vtt")))
    }

    @Test
    fun knownSubtitleCodecAliasesNormalizeByFamilyWithoutGuessingUnknownValues() {
        val families =
            mapOf(
                "srt" to listOf("srt", "subrip", "application/x-subrip"),
                "vtt" to listOf("vtt", "webvtt", "text/vtt", "text/webvtt"),
                "ass" to listOf("ass", "ssa", "text/x-ssa", "application/x-ass"),
                "ttml" to listOf("ttml", "application/ttml+xml"),
                "pgs" to listOf("pgs", "pgssub", "application/pgs", "hdmv_pgs_subtitle"),
                "vobsub" to listOf("vobsub", "dvdsub", "application/vobsub", "dvd_subtitle"),
                "dvbsub" to listOf("dvbsub", "dvb", "application/dvbsubs", "dvb_subtitle"),
                "xsub" to listOf("xsub"),
            )

        families.forEach { (canonical, aliases) ->
            aliases.forEach { alias ->
                assertEquals(canonical, normalizeSubtitleFormat("  ${alias.uppercase()}  "))
                assertTrue(subtitleFormatsEquivalent(canonical, alias))
            }
        }
        assertEquals("unknown-format", normalizeSubtitleFormat(" Unknown-Format "))
        assertFalse(subtitleFormatsEquivalent("unknown-format", "srt"))
        assertNull(normalizeSubtitleFormat(null))
    }

    @Test
    fun trackLanguagesCanonicalizeAcrossIsoVariantsAndKeepUnknownIsolated() {
        // 639-1 <-> 639-2/T (the (Tillu)² regression: server "tel" vs Media3 "te").
        assertEquals("tel", normalizeTrackLanguage("te"))
        assertEquals("tel", normalizeTrackLanguage("tel"))
        assertEquals("tam", normalizeTrackLanguage("ta"))
        assertEquals("hin", normalizeTrackLanguage("hi"))
        assertEquals("eng", normalizeTrackLanguage("en-US"))
        // 639-2 bibliographic -> terminological variants.
        assertEquals("fra", normalizeTrackLanguage("fre"))
        assertEquals("deu", normalizeTrackLanguage("ger"))
        assertEquals("zho", normalizeTrackLanguage("chi"))
        assertEquals("ces", normalizeTrackLanguage("cze"))
        // Unknown-language aliases carry no comparable identity.
        listOf("und", "unknown", "undetermined", "mul", "zxx").forEach { alias ->
            assertNull(normalizeTrackLanguage(alias), "alias $alias")
        }
        // Other unknown values pass through normalized instead of being guessed.
        assertEquals("xxx", normalizeTrackLanguage(" XXX "))
        assertNull(normalizeTrackLanguage(null))
        assertNull(normalizeTrackLanguage("  "))
    }

    @Test
    fun audioCodecFamiliesNormalizeAliasesAndKeepUnknownAndDistinctFamiliesIsolated() {
        val families =
            mapOf(
                "eac3" to listOf("eac3", "eac-3", "eac3-joc", "ec-3", "ec+3", "audio/eac3", "audio/eac3-joc"),
                "ac3" to listOf("ac3", "ac-3", "audio/ac3"),
                "dts" to listOf("dts", "dca", "vnd.dts", "audio/vnd.dts"),
                "dtshd" to listOf("dtshd", "dts-hd", "vnd.dts.hd", "audio/vnd.dts.hd"),
                "truehd" to listOf("truehd", "true-hd", "audio/true-hd"),
                "aac" to listOf("aac", "mp4a-latm", "audio/mp4a-latm", "mp4a.40.2", "mp4a.40.5", "audio/mp4a.40.2"),
                "mp3" to listOf("mp3", "mpeg", "audio/mpeg"),
            )

        families.forEach { (canonical, aliases) ->
            aliases.forEach { alias ->
                assertEquals(canonical, normalizeAudioCodecFamily("  ${alias.uppercase()}  "), "alias $alias")
            }
        }
        // Families that differ behaviorally never merge, and unknown codecs
        // pass through normalized instead of being guessed into a family.
        assertFalse(normalizeAudioCodecFamily("dts") == normalizeAudioCodecFamily("dtshd"))
        assertFalse(normalizeAudioCodecFamily("dtshd") == normalizeAudioCodecFamily("truehd"))
        assertEquals("opus", normalizeAudioCodecFamily(" Opus "))
        assertEquals("flac", normalizeAudioCodecFamily("audio/flac"))
        assertNull(normalizeAudioCodecFamily(null))
        assertNull(normalizeAudioCodecFamily("audio/"))
    }

    @Test
    fun descriptorsUseRawSourceTitleAsComparableIdentityForBothTrackKinds() {
        val subtitle =
            responseEmbeddedSubtitleTracks(
                listOf(
                    stream(
                        index = 2,
                        type = "Subtitle",
                        displayTitle = "English SDH (Default)",
                        title = "English SDH",
                        codec = "application/x-subrip",
                        deliveryMethod = "Embed",
                    ),
                ),
            ).single()
        // Native candidates carry the container track title, so the audio
        // descriptor must expose the raw source Title — the synthesized
        // DisplayTitle ("British - English - Dolby Digital+ - 5.1 - Default")
        // can never match it and forced TitleConflict transcodes.
        val audio =
            embeddedAudioTracks(
                listOf(
                    stream(
                        index = 1,
                        type = "Audio",
                        displayTitle = "British - English - Dolby Digital+ - 5.1 - Default",
                        title = "British",
                        codec = "mp4a.40.2",
                    ),
                ),
            ).single()

        assertEquals("srt", subtitle.codec)
        assertEquals("english sdh", subtitle.label)
        assertEquals("mp4a.40.2", audio.codec)
        assertEquals("british", audio.label)
    }

    @Test
    fun responseSubtitleDescriptorsStampTheExactFilteredCohortSize() {
        val descriptors =
            responseEmbeddedSubtitleTracks(
                listOf(
                    stream(index = 20, type = "Subtitle", deliveryMethod = "External"),
                    stream(index = 21, type = "Subtitle", deliveryMethod = "Encode"),
                    stream(index = 22, type = "Subtitle", deliveryMethod = "Drop"),
                    stream(index = 23, type = "Subtitle", deliveryMethod = "Embed"),
                    stream(index = 24, type = "Subtitle", deliveryMethod = "Hls"),
                ),
            )

        assertEquals(listOf(23 to 0, 24 to 1), descriptors.map { it.jellyfinStreamIndex to it.filteredContainerOrdinal })
        assertEquals(listOf(2, 2), descriptors.map { it.responseAuthoritativeCohortSize })
    }

    @Test
    fun qualityAndSizeHelpersRemainStable() {
        val options = qualityOptions(17_100_000L)
        assertEquals(
            listOf(12_000_000L, 8_000_000L, 4_000_000L, 2_000_000L, 1_500_000L),
            options.drop(1).map { it.maxBitrateBps },
        )
        assertEquals("1080p", options[1].displayResolution)
        assertEquals(1_920, options[1].resolutionWidth)
        assertEquals(1_080, options[1].resolutionHeight)
        assertEquals(
            listOf(
                3_840 to 2_160,
                3_840 to 2_160,
                1_920 to 1_080,
                1_920 to 1_080,
                1_920 to 1_080,
                1_280 to 720,
                1_280 to 720,
                854 to 480,
            ),
            qualityRungs.map { rung -> rung.width to rung.height },
        )
        assertEquals("480p", qualityRungForBitrate(1_500_000L)?.let { rung -> "${rung.height}p" })
        assertEquals(854, qualityRungForBitrate(1_500_000L)?.width)
        assertNull(qualityRungForBitrate(6_000_000L))
        assertTrue(abs(estimatedGbPerHour(17_100_000L) - 7.7) < 0.1)
    }

    @Test
    fun settingsProjectionUsesCanonicalLadderAndPreservesExactCustomBitrate() {
        assertEquals(
            listOf<Long?>(
                null,
                80_000_000L,
                40_000_000L,
                20_000_000L,
                12_000_000L,
                8_000_000L,
                4_000_000L,
                2_000_000L,
                1_500_000L,
            ),
            settingsQualityChoices(null).map { choice -> choice.maxBitrateBps },
        )

        val choices = settingsQualityChoices(10_000_000L)
        val custom = choices.single { choice -> choice.maxBitrateBps == 10_000_000L }
        assertTrue(custom.isCustom)
        assertNull(custom.resolutionWidth)
        assertNull(custom.resolutionHeight)
        assertEquals(10_000_000L, choices[1].maxBitrateBps)
    }

    @Test
    fun vlcBudgetIsIndependentNumericPolicyAndDisabledByDefault() {
        assertEquals(
            listOf<Long?>(null, 2_000_000L, 4_000_000L, 8_000_000L, 12_000_000L, 20_000_000L, 40_000_000L, 80_000_000L),
            vlcTranscodeBudgetOptions,
        )
        assertNull(normalizeVlcTranscodeBitrate(null))
        assertNull(normalizeVlcTranscodeBitrate(0L))
        assertNull(normalizeVlcTranscodeBitrate(-1L))
        assertNull(normalizeVlcTranscodeBitrate(Int.MAX_VALUE.toLong() + 1L))
        assertEquals(8_000_000L, normalizeVlcTranscodeBitrate(8_000_000L))
        assertEquals("1.5", formatBitrateMbps(1_500_000L))
    }

    @Test
    fun resolvesExternalSubtitleUrlsAgainstServerUrl() {
        assertEquals(
            "https://jellyfin.example/Videos/item/subtitles/1.srt",
            resolveExternalSubtitleUrl("https://jellyfin.example/", "/Videos/item/subtitles/1.srt"),
        )
    }
}

private fun embeddedTrack(
    streamIndex: Int,
    ordinal: Int,
): PlannedEmbeddedTrack =
    PlannedEmbeddedTrack(
        jellyfinStreamIndex = streamIndex,
        filteredContainerOrdinal = ordinal,
        codec = null,
        normalizedLanguage = null,
        label = null,
    )

private fun stream(
    index: Int? = null,
    type: String? = null,
    displayTitle: String? = null,
    language: String? = null,
    codec: String? = null,
    isDefault: Boolean? = null,
    isExternal: Boolean? = null,
    deliveryMethod: String? = null,
    deliveryUrl: String? = null,
    title: String? = null,
) = PlaybackMediaStream(
    index,
    type,
    displayTitle,
    title,
    language,
    codec,
    null,
    null,
    null,
    isDefault,
    isExternal,
    deliveryMethod,
    deliveryUrl,
)

private fun subtitleTarget(
    streamIndex: Int,
    kind: LocalSubtitleKind,
) = SubtitleActivationTarget(1L, "item-1", SubtitleActivationIdentity.JellyfinTrack(streamIndex), kind)
