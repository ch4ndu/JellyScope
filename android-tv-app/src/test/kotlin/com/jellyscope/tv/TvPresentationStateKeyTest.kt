// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import com.jellyscope.tv.ui.focus.TvRouteEntryId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TvPresentationStateKeyTest {
    @Test
    fun overlappingRouteEntriesNeverUseTheSameSaveableStateHolderKey() {
        val outgoing = TvRouteRenderKey(TvRoute.Library.name, TvRouteEntryId(8))
        val incoming = TvRouteRenderKey(TvRoute.Library.name, TvRouteEntryId(9))

        assertNotEquals(
            tvPresentationStateKey("library-id", outgoing, presentationEpoch = 4),
            tvPresentationStateKey("library-id", incoming, presentationEpoch = 4),
        )
    }

    @Test
    fun theSameBackStackEntryReusesItsPresentationStateKey() {
        val parent = TvRouteRenderKey(TvRoute.Library.name, TvRouteEntryId(8))

        assertEquals(
            tvPresentationStateKey("library-id", parent, presentationEpoch = 4),
            tvPresentationStateKey("library-id", parent, presentationEpoch = 4),
        )
    }
}
