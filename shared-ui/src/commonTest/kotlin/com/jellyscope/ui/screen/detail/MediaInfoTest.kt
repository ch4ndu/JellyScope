// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaInfoTest {
    @Test
    fun nullVersionAndNullSizeReturnsNull() {
        assertNull(buildMediaInfo(version = null, sizeBytes = null))
    }

    @Test
    fun buildsMediaInfoFromStreamFieldsAndSize() {
        val info =
            buildMediaInfo(
                version =
                    MediaVersion(
                        id = "source-1",
                        name = "1080p",
                        mediaStreams =
                            listOf(
                                stream(
                                    type = "Video",
                                    codec = "h264",
                                    bitRate = 8_000_000,
                                    height = 1080,
                                ),
                                stream(
                                    type = "Audio",
                                    language = "English",
                                    codec = "aac",
                                    channelLayout = "5.1",
                                ),
                                stream(
                                    type = "Subtitle",
                                    language = "English",
                                    codec = "srt",
                                    isExternal = true,
                                ),
                            ),
                    ),
                sizeBytes = 4_509_715_660,
            )

        assertEquals("4.2 GB", info?.fileLine)
        assertEquals(listOf("1080p · H264 · 8.0 Mbps"), info?.videoLines)
        assertEquals(listOf("English · AAC · 5.1"), info?.audioLines)
        assertEquals(listOf("English · SRT · External"), info?.subtitleLines)
    }

    @Test
    fun usesDisplayTitlesVerbatimWhenPresent() {
        val info =
            buildMediaInfo(
                version =
                    MediaVersion(
                        id = "source-1",
                        name = "Default",
                        mediaStreams =
                            listOf(
                                stream(
                                    type = "Video",
                                    displayTitle = "4K HEVC HDR",
                                ),
                                stream(
                                    type = "Audio",
                                    displayTitle = "English TrueHD 7.1",
                                ),
                                stream(
                                    type = "Subtitle",
                                    displayTitle = "English PGS",
                                ),
                            ),
                    ),
                sizeBytes = null,
            )

        assertEquals(listOf("4K HEVC HDR"), info?.videoLines)
        assertEquals(listOf("English TrueHD 7.1"), info?.audioLines)
        assertEquals(listOf("English PGS"), info?.subtitleLines)
    }
}

private fun stream(
    type: String,
    displayTitle: String? = null,
    language: String? = null,
    codec: String? = null,
    channelLayout: String? = null,
    bitRate: Long? = null,
    height: Int? = null,
    isExternal: Boolean? = null,
) = PlaybackMediaStream(
    index = null,
    type = type,
    displayTitle = displayTitle,
    title = null,
    language = language,
    codec = codec,
    channelLayout = channelLayout,
    bitRate = bitRate,
    height = height,
    isDefault = null,
    isExternal = isExternal,
    deliveryMethod = null,
    deliveryUrl = null,
)
