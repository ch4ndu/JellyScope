// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.util.LogBufferSize
import com.jellyscope.core.util.LogBufferStore
import kotlinx.coroutines.flow.StateFlow

class GetLogCollectionStateUseCase(
    private val preferenceStore: LogCollectionPreferenceStore,
    private val logBufferStore: LogBufferStore,
) {
    operator fun invoke(): StateFlow<Boolean> = preferenceStore.enabled

    fun bufferSize(): StateFlow<LogBufferSize> = logBufferStore.size
}
