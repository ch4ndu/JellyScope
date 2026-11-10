// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil3.Extras
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.util.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The successful display request and its loader remain the sampling authority. */
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

/** Public artwork is admitted explicitly, never by the absence of authentication extras. */
data class PublicArtworkOwner(
    val account: AccountIdentity,
    val entry: String,
    val url: String,
) {
    init {
        require(entry.isNotBlank())
        require(canonicalPublicArtwork.matches(url))
    }
}

private val canonicalPublicArtwork = Regex("https://image\\.tmdb\\.org/t/p/(w[0-9]+|original)/[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)")
private val publicArtworkOwnerKey = Extras.Key<PublicArtworkOwner?>(default = null)

/** Starts a fresh public request; no session, credential provider or authenticated builder. */
@Composable
fun publicArtworkRequest(
    owner: PublicArtworkOwner,
    decode: ImageDecode = ImageDecode.Backdrop,
): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(context, owner, decode) {
        val cacheKey = listOf(owner.account.serverId, owner.account.userId, owner.url).joinToString("|")
        ImageRequest
            .Builder(context)
            .data(owner.url)
            .memoryCacheKey("public|$cacheKey|${decode.width}|${decode.height}")
            .diskCacheKey("public|$cacheKey")
            .size(decode.width, decode.height)
            .apply { extras[publicArtworkOwnerKey] = owner }
            .build()
    }
}

@Composable
fun rememberArtworkAmbientColor(
    accountIdentity: AccountIdentity,
    entry: String,
    selectedArtworkUrl: String?,
    extractor: AmbientColorExtractor,
    publicOwner: PublicArtworkOwner? = null,
): AmbientColorState {
    val holder =
        remember(accountIdentity, entry, selectedArtworkUrl, extractor, publicOwner) {
            AmbientColorState(accountIdentity, selectedArtworkUrl, publicOwner)
        }
    val source = holder.source
    LaunchedEffect(holder, source) {
        val accepted = source ?: return@LaunchedEffect
        val color =
            runCatchingCancellable {
                extractor.extract(
                    accepted,
                    listOf(
                        accountIdentity.serverId,
                        accountIdentity.userId,
                        entry,
                        selectedArtworkUrl.orEmpty(),
                        if (publicOwner == null) "authenticated" else "public",
                    ).joinToString("|"),
                )
            }.getOrNull()
        currentCoroutineContext().ensureActive()
        holder.publishIfCurrent(accepted, color)
    }
    return holder
}

/** One account/entry/source owner rejects outgoing Crossfade callbacks and late samples. */
class AmbientColorState(
    private val accountIdentity: AccountIdentity,
    private val selectedArtworkUrl: String?,
    private val publicOwner: PublicArtworkOwner? = null,
) {
    var color: Color? by mutableStateOf(null)
        private set
    private var acceptedSource: AmbientImageSource? by mutableStateOf(null)
    val source: AmbientImageSource? get() = acceptedSource

    fun onImageLoaded(source: AmbientImageSource) {
        val request = source.result.request
        val authenticatedOwner = request.extras[AuthenticatedImageAccountIdentityKey]
        val requestPublicOwner = request.extras[publicArtworkOwnerKey]
        val owned =
            if (publicOwner == null) {
                authenticatedOwner == accountIdentity && requestPublicOwner == null
            } else {
                publicOwner.account == accountIdentity &&
                    publicOwner.url == selectedArtworkUrl &&
                    requestPublicOwner == publicOwner &&
                    authenticatedOwner == null &&
                    request.data == publicOwner.url
            }
        if (!owned || source.originalArtworkUrl != selectedArtworkUrl || source == acceptedSource) return
        color = null
        acceptedSource = source
    }

    fun publishIfCurrent(
        source: AmbientImageSource,
        color: Color?,
    ) {
        if (acceptedSource === source) this.color = color
    }
}
