// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.ui.graphics.vector.ImageVector

/** Declaration order is the settings card order. */
internal enum class SettingsSectionId {
    Account,
    Appearance,
    Playback,
    SkipSegments,
    AdvancedPlayback,
    Downloads,
    Services,
    Diagnostics,
    About,
    ComingLater,
}

internal enum class SettingsRowId {
    Server,
    SignedInUser,
    ActiveAccount,
    AddAccount,
    SignOut,
    Theme,
    TileSize,
    RememberLastLibrary,
    AudioLanguage,
    SubtitleLanguage,
    MaxBitrate,
    VlcTranscodeLimit,
    AutoPlayNext,
    StillWatching,
    PlaybackWarnings,
    AllowInsecureDesktopTls,
    AutoPlayNextDelay,
    PlayerBackend,
    Intros,
    Credits,
    Recaps,
    Previews,
    Commercials,
    AudioOutput,
    HdrHandling,
    MaximumVideoResolution,
    IosPlaybackCompatibility,
    VideoFormats,
    AudioFormats,
    RefreshCapabilities,
    PictureInPicture,
    Downloads,
    SubtitleResultPreference,
    OpenSubtitlesKey,
    ClearSubtitles,
    CollectLogs,
    SystemLogs,
    PlaybackInfoAtStart,
    SendClientLogs,
    AppVersion,
    License,
}

internal enum class SettingsRowIconRole {
    Primary,
    Accent,
    Destructive,
}

internal enum class SettingsRowTrailingKind {
    Chevron,
    Switch,
    Progress,
    None,
}

internal fun SettingsRowId.section(): SettingsSectionId =
    when (this) {
        SettingsRowId.Server,
        SettingsRowId.SignedInUser,
        SettingsRowId.ActiveAccount,
        SettingsRowId.AddAccount,
        SettingsRowId.SignOut,
        -> SettingsSectionId.Account
        SettingsRowId.Theme,
        SettingsRowId.TileSize,
        SettingsRowId.RememberLastLibrary,
        -> SettingsSectionId.Appearance
        SettingsRowId.AudioLanguage,
        SettingsRowId.SubtitleLanguage,
        SettingsRowId.MaxBitrate,
        SettingsRowId.VlcTranscodeLimit,
        SettingsRowId.AutoPlayNext,
        SettingsRowId.StillWatching,
        SettingsRowId.PlaybackWarnings,
        SettingsRowId.AllowInsecureDesktopTls,
        SettingsRowId.AutoPlayNextDelay,
        SettingsRowId.PlayerBackend,
        -> SettingsSectionId.Playback
        SettingsRowId.Intros,
        SettingsRowId.Credits,
        SettingsRowId.Recaps,
        SettingsRowId.Previews,
        SettingsRowId.Commercials,
        -> SettingsSectionId.SkipSegments
        SettingsRowId.AudioOutput,
        SettingsRowId.HdrHandling,
        SettingsRowId.MaximumVideoResolution,
        SettingsRowId.IosPlaybackCompatibility,
        SettingsRowId.VideoFormats,
        SettingsRowId.AudioFormats,
        SettingsRowId.RefreshCapabilities,
        SettingsRowId.PictureInPicture,
        -> SettingsSectionId.AdvancedPlayback
        SettingsRowId.Downloads -> SettingsSectionId.Downloads
        SettingsRowId.SubtitleResultPreference,
        SettingsRowId.OpenSubtitlesKey,
        SettingsRowId.ClearSubtitles,
        -> SettingsSectionId.Services
        SettingsRowId.CollectLogs,
        SettingsRowId.SystemLogs,
        SettingsRowId.PlaybackInfoAtStart,
        SettingsRowId.SendClientLogs,
        -> SettingsSectionId.Diagnostics
        SettingsRowId.AppVersion,
        SettingsRowId.License,
        -> SettingsSectionId.About
    }

internal fun SettingsRowId.trailingKind(): SettingsRowTrailingKind =
    when (this) {
        SettingsRowId.ActiveAccount,
        SettingsRowId.AddAccount,
        SettingsRowId.SignOut,
        SettingsRowId.Theme,
        SettingsRowId.TileSize,
        SettingsRowId.AudioLanguage,
        SettingsRowId.SubtitleLanguage,
        SettingsRowId.MaxBitrate,
        SettingsRowId.VlcTranscodeLimit,
        SettingsRowId.AutoPlayNextDelay,
        SettingsRowId.PlayerBackend,
        SettingsRowId.Intros,
        SettingsRowId.Credits,
        SettingsRowId.Recaps,
        SettingsRowId.Previews,
        SettingsRowId.Commercials,
        SettingsRowId.AudioOutput,
        SettingsRowId.HdrHandling,
        SettingsRowId.MaximumVideoResolution,
        SettingsRowId.IosPlaybackCompatibility,
        SettingsRowId.SubtitleResultPreference,
        SettingsRowId.OpenSubtitlesKey,
        SettingsRowId.License,
        SettingsRowId.Downloads,
        -> SettingsRowTrailingKind.Chevron
        SettingsRowId.RememberLastLibrary,
        SettingsRowId.AutoPlayNext,
        SettingsRowId.StillWatching,
        SettingsRowId.PlaybackWarnings,
        SettingsRowId.AllowInsecureDesktopTls,
        SettingsRowId.PictureInPicture,
        SettingsRowId.CollectLogs,
        SettingsRowId.SystemLogs,
        SettingsRowId.PlaybackInfoAtStart,
        -> SettingsRowTrailingKind.Switch
        SettingsRowId.RefreshCapabilities,
        SettingsRowId.ClearSubtitles,
        SettingsRowId.SendClientLogs,
        -> SettingsRowTrailingKind.Progress
        SettingsRowId.Server,
        SettingsRowId.SignedInUser,
        SettingsRowId.VideoFormats,
        SettingsRowId.AudioFormats,
        SettingsRowId.AppVersion,
        -> SettingsRowTrailingKind.None
    }

internal fun SettingsRowId.iconRole(): SettingsRowIconRole =
    when (this) {
        SettingsRowId.SignOut -> SettingsRowIconRole.Destructive
        SettingsRowId.Intros,
        SettingsRowId.Credits,
        SettingsRowId.Recaps,
        SettingsRowId.Previews,
        SettingsRowId.Commercials,
        SettingsRowId.ClearSubtitles,
        SettingsRowId.AppVersion,
        -> SettingsRowIconRole.Accent
        SettingsRowId.Server,
        SettingsRowId.SignedInUser,
        SettingsRowId.ActiveAccount,
        SettingsRowId.AddAccount,
        SettingsRowId.Theme,
        SettingsRowId.TileSize,
        SettingsRowId.RememberLastLibrary,
        SettingsRowId.AudioLanguage,
        SettingsRowId.SubtitleLanguage,
        SettingsRowId.MaxBitrate,
        SettingsRowId.VlcTranscodeLimit,
        SettingsRowId.AutoPlayNext,
        SettingsRowId.StillWatching,
        SettingsRowId.PlaybackWarnings,
        SettingsRowId.AllowInsecureDesktopTls,
        SettingsRowId.AutoPlayNextDelay,
        SettingsRowId.PlayerBackend,
        SettingsRowId.AudioOutput,
        SettingsRowId.HdrHandling,
        SettingsRowId.MaximumVideoResolution,
        SettingsRowId.IosPlaybackCompatibility,
        SettingsRowId.VideoFormats,
        SettingsRowId.AudioFormats,
        SettingsRowId.RefreshCapabilities,
        SettingsRowId.PictureInPicture,
        SettingsRowId.SubtitleResultPreference,
        SettingsRowId.OpenSubtitlesKey,
        SettingsRowId.CollectLogs,
        SettingsRowId.SystemLogs,
        SettingsRowId.PlaybackInfoAtStart,
        SettingsRowId.SendClientLogs,
        SettingsRowId.License,
        SettingsRowId.Downloads,
        -> SettingsRowIconRole.Primary
    }

internal fun SettingsRowId.icon(): ImageVector =
    when (this) {
        SettingsRowId.Server -> SettingsIcons.Server
        SettingsRowId.SignedInUser -> SettingsIcons.SignedInUser
        SettingsRowId.ActiveAccount -> SettingsIcons.ActiveAccount
        SettingsRowId.AddAccount -> SettingsIcons.AddAccount
        SettingsRowId.SignOut -> SettingsIcons.SignOut
        SettingsRowId.Theme -> SettingsIcons.Theme
        SettingsRowId.TileSize -> SettingsIcons.TileSize
        SettingsRowId.RememberLastLibrary -> SettingsIcons.RememberLibrary
        SettingsRowId.AudioLanguage -> SettingsIcons.AudioLanguage
        SettingsRowId.SubtitleLanguage -> SettingsIcons.SubtitleLanguage
        SettingsRowId.MaxBitrate -> SettingsIcons.MaxBitrate
        SettingsRowId.VlcTranscodeLimit -> SettingsIcons.MaxBitrate
        SettingsRowId.AutoPlayNext,
        SettingsRowId.StillWatching,
        SettingsRowId.PlaybackWarnings,
        SettingsRowId.AllowInsecureDesktopTls,
        SettingsRowId.AutoPlayNextDelay,
        -> SettingsIcons.ResumeBehavior
        SettingsRowId.PlayerBackend -> SettingsIcons.VideoFormats
        // Avoid reusing the video-format glyph for picture-in-picture.
        SettingsRowId.PictureInPicture -> SettingsIcons.FocusedCardZoom
        SettingsRowId.Intros -> SettingsIcons.Intros
        SettingsRowId.Credits -> SettingsIcons.Credits
        SettingsRowId.Recaps -> SettingsIcons.Recaps
        SettingsRowId.Previews -> SettingsIcons.Previews
        SettingsRowId.Commercials -> SettingsIcons.Commercials
        SettingsRowId.AudioOutput -> SettingsIcons.AudioOutput
        SettingsRowId.HdrHandling -> SettingsIcons.HdrHandling
        SettingsRowId.MaximumVideoResolution,
        SettingsRowId.IosPlaybackCompatibility,
        -> SettingsIcons.VideoFormats
        SettingsRowId.VideoFormats -> SettingsIcons.VideoFormats
        SettingsRowId.AudioFormats -> SettingsIcons.AudioFormats
        SettingsRowId.RefreshCapabilities -> SettingsIcons.RefreshCapabilities
        SettingsRowId.SubtitleResultPreference,
        -> SettingsIcons.SubtitleLanguage
        SettingsRowId.OpenSubtitlesKey -> SettingsIcons.OpenSubtitlesKey
        SettingsRowId.ClearSubtitles -> SettingsIcons.ClearSubtitles
        SettingsRowId.CollectLogs,
        SettingsRowId.SystemLogs,
        SettingsRowId.PlaybackInfoAtStart,
        SettingsRowId.SendClientLogs,
        -> SettingsIcons.AboutJellyScope
        SettingsRowId.AppVersion -> SettingsIcons.AppVersion
        SettingsRowId.License -> SettingsIcons.AboutJellyScope
        SettingsRowId.Downloads -> SettingsIcons.VideoFormats
    }
