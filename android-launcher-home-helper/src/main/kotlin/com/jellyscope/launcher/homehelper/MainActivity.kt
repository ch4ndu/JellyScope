// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher.homehelper

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var launcherStatus: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var accessibilityButton: Button
    private lateinit var launcherButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(24))
        }
        fun text(resource: Int, size: Float = 16f): TextView = TextView(this).apply {
            setText(resource)
            textSize = size
            setPadding(0, 0, 0, dp(16))
            content.addView(this)
        }
        val heading = text(R.string.app_name, 26f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) heading.isAccessibilityHeading = true
        text(R.string.setup_message)
        launcherStatus = text(R.string.launcher_unavailable)
        serviceStatus = text(R.string.service_disabled)
        accessibilityButton = Button(this).apply {
            setText(R.string.open_accessibility)
            minHeight = dp(48)
            setOnClickListener { openAccessibility() }
        }
        launcherButton = Button(this).apply {
            setText(R.string.open_launcher)
            minHeight = dp(48)
            setOnClickListener {
                if (!LauncherTarget.open(this@MainActivity)) {
                    refreshStatus()
                    Toast.makeText(this@MainActivity, R.string.launcher_open_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        listOf(accessibilityButton, launcherButton).forEach {
            content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
        }
        text(R.string.manual_open_notice)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) text(R.string.restricted_settings)
        text(R.string.disable_message)
        text(R.string.license_notice, 14f)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
        accessibilityButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val availability = LauncherTarget.availability(this)
        launcherStatus.text = getString(R.string.launcher_status, getString(availability.message))
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val ownService = ComponentName(this, HomeKeyService::class.java)
        val enabled = manager.isEnabled && manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == ownService
        }
        serviceStatus.setText(if (enabled) R.string.service_enabled else R.string.service_disabled)
        val launcherHadFocus = launcherButton.hasFocus()
        launcherButton.isEnabled = availability == LauncherAvailability.Available
        if (!launcherButton.isEnabled && launcherHadFocus) accessibilityButton.requestFocus()
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
