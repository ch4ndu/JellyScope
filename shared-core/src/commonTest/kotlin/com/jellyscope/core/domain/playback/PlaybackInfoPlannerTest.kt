// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackInfoPlannerTest {
    @Test
    fun libVlcAv1OriginalQualityDoesNotInjectBudgetButExplicitQualityWins() =
        runTest {
            val repository =
                StaticPlaybackInfoRepository(
                    PlaybackInfo(
                        playSessionId = "play-session",
                        mediaSources =
                            listOf(
                                PlaybackMediaSourceInfo(
                                    id = "source",
                                    supportsDirectPlay = false,
                                    supportsDirectStream = false,
                                    supportsTranscoding = true,
                                    transcodingUrl = "/Videos/item/master.m3u8?ApiKey=secret",
                                    container = "mkv",
                                    transcodingContainer = "ts",
                                    transcodingSubProtocol = "hls",
                                    bitrate = null,
                                ),
                            ),
                    ),
                )
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository = repository,
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                                DeviceDecodingCapabilities(
                                    videoCodecs = listOf("h264"),
                                    audioCodecs = listOf("aac"),
                                    supportsDolbyVision = false,
                                    videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(1920, 1080)),
                                    videoConstraintsByCodec = mapOf("h264" to VideoCodecConstraints(maxBitDepth = 8)),
                                )
                        },
                )
            val av1Stream =
                PlaybackMediaStream(
                    index = 1,
                    type = "Video",
                    displayTitle = null,
                    title = null,
                    language = null,
                    codec = "av1",
                    channelLayout = null,
                    bitRate = 40_000_000L,
                    height = 2160,
                    isDefault = true,
                    isExternal = false,
                    deliveryMethod = null,
                    deliveryUrl = null,
                    width = 3840,
                )
            val session = Session("https://jellyfin.example", "server", "Jellyfin", "user", "User", "token", "device")
            val requestPolicy =
                PlaybackInfoRequestPolicy(
                    backend = PlayerBackend.LibVlc,
                    diagnosticSessionSequence = 77L,
                )

            val originalPlan =
                planner.plan(
                    session = session,
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(av1Stream),
                    qualityPolicy = PlaybackQualityPolicy.Original,
                    requestPolicy = requestPolicy,
                )
            assertNull(repository.lastMaxStreamingBitrate)
            assertNull(originalPlan.maxStreamingBitrate)
            assertNull(originalPlan.qualityCapOrigin)
            assertNull(originalPlan.effectiveTranscodeMaxStreamingBitrate)
            assertEquals(PlaybackQualityMode.Original, originalPlan.qualityPolicy.mode)
            assertEquals(77L, originalPlan.diagnosticSessionSequence)

            val explicitPlan =
                planner.plan(
                    session = session,
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(av1Stream),
                    maxStreamingBitrate = 4_000_000L,
                    qualityCapOrigin = PlaybackQualityCapOrigin.ExplicitSessionChoice,
                    requestPolicy = requestPolicy,
                )
            assertEquals(4_000_000L, repository.lastMaxStreamingBitrate)
            assertEquals(PlaybackQualityCapOrigin.ExplicitSessionChoice, explicitPlan.qualityCapOrigin)

            planner.plan(
                session = session,
                itemId = "item",
                mediaSourceId = "source",
                startPositionTicks = 0L,
                detailMediaStreams = listOf(av1Stream),
                requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.ExoPlayer),
            )
            assertNull(repository.lastMaxStreamingBitrate)
        }

    @Test
    fun responseVideoReasonDoesNotRetryWithoutConfiguredBudget() =
        runTest {
            val repository =
                StaticPlaybackInfoRepository(
                    PlaybackInfo(
                        playSessionId = "play-session",
                        mediaSources =
                            listOf(
                                PlaybackMediaSourceInfo(
                                    id = "source",
                                    supportsDirectPlay = false,
                                    supportsDirectStream = false,
                                    supportsTranscoding = true,
                                    transcodingUrl = "/Videos/item/master.m3u8?TranscodeReasons=VideoCodecNotSupported",
                                    container = "mkv",
                                    transcodingContainer = "ts",
                                    transcodingSubProtocol = "hls",
                                    bitrate = null,
                                    transcodeReasons = listOf("VideoCodecNotSupported"),
                                ),
                            ),
                    ),
                )
            val plan =
                PlaybackInfoPlanner(repository, DirectPlayPlanner()).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )

            assertEquals(listOf<Long?>(null), repository.requestedMaxStreamingBitrates)
            assertNull(plan.qualityCapOrigin)
            assertNull(plan.effectiveTranscodeMaxStreamingBitrate)
        }

    @Test
    fun callerCapOriginsRemainTruthfulForDirectPlayAndDirectStream() =
        runTest {
            val origins =
                listOf(
                    PlaybackQualityCapOrigin.SettingsDefault,
                    PlaybackQualityCapOrigin.AutoSessionRecovery,
                    PlaybackQualityCapOrigin.ExplicitSessionChoice,
                )
            val modes =
                listOf(
                    StreamMode.DirectPlay to
                        PlaybackMediaSourceInfo(
                            id = "source",
                            supportsDirectPlay = true,
                            supportsDirectStream = false,
                            supportsTranscoding = false,
                            transcodingUrl = null,
                            container = "mkv",
                            bitrate = 12_000_000L,
                        ),
                    StreamMode.DirectStream to
                        PlaybackMediaSourceInfo(
                            id = "source",
                            supportsDirectPlay = false,
                            supportsDirectStream = true,
                            supportsTranscoding = false,
                            transcodingUrl = null,
                            container = "mkv",
                            transcodingContainer = "mp4",
                            bitrate = 12_000_000L,
                        ),
                )

            modes.forEach { (expectedMode, mediaSource) ->
                origins.forEach { origin ->
                    val plan =
                        PlaybackInfoPlanner(
                            StaticPlaybackInfoRepository(
                                PlaybackInfo(
                                    playSessionId = "play-session",
                                    mediaSources = listOf(mediaSource),
                                ),
                            ),
                            DirectPlayPlanner(),
                        ).plan(
                            session = testSession(),
                            itemId = "item",
                            mediaSourceId = "source",
                            startPositionTicks = 0L,
                            maxStreamingBitrate = 6_000_000L,
                            qualityCapOrigin = origin,
                        )

                    assertEquals(expectedMode, plan.streamMode)
                    assertEquals(6_000_000L, plan.maxStreamingBitrate)
                    assertEquals(origin, plan.qualityCapOrigin)
                    assertNull(plan.effectiveTranscodeMaxStreamingBitrate)
                }
            }
        }

    @Test
    fun audioOnlyReasonDoesNotTriggerVideoQualityRetry() =
        runTest {
            val repository =
                StaticPlaybackInfoRepository(
                    PlaybackInfo(
                        playSessionId = "play-session",
                        mediaSources =
                            listOf(
                                PlaybackMediaSourceInfo(
                                    id = "source",
                                    supportsDirectPlay = false,
                                    supportsDirectStream = false,
                                    supportsTranscoding = true,
                                    transcodingUrl = "/Videos/item/master.m3u8?TranscodeReasons=AudioCodecNotSupported",
                                    container = "mkv",
                                    transcodingContainer = "ts",
                                    transcodingSubProtocol = "hls",
                                    bitrate = null,
                                    transcodeReasons = listOf("AudioCodecNotSupported"),
                                ),
                            ),
                    ),
                )
            PlaybackInfoPlanner(repository, DirectPlayPlanner()).plan(
                session = testSession(),
                itemId = "item",
                mediaSourceId = "source",
                startPositionTicks = 0L,
                requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
            )

            assertEquals(listOf<Long?>(null), repository.requestedMaxStreamingBitrates)
        }

    @Test
    fun highBitrateH264DoesNotTriggerQualityCapWhenVideoIsKnownCompatible() =
        runTest {
            val repository =
                StaticPlaybackInfoRepository(
                    PlaybackInfo(
                        playSessionId = "play-session",
                        mediaSources =
                            listOf(
                                PlaybackMediaSourceInfo(
                                    id = "source",
                                    supportsDirectPlay = true,
                                    supportsDirectStream = false,
                                    supportsTranscoding = false,
                                    transcodingUrl = null,
                                    container = "mkv",
                                    bitrate = 49_000_000L,
                                ),
                            ),
                    ),
                )
            val plan =
                PlaybackInfoPlanner(
                    repository,
                    DirectPlayPlanner(),
                    object : DeviceProfileProvider {
                        override fun capabilities(backend: PlayerBackend) =
                            DeviceDecodingCapabilities(
                                videoCodecs = listOf("h264"),
                                audioCodecs = listOf("aac"),
                                supportsDolbyVision = false,
                                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(3840, 2160)),
                                videoConstraintsByCodec = mapOf("h264" to VideoCodecConstraints(maxBitDepth = 8)),
                            )
                    },
                ).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams =
                        listOf(
                            PlaybackMediaStream(
                                index = 1,
                                type = "Video",
                                displayTitle = null,
                                title = null,
                                language = null,
                                codec = "h264",
                                channelLayout = null,
                                bitRate = 49_000_000L,
                                height = 1080,
                                isDefault = true,
                                isExternal = false,
                                deliveryMethod = null,
                                deliveryUrl = null,
                                width = 1920,
                                bitDepth = 8,
                            ),
                        ),
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )

            assertNull(plan.maxStreamingBitrate)
            assertEquals(listOf<Long?>(null), repository.requestedMaxStreamingBitrates)
        }

    @Test
    fun nonDefaultBackendPlaybackInfoFailureDoesNotUseTheLocalDirectPlayFallback() =
        runTest {
            var requestedBackend: PlayerBackend? = null
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository = FailingPlaybackInfoRepository(),
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities {
                                requestedBackend = backend
                                return DeviceDecodingCapabilities(
                                    videoCodecs = listOf("h264"),
                                    audioCodecs = listOf("aac"),
                                    supportsDolbyVision = false,
                                )
                            }
                        },
                )

            assertFailsWith<IllegalStateException> {
                planner.plan(
                    session = Session("https://jellyfin.example", "server", "Jellyfin", "user", "User", "token", "device"),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.VlcKit),
                )
            }
            assertEquals(PlayerBackend.VlcKit, requestedBackend)
        }

    @Test
    fun mpvPlaybackInfoPlanningUsesMpvCapabilities() =
        runTest {
            val requestedBackends = mutableListOf<PlayerBackend>()
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository =
                        StaticPlaybackInfoRepository(
                            PlaybackInfo(
                                playSessionId = "play-session",
                                mediaSources =
                                    listOf(
                                        PlaybackMediaSourceInfo(
                                            id = "source",
                                            supportsDirectPlay = true,
                                            supportsDirectStream = false,
                                            supportsTranscoding = false,
                                            transcodingUrl = null,
                                            container = "mp4",
                                            bitrate = 12_000_000L,
                                        ),
                                    ),
                            ),
                        ),
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities {
                                requestedBackends += backend
                                check(backend == PlayerBackend.Mpv)
                                return DeviceDecodingCapabilities(
                                    videoCodecs = listOf("h264"),
                                    audioCodecs = listOf("aac"),
                                    supportsDolbyVision = false,
                                )
                            }
                        },
                )

            val plan =
                planner.plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.Mpv),
                )

            assertEquals(listOf(PlayerBackend.Mpv), requestedBackends)
            assertEquals(StreamMode.DirectPlay, plan.streamMode)
        }

    @Test
    fun explicitFixedPlaybackInfoFailureDoesNotUseTheLocalDirectPlayFallback() =
        runTest {
            val repository = SequencedPlaybackInfoRepository(listOf(Result.failure(IllegalStateException("unavailable"))))

            assertFailsWith<IllegalStateException> {
                PlaybackInfoPlanner(
                    mediaRepository = repository,
                    directPlayPlanner = DirectPlayPlanner(),
                ).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    qualityPolicy = PlaybackQualityPolicy.fixed(8_000_000L),
                    qualityCapOrigin = PlaybackQualityCapOrigin.ExplicitSessionChoice,
                )
            }

            assertEquals(8_000_000L, repository.requests.single().maxStreamingBitrate)
        }

    @Test
    fun vlcFixedDefaultPlaybackInfoFailureDoesNotUseTheLocalDirectPlayFallback() =
        runTest {
            val repository = SequencedPlaybackInfoRepository(listOf(Result.failure(IllegalStateException("unavailable"))))

            assertFailsWith<IllegalStateException> {
                PlaybackInfoPlanner(
                    mediaRepository = repository,
                    directPlayPlanner = DirectPlayPlanner(),
                ).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    qualityPolicy = PlaybackQualityPolicy.fixed(4_000_000L),
                    qualityCapOrigin = PlaybackQualityCapOrigin.SettingsDefault,
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )
            }

            val request = repository.requests.single()
            assertEquals(4_000_000L, request.maxStreamingBitrate)
            assertEquals(PlayerBackend.LibVlc, request.requestPolicy.backend)
        }

    @Test
    fun defaultPolicyFallbackRecordsDiagnosticsEvenThoughPlanningSucceeds() =
        runTest {
            val context = PlaybackDiagnosticsContext()
            val capabilities =
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                )
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository = FailingPlaybackInfoRepository(),
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = capabilities
                        },
                    playbackDiagnosticsContext = context,
                )
            val streams =
                listOf(
                    PlaybackMediaStream(
                        index = 1,
                        type = "Video",
                        displayTitle = "Private title",
                        title = "Private title",
                        language = null,
                        codec = "h264",
                        channelLayout = null,
                        bitRate = 12_000_000,
                        height = 1080,
                        isDefault = true,
                        isExternal = false,
                        deliveryMethod = null,
                        deliveryUrl = null,
                        width = 1920,
                        realFrameRate = 23.976,
                        bitDepth = 8,
                    ),
                    PlaybackMediaStream(
                        index = 2,
                        type = "Audio",
                        displayTitle = "Private audio title",
                        title = "Private audio title",
                        language = "eng",
                        codec = "aac",
                        channelLayout = "5.1",
                        bitRate = 640_000,
                        height = null,
                        isDefault = true,
                        isExternal = false,
                        deliveryMethod = null,
                        deliveryUrl = null,
                    ),
                )

            val plan =
                planner.plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    audioStreamIndex = 2,
                    detailMediaStreams = streams,
                    sourceContainer = "mkv",
                )

            assertEquals(StreamMode.DirectPlay, plan.streamMode)
            val snapshot = assertNotNull(context.snapshot())
            assertEquals(PlayerBackend.AVPlayer, snapshot.backend)
            assertEquals(capabilities, snapshot.capabilities)
            assertEquals("h264", snapshot.source?.videoCodec)
            assertEquals("aac", snapshot.source?.audioCodec)
            assertEquals("mkv", snapshot.source?.container)
        }

    @Test
    fun fallbackSubtitleLookupsMatchEquivalentProviderFormatAliases() =
        runTest {
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository = FailingPlaybackInfoRepository(),
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend) =
                                DeviceDecodingCapabilities(
                                    videoCodecs = listOf("h264"),
                                    audioCodecs = listOf("aac"),
                                    supportsDolbyVision = false,
                                    subtitleProfiles =
                                        listOf(
                                            DeviceSubtitleProfile(
                                                format = "vtt",
                                                deliveryMethods = listOf(SubtitleDeliveryMethod.Embed),
                                                kind = SubtitleKind.Text,
                                            ),
                                        ),
                                )
                        },
                )

            val plan =
                planner.plan(
                    session = Session("https://jellyfin.example", "server", "Jellyfin", "user", "User", "token", "device"),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams =
                        listOf(
                            PlaybackMediaStream(
                                index = 2,
                                type = "Audio",
                                displayTitle = null,
                                title = null,
                                language = "eng",
                                codec = "aac",
                                channelLayout = "stereo",
                                bitRate = null,
                                height = null,
                                isDefault = true,
                                isExternal = false,
                                deliveryMethod = null,
                                deliveryUrl = null,
                            ),
                            PlaybackMediaStream(
                                index = 4,
                                type = "Subtitle",
                                displayTitle = null,
                                title = null,
                                language = "eng",
                                codec = "webvtt",
                                channelLayout = null,
                                bitRate = null,
                                height = null,
                                isDefault = false,
                                isExternal = false,
                                deliveryMethod = "Embed",
                                deliveryUrl = null,
                            ),
                        ),
                    audioStreamIndex = 2,
                    subtitleSelection = SubtitleSelectionIntent.Track(4),
                )

            val subtitle = assertIs<PlannedSubtitle.Track>(plan.plannedSubtitle)
            assertEquals(SubtitleDeliveryMethod.Embed, subtitle.deliveryMethod)
            assertEquals("vtt", subtitle.normalizedFormat)
            assertEquals(4, subtitle.embeddedTrack?.jellyfinStreamIndex)
            assertNull(subtitle.embeddedTrack?.responseAuthoritativeCohortSize)
            assertNull(plan.embeddedAudioTracks.single().responseAuthoritativeCohortSize)
        }

    @Test
    fun plannerRetainsApprovedEmbeddedSubtitleDescriptorsWhenSelectionIsOff() =
        runTest {
            val video = sourceVideo(width = 1_920, height = 1_080)
            val subtitle =
                PlaybackMediaStream(
                    index = 4,
                    type = "Subtitle",
                    displayTitle = "English SRT",
                    title = null,
                    language = "eng",
                    codec = "srt",
                    channelLayout = null,
                    bitRate = null,
                    height = null,
                    isDefault = true,
                    isExternal = false,
                    deliveryMethod = "Embed",
                    deliveryUrl = null,
                )
            val streams = listOf(video, subtitle)
            val responseStreams = listOf(video, subtitle.copy(deliveryMethod = "Drop"))
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository =
                        StaticPlaybackInfoRepository(
                            PlaybackInfo(
                                playSessionId = "play-session",
                                mediaSources =
                                    listOf(
                                        PlaybackMediaSourceInfo(
                                            id = "source",
                                            supportsDirectPlay = true,
                                            supportsDirectStream = false,
                                            supportsTranscoding = false,
                                            transcodingUrl = null,
                                            container = "mkv",
                                            bitrate = 12_000_000L,
                                            mediaStreams = responseStreams,
                                        ),
                                    ),
                            ),
                        ),
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend) =
                                DeviceDecodingCapabilities(
                                    videoCodecs = listOf("h264"),
                                    audioCodecs = listOf("aac"),
                                    supportsDolbyVision = false,
                                    subtitleProfiles =
                                        listOf(
                                            DeviceSubtitleProfile(
                                                format = "srt",
                                                deliveryMethods = listOf(SubtitleDeliveryMethod.Embed),
                                                kind = SubtitleKind.Text,
                                            ),
                                        ),
                                )
                        },
                )

            val plan =
                planner.plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = streams,
                    subtitleSelection = SubtitleSelectionIntent.Off,
                )

            assertIs<PlannedSubtitle.Off>(plan.plannedSubtitle)
            assertEquals(StreamMode.DirectPlay, plan.streamMode)
            val descriptor = plan.embeddedSubtitleTracks.single()
            assertEquals(4, descriptor.jellyfinStreamIndex)
            assertEquals(0, descriptor.filteredContainerOrdinal)
            assertEquals("srt", descriptor.codec)
            assertNull(descriptor.responseAuthoritativeCohortSize)
        }

    @Test
    fun plannerKeepsRequestedTranscodeSubtitleWhenServerDefaultDiffers() =
        runTest {
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository =
                        StaticPlaybackInfoRepository(
                            PlaybackInfo(
                                playSessionId = "play-session",
                                mediaSources =
                                    listOf(
                                        PlaybackMediaSourceInfo(
                                            id = "source",
                                            supportsDirectPlay = false,
                                            supportsDirectStream = false,
                                            supportsTranscoding = true,
                                            transcodingUrl =
                                                "/Videos/item/master.m3u8?SubtitleStreamIndex=4" +
                                                    "&SubtitleMethod=Encode&ApiKey=secret",
                                            container = "mkv",
                                            defaultSubtitleStreamIndex = 3,
                                            bitrate = null,
                                            mediaStreams =
                                                listOf(
                                                    PlaybackMediaStream(
                                                        index = 4,
                                                        type = "Subtitle",
                                                        displayTitle = null,
                                                        title = null,
                                                        language = "eng",
                                                        codec = "pgs",
                                                        channelLayout = null,
                                                        bitRate = null,
                                                        height = null,
                                                        isDefault = false,
                                                        isExternal = false,
                                                        deliveryMethod = "Encode",
                                                        deliveryUrl = null,
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                    directPlayPlanner = DirectPlayPlanner(),
                )

            val plan =
                planner.plan(
                    session = Session("https://jellyfin.example", "server", "Jellyfin", "user", "User", "token", "device"),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    subtitleSelection = SubtitleSelectionIntent.Track(4),
                )

            assertEquals(4, plan.selectedSubtitleStreamIndex)
            assertEquals(
                "https://jellyfin.example/Videos/item/master.m3u8?SubtitleStreamIndex=4" +
                    "&SubtitleMethod=Encode&ApiKey=secret",
                plan.streamUrl,
            )
            assertEquals(4, assertIs<PlannedSubtitle.Track>(plan.plannedSubtitle).streamIndex)
        }

    @Test
    fun plannerDemotesEncodeSubtitleWhenUrlAttachesDifferentStream() =
        runTest {
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository =
                        StaticPlaybackInfoRepository(
                            PlaybackInfo(
                                playSessionId = "play-session",
                                mediaSources =
                                    listOf(
                                        PlaybackMediaSourceInfo(
                                            id = "source",
                                            supportsDirectPlay = false,
                                            supportsDirectStream = false,
                                            supportsTranscoding = true,
                                            transcodingUrl =
                                                "/Videos/item/master.m3u8?SubtitleStreamIndex=3" +
                                                    "&SubtitleMethod=Encode&ApiKey=secret",
                                            container = "mkv",
                                            defaultSubtitleStreamIndex = 3,
                                            bitrate = null,
                                            mediaStreams =
                                                listOf(
                                                    PlaybackMediaStream(
                                                        index = 4,
                                                        type = "Subtitle",
                                                        displayTitle = null,
                                                        title = null,
                                                        language = "eng",
                                                        codec = "pgs",
                                                        channelLayout = null,
                                                        bitRate = null,
                                                        height = null,
                                                        isDefault = false,
                                                        isExternal = false,
                                                        deliveryMethod = "Encode",
                                                        deliveryUrl = null,
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                    directPlayPlanner = DirectPlayPlanner(),
                )

            val plan =
                planner.plan(
                    session = Session("https://jellyfin.example", "server", "Jellyfin", "user", "User", "token", "device"),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    subtitleSelection = SubtitleSelectionIntent.Track(4),
                )

            // The mismatched attachment is stripped, so nothing burns the
            // requested track — it must never be published as active, and the
            // one-shot forced-Encode replan must remain available to recover it.
            assertEquals(
                "https://jellyfin.example/Videos/item/master.m3u8?ApiKey=secret",
                plan.streamUrl,
            )
            val unavailable = assertIs<PlannedSubtitle.Unavailable>(plan.plannedSubtitle)
            assertEquals(4, unavailable.streamIndex)
            assertEquals(true, unavailable.allowEncodeFallback)
        }

    @Test
    fun capabilityPreflightRefusesOversizeServerDirectPlayAndDirectStream() =
        runTest {
            listOf(StreamMode.DirectPlay, StreamMode.DirectStream).forEach { streamMode ->
                val repository =
                    SequencedPlaybackInfoRepository(
                        listOf(
                            Result.success(copyModePlaybackInfo(streamMode, sourceVideo(width = 3_840, height = 2_160))),
                            Result.success(copyModePlaybackInfo(streamMode, sourceVideo(width = 3_840, height = 2_160))),
                        ),
                    )

                assertFailsWith<PlaybackPlanningException.SourceVideoCopyUnsupported> {
                    boundedPlanner(repository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                    )
                }

                assertEquals(2, repository.requestPolicies.size)
                assertEquals(PlayerBackend.AVPlayer, repository.requestPolicies[1].backend)
                assertEquals(false, repository.requestPolicies[1].enableDirectPlay)
                assertEquals(false, repository.requestPolicies[1].enableDirectStream)
            }
        }

    @Test
    fun unknownDeviceResolutionBoundDoesNotRejectDirectPlay() =
        runTest {
            val source = sourceVideo(width = 3_840, height = 2_160)
            val planner =
                PlaybackInfoPlanner(
                    mediaRepository =
                        StaticPlaybackInfoRepository(
                            copyModePlaybackInfo(StreamMode.DirectPlay, source),
                        ),
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                                DeviceDecodingCapabilities(
                                    videoCodecs = listOf("h264"),
                                    audioCodecs = listOf("aac"),
                                    supportsDolbyVision = false,
                                    videoResolutionsByCodec = emptyMap(),
                                )
                        },
                )

            val plan =
                planner.plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(source),
                )

            assertEquals(StreamMode.DirectPlay, plan.streamMode)
        }

    @Test
    fun capabilityPreflightRefusesTheLocalPlaybackInfoFailureFallback() =
        runTest {
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.failure(IllegalStateException("unavailable")),
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, sourceVideo(width = 3_840, height = 2_160))),
                    ),
                )

            assertFailsWith<PlaybackPlanningException.SourceVideoCopyUnsupported> {
                boundedPlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(sourceVideo(width = 3_840, height = 2_160)),
                )
            }

            assertEquals(2, repository.requestPolicies.size)
            assertEquals(false, repository.requestPolicies[1].enableDirectPlay)
            assertEquals(false, repository.requestPolicies[1].enableDirectStream)
        }

    @Test
    fun forcedTranscodeRequestFailureRetainsTheAttemptedPolicyForDiagnostics() =
        runTest {
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, sourceVideo(width = 3_840, height = 2_160))),
                        Result.failure(IllegalStateException("recovery unavailable")),
                    ),
                )

            val failure =
                assertFailsWith<PlaybackPlanningException.SourceVideoCopyUnsupported> {
                    boundedPlanner(repository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        detailMediaStreams = listOf(sourceVideo(width = 3_840, height = 2_160)),
                        requestPolicy = PlaybackInfoRequestPolicy(diagnosticSessionSequence = 77L),
                    )
                }

            val attemptedPolicy = assertNotNull(failure.attemptedRequestPolicy)
            assertEquals(false, attemptedPolicy.enableDirectPlay)
            assertEquals(false, attemptedPolicy.enableDirectStream)
            assertEquals(false, attemptedPolicy.allowVideoStreamCopy)
            assertEquals(PlaybackRecoveryIntent.Compatibility, attemptedPolicy.recoveryIntent)
            assertEquals(PlaybackClientTrigger.DecodeCapabilityCap, attemptedPolicy.clientTrigger)
            assertEquals(77L, attemptedPolicy.diagnosticSessionSequence)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun capabilityPreflightRejectsThroughputAndMissingMetadata() =
        runTest {
            val throughputRepository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(
                            copyModePlaybackInfo(StreamMode.DirectPlay, sourceVideo(width = 1_920, height = 1_080, frameRate = 60.0)),
                        ),
                        Result.success(
                            copyModePlaybackInfo(StreamMode.Transcode, sourceVideo(width = 1_920, height = 1_080, frameRate = 60.0)),
                        ),
                    ),
                )

            val throughputPlan =
                boundedPlanner(throughputRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                )
            assertEquals(StreamMode.Transcode, throughputPlan.streamMode)
            assertEquals(2, throughputRepository.requestPolicies.size)

            // Missing dimensions refuse the copy but recover through the
            // transcode: output dimensions ARE expressible as profile conditions,
            // which is the mechanism this plan ships. Only the frame-rate case
            // below must stay fail-closed, because no profile condition can
            // express a throughput bound.
            val missingDimensionsRepository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, sourceVideo(width = null, height = 1_080))),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, sourceVideo(width = null, height = 1_080))),
                    ),
                )
            val missingDimensionsPlan =
                boundedPlanner(missingDimensionsRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                )
            assertEquals(StreamMode.Transcode, missingDimensionsPlan.streamMode)
            assertEquals(2, missingDimensionsRepository.requestPolicies.size)

            val missingRateSource = sourceVideo(width = 1_920, height = 1_080, frameRate = null)
            val missingRateRepository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, missingRateSource)),
                        // A transcode response cannot prove its output rate until
                        // MaxFramerate honouring is probe-verified, so this stays
                        // fail-closed after its one recovery request.
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, missingRateSource)),
                    ),
                )
            assertFailsWith<PlaybackPlanningException.SourceVideoCopyUnsupported> {
                boundedPlanner(missingRateRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                )
            }
            assertEquals(2, missingRateRepository.requestPolicies.size)
            assertEquals(30, missingRateRepository.requestPolicies.last().maxFramerate)
        }

    @Test
    fun inBoundsSourceStillDirectPlaysThroughThePreflight() =
        runTest {
            // The plan's named regression check: ordinary content that DirectPlays
            // today must keep DirectPlaying once the capability map is populated
            // and the preflight guards the copy paths. 1080p30 h264 sits exactly
            // at the bounded fixture's ceiling.
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(
                            copyModePlaybackInfo(StreamMode.DirectPlay, sourceVideo(width = 1_920, height = 1_080, frameRate = 30.0)),
                        ),
                    ),
                )

            val plan =
                boundedPlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                )

            assertEquals(StreamMode.DirectPlay, plan.streamMode)
            assertEquals(1, repository.requestPolicies.size)
        }

    @Test
    fun missingResponseMetadataIsBorrowedFromItemDetailBeforeFailingClosed() =
        runTest {
            // The response and the item detail describe the same file; when the
            // response omits the frame rate but detail carries it, the preflight
            // must use the borrowed value and DirectPlay in-bounds content
            // instead of failing closed on absent metadata.
            val responseSource = sourceVideo(width = 1_920, height = 1_080, frameRate = null)
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, responseSource))),
                )

            val plan =
                boundedPlanner(repository)
                    .plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        detailMediaStreams = listOf(sourceVideo(width = 1_920, height = 1_080, frameRate = 30.0)),
                    )

            assertEquals(StreamMode.DirectPlay, plan.streamMode)
            assertEquals(1, repository.requestPolicies.size)
        }

    @Test
    fun sparseMacOsCopyResponseBorrowsExactDetailCodecAndEnvelopeMetadata() =
        runTest {
            val detailSource = sourceVideo(width = 3_840, height = 2_160, frameRate = 120.0)
            val sparseResponse =
                detailSource.copy(
                    codec = null,
                    width = null,
                    height = null,
                    realFrameRate = null,
                )
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, sparseResponse)),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, sparseResponse)),
                    ),
                )

            val plan =
                standard4k60EnvelopePlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(detailSource),
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )

            assertEquals(StreamMode.Transcode, plan.streamMode)
            assertTrue(plan.videoExpected)
            assertEquals(2, repository.requestPolicies.size)
            assertFalse(repository.requestPolicies.last().allowVideoStreamCopy)
        }

    @Test
    fun missingFireTvResponseVideoAndSourceIdStillUseExactDetailRangePolicy() =
        runTest {
            val detailSource = fireTvExcludedSource()

            fun sparsePlaybackInfo(streamMode: StreamMode): PlaybackInfo {
                val playbackInfo = copyModePlaybackInfo(streamMode, detailSource, mediaSourceId = null)
                return playbackInfo.copy(
                    mediaSources =
                        playbackInfo.mediaSources.map { source -> source.copy(mediaStreams = emptyList()) },
                )
            }
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(sparsePlaybackInfo(StreamMode.DirectPlay)),
                        Result.success(sparsePlaybackInfo(StreamMode.Transcode)),
                    ),
                )

            val plan =
                fireTvRangeExcludedPlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(detailSource),
                )

            assertEquals(StreamMode.Transcode, plan.streamMode)
            assertTrue(plan.videoExpected)
            assertEquals(2, repository.requestPolicies.size)
            assertFalse(repository.requestPolicies.last().allowVideoStreamCopy)
        }

    @Test
    fun detailMetadataIsNotBorrowedAcrossMediaSourceVersions() =
        runTest {
            // The planner falls back to the response's FIRST source when the
            // requested id is absent — a different VERSION of the item. The
            // caller's detail streams describe the requested version, so
            // borrowing across versions could fill a 60fps alternate's missing
            // rate with the requested version's 24fps and permit an unsafe copy.
            // A mismatched source keeps its own metadata and fails closed.
            val rateLessAlternate = sourceVideo(width = 1_920, height = 1_080, frameRate = null)
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(
                            copyModePlaybackInfo(StreamMode.DirectPlay, rateLessAlternate, mediaSourceId = "other-version"),
                        ),
                        Result.success(
                            copyModePlaybackInfo(StreamMode.Transcode, rateLessAlternate, mediaSourceId = "other-version"),
                        ),
                    ),
                )

            assertFailsWith<PlaybackPlanningException.SourceVideoCopyUnsupported> {
                boundedPlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(sourceVideo(width = 1_920, height = 1_080, frameRate = 30.0)),
                )
            }
            assertEquals(2, repository.requestPolicies.size)
        }

    @Test
    fun mismatchedSourceWithoutVideoRefusesCopyAndPreservesVideoHealth() =
        runTest {
            val detailSource = sourceVideo(width = 3_840, height = 2_160, frameRate = 120.0)

            fun sparseAlternate(streamMode: StreamMode): PlaybackInfo {
                val playbackInfo =
                    copyModePlaybackInfo(
                        streamMode = streamMode,
                        source = detailSource,
                        mediaSourceId = "other-version",
                    )
                return playbackInfo.copy(
                    mediaSources =
                        playbackInfo.mediaSources.map { source -> source.copy(mediaStreams = emptyList()) },
                )
            }

            val autoRepository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(sparseAlternate(StreamMode.DirectPlay)),
                        Result.success(sparseAlternate(StreamMode.Transcode)),
                    ),
                )
            val autoPlan =
                standard4k60EnvelopePlanner(autoRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(detailSource),
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )
            assertEquals(StreamMode.Transcode, autoPlan.streamMode)
            assertTrue(autoPlan.videoExpected)
            assertEquals(2, autoRepository.requestPolicies.size)
            assertFalse(autoRepository.requestPolicies.last().allowVideoStreamCopy)

            val originalRepository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(sparseAlternate(StreamMode.DirectPlay))),
                )
            assertFailsWith<PlaybackPlanningException.SourceVideoCopyUnsupported> {
                standard4k60EnvelopePlanner(originalRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(detailSource),
                    qualityPolicy = PlaybackQualityPolicy.Original,
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )
            }
            assertEquals(1, originalRepository.requestPolicies.size)
        }

    @Test
    fun mismatchedSourceWithoutCodecCannotBypassMacOsEnvelope() =
        runTest {
            val detailSource = sourceVideo(width = 3_840, height = 2_160, frameRate = 120.0)
            val codecLessAlternate = detailSource.copy(codec = null)
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(
                            copyModePlaybackInfo(
                                StreamMode.DirectPlay,
                                codecLessAlternate,
                                mediaSourceId = "other-version",
                            ),
                        ),
                        Result.success(
                            copyModePlaybackInfo(
                                StreamMode.Transcode,
                                codecLessAlternate,
                                mediaSourceId = "other-version",
                            ),
                        ),
                    ),
                )

            val plan =
                standard4k60EnvelopePlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(detailSource),
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )

            assertEquals(StreamMode.Transcode, plan.streamMode)
            assertTrue(plan.videoExpected)
            assertEquals(2, repository.requestPolicies.size)
            assertFalse(repository.requestPolicies.last().allowVideoStreamCopy)
        }

    @Test
    fun responseVideoWithoutCodecCannotBypassMacOsEnvelopeWhenDetailHasNoVideo() =
        runTest {
            val codecLessSource =
                sourceVideo(width = 3_840, height = 2_160, frameRate = 120.0).copy(codec = null)
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, codecLessSource)),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, codecLessSource)),
                    ),
                )

            val plan =
                standard4k60EnvelopePlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = emptyList(),
                    requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                )

            assertEquals(StreamMode.Transcode, plan.streamMode)
            assertTrue(plan.videoExpected)
            assertEquals(2, repository.requestPolicies.size)
            assertFalse(repository.requestPolicies.last().allowVideoStreamCopy)
        }

    @Test
    fun mismatchedFireTvUnknownMetadataDoesNotInventRangeExclusion() =
        runTest {
            val detailSource = fireTvExcludedSource()
            val rangeLessAlternate =
                copyModePlaybackInfo(
                    StreamMode.DirectPlay,
                    detailSource.copy(videoRangeType = null),
                    mediaSourceId = "other-version",
                )
            val codecLessAlternate =
                copyModePlaybackInfo(
                    StreamMode.DirectPlay,
                    detailSource.copy(codec = null),
                    mediaSourceId = "other-version",
                )
            val videoLessAlternate =
                copyModePlaybackInfo(
                    StreamMode.DirectPlay,
                    detailSource,
                    mediaSourceId = "other-version",
                ).let { playbackInfo ->
                    playbackInfo.copy(
                        mediaSources =
                            playbackInfo.mediaSources.map { source -> source.copy(mediaStreams = emptyList()) },
                    )
                }

            listOf(rangeLessAlternate, codecLessAlternate, videoLessAlternate).forEach { playbackInfo ->
                val repository =
                    SequencedPlaybackInfoRepository(
                        listOf(Result.success(playbackInfo)),
                    )

                val plan =
                    fireTvRangeExcludedPlanner(repository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        detailMediaStreams = listOf(detailSource),
                    )

                assertEquals(StreamMode.DirectPlay, plan.streamMode)
                assertTrue(plan.videoExpected)
                assertEquals(1, repository.requestPolicies.size)
            }
        }

    @Test
    fun foreignCodecWithUnknownResolutionBoundRemainsDirectPlayEligible() =
        runTest {
            // A populated capability map with no entry for the SOURCE codec is
            // unknown evidence, not an unsupported-codec claim.
            val foreignSource = sourceVideo(width = 720, height = 576, codec = "mpeg2video")
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, foreignSource)),
                    ),
                )

            val plan =
                boundedPlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                )

            assertEquals(StreamMode.DirectPlay, plan.streamMode)
            assertEquals(1, repository.requestPolicies.size)
        }

    @Test
    fun capabilityPreflightNeverReusesUnprovenResponseTranscodeAndRetriesOnce() =
        runTest {
            // The original response's transcode CANNOT be reused for a
            // copy-refused source: its request left AllowVideoStreamCopy=true, so
            // the server may have remuxed the refused video verbatim and nothing
            // in the response reveals it (server-behaviors item 12). Recovery
            // must re-request with the copy provably disabled.
            val source = sourceVideo(width = 3_840, height = 2_160)
            val reusableRepository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source, includeTranscode = true)),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                    ),
                )

            val reusedPlan =
                boundedPlanner(reusableRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                )

            assertEquals(StreamMode.Transcode, reusedPlan.streamMode)
            assertEquals(2, reusableRepository.requestPolicies.size)
            val recoveryPolicy = reusableRepository.requestPolicies.last()
            assertEquals(false, recoveryPolicy.enableDirectPlay)
            assertEquals(false, recoveryPolicy.enableDirectStream)
            assertEquals(false, recoveryPolicy.allowVideoStreamCopy)
            // The overlay's "App trigger" row reads the PLAN's trigger: an
            // app-forced transcode must never render as "None", because the
            // forced re-encode adds no server TranscodeReason either.
            assertEquals(PlaybackClientTrigger.DecodeCapabilityCap, recoveryPolicy.clientTrigger)
            assertEquals(PlaybackClientTrigger.DecodeCapabilityCap, reusedPlan.clientTrigger)

            val retryRepository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source)),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                    ),
                )
            val recoveredPlan =
                boundedPlanner(retryRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                )

            assertEquals(StreamMode.Transcode, recoveredPlan.streamMode)
            assertEquals(2, retryRepository.requestPolicies.size)
            assertEquals(false, retryRepository.requestPolicies[1].enableDirectPlay)
            assertEquals(false, retryRepository.requestPolicies[1].enableDirectStream)
        }

    @Test
    fun qualityRungMappingCoversEveryUserSelectionSourceButNotAutomaticCaps() =
        runTest {
            val userOrigins =
                listOf(
                    PlaybackQualityCapOrigin.ExplicitSessionChoice,
                    PlaybackQualityCapOrigin.SettingsDefault,
                    PlaybackQualityCapOrigin.AutoSessionRecovery,
                )
            userOrigins.forEach { origin ->
                val repository =
                    SequencedPlaybackInfoRepository(
                        listOf(Result.success(copyModePlaybackInfo(StreamMode.Transcode, sourceVideo(width = 1_920, height = 1_080)))),
                    )

                boundedPlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    maxStreamingBitrate = 4_000_000L,
                    qualityCapOrigin = origin,
                )

                // The 1080p source under the 720p rung is copy-refused, so a
                // recovery request follows; the mapping under test is on the
                // FIRST policy, and the recovery must preserve it.
                val policy = repository.requestPolicies.first()
                assertEquals(origin, policy.qualityCapOrigin)
                assertEquals(1_280, policy.qualityResolutionCap?.maxWidth)
                assertEquals(720, policy.qualityResolutionCap?.maxHeight)
                repository.requestPolicies.drop(1).forEach { recovery ->
                    assertEquals(policy.qualityResolutionCap, recovery.qualityResolutionCap)
                    assertEquals(false, recovery.allowVideoStreamCopy)
                }
            }

            val originalRepository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, sourceVideo(width = 1_920, height = 1_080)))),
                )
            val originalPlan =
                qualityCapablePlanner(originalRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                )
            assertEquals(StreamMode.DirectPlay, originalPlan.streamMode)
            assertNull(originalRepository.requestPolicies.single().qualityResolutionCap)

            val unmatchedRepository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(copyModePlaybackInfo(StreamMode.Transcode, sourceVideo(width = 1_920, height = 1_080)))),
                )
            boundedPlanner(unmatchedRepository).plan(
                session = testSession(),
                itemId = "item",
                mediaSourceId = "source",
                startPositionTicks = 0L,
                maxStreamingBitrate = 6_000_000L,
                qualityCapOrigin = PlaybackQualityCapOrigin.ExplicitSessionChoice,
            )
            assertNull(unmatchedRepository.requestPolicies.single().qualityResolutionCap)

            val automaticRepository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(copyModePlaybackInfo(StreamMode.Transcode, sourceVideo(width = 1_920, height = 1_080)))),
                )
            boundedPlanner(automaticRepository).plan(
                session = testSession(),
                itemId = "item",
                mediaSourceId = "source",
                startPositionTicks = 0L,
                maxStreamingBitrate = 4_000_000L,
                qualityCapOrigin = null,
            )
            assertNull(automaticRepository.requestPolicies.single().qualityResolutionCap)
        }

    @Test
    fun qualityRungPreflightRefusesEverySourceCopyPathAndPinsRecoveryFramerate() =
        runTest {
            val source = sourceVideo(width = 1_920, height = 1_080)
            val sourceCopyModes = listOf(StreamMode.DirectPlay, StreamMode.DirectStream)
            sourceCopyModes.forEach { streamMode ->
                val repository =
                    SequencedPlaybackInfoRepository(
                        listOf(
                            Result.success(copyModePlaybackInfo(streamMode, source)),
                            Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                        ),
                    )

                val plan =
                    qualityCapablePlanner(repository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        maxStreamingBitrate = 4_000_000L,
                        qualityCapOrigin = PlaybackQualityCapOrigin.ExplicitSessionChoice,
                    )

                assertEquals(StreamMode.Transcode, plan.streamMode)
                assertEquals(2, repository.requestPolicies.size)
                assertEquals(
                    720,
                    repository.requestPolicies
                        .first()
                        .qualityResolutionCap
                        ?.maxHeight,
                )
                assertEquals(false, repository.requestPolicies.last().enableDirectPlay)
                assertEquals(false, repository.requestPolicies.last().enableDirectStream)
                assertEquals(30, repository.requestPolicies.last().maxFramerate)
            }

            val localFallbackRepository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.failure(IllegalStateException("unavailable")),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                    ),
                )
            assertFailsWith<IllegalStateException> {
                qualityCapablePlanner(localFallbackRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(source),
                    maxStreamingBitrate = 4_000_000L,
                    qualityCapOrigin = PlaybackQualityCapOrigin.ExplicitSessionChoice,
                )
            }

            assertEquals(1, localFallbackRepository.requestPolicies.size)
            assertEquals(4_000_000L, localFallbackRepository.requests.single().maxStreamingBitrate)
        }

    @Test
    fun plannerSnapshotsTheUserResolutionLimitAcrossForcedRecoveryRequests() =
        runTest {
            val source = sourceVideo(width = 3_840, height = 2_160)
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source)),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                    ),
                )
            val settingsStore =
                object : PlayerDeviceSettingsStore {
                    override val settings =
                        MutableStateFlow(
                            PlayerDeviceSettings(maxVideoResolution = PlayerVideoResolutionLimit.Height1080),
                        )

                    override suspend fun setSettings(settings: PlayerDeviceSettings) {
                        this.settings.value = settings
                    }
                }

            val plan =
                PlaybackInfoPlanner(
                    mediaRepository = repository,
                    directPlayPlanner = DirectPlayPlanner(),
                    playerDeviceSettingsStore = settingsStore,
                ).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(source),
                )

            assertEquals(StreamMode.Transcode, plan.streamMode)
            assertEquals(PlaybackClientTrigger.UserResolutionLimit, plan.clientTrigger)
            assertEquals(PlaybackResolutionPolicy.UserSetting, plan.resolutionPolicy)
            assertEquals(2, repository.requestPolicies.size)
            repository.requestPolicies.forEach { requestPolicy ->
                assertEquals(VideoCodecResolution(1_920, 1_080), requestPolicy.userVideoResolutionCap)
                assertTrue(requestPolicy.userVideoResolutionCapIsResolved)
            }
            assertTrue(plan.streamUrl.contains("MaxWidth=1920"))
            assertTrue(plan.streamUrl.contains("MaxHeight=1080"))
        }

    @Test
    fun tiedDeviceAndUserBoundsKeepDecodeCapabilityAttribution() =
        runTest {
            val source = sourceVideo(width = 3_840, height = 2_160)
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source)),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                    ),
                )
            val settingsStore =
                object : PlayerDeviceSettingsStore {
                    override val settings =
                        MutableStateFlow(
                            PlayerDeviceSettings(maxVideoResolution = PlayerVideoResolutionLimit.Height1080),
                        )

                    override suspend fun setSettings(settings: PlayerDeviceSettings) {
                        this.settings.value = settings
                    }
                }

            val plan =
                PlaybackInfoPlanner(
                    mediaRepository = repository,
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                                DeviceDecodingCapabilities(
                                    videoCodecs = listOf("h264"),
                                    audioCodecs = listOf("aac"),
                                    supportsDolbyVision = false,
                                    videoResolutionsByCodec =
                                        mapOf(
                                            "h264" to
                                                VideoCodecResolution(
                                                    maxWidth = 1_920,
                                                    maxHeight = 1_080,
                                                    maxFrameArea = blockPaddedArea(1_920, 1_080),
                                                    maxFrameAreaPerSecond = blockPaddedArea(1_920, 1_080) * 30L,
                                                ),
                                        ),
                                )
                        },
                    playerDeviceSettingsStore = settingsStore,
                ).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(source),
                )

            assertEquals(StreamMode.Transcode, plan.streamMode)
            assertEquals(PlaybackClientTrigger.DecodeCapabilityCap, plan.clientTrigger)
            assertEquals(PlaybackResolutionPolicy.VerifiedDeviceAndUserSetting, plan.resolutionPolicy)
        }

    @Test
    fun macOsLibVlcStandardEnvelopeUsesAreaThroughputForLocalCopyDecisions() =
        runTest {
            listOf(
                sourceVideo(width = 3_840, height = 2_160, frameRate = 60.0),
                sourceVideo(width = 1_920, height = 1_080, frameRate = 120.0),
            ).forEach { source ->
                val repository =
                    SequencedPlaybackInfoRepository(
                        listOf(Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source))),
                    )

                val plan =
                    standard4k60EnvelopePlanner(repository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        detailMediaStreams = listOf(source),
                        requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                    )

                assertEquals(StreamMode.DirectPlay, plan.streamMode)
                assertEquals(1, repository.requestPolicies.size)
            }

            listOf<PlaybackQualityPolicy?>(null, PlaybackQualityPolicy.fixed(80_000_000L)).forEach { qualityPolicy ->
                val source = sourceVideo(width = 3_840, height = 2_160, frameRate = 120.0)
                val repository =
                    SequencedPlaybackInfoRepository(
                        listOf(
                            Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source)),
                            Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                        ),
                    )

                val plan =
                    standard4k60EnvelopePlanner(repository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        detailMediaStreams = listOf(source),
                        qualityPolicy = qualityPolicy,
                        qualityCapOrigin =
                            PlaybackQualityCapOrigin.ExplicitSessionChoice.takeIf { qualityPolicy != null },
                        requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                    )

                assertEquals(StreamMode.Transcode, plan.streamMode)
                assertEquals(2, repository.requestPolicies.size)
                assertFalse(repository.requestPolicies.last().allowVideoStreamCopy)
                assertEquals(60, repository.requestPolicies.last().maxFramerate)
            }

            val originalSource = sourceVideo(width = 3_840, height = 2_160, frameRate = 120.0)
            val originalRepository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, originalSource))),
                )
            val failure =
                assertFailsWith<PlaybackPlanningException.SourceVideoCopyUnsupported> {
                    standard4k60EnvelopePlanner(originalRepository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        detailMediaStreams = listOf(originalSource),
                        qualityPolicy = PlaybackQualityPolicy.Original,
                        requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.LibVlc),
                    )
                }
            assertNull(failure.cause)
            assertEquals(1, originalRepository.requestPolicies.size)
        }

    @Test
    fun explicitFireTvRangeExclusionRecoversAutoAndFixedCopyResponses() =
        runTest {
            listOf(StreamMode.DirectPlay, StreamMode.DirectStream).forEach { streamMode ->
                listOf<PlaybackQualityPolicy?>(null, PlaybackQualityPolicy.fixed(8_000_000L)).forEach { qualityPolicy ->
                    val source = fireTvExcludedSource()
                    val repository =
                        SequencedPlaybackInfoRepository(
                            listOf(
                                Result.success(copyModePlaybackInfo(streamMode, source)),
                                Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                            ),
                        )

                    val plan =
                        fireTvRangeExcludedPlanner(repository).plan(
                            session = testSession(),
                            itemId = "item",
                            mediaSourceId = "source",
                            startPositionTicks = 0L,
                            detailMediaStreams = listOf(source),
                            qualityPolicy = qualityPolicy,
                            qualityCapOrigin =
                                PlaybackQualityCapOrigin.ExplicitSessionChoice.takeIf { qualityPolicy != null },
                        )

                    assertEquals(StreamMode.Transcode, plan.streamMode)
                    assertEquals(2, repository.requestPolicies.size)
                    assertFalse(repository.requestPolicies.last().enableDirectPlay)
                    assertFalse(repository.requestPolicies.last().enableDirectStream)
                    assertFalse(repository.requestPolicies.last().allowVideoStreamCopy)
                }
            }
        }

    @Test
    fun explicitFireTvRangeExclusionMakesOriginalFailClosedWithoutRecovery() =
        runTest {
            val source = fireTvExcludedSource()
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source))),
                )

            val failure =
                assertFailsWith<PlaybackPlanningException.SourceVideoCopyUnsupported> {
                    fireTvRangeExcludedPlanner(repository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        detailMediaStreams = listOf(source),
                        qualityPolicy = PlaybackQualityPolicy.Original,
                    )
                }

            assertNull(failure.cause)
            assertEquals(1, repository.requestPolicies.size)
        }

    @Test
    fun explicitFireTvRangeExclusionBlocksTheAutoLocalFallbackOnly() =
        runTest {
            val source = fireTvExcludedSource()
            val autoRepository =
                SequencedPlaybackInfoRepository(
                    listOf(
                        Result.failure(IllegalStateException("initial unavailable")),
                        Result.success(copyModePlaybackInfo(StreamMode.Transcode, source)),
                    ),
                )

            val plan =
                fireTvRangeExcludedPlanner(autoRepository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(source),
                )

            assertEquals(StreamMode.Transcode, plan.streamMode)
            assertEquals(2, autoRepository.requestPolicies.size)
            assertFalse(autoRepository.requestPolicies.last().allowVideoStreamCopy)

            listOf(PlaybackQualityPolicy.fixed(8_000_000L), PlaybackQualityPolicy.Original).forEach { qualityPolicy ->
                val repository =
                    SequencedPlaybackInfoRepository(
                        listOf(Result.failure(IllegalStateException("initial unavailable"))),
                    )
                assertFailsWith<IllegalStateException> {
                    fireTvRangeExcludedPlanner(repository).plan(
                        session = testSession(),
                        itemId = "item",
                        mediaSourceId = "source",
                        startPositionTicks = 0L,
                        detailMediaStreams = listOf(source),
                        qualityPolicy = qualityPolicy,
                    )
                }
                assertEquals(1, repository.requestPolicies.size)
            }
        }

    @Test
    fun unrelatedFireTvRangeStillDirectPlays() =
        runTest {
            val source =
                sourceVideo(
                    width = 1_920,
                    height = 1_080,
                    codec = "hevc",
                    videoRangeType = "DOVIWithHDR10",
                )
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source))),
                )

            val plan =
                fireTvRangeExcludedPlanner(repository).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(source),
                )

            assertEquals(StreamMode.DirectPlay, plan.streamMode)
            assertEquals(1, repository.requestPolicies.size)
        }

    @Test
    fun unrestrictedCompatibilityBypassesOnlyTheAppOwnedPreflightEnvelope() =
        runTest {
            val source = sourceVideo(width = 3_840, height = 2_160)
            val repository =
                SequencedPlaybackInfoRepository(
                    listOf(Result.success(copyModePlaybackInfo(StreamMode.DirectPlay, source))),
                )
            val settingsStore =
                object : PlayerDeviceSettingsStore {
                    override val settings =
                        MutableStateFlow(
                            PlayerDeviceSettings(
                                iosPlaybackCompatibilityMode = IosPlaybackCompatibilityMode.Unrestricted,
                            ),
                        )

                    override suspend fun setSettings(settings: PlayerDeviceSettings) {
                        this.settings.value = settings
                    }
                }

            val plan =
                PlaybackInfoPlanner(
                    mediaRepository = repository,
                    directPlayPlanner = DirectPlayPlanner(),
                    deviceProfileProvider =
                        object : DeviceProfileProvider {
                            override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                                DeviceDecodingCapabilities(
                                    videoCodecs = listOf("h264"),
                                    audioCodecs = listOf("aac"),
                                    supportsDolbyVision = false,
                                    videoResolutionsByCodec =
                                        mapOf(
                                            "h264" to
                                                VideoCodecResolution(
                                                    maxWidth = 1_920,
                                                    maxHeight = 1_080,
                                                    maxFrameArea = blockPaddedArea(1_920, 1_080),
                                                    maxFrameAreaPerSecond = blockPaddedArea(1_920, 1_080) * 30L,
                                                ),
                                        ),
                                    hasUserOverridableVideoInputEnvelope = true,
                                )
                        },
                    playerDeviceSettingsStore = settingsStore,
                ).plan(
                    session = testSession(),
                    itemId = "item",
                    mediaSourceId = "source",
                    startPositionTicks = 0L,
                    detailMediaStreams = listOf(source),
                )

            assertEquals(StreamMode.DirectPlay, plan.streamMode)
            assertEquals(null, plan.clientTrigger)
            assertEquals(1, repository.requestPolicies.size)
        }
}

private fun boundedPlanner(repository: MediaRepository): PlaybackInfoPlanner =
    PlaybackInfoPlanner(
        mediaRepository = repository,
        directPlayPlanner = DirectPlayPlanner(),
        deviceProfileProvider =
            object : DeviceProfileProvider {
                override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        videoResolutionsByCodec =
                            mapOf(
                                "h264" to
                                    VideoCodecResolution(
                                        maxWidth = 1_920,
                                        maxHeight = 1_080,
                                        maxFrameArea = blockPaddedArea(1_920, 1_080),
                                        maxFrameAreaPerSecond = blockPaddedArea(1_920, 1_080) * 30L,
                                    ),
                            ),
                    )
            },
    )

private fun standard4k60EnvelopePlanner(repository: MediaRepository): PlaybackInfoPlanner =
    PlaybackInfoPlanner(
        mediaRepository = repository,
        directPlayPlanner = DirectPlayPlanner(),
        deviceProfileProvider =
            object : DeviceProfileProvider {
                override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        videoResolutionsByCodec =
                            mapOf(
                                "h264" to
                                    VideoCodecResolution(
                                        maxWidth = 3_840,
                                        maxHeight = 2_160,
                                        maxFrameArea = 8_294_400L,
                                        maxFrameAreaPerSecond = 497_664_000L,
                                    ),
                            ),
                        hasUserOverridableVideoInputEnvelope = true,
                    )
            },
    )

private fun fireTvRangeExcludedPlanner(repository: MediaRepository): PlaybackInfoPlanner =
    PlaybackInfoPlanner(
        mediaRepository = repository,
        directPlayPlanner = DirectPlayPlanner(),
        deviceProfileProvider =
            object : DeviceProfileProvider {
                override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("hevc"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = true,
                        unsupportedVideoRangeTypesByCodec =
                            mapOf(
                                "hevc" to
                                    setOf(
                                        "DOVIWithHDR10Plus",
                                        "DOVIWithELHDR10Plus",
                                    ),
                            ),
                    )
            },
    )

private fun fireTvExcludedSource(): PlaybackMediaStream =
    sourceVideo(
        width = 1_920,
        height = 1_080,
        codec = "H265",
        videoRangeType = "dovi-with-hdr10-plus",
    )

private fun qualityCapablePlanner(repository: MediaRepository): PlaybackInfoPlanner =
    PlaybackInfoPlanner(
        mediaRepository = repository,
        directPlayPlanner = DirectPlayPlanner(),
        deviceProfileProvider =
            object : DeviceProfileProvider {
                override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        videoResolutionsByCodec =
                            mapOf(
                                "h264" to
                                    VideoCodecResolution(
                                        maxWidth = 3_840,
                                        maxHeight = 2_160,
                                        maxFrameArea = blockPaddedArea(3_840, 2_160),
                                        maxFrameAreaPerSecond = blockPaddedArea(3_840, 2_160) * 30L,
                                    ),
                            ),
                    )
            },
    )

private fun copyModePlaybackInfo(
    streamMode: StreamMode,
    source: PlaybackMediaStream,
    includeTranscode: Boolean = false,
    mediaSourceId: String? = "source",
): PlaybackInfo {
    val isTranscode = streamMode == StreamMode.Transcode
    return PlaybackInfo(
        playSessionId = "play-session",
        mediaSources =
            listOf(
                PlaybackMediaSourceInfo(
                    id = mediaSourceId,
                    supportsDirectPlay = streamMode == StreamMode.DirectPlay,
                    supportsDirectStream = streamMode == StreamMode.DirectStream,
                    supportsTranscoding = isTranscode || includeTranscode,
                    transcodingUrl =
                        if (isTranscode || includeTranscode) {
                            "/Videos/item/master.m3u8?VideoCodec=h264&ApiKey=secret"
                        } else {
                            null
                        },
                    container = "mkv",
                    transcodingContainer = if (streamMode == StreamMode.DirectStream) "mp4" else "ts",
                    transcodingSubProtocol = if (isTranscode || includeTranscode) "hls" else null,
                    bitrate = 12_000_000L,
                    mediaStreams = listOf(source),
                ),
            ),
    )
}

private fun PlaybackInfo.withTranscodeReasons(vararg reasons: String): PlaybackInfo =
    copy(
        mediaSources =
            mediaSources.mapIndexed { index, source ->
                if (index == 0) source.copy(transcodeReasons = reasons.toList()) else source
            },
    )

private fun sourceVideo(
    width: Int?,
    height: Int?,
    frameRate: Double? = 30.0,
    codec: String = "h264",
    videoRangeType: String? = null,
): PlaybackMediaStream =
    PlaybackMediaStream(
        index = 0,
        type = "Video",
        displayTitle = null,
        title = null,
        language = null,
        codec = codec,
        channelLayout = null,
        bitRate = 12_000_000L,
        height = height,
        isDefault = true,
        isExternal = false,
        deliveryMethod = null,
        deliveryUrl = null,
        width = width,
        realFrameRate = frameRate,
        videoRangeType = videoRangeType,
    )

private class StaticPlaybackInfoRepository(
    private val playbackInfo: PlaybackInfo,
) : MediaRepository by FailingPlaybackInfoRepository() {
    var lastMaxStreamingBitrate: Long? = null
    val requestedMaxStreamingBitrates = mutableListOf<Long?>()
    val requestedPolicies = mutableListOf<PlaybackInfoRequestPolicy>()

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> = Result.success(playbackInfo)

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfo> {
        lastMaxStreamingBitrate = maxStreamingBitrate
        requestedMaxStreamingBitrates += maxStreamingBitrate
        requestedPolicies += requestPolicy
        return Result.success(playbackInfo)
    }
}

private class SequencedPlaybackInfoRepository(
    private val responses: List<Result<PlaybackInfo>>,
) : MediaRepository by FailingPlaybackInfoRepository() {
    val requestPolicies = mutableListOf<PlaybackInfoRequestPolicy>()
    val requests = mutableListOf<RecordedPlaybackInfoRequest>()

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfo> {
        requestPolicies += requestPolicy
        requests +=
            RecordedPlaybackInfoRequest(
                mediaSourceId = mediaSourceId,
                startTimeTicks = startTimeTicks,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                maxStreamingBitrate = maxStreamingBitrate,
                requestPolicy = requestPolicy,
            )
        return responses.getOrElse(requestPolicies.lastIndex) { responses.last() }
    }
}

private data class RecordedPlaybackInfoRequest(
    val mediaSourceId: String?,
    val startTimeTicks: Long,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val maxStreamingBitrate: Long?,
    val requestPolicy: PlaybackInfoRequestPolicy,
)

private class RetryFailingPlaybackInfoRepository(
    private val firstPlaybackInfo: PlaybackInfo,
) : MediaRepository by FailingPlaybackInfoRepository() {
    val requestedMaxStreamingBitrates = mutableListOf<Long?>()

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfo> {
        requestedMaxStreamingBitrates += maxStreamingBitrate
        return if (requestedMaxStreamingBitrates.size == 1) {
            Result.success(firstPlaybackInfo)
        } else {
            Result.failure(IllegalStateException("retry failed"))
        }
    }
}

private fun testSession() = Session("https://jellyfin.example", "server", "Jellyfin", "user", "User", "token", "device")

private class FailingPlaybackInfoRepository : MediaRepository {
    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(seriesId: String?): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> = Result.failure(UnsupportedOperationException())

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getFavorites(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun search(query: FindQuery): Result<FindResults> = Result.success(FindResults())

    override suspend fun findPersons(term: String): Result<List<Person>> = Result.success(emptyList())

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> = Result.failure(IllegalStateException())

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> = Result.failure(UnsupportedOperationException())
}
