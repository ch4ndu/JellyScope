// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesPlayerDeviceSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("player_device_settings", Context.MODE_PRIVATE)

    @AfterTest
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun refreshRateMatchingDefaultsOffAndPersistsAsAString() =
        runTest {
            assertEquals(false, SharedPreferencesPlayerDeviceSettingsStore(context).settings.value.matchDisplayRefreshRate)

            SharedPreferencesPlayerDeviceSettingsStore(context).setSettings(
                PlayerDeviceSettings(
                    matchDisplayRefreshRate = true,
                    maxVideoResolution = PlayerVideoResolutionLimit.Height1080,
                    iosPlaybackCompatibilityMode = IosPlaybackCompatibilityMode.Unrestricted,
                ),
            )

            assertEquals("true", preferences.getString("match_display_refresh_rate", null))
            assertEquals("Height1080", preferences.getString("max_video_resolution", null))
            assertEquals("Unrestricted", preferences.getString("ios_playback_compatibility_mode", null))
            assertEquals(true, SharedPreferencesPlayerDeviceSettingsStore(context).settings.value.matchDisplayRefreshRate)
            assertEquals(
                PlayerVideoResolutionLimit.Height1080,
                SharedPreferencesPlayerDeviceSettingsStore(context).settings.value.maxVideoResolution,
            )
            assertEquals(
                IosPlaybackCompatibilityMode.Unrestricted,
                SharedPreferencesPlayerDeviceSettingsStore(context).settings.value.iosPlaybackCompatibilityMode,
            )
        }

    @Test
    fun invalidRefreshRateMatchingPreferenceFallsBackToOff() {
        preferences.edit().putString("match_display_refresh_rate", "not-a-boolean").commit()

        assertEquals(false, SharedPreferencesPlayerDeviceSettingsStore(context).settings.value.matchDisplayRefreshRate)
    }

    @Test
    fun invalidResolutionPreferenceFallsBackToUnlimited() {
        preferences.edit().putString("max_video_resolution", "not-a-resolution").commit()

        assertEquals(
            PlayerVideoResolutionLimit.Unlimited,
            SharedPreferencesPlayerDeviceSettingsStore(context).settings.value.maxVideoResolution,
        )
    }

    @Test
    fun invalidIosCompatibilityPreferenceFallsBackToStandard() {
        preferences.edit().putString("ios_playback_compatibility_mode", "not-a-mode").commit()

        assertEquals(
            IosPlaybackCompatibilityMode.Standard,
            SharedPreferencesPlayerDeviceSettingsStore(context).settings.value.iosPlaybackCompatibilityMode,
        )
    }
}
