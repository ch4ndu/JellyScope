// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.content.SharedPreferences
import com.jellyscope.core.domain.model.ImageQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SharedPreferencesImageQualityStore(
    context: Context,
) : ImageQualityStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(IMAGE_QUALITY_PREFS_NAME, Context.MODE_PRIVATE)
    private val _imageQuality = MutableStateFlow(preferences.readImageQuality())

    override val imageQuality: StateFlow<ImageQuality> = _imageQuality.asStateFlow()

    override suspend fun setImageQuality(imageQuality: ImageQuality) {
        preferences
            .edit()
            .putString(IMAGE_QUALITY_PREFS_KEY, imageQuality.name)
            .apply()
        _imageQuality.value = imageQuality
    }
}

private fun SharedPreferences.readImageQuality(): ImageQuality =
    getString(IMAGE_QUALITY_PREFS_KEY, null)?.toImageQuality() ?: ImageQuality.High

private fun String.toImageQuality(): ImageQuality = toTolerantEnumOrNull<ImageQuality>() ?: ImageQuality.High

private const val IMAGE_QUALITY_PREFS_NAME = "image_quality"
private const val IMAGE_QUALITY_PREFS_KEY = "image_quality"
