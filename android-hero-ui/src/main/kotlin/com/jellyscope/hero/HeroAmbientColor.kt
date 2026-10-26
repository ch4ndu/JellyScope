// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.hero

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.roundToInt

fun extractHeroAmbientColor(bitmap: Bitmap): Color? {
    val software =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }
    var sample: Bitmap? = null
    try {
        val scale = minOf(1f, 96f / maxOf(software.width, software.height))
        val resized =
            Bitmap.createScaledBitmap(
                software,
                (software.width * scale).roundToInt().coerceAtLeast(1),
                (software.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        sample = resized
        val palette = Palette.from(resized).generate()
        val swatch =
            palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch
                ?: palette.mutedSwatch ?: palette.darkMutedSwatch
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(swatch?.rgb ?: return null, hsl)
        if (hsl[1] >= 0.08f) hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = hsl[2].coerceIn(0.16f, 0.30f)
        return Color(ColorUtils.HSLToColor(hsl))
    } finally {
        // The caller retains its bitmap; recycle only copies created here.
        sample?.takeIf { it !== software }?.recycle()
        if (software !== bitmap) software.recycle()
    }
}
