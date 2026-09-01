// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color.TRANSPARENT
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.ui.JellyScopeApp
import com.jellyscope.ui.platform.LocalDownloadNotificationPermissionRequester
import com.jellyscope.ui.platform.LocalPlatformCapabilities
import com.jellyscope.ui.platform.PlatformCapabilities

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
        )
        installMobilePlayerPlatformOwner()

        setContent {
            val notificationPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    downloadNotificationPermissionLogger.i {
                        if (granted) {
                            "stage=notification-permission event=result-granted"
                        } else {
                            "stage=notification-permission event=result-denied"
                        }
                    }
                }
            val requestDownloadNotificationPermission = {
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                        downloadNotificationPermissionLogger.i {
                            "stage=notification-permission event=skipped-below-33"
                        }

                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED ->
                        downloadNotificationPermissionLogger.i {
                            "stage=notification-permission event=skipped-already-granted"
                        }

                    else -> {
                        downloadNotificationPermissionLogger.i {
                            "stage=notification-permission event=request-launched"
                        }
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            CompositionLocalProvider(
                LocalPlatformCapabilities provides PlatformCapabilities.Mobile,
                LocalDownloadNotificationPermissionRequester provides requestDownloadNotificationPermission,
            ) {
                JellyScopeApp(
                    initialServerUrl = AndroidDeveloperConfig.SERVER_URL.ifBlank { null },
                    prefillUsername = AndroidDeveloperConfig.USERNAME,
                    prefillPassword = AndroidDeveloperConfig.PASSWORD,
                )
            }
        }
    }
}

private val downloadNotificationPermissionLogger = diagnosticLogger(DiagnosticTag.DownloadNotificationPermission)
