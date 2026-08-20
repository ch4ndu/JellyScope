// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import org.junit.Test
import kotlin.test.assertEquals

class TvPlayerOverlayPolicyTest {
    @Test
    fun focusedDismissRestoresPlayerFocusWhenGuidanceLeavesComposition() {
        val focusRestorer = TvPlaybackGuidanceFocusRestorer()
        var restorationCount = 0

        focusRestorer.onActionFocusChanged(isFocused = true)
        focusRestorer.restoreFocusIfActionWasFocused { restorationCount += 1 }

        assertEquals(1, restorationCount)
    }

    @Test
    fun focusedDismissRestorationRunsOnlyOnceAcrossClickAndNoticeRemoval() {
        val focusRestorer = TvPlaybackGuidanceFocusRestorer()
        var restorationCount = 0

        focusRestorer.onActionFocusChanged(isFocused = true)
        focusRestorer.restoreFocusIfActionWasFocused { restorationCount += 1 }
        focusRestorer.restoreFocusIfActionWasFocused { restorationCount += 1 }

        assertEquals(1, restorationCount)
    }

    @Test
    fun unfocusedGuidanceRemovalLeavesPlayerFocusUntouched() {
        val focusRestorer = TvPlaybackGuidanceFocusRestorer()
        var restorationCount = 0

        focusRestorer.onActionFocusChanged(isFocused = false)
        focusRestorer.restoreFocusIfActionWasFocused { restorationCount += 1 }

        assertEquals(0, restorationCount)
    }

    @Test
    fun backPreservesModalPrecedenceAndHidesVisibleControls() {
        assertEquals(
            TvPlayerBackAction.CloseLocalMenu,
            backAction(localMenuOpen = true, pickerOpen = true, upNextVisible = true),
        )
        assertEquals(
            TvPlayerBackAction.ClosePicker,
            backAction(pickerOpen = true, upNextVisible = true),
        )
        assertEquals(
            TvPlayerBackAction.DismissUpNext,
            backAction(upNextVisible = true),
        )
        assertEquals(
            TvPlayerBackAction.HideOverlay,
            backAction(controlsVisible = true),
        )
        assertEquals(
            TvPlayerBackAction.ExitPlayer,
            backAction(),
        )
    }
}

private fun backAction(
    localMenuOpen: Boolean = false,
    pickerOpen: Boolean = false,
    upNextVisible: Boolean = false,
    controlsVisible: Boolean = false,
    queueOpen: Boolean = false,
): TvPlayerBackAction =
    tvPlayerBackAction(
        localMenuOpen = localMenuOpen,
        pickerOpen = pickerOpen,
        upNextVisible = upNextVisible,
        controlsVisible = controlsVisible,
        queueOpen = queueOpen,
    )
