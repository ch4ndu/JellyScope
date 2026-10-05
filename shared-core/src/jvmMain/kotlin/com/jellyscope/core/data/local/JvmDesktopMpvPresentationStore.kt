// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.DesktopMpvPresentation
import com.jellyscope.core.domain.model.DesktopMpvPresentationState
import com.jellyscope.core.playback.DesktopMpvRuntime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Construction freezes this process's mpv runtime from the saved choice, so
 * startup must create this store before any player or native probe. The key
 * is app-global and no account operation clears it.
 */
internal class JvmDesktopMpvPresentationStore(
    private val preferences: JvmFilePreferencesStore,
) : DesktopMpvPresentationStore {
    private val _state =
        run {
            val saved =
                preferences
                    .readString(DESKTOP_MPV_PRESENTATION_KEY)
                    .toTolerantEnumOrNull<DesktopMpvPresentation>()
                    ?: DesktopMpvPresentation.Standard
            val runtime = DesktopMpvRuntime.initialize(saved)
            MutableStateFlow(
                DesktopMpvPresentationState(
                    selected = saved,
                    active = runtime.mode,
                    availability = runtime.availability,
                ),
            )
        }

    override val state: StateFlow<DesktopMpvPresentationState> = _state.asStateFlow()

    /** A request made while another save is pending is ignored; Settings disables selection meanwhile. */
    override suspend fun select(presentation: DesktopMpvPresentation) {
        // Admission, the write, and settling finish together even if Settings is disposed.
        withContext(NonCancellable) {
            if (!admit(presentation)) return@withContext
            var published = false
            try {
                preferences.writeString(DESKTOP_MPV_PRESENTATION_KEY, presentation.name)
                // One update, so no observer sees saving cleared with the previous selection.
                _state.update { state -> state.copy(selected = presentation, isSaving = false) }
                published = true
            } finally {
                if (!published) _state.update { state -> state.copy(isSaving = false) }
            }
        }
    }

    /** Atomically claims the single pending save; false while another save holds it. */
    private fun admit(presentation: DesktopMpvPresentation): Boolean {
        var admitted = false
        _state.update { current ->
            require(current.isSelectorEnabled && (presentation == DesktopMpvPresentation.Standard || current.isMetalSelectable)) {
                "Desktop mpv presentation cannot change in this launch"
            }
            admitted = !current.isSaving
            if (admitted) current.copy(isSaving = true) else current
        }
        return admitted
    }
}

private const val DESKTOP_MPV_PRESENTATION_KEY = "desktop_mpv.presentation"
