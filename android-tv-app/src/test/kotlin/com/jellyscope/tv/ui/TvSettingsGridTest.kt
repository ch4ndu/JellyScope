// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.ui.focus.FocusDirection
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.ui.screen.settings.SettingsIcons
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TvSettingsGridTest {
    @Test
    fun matrixContainsTheSevenRowsAndFortyUniqueStableTileIds() {
        assertEquals(TV_SETTINGS_GRID_ROWS, tvSettingsGridRows.size)
        assertEquals(TV_SETTINGS_GRID_ROWS, tvSettingsGridSections.size)
        assertEquals(
            listOf(
                TvSettingsSectionId.Account,
                TvSettingsSectionId.Appearance,
                TvSettingsSectionId.Playback,
                TvSettingsSectionId.SkipSegments,
                TvSettingsSectionId.DevicePlayback,
                TvSettingsSectionId.ServicesAndAbout,
                TvSettingsSectionId.Diagnostics,
            ),
            tvSettingsGridSections,
        )
        assertTrue(tvSettingsGridRows.dropLast(1).all { row -> row.size >= TV_SETTINGS_GRID_COLUMNS })
        assertEquals(4, tvSettingsGridRows.last().size)
        assertEquals(TvSettingsTileId.entries.toSet(), tvSettingsGridRows.flatten().toSet())
        assertEquals(40, TvSettingsTileId.entries.size)
        assertEquals(
            listOf(
                listOf(
                    TvSettingsTileId.Server,
                    TvSettingsTileId.SignedInUser,
                    TvSettingsTileId.ActiveAccount,
                    TvSettingsTileId.AddAccount,
                    TvSettingsTileId.Logout,
                ),
                listOf(
                    TvSettingsTileId.Theme,
                    TvSettingsTileId.TileSize,
                    TvSettingsTileId.FocusedCardZoom,
                    TvSettingsTileId.RememberLastLibrary,
                    TvSettingsTileId.LibraryGridHero,
                ),
                listOf(
                    TvSettingsTileId.AudioLanguage,
                    TvSettingsTileId.SubtitleLanguage,
                    TvSettingsTileId.MaxBitrate,
                    TvSettingsTileId.StillWatching,
                    TvSettingsTileId.PlaybackWarnings,
                    TvSettingsTileId.AudioOutput,
                    TvSettingsTileId.PlayerBackend,
                    TvSettingsTileId.AutoPlayNext,
                    TvSettingsTileId.AutoPlayNextDelay,
                    TvSettingsTileId.VlcTranscodeLimit,
                ),
                listOf(
                    TvSettingsTileId.Intros,
                    TvSettingsTileId.Credits,
                    TvSettingsTileId.Recaps,
                    TvSettingsTileId.Previews,
                    TvSettingsTileId.Commercials,
                ),
                listOf(
                    TvSettingsTileId.VideoFormats,
                    TvSettingsTileId.AudioFormats,
                    TvSettingsTileId.HdrHandling,
                    TvSettingsTileId.MatchRefreshRate,
                    TvSettingsTileId.RefreshCapabilities,
                    TvSettingsTileId.MpvVideoOutput,
                ),
                listOf(
                    TvSettingsTileId.OpenSubtitlesKey,
                    TvSettingsTileId.ClearSubtitles,
                    TvSettingsTileId.AppVersion,
                    TvSettingsTileId.AboutJellyScope,
                    TvSettingsTileId.ServerUrl,
                ),
                listOf(
                    TvSettingsTileId.CollectLogs,
                    TvSettingsTileId.SendClientLogs,
                    TvSettingsTileId.VerboseLogcat,
                    TvSettingsTileId.PlaybackInfoAtStart,
                ),
            ),
            tvSettingsGridRows,
        )
        assertEquals(TvSettingsTileId.Server, tvSettingsEntryTile)
        assertEquals(tvSettingsEntryTile, tvSettingsGridRows.first().first())
    }

    @Test
    fun actionMappingCoversEveryTileAndHasNoPerAccountSignOutAction() {
        val expected =
            mapOf(
                TvSettingsTileId.Server to TvSettingsTileAction.Detail,
                TvSettingsTileId.SignedInUser to TvSettingsTileAction.Detail,
                TvSettingsTileId.ActiveAccount to TvSettingsTileAction.SwitchAccount,
                TvSettingsTileId.AddAccount to TvSettingsTileAction.AddAccount,
                TvSettingsTileId.Logout to TvSettingsTileAction.Logout,
                TvSettingsTileId.Theme to TvSettingsTileAction.Theme,
                TvSettingsTileId.TileSize to TvSettingsTileAction.TileSize,
                TvSettingsTileId.FocusedCardZoom to TvSettingsTileAction.FocusedCardZoom,
                TvSettingsTileId.RememberLastLibrary to TvSettingsTileAction.RememberLastLibrary,
                TvSettingsTileId.LibraryGridHero to TvSettingsTileAction.LibraryGridHero,
                TvSettingsTileId.AudioLanguage to TvSettingsTileAction.AudioLanguage,
                TvSettingsTileId.SubtitleLanguage to TvSettingsTileAction.SubtitleLanguage,
                TvSettingsTileId.MaxBitrate to TvSettingsTileAction.MaxBitrate,
                TvSettingsTileId.StillWatching to TvSettingsTileAction.StillWatching,
                TvSettingsTileId.PlaybackWarnings to TvSettingsTileAction.PlaybackWarnings,
                TvSettingsTileId.AudioOutput to TvSettingsTileAction.AudioOutput,
                TvSettingsTileId.PlayerBackend to TvSettingsTileAction.PlayerBackend,
                TvSettingsTileId.AutoPlayNext to TvSettingsTileAction.AutoPlayNext,
                TvSettingsTileId.AutoPlayNextDelay to TvSettingsTileAction.AutoPlayNextDelay,
                TvSettingsTileId.VlcTranscodeLimit to TvSettingsTileAction.VlcTranscodeLimit,
                TvSettingsTileId.Intros to TvSettingsTileAction.SegmentPolicy,
                TvSettingsTileId.Credits to TvSettingsTileAction.SegmentPolicy,
                TvSettingsTileId.Recaps to TvSettingsTileAction.SegmentPolicy,
                TvSettingsTileId.Previews to TvSettingsTileAction.SegmentPolicy,
                TvSettingsTileId.Commercials to TvSettingsTileAction.SegmentPolicy,
                TvSettingsTileId.VideoFormats to TvSettingsTileAction.Detail,
                TvSettingsTileId.AudioFormats to TvSettingsTileAction.Detail,
                TvSettingsTileId.HdrHandling to TvSettingsTileAction.HdrHandling,
                TvSettingsTileId.MatchRefreshRate to TvSettingsTileAction.MatchRefreshRate,
                TvSettingsTileId.RefreshCapabilities to TvSettingsTileAction.RefreshCapabilities,
                TvSettingsTileId.MpvVideoOutput to TvSettingsTileAction.MpvVideoOutput,
                TvSettingsTileId.OpenSubtitlesKey to TvSettingsTileAction.OpenSubtitlesKey,
                TvSettingsTileId.ClearSubtitles to TvSettingsTileAction.ClearSubtitles,
                TvSettingsTileId.AppVersion to TvSettingsTileAction.Detail,
                TvSettingsTileId.AboutJellyScope to TvSettingsTileAction.Detail,
                TvSettingsTileId.ServerUrl to TvSettingsTileAction.Detail,
                TvSettingsTileId.CollectLogs to TvSettingsTileAction.CollectLogs,
                TvSettingsTileId.SendClientLogs to TvSettingsTileAction.SendClientLogs,
                TvSettingsTileId.VerboseLogcat to TvSettingsTileAction.VerboseLogcat,
                TvSettingsTileId.PlaybackInfoAtStart to TvSettingsTileAction.PlaybackInfoAtStart,
            )
        assertEquals(expected, TvSettingsTileId.entries.associateWith(TvSettingsTileId::action))
        assertEquals(TvSettingsTileAction.Logout, TvSettingsTileId.Logout.action())
        assertEquals(TvSettingsTileAction.SwitchAccount, TvSettingsTileId.ActiveAccount.action())
        assertEquals(TvSettingsTileAction.AddAccount, TvSettingsTileId.AddAccount.action())
        assertFalse(TvSettingsTileAction.entries.any { action -> action.name.contains("SignOut") })
        assertEquals(
            setOf(
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
            ),
            TvSettingsTileId.entries.filter { tile -> tile.usesPlaybackPreferences() }.toSet(),
        )
    }

    @Test
    fun everySettingsTileResolvesToItsSharedCodeNativeGlyph() {
        val expected =
            mapOf(
                TvSettingsTileId.Server to SettingsIcons.Server,
                TvSettingsTileId.SignedInUser to SettingsIcons.SignedInUser,
                TvSettingsTileId.ActiveAccount to SettingsIcons.ActiveAccount,
                TvSettingsTileId.AddAccount to SettingsIcons.AddAccount,
                TvSettingsTileId.Logout to SettingsIcons.SignOut,
                TvSettingsTileId.Theme to SettingsIcons.Theme,
                TvSettingsTileId.TileSize to SettingsIcons.TileSize,
                TvSettingsTileId.FocusedCardZoom to SettingsIcons.FocusedCardZoom,
                TvSettingsTileId.RememberLastLibrary to SettingsIcons.RememberLibrary,
                TvSettingsTileId.LibraryGridHero to SettingsIcons.LibraryGridHero,
                TvSettingsTileId.AudioLanguage to SettingsIcons.AudioLanguage,
                TvSettingsTileId.SubtitleLanguage to SettingsIcons.SubtitleLanguage,
                TvSettingsTileId.MaxBitrate to SettingsIcons.MaxBitrate,
                TvSettingsTileId.StillWatching to SettingsIcons.ResumeBehavior,
                TvSettingsTileId.PlaybackWarnings to SettingsIcons.ResumeBehavior,
                TvSettingsTileId.AudioOutput to SettingsIcons.AudioOutput,
                TvSettingsTileId.PlayerBackend to SettingsIcons.VideoFormats,
                TvSettingsTileId.AutoPlayNext to SettingsIcons.ResumeBehavior,
                TvSettingsTileId.AutoPlayNextDelay to SettingsIcons.ResumeBehavior,
                TvSettingsTileId.VlcTranscodeLimit to SettingsIcons.MaxBitrate,
                TvSettingsTileId.Intros to SettingsIcons.Intros,
                TvSettingsTileId.Credits to SettingsIcons.Credits,
                TvSettingsTileId.Recaps to SettingsIcons.Recaps,
                TvSettingsTileId.Previews to SettingsIcons.Previews,
                TvSettingsTileId.Commercials to SettingsIcons.Commercials,
                TvSettingsTileId.VideoFormats to SettingsIcons.VideoFormats,
                TvSettingsTileId.AudioFormats to SettingsIcons.AudioFormats,
                TvSettingsTileId.HdrHandling to SettingsIcons.HdrHandling,
                TvSettingsTileId.MatchRefreshRate to SettingsIcons.MatchRefreshRate,
                TvSettingsTileId.RefreshCapabilities to SettingsIcons.RefreshCapabilities,
                TvSettingsTileId.MpvVideoOutput to SettingsIcons.VideoFormats,
                TvSettingsTileId.OpenSubtitlesKey to SettingsIcons.OpenSubtitlesKey,
                TvSettingsTileId.ClearSubtitles to SettingsIcons.ClearSubtitles,
                TvSettingsTileId.AppVersion to SettingsIcons.AppVersion,
                TvSettingsTileId.AboutJellyScope to SettingsIcons.AboutJellyScope,
                TvSettingsTileId.ServerUrl to SettingsIcons.ServerUrl,
                TvSettingsTileId.CollectLogs to SettingsIcons.AboutJellyScope,
                TvSettingsTileId.SendClientLogs to SettingsIcons.AboutJellyScope,
                TvSettingsTileId.VerboseLogcat to SettingsIcons.AboutJellyScope,
                TvSettingsTileId.PlaybackInfoAtStart to SettingsIcons.AboutJellyScope,
            )

        assertEquals(TvSettingsTileId.entries.toSet(), expected.keys)
        expected.forEach { (tile, icon) -> assertSame(icon, settingsTileIcon(tile)) }
    }

    @Test
    fun horizontalMovementChangesOneColumnAndOuterEdgesAreConsumedOrReturnToRail() {
        TvSettingsTileId.entries.forEach { tile ->
            val row = tvSettingsGridRows[tile.row]
            val column = row.indexOf(tile)
            val left = tvSettingsGridDestination(tile, FocusDirection.Left)
            if (column == 0) {
                assertEquals(TvSettingsGridDestination.Rail, left)
            } else {
                assertEquals(
                    TvSettingsGridDestination.Tile(row[column - 1]),
                    left,
                )
            }
            val right = tvSettingsGridDestination(tile, FocusDirection.Right)
            if (column == row.lastIndex) {
                assertEquals(TvSettingsGridDestination.Consume, right)
            } else {
                assertEquals(
                    TvSettingsGridDestination.Tile(row[column + 1]),
                    right,
                )
            }
        }
    }

    @Test
    fun verticalMovementKeepsLogicalColumnOrUsesTheAdjacentRowsLastTile() {
        TvSettingsTileId.entries.forEach { tile ->
            val up = tvSettingsGridDestination(tile, FocusDirection.Up)
            if (tile.row == 0) {
                assertEquals(TvSettingsGridDestination.Consume, up)
            } else {
                assertEquals(
                    TvSettingsGridDestination.Tile(tileAtOrLastColumn(tile.row - 1, tile.column)),
                    up,
                )
            }
            val down = tvSettingsGridDestination(tile, FocusDirection.Down)
            if (tile.row == TV_SETTINGS_GRID_ROWS - 1) {
                assertEquals(TvSettingsGridDestination.Consume, down)
            } else {
                assertEquals(
                    TvSettingsGridDestination.Tile(tileAtOrLastColumn(tile.row + 1, tile.column)),
                    down,
                )
            }
        }
    }

    @Test
    fun dialogPolicyFocusesCurrentEnabledOptionAndRestoresItsExactOpener() {
        assertEquals(
            "current",
            tvSettingsDialogInitialSelection("current", listOf("current" to true, "other" to true)),
        )
        assertEquals(
            "enabled",
            tvSettingsDialogInitialSelection("disabled", listOf("disabled" to false, "enabled" to true)),
        )
        assertEquals(
            "disabled",
            tvSettingsDialogInitialSelection("disabled", listOf("disabled" to false)),
        )
        assertEquals(TvSettingsTileId.OpenSubtitlesKey, tvSettingsDialogRestoreTarget(TvSettingsTileId.OpenSubtitlesKey))
        assertEquals(TvSettingsTileId.VerboseLogcat, tvSettingsDialogRestoreTarget(TvSettingsTileId.VerboseLogcat))
    }

    @Test
    fun dialogPolicyAppliesSelectOnlyOnceAndNeverWritesForBackOrDisabledOptions() {
        assertTrue(tvSettingsDialogApplies(TvSettingsDialogEvent.Select, optionEnabled = true, alreadyApplied = false))
        assertFalse(tvSettingsDialogApplies(TvSettingsDialogEvent.Select, optionEnabled = true, alreadyApplied = true))
        assertFalse(tvSettingsDialogApplies(TvSettingsDialogEvent.Back, optionEnabled = true, alreadyApplied = false))
        assertFalse(tvSettingsDialogApplies(TvSettingsDialogEvent.Select, optionEnabled = false, alreadyApplied = false))
    }

    @Test
    fun diagnosticsSendActionOnlyGuardsAgainstReentrancy() {
        assertTrue(tvClientLogsSendEnabled(isSending = false))
        assertFalse(tvClientLogsSendEnabled(isSending = true))
    }

    @Test
    fun candidateAudioAndHdrOptionsUseDetectedCapabilitiesInsteadOfTheCurrentSelection() {
        val unsupported = capabilities()
        val supported =
            capabilities(
                passthroughCodecs = listOf("ac3"),
                supportsHdr = true,
            )

        assertFalse(tvPlayerAudioModeAvailable(PlayerAudioMode.PassthroughWhenSupported, null))
        assertFalse(tvPlayerAudioModeAvailable(PlayerAudioMode.PassthroughWhenSupported, unsupported))
        assertTrue(tvPlayerAudioModeAvailable(PlayerAudioMode.PassthroughWhenSupported, supported))
        assertTrue(tvPlayerAudioModeAvailable(PlayerAudioMode.Auto, null))

        assertFalse(tvPlayerHdrModeAvailable(PlayerHdrMode.Auto, null))
        assertFalse(tvPlayerHdrModeAvailable(PlayerHdrMode.Auto, unsupported))
        assertTrue(tvPlayerHdrModeAvailable(PlayerHdrMode.Auto, supported))
        assertTrue(tvPlayerHdrModeAvailable(PlayerHdrMode.PreferSdr, null))
    }

    private fun tileAtOrLastColumn(
        row: Int,
        column: Int,
    ): TvSettingsTileId = tvSettingsGridRows[row][column.coerceAtMost(tvSettingsGridRows[row].lastIndex)]
}

private fun capabilities(
    passthroughCodecs: List<String> = emptyList(),
    supportsHdr: Boolean = false,
): DeviceDecodingCapabilities =
    DeviceDecodingCapabilities(
        videoCodecs = emptyList(),
        audioCodecs = emptyList(),
        supportsDolbyVision = false,
        audioPassthroughCodecs = passthroughCodecs,
        supportsHdr = supportsHdr,
    )
