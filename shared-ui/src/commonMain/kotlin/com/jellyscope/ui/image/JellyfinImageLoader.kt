// SPDX-License-Identifier: MPL-2.0

@file:OptIn(coil3.annotation.DelicateCoilApi::class)

package com.jellyscope.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.data.local.RefetchableServerCache
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.domain.model.AccountIdentity
import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A grown library scrolls hundreds of posters through the caches; tuned for
// the weakest target (Fire TV Cube gen1, 2GB):
// - memory cache capped below Coil's default so poster bitmaps never crowd
//   the small heap,
// - decode parallelism capped so a fast fling can't queue a decode storm
//   that starves composition frames.
private const val IMAGE_MEMORY_CACHE_PERCENT = 0.15
private const val IMAGE_DECODE_PARALLELISM = 3

@Composable
fun InstallJellyfinImageLoader(
    httpClient: HttpClient,
    serverScopedStoreRegistry: ServerScopedStoreRegistry,
    accountIdentity: AccountIdentity,
    boundaryEpoch: Long,
) {
    val context = LocalPlatformContext.current
    val retiredBoundary = CoilImageCacheRegistration.isRetired(accountIdentity, boundaryEpoch)
    val imageLoader =
        remember(context, httpClient, accountIdentity, boundaryEpoch, retiredBoundary) {
            CoilImageCacheRegistration.activeLoader(accountIdentity, boundaryEpoch)
                ?: if (retiredBoundary) {
                    null
                } else {
                    @Suppress("OPT_IN_USAGE")
                    ImageLoader
                        .Builder(context)
                        .components {
                            add(KtorNetworkFetcherFactory(httpClient))
                        }.memoryCache {
                            MemoryCache
                                .Builder()
                                .maxSizePercent(context, percent = IMAGE_MEMORY_CACHE_PERCENT)
                                .build()
                        }.decoderCoroutineContext(Dispatchers.Default.limitedParallelism(IMAGE_DECODE_PARALLELISM))
                        .applyPlatformImageTuning()
                        .crossfade(true)
                        .build()
                }
        }

    // This installer is composed before the logged-in navigation subtree, so
    // replace Coil's singleton before any sibling AsyncImage captures it.
    if (imageLoader != null) {
        remember(imageLoader, serverScopedStoreRegistry, accountIdentity, boundaryEpoch) {
            CoilImageCacheRegistration.activate(
                imageLoader = imageLoader,
                serverScopedStoreRegistry = serverScopedStoreRegistry,
                accountIdentity = accountIdentity,
                boundaryEpoch = boundaryEpoch,
            )
        }
    }
}

private object CoilImageCacheRegistration {
    private var registeredRegistry: ServerScopedStoreRegistry? = null
    private val lifecycle =
        AccountImageLoaderLifecycle(
            ioDispatcher = platformIoDispatcher(),
            cleanupScope = CoroutineScope(SupervisorJob() + platformIoDispatcher()),
            installSingleton = SingletonImageLoader::setUnsafe,
            clear = { imageLoader ->
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()
            },
            shutdown = ImageLoader::shutdown,
        )

    fun activate(
        imageLoader: ImageLoader,
        serverScopedStoreRegistry: ServerScopedStoreRegistry,
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    ) {
        if (registeredRegistry !== serverScopedStoreRegistry) {
            serverScopedStoreRegistry.register(CoilImageCacheClearableStore())
            registeredRegistry = serverScopedStoreRegistry
        }
        lifecycle.activate(accountIdentity, boundaryEpoch, imageLoader)
    }

    fun activeLoader(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    ): ImageLoader? = lifecycle.activeLoader(accountIdentity, boundaryEpoch)

    fun isRetired(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    ): Boolean = lifecycle.isRetired(accountIdentity, boundaryEpoch)

    suspend fun retireActive() = lifecycle.retireActive()

    suspend fun retireActiveForServer(serverId: String) = lifecycle.retireActiveIf { account -> account.serverId == serverId }

    suspend fun retireActiveForAccount(accountIdentity: AccountIdentity) =
        lifecycle.retireActiveIf { account -> account == accountIdentity }
}

private class CoilImageCacheClearableStore :
    RefetchableServerCache,
    AccountScopedClearableStore {
    override suspend fun clearServerScoped() {
        CoilImageCacheRegistration.retireActive()
    }

    override suspend fun clearServerScoped(serverId: String) {
        CoilImageCacheRegistration.retireActiveForServer(serverId)
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        CoilImageCacheRegistration.retireActiveForAccount(accountIdentity)
    }
}

internal class AccountImageLoaderLifecycle<T : Any>(
    private val ioDispatcher: CoroutineDispatcher,
    private val cleanupScope: CoroutineScope,
    private val installSingleton: (T) -> Unit,
    private val clear: (T) -> Unit,
    private val shutdown: (T) -> Unit,
) {
    private val lock = ReentrantLock()
    private var state: ImageLoaderLifecycleState<T>? = null

    fun activeLoader(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    ): T? =
        lock.withLock {
            (state as? ImageLoaderLifecycleState.Active)
                ?.takeIf { current ->
                    current.accountIdentity == accountIdentity && current.boundaryEpoch == boundaryEpoch
                }?.imageLoader
        }

    fun isRetired(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    ): Boolean =
        lock.withLock {
            (state as? ImageLoaderLifecycleState.Retired)?.let { retired ->
                retired.accountIdentity == accountIdentity && retired.boundaryEpoch == boundaryEpoch
            } == true
        }

    fun activate(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
        imageLoader: T,
    ): Boolean {
        var disposeRejected = false
        val accepted =
            lock.withLock {
                val current = state
                if (
                    current is ImageLoaderLifecycleState.Retired &&
                    current.accountIdentity == accountIdentity &&
                    current.boundaryEpoch == boundaryEpoch
                ) {
                    disposeRejected = current.imageLoader !== imageLoader
                    false
                } else {
                    val currentActive = current as? ImageLoaderLifecycleState.Active
                    check(currentActive == null || currentActive.imageLoader === imageLoader) {
                        "The previous account image loader must be retired before replacement."
                    }
                    installSingleton(imageLoader)
                    state = ImageLoaderLifecycleState.Active(accountIdentity, boundaryEpoch, imageLoader)
                    true
                }
            }
        if (disposeRejected) {
            cleanupScope.launch { shutdownOnly(imageLoader) }
        }
        return accepted
    }

    suspend fun retireActive() {
        retireActiveIf { true }
    }

    suspend fun retireActiveIf(matches: (AccountIdentity) -> Boolean) {
        val retired =
            lock.withLock {
                val active = state as? ImageLoaderLifecycleState.Active ?: return@withLock null
                if (!matches(active.accountIdentity)) return@withLock null
                ImageLoaderLifecycleState
                    .Retired(active.accountIdentity, active.boundaryEpoch, active.imageLoader)
                    .also { retired -> state = retired }
            } ?: return
        dispose(retired.imageLoader)
    }

    private suspend fun dispose(imageLoader: T) {
        withContext(ioDispatcher) {
            clear(imageLoader)
            shutdown(imageLoader)
        }
    }

    private suspend fun shutdownOnly(imageLoader: T) {
        withContext(ioDispatcher) {
            shutdown(imageLoader)
        }
    }
}

private sealed interface ImageLoaderLifecycleState<T : Any> {
    val accountIdentity: AccountIdentity
    val boundaryEpoch: Long
    val imageLoader: T

    data class Active<T : Any>(
        override val accountIdentity: AccountIdentity,
        override val boundaryEpoch: Long,
        override val imageLoader: T,
    ) : ImageLoaderLifecycleState<T>

    data class Retired<T : Any>(
        override val accountIdentity: AccountIdentity,
        override val boundaryEpoch: Long,
        override val imageLoader: T,
    ) : ImageLoaderLifecycleState<T>
}
