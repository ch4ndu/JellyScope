// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.person

internal sealed interface PersonChange {
    data object RetryPage : PersonChange

    data class LoadStarted(
        val reset: Boolean,
    ) : PersonChange

    data class Loaded(
        val content: PersonUiState.Content,
    ) : PersonChange

    data class LoadFailed(
        val reset: Boolean,
    ) : PersonChange
}

internal fun reducePerson(
    current: PersonUiState,
    change: PersonChange,
): PersonUiState =
    when (change) {
        PersonChange.RetryPage -> (current as? PersonUiState.Content)?.copy(error = false) ?: current
        is PersonChange.LoadStarted ->
            if (change.reset) {
                PersonUiState.Loading
            } else {
                (current as? PersonUiState.Content)?.copy(isLoadingMore = true, error = false) ?: current
            }
        is PersonChange.Loaded -> change.content
        is PersonChange.LoadFailed ->
            if (change.reset) {
                PersonUiState.Error()
            } else {
                (current as? PersonUiState.Content)?.copy(isLoadingMore = false, error = true) ?: PersonUiState.Error()
            }
    }
