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
                    #EXT-X-STREAM-INF:CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1280x720,BANDWIDTH=2500000
                    child/media.m3u8?TranscodeToken=secret
                    """.trimIndent(),
                mediaText =
                    """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-TARGETDURATION:10
                    #EXT-X-MEDIA-SEQUENCE:7
                    #EXT-X-PLAYLIST-TYPE:VOD
                    #EXTINF:9.5,first
                    chunks/first.ts?token=secret
                    #EXTINF:10,second
                    chunks/second.ts?token=secret
                    #EXT-X-ENDLIST
                    """.trimIndent(),
            )

        val packageValue = assertNotNull((parsed as? DownloadHlsParseResult.Package)?.value)
        assertEquals("child/media.m3u8?TranscodeToken=secret", packageValue.master.childPlaylistUri)
        assertEquals(7L, packageValue.media.mediaSequence)
        assertEquals(listOf("segment-000000.ts", "segment-000001.ts"), packageValue.media.segments.map { it.localPartKey.value })
        assertEquals(listOf("9.500", "10.000"), packageValue.media.segments.map { it.durationText })
        assertFalse(packageValue.masterText().contains("TranscodeToken"))
        assertFalse(packageValue.mediaText().contains("secret"))
        assertTrue(packageValue.mediaText().contains("segment-000001.ts"))
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
            DownloadHlsRejectReason.UnsupportedTag,
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
            (DownloadHlsPackage.parse(master, baseMedia.replace("#EXT-X-ENDLIST", "")) as DownloadHlsParseResult.Failure).reason,
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
        assertFalse(checkpoint.isCompatible(assertNotNull((changed as? DownloadHlsParseResult.Package)?.value)))
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
