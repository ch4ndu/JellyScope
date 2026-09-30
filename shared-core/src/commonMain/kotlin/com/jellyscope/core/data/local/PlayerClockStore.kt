// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import kotlinx.coroutines.flow.StateFlow

interface PlayerClockStore {
    val enabled: StateFlow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}
