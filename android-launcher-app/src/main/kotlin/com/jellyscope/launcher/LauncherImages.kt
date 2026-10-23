// SPDX-License-Identifier: MPL-2.0

@file:OptIn(coil3.annotation.DelicateCoilApi::class)

package com.jellyscope.launcher

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.RefetchableServerCache
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.appendCleanupFailures
import com.jellyscope.core.data.local.throwIfCleanupFailed
import com.jellyscope.core.data.remote.ImageAuthHeaderProvider
import com.jellyscope.core.data.remote.configureJellyfinApi
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.security.CredentialOriginGuard
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File
import java.net.URLEncoder

internal class LauncherImages(
    private val context: Context,
    private val registry: ServerScopedStoreRegistry,
    private val imageAuth: ImageAuthHeaderProvider,
) : RefetchableServerCache,
    AccountScopedClearableStore {
    private val directory = File(context.noBackupFilesDir, "launcher-artwork")
    private val mutex = Mutex()
    private val _active = MutableStateFlow<LauncherImageScope?>(null)
    val active = _active.asStateFlow()

    suspend fun activate(
        session: Session,
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
    ) = withContext(Dispatchers.IO) {
        registry.withGuardedLease(lease) {
            mutex.withLock {
                if (_active.value?.boundary == boundary) return@withLock
                retireLocked()
                _active.value =
                    LauncherImageScope(
                        context,
                        session,
                        boundary,
                        lease,
                        registry,
                        File(directory, stem(boundary.account)),
                        imageAuth,
                    )
            }
        }
    }

    suspend fun reset(
        session: Session,
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
    ) {
        invalidate(boundary, lease)
        activate(session, boundary, lease)
    }

    suspend fun invalidate(
        boundary: LauncherBoundary,
        lease: AccountWorkLease,
    ) = withContext(Dispatchers.IO) {
        registry.withGuardedLease(lease) { mutex.withLock { clearLocked { it == stem(boundary.account) } } }
    }

    override suspend fun clearServerScoped() = clear { true }

    override suspend fun clearServerScoped(serverId: String) = clear { it.startsWith("${launcherHash(serverId)}-") }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) = clear { it == stem(accountIdentity) }

    private suspend fun clear(matches: (String) -> Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock { clearLocked(matches) }
        }

    private fun clearLocked(matches: (String) -> Boolean) {
        if (_active.value
                ?.boundary
                ?.account
                ?.let { matches(stem(it)) } == true
        ) {
            retireLocked()
        }
        val files = directory.listFiles()
        check(files != null || !directory.exists())
        val failures = mutableListOf<Throwable>()
        files.orEmpty().filter { matches(it.name) }.forEach { file ->
            try {
                check(file.deleteRecursively() || !file.exists())
            } catch (failure: Exception) {
                appendCleanupFailures(failures, failure)
            }
        }
        throwIfCleanupFailed(failures)
    }

    private fun retireLocked() {
        val previous = _active.value ?: return
        _active.value = null
        previous.retire()
    }

    private fun stem(account: AccountIdentity) = "${launcherHash(account.serverId)}-${launcherAccountKey(account)}"
}

internal class LauncherImageScope(
    context: Context,
    private val session: Session,
    val boundary: LauncherBoundary,
    lease: AccountWorkLease,
    registry: ServerScopedStoreRegistry,
    directory: File,
    private val imageAuth: ImageAuthHeaderProvider,
) {
    @Volatile
    private var retired = false
    private val cache =
        DiskCache
            .Builder()
            .directory(directory.toOkioPath())
            .maxSizeBytes(64L * 1024 * 1024)
            .build()
    private val client =
        try {
            HttpClient(OkHttp) {
                configureJellyfinApi(followRedirects = false, enableHttpLogging = false)
                engine {
                    config {
                        followRedirects(false)
                        followSslRedirects(false)
                    }
                }
            }
        } catch (failure: Exception) {
            cache.shutdown()
            throw failure
        }
    val loader: ImageLoader =
        try {
            ImageLoader
                .Builder(context)
                .components {
                    add(KtorNetworkFetcherFactory(client))
                    add(
                        object : Interceptor {
                            override suspend fun intercept(chain: Interceptor.Chain): coil3.request.ImageResult {
                                check(!retired)
                                val result = chain.proceed()
                                return registry.withGuardedLease(lease) {
                                    check(!retired)
                                    result
                                } ?: error("Retired image boundary")
                            }
                        },
                    )
                }.memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
                .diskCache(cache)
                .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(2))
                .build()
        } catch (failure: Exception) {
            client.close()
            cache.shutdown()
            throw failure
        }

    fun request(
        context: Context,
        card: LauncherCard,
        aspect: LauncherAspect,
    ): ImageRequest? {
        if (retired) return null
        val primary = card.primaryTag ?: card.imageTag?.takeUnless { card.backdrop }
        val backdrop = card.backdropTag ?: card.imageTag?.takeIf { card.backdrop }
        val useBackdrop = aspect == LauncherAspect.Landscape && !backdrop.isNullOrBlank()
        val tag = (if (useBackdrop) backdrop else primary)?.takeIf { it.isNotBlank() } ?: return null
        if (!card.id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) return null
        val url =
            JellyfinImageUrlBuilder().build(
                serverUrl = session.serverUrl,
                itemId = encodeComponent(card.id),
                type = if (useBackdrop) JellyfinImageType.Backdrop else JellyfinImageType.Primary,
                tag = encodeComponent(tag),
                maxWidth = aspect.width,
            )
        if (!CredentialOriginGuard(session.serverUrl).mayAttachCredentials(url)) return null
        if (session.accessToken.isBlank()) return null
        val authorization = imageAuth.authHeader(deviceId = session.deviceId, token = session.accessToken)
        val diskKey = launcherHash("${launcherAccountKey(boundary.account)}:$url")
        return ImageRequest
            .Builder(context)
            .data(url)
            .httpHeaders(NetworkHeaders.Builder().set("Authorization", authorization).build())
            .diskCacheKey(diskKey)
            .memoryCacheKey("${boundary.key}:$diskKey")
            .build()
    }

    fun retire() {
        retired = true
        // Coil shutdown cancels loader work but leaves DiskCache open. Close aborts
        // editors, preventing cancelled or delayed fetches from repopulating it.
        val failures = mutableListOf<Throwable>()
        listOf<() -> Unit>(loader::shutdown, client::close, cache::clear, cache::shutdown).forEach { operation ->
            try {
                operation()
            } catch (failure: Exception) {
                appendCleanupFailures(failures, failure)
            }
        }
        throwIfCleanupFailed(failures)
    }

    private fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
