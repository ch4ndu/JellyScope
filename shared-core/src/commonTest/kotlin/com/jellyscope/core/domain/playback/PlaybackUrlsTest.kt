// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackUrlsTest {
    @Test
    fun subtitleHonestTranscodingUrlStripsAttachedSubtitleForNoneAndLocalSelections() {
        val url =
            "/Videos/item/master.m3u8?MediaSourceId=source&SubtitleStreamIndex=4" +
                "&SubtitleMethod=Encode&ApiKey=secret"

        assertEquals(
            "/Videos/item/master.m3u8?MediaSourceId=source&ApiKey=secret",
            subtitleHonestTranscodingUrl(url, selectedSubtitleStreamIndex = null),
        )
        assertEquals(
            "/Videos/item/master.m3u8?MediaSourceId=source&ApiKey=secret",
            subtitleHonestTranscodingUrl(
                url,
                selectedSubtitleStreamIndex = SubtitleSelectionIntent.LocalAsset("local-subtitle").selectedIndexOrNull(),
            ),
        )
    }

    @Test
    fun subtitleHonestTranscodingUrlStripsMismatchedAndRepeatedParametersWithoutReorderingOthers() {
        val url =
            "/Videos/item/master.m3u8?MediaSourceId=source&SubtitleStreamIndex=4" +
                "&SubtitleMethod=Encode&ApiKey=secret&SubtitleStreamIndex=5&SubtitleMethod=Hls"

        assertEquals(
            "/Videos/item/master.m3u8?MediaSourceId=source&ApiKey=secret",
            subtitleHonestTranscodingUrl(url, selectedSubtitleStreamIndex = 4),
        )
    }

    @Test
    fun subtitleHonestTranscodingUrlKeepsMatchingAndAbsentSubtitleParameters() {
        val matching = "/Videos/item/master.m3u8?SubtitleStreamIndex=4&SubtitleMethod=Encode&ApiKey=secret"
        val absent = "/Videos/item/master.m3u8?MediaSourceId=source&ApiKey=secret"

        assertEquals(matching, subtitleHonestTranscodingUrl(matching, selectedSubtitleStreamIndex = 4))
        assertEquals(absent, subtitleHonestTranscodingUrl(absent, selectedSubtitleStreamIndex = null))
    }

    @Test
    fun subtitleHonestTranscodingUrlComposesWithResolutionCapRewrite() {
        val subtitleStripped =
            subtitleHonestTranscodingUrl(
                transcodingUrl =
                    "/Videos/item/master.m3u8?SubtitleStreamIndex=4&SubtitleMethod=Encode" +
                        "&VideoCodec=h264&MediaSourceId=source",
                selectedSubtitleStreamIndex = null,
            )

        assertEquals(
            "/Videos/item/master.m3u8?VideoCodec=h264&MediaSourceId=source" +
                "&MaxWidth=1332&MaxHeight=2160&Width=1332&Height=2160",
            transcodeResolutionCap(
                transcodingUrl = subtitleStripped,
                sourceWidth = 2160,
                sourceHeight = 3502,
                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(3840, 2160)),
            ),
        )
    }

    @Test
    fun transcodeResolutionCapCanonicalizesCodecAliasSoH265IsStillCapped() {
        // The URL carries the h265 alias while the capability map is keyed by the
        // canonical "hevc"; the cap must still apply (portrait black-screen guard).
        assertEquals(
            "/Videos/item/master.m3u8?VideoCodec=h265&MaxWidth=1332&MaxHeight=2160&Width=1332&Height=2160",
            transcodeResolutionCap(
                transcodingUrl = "/Videos/item/master.m3u8?VideoCodec=h265",
                sourceWidth = 2160,
                sourceHeight = 3502,
                videoResolutionsByCodec = mapOf("hevc" to VideoCodecResolution(3840, 2160)),
            ),
        )
    }

    @Test
    fun transcodeResolutionCapUsesUserBoundWhenDesktopHasNoDecoderCeiling() {
        assertEquals(
            "/Videos/item/master.m3u8?VideoCodec=h264&MaxWidth=1920&MaxHeight=1080&Width=1920&Height=1080",
            transcodeResolutionCap(
                transcodingUrl = "/Videos/item/master.m3u8?VideoCodec=h264",
                sourceWidth = 3_840,
                sourceHeight = 2_160,
                videoResolutionsByCodec = emptyMap(),
                userResolutionCeiling = VideoCodecResolution(maxWidth = 1_920, maxHeight = 1_080),
            ),
        )
    }
}
