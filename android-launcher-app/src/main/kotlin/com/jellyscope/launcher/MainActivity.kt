// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private var isDefaultHome by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.isHomeDelivery()) model.homeInvoked()
        val images = get<LauncherImages>()
        setContent {
            LauncherShell(model, images, ::openApp, ::openSystemSettings, ::openMedia, isDefaultHome, ::openHomeSettings)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.isHomeDelivery()) model.homeInvoked()
    }

    override fun onStart() {
        super.onStart()
        model.foreground(true)
    }

    override fun onResume() {
        super.onResume()
        isDefaultHome =
            try {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                resolved?.let { ComponentName(it.packageName, it.name) == componentName } == true
            } catch (failure: Exception) {
                launcherFailure("home-default", failure)
                false
            }
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

    private fun openHomeSettings() {
        if (!launch(Intent(Settings.ACTION_HOME_SETTINGS), "home-settings")) model.showDialog(LauncherDialog.HomeSettingsUnavailable)
    }

    private fun Intent.isHomeDelivery(): Boolean = action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_HOME) && data == null

    private fun openMedia(
        itemId: String,
        boundaryKey: String?,
    ) {
        val session = playbackSession(itemId, boundaryKey)
        val intent = session?.let { JellyScopePlaybackContract.detailsIntent(it.serverId, it.userId, itemId) }
        if (session == null || intent == null) {
            model.showDialog(LauncherDialog.PlaybackStale)
            return
        }
        try {
            val expected = ComponentName(JellyScopePlaybackContract.PLAYER_PACKAGE, JellyScopePlaybackContract.PLAYER_ACTIVITY)
            val target = packageManager.getActivityInfo(expected, 0)
            if (!target.enabled || !target.applicationInfo.enabled || !target.exported) {
                Log.i("Launcher", "stage=details-resolve event=rejected reason=target-unavailable")
                model.showDialog(LauncherDialog.PlaybackUnavailable)
                return
            }
            val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            if (resolved == null || ComponentName(resolved.packageName, resolved.name) != expected) {
                Log.i("Launcher", "stage=details-resolve event=rejected reason=details-unsupported")
                model.showDialog(LauncherDialog.DetailsUnsupported)
                return
            }
            // Resolution may block. Recheck current authority immediately before dispatch,
            // with no coroutine or suspension between this check and startActivity.
            val current = playbackSession(itemId, boundaryKey)
            if (current == null || current.serverId != session.serverId || current.userId != session.userId) {
                model.showDialog(LauncherDialog.PlaybackStale)
                return
            }
            if (!launch(intent.setComponent(expected), "details-launch")) model.showDialog(LauncherDialog.PlaybackFailed)
        } catch (failure: PackageManager.NameNotFoundException) {
            launcherFailure("details-target", failure)
            model.showDialog(LauncherDialog.PlaybackUnavailable)
        } catch (failure: SecurityException) {
            launcherFailure("details-resolve", failure)
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
