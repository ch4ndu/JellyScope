// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAvPlayerBindingIdentityTest {
    @Test
    fun onlyExactOwnerPlayerAndPrepareEpochIdentityCanReuseABinding() {
        val owner = EqualIdentity("owner")
        val player = EqualIdentity("player")

        assertFalse(
            iosAvPlayerBindingChanged(
                currentOwner = owner,
                currentPlayer = player,
                currentPrepareEpoch = 7L,
                nextOwner = owner,
                nextPlayer = player,
                nextPrepareEpoch = 7L,
            ),
        )
        assertTrue(
            iosAvPlayerBindingChanged(owner, player, 7L, EqualIdentity("owner"), player, 7L),
        )
        assertTrue(
            iosAvPlayerBindingChanged(owner, player, 7L, owner, EqualIdentity("player"), 7L),
        )
        assertTrue(iosAvPlayerBindingChanged(owner, player, 7L, owner, player, 8L))
    }

    @Test
    fun presentationAndPictureInPictureValuesAreNotBindingIdentityInputs() {
        val owner = Any()
        val player = Any()

        repeat(3) {
            assertFalse(iosAvPlayerBindingChanged(owner, player, 9L, owner, player, 9L))
        }
    }

    private data class EqualIdentity(
        val value: String,
    )
}
