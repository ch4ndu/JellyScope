// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil3.ImageLoader
import coil3.request.SuccessResult
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.util.runCatchingCancellable
import com.jellyscope.ui.component.AuthenticatedImageAccountIdentityKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Successful display artwork and the loader that owns its authenticated request. [result] retains
 * Coil's final display request; the extractor must sample through it and [imageLoader] rather
 * than reconstructing the source request.
 */
data class AmbientImageSource(
    val result: SuccessResult,
    val imageLoader: ImageLoader,
    val originalArtworkUrl: String,
)

fun interface AmbientColorExtractor {
    suspend fun extract(
        source: AmbientImageSource,
        key: String,
    ): Color?
}

expect fun platformAmbientColorExtractor(): AmbientColorExtractor

@Composable
internal fun rememberAmbientColor(
    session: Session,
    itemId: String,
    selectedArtworkUrl: String?,
    extractor: AmbientColorExtractor,
): AmbientColorState {
    val accountIdentity = session.accountIdentity()
    val holder =
        remember(accountIdentity, itemId, selectedArtworkUrl, extractor) {
            AmbientColorState(
                accountIdentity = accountIdentity,
                selectedArtworkUrl = selectedArtworkUrl,
            )
        }
    val source = holder.source
    LaunchedEffect(holder, source) {
        val acceptedSource = source ?: return@LaunchedEffect
        val color =
            runCatchingCancellable {
                extractor.extract(
                    source = acceptedSource,
                    key = ambientImageCacheKey(session, itemId, selectedArtworkUrl),
                )
            }.getOrNull()
        currentCoroutineContext().ensureActive()
        holder.publishIfCurrent(acceptedSource, color)
    }
    return holder
}

/** Keeps one detail owner from accepting an outgoing Crossfade child's artwork callback. */
internal class AmbientColorState internal constructor(
    private val accountIdentity: AccountIdentity,
    private val selectedArtworkUrl: String?,
) {
    var color: Color? by mutableStateOf(null)
        private set

    private var acceptedSource: AmbientImageSource? by mutableStateOf(null)

    internal val source: AmbientImageSource?
        get() = acceptedSource

    fun onImageLoaded(source: AmbientImageSource) {
        if (source.originalArtworkUrl != selectedArtworkUrl ||
            source.result.request.extras[AuthenticatedImageAccountIdentityKey] != accountIdentity ||
            source == acceptedSource
        ) {
            return
        }
        color = null
        acceptedSource = source
    }

    internal fun publishIfCurrent(
        source: AmbientImageSource,
        color: Color?,
    ) {
        if (acceptedSource === source) {
            this.color = color
        }
    }
}

internal fun ambientImageCacheKey(
    session: Session,
    itemId: String,
    imageUrl: String?,
): String =
    listOf(
        session.serverId,
        session.userId,
        itemId,
        imageUrl.orEmpty(),
    ).joinToString("|")
