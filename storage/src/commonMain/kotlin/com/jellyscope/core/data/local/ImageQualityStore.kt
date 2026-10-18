// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.ImageQuality
import kotlinx.coroutines.flow.StateFlow

interface ImageQualityStore {
    val imageQuality: StateFlow<ImageQuality>

    suspend fun setImageQuality(imageQuality: ImageQuality)
}
