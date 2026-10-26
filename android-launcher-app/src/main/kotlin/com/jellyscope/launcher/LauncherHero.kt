// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.toBitmap
import com.jellyscope.hero.HeroAmbientBackground
import com.jellyscope.hero.HeroAmbientPresentation
import com.jellyscope.hero.HeroBackdrop
import com.jellyscope.hero.HeroContentCrossfade
import com.jellyscope.hero.extractHeroAmbientColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    val rows = remember(state.media.rows, state.selectedTab, state.signedIn) { state.homeRows }

    val targets =
        remember(rows) {
            buildMap {
                rows.forEach { row ->
                    row.cards.forEach { card ->
                        val target = LauncherHeroTarget(row.focusKey, card)
                        put(target.key, target)
                    }
                }
            }
        }

    fun target(key: String?): LauncherHeroTarget? = key?.let(targets::get)
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
    appsFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    if (state.preferences.heroEnabled != true || !state.signedIn || state.boundaryKey == null) return
    val activeImages = images?.takeIf { it.isActive }
    val active = !state.backgroundSuppressed && LocalWindowInfo.current.isWindowFocused
    // Replacement disposes both layers before an old account/scope can paint again.
    key(state.boundaryKey, state.selectedTab, state.homeResetGeneration, activeImages) {
        var retained by remember { mutableStateOf<LauncherHeroTarget?>(null) }
        SideEffect { if (target != null) retained = target }
        val retainedValid =
            retained?.takeIf { saved ->
                state.homeRows.any { row -> row.focusKey == saved.rowKey && row.cards.any { it.id == saved.card.id } }
            }
        val fading = target == null && appsFocused && retainedValid != null
        val visible = remember { Animatable(if (target == null) 0f else 1f) }
        LaunchedEffect(target != null, fading) {
            if (target != null || fading) {
                visible.animateTo(if (target != null) 1f else 0f, tween(LauncherStyle.HERO_CONTENT_FADE_MILLIS))
                if (target == null) retained = null
            } else {
                visible.snapTo(0f)
                retained = null
            }
        }
        val scene = target ?: retainedValid.takeIf { fading }
        if (scene != null) {
            LauncherHeroScene(
                scene,
                activeImages,
                active && !fading,
                state.selectedTab,
                modifier.graphicsLayer { alpha = visible.value },
            )
        }
    }
}

private data class HeroArtwork(
    val owner: Any,
    val bitmap: ImageBitmap?,
    val tint: Color?,
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
    tab: LauncherTab,
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
                            color = withContext(heroPaletteDispatcher) { extractHeroAmbientColor(decoded) }
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
        artwork = HeroArtwork(owner, bitmap, color, latestText.value)
    }

    LaunchedEffect(owner, requestedText, active) {
        if (!latestActive.value || latestOwner.value !== owner) return@LaunchedEffect
        val displayed = artwork?.takeIf { it.owner === owner } ?: return@LaunchedEffect
        val updatedText = latestText.value
        if (displayed.text != updatedText) artwork = displayed.copy(text = updatedText)
    }
    Box(
        modifier.HeroAmbientBackground(
            HeroAmbientPresentation(artwork?.owner, artwork?.tint),
            navy = LauncherStyle.backgroundEnd,
            gradientBottom = LauncherStyle.backgroundEnd,
            fallbackBrush = null,
            overlay = true,
        ),
    ) {
        HeroBackdrop(
            target = artwork,
            assetKey = artwork?.owner,
            navy = LauncherStyle.backgroundEnd,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(LauncherStyle.HERO_BACKDROP_WIDTH)
                    .height(LauncherStyle.heroBackdropHeight),
        ) { layer ->
            layer?.bitmap?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        // Readability remains behind the compact copy and overlapping ribbons.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, LauncherStyle.backgroundEnd.copy(alpha = 0.3f), LauncherStyle.backgroundEnd),
                ),
            ),
        )
        HeroContentCrossfade(target = artwork, assetKey = artwork?.owner) { layer ->
            layer?.let { HeroCopy(it.text, tab) }
        }
    }
}

@Composable
private fun HeroCopy(
    text: HeroText,
    tab: LauncherTab,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = LauncherStyle.headerHeight)
            .height(launcherHeroHeight(tab))
            .clipToBounds()
            .padding(horizontal = LauncherStyle.inset)
            .padding(top = LauncherStyle.heroContentTop),
        verticalArrangement = Arrangement.spacedBy(LauncherStyle.heroContentGap),
    ) {
        Text(
            text.title,
            Modifier.fillMaxWidth(LauncherStyle.HERO_TITLE_WIDTH),
            style = LauncherStyle.heroTitle,
            maxLines = if (tab == LauncherTab.Essentials) 1 else 2,
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
                Text(
                    text.overview,
                    style = LauncherStyle.body,
                    color = LauncherStyle.muted,
                    maxLines =
                        if (tab ==
                            LauncherTab.Essentials
                        ) {
                            2
                        } else {
                            3
                        },
                    overflow = TextOverflow.Ellipsis,
                )
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
