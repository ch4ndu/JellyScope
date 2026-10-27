// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.login

sealed interface QuickConnectUiState {
    data object Idle : QuickConnectUiState

    data class CodeShown(
        val secret: String,
        val code: String,
        val displayCode: String,
    ) : QuickConnectUiState

    data class Polling(
        val secret: String,
        val code: String,
        val displayCode: String,
    ) : QuickConnectUiState

    data object Success : QuickConnectUiState

    data class Error(
        val error: QuickConnectUiError,
    ) : QuickConnectUiState
}

sealed interface QuickConnectUiError {
    data object Expired : QuickConnectUiError

    data object Unavailable : QuickConnectUiError

    data object ServerError : QuickConnectUiError
}

sealed interface QuickConnectIntent {
    data object Start : QuickConnectIntent

    data object Cancel : QuickConnectIntent
}

internal sealed interface QuickConnectChange {
    class Updated(
        val state: QuickConnectUiState,
    ) : QuickConnectChange

    data object Cancelled : QuickConnectChange
}

internal fun reduceQuickConnect(
    current: QuickConnectUiState,
    change: QuickConnectChange,
): QuickConnectUiState =
    when (change) {
        is QuickConnectChange.Updated -> change.state
        QuickConnectChange.Cancelled -> if (current == QuickConnectUiState.Success) current else QuickConnectUiState.Idle
    }
