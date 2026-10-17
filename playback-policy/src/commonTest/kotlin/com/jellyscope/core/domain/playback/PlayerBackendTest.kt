// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlayerBackendTest {
    @Test
    fun policyRejectsDefaultBackendOutsideVisibleBackends() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PlayerBackendPolicy(
                    platform = PlayerBackendPlatform.Android,
                    defaultBackend = PlayerBackend.ExoPlayer,
                    visibleBackends = listOf(PlayerBackend.Mpv, PlayerBackend.LibVlc),
                )
            }

        assertEquals("The default player backend must be visible on its platform", failure.message)
    }

    @Test
    fun productionPoliciesPreservePlatformDefaultsVisibilityAndOrder() {
        val expectedPolicies =
            listOf(
                Triple(
                    androidPlayerBackendPolicy(),
                    PlayerBackend.ExoPlayer,
                    listOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv, PlayerBackend.LibVlc),
                ),
                Triple(
                    applePlayerBackendPolicy(),
                    PlayerBackend.AVPlayer,
                    listOf(PlayerBackend.Auto, PlayerBackend.AVPlayer, PlayerBackend.VlcKit),
                ),
                Triple(
                    desktopPlayerBackendPolicy(),
                    PlayerBackend.Mpv,
                    listOf(PlayerBackend.Mpv, PlayerBackend.LibVlc),
                ),
            )

        expectedPolicies.forEach { (policy, expectedDefault, expectedVisibleBackends) ->
            assertEquals(expectedDefault, policy.defaultBackend)
            assertEquals(expectedVisibleBackends, policy.visibleBackends)
            assertTrue(policy.defaultBackend in policy.visibleBackends)
        }
    }

    @Test
    fun concreteItemOverrideWinsOverDefaultAndAutoPolicy() {
        assertEquals(
            PlayerBackend.VlcKit,
            resolvePlayerBackend(
                defaultBackend = PlayerBackend.AVPlayer,
                itemOverride = PlayerBackend.VlcKit,
                source = hdrMp4Source,
                avPlayerCapabilities = avPlayerCapabilities,
            ),
        )
    }

    @Test
    fun autoItemOverrideFallsThroughToConcreteDefault() {
        assertEquals(
            PlayerBackend.VlcKit,
            resolvePlayerBackend(
                defaultBackend = PlayerBackend.VlcKit,
                itemOverride = PlayerBackend.Auto,
                source = directPlayableMp4Source,
                avPlayerCapabilities = avPlayerCapabilities,
            ),
        )
    }

    @Test
    fun concreteDefaultWinsOverAutoPolicy() {
        assertEquals(
            PlayerBackend.AVPlayer,
            resolvePlayerBackend(
                defaultBackend = PlayerBackend.AVPlayer,
                itemOverride = null,
                source = mkvSource,
                avPlayerCapabilities = avPlayerCapabilities,
            ),
        )
    }

    @Test
    fun androidPolicyNormalizesLegacyAppleDefaultToExoPlayer() {
        assertEquals(
            PlayerBackend.ExoPlayer,
            resolvePlayerBackend(
                defaultBackend = PlayerBackend.AVPlayer,
                itemOverride = null,
                source = mkvSource,
                avPlayerCapabilities = avPlayerCapabilities,
                backendPolicy = androidPlayerBackendPolicy(),
            ),
        )
    }

    @Test
    fun androidPolicyKeepsExplicitLibVlcSelection() {
        assertEquals(
            PlayerBackend.LibVlc,
            resolvePlayerBackend(
                defaultBackend = PlayerBackend.AVPlayer,
                itemOverride = PlayerBackend.LibVlc,
                source = directPlayableMp4Source,
                avPlayerCapabilities = avPlayerCapabilities,
                backendPolicy = androidPlayerBackendPolicy(),
            ),
        )
    }

    @Test
    fun androidPolicyExposesExoMpvAndLibVlcChoices() {
        val policy = androidPlayerBackendPolicy()

        assertEquals(PlayerBackend.ExoPlayer, policy.defaultBackend)
        assertEquals(
            listOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv, PlayerBackend.LibVlc),
            policy.visibleBackends,
        )
        assertEquals(PlayerBackend.ExoPlayer, policy.normalizePersisted(PlayerBackend.Auto))
        assertEquals(PlayerBackend.ExoPlayer, policy.normalizePersisted(PlayerBackend.AVPlayer))
        assertEquals(PlayerBackend.ExoPlayer, policy.normalizePersisted(PlayerBackend.VlcKit))
        assertEquals(PlayerBackend.ExoPlayer, policy.normalizePersisted(PlayerBackend.ExoPlayer))
        assertEquals(PlayerBackend.Mpv, policy.normalizePersisted(PlayerBackend.Mpv))
        assertEquals(PlayerBackend.LibVlc, policy.normalizePersisted(PlayerBackend.LibVlc))
    }

    @Test
    fun desktopPolicyExposesMpvAndLibVlcAndNormalizesForeignValuesToMpv() {
        val policy = desktopPlayerBackendPolicy()

        assertEquals(PlayerBackend.Mpv, policy.defaultBackend)
        assertEquals(listOf(PlayerBackend.Mpv, PlayerBackend.LibVlc), policy.visibleBackends)
        assertEquals(PlayerBackend.Mpv, policy.normalizePersisted(null))
        assertEquals(PlayerBackend.Mpv, policy.normalizePersisted(PlayerBackend.AVPlayer))
        assertEquals(PlayerBackend.Mpv, policy.normalizePersisted(PlayerBackend.VlcKit))
        assertEquals(PlayerBackend.Mpv, policy.normalizePersisted(PlayerBackend.ExoPlayer))
        assertEquals(PlayerBackend.Mpv, policy.normalizePersisted(PlayerBackend.Mpv))
        assertEquals(PlayerBackend.LibVlc, policy.normalizePersisted(PlayerBackend.LibVlc))
    }

    @Test
    fun desktopSharedApplePreferenceDefaultResolvesToTheDesktopEngine() {
        assertEquals(
            PlayerBackend.Mpv,
            resolvePlayerBackend(
                defaultBackend = PlayerBackend.AVPlayer,
                itemOverride = null,
                source = mkvSource,
                avPlayerCapabilities = avPlayerCapabilities,
                backendPolicy = desktopPlayerBackendPolicy(),
            ),
        )
    }

    @Test
    fun desktopForeignItemOverrideNormalizesToMpv() {
        assertEquals(
            PlayerBackend.Mpv,
            resolvePlayerBackend(
                defaultBackend = PlayerBackend.AVPlayer,
                itemOverride = PlayerBackend.VlcKit,
                source = directPlayableMp4Source,
                avPlayerCapabilities = avPlayerCapabilities,
                backendPolicy = desktopPlayerBackendPolicy(),
            ),
        )
    }

    @Test
    fun desktopExplicitLibVlcItemOverrideIsPreserved() {
        assertEquals(
            PlayerBackend.LibVlc,
            resolvePlayerBackend(
                defaultBackend = PlayerBackend.Mpv,
                itemOverride = PlayerBackend.LibVlc,
                source = directPlayableMp4Source,
                avPlayerCapabilities = avPlayerCapabilities,
                backendPolicy = desktopPlayerBackendPolicy(),
            ),
        )
    }

    @Test
    fun desktopDefaultAvailabilityIncludesBothSelectableBackends() {
        val desktop = FakeBackendPolicyProvider(desktopPlayerBackendPolicy())

        assertEquals(setOf(PlayerBackend.Mpv, PlayerBackend.LibVlc), desktop.availableBackends)
    }

    @Test
    fun availableBackendsKeepsTheVisibleSetWhenBackendsAreSelectable() {
        val android = FakeBackendPolicyProvider(androidPlayerBackendPolicy())

        assertEquals(
            setOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv, PlayerBackend.LibVlc),
            android.availableBackends,
        )
    }

    @Test
    fun autoHdrRoutesToAvPlayer() {
        assertEquals(PlayerBackend.AVPlayer, resolveAuto(hdrMp4Source))
    }

    @Test
    fun autoDirectPlayableMp4RoutesToAvPlayer() {
        assertEquals(PlayerBackend.AVPlayer, resolveAuto(directPlayableMp4Source))
    }

    @Test
    fun autoMkvRoutesToVlcKit() {
        assertEquals(PlayerBackend.VlcKit, resolveAuto(mkvSource))
    }

    @Test
    fun autoVp9RoutesToVlcKit() {
        assertEquals(
            PlayerBackend.VlcKit,
            resolveAuto(directPlayableMp4Source.copy(videoCodec = "vp9")),
        )
    }

    @Test
    fun autoUnknownCodecRoutesToVlcKit() {
        assertEquals(
            PlayerBackend.VlcKit,
            resolveAuto(directPlayableMp4Source.copy(audioCodec = "unknown")),
        )
    }

    @Test
    fun autoHdrInMkvRoutesToAvPlayer() {
        assertEquals(PlayerBackend.AVPlayer, resolveAuto(mkvSource.copy(isHdrOrDolbyVision = true)))
    }

    @Test
    fun autoMatchesContainerAndCodecsCaseInsensitively() {
        assertEquals(
            PlayerBackend.AVPlayer,
            resolveAuto(
                BackendSourceDescriptor(
                    container = " MKV ",
                    videoCodec = " HEVC ",
                    audioCodec = " AAC ",
                    isHdrOrDolbyVision = false,
                ),
                capabilities =
                    capabilitiesWithProfile(
                        containers = listOf("mkv"),
                        videoCodecs = listOf("hevc"),
                        audioCodecs = listOf("aac"),
                    ),
            ),
        )
    }

    @Test
    fun autoWithMissingSourceMetadataRoutesToVlcKit() {
        val incompleteSources =
            listOf(
                directPlayableMp4Source.copy(container = null),
                directPlayableMp4Source.copy(container = " "),
                directPlayableMp4Source.copy(videoCodec = null),
                directPlayableMp4Source.copy(videoCodec = "\t"),
                directPlayableMp4Source.copy(audioCodec = null),
                directPlayableMp4Source.copy(audioCodec = "  "),
            )

        incompleteSources.forEach { source ->
            assertEquals(PlayerBackend.VlcKit, resolveAuto(source))
        }
    }

    @Test
    fun hdrRoutesToAvPlayerEvenWhenSourceMetadataIsMissing() {
        assertEquals(
            PlayerBackend.AVPlayer,
            resolveAuto(
                BackendSourceDescriptor(
                    container = null,
                    videoCodec = " ",
                    audioCodec = null,
                    isHdrOrDolbyVision = true,
                ),
            ),
        )
    }

    @Test
    fun autoWithEmptyDirectPlayProfilesRoutesToVlcKit() {
        assertEquals(
            PlayerBackend.VlcKit,
            resolveAuto(
                directPlayableMp4Source,
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                    ),
            ),
        )
    }

    @Test
    fun originalDownloadRejectsOnlyProvenDecoderIncompatibility() {
        val capabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("av1"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
                videoResolutionsByCodec =
                    mapOf(
                        "av1" to
                            VideoCodecResolution(
                                maxWidth = 4096,
                                maxHeight = 2160,
                                maxFrameArea = 4096L * 2160L,
                            ),
                    ),
            )

        assertEquals(
            OriginalDownloadPlaybackCompatibility.Unsupported,
            evaluateOriginalDownloadPlaybackCompatibility(
                source =
                    BackendSourceDescriptor(
                        container = "webm",
                        videoCodec = "av1",
                        audioCodec = "opus",
                        isHdrOrDolbyVision = false,
                        videoWidth = 7680,
                        videoHeight = 4320,
                    ),
                capabilities = capabilities,
            ),
        )
        assertEquals(
            OriginalDownloadPlaybackCompatibility.Compatible,
            evaluateOriginalDownloadPlaybackCompatibility(
                source = directPlayableMp4Source.copy(videoCodec = "av1", videoWidth = 1920, videoHeight = 1080),
                capabilities = capabilities,
            ),
        )
        assertEquals(
            OriginalDownloadPlaybackCompatibility.Unknown,
            evaluateOriginalDownloadPlaybackCompatibility(
                source = directPlayableMp4Source.copy(videoCodec = "av1"),
                capabilities = capabilities,
            ),
        )
    }

    private class FakeBackendPolicyProvider(
        override val backendPolicy: PlayerBackendPolicy,
    ) : DeviceProfileProvider {
        override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = avPlayerCapabilities
    }

    private fun resolveAuto(
        source: BackendSourceDescriptor,
        capabilities: DeviceDecodingCapabilities = avPlayerCapabilities,
    ): PlayerBackend =
        resolvePlayerBackend(
            defaultBackend = PlayerBackend.Auto,
            itemOverride = null,
            source = source,
            avPlayerCapabilities = capabilities,
        )

    private companion object {
        val directPlayableMp4Source =
            BackendSourceDescriptor(
                container = "mp4",
                videoCodec = "h264",
                audioCodec = "aac",
                isHdrOrDolbyVision = false,
            )

        val hdrMp4Source = directPlayableMp4Source.copy(isHdrOrDolbyVision = true)

        val mkvSource =
            BackendSourceDescriptor(
                container = "mkv",
                videoCodec = "hevc",
                audioCodec = "aac",
                isHdrOrDolbyVision = false,
            )

        val avPlayerCapabilities =
            capabilitiesWithProfile(
                containers = listOf("mp4"),
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
            )

        fun capabilitiesWithProfile(
            containers: List<String>,
            videoCodecs: List<String>,
            audioCodecs: List<String>,
        ): DeviceDecodingCapabilities =
            DeviceDecodingCapabilities(
                videoCodecs = videoCodecs,
                audioCodecs = audioCodecs,
                supportsDolbyVision = false,
                directPlayProfiles =
                    listOf(
                        DeviceDirectPlayProfile(
                            containers = containers,
                            videoCodecs = videoCodecs,
                            audioCodecs = audioCodecs,
                        ),
                    ),
            )
    }
}
