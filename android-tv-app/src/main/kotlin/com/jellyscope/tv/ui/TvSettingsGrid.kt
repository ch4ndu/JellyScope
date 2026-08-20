// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.ui.focus.FocusDirection
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerHdrMode

/**
 * Stable identity and traversal policy for the fully composed, horizontally
 * scrollable Android TV Settings rows. UI code supplies labels, values, and
 * callbacks from the current ViewModel state; this policy deliberately has no
 * Compose state.
 */
internal enum class TvSettingsTileId(
    val row: Int,
    val column: Int,
) {
    Server(0, 0),
    SignedInUser(0, 1),
    ActiveAccount(0, 2),
    AddAccount(0, 3),
    Logout(0, 4),
    Theme(1, 0),
    TileSize(1, 1),
    FocusedCardZoom(1, 2),
    RememberLastLibrary(1, 3),
    LibraryGridHero(1, 4),
    AudioLanguage(2, 0),
    SubtitleLanguage(2, 1),
    MaxBitrate(2, 2),
    StillWatching(2, 3),
    PlaybackWarnings(2, 4),
    AudioOutput(2, 5),
    PlayerBackend(2, 6),
    AutoPlayNext(2, 7),
    AutoPlayNextDelay(2, 8),
    VlcTranscodeLimit(2, 9),
    Intros(3, 0),
    Credits(3, 1),
    Recaps(3, 2),
    Previews(3, 3),
    Commercials(3, 4),
    VideoFormats(4, 0),
    AudioFormats(4, 1),
    HdrHandling(4, 2),
    MatchRefreshRate(4, 3),
    RefreshCapabilities(4, 4),
    OpenSubtitlesKey(5, 0),
    ClearSubtitles(5, 1),
    AppVersion(5, 2),
    AboutJellyScope(5, 3),
    ServerUrl(5, 4),
    CollectLogs(6, 0),
    SendClientLogs(6, 1),
    VerboseLogcat(6, 2),
    PlaybackInfoAtStart(6, 3),
}

internal enum class TvSettingsSectionId(
    val row: Int,
) {
    Account(0),
    Appearance(1),
    Playback(2),
    SkipSegments(3),
    DevicePlayback(4),
    ServicesAndAbout(5),
    Diagnostics(6),
}

internal enum class TvSettingsTileAction {
    Detail,
    SwitchAccount,
    AddAccount,
    Logout,
    Theme,
    TileSize,
    FocusedCardZoom,
    RememberLastLibrary,
    LibraryGridHero,
    AudioLanguage,
    SubtitleLanguage,
    MaxBitrate,
    StillWatching,
    PlaybackWarnings,
    AudioOutput,
    PlayerBackend,
    AutoPlayNext,
    AutoPlayNextDelay,
    VlcTranscodeLimit,
    SegmentPolicy,
    HdrHandling,
    MatchRefreshRate,
    RefreshCapabilities,
    OpenSubtitlesKey,
    ClearSubtitles,
    CollectLogs,
    SendClientLogs,
    VerboseLogcat,
    PlaybackInfoAtStart,
}

internal fun TvSettingsTileId.action(): TvSettingsTileAction =
    when (this) {
        TvSettingsTileId.Server,
        TvSettingsTileId.SignedInUser,
        TvSettingsTileId.VideoFormats,
        TvSettingsTileId.AudioFormats,
        TvSettingsTileId.AppVersion,
        TvSettingsTileId.AboutJellyScope,
        TvSettingsTileId.ServerUrl,
        -> TvSettingsTileAction.Detail
        TvSettingsTileId.ActiveAccount -> TvSettingsTileAction.SwitchAccount
        TvSettingsTileId.AddAccount -> TvSettingsTileAction.AddAccount
        TvSettingsTileId.Logout -> TvSettingsTileAction.Logout
        TvSettingsTileId.Theme -> TvSettingsTileAction.Theme
        TvSettingsTileId.TileSize -> TvSettingsTileAction.TileSize
        TvSettingsTileId.FocusedCardZoom -> TvSettingsTileAction.FocusedCardZoom
        TvSettingsTileId.RememberLastLibrary -> TvSettingsTileAction.RememberLastLibrary
        TvSettingsTileId.LibraryGridHero -> TvSettingsTileAction.LibraryGridHero
        TvSettingsTileId.AudioLanguage -> TvSettingsTileAction.AudioLanguage
        TvSettingsTileId.SubtitleLanguage -> TvSettingsTileAction.SubtitleLanguage
        TvSettingsTileId.MaxBitrate -> TvSettingsTileAction.MaxBitrate
        TvSettingsTileId.StillWatching -> TvSettingsTileAction.StillWatching
        TvSettingsTileId.PlaybackWarnings -> TvSettingsTileAction.PlaybackWarnings
        TvSettingsTileId.AudioOutput -> TvSettingsTileAction.AudioOutput
        TvSettingsTileId.PlayerBackend -> TvSettingsTileAction.PlayerBackend
        TvSettingsTileId.AutoPlayNext -> TvSettingsTileAction.AutoPlayNext
        TvSettingsTileId.AutoPlayNextDelay -> TvSettingsTileAction.AutoPlayNextDelay
        TvSettingsTileId.VlcTranscodeLimit -> TvSettingsTileAction.VlcTranscodeLimit
        TvSettingsTileId.Intros,
        TvSettingsTileId.Credits,
        TvSettingsTileId.Recaps,
        TvSettingsTileId.Previews,
        TvSettingsTileId.Commercials,
        -> TvSettingsTileAction.SegmentPolicy
        TvSettingsTileId.HdrHandling -> TvSettingsTileAction.HdrHandling
        TvSettingsTileId.MatchRefreshRate -> TvSettingsTileAction.MatchRefreshRate
        TvSettingsTileId.RefreshCapabilities -> TvSettingsTileAction.RefreshCapabilities
        TvSettingsTileId.OpenSubtitlesKey -> TvSettingsTileAction.OpenSubtitlesKey
        TvSettingsTileId.ClearSubtitles -> TvSettingsTileAction.ClearSubtitles
        TvSettingsTileId.CollectLogs -> TvSettingsTileAction.CollectLogs
        TvSettingsTileId.SendClientLogs -> TvSettingsTileAction.SendClientLogs
        TvSettingsTileId.PlaybackInfoAtStart -> TvSettingsTileAction.PlaybackInfoAtStart
        TvSettingsTileId.VerboseLogcat -> TvSettingsTileAction.VerboseLogcat
    }

internal fun TvSettingsTileId.usesPlaybackPreferences(): Boolean =
    when (this) {
        TvSettingsTileId.AudioLanguage,
        TvSettingsTileId.SubtitleLanguage,
        TvSettingsTileId.MaxBitrate,
        TvSettingsTileId.StillWatching,
        TvSettingsTileId.PlaybackWarnings,
        TvSettingsTileId.PlayerBackend,
        TvSettingsTileId.AutoPlayNext,
        TvSettingsTileId.AutoPlayNextDelay,
        TvSettingsTileId.VlcTranscodeLimit,
        TvSettingsTileId.Intros,
        TvSettingsTileId.Credits,
        TvSettingsTileId.Recaps,
        TvSettingsTileId.Previews,
        TvSettingsTileId.Commercials,
        -> true
        else -> false
    }

internal sealed interface TvSettingsGridDestination {
    data object Rail : TvSettingsGridDestination

    data object Consume : TvSettingsGridDestination

    data class Tile(
        val id: TvSettingsTileId,
    ) : TvSettingsGridDestination
}

internal val tvSettingsGridRows: List<List<TvSettingsTileId>> =
    TvSettingsTileId.entries
        .groupBy(TvSettingsTileId::row)
        .toSortedMap()
        .map { (_, tiles) -> tiles.sortedBy(TvSettingsTileId::column) }

internal val tvSettingsGridSections: List<TvSettingsSectionId> =
    TvSettingsSectionId.entries.sortedBy(TvSettingsSectionId::row)

internal val tvSettingsEntryTile = TvSettingsTileId.Server

internal fun tvPlayerAudioModeAvailable(
    mode: PlayerAudioMode,
    capabilities: DeviceDecodingCapabilities?,
): Boolean =
    mode != PlayerAudioMode.PassthroughWhenSupported ||
        capabilities?.audioPassthroughCodecs?.isNotEmpty() == true

internal fun tvPlayerHdrModeAvailable(
    mode: PlayerHdrMode,
    capabilities: DeviceDecodingCapabilities?,
): Boolean =
    mode != PlayerHdrMode.Auto ||
        capabilities?.let { detected -> detected.supportsHdr || detected.supportsDolbyVision } == true

internal fun tvSettingsGridDestination(
    from: TvSettingsTileId,
    direction: FocusDirection,
): TvSettingsGridDestination {
    val currentRow = tvSettingsGridRows[from.row]
    val currentColumn = currentRow.indexOf(from)
    return when (direction) {
        FocusDirection.Left ->
            if (currentColumn == 0) {
                TvSettingsGridDestination.Rail
            } else {
                TvSettingsGridDestination.Tile(currentRow[currentColumn - 1])
            }
        FocusDirection.Right ->
            if (currentColumn == currentRow.lastIndex) {
                TvSettingsGridDestination.Consume
            } else {
                TvSettingsGridDestination.Tile(currentRow[currentColumn + 1])
            }
        FocusDirection.Up ->
            if (from.row == 0) {
                TvSettingsGridDestination.Consume
            } else {
                tileInAdjacentRow(from.row - 1, currentColumn)
            }
        FocusDirection.Down ->
            if (from.row == TV_SETTINGS_GRID_ROWS - 1) {
                TvSettingsGridDestination.Consume
            } else {
                tileInAdjacentRow(from.row + 1, currentColumn)
            }
        else -> TvSettingsGridDestination.Consume
    }
}

internal const val TV_SETTINGS_GRID_ROWS = 7
internal const val TV_SETTINGS_GRID_COLUMNS = 5

internal enum class TvSettingsDialogEvent {
    Select,
    Back,
}

internal fun <T> tvSettingsDialogInitialSelection(
    selected: T,
    options: List<Pair<T, Boolean>>,
): T? =
    options.firstOrNull { (value, enabled) -> value == selected && enabled }?.first
        ?: options.firstOrNull { (_, enabled) -> enabled }?.first
        ?: options.firstOrNull()?.first

internal fun tvSettingsDialogApplies(
    event: TvSettingsDialogEvent,
    optionEnabled: Boolean,
    alreadyApplied: Boolean,
): Boolean = event == TvSettingsDialogEvent.Select && optionEnabled && !alreadyApplied

internal fun tvSettingsDialogRestoreTarget(opener: TvSettingsTileId): TvSettingsTileId = opener

private fun tileInAdjacentRow(
    row: Int,
    column: Int,
): TvSettingsGridDestination.Tile {
    val targetRow = tvSettingsGridRows[row]
    return TvSettingsGridDestination.Tile(targetRow[column.coerceAtMost(targetRow.lastIndex)])
}
