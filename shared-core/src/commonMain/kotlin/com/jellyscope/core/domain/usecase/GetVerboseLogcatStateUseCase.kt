// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import kotlinx.coroutines.flow.StateFlow

class GetVerboseLogcatStateUseCase(
    private val preferenceStore: LogCollectionPreferenceStore,
) {
    operator fun invoke(): StateFlow<Boolean> = preferenceStore.verboseLogcatEnabled
}
