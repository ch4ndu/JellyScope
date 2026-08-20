// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

internal fun ambientColorFromArgbSamples(samples: List<Int>): Color? {
    val buckets = mutableMapOf<Int, AmbientBucket>()
    samples.forEach { argb ->
        val alpha = argb ushr 24 and 0xff
        if (alpha < MIN_AMBIENT_ALPHA) return@forEach
        val red = argb ushr 16 and 0xff
        val green = argb ushr 8 and 0xff
        val blue = argb and 0xff
        val lightness = (max(red, max(green, blue)) + min(red, min(green, blue))) / (2f * 255f)
        if (lightness !in MIN_SAMPLE_LIGHTNESS..MAX_SAMPLE_LIGHTNESS) return@forEach

        val key = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
        buckets.getOrPut(key) { AmbientBucket() }.add(red, green, blue)
    }

    val selected =
        buckets.values.maxByOrNull { bucket ->
            val red = bucket.red / bucket.count.toFloat()
            val green = bucket.green / bucket.count.toFloat()
            val blue = bucket.blue / bucket.count.toFloat()
            bucket.count * (MIN_SATURATION_WEIGHT + rgbSaturation(red, green, blue))
        } ?: return null
    return normalizeAmbientColor(
        red = selected.red / selected.count.toFloat() / 255f,
        green = selected.green / selected.count.toFloat() / 255f,
        blue = selected.blue / selected.count.toFloat() / 255f,
    )
}

private fun normalizeAmbientColor(
    red: Float,
    green: Float,
    blue: Float,
): Color {
    val maxChannel = max(red, max(green, blue))
    val minChannel = min(red, min(green, blue))
    val delta = maxChannel - minChannel
    val sourceLightness = (maxChannel + minChannel) / 2f
    val sourceSaturation =
        if (delta == 0f) {
            0f
        } else {
            delta / (1f - kotlin.math.abs(2f * sourceLightness - 1f))
        }
    val hue =
        when {
            delta == 0f -> 0f
            maxChannel == red -> 60f * (((green - blue) / delta) % 6f)
            maxChannel == green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }.let { value -> if (value < 0f) value + 360f else value }
    val saturation =
        if (sourceSaturation < NEUTRAL_SATURATION) {
            sourceSaturation
        } else {
            sourceSaturation.coerceAtLeast(MIN_BACKDROP_SATURATION)
        }
    return hslToColor(
        hue = hue,
        saturation = saturation.coerceIn(0f, 1f),
        lightness = sourceLightness.coerceIn(MIN_BACKDROP_LIGHTNESS, MAX_BACKDROP_LIGHTNESS),
    )
}

private fun hslToColor(
    hue: Float,
    saturation: Float,
    lightness: Float,
): Color {
    val chroma = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val hueSection = hue / 60f
    val secondary = chroma * (1f - kotlin.math.abs(hueSection % 2f - 1f))
    val (redPrime, greenPrime, bluePrime) =
        when (hueSection.toInt().coerceIn(0, 5)) {
            0 -> Triple(chroma, secondary, 0f)
            1 -> Triple(secondary, chroma, 0f)
            2 -> Triple(0f, chroma, secondary)
            3 -> Triple(0f, secondary, chroma)
            4 -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
    val match = lightness - chroma / 2f
    return Color(redPrime + match, greenPrime + match, bluePrime + match)
}

private fun rgbSaturation(
    red: Float,
    green: Float,
    blue: Float,
): Float {
    val maxChannel = max(red, max(green, blue))
    val minChannel = min(red, min(green, blue))
    return if (maxChannel == 0f) 0f else (maxChannel - minChannel) / maxChannel
}

private class AmbientBucket {
    var count: Int = 0
    var red: Int = 0
    var green: Int = 0
    var blue: Int = 0

    fun add(
        red: Int,
        green: Int,
        blue: Int,
    ) {
        count++
        this.red += red
        this.green += green
        this.blue += blue
    }
}

private const val MIN_AMBIENT_ALPHA = 128
private const val MIN_SAMPLE_LIGHTNESS = 0.025f
private const val MAX_SAMPLE_LIGHTNESS = 0.94f
private const val MIN_SATURATION_WEIGHT = 0.25f
private const val NEUTRAL_SATURATION = 0.08f
private const val MIN_BACKDROP_SATURATION = 0.45f
private const val MIN_BACKDROP_LIGHTNESS = 0.16f
private const val MAX_BACKDROP_LIGHTNESS = 0.30f
