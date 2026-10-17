// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSelectionGroupLoadPolicyTest {
    @Test
    fun notLoadedWaits() {
        assertEquals(
            MediaSelectionGroupLoadAction.Wait,
            resolveMediaSelectionGroupLoadAction(MediaSelectionGroupLoadState.NotLoaded),
        )
    }

    @Test
    fun loadingWaits() {
        assertEquals(
            MediaSelectionGroupLoadAction.Wait,
            resolveMediaSelectionGroupLoadAction(MediaSelectionGroupLoadState.Loading),
        )
    }

    @Test
    fun loadedPresentMaps() {
        assertEquals(
            MediaSelectionGroupLoadAction.Map,
            resolveMediaSelectionGroupLoadAction(MediaSelectionGroupLoadState.LoadedPresent),
        )
    }

    @Test
    fun loadedAbsentRecovers() {
        assertEquals(
            MediaSelectionGroupLoadAction.Recover,
            resolveMediaSelectionGroupLoadAction(MediaSelectionGroupLoadState.LoadedAbsent),
        )
    }
}
