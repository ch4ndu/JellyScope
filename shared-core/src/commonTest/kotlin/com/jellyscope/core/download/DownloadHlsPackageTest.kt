// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadHlsPackageTest {
    @Test
    fun parsesFiniteSingleVariantVodAndLocalizesOnlyRelativeNames() {
        val parsed =
            DownloadHlsPackage.parse(
                masterText =
                    """
                    #EXTM3U
                    # Jellyfin master metadata
                    #EXT-X-VERSION:6
                    #EXT-X-INDEPENDENT-SEGMENTS
                    #EXT-X-SESSION-DATA:DATA-ID="com.jellyfin.test",VALUE="ignored"
                    #EXT-X-IMAGE-STREAM-INF:BANDWIDTH=1000,URI="trickplay.m3u8"
                    #EXT-X-STREAM-INF:CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1280x720,BANDWIDTH=2500000
                    child/media.m3u8?TranscodeToken=secret
                    """.trimIndent(),
                mediaText =
                    """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-INDEPENDENT-SEGMENTS
                    #EXT-X-ALLOW-CACHE:YES
                    #EXT-X-TARGETDURATION:10
                    #EXT-X-MEDIA-SEQUENCE:7
                    #EXT-X-PLAYLIST-TYPE:VOD
                    #EXTINF:9.500499,first
                    chunks/first.ts?token=secret
                    #EXTINF:9.999999,second
                    chunks/second.ts?token=secret
                    #EXT-X-ENDLIST
                    """.trimIndent(),
            )

        val packageValue =
            assertNotNull(
                (parsed as? DownloadHlsParseResult.Package)?.value,
            )
        assertEquals("child/media.m3u8?TranscodeToken=secret", packageValue.master.childPlaylistUri)
        assertEquals(7L, packageValue.media.mediaSequence)
        assertEquals(
            listOf("segment-000000.ts", "segment-000001.ts"),
            packageValue.media.segments.map { segment -> segment.localPartKey.value },
        )
        assertEquals(listOf("9.500", "10.000"), packageValue.media.segments.map { it.durationText })
        assertEquals(listOf(9_500L, 10_000L), packageValue.media.segments.map { it.durationMillis })
        assertFalse(packageValue.masterText().contains("TranscodeToken"))
        assertFalse(packageValue.mediaText().contains("secret"))
        assertTrue(packageValue.mediaText().contains("segment-000001.ts"))

        val directPackage = DownloadHlsPackage.fromDirectMedia(packageValue.media, maxBitrateBps = 2_500_000L)
        assertTrue(directPackage.mediaPlaylistIsSource)
        assertTrue(directPackage.masterText().contains("BANDWIDTH=2500000"))
        assertTrue(directPackage.masterText().contains("media.m3u8"))
    }

    @Test
    fun rejectsUnverifiedPlaylistResourcesAndNonVodShapes() {
        val master =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000
            media.m3u8
            """.trimIndent()
        val baseMedia =
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:10,
            segment.ts
            #EXT-X-ENDLIST
            """.trimIndent()

        assertEquals(
            DownloadHlsRejectReason.UnsupportedMediaEncryption,
            (
                DownloadHlsPackage.parse(
                    master,
                    baseMedia.replace("segment.ts", "#EXT-X-KEY:METHOD=AES-128\nsegment.ts"),
                ) as DownloadHlsParseResult.Failure
            ).reason,
        )
        assertEquals(
            DownloadHlsRejectReason.UnsupportedResource,
            (
                DownloadHlsPackage.parse(
                    master,
                    baseMedia.replace("segment.ts", "https://other.example/segment.ts"),
                ) as DownloadHlsParseResult.Failure
            ).reason,
        )
        assertEquals(
            DownloadHlsRejectReason.MissingRequiredTag,
            (
                DownloadHlsPackage.parse(
                    master,
                    baseMedia.replace("#EXT-X-ENDLIST", ""),
                ) as DownloadHlsParseResult.Failure
            ).reason,
        )
        assertEquals(
            DownloadHlsRejectReason.UnsupportedMasterRendition,
            (
                DownloadHlsPackage.parseMasterOnly(
                    master.replace(
                        "#EXT-X-STREAM-INF",
                        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF",
                    ),
                ) as DownloadHlsParseResult.Failure
            ).reason,
        )
    }

    @Test
    fun checkpointRoundTripContainsOnlyLocalFactsAndRejectsSourceChanges() {
        val packageValue = parseFixture()
        val checkpoint =
            packageValue.initialCheckpoint().copy(
                master = DownloadHlsCheckpointPart("master.m3u8", 80L, true),
                media = DownloadHlsCheckpointPart("media.m3u8", 160L, true),
                segments =
                    listOf(
                        DownloadHlsCheckpointPart("segment-000000.ts", 100L, true),
                        DownloadHlsCheckpointPart("segment-000001.ts", 0L, false),
                    ),
            )

        val encoded = checkpoint.encode()
        assertFalse(encoded.contains("https://"))
        assertFalse(encoded.contains("token"))
        assertEquals(checkpoint, DownloadHlsCheckpoint.decode(encoded))
        assertTrue(checkpoint.isCompatible(packageValue))
        assertFalse(checkpoint.isComplete())
        assertEquals(4, checkpoint.artifactCheckpoint().size)

        val changed =
            DownloadHlsPackage.parse(
                masterText =
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=999
                    media.m3u8
                    """.trimIndent(),
                mediaText = fixtureMedia.replace("#EXT-X-MEDIA-SEQUENCE:7", "#EXT-X-MEDIA-SEQUENCE:8"),
            )
        assertFalse(
            checkpoint.isCompatible(
                assertNotNull(
                    (changed as? DownloadHlsParseResult.Package)?.value,
                ),
            ),
        )
    }

    @Test
    fun largeMediaPlaylistAndCompletedCheckpointShareBoundedEnvelope() {
        val queryPadding = "q".repeat(110)
        val mediaText =
            buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n")
                append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                repeat(8_192) { index ->
                    append("#EXTINF:1,\nsegments/segment-")
                    append(index.toString().padStart(6, '0'))
                    append(".ts?transient-query-marker=")
                    append(queryPadding)
                    append('\n')
                }
                append("#EXT-X-ENDLIST\n")
            }
        assertTrue(mediaText.encodeToByteArray().size > MAX_HLS_MASTER_PLAYLIST_BYTES)
        assertTrue(mediaText.encodeToByteArray().size <= MAX_HLS_MEDIA_PLAYLIST_BYTES)

        val media =
            assertNotNull(
                (DownloadHlsPackage.parseMediaOnly(mediaText) as? DownloadHlsParseResult.Media)?.value,
            )
        val packageValue = DownloadHlsPackage.fromDirectMedia(media, maxBitrateBps = 4_000_000L)
        val completed =
            packageValue.initialCheckpoint().copy(
                master =
                    DownloadHlsCheckpointPart(
                        "master.m3u8",
                        packageValue
                            .masterText()
                            .encodeToByteArray()
                            .size
                            .toLong(),
                        true,
                    ),
                media =
                    DownloadHlsCheckpointPart(
                        "media.m3u8",
                        packageValue
                            .mediaText()
                            .encodeToByteArray()
                            .size
                            .toLong(),
                        true,
                    ),
                segments =
                    packageValue.initialCheckpoint().segments.map { part ->
                        part.copy(lengthBytes = 123_456_789L, complete = true)
                    },
            )
        val encoded = completed.encode()
        assertTrue(encoded.encodeToByteArray().size > 1_048_576)
        assertTrue(encoded.encodeToByteArray().size <= MAX_HLS_CHECKPOINT_BYTES)
        assertFalse(encoded.contains("transient-query-marker"))
        assertEquals(completed, DownloadHlsCheckpoint.decode(encoded))
        assertTrue(completed.isCompatible(packageValue))
        assertTrue(completed.isComplete())
    }

    @Test
    fun durationCompletenessAllowsOnlyBoundedExtinfRounding() {
        val packageValue = parseFixture()

        assertTrue(hlsDurationMatchesSource(packageValue, sourceDurationMs = 19_499L))
        assertTrue(hlsDurationMatchesSource(packageValue, sourceDurationMs = 19_501L))
        assertFalse(hlsDurationMatchesSource(packageValue, sourceDurationMs = 19_000L))
    }

    private fun parseFixture(): DownloadHlsPackage =
        assertNotNull(
            (
                DownloadHlsPackage.parse(
                    masterText =
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=2500000
                        media.m3u8
                        """.trimIndent(),
                    mediaText = fixtureMedia,
                ) as? DownloadHlsParseResult.Package
            )?.value,
        )

    private val fixtureMedia =
        """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:7
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXTINF:9.5,
        first.ts
        #EXTINF:10,
        second.ts
        #EXT-X-ENDLIST
        """.trimIndent()
}
