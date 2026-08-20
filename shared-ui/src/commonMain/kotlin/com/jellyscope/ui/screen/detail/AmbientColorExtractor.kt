// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.ui.graphics.Color
import coil3.Image
import com.jellyscope.core.domain.model.Session

fun interface AmbientColorExtractor {
    suspend fun extract(
        image: Image,
        key: String,
    ): Color?
}

expect fun platformAmbientColorExtractor(): AmbientColorExtractor

internal fun ambientImageCacheKey(
    session: Session,
    itemId: String,
    imageUrl: String?,
): String =
    listOf(
        session.serverId,
        session.userId,
        itemId,
        imageUrl.orEmpty(),
    ).joinToString("|")
