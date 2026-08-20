// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JellyfinImageUrlBuilderTest {
    @Test
    fun buildsExactImageUrlWithoutToken() {
        val url =
            JellyfinImageUrlBuilder().build(
                serverUrl = "https://jellyfin.example/",
                itemId = "item-1",
                type = JellyfinImageType.Primary,
                tag = "tag-1",
                maxWidth = 300,
            )

        assertEquals(
            "https://jellyfin.example/Items/item-1/Images/Primary?tag=tag-1&maxWidth=300&quality=90",
            url,
        )
        assertFalse(url.contains("token", ignoreCase = true))
        assertFalse(url.contains("api_key", ignoreCase = true))
    }

    @Test
    fun reconstructsAfterServerUrlChange() {
        val builder = JellyfinImageUrlBuilder()

        val url =
            builder.build(
                serverUrl = "https://new.example/base",
                itemId = "item-1",
                type = JellyfinImageType.Backdrop,
                tag = "tag-2",
                maxWidth = 600,
            )

        assertEquals(
            "https://new.example/base/Items/item-1/Images/Backdrop?tag=tag-2&maxWidth=600&quality=90",
            url,
        )
    }

    @Test
    fun buildsPersonPrimaryImageUrl() {
        val url =
            JellyfinImageUrlBuilder().personPrimary(
                serverUrl = "https://jellyfin.example/",
                personId = "person-1",
                tag = "person-tag",
            )

        assertEquals(
            "https://jellyfin.example/Items/person-1/Images/Primary?tag=person-tag&maxWidth=200&quality=90",
            url,
        )
        assertFalse(url.contains("token", ignoreCase = true))
    }
}
