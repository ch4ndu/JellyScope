// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.ImageQuality
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AndroidImageQualityStoreTest {
    @Test
    fun restoresSavedQualityAndDefaultsSafelyToHigh() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences = context.getSharedPreferences("image_quality", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            try {
                val store = SharedPreferencesImageQualityStore(context)
                assertEquals(ImageQuality.High, store.imageQuality.value)
                store.setImageQuality(ImageQuality.Medium)
                assertEquals(ImageQuality.Medium, SharedPreferencesImageQualityStore(context).imageQuality.value)
                preferences.edit().putString("image_quality", "Unknown").commit()
                assertEquals(ImageQuality.High, SharedPreferencesImageQualityStore(context).imageQuality.value)
            } finally {
                preferences.edit().clear().commit()
            }
        }
}
