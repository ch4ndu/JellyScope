// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

internal class LauncherAppsRepository(
    context: Context,
    private val json: Json,
) {
    private val packageManager = context.packageManager
    private val ownPackage = context.packageName
    private val preferences = context.getSharedPreferences("launcher-device-pins", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LauncherAppsState())
    val state = _state.asStateFlow()
    private var pins: List<String> = emptyList()

    @Suppress("DEPRECATION")
    suspend fun refresh() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val tv = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
                val normal = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val catalog =
                    (tv + normal)
                        .map { it.activityInfo.packageName }
                        .distinct()
                        .mapNotNull { packageName ->
                            val intent =
                                packageManager.getLeanbackLaunchIntentForPackage(packageName)
                                    ?: packageManager.getLaunchIntentForPackage(packageName)
                            intent?.let { packageManager.resolveActivity(it, 0) }?.let(::app)
                        }.sortedWith(compareBy<LauncherApp> { it.label.lowercase(Locale.getDefault()) }.thenBy { it.componentKey })
                val stored =
                    try {
                        json.decodeFromString<List<String>>(preferences.getString("pins", "[]").orEmpty())
                    } catch (
                        _: Exception,
                    ) {
                        emptyList()
                    }
                pins = stored.distinct().filter { key -> catalog.any { it.componentKey == key } }.take(MAX_APP_PINS)
                persist()
                publish(catalog)
            }
        }

    suspend fun pin(key: String) =
        mutate {
            if (key !in this &&
                size < MAX_APP_PINS &&
                _state.value.catalog.any { it.componentKey == key }
            ) {
                add(key)
            }
        }

    suspend fun unpin(key: String) = mutate { remove(key) }

    suspend fun move(
        key: String,
        delta: Int,
    ) = mutate {
        val index = indexOf(key)
        val destination = index + delta
        if (index >= 0 && destination in indices && delta in listOf(-1, 1)) add(destination, removeAt(index))
    }

    @Suppress("DEPRECATION")
    suspend fun launchIntent(key: String): Intent? =
        withContext(Dispatchers.IO) {
            val component = ComponentName.unflattenFromString(key) ?: return@withContext null
            try {
                val info = packageManager.getActivityInfo(component, 0)
                if (!info.enabled || !info.applicationInfo.enabled || !info.exported) return@withContext null
                Intent.makeMainActivity(component)
            } catch (failure: PackageManager.NameNotFoundException) {
                launcherFailure("app-resolve", failure)
                null
            } catch (failure: SecurityException) {
                launcherFailure("app-resolve", failure)
                null
            }
        }

    private suspend fun mutate(change: MutableList<String>.() -> Unit) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = pins
                pins = pins.toMutableList().apply(change).toList()
                try {
                    persist()
                } catch (failure: Exception) {
                    pins = previous
                    throw failure
                }
                publish(_state.value.catalog)
            }
        }

    private fun persist() {
        check(preferences.edit().putString("pins", json.encodeToString(pins)).commit())
    }

    private fun publish(catalog: List<LauncherApp>) {
        _state.value = LauncherAppsState(catalog, pins.mapNotNull { key -> catalog.firstOrNull { it.componentKey == key } })
    }

    private fun app(info: ResolveInfo): LauncherApp? {
        val activity = info.activityInfo
        if (activity.packageName == ownPackage || !activity.enabled || !activity.applicationInfo.enabled || !activity.exported) return null
        val component = ComponentName(activity.packageName, activity.name).flattenToString()
        val label =
            try {
                info
                    .loadLabel(packageManager)
                    .toString()
                    .takeIf(String::isNotBlank)
                    ?.take(200)
                    ?: activity.packageName
            } catch (failure: Exception) {
                launcherFailure("app-label", failure)
                activity.packageName
            }
        val icon =
            try {
                val drawable = info.loadIcon(packageManager)
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(Canvas(bitmap))
                bitmap.asImageBitmap()
            } catch (failure: Exception) {
                launcherFailure("app-icon", failure)
                null
            }
        return LauncherApp(component, label, icon)
    }
}
