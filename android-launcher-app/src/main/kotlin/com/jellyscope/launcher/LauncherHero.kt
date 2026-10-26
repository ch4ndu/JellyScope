// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import androidx.tv.material3.Text
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class LauncherHeroTarget(
    val rowKey: String,
    val card: LauncherCard,
) {
    val key: String get() = "$rowKey:${card.id}"
}

@Composable
internal fun rememberLauncherHeroTarget(
    state: LauncherUiState,
    focus: LauncherFocus,
): LauncherHeroTarget? {
    val enabled = state.preferences.heroEnabled == true && state.signedIn && state.boundaryKey != null
    var selected by remember(state.boundaryKey, state.selectedTab, state.homeResetGeneration, enabled) { mutableStateOf<String?>(null) }
    val rows = state.homeRows

    fun target(key: String?): LauncherHeroTarget? =
        rows.firstNotNullOfOrNull { row ->
            row.cards.firstOrNull { "${row.focusKey}:${it.id}" == key }?.let { LauncherHeroTarget(row.focusKey, it) }
        }
    val current = focus.current
    val frozen = state.backgroundSuppressed || !LocalWindowInfo.current.isWindowFocused
    val appsFocused = !frozen && !focus.parentFocused && current?.startsWith("apps:") == true
    val selectedTarget = target(selected)
    val candidate =
        if (!enabled || frozen || appsFocused) {
            null
        } else if (focus.hasFocus && !focus.parentFocused) {
            target(current)
        } else if (selected == null && state.selectedTab == LauncherTab.Jellyfin) {
            rows.firstNotNullOfOrNull { row -> row.cards.firstOrNull()?.let { LauncherHeroTarget(row.focusKey, it) } }
        } else {
            null
        }
    LaunchedEffect(enabled, frozen, appsFocused, current, candidate?.key, selectedTarget?.key) {
        if (!enabled || appsFocused) {
            selected = null
        } else if (!frozen) {
            if (selectedTarget == null) selected = null
            candidate?.takeIf { it.key != selectedTarget?.key }?.let {
                delay(LauncherStyle.HERO_SETTLE_MILLIS)
                selected = it.key
            }
        }
    }
    return selectedTarget.takeIf { enabled && !appsFocused }
}

@Composable
internal fun LauncherHero(
    state: LauncherUiState,
    target: LauncherHeroTarget?,
    images: LauncherImageScope?,
    modifier: Modifier = Modifier,
) {
    if (target == null || state.preferences.heroEnabled != true || state.boundaryKey == null) return
    val activeImages = images?.takeIf { it.isActive }
    val active = !state.backgroundSuppressed && LocalWindowInfo.current.isWindowFocused
    // Replacement disposes both layers before an old account/scope can paint again.
    key(state.boundaryKey, state.selectedTab, state.homeResetGeneration, activeImages) {
        LauncherHeroScene(target, activeImages, active, modifier)
    }
}

private data class HeroArtwork(
    val owner: Any,
    val bitmap: ImageBitmap?,
    val tint: Color,
    val text: HeroText,
)

private data class HeroText(
    val title: String,
    val metadata: String,
    val overview: String,
)

@Composable
private fun LauncherHeroScene(
    target: LauncherHeroTarget,
    images: LauncherImageScope?,
    active: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = remember(target.rowKey, target.card.id, target.card.heroBackdropTag) { Any() }
    val latestOwner = rememberUpdatedState(owner)
    val latestActive = rememberUpdatedState(active)
    val requestedText = HeroText(target.card.title, target.card.heroMetadata(), target.card.overview.orEmpty())
    val latestText = rememberUpdatedState(requestedText)
    var loadGeneration by remember { mutableStateOf(0L) }
    var artwork by remember { mutableStateOf<HeroArtwork?>(null) }
    var previousArtwork by remember { mutableStateOf<HeroArtwork?>(null) }
    var gradientFrom by remember { mutableStateOf(Color.Transparent) }
    var transition by remember { mutableStateOf(0L) }
    var startedTransition by remember { mutableStateOf(0L) }
    val imageAlpha = remember { Animatable(1f) }
    val gradientAlpha = remember { Animatable(1f) }
    val textAlpha = remember { Animatable(1f) }
    val imageProgress = if (startedTransition == transition) imageAlpha.value else 0f
    val gradientProgress = if (startedTransition == transition) gradientAlpha.value else 0f
    val textProgress = if (startedTransition == transition) textAlpha.value else 0f
    val tint = lerp(gradientFrom, artwork?.tint ?: Color.Transparent, gradientProgress)

    LaunchedEffect(owner, active) {
        if (!active || artwork?.owner === owner) return@LaunchedEffect
        val generation = ++loadGeneration
        var bitmap: ImageBitmap? = null
        var color: Color? = null
        try {
            val request = images?.heroRequest(context, target.card)
            if (request != null) {
                when (val result = images.loader.execute(request)) {
                    is SuccessResult -> {
                        val decoded =
                            withContext(Dispatchers.Default) {
                                val image = result.image
                                require(image.width in 1..LauncherStyle.HERO_MAX_WIDTH && image.height in 1..LauncherStyle.HERO_MAX_HEIGHT)
                                image.toBitmap(width = image.width, height = image.height)
                            }
                        bitmap = decoded.asImageBitmap()
                        try {
                            color = withContext(heroPaletteDispatcher) { heroAmbientColor(decoded) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            launcherFailure("hero-palette", failure)
                        }
                    }
                    is ErrorResult -> launcherFailure("hero-image", result.throwable)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            launcherFailure("hero-image", failure)
        }
        if (latestOwner.value !== owner || !latestActive.value || generation != loadGeneration || images?.isActive == false) {
            return@LaunchedEffect
        }
        // Keep the displayed owner intact while loading. Publish the new copy
        // with its terminal artwork, using metadata refreshed during the request.
        val progress = if (startedTransition == transition) imageAlpha.value else 0f
        val gradient = if (startedTransition == transition) gradientAlpha.value else 0f
        gradientFrom = lerp(gradientFrom, artwork?.tint ?: Color.Transparent, gradient)
        previousArtwork = if (progress < 0.5f) previousArtwork ?: artwork else artwork ?: previousArtwork
        artwork =
            HeroArtwork(
                owner,
                bitmap,
                color?.let { lerp(LauncherStyle.backgroundEnd, it, 0.55f).copy(alpha = 0.9f) } ?: Color.Transparent,
                latestText.value,
            )
        transition += 1
    }
    LaunchedEffect(transition, active) {
        if (!active || artwork == null) return@LaunchedEffect
        val generation = transition
        if (startedTransition != generation) {
            imageAlpha.snapTo(0f)
            gradientAlpha.snapTo(0f)
            textAlpha.snapTo(0f)
            startedTransition = generation
        }
        coroutineScope {
            launch { imageAlpha.animateTo(1f, tween(LauncherStyle.HERO_IMAGE_FADE_MILLIS)) }
            launch { gradientAlpha.animateTo(1f, tween(LauncherStyle.HERO_CONTENT_FADE_MILLIS)) }
            launch { textAlpha.animateTo(1f, tween(LauncherStyle.HERO_CONTENT_FADE_MILLIS)) }
        }
        if (transition == generation) previousArtwork = null
    }

    LaunchedEffect(owner, requestedText, active) {
        if (!latestActive.value || latestOwner.value !== owner) return@LaunchedEffect
        val displayed = artwork?.takeIf { it.owner === owner } ?: return@LaunchedEffect
        val updatedText = latestText.value
        if (displayed.text != updatedText) artwork = displayed.copy(text = updatedText)
    }
    Box(modifier) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(tint, Color.Transparent))))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(LauncherStyle.HERO_BACKDROP_WIDTH)
                .height(LauncherStyle.heroBackdropHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    // Same physical-edge masks as TvHomeHero.
                    val horizontal = Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black)
                    val vertical = Brush.verticalGradient(0f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent)
                    onDrawWithContent {
                        drawContent()
                        drawRect(horizontal, blendMode = BlendMode.DstIn)
                        drawRect(vertical, blendMode = BlendMode.DstIn)
                    }
                },
        ) {
            previousArtwork?.bitmap?.let { HeroImage(it, 1f - imageProgress) }
            artwork?.bitmap?.let { HeroImage(it, imageProgress) }
        }
        previousArtwork?.let { HeroCopy(it.text, 1f - textProgress, Modifier.clearAndSetSemantics {}) }
        artwork?.let { HeroCopy(it.text, textProgress) }
    }
}

@Composable
private fun HeroImage(
    bitmap: ImageBitmap,
    alpha: Float,
) {
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    listOf(
                        LauncherStyle.backgroundEnd.copy(alpha = 0.55f),
                        LauncherStyle.backgroundEnd.copy(alpha = 0.2f),
                        Color.Transparent,
                    ),
                ),
            ),
        )
    }
}

@Composable
private fun HeroCopy(
    text: HeroText,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = LauncherStyle.headerHeight)
            .height(LauncherStyle.heroHeight)
            .clipToBounds()
            .padding(horizontal = LauncherStyle.inset)
            .padding(top = LauncherStyle.heroContentTop)
            .graphicsLayer { this.alpha = alpha },
        verticalArrangement = Arrangement.spacedBy(LauncherStyle.heroContentGap),
    ) {
        Text(
            text.title,
            Modifier.fillMaxWidth(LauncherStyle.HERO_TITLE_WIDTH),
            style = LauncherStyle.heroTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Column(
            Modifier.fillMaxWidth(LauncherStyle.HERO_CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(LauncherStyle.heroContentGap),
        ) {
            if (text.metadata.isNotEmpty()) {
                Text(text.metadata, style = LauncherStyle.body, color = LauncherStyle.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (text.overview.isNotEmpty()) {
                Text(text.overview, style = LauncherStyle.body, color = LauncherStyle.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LauncherCard.heroMetadata(): String {
    val parts =
        listOfNotNull(
            seriesName?.takeIf { it.isNotBlank() },
            if (season != null && episode != null) stringResource(R.string.hero_episode, season, episode) else null,
            year?.toString(),
            runtimeMinutes?.let { stringResource(R.string.hero_runtime, it) },
            officialRating?.takeIf { it.isNotBlank() },
        )
    var line = parts.firstOrNull() ?: return ""
    for (index in 1..parts.lastIndex) line = stringResource(R.string.hero_metadata_separator, line, parts[index])
    return line
}

private val heroPaletteDispatcher = Dispatchers.Default.limitedParallelism(1)

/** Adapted from AmbientColorController's swatch order and HSL normalization. */
private fun heroAmbientColor(bitmap: Bitmap): Color? {
    val software =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }
    var sample: Bitmap? = null
    try {
        val scale = minOf(1f, LauncherStyle.HERO_PALETTE_SIZE.toFloat() / maxOf(software.width, software.height))
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
            palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch ?: palette.darkMutedSwatch
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(swatch?.rgb ?: return null, hsl)
        if (hsl[1] >= 0.08f) hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = hsl[2].coerceIn(0.16f, 0.30f)
        return Color(ColorUtils.HSLToColor(hsl))
    } finally {
        // Only our downsample/software copies are owned here; Coil's bitmap is retained.
        sample?.takeIf { it !== software }?.recycle()
        if (software !== bitmap) software.recycle()
    }
}
