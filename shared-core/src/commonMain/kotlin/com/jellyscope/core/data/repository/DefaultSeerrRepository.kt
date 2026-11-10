// SPDX-License-Identifier: MPL-2.0

@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.jellyscope.core.data.repository

import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.data.local.AccountWorkLease
import com.jellyscope.core.data.local.RefetchableServerCache
import com.jellyscope.core.data.local.SeerrSessionStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.StoredSeerrSession
import com.jellyscope.core.data.remote.SeerrApi
import com.jellyscope.core.data.remote.SeerrApiResponse
import com.jellyscope.core.data.remote.SeerrTransportSession
import com.jellyscope.core.data.remote.SeerrWireDetail
import com.jellyscope.core.data.remote.SeerrWireQuickConnect
import com.jellyscope.core.data.remote.SeerrWireUser
import com.jellyscope.core.data.remote.normalizeSeerrEndpoint
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.SeerrAvailability
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrCatalogPage
import com.jellyscope.core.domain.model.SeerrConnection
import com.jellyscope.core.domain.model.SeerrConnectionPhase
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrMediaType
import com.jellyscope.core.domain.model.SeerrOwner
import com.jellyscope.core.domain.model.SeerrPerson
import com.jellyscope.core.domain.model.SeerrPublicationToken
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuickConnect
import com.jellyscope.core.domain.model.SeerrQuota
import com.jellyscope.core.domain.model.SeerrQuotaKind
import com.jellyscope.core.domain.model.SeerrQuotas
import com.jellyscope.core.domain.model.SeerrRequestCreated
import com.jellyscope.core.domain.model.SeerrRequestIntent
import com.jellyscope.core.domain.model.SeerrRequestOption
import com.jellyscope.core.domain.model.SeerrRequestStatus
import com.jellyscope.core.domain.model.SeerrRequestsPage
import com.jellyscope.core.domain.model.SeerrResult
import com.jellyscope.core.domain.model.SeerrServiceInfo
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** App scoped; register once. No owned coroutine scope or background polling job. */
@HiddenFromObjC
class DefaultSeerrRepository(
    private val api: SeerrApi,
    private val store: SeerrSessionStore,
    private val sessions: SessionRepository,
    private val registry: ServerScopedStoreRegistry,
    private val mediaRepository: MediaRepository,
    private val workDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : SeerrRepository,
    RefetchableServerCache,
    AccountScopedClearableStore {
    private val mutex = Mutex()
    private var generation = 0L
    private var active: Connection? = null

    init {
        registry.register(this)
    }

    override suspend fun connection(owner: SeerrOwner): SeerrResult<SeerrConnection> = loadConnection(owner, forceVerify = false)

    override suspend fun restore(owner: SeerrOwner): SeerrResult<SeerrConnection> = loadConnection(owner, forceVerify = true)

    private suspend fun loadConnection(
        owner: SeerrOwner,
        forceVerify: Boolean,
    ): SeerrResult<SeerrConnection> =
        withContext(workDispatcher) {
            val lease = registry.acquireWorkLease(owner.account, owner.boundaryEpoch) ?: return@withContext stale()
            try {
                var loadedSavedConnection = false
                val connection =
                    registry.withGuardedLease(lease) {
                        mutex.withLock {
                            checkOwner(owner, catalog = false)
                            val existing = active?.takeIf { it.token.owner == owner }
                            if (existing != null) return@withLock existing
                            val saved = persistence { store.read(owner.account) }
                            loadedSavedConnection = saved != null
                            replace(owner, saved?.endpoint).also { created ->
                                if (saved != null) {
                                    created.transport = SeerrTransportSession(saved.endpoint, saved.cookies)
                                    created.loginMethod = SeerrLoginMethod.valueOf(saved.loginMethod)
                                    created.expectedUserId = saved.seerrUserId
                                }
                            }
                        }
                    } ?: return@withContext stale()
                if ((!forceVerify && !loadedSavedConnection) ||
                    connection.transport == null ||
                    (connection.expectedUserId == null && !connection.verified)
                ) {
                    return@withContext registry.withGuardedLease(lease) {
                        mutex.withLock {
                            checkToken(connection.token, catalog = false)
                            SeerrResult.Success(connection.publicValue(), connection.token)
                        }
                    } ?: stale()
                }
                operation(connection.token, catalog = false, authenticated = false) { work ->
                    val needsVerification = guarded(work) { forceVerify || !connection.verified }
                    if (needsVerification) {
                        bootstrap(work)
                        verifyUser(work)
                    }
                    guarded(work) { connection.publicValue() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OperationFailure) {
                failedResult(owner, failure)
            } catch (failure: Throwable) {
                logger.w {
                    formatSafeFailureDiagnostic("seerr-connection", "load-failed", failure) +
                        " reason=PersistenceFailed"
                }
                failedResult(owner, OperationFailure(SeerrError.PersistenceFailed))
            }
        }

    override suspend fun validateService(
        owner: SeerrOwner,
        url: String,
    ): SeerrResult<SeerrServiceInfo> =
        withContext(workDispatcher) {
            val endpoint =
                try {
                    normalizeSeerrEndpoint(url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.w {
                        formatSafeFailureDiagnostic("seerr-connection", "validation-failed", failure) +
                            " reason=InvalidUrl"
                    }
                    return@withContext failedResult(owner, OperationFailure(SeerrError.InvalidUrl))
                }
            val lease = registry.acquireWorkLease(owner.account, owner.boundaryEpoch) ?: return@withContext stale()
            try {
                val connection =
                    registry.withGuardedLease(lease) {
                        mutex.withLock {
                            checkOwner(owner, catalog = false)
                            currentCoroutineContext().ensureActive()
                            withContext(NonCancellable) {
                                val created = replace(owner, endpoint)
                                clearSavedConnection(created)
                                created
                            }
                        }
                    } ?: return@withContext stale()
                try {
                    operation(connection.token, catalog = false, authenticated = false) { bootstrap(it) }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { retireAuthentication(connection.token) }
                    throw cancelled
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OperationFailure) {
                failedResult(owner, failure)
            }
        }

    override suspend fun connect(
        token: SeerrPublicationToken,
        method: SeerrLoginMethod,
        identifier: String,
        password: String,
    ): SeerrResult<SeerrConnection> =
        withContext(workDispatcher) {
            val authToken =
                when (val started = startAuthentication(token, method)) {
                    is SeerrResult.Failure -> return@withContext started
                    is SeerrResult.Success -> started.token
                }
            var finished = false
            try {
                val result =
                    operation(authToken, catalog = false, authenticated = false) { work ->
                        val service = bootstrap(work)
                        if ((method == SeerrLoginMethod.Local && !service.localLoginEnabled) ||
                            (method == SeerrLoginMethod.Jellyfin && !service.jellyfinLoginEnabled)
                        ) {
                            abort(SeerrError.UnsupportedService)
                        }
                        if (identifier.isBlank() || identifier.length > 512 || password.length > 4096) abort(SeerrError.InvalidCredentials)
                        if (method == SeerrLoginMethod.Local && password.isEmpty()) abort(SeerrError.InvalidCredentials)
                        exchange(work, authenticated = false) { api.login(it, method, identifier.trim(), password) }
                        verifyUser(work)
                        guarded(work) { work.connection.publicValue() }
                    }
                val settled = settleAuthentication(result, authToken)
                finished = true
                settled
            } finally {
                if (!finished) withContext(NonCancellable) { retireAuthentication(authToken) }
            }
        }

    override suspend fun beginQuickConnect(token: SeerrPublicationToken): SeerrResult<SeerrQuickConnect> =
        withContext(workDispatcher) {
            val authToken =
                when (val started = startAuthentication(token, SeerrLoginMethod.Jellyfin)) {
                    is SeerrResult.Failure -> return@withContext started
                    is SeerrResult.Success -> started.token
                }
            var finished = false
            try {
                val result =
                    operation(authToken, catalog = false, authenticated = false) { work ->
                        if (!bootstrap(work).quickConnectSupported) abort(SeerrError.QuickConnectUnavailable)
                        val code =
                            try {
                                exchange(work, authenticated = false) { api.beginQuickConnect(it) }
                            } catch (failure: OperationFailure) {
                                if (failure.error in setOf(SeerrError.InvalidCredentials, SeerrError.MetadataUnavailable)) {
                                    abort(SeerrError.QuickConnectUnavailable)
                                }
                                throw failure
                            }
                        guarded(work) {
                            work.connection.quickConnect = code
                            work.connection.quickConnectExpires = Clock.System.now().toEpochMilliseconds() + 5.minutes.inWholeMilliseconds
                            SeerrQuickConnect(code.code, work.connection.quickConnectExpires)
                        }
                    }
                val settled = settleAuthentication(result, authToken)
                finished = true
                settled
            } finally {
                if (!finished) withContext(NonCancellable) { retireAuthentication(authToken) }
            }
        }

    override suspend fun completeQuickConnect(token: SeerrPublicationToken): SeerrResult<SeerrConnection> =
        withContext(workDispatcher) {
            var finished = false
            try {
                val result =
                    operation(token, catalog = false, authenticated = false) { work ->
                        if (guarded(work) { work.connection.verified }) {
                            return@operation guarded(work) { work.connection.publicValue() }
                        }
                        repeat(150) {
                            val code =
                                guarded(work) {
                                    if (Clock.System.now().toEpochMilliseconds() >=
                                        work.connection.quickConnectExpires
                                    ) {
                                        abort(SeerrError.QuickConnectExpired)
                                    }
                                    work.connection.quickConnect ?: abort(SeerrError.QuickConnectExpired)
                                }
                            val authorized =
                                try {
                                    exchange(work, authenticated = false) { api.checkQuickConnect(it, code.secret) }
                                } catch (failure: OperationFailure) {
                                    if (failure.error == SeerrError.MetadataUnavailable) abort(SeerrError.QuickConnectExpired)
                                    if (failure.error == SeerrError.PermissionDenied) abort(SeerrError.QuickConnectUnavailable)
                                    throw failure
                                }
                            if (authorized) {
                                guarded(work) {
                                    if (Clock.System.now().toEpochMilliseconds() >= work.connection.quickConnectExpires) {
                                        abort(SeerrError.QuickConnectExpired)
                                    }
                                }
                                exchange(work, authenticated = false) { api.completeQuickConnect(it, code.secret) }
                                verifyUser(work)
                                return@operation guarded(work) {
                                    work.connection.quickConnect = null
                                    work.connection.publicValue()
                                }
                            }
                            delay(2_000)
                        }
                        abort(SeerrError.QuickConnectExpired)
                    }
                val settled = settleAuthentication(result, token)
                finished = true
                settled
            } finally {
                if (!finished) withContext(NonCancellable) { retireAuthentication(token) }
            }
        }

    override suspend fun cancelAuthentication(token: SeerrPublicationToken): SeerrResult<SeerrConnection> =
        withContext(workDispatcher) { retireAuthentication(token) }

    private suspend fun <T> settleAuthentication(
        result: SeerrResult<T>,
        token: SeerrPublicationToken,
    ): SeerrResult<T> {
        if (result !is SeerrResult.Failure) return result
        val retired = withContext(NonCancellable) { retireAuthentication(token, includeVerified = false) }
        return if (retired is SeerrResult.Success && result.error != SeerrError.StaleOwner) {
            SeerrResult.Failure(result.error, retired.token, retired.value)
        } else if (retired is SeerrResult.Failure && retired.error == SeerrError.PersistenceFailed) {
            retired
        } else {
            result
        }
    }

    private suspend fun retireAuthentication(
        token: SeerrPublicationToken,
        includeVerified: Boolean = true,
    ): SeerrResult<SeerrConnection> {
        val lease = registry.acquireWorkLease(token.owner.account, token.owner.boundaryEpoch) ?: return stale()
        return try {
            registry.withGuardedLease(lease) {
                mutex.withLock {
                    checkOwner(token.owner, catalog = false)
                    val connection = active ?: abort(SeerrError.StaleOwner)
                    if (connection.token != token && connection.authenticationParent != token) {
                        abort(SeerrError.StaleOwner)
                    }
                    if (connection.verified && (!includeVerified || connection.authenticationParent == null)) {
                        return@withLock SeerrResult.Success(connection.publicValue(), connection.token)
                    }
                    withContext(NonCancellable) {
                        val retired = replace(token.owner, connection.transport?.endpoint)
                        retired.service = connection.service
                        clearSavedConnection(retired)
                        SeerrResult.Success(retired.publicValue(), retired.token)
                    }
                }
            } ?: stale()
        } catch (failure: OperationFailure) {
            failedResult(token.owner, failure)
        }
    }

    private suspend fun startAuthentication(
        token: SeerrPublicationToken,
        method: SeerrLoginMethod,
    ): SeerrResult<SeerrConnection> {
        val lease = registry.acquireWorkLease(token.owner.account, token.owner.boundaryEpoch) ?: return stale()
        return try {
            registry.withGuardedLease(lease) {
                mutex.withLock {
                    checkToken(token, catalog = false)
                    val previous = requireNotNull(active)
                    val endpoint = previous.transport?.endpoint ?: abort(SeerrError.AuthenticationRequired)
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        val created = replace(token.owner, endpoint)
                        created.loginMethod = method
                        created.authenticationParent = token
                        clearSavedConnection(created)
                        SeerrResult.Success(created.publicValue(), created.token)
                    }
                }
            } ?: stale()
        } catch (failure: OperationFailure) {
            failedResult(token.owner, failure)
        }
    }

    override suspend fun disconnect(owner: SeerrOwner): SeerrResult<SeerrConnection> =
        withContext(workDispatcher) {
            val lease = registry.acquireWorkLease(owner.account, owner.boundaryEpoch) ?: return@withContext stale()
            var logoutSession: SeerrTransportSession? = null
            try {
                val disconnected =
                    registry.withGuardedLease(lease) {
                        mutex.withLock {
                            checkOwner(owner, catalog = false)
                            currentCoroutineContext().ensureActive()
                            withContext(NonCancellable) {
                                logoutSession = active?.takeIf { it.token.owner == owner }?.transport
                                // Durable local isolation settles before best-effort remote logout.
                                val created = replace(owner, null)
                                clearSavedConnection(created)
                                SeerrResult.Success(created.publicValue(), created.token)
                            }
                        }
                    } ?: return@withContext stale()
                logger.i { "stage=seerr-connection event=disconnected" }
                logoutSession?.let { saved ->
                    withTimeoutOrNull(3_000) {
                        if (isCurrent(disconnected.token, catalog = false)) {
                            val bootstrap = api.publicSettings(saved, "")
                            if (bootstrap.error == null && isCurrent(disconnected.token, catalog = false)) {
                                api.logout(saved.copy(cookies = bootstrap.cookies))
                            }
                        }
                    }
                }
                operation(disconnected.token, catalog = false, authenticated = false) { work ->
                    guarded(work) { work.connection.publicValue() }
                }
            } catch (failure: OperationFailure) {
                failedResult(owner, failure)
            }
        }

    override suspend fun catalog(
        token: SeerrPublicationToken,
        catalog: SeerrCatalog,
        page: Int,
        query: String,
    ): SeerrResult<SeerrCatalogPage> =
        operation(token) { work ->
            if (page <= 0 || query.length > 300 || (catalog == SeerrCatalog.Search && query.isBlank())) abort(SeerrError.InvalidSelection)
            exchange(work) { api.catalog(it, catalog, page, query.trim()) }
        }

    override suspend fun similar(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
    ): SeerrResult<SeerrCatalogPage> = operation(token) { work -> exchange(work) { api.similar(it, key) } }

    override suspend fun detail(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
    ): SeerrResult<SeerrDetail> = operation(token) { loadDetail(it, key) }

    override suspend fun person(
        token: SeerrPublicationToken,
        personId: Int,
    ): SeerrResult<SeerrPerson> =
        operation(token) { work ->
            if (personId <= 0) abort(SeerrError.InvalidSelection)
            val person = exchange(work) { api.person(it, personId) }
            val credits = exchange(work) { api.personCredits(it, personId) }
            person.copy(
                movies = credits.filter { it.key.type == SeerrMediaType.Movie },
                series = credits.filter { it.key.type == SeerrMediaType.Tv },
            )
        }

    override suspend fun quota(token: SeerrPublicationToken): SeerrResult<SeerrQuotas> =
        operation(token) { work ->
            val id = guarded(work) { requireNotNull(work.connection.user).user.id }
            exchange(work) { api.quota(it, id) }
        }

    override suspend fun requests(
        token: SeerrPublicationToken,
        skip: Int,
    ): SeerrResult<SeerrRequestsPage> =
        operation(token) { work ->
            if (skip < 0 || skip > Int.MAX_VALUE - 20) abort(SeerrError.InvalidSelection)
            val id = guarded(work) { requireNotNull(work.connection.user).user.id }
            val page = exchange(work) { api.requests(it, id, skip) }
            // Hydrate each distinct key serially; transport caps the raw page at 20 entries.
            // Optional metadata failures retain actionable identity and status.
            val hydrated = mutableMapOf<SeerrMediaKey, SeerrWireDetail>()
            for (key in page.items.map { it.request.key }.distinct()) {
                try {
                    hydrated[key] = exchange(work) { api.detail(it, key) }
                } catch (failure: OperationFailure) {
                    if (failure.error == SeerrError.RateLimited) break
                    if (failure.error !in optionalReadFailures + SeerrError.PermissionDenied + SeerrError.Blocklisted) throw failure
                }
            }
            page.copy(
                items =
                    page.items.map { entry ->
                        hydrated[entry.request.key]?.let { entry.copy(media = it.media) } ?: entry
                    },
            )
        }

    override suspend fun request(
        token: SeerrPublicationToken,
        intent: SeerrRequestIntent,
    ): SeerrResult<SeerrRequestCreated> =
        withContext(workDispatcher) {
            val lease = registry.acquireWorkLease(token.owner.account, token.owner.boundaryEpoch) ?: return@withContext stale()
            var reserved: Connection? = null
            try {
                registry.withGuardedLease(lease) {
                    mutex.withLock {
                        checkToken(token, catalog = true)
                        val connection = requireNotNull(active)
                        if (connection.submitting) abort(SeerrError.RequestInProgress)
                        if (connection.uncertain != null) abort(SeerrError.ReconciliationRequired)
                        connection.submitting = true
                        reserved = connection
                    }
                } ?: return@withContext stale()
                val submitted = intent.copy(seasons = intent.seasons.toList())
                operation(token) { work ->
                    verifyUser(work)
                    bootstrap(work)
                    val detail = loadDetail(work, submitted.key)
                    val option = if (submitted.quality == SeerrQuality.FourK) detail.fourK else detail.standard
                    option.blockedBy?.let(::abort)
                    if (submitted.key.type == SeerrMediaType.Movie) {
                        if (submitted.seasons.isNotEmpty()) abort(SeerrError.InvalidSelection)
                    } else if (submitted.seasons.isEmpty() ||
                        submitted.seasons.distinct().size != submitted.seasons.size ||
                        submitted.seasons.any { it !in option.eligibleSeasonNumbers }
                    ) {
                        abort(SeerrError.InvalidSelection)
                    }
                    val units = if (submitted.key.type == SeerrMediaType.Movie) 1 else submitted.seasons.size
                    if (!detail.quota.allows(units)) abort(SeerrError.QuotaExceeded)
                    guarded(work) {
                        currentCoroutineContext().ensureActive()
                        work.connection.uncertain = submitted
                    }
                    try {
                        val created = exchange(work) { api.request(it, submitted) }
                        guarded(work) { work.connection.uncertain = null }
                        logger.i { "stage=seerr-request event=created" }
                        SeerrRequestCreated(created)
                    } catch (failure: OperationFailure) {
                        if (failure.error !in uncertainFailures) {
                            mutex.withLock { work.connection.uncertain = null }
                        }
                        if (failure.error == SeerrError.NoSeasonsAvailable) {
                            // Refresh admission after the explicit non-creation response;
                            // the caller renders the typed explanation and reloads detail.
                            try {
                                loadDetail(work, submitted.key)
                            } catch (refresh: OperationFailure) {
                                if (refresh.error !in optionalReadFailures) throw refresh
                            }
                        }
                        throw failure
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OperationFailure) {
                failedResult(token.owner, failure, token, catalog = true)
            } finally {
                // No boundary reentry is needed: this only releases the captured
                // retired-or-current connection's local submission reservation.
                withContext(NonCancellable) { mutex.withLock { reserved?.submitting = false } }
            }
        }

    override suspend fun reconcileRequest(token: SeerrPublicationToken): SeerrResult<SeerrDetail> =
        operation(token) { work ->
            val intent = guarded(work) { work.connection.uncertain ?: abort(SeerrError.InvalidSelection) }
            val refreshed = loadDetail(work, intent.key)
            val id = guarded(work) { requireNotNull(work.connection.user).user.id }
            exchange(work) { api.requests(it, id, 0) }
            guarded(work) { work.connection.uncertain = null }
            refreshed.copy(reconciliationRequired = false)
        }

    override suspend fun resolveLibraryItem(
        token: SeerrPublicationToken,
        key: SeerrMediaKey,
        quality: SeerrQuality,
    ): SeerrResult<SeerrLibraryItem> =
        operation(token) { work ->
            val detail =
                try {
                    exchange(work) { api.detail(it, key) }
                } catch (failure: OperationFailure) {
                    if (failure.error in setOf(SeerrError.InvalidResponse, SeerrError.MetadataUnavailable)) {
                        abort(SeerrError.LibraryItemUnavailable)
                    }
                    throw failure
                }
            val availability = if (quality == SeerrQuality.FourK) detail.media.fourKAvailability else detail.media.standardAvailability
            if (availability !in
                setOf(SeerrAvailability.Available, SeerrAvailability.PartiallyAvailable)
            ) {
                abort(SeerrError.LibraryItemUnavailable)
            }
            val candidate =
                (if (quality == SeerrQuality.FourK) detail.jellyfinMediaId4k else detail.jellyfinMediaId)
                    ?: abort(SeerrError.LibraryItemUnavailable)
            guarded(work) { Unit }
            val item = mediaRepository.getItemDetail(candidate).getOrNull() ?: abort(SeerrError.LibraryItemUnavailable)
            val kind = if (key.type == SeerrMediaType.Movie) MediaKind.Movie else MediaKind.Series
            if (item.item.id != candidate ||
                item.item.kind != kind ||
                item.tmdbId?.toIntOrNull() != key.tmdbId
            ) {
                abort(SeerrError.LibraryItemUnavailable)
            }
            guarded(work) { SeerrLibraryItem(item.item.id, kind, key) }
        }

    override suspend fun isCurrent(
        token: SeerrPublicationToken,
        catalog: Boolean,
    ): Boolean = publishIfCurrent(token, catalog) {}

    override suspend fun publishIfCurrent(
        token: SeerrPublicationToken,
        catalog: Boolean,
        publish: () -> Unit,
    ): Boolean {
        val lease = registry.acquireWorkLease(token.owner.account, token.owner.boundaryEpoch) ?: return false
        return registry.withGuardedLease(lease) {
            mutex.withLock {
                try {
                    checkToken(token, catalog)
                } catch (_: OperationFailure) {
                    return@withLock false
                }
                publish()
                true
            }
        } ?: false
    }

    private suspend fun bootstrap(work: Work): SeerrServiceInfo {
        val version = exchange(work, authenticated = false) { api.status(it) }
        val settings = exchange(work, authenticated = false) { api.publicSettings(it, version) }
        guarded(work) { work.connection.service = settings }
        return settings
    }

    private suspend fun verifyUser(work: Work) {
        val user = exchange(work, authenticated = false) { api.me(it) }
        val matches = guarded(work) { userMatches(work.connection, user) }
        if (!matches) invalidateConnection(work, SeerrError.AccountMismatch)
        guarded(work) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val connection = work.connection
                connection.user = user
                connection.expectedUserId = user.user.id
                connection.verified = true
                connection.quickConnect = null
                try {
                    persist(connection)
                } catch (failure: OperationFailure) {
                    connection.verified = false
                    throw failure
                }
            }
        }
        logger.i { "stage=seerr-auth event=verified" }
    }

    private fun userMatches(
        connection: Connection,
        user: SeerrWireUser,
    ): Boolean {
        val jellyfinUserId = user.jellyfinUserId
        return !(
            connection.expectedUserId?.let { it != user.user.id } == true ||
                (
                    connection.loginMethod != SeerrLoginMethod.Local &&
                        jellyfinUserId != null &&
                        normalizedUserId(jellyfinUserId) != normalizedUserId(connection.token.owner.account.userId)
                )
        )
    }

    private suspend fun loadDetail(
        work: Work,
        key: SeerrMediaKey,
    ): SeerrDetail {
        val wire = exchange(work) { api.detail(it, key) }
        val userId = guarded(work) { requireNotNull(work.connection.user).user.id }
        val quotas =
            try {
                exchange(work) { api.quota(it, userId) }
            } catch (failure: OperationFailure) {
                if (failure.error !in optionalReadFailures + SeerrError.PermissionDenied + SeerrError.QuotaExceeded) throw failure
                SeerrQuotas(unavailableQuota, unavailableQuota)
            }
        return guarded(work) {
            val connection = work.connection
            val quota = if (key.type == SeerrMediaType.Movie) quotas.movie else quotas.tv
            SeerrDetail(
                wire.media,
                wire.overview,
                wire.backdropUrl,
                wire.seasons,
                wire.requests,
                quota,
                requestOption(connection, wire, quota, SeerrQuality.Standard),
                requestOption(connection, wire, quota, SeerrQuality.FourK),
                connection.uncertain != null,
                cast = wire.cast,
            )
        }
    }

    private fun requestOption(
        connection: Connection,
        wire: SeerrWireDetail,
        quota: SeerrQuota,
        quality: SeerrQuality,
    ): SeerrRequestOption {
        val type = wire.media.key.type
        val permission = requireNotNull(connection.user).user.permissions
        val availability = if (quality == SeerrQuality.FourK) wire.media.fourKAvailability else wire.media.standardAvailability
        val blockers =
            wire.requests.filter {
                it.quality == quality && it.status !in setOf(SeerrRequestStatus.Declined, SeerrRequestStatus.Completed)
            }
        val eligible =
            wire.seasons
                .filter { season ->
                    val status = if (quality == SeerrQuality.FourK) season.fourKAvailability else season.standardAvailability
                    season.episodeCount > 0 &&
                        (season.number > 0 || connection.service?.specialEpisodesEnabled == true) &&
                        status in requestableAvailability &&
                        blockers.none { season.number in it.seasons }
                }.map { it.number }
        val blocked =
            when {
                !permission.canRequest(type, quality) -> SeerrError.PermissionDenied
                quality == SeerrQuality.FourK &&
                    (
                        if (type ==
                            SeerrMediaType.Movie
                        ) {
                            connection.service?.movie4kEnabled
                        } else {
                            connection.service?.series4kEnabled
                        }
                    ) != true ->
                    SeerrError.PermissionDenied
                !wire.requestMetadataComplete || wire.media.title.isNullOrBlank() || availability == SeerrAvailability.Unrecognized ->
                    SeerrError.MetadataUnavailable
                availability == SeerrAvailability.Blocklisted || wire.media.standardAvailability == SeerrAvailability.Blocklisted ->
                    SeerrError.Blocklisted
                availability == SeerrAvailability.Available -> SeerrError.AlreadyAvailable
                type == SeerrMediaType.Movie && (blockers.isNotEmpty() || availability !in requestableAvailability) ->
                    SeerrError.AlreadyRequested
                type == SeerrMediaType.Tv && eligible.isEmpty() -> SeerrError.NoSeasonsAvailable
                quota.kind == SeerrQuotaKind.Unavailable -> SeerrError.QuotaUnavailable
                !quota.allows(1) -> SeerrError.QuotaExceeded
                else -> null
            }
        return SeerrRequestOption(quality, eligible, blocked, permission.requiresApproval(type, quality))
    }

    /** Serializes cookie rotation for one connection, never the account boundary across IO. */
    private suspend fun <T> operation(
        token: SeerrPublicationToken,
        catalog: Boolean = true,
        authenticated: Boolean = true,
        block: suspend (Work) -> T,
    ): SeerrResult<T> =
        withContext(workDispatcher) {
            val lease = registry.acquireWorkLease(token.owner.account, token.owner.boundaryEpoch) ?: return@withContext stale()
            try {
                val connection =
                    registry.withGuardedLease(lease) {
                        mutex.withLock {
                            checkToken(token, catalog)
                            requireNotNull(active)
                        }
                    } ?: return@withContext stale()
                val work = Work(connection, lease, catalog)
                connection.requests.withLock {
                    guarded(work) {
                        if (authenticated && !connection.verified) abort(SeerrError.AuthenticationRequired)
                    }
                    val value = block(work)
                    guarded(work) { SeerrResult.Success(value, token) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OperationFailure) {
                failedResult(token.owner, failure, token, catalog)
            } catch (failure: Throwable) {
                logger.w {
                    formatSafeFailureDiagnostic("seerr-service", "operation-failed", failure) +
                        " reason=InvalidResponse"
                }
                failedResult(token.owner, OperationFailure(SeerrError.InvalidResponse), token, catalog)
            }
        }

    private suspend fun <T> exchange(
        work: Work,
        authenticated: Boolean = true,
        call: suspend (SeerrTransportSession) -> SeerrApiResponse<T>,
    ): T {
        val transport = guarded(work) { work.connection.transport ?: abort(SeerrError.AuthenticationRequired) }
        currentCoroutineContext().ensureActive()
        val response = call(transport)
        guarded(work) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                work.connection.transport = transport.copy(cookies = response.cookies)
                if (work.connection.verified && response.error != SeerrError.SessionExpired) persist(work.connection)
            }
        }
        if (response.error == SeerrError.SessionExpired) invalidateConnection(work, SeerrError.SessionExpired)
        if (authenticated && response.httpStatus == 403 && response.error != SeerrError.SecurityRejected) {
            // One probe, no recursion and no replay of the original operation.
            val probeTransport = guarded(work) { requireNotNull(work.connection.transport) }
            val probe = api.me(probeTransport)
            guarded(work) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    work.connection.transport = probeTransport.copy(cookies = probe.cookies)
                    if (work.connection.verified && probe.error != SeerrError.SessionExpired) persist(work.connection)
                }
            }
            if (probe.error == SeerrError.SessionExpired) invalidateConnection(work, SeerrError.SessionExpired)
            val user = probe.value ?: abort(SeerrError.VerificationUnavailable)
            if (!guarded(work) { userMatches(work.connection, user) }) invalidateConnection(work, SeerrError.AccountMismatch)
            guarded(work) {
                work.connection.user = user
            }
        }
        response.error?.let(::abort)
        return response.value ?: abort(SeerrError.InvalidResponse)
    }

    private suspend fun invalidateConnection(
        work: Work,
        error: SeerrError,
    ): Nothing {
        val next =
            guarded(work) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val endpoint = work.connection.transport?.endpoint
                    val created = replace(work.connection.token.owner, endpoint)
                    clearSavedConnection(created)
                    created.token
                }
            }
        logger.i { "stage=seerr-auth event=expired" }
        throw OperationFailure(error, next)
    }

    private suspend fun <T> guarded(
        work: Work,
        block: suspend () -> T,
    ): T =
        registry.withGuardedLease(work.lease) {
            mutex.withLock {
                checkToken(work.connection.token, work.catalog)
                block()
            }
        } ?: abort(SeerrError.StaleOwner)

    private suspend fun persist(connection: Connection) {
        val transport = requireNotNull(connection.transport)
        persistence {
            store.writeVerified(
                connection.token.owner.account,
                StoredSeerrSession(
                    transport.endpoint,
                    requireNotNull(connection.user).user.id,
                    requireNotNull(connection.loginMethod).name,
                    transport.cookies,
                ),
            )
        }
    }

    private suspend fun clearSavedConnection(connection: Connection) {
        try {
            persistence { store.clearAccount(connection.token.owner.account) }
        } catch (failure: OperationFailure) {
            throw OperationFailure(failure.error, connection.token)
        }
    }

    private suspend fun <T> persistence(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logger.w {
                formatSafeFailureDiagnostic("seerr-persistence", "failed", failure) +
                    " reason=PersistenceFailed"
            }
            abort(SeerrError.PersistenceFailed)
        }

    private fun checkOwner(
        owner: SeerrOwner,
        catalog: Boolean,
    ) {
        val current = sessions.sessionState.value as? SessionState.LoggedIn ?: abort(SeerrError.StaleOwner)
        if (current.boundaryEpoch != owner.boundaryEpoch || current.session.accountIdentity() != owner.account) abort(SeerrError.StaleOwner)
        if (catalog && current.session.maxParentalRating != null) abort(SeerrError.RestrictedAccount)
    }

    private fun checkToken(
        token: SeerrPublicationToken,
        catalog: Boolean,
    ) {
        checkOwner(token.owner, catalog = false)
        if (active?.token != token) abort(SeerrError.StaleOwner)
        checkOwner(token.owner, catalog)
    }

    private fun replace(
        owner: SeerrOwner,
        endpoint: String?,
    ): Connection {
        active?.erase()
        generation += 1
        return Connection(SeerrPublicationToken(owner, generation), endpoint?.let(::SeerrTransportSession)).also { active = it }
    }

    private suspend fun failedResult(
        owner: SeerrOwner,
        failure: OperationFailure,
        expected: SeerrPublicationToken? = null,
        catalog: Boolean = false,
    ): SeerrResult.Failure {
        if (failure.error == SeerrError.StaleOwner) return stale()
        val lease = registry.acquireWorkLease(owner.account, owner.boundaryEpoch) ?: return stale()
        return registry.withGuardedLease(lease) {
            mutex.withLock {
                try {
                    checkOwner(owner, catalog = false)
                    val token = failure.replacementToken ?: expected
                    if (token != null && active?.token != token) return@withLock stale()
                    checkOwner(owner, catalog)
                    logger.w { "stage=seerr-service event=failed reason=${failure.error.name}" }
                    val current = active?.takeIf { it.token.owner == owner }
                    SeerrResult.Failure(failure.error, current?.token, current?.publicValue())
                } catch (policy: OperationFailure) {
                    val current = active?.takeIf { it.token.owner == owner }
                    SeerrResult.Failure(policy.error, current?.token, current?.publicValue())
                }
            }
        } ?: stale()
    }

    // Registry invokes these with its gate already held. Never reacquire it here.
    override suspend fun clearServerScoped() =
        mutex.withLock {
            active?.erase()
            active = null
            generation += 1
        }

    override suspend fun clearServerScoped(serverId: String) =
        mutex.withLock {
            if (active
                    ?.token
                    ?.owner
                    ?.account
                    ?.serverId == serverId
            ) {
                active?.erase()
                active = null
                generation += 1
            }
        }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) =
        mutex.withLock {
            if (active?.token?.owner?.account == accountIdentity) {
                active?.erase()
                active = null
                generation += 1
            }
        }

    private class Connection(
        val token: SeerrPublicationToken,
        var transport: SeerrTransportSession?,
    ) {
        val requests = Mutex()
        var service: SeerrServiceInfo? = null
        var user: SeerrWireUser? = null
        var expectedUserId: Int? = null
        var loginMethod: SeerrLoginMethod? = null
        var verified = false
        var authenticationParent: SeerrPublicationToken? = null
        var quickConnect: SeerrWireQuickConnect? = null
        var quickConnectExpires = 0L
        var submitting = false
        var uncertain: SeerrRequestIntent? = null

        fun publicValue(): SeerrConnection =
            SeerrConnection(
                when {
                    transport == null -> SeerrConnectionPhase.Disconnected
                    verified -> SeerrConnectionPhase.Connected
                    expectedUserId != null -> SeerrConnectionPhase.VerificationRequired
                    else -> SeerrConnectionPhase.AwaitingLogin
                },
                transport?.endpoint,
                user?.user,
                service,
                loginMethod,
                uncertain,
            )

        fun erase() {
            transport = null
            user = null
            quickConnect = null
            uncertain = null
            verified = false
        }
    }

    private class Work(
        val connection: Connection,
        val lease: AccountWorkLease,
        val catalog: Boolean,
    )

    private class OperationFailure(
        val error: SeerrError,
        val replacementToken: SeerrPublicationToken? = null,
    ) : Exception()

    private fun abort(error: SeerrError): Nothing = throw OperationFailure(error)

    private fun stale(): SeerrResult.Failure = SeerrResult.Failure(SeerrError.StaleOwner)
}

private fun normalizedUserId(value: String): String = value.replace("-", "").lowercase()

private val unavailableQuota = SeerrQuota(SeerrQuotaKind.Unavailable)
private val requestableAvailability = setOf(SeerrAvailability.Unknown, SeerrAvailability.Deleted)
private val optionalReadFailures =
    setOf(SeerrError.NetworkUnavailable, SeerrError.InvalidResponse, SeerrError.MetadataUnavailable, SeerrError.RateLimited)
private val uncertainFailures =
    setOf(
        SeerrError.ReconciliationRequired,
        SeerrError.VerificationUnavailable,
        SeerrError.PersistenceFailed,
        SeerrError.RestrictedAccount,
        SeerrError.StaleOwner,
    )
private val logger = diagnosticLogger(DiagnosticTag.MediaRepository)
