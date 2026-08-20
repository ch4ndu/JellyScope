// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

internal data class DeviceProfileDeclaration(
    val containers: List<String>,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
)

internal val vlcKitDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers =
            listOf(
                "mkv",
                "webm",
                "mp4",
                "m4v",
                "mov",
                "mpegts",
                "ts",
                "m2ts",
                "avi",
                "flv",
                "3gp",
                "ogv",
                "asf",
                "wmv",
            ),
        videoCodecs =
            listOf(
                "h264",
                "hevc",
                "vp8",
                "vp9",
                "mpeg1video",
                "mpeg2video",
                "mpeg4",
                "msmpeg4v1",
                "msmpeg4v2",
                "msmpeg4v3",
                "vc1",
                "wmv1",
                "wmv2",
                "wmv3",
                "prores",
                "theora",
                "dirac",
                "dv",
                "ffv1",
                "flv1",
                "h261",
                "h263",
                "mjpeg",
                "av1",
            ),
        // MobileVLCKit exposes DTS under both Jellyfin spellings. It does not
        // ship TrueHD/MLP support in the validated 3.7.3 binary.
        audioCodecs =
            listOf(
                "aac",
                "ac3",
                "alac",
                "amr_nb",
                "amr_wb",
                "dts",
                "dca",
                "eac3",
                "flac",
                "mp1",
                "mp2",
                "mp3",
                "nellymoser",
                "opus",
                "pcm_alaw",
                "pcm_bluray",
                "pcm_dvd",
                "pcm_mulaw",
                "pcm_s16be",
                "pcm_s16le",
                "pcm_s24be",
                "pcm_s24le",
                "pcm_u8",
                "speex",
                "vorbis",
                "wavpack",
                "wmalossless",
                "wmapro",
                "wmav1",
                "wmav2",
            ),
    )

internal val androidDirectPlayDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "mpegts", "ts"),
        videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
        audioCodecs = listOf("aac", "mp3", "ac3", "eac3", "opus", "flac", "vorbis", "pcm", "dts", "dca"),
    )

/**
 * Conservative DirectPlay declaration for the pinned Android libmpv build.
 *
 * This is intentionally separate from Media3 and LibVLC: mpv's FFmpeg build
 * can decode the additional software-codec families below, while the initial
 * Android controller delegates only the listed video families to MediaCodec.
 */
internal val androidMpvDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("avi", "mkv", "m2ts", "mov", "mp4", "mpegts", "ts", "webm"),
        videoCodecs = listOf("av1", "h264", "hevc", "mpeg2video", "mpeg4", "vp8", "vp9"),
        audioCodecs =
            listOf(
                "aac",
                "ac3",
                "dca",
                "dts",
                "eac3",
                "flac",
                "mp3",
                "opus",
                "pcm",
                "truehd",
                "vorbis",
            ),
    )

internal val androidLibVlcDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("avi", "mkv", "mov", "mp4", "mpegts", "ts", "webm"),
        videoCodecs = listOf("h264", "hevc", "mpeg1video", "mpeg2video", "vp8", "vp9", "av1"),
        audioCodecs = listOf("aac", "ac3", "eac3", "flac", "mp3", "opus", "vorbis"),
    )

internal val desktopMpvDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "mpegts", "ts", "avi"),
        videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
        audioCodecs = listOf("aac", "mp3", "flac", "ac3", "eac3", "opus", "vorbis", "pcm", "dts", "dca", "truehd", "mp2"),
    )

internal val desktopLibVlcDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "mpegts", "ts", "avi"),
        videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
        audioCodecs = listOf("aac", "mp3", "flac", "ac3", "eac3", "opus", "vorbis", "pcm", "dts", "dca", "truehd", "mp2"),
    )

internal val appleDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("mp4", "m4v", "mov", "mpegts", "ts"),
        videoCodecs = listOf("h264", "hevc", "av1"),
        audioCodecs = listOf("aac", "mp3", "ac3", "eac3", "flac", "alac"),
    )

internal val diagnosticsDeviceProfileDeclarations =
    listOf(
        vlcKitDeviceProfileDeclaration,
        androidDirectPlayDeviceProfileDeclaration,
        androidMpvDeviceProfileDeclaration,
        androidLibVlcDeviceProfileDeclaration,
        desktopMpvDeviceProfileDeclaration,
        desktopLibVlcDeviceProfileDeclaration,
        appleDeviceProfileDeclaration,
    )

internal val androidDirectPlayProfiles =
    listOf(
        DeviceDirectPlayProfile(
            containers = androidDirectPlayDeviceProfileDeclaration.containers,
            videoCodecs = androidDirectPlayDeviceProfileDeclaration.videoCodecs,
            audioCodecs = androidDirectPlayDeviceProfileDeclaration.audioCodecs,
        ),
    )
