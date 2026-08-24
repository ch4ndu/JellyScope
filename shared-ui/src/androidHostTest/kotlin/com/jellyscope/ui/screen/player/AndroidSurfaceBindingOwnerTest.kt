// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AndroidSurfaceBindingOwnerTest {
    @Test
    fun bindingIdentityUsesExactControllerAndPlatformPlayerReferences() {
        val firstController = EqualIdentity("controller")
        val equalController = EqualIdentity("controller")
        val firstPlayer = EqualIdentity("player")
        val equalPlayer = EqualIdentity("player")

        assertEquals(
            AndroidSurfaceBindingIdentityKey(firstController, firstPlayer),
            AndroidSurfaceBindingIdentityKey(firstController, firstPlayer),
        )
        assertNotEquals(
            AndroidSurfaceBindingIdentityKey(firstController, firstPlayer),
            AndroidSurfaceBindingIdentityKey(equalController, firstPlayer),
        )
        assertNotEquals(
            AndroidSurfaceBindingIdentityKey(firstController, firstPlayer),
            AndroidSurfaceBindingIdentityKey(firstController, equalPlayer),
        )
    }

    @Test
    fun replacementDetachesBeforeAttachAndStaleOrForeignReleaseIsHarmless() {
        val owner = AndroidSurfaceBindingOwner()
        val foreignOwner = AndroidSurfaceBindingOwner()
        val first = owner.newToken()
        val events = mutableListOf<String>()
        owner.bind(first, detach = { events += "detach-first" }, attach = { events += "attach-first" })

        val second = owner.newToken()
        owner.bind(second, detach = { events += "detach-second" }, attach = { events += "attach-second" })
        owner.release(first)
        foreignOwner.release(second)
        owner.release(second)

        assertEquals(
            listOf("attach-first", "detach-first", "attach-second", "detach-second"),
            events,
        )
    }

    @Test
    fun oneBindingTokenAttachesOnceUntilItsTrueIdentityIsReplaced() {
        val owner = AndroidSurfaceBindingOwner()
        val token = owner.newToken()
        var attachCount = 0
        var detachCount = 0

        repeat(3) {
            owner.bind(
                token = token,
                detach = { detachCount += 1 },
                attach = { attachCount += 1 },
            )
        }

        assertEquals(1, attachCount)
        assertEquals(0, detachCount)
        owner.release(token)
        assertEquals(1, detachCount)
    }

    @Test
    fun delayedStaleAttachAndReleaseCannotTouchTheCurrentBinding() {
        val owner = AndroidSurfaceBindingOwner()
        val first = owner.newToken()
        val second = owner.newToken()
        val events = mutableListOf<String>()
        var delayedFirstAttach: (() -> Unit)? = null
        owner.bind(
            token = first,
            detach = { events += "detach-first" },
            attach = {
                delayedFirstAttach = {
                    if (owner.isCurrent(first)) events += "attach-first"
                }
            },
        )
        owner.bind(
            token = second,
            detach = { events += "detach-second" },
            attach = { events += "attach-second" },
        )

        delayedFirstAttach?.invoke()
        owner.release(first)

        assertEquals(listOf("detach-first", "attach-second"), events)
        assertEquals(true, owner.isCurrent(second))
    }

    @Test
    fun newerActivePlayerPublicationCannotBeOverwrittenOrClearedByAStaleOwner() {
        val owner = AndroidActivePlayerRegistrationOwner()
        val first = owner.newRegistration()
        val second = owner.newRegistration()

        assertEquals(true, owner.acceptPublication(first))
        assertEquals(true, owner.acceptPublication(second))
        assertEquals(false, owner.acceptPublication(first))
        assertEquals(false, owner.release(first))
        assertEquals(true, owner.acceptPublication(second))
        assertEquals(true, owner.release(second))
        assertEquals(false, owner.acceptPublication(second))
    }

    private data class EqualIdentity(
        val value: String,
    )
}
