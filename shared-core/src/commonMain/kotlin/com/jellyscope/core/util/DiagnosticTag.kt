// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import co.touchlab.kermit.Logger
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

internal enum class DiagnosticTagCapturePolicy {
    Ordinary,
    DesktopPlaybackProbe,
}

/**
 * Closed set of Kermit tags whose structured output may cross the safe-log
 * capture boundary. The wire values are stable because they are part of the
 * uploaded diagnostic format.
 */
enum class DiagnosticTag(
    val wireValue: String,
    internal val capturePolicy: DiagnosticTagCapturePolicy = DiagnosticTagCapturePolicy.Ordinary,
) {
    PlaybackInfoPlanner("PlaybackInfoPlanner"),
    Media3PlayerController("Media3PlayerController"),
    MpvPlayerController("MpvPlayerController"),
    AppleAVPlayerController("AppleAVPlayerController"),
    VlcKitPlayerController("VlcKitPlayerController"),
    LibVlcPlayerController("LibVlcPlayerController"),
    AudioActivation("AudioActivation"),
    SubtitleActivation("SubtitleActivation"),
    PlaybackReportingQueue("PlaybackReportingQueue"),
    MediaRepository("MediaRepository"),
    SessionRepository("SessionRepository"),
    PlayerViewModel("PlayerViewModel"),
    DeviceProfile("DeviceProfile"),
    AndroidProcessExitDiagnostics("AndroidProcessExitDiagnostics"),
    AndroidPlaybackModule("AndroidPlaybackModule"),
    AndroidMpvPlayerController("AndroidMpvPlayerController"),
    MacosNowPlaying("MacosNowPlaying"),
    AndroidKeystoreSecureStore("AndroidKeystoreSecureStore"),
    AuthRepository("AuthRepository"),
    ServerEntryViewModel("ServerEntryViewModel"),
    DetailViewModel("DetailViewModel"),
    SeriesViewModel("SeriesViewModel"),
    AmbientColor("AmbientColor"),
    AndroidLibVlcAvailability("AndroidLibVlcAvailability"),
    IosPlayerSurface("IosPlayerSurface"),
    PlaybackAudioSession("PlaybackAudioSession"),
    DesktopMpvSurface("DesktopMpvSurface"),
    PreviousRunFailure("PreviousRunFailure"),
    TvSettingsPresenter("TvSettingsPresenter"),
    TvSearchPresenter("TvSearchPresenter"),
    OpenSubtitles("OpenSubtitles"),
    LocalSubtitleSync("LocalSubtitleSync"),
    WatchNextSyncWorker("WatchNextSyncWorker"),
    SettingsViewModel("SettingsViewModel"),
    DownloadsViewModel("DownloadsViewModel"),
    FixedDownload("FixedDownload"),
    OriginalDownload("OriginalDownload"),
    DownloadNotificationPermission("DownloadNotificationPermission"),
    TvPlaybackSessionPresenter("TvPlaybackSessionPresenter"),
    TvDisplayMode("TvDisplayMode"),
    JellyScopePlaybackProbe("JellyScopePlaybackProbe", DiagnosticTagCapturePolicy.DesktopPlaybackProbe),
}

/**
 * Creates a logger for a closed diagnostic tag. Authorization is deliberately
 * limited to the tag; [LogScrubber] remains the final schema and privacy gate.
 *
 * The logger type is hidden from Objective-C exports because this is a
 * Kotlin-only producer helper used across source sets.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun diagnosticLogger(tag: DiagnosticTag): Logger = Logger.withTag(tag.wireValue)
