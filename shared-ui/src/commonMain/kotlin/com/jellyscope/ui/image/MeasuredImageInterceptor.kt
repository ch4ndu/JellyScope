// SPDX-License-Identifier: MPL-2.0
package com.jellyscope.ui.image

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import com.jellyscope.ui.component.MeasuredImageRequestKey

// Preserve the request's authentication and account cache identity while lowering
// only the existing Jellyfin image-width parameter and measured decode variant.
internal class MeasuredImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.extras[MeasuredImageRequestKey] != true) return chain.proceed()
        val width = (chain.size.width as? Dimension.Pixels)?.px
        val height = (chain.size.height as? Dimension.Pixels)?.px
        val data = request.data
        val resizedData = if (data is String && width != null) lowerImageMaxWidth(data, width) else data
        val variant = ":measured:${width ?: "auto"}x${height ?: "auto"}"
        val resized =
            request
                .newBuilder()
                .data(resizedData)
                .apply {
                    request.memoryCacheKey?.let { memoryCacheKey(it + variant) }
                    request.diskCacheKey?.let { diskCacheKey(it + variant) }
                }.build()
        return chain.withRequest(resized).proceed()
    }
}

internal fun lowerImageMaxWidth(
    url: String,
    width: Int,
): String =
    IMAGE_WIDTH.replace(url) { match ->
        val cappedWidth = minOf(width, match.groupValues[2].toIntOrNull() ?: width)
        "${match.groupValues[1]}maxWidth=$cappedWidth"
    }

private val IMAGE_WIDTH = Regex("([?&])maxWidth=([0-9]+)", RegexOption.IGNORE_CASE)
