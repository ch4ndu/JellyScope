// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.VideoRangeCapabilities
import com.jellyscope.core.domain.playback.VideoRangeTypePolicy
import com.jellyscope.core.domain.playback.reconcileVideoResolutionBounds
import com.jellyscope.core.domain.playback.resolvePlayerDevicePolicy
import com.jellyscope.core.domain.playback.subtitleFormatsEquivalent
import com.jellyscope.core.domain.playback.toExactBitrateConstraint

fun buildDeviceProfile(
    capabilities: DeviceDecodingCapabilities,
    maxStreamingBitrate: Long?,
    playerSettings: PlayerDeviceSettings = PlayerDeviceSettings(),
    requestPolicy: PlaybackInfoRequestPolicy = PlaybackInfoRequestPolicy(),
    bitrateConstraint: PlaybackBitrateConstraint? = null,
): PlaybackDeviceProfileDto {
    val effectiveConstraint =
        bitrateConstraint
            ?: requestPolicy.bitrateConstraint.takeUnless { constraint ->
                constraint == PlaybackBitrateConstraint.NoClientLimit
            }
            ?: maxStreamingBitrate.toExactBitrateConstraint()
    val wireBitrate = effectiveConstraint.bitrateBps
    val outputBitrate =
        effectiveConstraint.bitrateBps.takeUnless {
            effectiveConstraint == PlaybackBitrateConstraint.NoClientLimit
        }
    val userVideoResolutionCap =
        if (requestPolicy.userVideoResolutionCapIsResolved) {
            requestPolicy.userVideoResolutionCap
        } else {
            requestPolicy.userVideoResolutionCap ?: playerSettings.maxVideoResolution.resolutionCap
        }
    val effectiveRequestPolicy =
        requestPolicy.copy(
            userVideoResolutionCap = userVideoResolutionCap,
            userVideoResolutionCapIsResolved = true,
        )
    val policy =
        resolvePlayerDevicePolicy(
            capabilities = capabilities,
            settings = playerSettings,
        )
    val effectiveCapabilities = policy.capabilities
    val videoCodecs = effectiveCapabilities.videoCodecs.ifEmpty { baselineVideoCodecs }
    val audioCodecs = policy.audioCodecs.ifEmpty { baselineAudioCodecs }
    val transcodingAudioCodecs =
        hlsTsAudioCodecs.filter { codec -> codec in audioCodecs }.ifEmpty { listOf("aac") }

    return PlaybackDeviceProfileDto(
        directPlayProfiles =
            (
                effectiveCapabilities.directPlayProfiles
                    .map { profile ->
                        DirectPlayProfileDto(
                            type = "Video",
                            container = profile.containers.joinToString(","),
                            videoCodec = profile.videoCodecs.filter { it in videoCodecs }.joinToString(","),
                            audioCodec = profile.audioCodecs.filter { it in audioCodecs }.joinToString(","),
                        )
                    }.filter { profile -> profile.container.isNotBlank() && !profile.videoCodec.isNullOrBlank() }
                    .takeIf { effectiveCapabilities.directPlayProfiles.isNotEmpty() }
                    ?: listOf(
                        DirectPlayProfileDto(
                            type = "Video",
                            container = "mp4,m4v",
                            videoCodec = videoCodecs.joinToString(","),
                            audioCodec = audioCodecs.joinToString(","),
                        ),
                    )
            ) +
                listOf(
                    DirectPlayProfileDto(
                        type = "Audio",
                        container = "mp3,aac,flac,ogg",
                        videoCodec = null,
                        audioCodec = audioCodecs.joinToString(","),
                    ),
                ),
        transcodingProfiles =
            listOf(
                TranscodingProfileDto(
                    container = "ts",
                    type = "Video",
                    protocol = "hls",
                    videoCodec = "h264",
                    audioCodec = transcodingAudioCodecs.joinToString(","),
                    context = "Streaming",
                    maxAudioChannels = policy.maxAudioChannels,
                    enableSubtitlesInManifest = true,
                    maxBitrate = outputBitrate,
                ),
            ),
        codecProfiles =
            videoCodecProfiles(
                capabilities = effectiveCapabilities,
                policy = policy,
                videoCodecs = videoCodecs,
                bitrateConstraint = effectiveConstraint,
                requestPolicy = effectiveRequestPolicy,
            ) + audioCodecProfiles(policy),
        subtitleProfiles = subtitleProfiles(effectiveCapabilities, effectiveRequestPolicy),
        maxStreamingBitrate = wireBitrate,
        maxStaticBitrate = wireBitrate,
    )
}

private fun subtitleProfiles(
    capabilities: DeviceDecodingCapabilities,
    requestPolicy: PlaybackInfoRequestPolicy,
): List<SubtitleProfileDto> {
    val forcedFormat = requestPolicy.forceEncodeSubtitle?.normalizedFormat
    val profiles =
        capabilities.subtitleProfiles.flatMap { profile ->
            val methods =
                if (subtitleFormatsEquivalent(profile.format, forcedFormat)) {
                    listOf(SubtitleDeliveryMethod.Encode)
                } else {
                    profile.deliveryMethods
                }
            methods.distinct().map { method ->
                SubtitleProfileDto(
                    format = profile.format,
                    method = method.toJellyfinMethod(),
                )
            }
        }
    return if (forcedFormat != null && profiles.none { profile -> subtitleFormatsEquivalent(profile.format, forcedFormat) }) {
        profiles + SubtitleProfileDto(format = forcedFormat, method = "Encode")
    } else {
        profiles
    }
}

private fun SubtitleDeliveryMethod.toJellyfinMethod(): String =
    when (this) {
        SubtitleDeliveryMethod.Hls -> "Hls"
        else -> name
    }

private fun videoCodecProfiles(
    capabilities: DeviceDecodingCapabilities,
    policy: EffectivePlayerDevicePolicy,
    videoCodecs: List<String>,
    bitrateConstraint: PlaybackBitrateConstraint,
    requestPolicy: PlaybackInfoRequestPolicy,
): List<CodecProfileDto> {
    val codecOrder = videoCodecs.distinct()

    return codecOrder.map { codec ->
        val conditions =
            buildList {
                add(
                    ProfileConditionDto(
                        condition = "EqualsAny",
                        property = "VideoRangeType",
                        value =
                            VideoRangeTypePolicy
                                .supportedRangeTypes(
                                    capabilities = capabilities.videoRangeCapabilitiesByCodec[codec] ?: VideoRangeCapabilities(),
                                    preferSdr = policy.effectiveHdrMode == PlayerHdrMode.PreferSdr,
                                    unsupportedRangeTypes =
                                        VideoRangeTypePolicy.unsupportedRangeTypesForCodec(
                                            codec = codec,
                                            unsupportedRangeTypesByCodec = capabilities.unsupportedVideoRangeTypesByCodec,
                                        ),
                                ).joinToString("|"),
                        isRequired = true,
                    ),
                )
                val effectiveResolution =
                    reconcileVideoResolutionBounds(
                        deviceCeiling = capabilities.videoResolutionsByCodec[codec],
                        qualityRungCeiling = requestPolicy.qualityResolutionCap,
                        userResolutionCeiling = requestPolicy.userVideoResolutionCap,
                    )
                effectiveResolution?.maxWidth?.takeIf { width -> width > 0 }?.let { width ->
                    add(
                        ProfileConditionDto(
                            condition = "LessThanEqual",
                            property = "Width",
                            value = width.toString(),
                            isRequired = true,
                        ),
                    )
                }
                effectiveResolution?.maxHeight?.takeIf { height -> height > 0 }?.let { height ->
                    add(
                        ProfileConditionDto(
                            condition = "LessThanEqual",
                            property = "Height",
                            value = height.toString(),
                            isRequired = true,
                        ),
                    )
                }
                capabilities.videoConstraintsByCodec[codec]?.let { constraints ->
                    constraints.supportedProfiles.takeIf { profiles -> profiles.isNotEmpty() }?.let { profiles ->
                        add(ProfileConditionDto("EqualsAny", "VideoProfile", profiles.joinToString("|"), true))
                    }
                    constraints.maxLevel?.let { level ->
                        add(ProfileConditionDto("LessThanEqual", "VideoLevel", level.toString(), true))
                    }
                    constraints.maxBitDepth?.let { bitDepth ->
                        add(ProfileConditionDto("LessThanEqual", "VideoBitDepth", bitDepth.toString(), true))
                    }
                    constraints.requiredCodecTags.takeIf { tags -> tags.isNotEmpty() }?.let { tags ->
                        add(ProfileConditionDto("EqualsAny", "VideoCodecTag", tags.joinToString("|"), true))
                    }
                    constraints.maxVideoFrameRate?.let { frameRate ->
                        add(ProfileConditionDto("LessThanEqual", "VideoFramerate", frameRate.toString(), true))
                    }
                    if (constraints.allowsInterlaced == false) {
                        add(ProfileConditionDto("NotEquals", "IsInterlaced", "true", true))
                    }
                }
                when (bitrateConstraint) {
                    is PlaybackBitrateConstraint.ExactUserLimit,
                    is PlaybackBitrateConstraint.AutoSessionLimit,
                    -> bitrateConstraint.bitrateBps
                    PlaybackBitrateConstraint.NoClientLimit -> null
                }?.let { bitrate ->
                    add(
                        ProfileConditionDto(
                            condition = "LessThanEqual",
                            property = "VideoBitrate",
                            value = bitrate.toString(),
                            isRequired = true,
                        ),
                    )
                }
            }

        CodecProfileDto(
            type = "Video",
            codec = codec,
            conditions = conditions,
        )
    }
}

private fun audioCodecProfiles(policy: EffectivePlayerDevicePolicy): List<CodecProfileDto> {
    val codecGroupsByChannelCeiling =
        policy.audioCodecs
            .ifEmpty { baselineAudioCodecs }
            .distinct()
            .mapNotNull { codec ->
                policy.maxAudioChannelsByCodec[codec]
                    ?.takeIf { channels -> channels > 0 }
                    ?.let { channels -> codec to channels }
            }.groupBy(
                keySelector = { (_, channels) -> channels },
                valueTransform = { (codec, _) -> codec },
            )

    return codecGroupsByChannelCeiling.flatMap { (maxAudioChannels, codecs) ->
        val condition =
            ProfileConditionDto(
                condition = "LessThanEqual",
                property = "AudioChannels",
                value = maxAudioChannels.toString(),
                isRequired = true,
            )
        listOf(
            CodecProfileDto(
                type = "Audio",
                codec = codecs.joinToString(","),
                conditions = listOf(condition),
            ),
            CodecProfileDto(
                type = "VideoAudio",
                codec = codecs.joinToString(","),
                conditions = listOf(condition),
            ),
        )
    }
}

private val baselineVideoCodecs = listOf("h264")
private val baselineAudioCodecs = listOf("aac", "mp3")
private val hlsTsAudioCodecs = listOf("aac", "mp3", "ac3", "eac3")
