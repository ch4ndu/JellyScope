// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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
    private var committedOrder: List<String>? = null

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
                val available = catalog.associateBy { it.componentKey }
                // Capture the durable source before Editor.commit can change the
                // preferences' in-memory map even when its disk write fails.
                val source = committedOrder ?: storedOrder().also { committedOrder = it }
                val surviving = source.distinct().filter { it in available }
                val order = surviving + catalog.map { it.componentKey }.filter { it !in surviving }
                val ordered = order.mapNotNull(available::get)
                try {
                    persist(order)
                    committedOrder = order
                    _state.value = LauncherAppsState(ordered, loaded = true)
                } catch (failure: Exception) {
                    // Discovery remains usable; only a successful commit changes authority.
                    launcherFailure("app-order-refresh-save", failure)
                    _state.value = LauncherAppsState(ordered, durable = false, loaded = true)
                }
            }
        }

    suspend fun move(
        key: String,
        delta: Int,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val candidate = _state.value.catalog.toMutableList()
            val index = candidate.indexOfFirst { it.componentKey == key }
            check(index >= 0)
            val destination = index + delta
            if (destination !in candidate.indices || delta !in listOf(-1, 1)) return@withLock
            candidate.add(destination, candidate.removeAt(index))
            val order = candidate.map { it.componentKey }
            // Also makes a previously non-durable refresh snapshot durable.
            persist(order)
            committedOrder = order
            _state.value = LauncherAppsState(candidate, loaded = true)
        }
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

    private fun storedOrder(): List<String> =
        try {
            val key = if (preferences.contains("app-order-v1")) "app-order-v1" else "pins"
            json.decodeFromString<List<String>>(preferences.getString(key, "[]").orEmpty())
        } catch (failure: Exception) {
            launcherFailure("app-order-read", failure)
            emptyList()
        }

    private fun persist(order: List<String>) {
        check(preferences.edit().putString("app-order-v1", json.encodeToString(order)).commit())
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
        val banner =
            try {
                if (activity.banner ==
                    0
                ) {
                    null
                } else {
                    packageManager.getDrawable(activity.packageName, activity.banner, activity.applicationInfo)?.raster(320, 180)
                }
            } catch (failure: Exception) {
                launcherFailure("app-banner", failure)
                null
            }
                ?: try {
                    val application = activity.applicationInfo
                    if (application.banner ==
                        0
                    ) {
                        null
                    } else {
                        packageManager.getDrawable(activity.packageName, application.banner, application)?.raster(320, 180)
                    }
                } catch (failure: Exception) {
                    launcherFailure("application-banner", failure)
                    null
                }
        val icon =
            try {
                info.loadIcon(packageManager).raster(96, 96)
            } catch (failure: Exception) {
                launcherFailure("app-icon", failure)
                null
            }
        return LauncherApp(component, label, icon, banner)
    }

    private fun Drawable.raster(
        width: Int,
        height: Int,
    ): androidx.compose.ui.graphics.ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = minOf(width.toFloat() / intrinsicWidth.coerceAtLeast(1), height.toFloat() / intrinsicHeight.coerceAtLeast(1))
        val drawWidth = if (intrinsicWidth > 0) (intrinsicWidth * scale).toInt().coerceAtLeast(1) else width
        val drawHeight = if (intrinsicHeight > 0) (intrinsicHeight * scale).toInt().coerceAtLeast(1) else height
        val canvas = Canvas(bitmap)
        canvas.translate((width - drawWidth) / 2f, (height - drawHeight) / 2f)
        setBounds(0, 0, drawWidth, drawHeight)
        draw(canvas)
        return bitmap.asImageBitmap()
    }
}
