// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackInfoDtoTest {
    @Test
    fun resolvesEncodedTranscodeReasonsFromUrlWhenResponseListIsEmpty() {
        val source =
            PlaybackInfoMediaSourceDto(
                transcodingUrl =
                    "https://jellyfin.example/Videos/item/master.m3u8?" +
                        "VideoCodec=h264&TranscodeReasons=" +
                        "VideoCodecNotSupported%2C%20SubtitleCodecNotSupported",
            )

        assertEquals(
            listOf("VideoCodecNotSupported", "SubtitleCodecNotSupported"),
            source.resolvedTranscodeReasons(),
        )
    }

    @Test
    fun responseTranscodeReasonsTakePrecedenceOverUrlFallback() {
        val source =
            PlaybackInfoMediaSourceDto(
                transcodingUrl = "/Videos/item/master.m3u8?TranscodeReasons=VideoCodecNotSupported",
                transcodeReasons = listOf("ContainerNotSupported"),
            )

        assertEquals(listOf("ContainerNotSupported"), source.resolvedTranscodeReasons())
    }

    @Test
    fun malformedTranscodeReasonQueryDoesNotFailPlaybackMapping() {
        val source =
            PlaybackInfoMediaSourceDto(
                transcodingUrl = "/Videos/item/master.m3u8?TranscodeReasons=%",
            )

        assertEquals(emptyList(), source.resolvedTranscodeReasons())
    }

    @Test
    fun serializesPlaybackInfoDeviceProfileWithExplicitFields() {
        val json =
            Json {
                explicitNulls = false
            }.encodeToString(
                PlaybackInfoRequestDto(
                    userId = "user-1",
                    startTimeTicks = 12_345_000L,
                    mediaSourceId = "source-1",
                    autoOpenLiveStream = false,
                    deviceProfile =
                        PlaybackDeviceProfileDto(
                            directPlayProfiles =
                                listOf(
                                    DirectPlayProfileDto(
                                        type = "Video",
                                        container = "mp4,m4v,mkv,webm",
                                        videoCodec = "h264",
                                        audioCodec = "aac,mp3",
                                    ),
                                    DirectPlayProfileDto(
                                        type = "Audio",
                                        container = "mp3,aac,flac,ogg",
                                        videoCodec = null,
                                        audioCodec = "aac,mp3",
                                    ),
                                ),
                            transcodingProfiles =
                                listOf(
                                    TranscodingProfileDto(
                                        container = "ts",
                                        type = "Video",
                                        protocol = "hls",
                                        videoCodec = "h264",
                                        audioCodec = "aac",
                                        context = "Streaming",
                                        enableSubtitlesInManifest = true,
                                    ),
                                ),
                            codecProfiles =
                                listOf(
                                    CodecProfileDto(
                                        type = "Video",
                                        codec = "hevc",
                                        conditions =
                                            listOf(
                                                ProfileConditionDto(
                                                    condition = "EqualsAny",
                                                    property = "VideoRangeType",
                                                    value = "SDR|DOVIWithSDR",
                                                    isRequired = true,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    maxStreamingBitrate = 20_000_000,
                    maxAudioChannels = 6,
                    enableDirectPlay = true,
                    enableDirectStream = false,
                    enableTranscoding = true,
                    allowAudioStreamCopy = false,
                    allowVideoStreamCopy = true,
                    audioStreamIndex = 1,
                    subtitleStreamIndex = 2,
                ),
            )

        assertTrue(json.contains(""""UserId":"user-1""""))
        assertTrue(json.contains(""""AutoOpenLiveStream":false"""))
        assertTrue(json.contains(""""MaxStreamingBitrate":20000000"""))
        assertTrue(json.contains(""""MaxAudioChannels":6"""))
        assertTrue(json.contains(""""EnableDirectPlay":true"""))
        assertTrue(json.contains(""""EnableDirectStream":false"""))
        assertTrue(json.contains(""""EnableTranscoding":true"""))
        assertTrue(json.contains(""""AllowAudioStreamCopy":false"""))
        assertTrue(json.contains(""""AllowVideoStreamCopy":true"""))
        assertTrue(json.contains(""""AudioStreamIndex":1"""))
        assertTrue(json.contains(""""SubtitleStreamIndex":2"""))
        assertTrue(json.contains(""""DirectPlayProfiles""""))
        assertTrue(json.contains(""""Container":"mp4,m4v,mkv,webm""""))
        assertTrue(json.contains(""""VideoCodec":"h264""""))
        assertTrue(json.contains(""""AudioCodec":"aac,mp3""""))
        assertTrue(json.contains(""""Container":"mp3,aac,flac,ogg""""))
        assertTrue(json.contains(""""TranscodingProfiles""""))
        assertTrue(json.contains(""""Protocol":"hls""""))
        assertTrue(json.contains(""""Context":"Streaming""""))
        assertTrue(json.contains(""""EnableSubtitlesInManifest":true"""))
        assertTrue(json.contains(""""CodecProfiles""""))
        assertTrue(json.contains(""""Codec":"hevc""""))
        assertTrue(json.contains(""""Conditions""""))
        assertTrue(json.contains(""""Condition":"EqualsAny""""))
        assertTrue(json.contains(""""Property":"VideoRangeType""""))
        assertTrue(json.contains(""""Value":"SDR|DOVIWithSDR""""))
        assertTrue(json.contains(""""IsRequired":true"""))
    }
}
