// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LauncherPreferenceState(
    val heroEnabled: Boolean? = null,
    val writing: Boolean = false,
    val failed: Boolean = false,
)

/** Device preferences survive account cleanup; null keeps artwork off until loaded. */
internal class LauncherPreferences(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var storage: SharedPreferences? = null
    private val _state = MutableStateFlow(LauncherPreferenceState())
    val state = _state.asStateFlow()

    init {
        scope.launch { mutex.withLock { load() } }
    }

    fun toggleHero() {
        scope.launch {
            mutex.withLock {
                val previous = _state.value.heroEnabled
                if (previous == null) {
                    load()
                    return@withLock
                }
                _state.value = LauncherPreferenceState(previous, writing = true)
                try {
                    withContext(Dispatchers.IO) {
                        val preferences = checkNotNull(storage)
                        try {
                            check(preferences.edit().putBoolean(HERO_KEY, !previous).commit())
                        } catch (failure: Exception) {
                            // A failed commit can still change SharedPreferences memory.
                            // Attempt a checked restore before another serialized press.
                            try {
                                check(preferences.edit().putBoolean(HERO_KEY, previous).commit())
                            } catch (rollback: Exception) {
                                launcherFailure("hero-preference-rollback", rollback)
                                failure.addSuppressed(rollback)
                            }
                            throw failure
                        }
                    }
                    _state.value = LauncherPreferenceState(!previous)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    launcherFailure("hero-preference-write", failure)
                    _state.value = LauncherPreferenceState(previous, failed = true)
                }
            }
        }
    }

    private suspend fun load() {
        _state.value = LauncherPreferenceState()
        try {
            val (preferences, enabled) =
                withContext(Dispatchers.IO) {
                    val preferences = context.getSharedPreferences("launcher-preferences", Context.MODE_PRIVATE)
                    preferences to preferences.getBoolean(HERO_KEY, true)
                }
            storage = preferences
            _state.value = LauncherPreferenceState(enabled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("hero-preference-read", failure)
            _state.value = LauncherPreferenceState(failed = true)
        }
    }

    private companion object {
        const val HERO_KEY = "cinematic-hero"
    }
}
