// SPDX-License-Identifier: MPL-2.0
package com.jellyscope.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.SizeResolver
import kotlin.math.roundToInt

val LocalImageDecodeScale = staticCompositionLocalOf { 1f }
val MeasuredImageRequestKey = Extras.Key(default = false)

fun scaledImageSize(
    size: IntSize,
    scale: Float,
): Size =
    Size(
        (size.width * scale).roundToInt().coerceAtLeast(1),
        (size.height * scale).roundToInt().coerceAtLeast(1),
    )

/** Caps each dimension at [limit] so a reduced target never exceeds what High requests. */
fun Size.limitedTo(limit: Size): Size = Size(width.limitedTo(limit.width), height.limitedTo(limit.height))

@Composable
fun MeasuredAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    filterQuality: FilterQuality = FilterQuality.Low,
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalPlatformContext.current),
) {
    val scale = LocalImageDecodeScale.current
    val context = LocalPlatformContext.current
    val constraints = rememberConstraintsSizeResolver()
    val reduced = scale < 1f && model != null
    val request =
        remember(model, scale, context, constraints) {
            if (scale < 1f && model != null) reducedImageRequest(model, scale, constraints, context) else model
        }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        modifier = if (reduced) modifier.then(constraints) else modifier,
        alignment = alignment,
        contentScale = contentScale,
        onSuccess = onSuccess,
        filterQuality = filterQuality,
    )
}

private fun reducedImageRequest(
    model: Any,
    scale: Float,
    constraints: SizeResolver,
    context: PlatformContext,
): ImageRequest {
    val original = model as? ImageRequest
    // Only an explicit caller size bounds High; otherwise High also decodes at the layout constraints.
    val highSize = original?.defined?.sizeResolver
    val builder = original?.newBuilder() ?: ImageRequest.Builder(context).data(model)
    return builder
        .size(
            SizeResolver {
                val layout = constraints.size()
                val scaled = Size(layout.width.scaledBy(scale), layout.height.scaledBy(scale))
                highSize?.let { scaled.limitedTo(it.size()) } ?: scaled
            },
        ).apply { extras[MeasuredImageRequestKey] = true }
        .build()
}

private fun Dimension.scaledBy(scale: Float): Dimension =
    if (this is Dimension.Pixels) Dimension.Pixels((px * scale).roundToInt().coerceAtLeast(1)) else this

private fun Dimension.limitedTo(limit: Dimension): Dimension =
    when {
        limit !is Dimension.Pixels -> this
        this is Dimension.Pixels -> Dimension.Pixels(minOf(px, limit.px))
        else -> limit
    }
