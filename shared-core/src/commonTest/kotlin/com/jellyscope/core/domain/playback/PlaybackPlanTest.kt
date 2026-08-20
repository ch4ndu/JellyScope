// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackPlanTest {
    @Test
    fun playbackPlanStoresMinimalDirectPlayFields() {
        val policy =
            ProgressReportingPolicy(
                reportIntervalMs = 10_000,
            )

        val plan =
            PlaybackPlan(
                itemId = "item-1",
                mediaSourceId = "media-source-1",
                startPositionMs = 12_345,
                streamMode = StreamMode.DirectPlay,
                streamUrl = "https://jellyfin.example/videos/item-1",
                progressReportingPolicy = policy,
            )

        assertEquals("item-1", plan.itemId)
        assertEquals("media-source-1", plan.mediaSourceId)
        assertEquals(12_345, plan.startPositionMs)
        assertEquals(StreamMode.DirectPlay, plan.streamMode)
        assertEquals("https://jellyfin.example/videos/item-1", plan.streamUrl)
        assertEquals(policy, plan.progressReportingPolicy)
        assertEquals(null, plan.playSessionId)
    }

    @Test
    fun streamModeIncludesExpectedValues() {
        assertEquals(
            listOf(
                StreamMode.DirectPlay,
                StreamMode.DirectStream,
                StreamMode.Transcode,
                StreamMode.Offline,
            ),
            StreamMode.entries.toList(),
        )
    }

    @Test
    fun directPlayPlannerBuildsTokenFreeStreamUrlAndPolicy() {
        val plan =
            DirectPlayPlanner().plan(
                session =
                    com.jellyscope.core.domain.model.Session(
                        serverUrl = "https://jellyfin.example/",
                        serverId = "server-1",
                        serverName = "Home Jellyfin",
                        userId = "user-1",
                        userName = "Demo User",
                        accessToken = "secret-token",
                        deviceId = "device-1",
                    ),
                itemId = "item-1",
                mediaSourceId = "media-source-1",
                startPositionTicks = 123_450_000L,
            )

        assertEquals("item-1", plan.itemId)
        assertEquals("media-source-1", plan.mediaSourceId)
        assertEquals(12_345L, plan.startPositionMs)
        assertEquals(StreamMode.DirectPlay, plan.streamMode)
        assertEquals(
            "https://jellyfin.example/Videos/item-1/stream" +
                "?static=true&mediaSourceId=media-source-1&deviceId=device-1",
            plan.streamUrl,
        )
        assertEquals(10_000L, plan.progressReportingPolicy.reportIntervalMs)
        assertEquals(false, plan.streamUrl.contains("secret-token"))
    }

    @Test
    fun directPlayPlannerNormalizesServerUrlWhitespaceAndTrailingSlash() {
        val plan =
            DirectPlayPlanner().plan(
                session =
                    com.jellyscope.core.domain.model.Session(
                        serverUrl = " https://jellyfin.example/base/ ",
                        serverId = "server-1",
                        serverName = "Home Jellyfin",
                        userId = "user-1",
                        userName = "Demo User",
                        accessToken = "secret-token",
                        deviceId = "device-1",
                    ),
                itemId = "item-1",
                mediaSourceId = "media-source-1",
                startPositionTicks = 0L,
            )

        assertEquals(
            "https://jellyfin.example/base/Videos/item-1/stream" +
                "?static=true&mediaSourceId=media-source-1&deviceId=device-1",
            plan.streamUrl,
        )
    }

    @Test
    fun ticksConversionUsesSingleConstant() {
        assertEquals(10_000L, JELLYFIN_TICKS_PER_MILLISECOND)
        assertEquals(12_345L, ticksToMilliseconds(123_450_000L))
        assertEquals(123_450_000L, millisecondsToTicks(12_345L))
    }

    @Test
    fun mediaSegmentsResolveCurrentPosition() {
        val segments =
            listOf(
                MediaSegment(MediaSegmentType.Intro, startTicks = 10_000_000L, endTicks = 30_000_000L),
                MediaSegment(MediaSegmentType.Outro, startTicks = 50_000_000L, endTicks = 70_000_000L),
            )

        assertEquals(MediaSegmentType.Intro, segments.currentSegment(positionMs = 1_500L)?.type)
        assertEquals(null, segments.currentSegment(positionMs = 3_000L))
        assertEquals(MediaSegmentType.Outro, segments.currentSegment(positionMs = 5_000L)?.type)
    }

    @Test
    fun trickplayCalculatesTileIndexAndUrl() {
        val trickplay =
            TrickplayInfo(
                resolutionKey = "320",
                width = 320,
                height = 180,
                tileWidth = 10,
                tileHeight = 10,
                thumbnailWidth = 320,
                thumbnailHeight = 180,
                thumbnailCount = 250,
                intervalMs = 10_000L,
            )

        assertEquals(3, trickplay.tileCount)
        assertEquals(0, trickplay.tileIndexForPositionMs(990_000L))
        assertEquals(1, trickplay.tileIndexForPositionMs(1_000_000L))
        assertEquals(2, trickplay.tileIndexForPositionMs(9_999_999L))
        assertEquals(
            "https://jellyfin.example/Videos/item-1/Trickplay/320/2.jpg",
            JellyfinImageUrlBuilder().trickplayTileUrl(
                serverUrl = "https://jellyfin.example/",
                itemId = "item-1",
                width = trickplay.width,
                index = trickplay.tileIndexForPositionMs(9_999_999L),
            ),
        )
    }

    @Test
    fun playbackInfoDecisionKeepsDirectPlayWhenServerSupportsIt() {
        val plan =
            decidePlan(
                directPlan =
                    directPlan.copy(
                        maxStreamingBitrate = 8_000_000L,
                        qualityCapOrigin = PlaybackQualityCapOrigin.SettingsDefault,
                    ),
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = true,
                                    transcodingUrl = "/Videos/item-1/master.m3u8",
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
            )

        assertEquals(StreamMode.DirectPlay, plan.streamMode)
        assertEquals(directPlan.streamUrl, plan.streamUrl)
        assertEquals("play-session-1", plan.playSessionId)
        assertEquals(8_000_000L, plan.maxStreamingBitrate)
        assertEquals(PlaybackQualityCapOrigin.SettingsDefault, plan.qualityCapOrigin)
        assertNull(plan.effectiveTranscodeMaxStreamingBitrate)
    }

    @Test
    fun playbackInfoDecisionPopulatesVideoPresentationFromResponseVideoStream() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = true,
                                    transcodingUrl = null,
                                    streams =
                                        listOf(
                                            PlaybackMediaStream(
                                                index = 0,
                                                type = "Video",
                                                displayTitle = null,
                                                title = null,
                                                language = null,
                                                codec = "hevc",
                                                channelLayout = null,
                                                bitRate = null,
                                                height = 2160,
                                                isDefault = null,
                                                isExternal = null,
                                                deliveryMethod = null,
                                                deliveryUrl = null,
                                                width = 3840,
                                                realFrameRate = 23.976,
                                                videoRangeType = "HDR10",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
            )

        assertEquals(
            PlannedVideoPresentation(
                width = 3840,
                height = 2160,
                frameRate = 23.976,
                videoRangeType = "HDR10",
            ),
            plan.videoPresentation,
        )
    }

    @Test
    fun directPlayPlannerUsesDetailStreamPresentationWhenPlaybackInfoIsUnavailable() {
        val plan =
            DirectPlayPlanner().plan(
                session =
                    com.jellyscope.core.domain.model.Session(
                        serverUrl = "https://jellyfin.example",
                        serverId = "server-1",
                        serverName = "Home Jellyfin",
                        userId = "user-1",
                        userName = "Demo User",
                        accessToken = "secret-token",
                        deviceId = "device-1",
                    ),
                itemId = "item-1",
                mediaSourceId = "media-source-1",
                startPositionTicks = 0L,
                detailMediaStreams =
                    listOf(
                        PlaybackMediaStream(
                            index = 0,
                            type = "Video",
                            displayTitle = null,
                            title = null,
                            language = null,
                            codec = "h264",
                            channelLayout = null,
                            bitRate = null,
                            height = 1080,
                            isDefault = null,
                            isExternal = null,
                            deliveryMethod = null,
                            deliveryUrl = null,
                            width = 1920,
                            realFrameRate = 24.0,
                            videoRangeType = "SDR",
                        ),
                    ),
            )

        assertEquals(
            PlannedVideoPresentation(1920, 1080, 24.0, "SDR"),
            plan.videoPresentation,
        )
    }

    @Test
    fun playbackInfoDecisionUsesTranscodeUrlWhenDirectPlayIsUnsupported() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = false,
                                    transcodingUrl = "/Videos/item-1/master.m3u8?MediaSourceId=source-1",
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
            )

        assertEquals(StreamMode.Transcode, plan.streamMode)
        assertEquals("https://jellyfin.example/Videos/item-1/master.m3u8?MediaSourceId=source-1", plan.streamUrl)
        assertEquals("play-session-1", plan.playSessionId)
    }

    @Test
    fun playbackInfoDecisionStripsServerAttachedTranscodeSubtitleAndKeepsOffPlanState() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = false,
                                    transcodingUrl =
                                        "/Videos/item-1/master.m3u8?MediaSourceId=source-1" +
                                            "&SubtitleStreamIndex=4&SubtitleMethod=Encode&ApiKey=secret",
                                    defaultSubtitleStreamIndex = 4,
                                    streams = listOf(subtitleStream(4, "Encode", "pgs")),
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
                subtitleSelection = SubtitleSelectionIntent.Off,
            )

        assertEquals(StreamMode.Transcode, plan.streamMode)
        assertEquals(
            "https://jellyfin.example/Videos/item-1/master.m3u8?MediaSourceId=source-1&ApiKey=secret",
            plan.streamUrl,
        )
        assertNull(plan.selectedSubtitleStreamIndex)
        assertEquals(PlannedSubtitle.Off, plan.plannedSubtitle)
    }

    @Test
    fun playbackInfoDecisionKeepsAbsoluteTranscodeUrl() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = null,
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = false,
                                    transcodingUrl = "https://cdn.example/item-1/master.m3u8",
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
            )

        assertEquals(StreamMode.Transcode, plan.streamMode)
        assertEquals("https://cdn.example/item-1/master.m3u8", plan.streamUrl)
    }

    @Test
    fun playbackInfoDecisionBuildsDirectStreamUrlFromResponseSelections() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = false,
                                    supportsDirectStream = true,
                                    supportsTranscoding = true,
                                    transcodingUrl = "/Videos/item-1/master.m3u8",
                                    transcodingContainer = "mp4,ts",
                                    defaultAudioStreamIndex = 3,
                                    defaultSubtitleStreamIndex = 4,
                                    streams =
                                        listOf(
                                            subtitleStream(index = 4, deliveryMethod = "Embed", codec = "srt"),
                                        ),
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
                subtitleSelection = SubtitleSelectionIntent.Track(4),
            )

        assertEquals(StreamMode.DirectStream, plan.streamMode)
        assertEquals("video/mp4", plan.streamMimeType)
        assertEquals(3, plan.selectedAudioStreamIndex)
        assertEquals(4, plan.selectedSubtitleStreamIndex)
        assertEquals(SubtitleDeliveryMethod.Embed, (plan.plannedSubtitle as PlannedSubtitle.Track).deliveryMethod)
        assertEquals(
            "https://jellyfin.example/Videos/item-1/stream.mp4?mediaSourceId=media-source-1&deviceId=device-1&playSessionId=play-session-1&audioStreamIndex=3&subtitleStreamIndex=4&subtitleMethod=Embed",
            plan.streamUrl,
        )
    }

    @Test
    fun responseDescriptorsUseFilteredEmbeddedOrdering() {
        val responseStreams =
            listOf(
                mediaStream(0, "Video"),
                mediaStream(8, "Audio", codec = "aac", language = "eng"),
                mediaStream(20, "Subtitle", deliveryMethod = "External"),
                mediaStream(3, "Audio", codec = "ac3", language = "spa"),
                mediaStream(21, "Subtitle", deliveryMethod = "Encode"),
                mediaStream(22, "Subtitle", deliveryMethod = "Embed", codec = "srt", language = "eng"),
                mediaStream(23, "Subtitle", deliveryMethod = "Hls", codec = "vtt", language = "spa"),
            )
        val plan =
            decidePlan(
                directPlan =
                    directPlan.copy(
                        embeddedAudioTracks = listOf(PlannedEmbeddedTrack(99, 0, null, null, null)),
                    ),
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "session",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = true,
                                    transcodingUrl = null,
                                    defaultAudioStreamIndex = 3,
                                    defaultSubtitleStreamIndex = 23,
                                    streams = responseStreams,
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
                subtitleSelection = SubtitleSelectionIntent.Track(23),
            )

        assertEquals(listOf(8 to 0, 3 to 1), plan.embeddedAudioTracks.map { it.jellyfinStreamIndex to it.filteredContainerOrdinal })
        assertEquals(listOf(2, 2), plan.embeddedAudioTracks.map { it.responseAuthoritativeCohortSize })
        val subtitle = plan.plannedSubtitle as PlannedSubtitle.Track
        assertEquals(23, subtitle.embeddedTrack?.jellyfinStreamIndex)
        assertEquals(1, subtitle.embeddedTrack?.filteredContainerOrdinal)
        assertEquals(2, subtitle.embeddedTrack?.responseAuthoritativeCohortSize)
    }

    @Test
    fun malformedDirectStreamFallsThroughToValidHlsTranscode() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = false,
                                    supportsDirectStream = true,
                                    supportsTranscoding = true,
                                    transcodingUrl = "/Videos/item-1/master.m3u8",
                                    transcodingContainer = "../mp4",
                                    transcodingSubProtocol = "hls",
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
            )

        assertEquals(StreamMode.Transcode, plan.streamMode)
        assertEquals("application/x-mpegURL", plan.streamMimeType)
    }

    @Test
    fun unknownExternalSubtitleMimeIsUnavailableAndEligibleForEncodeFallback() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = true,
                                    transcodingUrl = null,
                                    defaultSubtitleStreamIndex = 4,
                                    streams =
                                        listOf(
                                            subtitleStream(4, "External", "mystery").copy(
                                                isExternal = true,
                                                deliveryUrl = "/Videos/item/subtitles/4.mystery",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
                subtitleSelection = SubtitleSelectionIntent.Track(4),
            )

        val unavailable = plan.plannedSubtitle as PlannedSubtitle.Unavailable
        assertEquals(true, unavailable.allowEncodeFallback)
        assertEquals("mystery", unavailable.normalizedFormat)
    }

    @Test
    fun missingResponseSubtitleUsesExactDetailFormatForOneEncodeFallback() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = true,
                                    transcodingUrl = null,
                                    defaultSubtitleStreamIndex = 4,
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
                subtitleSelection = SubtitleSelectionIntent.Track(4),
                detailMediaStreams = listOf(subtitleStream(4, "Embed", "application/x-subrip")),
            )

        val unavailable = plan.plannedSubtitle as PlannedSubtitle.Unavailable
        assertEquals(4, unavailable.streamIndex)
        assertEquals("srt", unavailable.normalizedFormat)
        assertEquals(SubtitleKind.Text, unavailable.kind)
        assertTrue(unavailable.allowEncodeFallback)
    }

    @Test
    fun missingResponseAndDetailSubtitleMetadataDisablesEncodeFallback() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = true,
                                    transcodingUrl = null,
                                    defaultSubtitleStreamIndex = 4,
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
                subtitleSelection = SubtitleSelectionIntent.Track(4),
            )

        val unavailable = plan.plannedSubtitle as PlannedSubtitle.Unavailable
        assertNull(unavailable.normalizedFormat)
        assertFalse(unavailable.allowEncodeFallback)
    }

    @Test
    fun transcodeResolutionCapReplacesServerProvidedTargetThatExceedsTheRung() {
        // The server's own Width/Height choice must not bypass a selected rung:
        // a 1080p target under a 480p rung shrinks to the rung, aspect preserved.
        val url = "http://server/videos/item/main.m3u8?VideoCodec=h264&Width=1920&Height=1080"
        val capped =
            transcodeResolutionCap(
                transcodingUrl = url,
                sourceWidth = 3_840,
                sourceHeight = 2_160,
                videoResolutionsByCodec = emptyMap(),
                qualityRungCeiling = VideoCodecResolution(maxWidth = 854, maxHeight = 480),
            )
        checkNotNull(capped)
        // 480 * 1920/1080 = 853.3, even-rounded to 852: the cap preserves the
        // SERVER target's aspect, bounded by the rung height.
        assertTrue(capped.contains("Width=852"))
        assertTrue(capped.contains("Height=480"))
        assertFalse(capped.contains("Width=1920"))
        assertFalse(capped.contains("Height=1080"))
    }

    @Test
    fun transcodeResolutionCapPinsMaxFramerateEvenWhenTheBoxAlreadyFits() {
        // The recovery path's MaxFramerate pin rides this rewrite because
        // PlaybackInfoDto has no such member; it must attach even when no
        // dimension needs capping, and must not duplicate an existing pin.
        val url = "http://server/videos/item/main.m3u8?VideoCodec=h264"
        val pinned =
            transcodeResolutionCap(
                transcodingUrl = url,
                sourceWidth = 1_280,
                sourceHeight = 720,
                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(maxWidth = 1_920, maxHeight = 1_080)),
                maxFramerate = 30,
            )
        checkNotNull(pinned)
        assertTrue(pinned.contains("MaxFramerate=30"))
        assertFalse(pinned.contains("Width="))

        // An existing pin at or below the requested ceiling is left alone...
        val alreadyTighter =
            transcodeResolutionCap(
                transcodingUrl = "$url&MaxFramerate=24",
                sourceWidth = 1_280,
                sourceHeight = 720,
                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(maxWidth = 1_920, maxHeight = 1_080)),
                maxFramerate = 30,
            )
        assertEquals(null, alreadyTighter)

        // ...but an existing HIGHER value is a bypass and must be tightened.
        val loosenedByServer =
            transcodeResolutionCap(
                transcodingUrl = "$url&MaxFramerate=60",
                sourceWidth = 1_280,
                sourceHeight = 720,
                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(maxWidth = 1_920, maxHeight = 1_080)),
                maxFramerate = 30,
            )
        checkNotNull(loosenedByServer)
        assertTrue(loosenedByServer.contains("MaxFramerate=30"))
        assertFalse(loosenedByServer.contains("MaxFramerate=60"))

        // A source with no dimensions is an accepted forced-transcode path and
        // must still carry its rate ceiling.
        val dimensionless =
            transcodeResolutionCap(
                transcodingUrl = url,
                sourceWidth = null,
                sourceHeight = null,
                videoResolutionsByCodec = emptyMap(),
                maxFramerate = 30,
            )
        checkNotNull(dimensionless)
        assertTrue(dimensionless.contains("MaxFramerate=30"))
    }

    @Test
    fun transcodeResolutionCapFitsPortraitSourceIntoDecoderBox() {
        // Mirrors the verified Fire TV failure: a 2160x3502 vertical source
        // transcoded for a decoder that tops out at 3840x2160 must be scaled
        // by height, not width.
        val capped =
            transcodeResolutionCap(
                transcodingUrl = "https://jellyfin.example/videos/item-1/master.m3u8?VideoCodec=h264&MaxWidth=3840&MaxHeight=2160",
                sourceWidth = 2160,
                sourceHeight = 3502,
                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(maxWidth = 3840, maxHeight = 2160)),
            )

        // MaxWidth/MaxHeight must be replaced (not just supplemented): the
        // server's bitrate-driven resolution normalizer overrides explicit
        // Width/Height but honors a MaxWidth already below its width tier.
        assertEquals(
            "https://jellyfin.example/videos/item-1/master.m3u8?VideoCodec=h264" +
                "&MaxWidth=1332&MaxHeight=2160&Width=1332&Height=2160",
            capped,
        )
    }

    @Test
    fun transcodeResolutionCapFitsOversizedLandscapeSource() {
        val capped =
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264",
                sourceWidth = 7680,
                sourceHeight = 4320,
                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(maxWidth = 3840, maxHeight = 2160)),
            )

        assertEquals(
            "/videos/item-1/master.m3u8?VideoCodec=h264&MaxWidth=3840&MaxHeight=2160&Width=3840&Height=2160",
            capped,
        )
    }

    // Device-verified against a Chromecast with Google TV (2026-07-25). Its
    // Amlogic AVC decoder declares `size max 4096x4096` but `block-count 34560`
    // (4096x2160) and `blocks-per-second 1036800` (30fps at that box). Capping a
    // 60fps 8K source on the box alone yielded 4096x2304, and the server encoded
    // AVC level 6.0, which neither the hardware nor the software decoder would
    // configure — playback died with BAD_VALUE while CONFIGURING.
    private val amlogicAvc =
        VideoCodecResolution(
            maxWidth = 4_096,
            maxHeight = 2_160,
            maxFrameArea = 4_096L * 2_160L,
            maxFrameAreaPerSecond = 4_096L * 2_160L * 30L,
        )

    @Test
    fun transcodeResolutionCapHonoursThroughputBudgetAtHighFrameRate() {
        val capped =
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264",
                sourceWidth = 7_680,
                sourceHeight = 4_320,
                videoResolutionsByCodec = mapOf("h264" to amlogicAvc),
                sourceFrameRate = 60.0,
            )

        // 60fps halves the usable area, so the result must be well under the
        // 4096x2160 box that the same decoder allows at 30fps.
        val width = capped!!.substringAfter("&Width=").substringBefore('&').toInt()
        val height = capped.substringAfter("&Height=").toInt()
        assertTrue(blockPaddedArea(width, height) * 60L <= amlogicAvc.maxFrameAreaPerSecond!!)
        assertTrue(width < 4_096, "expected a width below the 30fps box, got $width")
        // Aspect ratio must survive the shrink (16:9 within a pixel of rounding).
        assertTrue(kotlin.math.abs(width.toDouble() / height - 16.0 / 9.0) < 0.02)
    }

    @Test
    fun transcodeResolutionCapUsesFullBoxWhenFrameRateLeavesHeadroom() {
        val capped =
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264",
                sourceWidth = 7_680,
                sourceHeight = 4_320,
                videoResolutionsByCodec = mapOf("h264" to amlogicAvc),
                sourceFrameRate = 24.0,
            )

        // At 24fps the throughput budget is not the binding limit, so the box is.
        assertEquals(
            "/videos/item-1/master.m3u8?VideoCodec=h264&MaxWidth=3840&MaxHeight=2160&Width=3840&Height=2160",
            capped,
        )
    }

    @Test
    fun transcodeResolutionCapCapsInBoxSourceThatExceedsThroughput() {
        // 4096x2160 is inside the decoder's box yet needs 60fps of throughput it
        // does not have, so a source that never trips the box must still be cut.
        val capped =
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264",
                sourceWidth = 4_096,
                sourceHeight = 2_160,
                videoResolutionsByCodec = mapOf("h264" to amlogicAvc),
                sourceFrameRate = 60.0,
            )

        assertNotNull(capped)
        val width = capped!!.substringAfter("&Width=").substringBefore('&').toInt()
        assertTrue(width < 4_096)
    }

    @Test
    fun transcodeResolutionCapLeavesAnExactlyFittingSourceAlone() {
        // Regression: 1080 is not 16-aligned, so it pads to 1088. A budget derived
        // from a raw 1920x1080 ceiling would charge the padded area against the
        // unpadded budget and cap ordinary 1080p on any 1080p-ceiling decoder.
        // The budget must therefore be block-padded on the capability side too.
        val fhd =
            VideoCodecResolution(
                maxWidth = 1_920,
                maxHeight = 1_080,
                maxFrameArea = blockPaddedArea(1_920, 1_080),
                maxFrameAreaPerSecond = blockPaddedArea(1_920, 1_080) * 60L,
            )

        assertNull(
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264",
                sourceWidth = 1_920,
                sourceHeight = 1_080,
                videoResolutionsByCodec = mapOf("h264" to fhd),
                sourceFrameRate = 60.0,
            ),
        )
    }

    @Test
    fun transcodeResolutionCapStopsAtTheFloorForAnAbsurdBudget() {
        // A pathological budget must still yield a usable capped size rather than
        // driving a dimension to zero and dropping the cap altogether.
        val starved =
            VideoCodecResolution(
                maxWidth = 4_096,
                maxHeight = 2_160,
                maxFrameArea = 1_024L,
                maxFrameAreaPerSecond = 1_024L,
            )

        val capped =
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264",
                sourceWidth = 3_840,
                sourceHeight = 2_160,
                videoResolutionsByCodec = mapOf("h264" to starved),
                sourceFrameRate = 60.0,
            )

        assertNotNull(capped)
        val width = capped!!.substringAfter("&Width=").substringBefore('&').toInt()
        val height = capped.substringAfter("&Height=").toInt()
        assertTrue(width > 0 && height > 0, "capped dimensions must stay positive, got ${width}x$height")
    }

    @Test
    fun transcodeResolutionCapIgnoresThroughputWhenFrameRateUnknown() {
        // No frame rate means no rate budget; the box still applies on its own.
        assertNull(
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264",
                sourceWidth = 4_096,
                sourceHeight = 2_160,
                videoResolutionsByCodec = mapOf("h264" to amlogicAvc),
                sourceFrameRate = null,
            ),
        )
    }

    @Test
    fun transcodeResolutionCapUsesTightestBoxAcrossAdvertisedCodecs() {
        val capped =
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264,hevc",
                sourceWidth = 3840,
                sourceHeight = 2160,
                videoResolutionsByCodec =
                    mapOf(
                        "h264" to VideoCodecResolution(maxWidth = 1920, maxHeight = 1080),
                        "hevc" to VideoCodecResolution(maxWidth = 3840, maxHeight = 2160),
                    ),
            )

        assertEquals(
            "/videos/item-1/master.m3u8?VideoCodec=h264,hevc&MaxWidth=1920&MaxHeight=1080&Width=1920&Height=1080",
            capped,
        )
    }

    @Test
    fun qualityRungAndDeviceCeilingsReconcileOnceWithOriginalExempt() {
        val deviceCeiling = VideoCodecResolution(maxWidth = 1_920, maxHeight = 1_080, maxFrameArea = 2_088_960L)
        val rungCeiling = requireNotNull(qualityRungForBitrate(4_000_000L)).resolutionCap

        assertEquals(
            VideoCodecResolution(maxWidth = 1_280, maxHeight = 720, maxFrameArea = 2_088_960L),
            reconcileVideoResolutionBounds(deviceCeiling, rungCeiling),
        )
        assertEquals(deviceCeiling, reconcileVideoResolutionBounds(deviceCeiling, null))
        assertEquals(rungCeiling, reconcileVideoResolutionBounds(null, rungCeiling))
    }

    @Test
    fun transcodeResolutionCapUsesSelectedRungWhenDeviceCeilingIsAbsent() {
        val rungCeiling = requireNotNull(qualityRungForBitrate(4_000_000L)).resolutionCap
        assertEquals(
            "/videos/item-1/master.m3u8?VideoCodec=h264&MaxWidth=1280&MaxHeight=720&Width=1280&Height=720",
            transcodeResolutionCap(
                transcodingUrl = "/videos/item-1/master.m3u8?VideoCodec=h264",
                sourceWidth = 1_920,
                sourceHeight = 1_080,
                videoResolutionsByCodec = emptyMap(),
                qualityRungCeiling = rungCeiling,
            ),
        )
    }

    @Test
    fun transcodeResolutionCapSkipsWhenNotNeededOrUnknown() {
        val h264Box = mapOf("h264" to VideoCodecResolution(maxWidth = 3840, maxHeight = 2160))
        val url = "/videos/item-1/master.m3u8?VideoCodec=h264"

        // Source fits the decoder box.
        assertEquals(null, transcodeResolutionCap(url, 3840, 2160, h264Box))
        // Decoder limits unknown for the target codec.
        assertEquals(null, transcodeResolutionCap(url, 2160, 3502, mapOf("hevc" to VideoCodecResolution(3840, 2160))))
        assertEquals(null, transcodeResolutionCap(url, 2160, 3502, emptyMap()))
        // No target codec advertised in the URL.
        assertEquals(null, transcodeResolutionCap("/videos/item-1/master.m3u8?foo=bar", 2160, 3502, h264Box))
        // Source dimensions unknown.
        assertEquals(null, transcodeResolutionCap(url, null, 3502, h264Box))
        // URL already carries explicit dimensions.
        assertEquals(null, transcodeResolutionCap("$url&Width=1332&Height=2160", 2160, 3502, h264Box))
    }

    @Test
    fun playbackInfoDecisionCapsTranscodeResolutionForUndecodableSources() {
        val plan =
            decidePlan(
                // The copy-disabled policy marks this as the recovery request: a
                // copy-refused source may only accept a transcode whose request
                // provably forced the video re-encode.
                requestPolicy = PlaybackInfoRequestPolicy(allowVideoStreamCopy = false),
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = false,
                                    transcodingUrl = "/Videos/item-1/master.m3u8?VideoCodec=h264&MediaSourceId=source-1",
                                    streams = listOf(videoStream(width = 2160, height = 3502)),
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
                deviceCapabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(maxWidth = 3840, maxHeight = 2160)),
                    ),
            )

        assertEquals(StreamMode.Transcode, plan.streamMode)
        assertEquals(
            "https://jellyfin.example/Videos/item-1/master.m3u8?VideoCodec=h264&MediaSourceId=source-1" +
                "&MaxWidth=1332&MaxHeight=2160&Width=1332&Height=2160",
            plan.streamUrl,
        )
    }

    @Test
    fun playbackInfoDecisionLeavesTranscodeUrlUnchangedWithoutCapabilities() {
        val plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = false,
                                    transcodingUrl = "/Videos/item-1/master.m3u8?VideoCodec=h264&MediaSourceId=source-1",
                                    streams = listOf(videoStream(width = 2160, height = 3502)),
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
            )

        assertEquals(
            "https://jellyfin.example/Videos/item-1/master.m3u8?VideoCodec=h264&MediaSourceId=source-1",
            plan.streamUrl,
        )
    }

    @Test
    fun playbackInfoDecisionFailsWhenServerReturnsNoUsableStream() {
        assertFailsWith<PlaybackPlanningException.NoSupportedStream> {
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        playSessionId = "play-session-1",
                        mediaSources =
                            listOf(
                                playbackMediaSource(
                                    supportsDirectPlay = false,
                                    transcodingUrl = null,
                                ),
                            ),
                    ),
                serverUrl = "https://jellyfin.example",
            )
        }
    }

    @Test
    fun cubeLikePortraitVp9RejectsSourceCopyAndCompatibilityTranscodesWithoutQualityCap() {
        listOf(PlaybackQualityPolicy.Auto, PlaybackQualityPolicy.Original).forEach { qualityPolicy ->
            val source =
                playbackMediaSource(
                    supportsDirectPlay = true,
                    transcodingUrl = "/Videos/item-1/master.m3u8?VideoCodec=vp9",
                    streams = listOf(videoStream(2_160, 3_840, frameRate = 60.0)),
                )
            val capabilities = cubeLikeLibVlcCapabilities()

            assertFailsWith<PlaybackPlanningException.SourceVideoCopyRejected> {
                decidePlan(
                    directPlan =
                        directPlan.copy(
                            qualityPolicy = qualityPolicy,
                            clientTrigger = PlaybackClientTrigger.DecodeCapabilityCap,
                            recoveryIntent = PlaybackRecoveryIntent.Compatibility,
                        ),
                    playbackInfo = PlaybackInfo("session", listOf(source)),
                    serverUrl = "https://jellyfin.example",
                    deviceCapabilities = capabilities,
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )
            }

            val plan =
                decidePlan(
                    directPlan =
                        directPlan.copy(
                            qualityPolicy = qualityPolicy,
                            clientTrigger = PlaybackClientTrigger.DecodeCapabilityCap,
                            recoveryIntent = PlaybackRecoveryIntent.Compatibility,
                        ),
                    playbackInfo = PlaybackInfo("session", listOf(source)),
                    serverUrl = "https://jellyfin.example",
                    deviceCapabilities = capabilities,
                    requestPolicy =
                        PlaybackInfoRequestPolicy(
                            allowVideoStreamCopy = false,
                            backend = PlayerBackend.LibVlc,
                            clientTrigger = PlaybackClientTrigger.DecodeCapabilityCap,
                            recoveryIntent = PlaybackRecoveryIntent.Compatibility,
                        ),
                )

            assertEquals(StreamMode.Transcode, plan.streamMode)
            assertEquals(PlaybackResolutionPolicy.VerifiedDeviceCap, plan.resolutionPolicy)
            assertEquals(PlaybackBitrateConstraint.NoClientLimit, plan.bitrateConstraint)
            assertNull(plan.maxStreamingBitrate)
            assertEquals(qualityPolicy, plan.qualityPolicy)
            assertEquals(PlaybackClientTrigger.DecodeCapabilityCap, plan.clientTrigger)
        }
    }

    @Test
    fun inBoundsLandscapeVp9RemainsDirectPlayAndUnprobedAv1RemainsDirectPlay() {
        val source =
            playbackMediaSource(
                supportsDirectPlay = true,
                transcodingUrl = null,
                streams = listOf(videoStream(1_920, 1_080, frameRate = 30.0)),
            )
        val landscapePlan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo = PlaybackInfo("session", listOf(source)),
                serverUrl = "https://jellyfin.example",
                deviceCapabilities = cubeLikeLibVlcCapabilities(),
            )

        assertEquals(StreamMode.DirectPlay, landscapePlan.streamMode)
        assertEquals(PlaybackResolutionPolicy.VerifiedDeviceCap, landscapePlan.resolutionPolicy)

        val av1Plan =
            decidePlan(
                directPlan = directPlan,
                playbackInfo =
                    PlaybackInfo(
                        "session",
                        listOf(
                            playbackMediaSource(
                                supportsDirectPlay = true,
                                transcodingUrl = null,
                                streams = listOf(videoStream(3_840, 2_160, codec = "av1", frameRate = 60.0)),
                            ),
                        ),
                    ),
                serverUrl = "https://jellyfin.example",
                deviceCapabilities = cubeLikeLibVlcCapabilities(),
            )

        assertEquals(StreamMode.DirectPlay, av1Plan.streamMode)
        assertEquals(PlaybackResolutionPolicy.NoCap, av1Plan.resolutionPolicy)
    }

    @Test
    fun fixedVlcDefaultRemainsUserPolicySeparateFromDeviceCapability() {
        val fixedBitrate = 8_000_000L
        val qualityCap = requireNotNull(qualityRungForBitrate(fixedBitrate)).resolutionCap
        val source =
            playbackMediaSource(
                supportsDirectPlay = true,
                transcodingUrl = "/Videos/item-1/master.m3u8?VideoCodec=vp9",
                streams = listOf(videoStream(3_840, 2_160)),
            )

        val plan =
            decidePlan(
                directPlan =
                    directPlan.copy(
                        maxStreamingBitrate = fixedBitrate,
                        qualityCapOrigin = PlaybackQualityCapOrigin.SettingsDefault,
                        qualityPolicy = PlaybackQualityPolicy.fixed(fixedBitrate),
                        bitrateConstraint = PlaybackBitrateConstraint.ExactUserLimit(fixedBitrate),
                    ),
                playbackInfo = PlaybackInfo("session", listOf(source)),
                serverUrl = "https://jellyfin.example",
                deviceCapabilities = cubeLikeLibVlcCapabilities(),
                requestPolicy =
                    PlaybackInfoRequestPolicy(
                        allowVideoStreamCopy = false,
                        backend = PlayerBackend.LibVlc,
                        qualityResolutionCap = qualityCap,
                        qualityCapOrigin = PlaybackQualityCapOrigin.SettingsDefault,
                        bitrateConstraint = PlaybackBitrateConstraint.ExactUserLimit(fixedBitrate),
                        recoveryIntent = PlaybackRecoveryIntent.Quality,
                    ),
            )

        assertEquals(StreamMode.Transcode, plan.streamMode)
        assertEquals(PlaybackResolutionPolicy.VerifiedDeviceAndQualityRung, plan.resolutionPolicy)
        assertEquals(fixedBitrate, plan.maxStreamingBitrate)
        assertEquals(PlaybackBitrateConstraint.ExactUserLimit(fixedBitrate), plan.bitrateConstraint)
        assertEquals(PlaybackQualityPolicy.fixed(fixedBitrate), plan.qualityPolicy)
    }
}

private fun playbackMediaSource(
    supportsDirectPlay: Boolean,
    transcodingUrl: String?,
    supportsDirectStream: Boolean = false,
    supportsTranscoding: Boolean = transcodingUrl != null,
    transcodingContainer: String? = null,
    transcodingSubProtocol: String? = null,
    defaultAudioStreamIndex: Int? = null,
    defaultSubtitleStreamIndex: Int? = null,
    streams: List<PlaybackMediaStream> = emptyList(),
) = PlaybackMediaSourceInfo(
    id = "media-source-1",
    supportsDirectPlay = supportsDirectPlay,
    supportsDirectStream = supportsDirectStream,
    supportsTranscoding = supportsTranscoding,
    transcodingUrl = transcodingUrl,
    container = "mkv",
    transcodingContainer = transcodingContainer,
    transcodingSubProtocol = transcodingSubProtocol,
    defaultAudioStreamIndex = defaultAudioStreamIndex,
    defaultSubtitleStreamIndex = defaultSubtitleStreamIndex,
    bitrate = null,
    mediaStreams = streams,
)

private fun subtitleStream(
    index: Int,
    deliveryMethod: String,
    codec: String,
) = PlaybackMediaStream(index, "Subtitle", "English", null, "eng", codec, null, null, null, false, false, deliveryMethod, null)

private fun mediaStream(
    index: Int,
    type: String,
    codec: String? = null,
    language: String? = null,
    deliveryMethod: String? = null,
) = PlaybackMediaStream(index, type, null, null, language, codec, null, null, null, false, false, deliveryMethod, null)

private fun videoStream(
    width: Int,
    height: Int,
    codec: String = "vp9",
    frameRate: Double = 30.0,
) = PlaybackMediaStream(
    index = 0,
    type = "Video",
    displayTitle = null,
    title = null,
    language = null,
    codec = codec,
    channelLayout = null,
    bitRate = null,
    height = height,
    isDefault = null,
    isExternal = null,
    deliveryMethod = null,
    deliveryUrl = null,
    width = width,
    realFrameRate = frameRate,
    videoRangeType = "SDR",
)

private val directPlan =
    PlaybackPlan(
        itemId = "item-1",
        mediaSourceId = "media-source-1",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/item-1/stream?static=true&mediaSourceId=media-source-1&deviceId=device-1",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
    )

private fun cubeLikeLibVlcCapabilities() =
    DeviceDecodingCapabilities(
        videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
        audioCodecs = listOf("aac"),
        supportsDolbyVision = false,
        videoResolutionsByCodec =
            mapOf(
                "h264" to VideoCodecResolution(),
                "hevc" to VideoCodecResolution(),
                "vp9" to
                    VideoCodecResolution(
                        maxWidth = 3_840,
                        maxHeight = 2_160,
                        maxFrameArea = blockPaddedArea(3_840, 2_160),
                        maxFrameAreaPerSecond = blockPaddedArea(3_840, 2_160) * 30L,
                    ),
                "av1" to VideoCodecResolution(),
            ),
    )
