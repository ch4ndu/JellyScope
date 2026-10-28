// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Selecting a native elementary stream succeeds whenever the stream exists in the
 * container, so index readback is not proof of decoding: switching to a codec the
 * backend cannot decode played silently with the video still running. These cover
 * the predicate that turns that case into the existing DirectPlay-disabled
 * recovery, before any native attempt is made.
 */
class DirectPlayAudioAdmissibilityTest {
    private val vlcKit = resolvePlayerDevicePolicy(vlcKitDeviceCapabilities(), PlayerDeviceSettings())

    @Test
    fun admitsCodecsTheBackendDeclaresItCanDirectPlay() {
        listOf("aac", "ac3", "eac3", "flac").forEach { codec ->
            assertTrue(vlcKit.admitsDirectPlayAudioSwitch(codec), "expected $codec to be admitted")
        }
    }

    @Test
    fun rejectsCodecTheBackendCannotDecode() {
        // MobileVLCKit 3.7.x ships no TrueHD/MLP decoder — the reported repro.
        assertFalse(vlcKit.admitsDirectPlayAudioSwitch("truehd"))
    }

    @Test
    fun normalizesCodecSpellingsBeforeComparing() {
        // A spelling mismatch must never masquerade as "cannot decode".
        listOf("EAC3 ", "ec-3", "eac3-joc", "mp4a.40.2").forEach { codec ->
            assertTrue(vlcKit.admitsDirectPlayAudioSwitch(codec), "expected $codec to normalize to an admitted family")
        }
    }

    @Test
    fun failsOpenWhenTheAnswerIsUnknown() {
        // Missing metadata is not evidence of anything, so it stays admissible.
        assertTrue(vlcKit.admitsDirectPlayAudioSwitch(null))
        assertTrue(vlcKit.admitsDirectPlayAudioSwitch("  "))
        val noProfiles =
            resolvePlayerDevicePolicy(
                DeviceDecodingCapabilities(
                    videoCodecs = emptyList(),
                    audioCodecs = emptyList(),
                    supportsDolbyVision = false,
                ),
                PlayerDeviceSettings(),
            )
        assertTrue(noProfiles.admitsDirectPlayAudioSwitch("truehd"))
    }

    @Test
    fun rejectsACodecTheProfileDoesNotList() {
        // Preferring a transcode over silence: an unlisted codec may simply be
        // undecodable, and a needless transcode still produces audio.
        assertFalse(vlcKit.admitsDirectPlayAudioSwitch("some-future-codec"))
    }

    @Test
    fun stampingPreservesDescriptorCountAndOrdinals() {
        // The desktop VLC controller uses the descriptor count as a native-track
        // sanity check, so stamping must never shorten or reorder the list.
        val streams =
            listOf(
                stream(index = 1, type = "Audio", codec = "aac"),
                stream(index = 2, type = "Audio", codec = "truehd"),
                stream(index = 3, type = "Audio", codec = "ac3"),
            )

        val plain = embeddedAudioTracks(streams)
        val stamped = embeddedAudioTracks(streams, vlcKit, container = null)

        assertEquals(plain.size, stamped.size)
        assertEquals(
            plain.map { track -> track.jellyfinStreamIndex to track.filteredContainerOrdinal },
            stamped.map { track -> track.jellyfinStreamIndex to track.filteredContainerOrdinal },
        )
        assertEquals(listOf(true, false, true), stamped.map { track -> track.directPlayAdmissible })
    }

    @Test
    fun stampingWithoutCapabilitiesLeavesEveryTrackAdmissible() {
        val streams = listOf(stream(index = 1, type = "Audio", codec = "truehd"))

        assertTrue(embeddedAudioTracks(streams, effectivePolicy = null, container = null).all { track -> track.directPlayAdmissible })
    }

    @Test
    fun stampingAppliesTheEffectiveCodecContainerAndChannelPolicy() {
        // A switch the negotiated Stereo PCM profile excludes must take the
        // existing replan instead of playing natively, while unknown channel
        // metadata and a multi-valued source container stay admissible.
        val stereo = resolvePlayerDevicePolicy(vlcKitDeviceCapabilities(), PlayerDeviceSettings(audioMode = PlayerAudioMode.StereoPcm))
        val streams =
            listOf(
                stream(index = 1, type = "Audio", codec = "aac", channels = 2),
                stream(index = 2, type = "Audio", codec = "ac3", channels = 2),
                stream(index = 3, type = "Audio", codec = "aac", channels = 6),
                stream(index = 4, type = "Audio", codec = "aac"),
            )

        val stamped = embeddedAudioTracks(streams, stereo, container = "mov,mp4,m4a")
        val wrongContainer = embeddedAudioTracks(streams, stereo, container = "wtv")

        assertEquals(listOf(true, false, false, true), stamped.map { track -> track.directPlayAdmissible })
        assertEquals(listOf(false, false, false, false), wrongContainer.map { track -> track.directPlayAdmissible })
        assertEquals(
            embeddedAudioTracks(streams).map { track -> track.jellyfinStreamIndex to track.filteredContainerOrdinal },
            stamped.map { track -> track.jellyfinStreamIndex to track.filteredContainerOrdinal },
        )
    }

    private fun stream(
        index: Int,
        type: String,
        codec: String,
        channels: Int? = null,
    ) = PlaybackMediaStream(
        index = index,
        type = type,
        displayTitle = null,
        title = null,
        language = null,
        codec = codec,
        channelLayout = null,
        bitRate = null,
        height = null,
        isDefault = null,
        isExternal = null,
        deliveryMethod = null,
        deliveryUrl = null,
        channels = channels,
    )
}
