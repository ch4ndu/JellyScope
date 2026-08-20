// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.action.QuickConnectLoginAction
import com.jellyscope.core.domain.model.AuthError
import com.jellyscope.core.domain.model.QuickConnectCode
import com.jellyscope.core.domain.model.QuickConnectLoginUpdate
import com.jellyscope.core.domain.model.ServerInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

class QuickConnectViewModel(
    private val serverInfo: ServerInfo,
    private val quickConnectLoginAction: QuickConnectLoginAction,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val _state = MutableStateFlow<QuickConnectUiState>(QuickConnectUiState.Idle)
    val state: StateFlow<QuickConnectUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) {
            return
        }

        job =
            viewModelScope.launch {
                quickConnectLoginAction(serverInfo).flowOn(workDispatcher).collect { result ->
                    result.fold(
                        onSuccess = { update ->
                            _state.value = update.toUiState()
                        },
                        onFailure = { throwable ->
                            _state.value = QuickConnectUiState.Error(throwable.toUiError())
                        },
                    )
                }
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { state ->
            when (state) {
                QuickConnectUiState.Success -> state
                else -> QuickConnectUiState.Idle
            }
        }
    }
}

private fun QuickConnectLoginUpdate.toUiState(): QuickConnectUiState =
    when (this) {
        is QuickConnectLoginUpdate.CodeAvailable -> code.toCodeShownState()
        is QuickConnectLoginUpdate.Polling -> code.toPollingState()
        is QuickConnectLoginUpdate.Success -> QuickConnectUiState.Success
    }

private fun QuickConnectCode.toCodeShownState(): QuickConnectUiState.CodeShown =
    QuickConnectUiState.CodeShown(
        secret = secret,
        code = code,
        displayCode = code.formatQuickConnectCode(),
    )

private fun QuickConnectCode.toPollingState(): QuickConnectUiState.Polling =
    QuickConnectUiState.Polling(
        secret = secret,
        code = code,
        displayCode = code.formatQuickConnectCode(),
    )

private fun Throwable.toUiError(): QuickConnectUiError =
    when (this) {
        AuthError.QuickConnectExpired -> QuickConnectUiError.Expired
        AuthError.QuickConnectUnavailable -> QuickConnectUiError.Unavailable
        else -> QuickConnectUiError.ServerError
    }

private fun String.formatQuickConnectCode(): String = chunked(QUICK_CONNECT_CODE_GROUP_SIZE).joinToString(" ")

private const val QUICK_CONNECT_CODE_GROUP_SIZE = 3
