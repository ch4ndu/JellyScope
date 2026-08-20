// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.AppColorThemeId
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AndroidAppThemeStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest
    fun setUp() {
        preferences().edit().clear().commit()
    }

    @AfterTest
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun defaultsToEmberAndPersistsSelectedTheme() =
        runTest {
            val store = SharedPreferencesAppThemeStore(context)

            assertEquals(AppColorThemeId.Ember, store.theme.value)

            store.setTheme(AppColorThemeId.Midnight)

            val restoredStore = SharedPreferencesAppThemeStore(context)
            assertEquals(AppColorThemeId.Midnight, restoredStore.theme.value)
        }

    @Test
    fun unknownPersistedThemeFallsBackToEmber() {
        preferences()
            .edit()
            .putString(APP_THEME_TEST_PREFS_KEY, "Unknown")
            .commit()

        val store = SharedPreferencesAppThemeStore(context)

        assertEquals(AppColorThemeId.Ember, store.theme.value)
    }

    private fun preferences() = context.getSharedPreferences(APP_THEME_TEST_PREFS_NAME, Context.MODE_PRIVATE)
}

private const val APP_THEME_TEST_PREFS_NAME = "app_theme"
private const val APP_THEME_TEST_PREFS_KEY = "app_color_theme"
