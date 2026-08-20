// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.Image
import coil3.toBitmap
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ambientLogger = diagnosticLogger(DiagnosticTag.AmbientColor)

actual fun platformAmbientColorExtractor(): AmbientColorExtractor = AndroidAmbientColorExtractor()

private class AndroidAmbientColorExtractor : AmbientColorExtractor {
    private val cache = LruCache<String, Color>(64)

    override suspend fun extract(
        image: Image,
        key: String,
    ): Color? {
        cache.get(key)?.let { cached -> return cached }

        return withContext(Dispatchers.Default) {
            runCatching {
                val maxDim = 96
                val aspect = if (image.height > 0) image.width.toFloat() / image.height.toFloat() else 1f
                val targetW = if (aspect >= 1f) maxDim else (maxDim * aspect).toInt().coerceAtLeast(1)
                val targetH = if (aspect >= 1f) (maxDim / aspect).toInt().coerceAtLeast(1) else maxDim
                val bitmap = image.toBitmap(width = targetW, height = targetH)
                val softwareBitmap =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        bitmap.config == Bitmap.Config.HARDWARE
                    ) {
                        bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        bitmap
                    }
                Palette
                    .from(softwareBitmap)
                    .generate()
                    .selectAmbientColor()
                    ?.let { selected -> Color(normalizeForBackdrop(selected)) }
            }.onFailure { error ->
                ambientLogger.i {
                    formatSafeFailureDiagnostic(
                        stage = "ambient-color",
                        event = "failed",
                        throwable = error,
                    )
                }
            }.getOrNull()
                ?.also { color ->
                    cache.put(key, color)
                }
        }
    }
}

private fun Palette.selectAmbientColor(): Int? =
    listOfNotNull(
        vibrantSwatch?.rgb,
        darkVibrantSwatch?.rgb,
        dominantSwatch?.rgb,
        mutedSwatch?.rgb,
        darkMutedSwatch?.rgb,
    ).firstOrNull()

private fun normalizeForBackdrop(argb: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    if (hsl[1] >= NEUTRAL_SATURATION) {
        hsl[1] = hsl[1].coerceAtLeast(0.45f)
    }
    hsl[2] = hsl[2].coerceIn(0.16f, 0.30f)
    return ColorUtils.HSLToColor(hsl)
}

private const val NEUTRAL_SATURATION = 0.08f
