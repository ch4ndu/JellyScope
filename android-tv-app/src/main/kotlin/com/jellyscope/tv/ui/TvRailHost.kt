// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester

@Stable
internal class TvHostedRailController(
    private val enabledState: State<Boolean>,
    private val visibleState: State<Boolean>,
    private val railHasFocusState: State<Boolean>,
    private val contentAutofocusSuppressedState: State<Boolean>,
    private val contentRegistrationKeyState: State<String>,
    private val requestRailFocusState: State<() -> Boolean>,
    private val setContentRightFocusRequesterState: State<(String, FocusRequester?) -> Unit>,
    private val setContentRightFocusActionState: State<(String, (() -> Boolean)?) -> Unit>,
) {
    val enabled: Boolean
        get() = enabledState.value

    val visible: Boolean
        get() = visibleState.value

    val railHasFocus: Boolean
        get() = railHasFocusState.value

    val contentAutofocusSuppressed: Boolean
        get() = contentAutofocusSuppressedState.value

    val contentRegistrationKey: String
        get() = contentRegistrationKeyState.value

    fun requestRailFocus(): Boolean = requestRailFocusState.value()

    fun setContentRightFocusRequester(
        registrationKey: String,
        requester: FocusRequester?,
    ) {
        setContentRightFocusRequesterState.value(registrationKey, requester)
    }

    fun setContentRightFocusAction(
        registrationKey: String,
        action: (() -> Boolean)?,
    ) {
        setContentRightFocusActionState.value(registrationKey, action)
    }
}

private val DisabledTvHostedRailController =
    TvHostedRailController(
        enabledState = mutableStateOf(false),
        visibleState = mutableStateOf(false),
        railHasFocusState = mutableStateOf(false),
        contentAutofocusSuppressedState = mutableStateOf(false),
        contentRegistrationKeyState = mutableStateOf(""),
        requestRailFocusState = mutableStateOf({ false }),
        setContentRightFocusRequesterState = mutableStateOf({ _, _ -> }),
        setContentRightFocusActionState = mutableStateOf({ _, _ -> }),
    )

internal val LocalTvHostedRailController = compositionLocalOf { DisabledTvHostedRailController }

internal sealed interface TvRailTarget {
    data object Find : TvRailTarget

    data object Discover : TvRailTarget

    data object Favorites : TvRailTarget

    data object Home : TvRailTarget

    data object Downloads : TvRailTarget

    data object Settings : TvRailTarget

    data class Library(
        val id: String,
    ) : TvRailTarget
}

internal fun TvRailDestination.toRailTarget(): TvRailTarget =
    when (this) {
        TvRailDestination.Find -> TvRailTarget.Find
        TvRailDestination.Discover -> TvRailTarget.Discover
        TvRailDestination.Favorites -> TvRailTarget.Favorites
        TvRailDestination.Home -> TvRailTarget.Home
        TvRailDestination.Downloads -> TvRailTarget.Downloads
        TvRailDestination.Settings -> TvRailTarget.Settings
    }
