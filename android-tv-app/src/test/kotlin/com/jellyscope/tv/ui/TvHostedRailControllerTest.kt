// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvHostedRailControllerTest {
    @Test
    fun stableFacadeReadsLatestStateAndCallbacks() {
        val visible = mutableStateOf(false)
        val registrationKey = mutableStateOf("home")
        val requestFocus = mutableStateOf<() -> Boolean>({ false })
        var registeredRequesterKey: String? = null
        val setRequester =
            mutableStateOf<(String, FocusRequester?) -> Unit>(
                value = { key, _ -> registeredRequesterKey = key },
            )
        val controller =
            TvHostedRailController(
                enabledState = mutableStateOf(true),
                visibleState = visible,
                railHasFocusState = mutableStateOf(false),
                contentAutofocusSuppressedState = mutableStateOf(false),
                contentRegistrationKeyState = registrationKey,
                requestRailFocusState = requestFocus,
                setContentRightFocusRequesterState = setRequester,
                setContentRightFocusActionState =
                    mutableStateOf<(String, (() -> Boolean)?) -> Unit>(
                        value = { _, _ -> },
                    ),
            )

        assertFalse(controller.visible)
        assertFalse(controller.requestRailFocus())
        visible.value = true
        registrationKey.value = "library"
        requestFocus.value = { true }
        controller.setContentRightFocusRequester("library", null)

        assertTrue(controller.visible)
        assertEquals("library", controller.contentRegistrationKey)
        assertTrue(controller.requestRailFocus())
        assertEquals("library", registeredRequesterKey)
    }
}
