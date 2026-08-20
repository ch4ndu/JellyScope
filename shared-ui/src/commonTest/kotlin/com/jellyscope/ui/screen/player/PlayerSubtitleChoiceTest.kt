// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlaybackStatus
import com.jellyscope.core.domain.playback.PlayerTimingState
import com.jellyscope.core.domain.playback.PlayerTimingSupport
import com.jellyscope.core.domain.playback.PlayerTimingValue
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSubtitleChoiceTest {
    @Test
    fun pickerChoiceRequiresServerOrLocalTrackInAdditionToImplicitOff() {
        assertFalse(content().hasSubtitlePickerChoice())
        assertTrue(
            content(
                subtitleOptions =
                    listOf(
                        SubtitleTrackOption(
                            streamIndex = 2,
                            ordinal = 0,
                            displayName = "English",
                            language = "eng",
                            isDefault = false,
                            isExternal = false,
                        ),
                    ),
            ).hasSubtitlePickerChoice(),
        )
        assertTrue(content(localSubtitleOptions = listOf(localSubtitle())).hasSubtitlePickerChoice())
    }

    @Test
    fun supportedSubtitleTimingDoesNotManufactureAChoice() {
        // Every Android/desktop backend reports subtitle timing as Supported, so
        // an item with no subtitle track at all must still report no choice —
        // otherwise the subtitle button shows and its picker closes on open.
        assertFalse(
            content(
                timingState =
                    PlayerTimingState(
                        audio = PlayerTimingValue(support = PlayerTimingSupport.Supported),
                        subtitle = PlayerTimingValue(support = PlayerTimingSupport.Supported),
                    ),
            ).hasSubtitlePickerChoice(),
        )
    }
}

private fun content(
    subtitleOptions: List<SubtitleTrackOption> = emptyList(),
    localSubtitleOptions: List<LocalSubtitleAsset> = emptyList(),
    timingState: PlayerTimingState = PlayerTimingState.Unsupported,
) = PlayerUiState.Content(
    playbackState =
        PlaybackState(
            status = PlaybackStatus.Paused,
            positionMs = 0L,
            durationMs = 1_000L,
            bufferedPositionMs = 0L,
        ),
    subtitleOptions = subtitleOptions,
    localSubtitleOptions = localSubtitleOptions,
    timingState = timingState,
)

private fun localSubtitle() =
    LocalSubtitleAsset(
        id = "local-1",
        serverId = "server",
        userId = "user",
        itemId = "item",
        mediaSourceId = "source",
        provider = "opensubtitles",
        providerSubtitleId = "subtitle",
        providerFileId = "file",
        language = "eng",
        label = "English",
        releaseName = null,
        originalFormat = "vtt",
        mimeType = "text/vtt",
        fileId = "local.vtt",
        hearingImpaired = false,
        forced = false,
        trusted = true,
        createdAtEpochMs = 1L,
        lastUsedAtEpochMs = 1L,
        syncState = LocalSubtitleSyncState.LocalOnlyAlternateSource,
    )
