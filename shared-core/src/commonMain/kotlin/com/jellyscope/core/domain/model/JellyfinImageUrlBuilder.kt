// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

class JellyfinImageUrlBuilder {
    fun build(
        serverUrl: String,
        itemId: String,
        type: JellyfinImageType,
        tag: String?,
        maxWidth: Int,
        quality: Int = 90,
    ): String {
        val baseUrl = serverUrl.trimEnd('/')
        val tagParameter = tag?.let { "tag=$it&" }.orEmpty()
        return "$baseUrl/Items/$itemId/Images/${type.apiValue}?${tagParameter}maxWidth=$maxWidth" +
            "&quality=$quality"
    }

    fun personPrimary(
        serverUrl: String,
        personId: String,
        tag: String?,
        maxWidth: Int = 200,
        quality: Int = 90,
    ): String =
        build(
            serverUrl = serverUrl,
            itemId = personId,
            type = JellyfinImageType.Primary,
            tag = tag,
            maxWidth = maxWidth,
            quality = quality,
        )

    fun trickplayTileUrl(
        serverUrl: String,
        itemId: String,
        width: Int,
        index: Int,
    ): String {
        val baseUrl = serverUrl.trimEnd('/')
        return "$baseUrl/Videos/$itemId/Trickplay/$width/${index.coerceAtLeast(0)}.jpg"
    }
}

enum class JellyfinImageType(
    val apiValue: String,
) {
    Primary("Primary"),
    Backdrop("Backdrop"),
    Logo("Logo"),
}
