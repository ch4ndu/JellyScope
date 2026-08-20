// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthGuidanceReason
import com.jellyscope.core.domain.playback.StreamMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerInteractionPolicyTest {
    @Test
    fun advisoryGuidanceIsDismissOnly() {
        val actions =
            playbackGuidanceActions(
                PlaybackHealthGuidance(
                    sessionToken = 1L,
                    reason = PlaybackHealthGuidanceReason.LongBuffering,
                ),
            )

        assertTrue(actions.dismiss)
        assertFalse(actions.navigateToSettings)
        assertFalse(actions.reduceQuality)
    }

    @Test
    fun actionableGuidanceOffersOnlyTheActionsAllowedByItsModel() {
        val actions =
            playbackGuidanceActions(
                PlaybackHealthGuidance(
                    sessionToken = 1L,
                    reason = PlaybackHealthGuidanceReason.RepeatedStalls,
                    streamMode = StreamMode.DirectPlay,
                    nextLowerQualityRungBps = 8_000_000L,
                    canReduceQuality = true,
                    canOpenPlaybackSettings = true,
                ),
            )

        assertTrue(actions.dismiss)
        assertTrue(actions.navigateToSettings)
        assertTrue(actions.reduceQuality)
    }

    @Test
    fun guidanceOmitsReduceWhenThereIsNoLowerCanonicalRung() {
        val actions =
            playbackGuidanceActions(
                PlaybackHealthGuidance(
                    sessionToken = 2L,
                    reason = PlaybackHealthGuidanceReason.LongBuffering,
                    streamMode = StreamMode.Transcode,
                    canReduceQuality = true,
                    canOpenPlaybackSettings = true,
                ),
            )

        assertTrue(actions.navigateToSettings)
        assertFalse(actions.reduceQuality)
    }

    @Test
    fun guidanceWordingIsStreamAwareWithoutClaimingACause() {
        fun wording(
            reason: PlaybackHealthGuidanceReason,
            mode: StreamMode?,
        ): PlayerPlaybackGuidanceWording =
            PlaybackHealthGuidance(
                sessionToken = 3L,
                reason = reason,
                streamMode = mode,
            ).wordingPolicy()

        assertEquals(
            PlayerPlaybackGuidanceWording.SlowStartup,
            wording(PlaybackHealthGuidanceReason.SlowStartup, StreamMode.DirectPlay),
        )
        assertEquals(
            PlayerPlaybackGuidanceWording.DirectPlayCapacity,
            wording(PlaybackHealthGuidanceReason.DroppedFrames, StreamMode.DirectPlay),
        )
        assertEquals(
            PlayerPlaybackGuidanceWording.StreamingPressure,
            wording(PlaybackHealthGuidanceReason.RepeatedStalls, StreamMode.DirectStream),
        )
        assertEquals(
            PlayerPlaybackGuidanceWording.StreamingPressure,
            wording(PlaybackHealthGuidanceReason.RepeatedStalls, StreamMode.Transcode),
        )
        assertEquals(
            PlayerPlaybackGuidanceWording.PlaybackTakingLonger,
            wording(PlaybackHealthGuidanceReason.LongBuffering, null),
        )
    }

    @Test
    fun backClosesPickerThenControlsThenPlayer() {
        assertEquals(
            PlayerBackAction.ClosePicker,
            playerBackAction(PlayerPicker.Audio, controlsVisible = true),
        )
        assertEquals(
            PlayerBackAction.HideControls,
            playerBackAction(PlayerPicker.None, controlsVisible = true),
        )
        assertEquals(
            PlayerBackAction.ExitPlayer,
            playerBackAction(PlayerPicker.None, controlsVisible = false),
        )
    }

    @Test
    fun firstTapInTheCentreTogglesControlsImmediately() {
        assertEquals(
            PlayerTapAction.ToggleControlsNow,
            playerTapAction(zone = PlayerTapZone.Center, lastZone = null, msSinceLastTap = null),
        )
    }

    @Test
    fun secondCentreTapInsideTheDoubleTapWindowIsIgnoredSoControlsDoNotFlicker() {
        assertEquals(
            PlayerTapAction.Ignore,
            playerTapAction(zone = PlayerTapZone.Center, lastZone = PlayerTapZone.Center, msSinceLastTap = 120L),
        )
    }

    @Test
    fun centreTapAfterTheDoubleTapWindowTogglesAgain() {
        assertEquals(
            PlayerTapAction.ToggleControlsNow,
            playerTapAction(zone = PlayerTapZone.Center, lastZone = PlayerTapZone.Center, msSinceLastTap = 400L),
        )
    }

    @Test
    fun sideTapsWaitForTheDoubleTapWindowBeforeTogglingControls() {
        assertEquals(
            PlayerTapAction.ScheduleToggleControls,
            playerTapAction(zone = PlayerTapZone.Left, lastZone = null, msSinceLastTap = null),
        )
        assertEquals(
            PlayerTapAction.ScheduleToggleControls,
            playerTapAction(zone = PlayerTapZone.Right, lastZone = PlayerTapZone.Right, msSinceLastTap = 400L),
        )
    }

    @Test
    fun doubleTapOnASideSeeksInThatDirection() {
        assertEquals(
            PlayerTapAction.SeekBackward,
            playerTapAction(zone = PlayerTapZone.Left, lastZone = PlayerTapZone.Left, msSinceLastTap = 200L),
        )
        assertEquals(
            PlayerTapAction.SeekForward,
            playerTapAction(zone = PlayerTapZone.Right, lastZone = PlayerTapZone.Right, msSinceLastTap = 200L),
        )
    }

    @Test
    fun tapsInDifferentZonesAreNeverADoubleTap() {
        assertEquals(
            PlayerTapAction.ScheduleToggleControls,
            playerTapAction(zone = PlayerTapZone.Right, lastZone = PlayerTapZone.Left, msSinceLastTap = 100L),
        )
        assertEquals(
            PlayerTapAction.ToggleControlsNow,
            playerTapAction(zone = PlayerTapZone.Center, lastZone = PlayerTapZone.Left, msSinceLastTap = 100L),
        )
    }

    @Test
    fun fullscreenDesktopCursorAutoHidesUnlessAUiModeNeedsIt() {
        assertTrue(
            shouldAutoHidePlayerCursor(
                desktopPlayerControls = true,
                fullscreen = true,
                picker = PlayerPicker.None,
                pictureInPicture = false,
            ),
        )
        assertFalse(
            shouldAutoHidePlayerCursor(
                desktopPlayerControls = true,
                fullscreen = true,
                picker = PlayerPicker.Chapters,
                pictureInPicture = false,
            ),
        )
        assertFalse(
            shouldAutoHidePlayerCursor(
                desktopPlayerControls = true,
                fullscreen = false,
                picker = PlayerPicker.None,
                pictureInPicture = false,
            ),
        )
    }

    @Test
    fun subtitleClearanceIsActiveForVisibleControlsWithoutAPicker() {
        assertTrue(
            isSubtitleClearanceActive(
                controlsVisible = true,
                picker = PlayerPicker.None,
                pictureInPicture = false,
            ),
        )
    }

    @Test
    fun subtitleClearanceStaysActiveWhenAPickerIsOpenAfterControlsAutoHide() {
        assertTrue(
            isSubtitleClearanceActive(
                controlsVisible = false,
                picker = PlayerPicker.Subtitles,
                pictureInPicture = false,
            ),
        )
    }

    @Test
    fun subtitleClearanceIsInactiveWhenControlsAndPickerAreHidden() {
        assertFalse(
            isSubtitleClearanceActive(
                controlsVisible = false,
                picker = PlayerPicker.None,
                pictureInPicture = false,
            ),
        )
    }

    @Test
    fun pictureInPictureDisablesSubtitleClearanceRegardlessOfOtherInputs() {
        assertFalse(
            isSubtitleClearanceActive(
                controlsVisible = true,
                picker = PlayerPicker.Audio,
                pictureInPicture = true,
            ),
        )
    }
}
