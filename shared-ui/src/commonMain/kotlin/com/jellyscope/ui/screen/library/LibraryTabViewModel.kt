// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.SetLastLibraryIdAction
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetLastLibraryIdUseCase
import com.jellyscope.core.domain.usecase.GetUserLibrariesUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

sealed interface LibraryTabUiState {
    data object Loading : LibraryTabUiState

    data class Loaded(
        val libraries: List<Library>,
        val selectedLibraryId: String?,
    ) : LibraryTabUiState

    data object Error : LibraryTabUiState
}

class LibraryTabViewModel(
    private val session: Session,
    private val getUserLibrariesUseCase: GetUserLibrariesUseCase,
    private val getLastLibraryIdUseCase: GetLastLibraryIdUseCase,
    private val setLastLibraryIdAction: SetLastLibraryIdAction,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val accountKey = "${session.serverId}:${session.userId}"
    private val pendingLibrarySelections = Channel<String>(capacity = Channel.CONFLATED)
    private val _state = MutableStateFlow<LibraryTabUiState>(LibraryTabUiState.Loading)
    val state: StateFlow<LibraryTabUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(workDispatcher) {
            for (libraryId in pendingLibrarySelections) {
                try {
                    setLastLibraryIdAction(accountKey, libraryId)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Throwable) {
                    // A failed preference write must not stop later selections from being saved.
                }
            }
        }
        retry()
    }

    fun retry() {
        _state.update { LibraryTabUiState.Loading }
        viewModelScope.launch {
            val resolved =
                withContext(workDispatcher) {
                    getUserLibrariesUseCase().fold(
                        onSuccess = { libraries ->
                            val storedLibraryId = getLastLibraryIdUseCase(accountKey)
                            val selectedLibraryId =
                                storedLibraryId?.takeIf { id -> libraries.any { library -> library.id == id } }
                                    ?: libraries.firstOrNull()?.id
                            ResolvedLibraries(
                                libraries = libraries,
                                storedLibraryId = storedLibraryId,
                                selectedLibraryId = selectedLibraryId,
                            )
                        },
                        onFailure = { null },
                    )
                }

            if (resolved == null) {
                _state.update { LibraryTabUiState.Error }
                return@launch
            }

            _state.update {
                LibraryTabUiState.Loaded(
                    libraries = resolved.libraries,
                    selectedLibraryId = resolved.selectedLibraryId,
                )
            }
            if (resolved.selectedLibraryId != null && resolved.selectedLibraryId != resolved.storedLibraryId) {
                pendingLibrarySelections.trySend(resolved.selectedLibraryId)
            }
        }
    }

    fun selectLibrary(libraryId: String) {
        val loaded = _state.value as? LibraryTabUiState.Loaded ?: return
        if (loaded.libraries.none { library -> library.id == libraryId }) return

        _state.update { current ->
            (current as? LibraryTabUiState.Loaded)?.copy(selectedLibraryId = libraryId) ?: current
        }
        pendingLibrarySelections.trySend(libraryId)
    }

    private data class ResolvedLibraries(
        val libraries: List<Library>,
        val storedLibraryId: String?,
        val selectedLibraryId: String?,
    )
}
