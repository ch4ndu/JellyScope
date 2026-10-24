// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher.homehelper

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class HomeKeyService : AccessibilityService() {
    private var downTime: Long? = null
    private var deviceId = 0
    private var handled = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && !matches(event)) {
                    downTime = event.downTime
                    deviceId = event.deviceId
                    // The key dispatcher needs the result synchronously. Never
                    // schedule this launch or retry it on a held-key repeat.
                    handled = !event.isCanceled && LauncherTarget.open(this)
                }
                matches(event) && handled
            }
            KeyEvent.ACTION_UP -> {
                val matching = matches(event)
                val consumed = matching && handled
                if (matching) clearPress()
                consumed
            }
            else -> false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = clearPress()

    override fun onDestroy() {
        clearPress()
        super.onDestroy()
    }

    private fun matches(event: KeyEvent): Boolean = downTime == event.downTime && deviceId == event.deviceId

    private fun clearPress() {
        downTime = null
        deviceId = 0
        handled = false
    }
}

internal enum class LauncherAvailability(val message: Int) {
    Available(R.string.launcher_available),
    Missing(R.string.launcher_missing),
    Disabled(R.string.launcher_disabled),
    Unavailable(R.string.launcher_unavailable),
}

internal object LauncherTarget {
    private val component = ComponentName("com.udnahc.jellyscope.launcher", "com.jellyscope.launcher.MainActivity")

    @Suppress("DEPRECATION")
    fun availability(context: Context): LauncherAvailability = try {
        val packages = context.packageManager
        val activity = packages.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        val applicationEnabled = enabled(packages.getApplicationEnabledSetting(component.packageName), activity.applicationInfo.enabled)
        val activityEnabled = enabled(packages.getComponentEnabledSetting(component), activity.enabled)
        when {
            !applicationEnabled || !activityEnabled -> LauncherAvailability.Disabled
            !activity.exported -> LauncherAvailability.Unavailable
            else -> LauncherAvailability.Available
        }
    } catch (_: PackageManager.NameNotFoundException) {
        LauncherAvailability.Missing
    } catch (_: IllegalArgumentException) {
        // The package can disappear between the availability queries.
        LauncherAvailability.Unavailable
    } catch (_: SecurityException) {
        LauncherAvailability.Unavailable
    }

    fun open(context: Context): Boolean {
        if (availability(context) != LauncherAvailability.Available) return false
        return try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).setComponent(component).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun enabled(setting: Int, manifestEnabled: Boolean): Boolean = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }
}
