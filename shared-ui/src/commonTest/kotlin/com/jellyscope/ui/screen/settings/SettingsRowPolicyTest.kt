// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SettingsRowPolicyTest {
    // Asserted in both directions on purpose. Checking only that these rows resolve
    // to None would still pass if a row added later were left unclassified, which is
    // exactly the mistake the chevron rule exists to prevent: a row with nothing to
    // open must not advertise a destination.
    @Test
    fun exactlyTheReadOnlyRowsHaveNoTrailingAction() {
        val readOnlyRows =
            setOf(
                SettingsRowId.Server,
                SettingsRowId.SignedInUser,
                SettingsRowId.VideoFormats,
                SettingsRowId.AudioFormats,
                SettingsRowId.AppVersion,
            )

        assertEquals(
            readOnlyRows,
            SettingsRowId.entries.filter { it.trailingKind() == SettingsRowTrailingKind.None }.toSet(),
        )
    }

    @Test
    fun exactlyTheBooleanRowsUseSwitchTrailingAction() {
        val booleanRows =
            setOf(
                SettingsRowId.RememberLastLibrary,
                SettingsRowId.AutoPlayNext,
                SettingsRowId.StillWatching,
                SettingsRowId.PlaybackWarnings,
                SettingsRowId.PictureInPicture,
                SettingsRowId.CollectLogs,
                SettingsRowId.SystemLogs,
                SettingsRowId.PlaybackInfoAtStart,
            )

        assertEquals(
            booleanRows,
            SettingsRowId.entries.filter { it.trailingKind() == SettingsRowTrailingKind.Switch }.toSet(),
        )
    }

    @Test
    fun exactlyTheImmediateActionRowsShowProgress() {
        val actionRows =
            setOf(
                SettingsRowId.RefreshCapabilities,
                SettingsRowId.ClearSubtitles,
                SettingsRowId.SendClientLogs,
            )

        assertEquals(
            actionRows,
            SettingsRowId.entries.filter { it.trailingKind() == SettingsRowTrailingKind.Progress }.toSet(),
        )
    }

    @Test
    fun downloadsRowOpensTheDownloadsDestination() {
        assertEquals(SettingsSectionId.Downloads, SettingsRowId.Downloads.section())
        assertEquals(SettingsRowTrailingKind.Chevron, SettingsRowId.Downloads.trailingKind())
    }

    @Test
    fun sectionMembershipCoversEveryRowExactlyOnce() {
        val rowsGroupedByDeclaredSection =
            SettingsSectionId.entries.flatMap { section ->
                SettingsRowId.entries.filter { row -> row.section() == section }
            }

        assertEquals(SettingsRowId.entries.toSet(), rowsGroupedByDeclaredSection.toSet())
        assertEquals(SettingsRowId.entries.size, rowsGroupedByDeclaredSection.size)
    }

    @Test
    fun maximumVideoResolutionUsesTheAdvancedPlaybackPicker() {
        assertEquals(SettingsSectionId.AdvancedPlayback, SettingsRowId.MaximumVideoResolution.section())
        assertEquals(SettingsRowTrailingKind.Chevron, SettingsRowId.MaximumVideoResolution.trailingKind())
    }

    @Test
    fun subtitleResultPreferenceIsAPrimaryServicesPicker() {
        assertEquals(SettingsSectionId.Services, SettingsRowId.SubtitleResultPreference.section())
        assertEquals(SettingsRowTrailingKind.Chevron, SettingsRowId.SubtitleResultPreference.trailingKind())
        assertEquals(SettingsRowIconRole.Primary, SettingsRowId.SubtitleResultPreference.iconRole())
    }

    @Test
    fun licenseRowOpensTheSourceAndNoticesDestination() {
        assertEquals(SettingsRowTrailingKind.Chevron, SettingsRowId.License.trailingKind())
        assertEquals(
            "https://github.com/ch4ndu/JellyScope/tree/0123456789abcdef",
            jellyScopeSourceUrl("0123456789abcdef"),
        )
        assertEquals(
            "https://github.com/ch4ndu/JellyScope/blob/0123456789abcdef/distribution/OPEN_SOURCE_NOTICES.md",
            jellyScopeNoticesUrl("0123456789abcdef"),
        )
    }

    // ComingLater renders planned-feature badge rows, which are not settings and so
    // own no SettingsRowId. Every other section must have at least one row, or the
    // grid would emit an empty card.
    @Test
    fun everySectionExceptComingLaterHasRows() {
        val sectionsWithRows = SettingsRowId.entries.map { it.section() }.toSet()

        assertEquals(SettingsSectionId.entries.toSet() - SettingsSectionId.ComingLater, sectionsWithRows)
    }

    @Test
    fun destructiveAndAccentRolesMatchTheTvGlyphSet() {
        assertEquals(SettingsRowIconRole.Destructive, SettingsRowId.SignOut.iconRole())
        assertEquals(
            setOf(
                SettingsRowId.Intros,
                SettingsRowId.Credits,
                SettingsRowId.Recaps,
                SettingsRowId.Previews,
                SettingsRowId.Commercials,
                SettingsRowId.ClearSubtitles,
                SettingsRowId.AppVersion,
            ),
            SettingsRowId.entries.filter { it.iconRole() == SettingsRowIconRole.Accent }.toSet(),
        )
    }

    @Test
    fun everySettingsRowResolvesToItsSharedCodeNativeGlyph() {
        val expected =
            mapOf(
                SettingsRowId.Server to SettingsIcons.Server,
                SettingsRowId.SignedInUser to SettingsIcons.SignedInUser,
                SettingsRowId.ActiveAccount to SettingsIcons.ActiveAccount,
                SettingsRowId.AddAccount to SettingsIcons.AddAccount,
                SettingsRowId.SignOut to SettingsIcons.SignOut,
                SettingsRowId.Theme to SettingsIcons.Theme,
                SettingsRowId.TileSize to SettingsIcons.TileSize,
                SettingsRowId.RememberLastLibrary to SettingsIcons.RememberLibrary,
                SettingsRowId.AudioLanguage to SettingsIcons.AudioLanguage,
                SettingsRowId.SubtitleLanguage to SettingsIcons.SubtitleLanguage,
                SettingsRowId.MaxBitrate to SettingsIcons.MaxBitrate,
                SettingsRowId.VlcTranscodeLimit to SettingsIcons.MaxBitrate,
                SettingsRowId.AutoPlayNext to SettingsIcons.ResumeBehavior,
                SettingsRowId.StillWatching to SettingsIcons.ResumeBehavior,
                SettingsRowId.PlaybackWarnings to SettingsIcons.ResumeBehavior,
                SettingsRowId.AutoPlayNextDelay to SettingsIcons.ResumeBehavior,
                SettingsRowId.PlayerBackend to SettingsIcons.VideoFormats,
                SettingsRowId.Intros to SettingsIcons.Intros,
                SettingsRowId.Credits to SettingsIcons.Credits,
                SettingsRowId.Recaps to SettingsIcons.Recaps,
                SettingsRowId.Previews to SettingsIcons.Previews,
                SettingsRowId.Commercials to SettingsIcons.Commercials,
                SettingsRowId.AudioOutput to SettingsIcons.AudioOutput,
                SettingsRowId.HdrHandling to SettingsIcons.HdrHandling,
                SettingsRowId.MaximumVideoResolution to SettingsIcons.VideoFormats,
                SettingsRowId.IosPlaybackCompatibility to SettingsIcons.VideoFormats,
                SettingsRowId.VideoFormats to SettingsIcons.VideoFormats,
                SettingsRowId.AudioFormats to SettingsIcons.AudioFormats,
                SettingsRowId.RefreshCapabilities to SettingsIcons.RefreshCapabilities,
                SettingsRowId.PictureInPicture to SettingsIcons.FocusedCardZoom,
                SettingsRowId.SubtitleResultPreference to SettingsIcons.SubtitleLanguage,
                SettingsRowId.OpenSubtitlesKey to SettingsIcons.OpenSubtitlesKey,
                SettingsRowId.ClearSubtitles to SettingsIcons.ClearSubtitles,
                SettingsRowId.CollectLogs to SettingsIcons.AboutJellyScope,
                SettingsRowId.SystemLogs to SettingsIcons.AboutJellyScope,
                SettingsRowId.PlaybackInfoAtStart to SettingsIcons.AboutJellyScope,
                SettingsRowId.SendClientLogs to SettingsIcons.AboutJellyScope,
                SettingsRowId.Downloads to SettingsIcons.VideoFormats,
                SettingsRowId.AppVersion to SettingsIcons.AppVersion,
                SettingsRowId.License to SettingsIcons.AboutJellyScope,
            )

        assertEquals(SettingsRowId.entries.toSet(), expected.keys)
        expected.forEach { (row, icon) -> assertSame(icon, row.icon()) }
    }
}
