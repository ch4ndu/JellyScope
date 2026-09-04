// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticDisplayResult
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeDiagnosticsTest {
    @Test
    fun failureDiagnosticKeepsOnlyFixedLabelsAndExceptionType() {
        val output =
            formatSafeFailureDiagnostic(
                stage = "repository",
                event = "request-failed",
                throwable = SecretBearingFailure(SECRET_SHAPED_MESSAGE),
            )

        assertEquals(
            "stage=repository event=request-failed exceptionType=SecretBearingFailure",
            output,
        )
        assertFalse(output.contains("https://"))
        assertFalse(output.contains("Authorization"))
        assertFalse(output.contains("token"))
        assertFalse(output.contains("/Users/"))
    }

    @Test
    fun failureDiagnosticRejectsUnsafeStageAndEventLabels() {
        val output =
            formatSafeFailureDiagnostic(
                stage = "https://server.example?token=secret",
                event = "/Users/example/video.mkv",
                throwable = IllegalStateException(SECRET_SHAPED_MESSAGE),
            )

        assertEquals(
            "stage=unknown event=unknown exceptionType=IllegalStateException",
            output,
        )
    }

    @Test
    fun typedFailureDiagnosticKeepsOperationBeforeExceptionType() {
        assertEquals(
            "stage=repository event=failed operation=getItemDetail exceptionType=IllegalStateException",
            formatSafeFailureDiagnostic(
                stage = "repository",
                event = "failed",
                operation = DiagnosticOperation.GetItemDetail,
                throwable = IllegalStateException("https://server.example/token=secret"),
            ),
        )
    }

    @Test
    fun diagnosticTagRegistryAcceptsOrdinaryStructuredTagsAndSpecialProbe() {
        val expectedWireValues =
            setOf(
                "PlaybackInfoPlanner",
                "Media3PlayerController",
                "MpvPlayerController",
                "AppleAVPlayerController",
                "VlcKitPlayerController",
                "LibVlcPlayerController",
                "AudioActivation",
                "SubtitleActivation",
                "PlaybackReportingQueue",
                "MediaRepository",
                "SessionRepository",
                "PlayerViewModel",
                "TrickplayPreview",
                "DeviceProfile",
                "AndroidProcessExitDiagnostics",
                "AndroidPlaybackModule",
                "AndroidMpvPlayerController",
                "MacosNowPlaying",
                "AndroidKeystoreSecureStore",
                "AuthRepository",
                "ServerEntryViewModel",
                "DetailViewModel",
                "SeriesViewModel",
                "AmbientColor",
                "AndroidLibVlcAvailability",
                "IosPlayerSurface",
                "PlaybackAudioSession",
                "DesktopMpvSurface",
                "PreviousRunFailure",
                "TvSettingsPresenter",
                "TvSearchPresenter",
                "OpenSubtitles",
                "LocalSubtitleSync",
                "WatchNextSyncWorker",
                "SettingsViewModel",
                "DownloadsViewModel",
                "FixedDownload",
                "OriginalDownload",
                "DownloadNotificationPermission",
                "TvPlaybackSessionPresenter",
                "TvDisplayMode",
                "JellyScopePlaybackProbe",
            )

        assertEquals(expectedWireValues, DiagnosticTag.entries.map { tag -> tag.wireValue }.toSet())
        DiagnosticTag.entries
            .filterNot { tag -> tag == DiagnosticTag.JellyScopePlaybackProbe }
            .forEach { tag ->
                assertNotNull(
                    LogScrubber.capture(
                        tag = tag.wireValue,
                        message = "stage=repository event=failed exceptionType=IllegalStateException",
                    ),
                    "registered tag ${tag.wireValue} should accept structured diagnostics",
                )
            }
        assertNotNull(
            LogScrubber.capture(
                tag = DiagnosticTag.JellyScopePlaybackProbe.wireValue,
                message = "stage=probe event=surfaceTiming frames=1 renderMaxMicros=2 presentMaxMicros=3",
            ),
        )
        val openSubtitlesResponseFailure =
            "stage=api event=response-decode-failed operation=openSubtitleSearch requestKind=ImdbEpisode httpCode=200 " +
                "responseContentType=Json exceptionType=JsonConvertException causeType=JsonDecodingException"
        assertEquals(
            openSubtitlesResponseFailure,
            LogScrubber.capture(
                tag = DiagnosticTag.OpenSubtitles.wireValue,
                message = openSubtitlesResponseFailure,
            ),
        )
        val fixedDownloadRejection =
            "stage=fixed-download event=playlist-rejected reason=DeclaredLengthTooLarge " +
                "requestKind=MediaPlaylist failure=PayloadTooLarge result=UnsupportedArtifact"
        assertEquals(
            fixedDownloadRejection,
            LogScrubber.capture(
                tag = DiagnosticTag.FixedDownload.wireValue,
                message = fixedDownloadRejection,
            ),
        )
        val originalDownloadSettlementFailure =
            "stage=original-download event=checkpoint-failed exceptionType=IllegalStateException"
        assertEquals(
            originalDownloadSettlementFailure,
            LogScrubber.capture(
                tag = DiagnosticTag.OriginalDownload.wireValue,
                message = originalDownloadSettlementFailure,
            ),
        )
    }

    @Test
    fun newlyRegisteredPlaybackProducersAdmitTheirFormattedRecords() {
        val tvPlaybackMessage =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.NativePlayer,
                    event = PlaybackDiagnosticEvent.HealthSignal,
                    platform = PlaybackDiagnosticPlatform.TvOs,
                ),
            )
        val tvDisplayMessage =
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.TvDisplay,
                    event = PlaybackDiagnosticEvent.TvDisplay,
                    platform = PlaybackDiagnosticPlatform.Android,
                    tvDisplayResult = PlaybackDiagnosticDisplayResult.Applied,
                ),
            )

        assertEquals(
            tvPlaybackMessage,
            LogScrubber.capture(DiagnosticTag.TvPlaybackSessionPresenter.wireValue, tvPlaybackMessage),
        )
        assertEquals(
            tvDisplayMessage,
            LogScrubber.capture(DiagnosticTag.TvDisplayMode.wireValue, tvDisplayMessage),
        )
        assertFalse(tvPlaybackMessage.contains("itemId"))
        assertFalse(tvDisplayMessage.contains("modeId"))
    }

    @Test
    fun unknownAndFreeformCaptureIntentMessagesRemainRejected() {
        assertNull(LogScrubber.capture("UnknownProducer", "stage=repository event=failed"))
        assertNull(
            LogScrubber.capture(
                tag = DiagnosticTag.AmbientColor.wireValue,
                message = "Ambient color for movie-1: ff00ff",
            ),
        )
        assertNull(
            LogScrubber.capture(
                tag = DiagnosticTag.AuthRepository.wireValue,
                message = "Quick Connect auth request failed",
            ),
        )
        assertTrue(
            LogScrubber.capture(
                tag = DiagnosticTag.AmbientColor.wireValue,
                message = "stage=ambient-color event=failed exceptionType=IllegalStateException",
            ) != null,
        )
    }

    private class SecretBearingFailure(
        message: String,
    ) : RuntimeException(message)

    private companion object {
        const val SECRET_SHAPED_MESSAGE =
            "https://server.example/video?api_key=token Authorization=secret /Users/example/video.mkv"
    }
}
