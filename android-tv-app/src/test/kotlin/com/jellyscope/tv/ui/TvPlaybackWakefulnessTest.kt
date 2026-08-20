// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TvPlaybackWakefulnessTest {
    @Test
    fun installAndCleanupAreIdempotentAtTheActivityWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        setTvPlaybackWakefulness(activity, enabled = true)
        setTvPlaybackWakefulness(activity, enabled = true)

        assertTrue(activity.window.keepsScreenOn())

        setTvPlaybackWakefulness(activity, enabled = false)
        setTvPlaybackWakefulness(activity, enabled = false)

        assertFalse(activity.window.keepsScreenOn())
    }

    @Test
    fun missingActivityIsSafeForInstallAndCleanup() {
        setTvPlaybackWakefulness(activity = null, enabled = true)
        setTvPlaybackWakefulness(activity = null, enabled = false)
    }

    @Test
    fun productionRouteInstallsEffectBeforePlayerContentAndEffectOwnsCleanup() {
        val routeSource = productionSource("TvPlayerScreen.kt")
        val effectSource = productionSource("TvPlaybackWakefulness.kt")

        val installIndex = routeSource.indexOf("TvPlaybackWakefulnessEffect(activity)")
        val contentIndex = routeSource.indexOf("TvPlayerContent(")

        assertTrue(installIndex >= 0, "TvPlayerScreen must install route-scoped wakefulness")
        assertTrue(contentIndex > installIndex, "Wakefulness must be installed before TvPlayerContent")
        assertTrue("DisposableEffect(activity)" in effectSource)
        assertTrue("onDispose" in effectSource)
        assertTrue("enabled = false" in effectSource)
    }
}

private fun Window.keepsScreenOn(): Boolean = attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

private fun productionSource(fileName: String): String {
    val path = "src/main/kotlin/com/jellyscope/tv/ui/$fileName"
    return sequenceOf(File(path), File("android-tv-app/$path"))
        .firstOrNull(File::isFile)
        ?.readText()
        ?: error("Unable to locate production source: $path")
}
