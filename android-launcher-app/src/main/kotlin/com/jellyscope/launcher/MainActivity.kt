// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val model: LauncherViewModel by viewModel()
    private var externalReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val images = get<LauncherImages>()
        setContent { LauncherShell(model, images, ::openApp, ::openSystemSettings, ::openMedia) }
    }

    override fun onStart() {
        super.onStart()
        model.foreground(true)
    }

    override fun onResume() {
        super.onResume()
        model.resumed(externalReturn)
        externalReturn = false
    }

    override fun onStop() {
        model.foreground(false)
        super.onStop()
    }

    private fun openApp(key: String) {
        lifecycleScope.launch {
            try {
                val intent = get<LauncherAppsRepository>().launchIntent(key)
                if (intent == null || !launch(intent, "app-launch")) model.appUnavailable()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                launcherFailure("app-launch", failure)
                model.appUnavailable()
            }
        }
    }

    private fun openSystemSettings() {
        if (!launch(Intent(Settings.ACTION_SETTINGS), "system-settings")) model.showDialog(LauncherDialog.SystemSettings)
    }

    private fun openMedia(
        itemId: String,
        boundaryKey: String?,
    ) {
        val session = playbackSession(itemId, boundaryKey)
        val intent = session?.let { JellyScopePlaybackContract.intent(it.serverId, it.userId, itemId) }
        if (session == null || intent == null) {
            model.showDialog(LauncherDialog.PlaybackStale)
            return
        }
        try {
            if (packageManager.resolveActivity(intent, 0) == null) {
                model.showDialog(LauncherDialog.PlaybackUnavailable)
                return
            }
            // Resolution may block. Recheck current authority immediately before dispatch,
            // with no coroutine or suspension between this check and startActivity.
            val current = playbackSession(itemId, boundaryKey)
            if (current == null || current.serverId != session.serverId || current.userId != session.userId) {
                model.showDialog(LauncherDialog.PlaybackStale)
                return
            }
            if (!launch(intent, "playback-launch")) model.showDialog(LauncherDialog.PlaybackFailed)
        } catch (failure: SecurityException) {
            launcherFailure("playback-resolve", failure)
            model.showDialog(LauncherDialog.PlaybackFailed)
        }
    }

    private fun playbackSession(
        itemId: String,
        boundaryKey: String?,
    ): Session? {
        val loggedIn = get<SessionRepository>().sessionState.value as? SessionState.LoggedIn ?: return null
        val current = model.state.value
        val key = LauncherBoundary(loggedIn.session.accountIdentity(), loggedIn.boundaryEpoch).key
        if (boundaryKey == null || boundaryKey != key || current.boundaryKey != key || current.media.boundaryKey != key) return null
        if (!current.media.rows.any { row -> row.layout.visible && row.cards.any { it.id == itemId } }) return null
        val session = loggedIn.session
        if (!JellyScopePlaybackContract.isValidIdentifier(session.serverId) ||
            !JellyScopePlaybackContract.isValidIdentifier(session.userId) ||
            !JellyScopePlaybackContract.isValidIdentifier(itemId)
        ) {
            return null
        }
        return session
    }

    private fun launch(
        intent: Intent,
        stage: String,
    ): Boolean =
        try {
            startActivity(intent)
            externalReturn = true
            true
        } catch (failure: ActivityNotFoundException) {
            launcherFailure(stage, failure)
            false
        } catch (failure: SecurityException) {
            launcherFailure(stage, failure)
            false
        }
}
