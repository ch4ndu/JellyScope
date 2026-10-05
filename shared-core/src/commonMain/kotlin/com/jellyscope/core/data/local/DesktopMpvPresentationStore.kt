// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.DesktopMpvPresentation
import com.jellyscope.core.domain.model.DesktopMpvPresentationState
import kotlinx.coroutines.flow.StateFlow

/** Desktop-only; other platforms bind no implementation. */
interface DesktopMpvPresentationStore {
    val state: StateFlow<DesktopMpvPresentationState>

    /**
     * Publishes [presentation] as selected only after it is durably written.
     * A started write completes even if the caller is cancelled. Throws on a
     * storage failure, leaving the previous selection published. Requests while
     * [state] reports a pending save are ignored.
     */
    suspend fun select(presentation: DesktopMpvPresentation)
}
