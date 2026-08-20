// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import co.touchlab.kermit.Logger
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.core.util.runCatchingCancellable
import com.jellyscope.core.util.safeDiagnosticType
import com.jellyscope.ui.component.MediaCardUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private val ambientLogger = Logger.withTag("AmbientColorController")
private val ambientFailureLogger = diagnosticLogger(DiagnosticTag.AmbientColor)
private val ambientTransitionCounter = AtomicLong(0L)

internal enum class TvHeroAmbientSurface(
    val token: String,
) {
    Home("home"),
    Recommended("recommended"),
    Library("library"),
    Collection("collection"),
}

private object EmptyAmbientOwnerKey

internal data class TvHeroAmbientPresentation(
    val animationOwnerKey: Any,
    val transition: Long,
    val color: Color?,
    val clearWhenColorMissing: Boolean,
) {
    companion object {
        val Empty =
            TvHeroAmbientPresentation(
                animationOwnerKey = EmptyAmbientOwnerKey,
                transition = 0L,
                color = null,
                clearWhenColorMissing = true,
            )
    }
}

internal data class TvHeroAmbientOwnershipState(
    val ownerKey: Any?,
    val transition: Long,
    val color: Color?,
)

internal fun selectTvHeroAmbientOwner(
    ownerKey: Any?,
    transition: Long,
): TvHeroAmbientOwnershipState =
    TvHeroAmbientOwnershipState(
        ownerKey = ownerKey,
        transition = transition,
        color = null,
    )

internal fun applyTvHeroAmbientColor(
    state: TvHeroAmbientOwnershipState,
    ownerKey: Any,
    transition: Long,
    color: Color,
): TvHeroAmbientOwnershipState =
    if (state.ownerKey === ownerKey && state.transition == transition) {
        state.copy(color = color)
    } else {
        state
    }

private class TvHeroAmbientOwnerKey(
    val itemId: String,
    val surface: TvHeroAmbientSurface,
    val cacheKey: String,
)

private data class AmbientExtractionRequest(
    val itemId: String,
    val cacheKey: String,
    val ownerKey: TvHeroAmbientOwnerKey,
    val transition: Long,
    val surface: TvHeroAmbientSurface,
)

private enum class AmbientPosterSource(
    val token: String,
) {
    BitmapCache("bitmap-cache"),
    ImageLoader("image-loader"),
}

private sealed interface AmbientExtractionResult {
    data class ColorReady(
        val color: Color,
    ) : AmbientExtractionResult

    data object NoSwatch : AmbientExtractionResult

    data class Failed(
        val throwable: Throwable,
    ) : AmbientExtractionResult
}

private class AmbientColorController(
    private val scope: CoroutineScope,
    private val onResult: (AmbientExtractionRequest, AmbientExtractionResult) -> Boolean,
) {
    private val cache = LruCache<String, Color>(64)

    fun colorFor(cacheKey: String): Color? = cache.get(cacheKey)

    fun extractFromPoster(
        request: AmbientExtractionRequest,
        bitmap: Bitmap,
        source: AmbientPosterSource,
    ) {
        logAmbientTransition(
            surface = request.surface,
            transition = request.transition,
            event = "extraction",
            source = source.token,
            result = "requested",
            width = bitmap.width,
            height = bitmap.height,
        )
        cache.get(request.cacheKey)?.let { cached ->
            publishResult(
                request = request,
                result = AmbientExtractionResult.ColorReady(cached),
                source = "color-cache",
                width = bitmap.width,
                height = bitmap.height,
            )
            return
        }

        scope.launch(Dispatchers.Default) {
            val result =
                runCatchingCancellable {
                    // Palette needs pixel access; hardware bitmaps (API 26+)
                    // must be copied to a software config first.
                    val softwareBitmap =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            bitmap.config == Bitmap.Config.HARDWARE
                        ) {
                            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            bitmap
                        }
                    Palette.from(softwareBitmap).generate().selectAmbientColor()?.let { selected ->
                        AmbientExtractionResult.ColorReady(
                            Color(AmbientPalette.normalizeForBackdrop(selected)),
                        )
                    } ?: AmbientExtractionResult.NoSwatch
                }.getOrElse { error -> AmbientExtractionResult.Failed(error) }

            withContext(Dispatchers.Main) {
                val color = (result as? AmbientExtractionResult.ColorReady)?.color
                if (color != null) {
                    cache.put(request.cacheKey, color)
                }
                publishResult(
                    request = request,
                    result = result,
                    source = "palette",
                    width = bitmap.width,
                    height = bitmap.height,
                )
            }
        }
    }

    private fun publishResult(
        request: AmbientExtractionRequest,
        result: AmbientExtractionResult,
        source: String,
        width: Int,
        height: Int,
    ) {
        if (result is AmbientExtractionResult.Failed) {
            ambientFailureLogger.w { formatAmbientColorFailureDiagnostic(result.throwable) }
        }
        val accepted = onResult(request, result)
        if (!accepted) {
            logAmbientTransition(
                surface = request.surface,
                transition = request.transition,
                event = "extraction",
                source = source,
                result = "stale-rejected",
                width = width,
                height = height,
            )
            return
        }

        when (result) {
            is AmbientExtractionResult.ColorReady ->
                logAmbientTransition(
                    surface = request.surface,
                    transition = request.transition,
                    event = "extraction",
                    source = source,
                    result = "applied",
                    width = width,
                    height = height,
                    color = result.color,
                )
            AmbientExtractionResult.NoSwatch ->
                logAmbientTransition(
                    surface = request.surface,
                    transition = request.transition,
                    event = "extraction",
                    source = source,
                    result = "no-swatch",
                    width = width,
                    height = height,
                )
            is AmbientExtractionResult.Failed ->
                logAmbientTransition(
                    surface = request.surface,
                    transition = request.transition,
                    event = "extraction",
                    source = source,
                    result = "failed",
                    width = width,
                    height = height,
                    exceptionType = result.throwable.safeDiagnosticType(),
                )
        }
    }
}

internal fun formatAmbientColorFailureDiagnostic(throwable: Throwable): String =
    formatSafeFailureDiagnostic(
        stage = "ambient-color",
        event = "failed",
        operation = DiagnosticOperation.AmbientColorExtraction,
        throwable = throwable,
    )

internal data class TvHeroAmbientState(
    val presentation: TvHeroAmbientPresentation,
    val onPosterLoaded: (itemId: String, bitmap: Bitmap) -> Unit,
)

@Composable
internal fun rememberTvHeroAmbientState(
    item: MediaCardUi?,
    surface: TvHeroAmbientSurface,
    accountKey: String,
): TvHeroAmbientState {
    val requestedOwnerKey =
        remember(item?.id, item?.imageUrl, item?.backdropUrl, item?.imageRef, surface, accountKey) {
            item?.id?.let { itemId ->
                TvHeroAmbientOwnerKey(
                    itemId = itemId,
                    surface = surface,
                    cacheKey = item.ambientImageCacheKey(accountKey),
                )
            }
        }
    val currentRequestedOwnerKey = rememberUpdatedState(requestedOwnerKey)
    val ownershipState =
        remember {
            mutableStateOf(
                TvHeroAmbientOwnershipState(
                    ownerKey = null,
                    transition = 0L,
                    color = null,
                ),
            )
        }
    val scope = rememberCoroutineScope()
    val controller =
        remember {
            AmbientColorController(scope = scope) { request, result ->
                val current = ownershipState.value
                val ownerIsCurrent =
                    currentRequestedOwnerKey.value === request.ownerKey &&
                        current.ownerKey === request.ownerKey &&
                        current.transition == request.transition
                if (ownerIsCurrent && result is AmbientExtractionResult.ColorReady) {
                    ownershipState.value =
                        applyTvHeroAmbientColor(
                            state = current,
                            ownerKey = request.ownerKey,
                            transition = request.transition,
                            color = result.color,
                        )
                }
                ownerIsCurrent
            }
        }
    val posterBitmaps = remember { PosterBitmapSlot() }
    val onPosterLoaded =
        remember(controller, posterBitmaps) {
            { itemId: String, bitmap: Bitmap ->
                posterBitmaps.update(itemId, bitmap)
                val ownerKey = currentRequestedOwnerKey.value
                val current = ownershipState.value
                if (
                    ownerKey != null &&
                    ownerKey.itemId == itemId &&
                    current.ownerKey === ownerKey &&
                    current.transition > 0L
                ) {
                    logAmbientTransition(
                        surface = ownerKey.surface,
                        transition = current.transition,
                        event = "poster",
                        source = AmbientPosterSource.ImageLoader.token,
                        result = "arrived",
                        width = bitmap.width,
                        height = bitmap.height,
                    )
                    controller.extractFromPoster(
                        request =
                            AmbientExtractionRequest(
                                itemId = itemId,
                                cacheKey = ownerKey.cacheKey,
                                ownerKey = ownerKey,
                                transition = current.transition,
                                surface = ownerKey.surface,
                            ),
                        bitmap = bitmap,
                        source = AmbientPosterSource.ImageLoader,
                    )
                }
            }
        }

    LaunchedEffect(requestedOwnerKey) {
        val ownerKey = requestedOwnerKey
        if (ownerKey == null) {
            ownershipState.value = selectTvHeroAmbientOwner(ownerKey = null, transition = 0L)
            return@LaunchedEffect
        }

        val transition = ambientTransitionCounter.incrementAndGet()
        val selected = selectTvHeroAmbientOwner(ownerKey = ownerKey, transition = transition)
        ownershipState.value = selected
        val cachedColor = controller.colorFor(ownerKey.cacheKey)
        logAmbientTransition(
            surface = ownerKey.surface,
            transition = transition,
            event = "selected",
            source = "color-cache",
            result = if (cachedColor == null) "no-color-cache-miss" else "color-cache-hit",
            color = cachedColor,
        )
        if (cachedColor != null) {
            ownershipState.value =
                applyTvHeroAmbientColor(
                    state = selected,
                    ownerKey = ownerKey,
                    transition = transition,
                    color = cachedColor,
                )
            logAmbientTransition(
                surface = ownerKey.surface,
                transition = transition,
                event = "extraction",
                source = "color-cache",
                result = "applied",
                color = cachedColor,
            )
            return@LaunchedEffect
        }

        val cachedBitmap = posterBitmaps.bitmapFor(ownerKey.itemId)
        logAmbientTransition(
            surface = ownerKey.surface,
            transition = transition,
            event = "poster",
            source = AmbientPosterSource.BitmapCache.token,
            result = if (cachedBitmap == null) "miss" else "hit",
            width = cachedBitmap?.width,
            height = cachedBitmap?.height,
        )
        if (cachedBitmap != null) {
            controller.extractFromPoster(
                request =
                    AmbientExtractionRequest(
                        itemId = ownerKey.itemId,
                        cacheKey = ownerKey.cacheKey,
                        ownerKey = ownerKey,
                        transition = transition,
                        surface = ownerKey.surface,
                    ),
                bitmap = cachedBitmap,
                source = AmbientPosterSource.BitmapCache,
            )
        }
    }

    val presentedState =
        if (ownershipState.value.ownerKey === requestedOwnerKey) {
            ownershipState.value
        } else {
            selectTvHeroAmbientOwner(ownerKey = requestedOwnerKey, transition = 0L)
        }
    val presentation =
        if (presentedState.ownerKey == null) {
            TvHeroAmbientPresentation.Empty
        } else {
            TvHeroAmbientPresentation(
                animationOwnerKey = presentedState.ownerKey,
                transition = presentedState.transition,
                color = presentedState.color,
                clearWhenColorMissing = false,
            )
        }

    return TvHeroAmbientState(
        presentation = presentation,
        onPosterLoaded = onPosterLoaded,
    )
}

private fun MediaCardUi.ambientImageCacheKey(accountKey: String): String {
    val reference = imageRef
    val selectedImage = backdropUrl ?: imageUrl.orEmpty()
    return listOf(
        accountKey,
        id,
        selectedImage,
        reference?.type?.apiValue.orEmpty(),
        reference?.tag.orEmpty(),
    ).joinToString("|")
}

private fun logAmbientTransition(
    surface: TvHeroAmbientSurface,
    transition: Long,
    event: String,
    source: String,
    result: String,
    width: Int? = null,
    height: Int? = null,
    color: Color? = null,
    exceptionType: String? = null,
) {
    ambientLogger.i {
        buildString {
            append("surface=")
            append(surface.token)
            append(" transition=")
            append(transition)
            append(" event=")
            append(event)
            append(" source=")
            append(source)
            append(" result=")
            append(result)
            if (width != null && height != null) {
                append(" width=")
                append(width)
                append(" height=")
                append(height)
            }
            if (color != null) {
                append(" color=")
                append(
                    color
                        .toArgb()
                        .toUInt()
                        .toString(16)
                        .padStart(8, '0'),
                )
            }
            if (exceptionType != null) {
                append(" exceptionType=")
                append(exceptionType)
            }
        }
    }
}

object AmbientPalette {
    // Saturated swatches first: dark-swatch-first picked near-black colors
    // (e.g. #161a1e) that vanished against the navy base. The low-luminance
    // effect needs the poster's rich hue, darkened afterwards.
    fun selectAmbientColor(
        vibrant: Color?,
        darkVibrant: Color?,
        dominant: Color?,
        muted: Color?,
        darkMuted: Color?,
    ): Color? = vibrant ?: darkVibrant ?: dominant ?: muted ?: darkMuted

    // Normalize the swatch to a rich, dark backdrop tint in HSL space:
    // enforce enough saturation to read as color (unless the source is
    // genuinely neutral) and clamp luminance so text stays legible.
    fun normalizeForBackdrop(argb: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(argb, hsl)
        if (hsl[1] >= NEUTRAL_SATURATION) {
            hsl[1] = hsl[1].coerceAtLeast(0.45f)
        }
        hsl[2] = hsl[2].coerceIn(0.16f, 0.30f)
        return ColorUtils.HSLToColor(hsl)
    }

    private const val NEUTRAL_SATURATION = 0.08f
}

private fun Palette.selectAmbientColor(): Int? =
    AmbientPalette
        .selectAmbientColor(
            vibrant = vibrantSwatch?.rgb?.let(::Color),
            darkVibrant = darkVibrantSwatch?.rgb?.let(::Color),
            dominant = dominantSwatch?.rgb?.let(::Color),
            muted = mutedSwatch?.rgb?.let(::Color),
            darkMuted = darkMutedSwatch?.rgb?.let(::Color),
        )?.toArgb()
