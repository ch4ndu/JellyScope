// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
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
        setContent { LauncherShell(model, images, ::openApp, ::openSystemSettings) }
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
