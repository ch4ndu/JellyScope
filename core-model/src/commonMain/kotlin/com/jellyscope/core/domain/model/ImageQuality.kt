// SPDX-License-Identifier: MPL-2.0
package com.jellyscope.core.domain.model

enum class ImageQuality(
    val decodeScale: Float,
) {
    Low(0.6f),
    Medium(0.75f),
    High(1f),
}
