// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class VlcSlaveSubtitleUrlTest {
    @Test
    fun subripPathIsRewrittenToSrtForLibVlcSlaveSniffing() {
        assertEquals(
            "https://jellyfin.example/Videos/item/source/Subtitles/3/0/Stream.srt",
            vlcSlaveSubtitleUrl("https://jellyfin.example/Videos/item/source/Subtitles/3/0/Stream.subrip"),
        )
    }

    @Test
    fun rewriteKeepsEveryQueryParameterIncludingCredentials() {
        assertEquals(
            "https://jellyfin.example/Videos/item/Subtitles/3/0/Stream.srt?api_key=token&foo=bar",
            vlcSlaveSubtitleUrl("https://jellyfin.example/Videos/item/Subtitles/3/0/Stream.subrip?api_key=token&foo=bar"),
        )
    }

    @Test
    fun extensionMatchIsCaseInsensitiveAndOnlyAppliesToThePathSuffix() {
        assertEquals(
            "https://jellyfin.example/Videos/item/Stream.srt",
            vlcSlaveSubtitleUrl("https://jellyfin.example/Videos/item/Stream.SUBRIP"),
        )
        // A ".subrip" appearing only in the query is not a delivery extension.
        val queryOnly = "https://jellyfin.example/Videos/item/Stream.vtt?name=Stream.subrip"
        assertEquals(queryOnly, vlcSlaveSubtitleUrl(queryOnly))
    }

    @Test
    fun urlsVlcAlreadyRecognizesAreReturnedUnchanged() {
        listOf(
            "https://jellyfin.example/Videos/item/Subtitles/3/0/Stream.srt?api_key=token",
            "https://jellyfin.example/Videos/item/Subtitles/3/0/Stream.vtt",
            "https://jellyfin.example/Videos/item/Subtitles/3/0/Stream.ass",
            "file:///var/mobile/Containers/subtitles/asset.vtt",
            "https://jellyfin.example/Videos/item/master.m3u8?SubtitleMethod=Encode",
        ).forEach { url -> assertEquals(url, vlcSlaveSubtitleUrl(url)) }
    }
}
