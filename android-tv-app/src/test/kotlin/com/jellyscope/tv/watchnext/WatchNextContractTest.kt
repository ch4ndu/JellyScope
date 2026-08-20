// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.JellyfinImageType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class WatchNextContractTest {
    private val context = RuntimeEnvironment.getApplication()
    private val first = AccountIdentity("server-1", "user-1")
    private val sibling = AccountIdentity("server-1", "user-2")

    @Test
    fun payloadAndPosterIdentityIncludeTheCapturedAccount() {
        val firstPayload = WatchNextContract.accountPayload(first, "item-1")
        val siblingPayload = WatchNextContract.accountPayload(sibling, "item-1")

        assertEquals(WatchNextPayload("server-1", "user-1", "item-1"), WatchNextContract.parseAccountPayload(firstPayload))
        assertNotEquals(firstPayload, siblingPayload)
        assertNotEquals(
            WatchNextContract.posterUri(context, first, "item-1"),
            WatchNextContract.posterUri(context, sibling, "item-1"),
        )
        assertNotEquals(
            WatchNextContract.posterFile(context, first, "item-1"),
            WatchNextContract.posterFile(context, first, "item-2"),
        )
    }

    @Test
    fun encodedPayloadRoundTripsSeparatorsAndIntentCarriesOnlyQualifiedNavigation() {
        val identity = AccountIdentity("server|remote", "user/one")
        val itemId = "item|one"
        val intent = WatchNextContract.launchIntent(context, identity, itemId)

        assertEquals(
            WatchNextPayload(identity.serverId, identity.userId, itemId),
            WatchNextContract.parseAccountPayload(intent.getStringExtra(EXTRA_ACCOUNT_PAYLOAD)),
        )
        assertEquals(itemId, intent.getStringExtra(EXTRA_ITEM_ID))
    }

    @Test
    fun malformedPayloadsFailClosed() {
        assertNull(WatchNextContract.parseAccountPayload(null))
        assertNull(WatchNextContract.parseAccountPayload("jellyscope-watch-next|server-1|user-1"))
        assertNull(WatchNextContract.parseAccountPayload("other|server-1|user-1|item-1"))
        assertNull(WatchNextContract.parseAccountPayload("jellyscope-watch-next|server-1|user-1|"))
    }

    @Test
    fun posterProviderPathShapeIsAccountQualified() {
        val pathSegments = WatchNextContract.posterUri(context, first, "item-1").pathSegments

        assertEquals(listOf(POSTER_PATH, "server-1", "user-1", "item-1"), pathSegments)
    }

    @Test
    fun changedImageTagUsesASeparateAtomicCacheTarget() {
        val firstImage = PosterImageSource(JellyfinImageType.Primary, "tag-a", POSTER_WIDTH_PX)
        val changedImage = PosterImageSource(JellyfinImageType.Primary, "tag-b", POSTER_WIDTH_PX)

        assertNotEquals(
            WatchNextContract.posterFile(context, first, "item-1", firstImage),
            WatchNextContract.posterFile(context, first, "item-1", changedImage),
        )
        assertNotEquals(
            WatchNextContract.posterUri(context, first, "item-1", firstImage),
            WatchNextContract.posterUri(context, first, "item-1", changedImage),
        )
    }
}
