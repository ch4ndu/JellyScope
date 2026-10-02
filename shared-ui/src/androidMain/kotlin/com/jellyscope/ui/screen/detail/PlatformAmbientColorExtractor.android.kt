// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.core.util.runCatchingCancellable
import com.jellyscope.ui.component.MeasuredImageRequestKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val ambientLogger = diagnosticLogger(DiagnosticTag.AmbientColor)

actual fun platformAmbientColorExtractor(): AmbientColorExtractor = AndroidAmbientColorExtractor()

private class AndroidAmbientColorExtractor : AmbientColorExtractor {
    private val cache = LruCache<String, Color>(64)

    override suspend fun extract(
        source: AmbientImageSource,
        key: String,
    ): Color? {
        cache.get(key)?.let { cached -> return cached }

        return withContext(Dispatchers.Default) {
            val color =
                runCatchingCancellable {
                    currentCoroutineContext().ensureActive()
                    val result = source.imageLoader.execute(source.ambientSampleRequest())
                    currentCoroutineContext().ensureActive()
                    val bitmap =
                        when (result) {
                            is SuccessResult ->
                                (result.image as? BitmapImage)?.bitmap
                                    ?: return@runCatchingCancellable rejectedAmbientSample("sample-unsupported")
                            is ErrorResult -> throw result.throwable
                        }
                    if (bitmap.width !in 1..AMBIENT_SAMPLE_SIZE || bitmap.height !in 1..AMBIENT_SAMPLE_SIZE) {
                        return@runCatchingCancellable rejectedAmbientSample("sample-bounds")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
                        return@runCatchingCancellable rejectedAmbientSample("sample-hardware")
                    }
                    Palette
                        .from(bitmap)
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
            currentCoroutineContext().ensureActive()
            color?.also { cache.put(key, it) }
        }
    }
}

private fun AmbientImageSource.ambientSampleRequest(): ImageRequest =
    result.request
        .newBuilder()
        .target(null)
        .listener(null)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .size(AMBIENT_SAMPLE_SIZE, AMBIENT_SAMPLE_SIZE)
        .scale(Scale.FIT)
        .precision(Precision.EXACT)
        .allowHardware(false)
        .apply { extras[MeasuredImageRequestKey] = false }
        .build()

private fun rejectedAmbientSample(event: String): Color? {
    ambientLogger.i {
        formatSafeFailureDiagnostic(
            stage = "ambient-color",
            event = event,
            throwable = AmbientSampleRejectedException(),
        )
    }
    return null
}

private class AmbientSampleRejectedException : IllegalStateException()

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
private const val AMBIENT_SAMPLE_SIZE = 96
