// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Precision
import com.jellyscope.core.data.remote.ImageAuthHeaderProvider
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.home_card_cd_episode
import com.jellyscope.ui.generated.resources.home_card_cd_library
import com.jellyscope.ui.generated.resources.home_card_cd_movie
import com.jellyscope.ui.generated.resources.home_card_cd_other
import com.jellyscope.ui.generated.resources.home_card_cd_series
import com.jellyscope.ui.generated.resources.home_progress_state
import com.jellyscope.ui.generated.resources.home_unsupported_badge
import com.jellyscope.ui.generated.resources.home_watched_badge
import com.jellyscope.ui.generated.resources.tv_kind_episode
import com.jellyscope.ui.generated.resources.tv_kind_item
import com.jellyscope.ui.generated.resources.tv_kind_library
import com.jellyscope.ui.generated.resources.tv_kind_movie
import com.jellyscope.ui.generated.resources.tv_kind_series
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Immutable
data class MediaCardUi(
    val id: String,
    val title: String,
    val subtitle: String?,
    val progressFraction: Float?,
    val watched: Boolean,
    val isFavorite: Boolean = false,
    val unplayedCount: Int?,
    val imageUrl: String?,
    // The inputs the URL above was built from, when they are known. Cards prefer
    // this so they can request the variant width they are going to decode at;
    // `imageUrl` remains the fallback for producers that only have a finished URL.
    val imageRef: MediaImageRef? = null,
    val kind: MediaCardKind,
    val supported: Boolean = true,
    val year: String? = null,
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val runtimeText: String? = null,
    val metadataLine: String? = null,
    val heroMetadataLine: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val genresLine: String? = null,
    val backdropUrl: String? = null,
    val resumePositionTicks: Long? = null,
    val libraryCollectionType: LibraryCollectionType? = null,
)

enum class MediaCardKind {
    Library,
    Movie,
    Series,
    Episode,
    Other,
}

@Immutable
data class TvHeroMetadata(
    val seriesName: String?,
    val episodeLabel: String?,
    val yearOrFallback: String?,
    val runtimeText: String?,
    val progressPercent: Int?,
    val watched: Boolean,
    val unsupported: Boolean,
)

@Immutable
internal data class MediaCardSemanticState(
    val watched: Boolean,
    val unsupported: Boolean,
    val progressPercent: Int?,
)

internal fun mediaCardSemanticState(
    watched: Boolean,
    supported: Boolean,
    progressFraction: Float?,
): MediaCardSemanticState =
    MediaCardSemanticState(
        watched = watched,
        unsupported = !supported,
        progressPercent =
            progressFraction
                ?.takeIf { progress -> progress.isFinite() }
                ?.coerceIn(0f, 1f)
                ?.times(100f)
                ?.roundToInt(),
    )

fun MediaCardUi.tvHeroMetadata(): TvHeroMetadata =
    TvHeroMetadata(
        seriesName = seriesName,
        episodeLabel = episodeLabel,
        yearOrFallback = year ?: subtitle?.takeIf { value -> value.length == 4 && value.all { it.isDigit() } },
        runtimeText = runtimeText,
        progressPercent = progressFraction?.let { progress -> (progress * 100).toInt() },
        watched = watched,
        unsupported = !supported,
    )

@Composable
fun mediaCardKindLabel(kind: MediaCardKind): String =
    when (kind) {
        MediaCardKind.Library -> stringResource(Res.string.tv_kind_library)
        MediaCardKind.Movie -> stringResource(Res.string.tv_kind_movie)
        MediaCardKind.Series -> stringResource(Res.string.tv_kind_series)
        MediaCardKind.Episode -> stringResource(Res.string.tv_kind_episode)
        MediaCardKind.Other -> stringResource(Res.string.tv_kind_item)
    }

/** Returns whether this card kind can start direct playback from a card action. */
internal fun MediaCardKind.isDirectlyPlayable(): Boolean =
    this == MediaCardKind.Movie ||
        this == MediaCardKind.Episode ||
        this == MediaCardKind.Other

/** Builds the card long-click direct-play action when the item is directly playable. */
internal fun MediaCardUi.directPlayAction(onPlayItem: (String, Long) -> Unit): (() -> Unit)? =
    if (kind.isDirectlyPlayable()) {
        {
            onPlayItem(id, resumePositionTicks ?: 0L)
        }
    } else {
        null
    }

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MediaCard(
    item: MediaCardUi,
    session: Session,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    useWideCard: Boolean = false,
    fillWidth: Boolean = false,
    imageAspectRatio: Float? = null,
    titleMaxLines: Int = 2,
    showSubtitle: Boolean = true,
    durationLabel: String? = null,
    artwork: (@Composable () -> Unit)? = null,
) {
    val cardWidth =
        if (useWideCard) {
            Dimensions.libraryCardWidth.tileScaled()
        } else {
            Dimensions.posterCardWidth.tileScaled()
        }
    val widthModifier =
        if (fillWidth) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width(cardWidth)
        }
    val contentDescription = mediaCardContentDescription(item)
    val semanticState =
        mediaCardSemanticState(
            watched = item.watched,
            supported = item.supported,
            progressFraction = item.progressFraction,
        )
    val mediaStateDescription =
        listOfNotNull(
            stringResource(Res.string.home_watched_badge).takeIf { semanticState.watched },
            stringResource(Res.string.home_unsupported_badge).takeIf { semanticState.unsupported },
            semanticState.progressPercent?.let { percent ->
                stringResource(Res.string.home_progress_state, percent)
            },
        ).joinToString(", ")
            .takeIf { description -> description.isNotEmpty() }
    val palette = LocalJellyfinPalette.current

    Column(
        modifier =
            modifier
                .then(widthModifier)
                .heightIn(min = Dimensions.minTouchTarget)
                .alpha(
                    if (item.supported) {
                        1f
                    } else {
                        0.5f
                    },
                ).semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    mediaStateDescription?.let { description -> stateDescription = description }
                }.let { clickableModifier ->
                    if (onLongClick == null) {
                        clickableModifier.clickable(onClick = onClick)
                    } else {
                        clickableModifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    }
                },
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        imageAspectRatio ?: if (useWideCard) {
                            Dimensions.libraryCardAspectRatio
                        } else {
                            Dimensions.posterCardAspectRatio
                        },
                    ),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box {
                val imageUrl = item.imageUrl
                if (artwork != null) {
                    artwork()
                } else if (imageUrl != null) {
                    // Decode at the size this card is drawn at, derived from the
                    // resolved tile token and display density — not from a measured
                    // per-item width, which would mint a cache entry per card. See
                    // cardImageDecode.
                    val density = LocalDensity.current
                    val aspect =
                        if (useWideCard) CardImageAspect.Wide else CardImageAspect.Poster
                    val decode =
                        remember(cardWidth, density, aspect, fillWidth) {
                            cardImageDecode(
                                widthPx = with(density) { cardWidth.toPx() },
                                aspect = aspect,
                                adaptiveCell = fillWidth,
                            )
                        }
                    val request =
                        item.imageRef?.let { ref ->
                            authenticatedImageRequest(ref, session, decode)
                        } ?: authenticatedImageRequest(imageUrl, session, decode)
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (item.watched) {
                    WatchedBadge(
                        contentDescription = stringResource(Res.string.home_watched_badge),
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(Dimensions.badgePadding),
                    )
                } else if (!item.supported) {
                    MediaCardBadge(
                        text = stringResource(Res.string.home_unsupported_badge),
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                durationLabel?.let { label ->
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(Dimensions.badgePadding),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(Dimensions.badgePadding))
                    }
                }
                item.progressFraction?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        color = palette.accentAmber,
                        trackColor =
                            palette.accentAmber.copy(
                                alpha = MEDIA_CARD_PROGRESS_TRACK_ALPHA,
                            ),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(Dimensions.progressBarHeight),
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            minLines = titleMaxLines,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (showSubtitle) {
            item.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: Spacer(modifier = Modifier.height(Dimensions.contentSpacing))
        }
    }
}

@Composable
private fun MediaCardBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier =
            modifier
                .padding(Dimensions.badgePadding)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraSmall,
                ).padding(
                    horizontal = Dimensions.badgeHorizontalPadding,
                    vertical = Dimensions.badgeVerticalPadding,
                ),
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelSmall,
    )
}

private const val MEDIA_CARD_PROGRESS_TRACK_ALPHA = 0.24f

/**
 * Decode dimensions for an image request. EXACT precision means a given URL
 * decodes to exactly this bitmap, and the cache key carries the size, so two
 * surfaces asking for the same size share one entry. Aspects must match the
 * content or EXACT squashes it.
 *
 * Detail and hero surfaces use these canonical sizes; list and grid cards derive
 * a bounded size from their resolved tile token via [cardImageDecode].
 */
data class ImageDecode(
    val width: Int,
    val height: Int,
) {
    companion object {
        val Poster = ImageDecode(width = 480, height = 720)
        val Thumb = ImageDecode(width = 480, height = 270)
        val Backdrop = ImageDecode(width = 1280, height = 720)
    }
}

/**
 * Everything needed to build an image URL, so the requested variant width can be
 * chosen where the display size is known rather than baked in by the mapper.
 *
 * The mapper used to hand cards a finished URL at a fixed `maxWidth`, which made
 * "request what you are going to decode" impossible to express: the width is only
 * known inside composition, after the tile scale and density are resolved.
 */
data class MediaImageRef(
    val serverUrl: String,
    val itemId: String,
    val type: JellyfinImageType,
    val tag: String?,
)

/**
 * Request for an image whose URL is built here, at the width it will be decoded
 * at, so the download and the decode agree instead of the server sending a fixed
 * 300px variant that then gets scaled to something else.
 */
@Composable
fun authenticatedImageRequest(
    image: MediaImageRef,
    session: Session,
    decode: ImageDecode,
): ImageRequest {
    val imageUrlBuilder = koinInject<JellyfinImageUrlBuilder>()
    val imageUrl =
        remember(image, decode.width, imageUrlBuilder) {
            imageUrlBuilder.build(
                serverUrl = image.serverUrl,
                itemId = image.itemId,
                type = image.type,
                tag = image.tag,
                maxWidth = decode.width,
            )
        }
    return authenticatedImageRequest(imageUrl, session, decode)
}

@Composable
fun authenticatedImageRequest(
    imageUrl: String,
    session: Session,
    // Null skips the client-side resize (Coil sizes to the target bounds). Use it
    // for sprite sheets like trickplay tiles that must not be downscaled.
    decode: ImageDecode? = ImageDecode.Poster,
): ImageRequest {
    val platformContext = LocalPlatformContext.current
    val imageAuthHeaderProvider = koinInject<ImageAuthHeaderProvider>()
    return remember(
        imageUrl,
        session.deviceId,
        session.accessToken,
        decode,
        platformContext,
        imageAuthHeaderProvider,
    ) {
        buildAuthenticatedImageRequest(
            context = platformContext,
            imageUrl = imageUrl,
            session = session,
            imageAuthHeaderProvider = imageAuthHeaderProvider,
            decode = decode,
        )
    }
}

// Non-composable builder so prefetch (ImageLoader.enqueue) uses the EXACT
// same data + size + cache key + auth as the on-screen request, guaranteeing
// the prefetched bitmap is a cache hit when the composable request runs.
fun buildAuthenticatedImageRequest(
    context: PlatformContext,
    imageUrl: String,
    session: Session,
    imageAuthHeaderProvider: ImageAuthHeaderProvider,
    decode: ImageDecode? = ImageDecode.Poster,
): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(imageUrl)
        .memoryCacheKey(authenticatedImageCacheKey(imageUrl, session, decode))
        .diskCacheKey(authenticatedImageCacheKey(imageUrl, session, decode))
        .apply {
            if (decode != null) {
                size(decode.width, decode.height)
                precision(Precision.EXACT)
            }
        }.httpHeaders(
            NetworkHeaders
                .Builder()
                .set(
                    "Authorization",
                    imageAuthHeaderProvider.authHeader(
                        deviceId = session.deviceId,
                        token = session.accessToken,
                    ),
                ).build(),
        ).build()

internal fun authenticatedImageCacheKey(
    imageUrl: String,
    session: Session,
    decode: ImageDecode?,
): String {
    val account = session.accountIdentity()
    val dimensions = decode?.let { value -> "${value.width}x${value.height}" } ?: "target"
    return "jellyscope-image:${account.serverId.length}:${account.serverId}:" +
        "${account.userId.length}:${account.userId}:$dimensions:$imageUrl"
}

@Composable
private fun mediaCardContentDescription(item: MediaCardUi): String =
    when (item.kind) {
        MediaCardKind.Library -> stringResource(Res.string.home_card_cd_library, item.title)
        MediaCardKind.Movie -> stringResource(Res.string.home_card_cd_movie, item.title)
        MediaCardKind.Series -> stringResource(Res.string.home_card_cd_series, item.title)
        MediaCardKind.Episode -> stringResource(Res.string.home_card_cd_episode, item.title)
        MediaCardKind.Other -> stringResource(Res.string.home_card_cd_other, item.title)
    }
