// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.jellyscope.ui.JellyScopeApp
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
            CompositionLocalProvider(
                LocalPlatformCapabilities provides PlatformCapabilities.Mobile,
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
