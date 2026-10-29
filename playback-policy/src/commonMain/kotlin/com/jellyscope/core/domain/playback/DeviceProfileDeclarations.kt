// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
data class DeviceProfileDeclaration(
    val containers: List<String>,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
)

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val vlcKitDeviceProfileDeclaration =
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
        // Historical rationale from the validated MobileVLCKit 3.7.3 binary: it
        // exposed DTS under both Jellyfin spellings and shipped no TrueHD/MLP
        // support. For the current VLCKit 4.0.0a25 pin, alac and every listed
        // pcm_* name except pcm_dvd have a compiled avcodec route to a
        // registered libavcodec decoder in every required iOS/tvOS slice. That
        // static check does not establish which module VLC selects at runtime
        // or that decoded audio is audible. The remaining entries, including
        // pcm_dvd, have not been requalified for 4.0.0a25.
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
                "pcm_f32be",
                "pcm_f32le",
                "pcm_f64be",
                "pcm_f64le",
                "pcm_mulaw",
                "pcm_s16be",
                "pcm_s16le",
                "pcm_s16le_planar",
                "pcm_s24be",
                "pcm_s24daud",
                "pcm_s24le",
                "pcm_s32be",
                "pcm_s32le",
                "pcm_s8",
                "pcm_u16be",
                "pcm_u16le",
                "pcm_u24be",
                "pcm_u24le",
                "pcm_u32be",
                "pcm_u32le",
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

// Exact FFmpeg codec_name values for Matroska PCM that the Media3 1.9.0
// MatroskaExtractor maps to an audio/raw encoding. Requalify when the Media3 pin changes.
private val androidMedia3MatroskaPcmAudioCodecs =
    listOf("pcm_u8", "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_s32be", "pcm_f32le")

internal val androidDirectPlayDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "mpegts", "ts"),
        videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
        audioCodecs =
            listOf("aac", "mp3", "ac3", "eac3", "opus", "flac", "vorbis") +
                androidMedia3MatroskaPcmAudioCodecs +
                listOf("dts", "dca"),
    )

/**
 * Conservative DirectPlay declaration for the pinned Android libmpv build.
 *
 * This is intentionally separate from Media3 and LibVLC: mpv's FFmpeg build
 * can decode the additional software-codec families below, while the initial
 * Android controller delegates only the listed video families to MediaCodec.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val androidMpvDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("avi", "mkv", "m2ts", "mov", "mp4", "mpegts", "ts", "webm"),
        videoCodecs = listOf("av1", "h264", "hevc", "mpeg2video", "mpeg4", "vp8", "vp9"),
        // PCM, ALAC and MLP use exact FFmpeg codec_name values with decoders
        // registered in every shipped ABI. There is no generic "pcm" codec name.
        audioCodecs =
            listOf(
                "aac",
                "ac3",
                "alac",
                "dca",
                "dts",
                "eac3",
                "flac",
                "mlp",
                "mp3",
                "opus",
                "pcm_alaw",
                "pcm_bluray",
                "pcm_dvd",
                "pcm_f16le",
                "pcm_f24le",
                "pcm_f32be",
                "pcm_f32le",
                "pcm_f64be",
                "pcm_f64le",
                "pcm_lxf",
                "pcm_mulaw",
                "pcm_s16be",
                "pcm_s16be_planar",
                "pcm_s16le",
                "pcm_s16le_planar",
                "pcm_s24be",
                "pcm_s24daud",
                "pcm_s24le",
                "pcm_s24le_planar",
                "pcm_s32be",
                "pcm_s32le",
                "pcm_s32le_planar",
                "pcm_s64be",
                "pcm_s64le",
                "pcm_s8",
                "pcm_s8_planar",
                "pcm_sga",
                "pcm_u16be",
                "pcm_u16le",
                "pcm_u24be",
                "pcm_u24le",
                "pcm_u32be",
                "pcm_u32le",
                "pcm_u8",
                "pcm_vidc",
                "truehd",
                "vorbis",
            ),
    )

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val androidLibVlcDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("avi", "mkv", "mov", "mp4", "mpegts", "ts", "webm"),
        videoCodecs = listOf("h264", "hevc", "mpeg1video", "mpeg2video", "vp8", "vp9", "av1"),
        // For the pinned libvlc-all 3.7.7 AAR, alac, mlp, truehd and every
        // listed pcm_* name have a compiled avcodec route to a registered
        // libavcodec decoder in all three shipped Android ABIs. MLP stays
        // distinct from TrueHD. That static check does not establish which
        // module VLC selects at runtime or that decoded audio is audible. The
        // other seven entries were not part of this qualification.
        audioCodecs =
            listOf(
                "aac",
                "ac3",
                "alac",
                "eac3",
                "flac",
                "mlp",
                "mp3",
                "opus",
                "pcm_alaw",
                "pcm_bluray",
                "pcm_f32be",
                "pcm_f32le",
                "pcm_f64be",
                "pcm_f64le",
                "pcm_mulaw",
                "pcm_s16be",
                "pcm_s16le",
                "pcm_s16le_planar",
                "pcm_s24be",
                "pcm_s24daud",
                "pcm_s24le",
                "pcm_s32be",
                "pcm_s32le",
                "pcm_s8",
                "pcm_u16be",
                "pcm_u16le",
                "pcm_u24be",
                "pcm_u24le",
                "pcm_u32be",
                "pcm_u32le",
                "pcm_u8",
                "truehd",
                "vorbis",
            ),
    )

/**
 * Desktop mpv declaration for runtimes outside the pinned macOS bundle. It
 * advertises no PCM: there is no generic "pcm" codec name, and these runtimes
 * have no qualified decoder set.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val desktopMpvDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "mpegts", "ts", "avi"),
        videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
        audioCodecs = listOf("aac", "mp3", "flac", "ac3", "eac3", "opus", "vorbis", "dts", "dca", "truehd", "mp2"),
    )

// For FFmpeg 9.0.2, shared by bundled Standard and Metal, every name below is
// an exact FFmpeg codec_name with a registered libavcodec decoder. That static
// decoder registration check does not establish demuxing, audible output,
// channel mapping or runtime acceptance. Requalify when the FFmpeg version or
// configure line changes.
private val macOsBundledMpvPcmAudioCodecs =
    listOf(
        "pcm_alaw",
        "pcm_bluray",
        "pcm_dvd",
        "pcm_f16le",
        "pcm_f24le",
        "pcm_f32be",
        "pcm_f32le",
        "pcm_f64be",
        "pcm_f64le",
        "pcm_lxf",
        "pcm_mulaw",
        "pcm_s16be",
        "pcm_s16be_planar",
        "pcm_s16le",
        "pcm_s16le_planar",
        "pcm_s24be",
        "pcm_s24daud",
        "pcm_s24le",
        "pcm_s24le_planar",
        "pcm_s32be",
        "pcm_s32le",
        "pcm_s32le_planar",
        "pcm_s64be",
        "pcm_s64le",
        "pcm_s8",
        "pcm_s8_planar",
        "pcm_sga",
        "pcm_u16be",
        "pcm_u16le",
        "pcm_u24be",
        "pcm_u24le",
        "pcm_u32be",
        "pcm_u32le",
        "pcm_u8",
        "pcm_vidc",
    )

/** Desktop mpv declaration for the bundled macOS Standard and Metal runtimes. */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val macOsBundledMpvDeviceProfileDeclaration =
    desktopMpvDeviceProfileDeclaration.copy(
        audioCodecs = desktopMpvDeviceProfileDeclaration.audioCodecs + macOsBundledMpvPcmAudioCodecs,
    )

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val desktopLibVlcDeviceProfileDeclaration =
    DeviceProfileDeclaration(
        containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "mpegts", "ts", "avi"),
        videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
        audioCodecs = listOf("aac", "mp3", "flac", "ac3", "eac3", "opus", "vorbis", "pcm", "dts", "dca", "truehd", "mp2"),
    )

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val appleDeviceProfileDeclaration =
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
        macOsBundledMpvDeviceProfileDeclaration,
        desktopLibVlcDeviceProfileDeclaration,
        appleDeviceProfileDeclaration,
    )

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
val androidDirectPlayProfiles =
    listOf(
        // PCM is advertised for MKV only: Media3 has no TS LPCM reader, MOV/MP4
        // PCM names cannot distinguish supported sample entries, and WebM has no PCM.
        DeviceDirectPlayProfile(
            containers = androidDirectPlayDeviceProfileDeclaration.containers - "mkv",
            videoCodecs = androidDirectPlayDeviceProfileDeclaration.videoCodecs,
            audioCodecs = androidDirectPlayDeviceProfileDeclaration.audioCodecs - androidMedia3MatroskaPcmAudioCodecs.toSet(),
        ),
        DeviceDirectPlayProfile(
            containers = listOf("mkv"),
            videoCodecs = androidDirectPlayDeviceProfileDeclaration.videoCodecs,
            audioCodecs = androidDirectPlayDeviceProfileDeclaration.audioCodecs,
        ),
    )
