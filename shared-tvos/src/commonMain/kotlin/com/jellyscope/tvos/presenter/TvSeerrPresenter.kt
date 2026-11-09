// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.BeginSeerrQuickConnectAction
import com.jellyscope.core.domain.action.CancelSeerrAuthenticationAction
import com.jellyscope.core.domain.action.CompleteSeerrQuickConnectAction
import com.jellyscope.core.domain.action.ConnectSeerrAction
import com.jellyscope.core.domain.action.DisconnectSeerrAction
import com.jellyscope.core.domain.action.ReconcileSeerrRequestAction
import com.jellyscope.core.domain.action.RequestSeerrMediaAction
import com.jellyscope.core.domain.action.RestoreSeerrConnectionAction
import com.jellyscope.core.domain.action.ValidateSeerrServiceAction
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrConnection
import com.jellyscope.core.domain.model.SeerrConnectionPhase
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.SeerrMediaCard
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrOwner
import com.jellyscope.core.domain.model.SeerrPublicationToken
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrRequestIntent
import com.jellyscope.core.domain.model.SeerrRequestSummary
import com.jellyscope.core.domain.model.SeerrResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.model.isFullyAvailable
import com.jellyscope.core.domain.usecase.GetSeerrCatalogUseCase
import com.jellyscope.core.domain.usecase.GetSeerrConnectionUseCase
import com.jellyscope.core.domain.usecase.GetSeerrDetailUseCase
import com.jellyscope.core.domain.usecase.GetSeerrRequestsUseCase
import com.jellyscope.core.domain.usecase.GetSeerrSimilarUseCase
import com.jellyscope.core.domain.usecase.ObserveSessionStateUseCase
import com.jellyscope.core.domain.usecase.ResolveSeerrLibraryItemUseCase
import com.jellyscope.core.domain.usecase.ValidateSeerrPublicationUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvSeerrPresenter(
    session: Session,
    boundaryEpoch: Long,
    private val getConnection: GetSeerrConnectionUseCase,
    private val restoreConnection: RestoreSeerrConnectionAction,
    private val validateService: ValidateSeerrServiceAction,
    private val connect: ConnectSeerrAction,
    private val beginQuickConnect: BeginSeerrQuickConnectAction,
    private val completeQuickConnect: CompleteSeerrQuickConnectAction,
    private val cancelAuthentication: CancelSeerrAuthenticationAction,
    private val disconnect: DisconnectSeerrAction,
    private val getCatalog: GetSeerrCatalogUseCase,
    private val getRequests: GetSeerrRequestsUseCase,
    private val getDetail: GetSeerrDetailUseCase,
    private val getSimilar: GetSeerrSimilarUseCase,
    private val requestMedia: RequestSeerrMediaAction,
    private val reconcileRequest: ReconcileSeerrRequestAction,
    private val resolveLibraryItem: ResolveSeerrLibraryItemUseCase,
    private val validatePublication: ValidateSeerrPublicationUseCase,
    observeSessionState: ObserveSessionStateUseCase,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val owner = SeerrOwner(session.accountIdentity(), boundaryEpoch)
    private val sessions = observeSessionState()
    private val mutableState = MutableStateFlow(TvSeerrState(restricted = session.maxParentalRating != null))
    private val state: StateFlow<TvSeerrState> = mutableState.asStateFlow()
    private val effects = Channel<TvSeerrLibraryEffect>(Channel.BUFFERED)
    private var token: SeerrPublicationToken? = null
    private var connection: SeerrConnection? = null
    private var validatedEndpoint: String? = null
    private var authToken: SeerrPublicationToken? = null
    private var managementJob: Job? = null
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null
    private var mutation: TvSeerrMutation = TvSeerrMutation.None
    private var libraryJob: Job? = null
    private var managementGeneration = 0L
    private var listGeneration = 0L
    private var detailGeneration = 0L
    private var resolvingLibrary = false
    private var libraryGeneration = 0L
    private var effectSequence = 0L
    private var pendingLibrary: PendingSeerrLibrary? = null
    private var cards = emptyList<SeerrMediaCard>()
    private var requests = emptyList<SeerrRequestSummary>()

    private data class BrowseSnapshot(
        val browse: TvSeerrBrowseState,
        val cards: List<SeerrMediaCard>,
        val requests: List<SeerrRequestSummary>,
        val page: Int,
        val nextSkip: Int,
    )

    private val browseSnapshots = mutableMapOf<TvSeerrSection, BrowseSnapshot>()
    private var page = 0
    private var nextSkip = 0
    private var detail: SeerrDetail? = null
    private var detailAdmitted = false
    private var detailKey: SeerrMediaKey? = null
    private var quality = SeerrQuality.Standard
    private var selectedSeasons = emptySet<Int>()
    private var relatedJob: Job? = null
    private var relatedRevision = 0L
    private var entrySequence = 0L
    private var hideAvailable = true
    private val history = mutableListOf<SeerrDetailSnapshot>()
    private var visible = false
    private var closed = false

    init {
        scope.launch {
            sessions.collect { live ->
                val loggedIn = live as? SessionState.LoggedIn
                val matching =
                    loggedIn != null &&
                        loggedIn.session.accountIdentity() == owner.account &&
                        loggedIn.boundaryEpoch == owner.boundaryEpoch
                if (!matching) {
                    invalidateOwner()
                } else if (state.value.ownerValid) {
                    val restricted = loggedIn?.session?.maxParentalRating != null
                    if (restricted != state.value.restricted) {
                        clearContent()
                        reduce(TvSeerrChange.Policy(ownerValid = true, restricted = restricted))
                        if (!restricted) loadConnection(restore = false)
                    }
                }
            }
        }
        loadConnection(restore = false)
    }

    fun currentState(): TvSeerrState = state.value

    fun watchState(onChange: (TvSeerrState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun watchEffects(onEffect: (TvSeerrLibraryEffect) -> Unit): WatchHandle {
        val job = scope.launch { effects.receiveAsFlow().collect { onEffect(it) } }
        return WatchHandle { job.cancel() }
    }

    fun accept(intent: TvSeerrIntent) {
        if (closed || !state.value.ownerValid) return
        when (intent) {
            TvSeerrIntent.Visible -> {
                visible = true
                refreshRelatedAdmission()
                if (state.value.detail?.relatedPhase == TvSeerrRelatedPhase.NotAttempted) loadRelated()
            }
            TvSeerrIntent.ToggleHideAvailable -> {
                if (detailKey != null ||
                    state.value.section != TvSeerrSection.Discover ||
                    state.value.browse.catalog == SeerrCatalog.Search
                ) {
                    return
                }
                hideAvailable = !hideAvailable
                reprojectCards(resetFocus = true)
            }
            is TvSeerrIntent.OpenSimilar -> openSimilar(intent.id)
            TvSeerrIntent.RetrySimilar -> loadRelated()
            is TvSeerrIntent.RememberPosition ->
                state.value.detail?.let {
                    val next =
                        when (intent.section) {
                            "vertical" -> it.copy(verticalAnchor = intent.id)
                            "seasons" -> it.copy(seasonAnchor = intent.id)
                            "cast" -> it.copy(castAnchor = intent.id)
                            "similar" -> it.copy(similarAnchor = intent.id)
                            else -> return
                        }
                    reduce(TvSeerrChange.Detail(next))
                }
            is TvSeerrIntent.FocusDetail -> state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(focusedId = intent.id))) }

            is TvSeerrIntent.SelectSection -> selectSection(intent.section)
            is TvSeerrIntent.SelectCatalog -> selectCatalog(intent.catalog, "")
            is TvSeerrIntent.Search -> if (intent.query.isNotBlank()) selectCatalog(SeerrCatalog.Search, intent.query.trim())
            is TvSeerrIntent.QueryChanged -> {
                if (!canShowCatalog() ||
                    state.value.section != TvSeerrSection.Search ||
                    detailKey != null ||
                    mutation is TvSeerrMutation.Submitting
                ) {
                    return
                }
                listGeneration++
                listJob?.cancel()
                reduce(TvSeerrChange.Browse(state.value.browse.copy(query = intent.query, loading = false)))
            }
            TvSeerrIntent.ClearSearch -> selectCatalog(SeerrCatalog.Search, "")
            TvSeerrIntent.Refresh -> if (detailKey != null) loadDetail() else loadList(append = false)
            TvSeerrIntent.LoadMore -> loadList(append = true)
            is TvSeerrIntent.RememberBrowsePosition -> {
                if (canShowCatalog() && state.value.section == intent.section && detailKey == null) {
                    reduce(TvSeerrChange.Browse(state.value.browse.copy(verticalAnchor = intent.id)))
                }
            }
            is TvSeerrIntent.FocusCard -> reduce(TvSeerrChange.Browse(state.value.browse.copy(focusedCardId = intent.id)))
            is TvSeerrIntent.OpenDetail -> openDetail(intent.id)
            TvSeerrIntent.Back -> back()
            is TvSeerrIntent.SelectQuality -> selectQuality(intent.fourK)
            is TvSeerrIntent.ToggleSeason -> toggleSeason(intent.number)
            TvSeerrIntent.ConfirmRequest -> confirmRequest()
            TvSeerrIntent.SubmitRequest -> submitRequest()
            TvSeerrIntent.Reconcile -> reconcile()
            TvSeerrIntent.ViewInLibrary -> resolveLibrary()
            is TvSeerrIntent.UrlChanged -> if (intent.url != state.value.connection.url) cancelAuth(editedUrl = intent.url)
            is TvSeerrIntent.ValidateService -> validate(intent.url)
            is TvSeerrIntent.Login -> login(intent)
            is TvSeerrIntent.BeginQuickConnect -> quickConnect(intent.url)
            TvSeerrIntent.CancelAuthentication -> {
                if (state.value.section == TvSeerrSection.Connection) selectSection(TvSeerrSection.Discover) else cancelAuth()
            }
            TvSeerrIntent.RestoreConnection -> loadConnection(restore = true)
            TvSeerrIntent.Disconnect -> disconnectConnection()
            TvSeerrIntent.Hidden -> {
                visible = false
                cancelRelated()
                cancelLibrary()
                if (authToken != null) cancelAuth()
            }
        }
    }

    /** Call from Main. onNavigate must synchronously append the route, without dispatching another task. */
    fun consumeLibraryEffect(
        id: Long,
        onNavigate: (String) -> Unit,
    ) {
        val pending = pendingLibrary?.takeIf { it.id == id } ?: return
        pendingLibrary = null
        resolvingLibrary = true
        state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(resolvingLibrary = true, relatedEnabled = false))) }
        libraryJob =
            scope.launch {
                try {
                    validatePublication.publish(pending.token) {
                        if (canShowCatalog() &&
                            pending.generation == libraryGeneration &&
                            detailKey == pending.item.key &&
                            token == pending.token
                        ) {
                            onNavigate(pending.item.itemId)
                        }
                    }
                } finally {
                    if (pending.generation == libraryGeneration) {
                        resolvingLibrary = false
                        state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(resolvingLibrary = false))) }
                        refreshRelatedAdmission()
                    }
                }
            }
    }

    private fun reduce(change: TvSeerrChange) {
        mutableState.update { reduceTvSeerr(it, change) }
    }

    private fun setMutation(value: TvSeerrMutation) {
        mutation = value
        reduce(TvSeerrChange.Confirmation(value.confirmation))
        state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(relatedEnabled = relatedAllowed()))) }
    }

    private fun ownerMatches(): Boolean {
        val live = sessions.value as? SessionState.LoggedIn ?: return false
        return !closed &&
            state.value.ownerValid &&
            live.session.accountIdentity() == owner.account &&
            live.boundaryEpoch == owner.boundaryEpoch
    }

    private fun canShowCatalog(): Boolean =
        ownerMatches() &&
            (sessions.value as? SessionState.LoggedIn)?.session?.maxParentalRating == null &&
            !state.value.restricted

    private fun invalidateOwner() {
        if (!state.value.ownerValid) return
        managementGeneration += 1
        managementJob?.cancel()
        retireAuthOnClose()
        clearContent()
        token = null
        connection = null
        validatedEndpoint = null
        reduce(TvSeerrChange.Connection(TvSeerrConnectionState()))
        reduce(TvSeerrChange.Policy(ownerValid = false, restricted = true))
    }

    private fun cancelLibrary() {
        libraryGeneration += 1
        libraryJob?.cancel()
        resolvingLibrary = false
        pendingLibrary = null
        state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(resolvingLibrary = false))) }
        refreshRelatedAdmission()
    }

    private fun cancelReads() {
        cancelRelated()
        listGeneration += 1
        detailGeneration += 1
        listJob?.cancel()
        detailJob?.cancel()
        cancelLibrary()
    }

    private fun clearContent() {
        browseSnapshots.clear()
        history.clear()
        cancelReads()
        mutationJob?.cancel()
        cards = emptyList()
        requests = emptyList()
        detail = null
        detailAdmitted = false
        detailKey = null
        selectedSeasons = emptySet()
        page = 0
        nextSkip = 0
        mutation = TvSeerrMutation.None
        reduce(TvSeerrChange.ClearContent)
    }

    private fun applyConnection(value: SeerrConnection) {
        connection = value
        val currentUrl = state.value.connection.url ?: value.endpoint.orEmpty()
        val projected = value.toTvSeerr()
        reduce(
            TvSeerrChange.Connection(
                projected.copy(
                    url = currentUrl,
                    canLogin = projected.canLogin && value.endpoint == validatedEndpoint && currentUrl == validatedEndpoint,
                ),
            ),
        )
        reduce(TvSeerrChange.Reconciliation(value.unresolvedRequest != null))
        if (value.phase != SeerrConnectionPhase.Connected) clearContent()
    }

    private suspend fun <T> publishResult(
        result: SeerrResult<T>,
        catalog: Boolean,
        current: () -> Boolean,
        onFailure: (String) -> Unit,
        onSuccess: (T) -> Unit,
    ): Boolean {
        if (!ownerMatches() || !current()) return false
        if (result is SeerrResult.Failure && result.error == SeerrError.StaleOwner) return false
        val publication =
            when (result) {
                is SeerrResult.Success -> result.token
                is SeerrResult.Failure -> result.token ?: token
            }
        // Pre-validation management failures have no catalog payload or usable token.
        if (publication == null) {
            if (!catalog && result is SeerrResult.Failure && ownerMatches() && current()) onFailure(result.error.copyKey())
            return false
        }
        var delivered = false
        validatePublication.publish(publication, catalog = catalog) {
            if (ownerMatches() && current() && (!catalog || canShowCatalog())) {
                token = publication
                when (result) {
                    is SeerrResult.Success -> onSuccess(result.value)
                    is SeerrResult.Failure -> {
                        result.connection?.let(::applyConnection)
                        onFailure(result.error.copyKey())
                    }
                }
                delivered = true
            }
        }
        // Expiry/reconnect snapshots are management only; never publish rejected catalog data here.
        val fallbackConnection = if (result is SeerrResult.Failure) result.connection else null
        if (!delivered && result is SeerrResult.Failure && fallbackConnection != null && ownerMatches() && current()) {
            validatePublication.publish(publication, catalog = false) {
                if (ownerMatches() && current()) {
                    token = publication
                    applyConnection(fallbackConnection)
                    reduce(TvSeerrChange.Connection(state.value.connection.copy(errorKey = result.error.copyKey())))
                }
            }
        }
        return delivered
    }

    private fun managementFailure(key: String) {
        authToken = null
        reduce(
            TvSeerrChange.Connection(
                state.value.connection.copy(
                    busy = false,
                    authenticating = false,
                    quickConnectCode = null,
                    quickConnectExpiry = 0,
                    errorKey = key,
                ),
            ),
        )
    }

    private fun startManagement(authenticating: Boolean = false): Long {
        managementJob?.cancel()
        val generation = ++managementGeneration
        clearContent()
        reduce(
            TvSeerrChange.Connection(
                state.value.connection.copy(
                    busy = true,
                    authenticating = authenticating,
                    quickConnectCode = null,
                    quickConnectExpiry = 0,
                    errorKey = null,
                ),
            ),
        )
        return generation
    }

    private fun loadConnection(restore: Boolean) {
        if (!ownerMatches() || state.value.connection.authenticating) return
        val generation = startManagement()
        managementJob =
            scope.launch {
                val result = if (restore) restoreConnection(owner) else getConnection(owner)
                publishResult(result, false, { generation == managementGeneration }, ::managementFailure) {
                    if (restore) {
                        reduce(TvSeerrChange.Connection(state.value.connection.copy(url = it.endpoint.orEmpty())))
                    }
                    applyConnection(it)
                }
                if (generation == managementGeneration &&
                    state.value.connection.connected &&
                    canShowCatalog() &&
                    state.value.section != TvSeerrSection.Connection
                ) {
                    loadList(append = false)
                }
            }
    }

    private fun validate(url: String) {
        if (state.value.connection.busy) return
        validatedEndpoint = null
        reduce(TvSeerrChange.Connection(state.value.connection.copy(url = url, canLogin = false)))
        val generation = startManagement()
        managementJob =
            scope.launch {
                publishResult(
                    validateService(owner, url.trim()),
                    false,
                    { generation == managementGeneration },
                    ::managementFailure,
                ) { service ->
                    validatedEndpoint = service.endpoint
                    reduce(TvSeerrChange.Connection(state.value.connection.copy(url = service.endpoint)))
                    applyConnection(SeerrConnection(SeerrConnectionPhase.AwaitingLogin, endpoint = service.endpoint, service = service))
                }
            }
    }

    private fun canAuthenticate(url: String): Boolean {
        val current = state.value.connection
        return ownerMatches() &&
            !current.busy &&
            current.canLogin &&
            url == current.url &&
            url == current.endpoint &&
            url == validatedEndpoint
    }

    private fun login(intent: TvSeerrIntent.Login) {
        val captured = token ?: return
        if (!canAuthenticate(intent.url)) return
        val generation = startManagement(authenticating = true)
        authToken = captured
        managementJob =
            scope.launch {
                val result = connect(captured, intent.method, intent.identifier.trim(), intent.password)
                publishResult(result, false, { generation == managementGeneration }, ::managementFailure) {
                    authToken = null
                    applyConnection(it)
                }
            }
    }

    private fun quickConnect(url: String) {
        val captured = token ?: return
        if (!canAuthenticate(url) || !state.value.connection.quickConnect) return
        val generation = startManagement(authenticating = true)
        authToken = captured
        managementJob =
            scope.launch {
                var pollingToken: SeerrPublicationToken? = null
                publishResult(beginQuickConnect(captured), false, { generation == managementGeneration }, ::managementFailure) { code ->
                    pollingToken = token
                    authToken = token
                    reduce(
                        TvSeerrChange.Connection(
                            state.value.connection.copy(
                                quickConnectCode = code.code,
                                quickConnectExpiry = code.expiresAtEpochMillis,
                            ),
                        ),
                    )
                }
                val polling = pollingToken ?: return@launch
                publishResult(completeQuickConnect(polling), false, { generation == managementGeneration }, ::managementFailure) {
                    authToken = null
                    applyConnection(it)
                }
            }
    }

    private fun cancelAuth(editedUrl: String? = null) {
        val captured = authToken ?: token.takeIf { editedUrl != null }
        if (captured == null && editedUrl == null) return
        authToken = null
        val generation = startManagement()
        if (editedUrl != null) {
            token = null
            connection = null
            validatedEndpoint = null
            reduce(TvSeerrChange.Connection(TvSeerrConnectionState(url = editedUrl)))
        }
        if (captured == null) return
        managementJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val cancelled = withContext(NonCancellable) { cancelAuthentication(captured) }
                if (editedUrl != null) {
                    // Typing retires local authentication without validating or restoring the old endpoint.
                    if (cancelled is SeerrResult.Failure) {
                        publishResult(
                            cancelled.copy(connection = null),
                            false,
                            { generation == managementGeneration },
                            ::managementFailure,
                        ) {}
                    }
                    return@launch
                }
                if (cancelled is SeerrResult.Failure && cancelled.error != SeerrError.StaleOwner) {
                    publishResult(cancelled, false, { generation == managementGeneration }, ::managementFailure) {}
                    return@launch
                }
                // A canceled polling job may already have retired the generation. Read the resulting snapshot.
                publishResult(getConnection(owner), false, { generation == managementGeneration }, ::managementFailure, ::applyConnection)
                if (generation == managementGeneration &&
                    state.value.connection.connected &&
                    state.value.section != TvSeerrSection.Connection
                ) {
                    loadList(append = false)
                }
            }
    }

    private fun disconnectConnection() {
        if (mutation is TvSeerrMutation.Submitting) return
        val capturedAuth = authToken
        authToken = null
        validatedEndpoint = null
        val generation = startManagement()
        managementJob =
            scope.launch {
                capturedAuth?.let { cancelAuthentication(it) }
                publishResult(disconnect(owner), false, { generation == managementGeneration }, ::managementFailure, ::applyConnection)
            }
    }

    private fun selectSection(
        section: TvSeerrSection,
        load: Boolean = true,
    ) {
        if (mutation !is TvSeerrMutation.Submitting) history.clear()
        if (mutation is TvSeerrMutation.Submitting) return
        if (section == state.value.section) {
            if (detailKey != null) back()
            return
        }
        browseSnapshots[state.value.section] = BrowseSnapshot(state.value.browse.copy(loading = false), cards, requests, page, nextSkip)
        cancelReads()
        if (authToken != null) cancelAuth()
        detail = null
        detailAdmitted = false
        detailKey = null
        mutation = TvSeerrMutation.None
        reduce(TvSeerrChange.Section(section))
        if (section != TvSeerrSection.Connection) {
            val saved = browseSnapshots[section]
            cards = saved?.cards.orEmpty()
            requests = saved?.requests.orEmpty()
            page = saved?.page ?: 0
            nextSkip = saved?.nextSkip ?: 0
            reduce(
                TvSeerrChange.Browse(
                    saved?.browse ?: TvSeerrBrowseState(
                        catalog = if (section == TvSeerrSection.Search) SeerrCatalog.Search else SeerrCatalog.Trending,
                        hideAvailable = hideAvailable,
                    ),
                ),
            )
            if (load && !state.value.browse.loaded) loadList(append = false)
        }
    }

    private fun selectCatalog(
        catalog: SeerrCatalog,
        query: String,
    ) {
        if (mutation is TvSeerrMutation.Submitting || !canShowCatalog()) return
        selectSection(if (catalog == SeerrCatalog.Search) TvSeerrSection.Search else TvSeerrSection.Discover, load = false)
        cancelReads()
        history.clear()
        detailKey = null
        detail = null
        detailAdmitted = false
        cards = emptyList()
        requests = emptyList()
        mutation = TvSeerrMutation.None
        page = 0
        nextSkip = 0
        reduce(
            TvSeerrChange.Browse(
                TvSeerrBrowseState(catalog = catalog, query = query, submittedQuery = query, hideAvailable = hideAvailable),
            ),
        )
        loadList(append = false)
    }

    private fun loadList(append: Boolean) {
        val captured = token ?: return
        val current = state.value
        if (!canShowCatalog() ||
            !current.connection.connected ||
            current.connection.busy ||
            current.section == TvSeerrSection.Connection ||
            detailKey != null
        ) {
            return
        }
        if (current.section == TvSeerrSection.Search && current.browse.submittedQuery.isBlank()) return
        if (append && (current.browse.loading || !current.browse.hasMore)) return
        cancelReads()
        val generation = ++listGeneration
        val currentList = {
            generation == listGeneration &&
                state.value.section == current.section &&
                state.value.browse.submittedQuery == current.browse.submittedQuery &&
                state.value.browse.catalog == current.browse.catalog
        }
        val requestedPage = if (append) page + 1 else 1
        val skip = if (append) nextSkip else 0
        reduce(TvSeerrChange.Browse(current.browse.copy(loading = true, errorKey = null)))
        listJob =
            scope.launch {
                if (current.section == TvSeerrSection.Requests) {
                    publishResult(getRequests(captured, skip), true, currentList, ::listFailure) { result ->
                        requests = ((if (append) requests else emptyList()) + result.items.map { it.request }).distinctBy { it.id }
                        publishCards(result.items.map { it.media }, append, result.page, result.hasMore, result.nextSkip)
                    }
                } else if (current.browse.catalog == SeerrCatalog.Search && current.browse.submittedQuery.isBlank()) {
                    reduce(TvSeerrChange.Browse(current.browse.copy(loaded = false, loading = false)))
                } else {
                    publishResult(
                        getCatalog(captured, current.browse.catalog, requestedPage, current.browse.submittedQuery),
                        true,
                        currentList,
                        ::listFailure,
                    ) { result ->
                        publishCards(result.items, append, result.page, result.hasMore, 0)
                    }
                }
            }
    }

    private fun publishCards(
        incoming: List<SeerrMediaCard>,
        append: Boolean,
        currentPage: Int,
        hasMore: Boolean,
        skip: Int,
    ) {
        cards = ((if (append) cards else emptyList()) + incoming).distinctBy { it.key }
        page = currentPage
        nextSkip = skip
        val projected = projectCards()
        val currentFocus = state.value.browse.focusedCardId
        reduce(
            TvSeerrChange.Browse(
                state.value.browse.copy(
                    hideAvailable = hideAvailable,
                    cards = projected,
                    loading = false,
                    loaded = true,
                    errorKey = null,
                    hasMore = hasMore,
                    focusedCardId = currentFocus?.takeIf { id -> projected.any { it.id == id } } ?: projected.firstOrNull()?.id,
                ),
            ),
        )
    }

    private fun projectCards(): List<TvSeerrCard> {
        val requestsByMedia = requests.groupBy { it.key }
        return if (state.value.section == TvSeerrSection.Requests) {
            requests.mapNotNull { request ->
                cards.firstOrNull { it.key == request.key }?.toTvSeerr(listOf(request))?.copy(id = "request:${request.id}")
            }
        } else {
            cards
                .filterNot { hideAvailable && state.value.browse.catalog != SeerrCatalog.Search && it.isFullyAvailable() }
                .map { card -> card.toTvSeerr(requestsByMedia[card.key].orEmpty()) }
        }
    }

    private fun listFailure(key: String) {
        reduce(TvSeerrChange.Browse(state.value.browse.copy(loading = false, errorKey = key)))
    }

    private fun openDetail(id: String) {
        if (!canShowCatalog() || detailKey != null || mutation is TvSeerrMutation.Submitting) return
        val visible =
            state.value.browse.cards
                .firstOrNull { it.id == id } ?: return
        val key = visible.requests.singleOrNull()?.let { request -> requests.firstOrNull { it.id == request.id }?.key }
        val card = cards.firstOrNull { it.key == key || it.key.stableId() == id } ?: return
        history.clear()
        cancelReads()
        reduce(TvSeerrChange.Browse(state.value.browse.copy(loading = false, focusedCardId = id)))
        detailKey = card.key
        detail = null
        detailAdmitted = false
        selectedSeasons = emptySet()
        quality = SeerrQuality.Standard
        reduce(TvSeerrChange.Detail(TvSeerrDetailState(card.toTvSeerr(), entryId = ++entrySequence)))
        loadDetail()
    }

    private fun loadDetail(notice: String? = state.value.detail?.noticeKey) {
        val captured = token ?: return
        val key = detailKey ?: return
        if (!canShowCatalog() || mutation is TvSeerrMutation.Submitting) return
        cancelRelated()
        detailJob?.cancel()
        cancelLibrary()
        val generation = ++detailGeneration
        detailAdmitted = false
        setMutation(TvSeerrMutation.None)
        state.value.detail?.let {
            reduce(
                TvSeerrChange.Detail(
                    it.copy(loading = true, errorKey = null, canRequest = false, selectionEnabled = false, relatedEnabled = false),
                ),
            )
        }
        detailJob =
            scope.launch {
                publishResult(getDetail(captured, key), true, { generation == detailGeneration && key == detailKey }, ::detailFailure) {
                    val firstLoad = detail == null
                    detail = it
                    detailAdmitted = true
                    reduce(TvSeerrChange.Reconciliation(it.reconciliationRequired))
                    if (firstLoad && !it.standard.canRequest && it.fourK.canRequest) quality = SeerrQuality.FourK
                    val option = if (quality == SeerrQuality.FourK) it.fourK else it.standard
                    selectedSeasons = selectedSeasons.intersect(option.eligibleSeasonNumbers.toSet())
                    projectDetail(notice = notice)
                    if (state.value.detail?.relatedPhase == TvSeerrRelatedPhase.NotAttempted) loadRelated()
                }
            }
    }

    private fun detailFailure(key: String) {
        detailAdmitted = false
        setMutation(TvSeerrMutation.None)
        state.value.detail?.let {
            reduce(
                TvSeerrChange.Detail(
                    it.copy(
                        loading = false,
                        errorKey = key,
                        resolvingLibrary = false,
                        canRequest = false,
                        selectionEnabled = false,
                        relatedEnabled = false,
                        reconciliationRequired = state.value.reconciliationRequired,
                    ),
                ),
            )
        }
    }

    private fun projectDetail(notice: String? = state.value.detail?.noticeKey) {
        val value = detail ?: return
        val projected =
            value.toTvSeerr(
                quality,
                selectedSeasons,
                state.value.reconciliationRequired,
                connection?.service?.specialEpisodesEnabled == true,
            )
        reduce(
            TvSeerrChange.Detail(
                projected.copy(
                    entryId = state.value.detail?.entryId ?: ++entrySequence,
                    similar =
                        state.value.detail
                            ?.similar
                            .orEmpty(),
                    relatedPhase = state.value.detail?.relatedPhase ?: TvSeerrRelatedPhase.NotAttempted,
                    focusedId = state.value.detail?.focusedId,
                    verticalAnchor = state.value.detail?.verticalAnchor,
                    seasonAnchor = state.value.detail?.seasonAnchor,
                    castAnchor = state.value.detail?.castAnchor,
                    similarAnchor = state.value.detail?.similarAnchor,
                    selectedSeasonCount = selectedSeasons.size,
                    showFourK = supportsFourK(value),
                    resolvingLibrary = resolvingLibrary,
                    relatedEnabled = relatedAllowed(coreLoading = false),
                    canRequest = projected.canRequest && detailAdmitted,
                    selectionEnabled = detailAdmitted,
                    noticeKey = notice,
                ),
            ),
        )
    }

    private fun supportsFourK(value: SeerrDetail): Boolean {
        val configured =
            if (value.media.key.type == com.jellyscope.core.domain.model.SeerrMediaType.Movie) {
                connection?.service?.movie4kEnabled
            } else {
                connection?.service?.series4kEnabled
            }
        return (configured == true && connection?.user?.permissions?.canRequest(value.media.key.type, SeerrQuality.FourK) == true) ||
            value.media.fourKAvailability in
            setOf(
                com.jellyscope.core.domain.model.SeerrAvailability.Available,
                com.jellyscope.core.domain.model.SeerrAvailability.PartiallyAvailable,
            )
    }

    private fun selectQuality(fourK: Boolean) {
        if (fourK && state.value.detail?.showFourK != true) return
        if (!canShowCatalog() || !detailAdmitted || state.value.detail?.loading != false || mutation.confirmation != null) return
        quality = if (fourK) SeerrQuality.FourK else SeerrQuality.Standard
        selectedSeasons = emptySet()
        cancelLibrary()
        projectDetail()
    }

    private fun toggleSeason(number: Int) {
        if (!canShowCatalog() || !detailAdmitted || state.value.detail?.loading != false || mutation.confirmation != null) return
        val season =
            state.value.detail
                ?.seasons
                ?.firstOrNull { it.number == number && it.enabled } ?: return
        selectedSeasons = if (season.selected) selectedSeasons - number else selectedSeasons + number
        projectDetail()
    }

    private fun confirmRequest() {
        val current = state.value.detail ?: return
        val title = current.card.title ?: return
        if (!canShowCatalog() || !detailAdmitted || !current.canRequest || current.loading) return
        cancelLibrary()
        setMutation(
            TvSeerrMutation.Confirming(
                SeerrRequestIntent(detailKey ?: return, quality, selectedSeasons.sorted()),
                TvSeerrConfirmation(
                    title,
                    current.card.typeKey,
                    quality.copyKey(),
                    current.seasons.filter { it.selected },
                    current.requiresApproval,
                ),
            ),
        )
    }

    private fun submitRequest() {
        val captured = token ?: return
        val key = detailKey ?: return
        val confirming = mutation as? TvSeerrMutation.Confirming ?: return
        if (!canShowCatalog() || state.value.detail?.canRequest != true) return
        cancelRelated()
        val generation = detailGeneration
        val intent = confirming.intent
        setMutation(TvSeerrMutation.Submitting(intent, generation, confirming.confirmation))
        mutationJob =
            scope.launch {
                var refresh = false
                var notice: String? = null
                publishResult(requestMedia(captured, intent), true, { generation == detailGeneration && key == detailKey }, { error ->
                    setMutation(TvSeerrMutation.None)
                    detailFailure(error)
                    val uncertain = state.value.reconciliationRequired || error == SeerrError.ReconciliationRequired.copyKey()
                    reduce(TvSeerrChange.Reconciliation(uncertain))
                    if (uncertain) {
                        projectDetail(notice = SeerrError.ReconciliationRequired.copyKey())
                    } else if (error == SeerrError.NoSeasonsAvailable.copyKey()) {
                        notice = error
                        refresh = true
                    }
                }) {
                    detailAdmitted = false
                    state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(canRequest = false, loading = true))) }
                    setMutation(TvSeerrMutation.None)
                    notice = "seerr.request.created"
                    refresh = true
                }
                if (refresh && generation == detailGeneration && key == detailKey) loadDetail(notice)
            }
    }

    private fun reconcile() {
        val captured = token ?: return
        if (!canShowCatalog() ||
            !state.value.reconciliationRequired ||
            mutation is TvSeerrMutation.Submitting ||
            state.value.detail?.loading == true ||
            state.value.browse.loading
        ) {
            return
        }
        cancelReads()
        val generation = detailGeneration
        mutation = TvSeerrMutation.Reconciling(generation, mutation as? TvSeerrMutation.Confirming)
        detailAdmitted = false
        reduce(TvSeerrChange.Browse(state.value.browse.copy(loading = true, errorKey = null)))
        state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(loading = true, canRequest = false, errorKey = null))) }
        detailJob =
            scope.launch {
                publishResult(reconcileRequest(captured), true, { generation == detailGeneration }, { error ->
                    mutation = (mutation as? TvSeerrMutation.Reconciling)?.confirming ?: TvSeerrMutation.None
                    reduce(TvSeerrChange.Browse(state.value.browse.copy(loading = false)))
                    if (detailKey != null) detailFailure(error) else listFailure(error)
                }) { refreshed ->
                    mutation = (mutation as? TvSeerrMutation.Reconciling)?.confirming ?: TvSeerrMutation.None
                    reduce(TvSeerrChange.Browse(state.value.browse.copy(loading = false)))
                    reduce(TvSeerrChange.Reconciliation(false))
                    detailKey = refreshed.media.key
                    detail = refreshed
                    detailAdmitted = true
                    selectedSeasons = emptySet()
                    if (!refreshed.standard.canRequest && refreshed.fourK.canRequest) quality = SeerrQuality.FourK
                    projectDetail(notice = "seerr.request.reconciled")
                }
            }
    }

    private fun resolveLibrary() {
        val captured = token ?: return
        val key = detailKey ?: return
        if (!canShowCatalog() ||
            state.value.detail?.loading != false ||
            mutation.confirmation != null ||
            resolvingLibrary ||
            pendingLibrary != null
        ) {
            return
        }
        cancelRelated()
        val generation = ++libraryGeneration
        resolvingLibrary = true
        state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(resolvingLibrary = true, relatedEnabled = false, errorKey = null))) }
        libraryJob =
            scope.launch {
                publishResult(
                    resolveLibraryItem(captured, key, quality),
                    true,
                    { generation == libraryGeneration && key == detailKey },
                    { error ->
                        resolvingLibrary = false
                        state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(resolvingLibrary = false, errorKey = error))) }
                        refreshRelatedAdmission()
                    },
                ) { item ->
                    val publication = token ?: return@publishResult
                    val id = ++effectSequence
                    pendingLibrary = PendingSeerrLibrary(id, publication, item, generation)
                    resolvingLibrary = false
                    state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(resolvingLibrary = false))) }
                    effects.trySend(TvSeerrLibraryEffect(id))
                }
            }
    }

    private fun back() {
        if (mutation is TvSeerrMutation.Submitting) return
        if (mutation.confirmation != null) {
            val reconciling = mutation as? TvSeerrMutation.Reconciling
            setMutation(reconciling?.copy(confirming = null) ?: TvSeerrMutation.None)
        } else if (detailKey != null) {
            cancelReads()
            val parent = history.removeLastOrNull()
            if (parent == null) {
                detailKey = null
                detail = null
                detailAdmitted = false
                selectedSeasons = emptySet()
                reduce(TvSeerrChange.Detail(null))
            } else {
                detailKey = parent.detail.media.key
                detail = parent.detail
                quality = parent.quality
                selectedSeasons = parent.selected
                detailAdmitted = false
                reduce(TvSeerrChange.Detail(parent.display.copy(canRequest = false, selectionEnabled = false, relatedEnabled = false)))
                loadDetail(notice = null)
            }
        } else if (state.value.section == TvSeerrSection.Connection) {
            selectSection(TvSeerrSection.Discover)
        }
    }

    private fun reprojectCards(resetFocus: Boolean) {
        val before = state.value.browse
        val projected = projectCards()
        reduce(
            TvSeerrChange.Browse(
                before.copy(
                    hideAvailable = hideAvailable,
                    cards = projected,
                    focusedCardId = if (resetFocus) projected.firstOrNull()?.id else before.focusedCardId,
                ),
            ),
        )
    }

    private fun refreshRelatedAdmission() {
        state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(relatedEnabled = relatedAllowed()))) }
    }

    private fun relatedAllowed(coreLoading: Boolean = state.value.detail?.loading != false): Boolean =
        visible &&
            canShowCatalog() &&
            detailAdmitted &&
            detailKey != null &&
            !coreLoading &&
            mutation == TvSeerrMutation.None &&
            !state.value.reconciliationRequired &&
            !resolvingLibrary &&
            pendingLibrary == null

    private fun cancelRelated() {
        relatedRevision++
        relatedJob?.cancel()
        relatedJob = null
        state.value.detail?.let {
            if (it.relatedPhase == TvSeerrRelatedPhase.Loading) {
                reduce(TvSeerrChange.Detail(it.copy(relatedPhase = TvSeerrRelatedPhase.Interrupted)))
            }
        }
    }

    private fun loadRelated() {
        val captured = token ?: return
        val key = detailKey ?: return
        val current = state.value.detail ?: return
        if (!relatedAllowed() || current.relatedPhase == TvSeerrRelatedPhase.Loading || relatedJob?.isActive == true) return
        val revision = ++relatedRevision
        val entry = current.entryId
        reduce(TvSeerrChange.Detail(current.copy(relatedPhase = TvSeerrRelatedPhase.Loading)))
        relatedJob =
            scope.launch {
                val owns = { revision == relatedRevision && state.value.detail?.entryId == entry && token == captured }
                publishResult(getSimilar(captured, key), true, owns, {
                    state.value.detail?.let { reduce(TvSeerrChange.Detail(it.copy(relatedPhase = TvSeerrRelatedPhase.Error))) }
                }) { page ->
                    state.value.detail?.let {
                        reduce(
                            TvSeerrChange.Detail(
                                it.copy(
                                    relatedPhase = TvSeerrRelatedPhase.Ready,
                                    similar =
                                        page.items.map { card ->
                                            card.toTvSeerr()
                                        },
                                ),
                            ),
                        )
                    }
                }
            }
    }

    private fun openSimilar(id: String) {
        if (!relatedAllowed()) return
        val current = state.value.detail ?: return
        val card = current.similar.firstOrNull { it.id == id } ?: return
        val value = detail ?: return
        val type =
            if (card.typeKey ==
                "seerr.movie"
            ) {
                com.jellyscope.core.domain.model.SeerrMediaType.Movie
            } else {
                com.jellyscope.core.domain.model.SeerrMediaType.Tv
            }
        val key = SeerrMediaKey(type, id.substringAfter(':').toIntOrNull() ?: return)
        cancelReads()
        val snapshot = state.value.detail ?: return
        history.add(
            SeerrDetailSnapshot(
                value,
                quality,
                selectedSeasons,
                snapshot.copy(noticeKey = null, canRequest = false, selectionEnabled = false),
            ),
        )
        detailKey = key
        detail = null
        detailAdmitted = false
        quality = SeerrQuality.Standard
        selectedSeasons = emptySet()
        reduce(TvSeerrChange.Detail(TvSeerrDetailState(card, entryId = ++entrySequence)))
        loadDetail(notice = null)
    }

    private fun retireAuthOnClose() {
        val captured = authToken ?: return
        authToken = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { cancelAuthentication(captured) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        managementJob?.cancel()
        retireAuthOnClose()
        clearContent()
        effects.close()
        super.close()
    }
}

private data class PendingSeerrLibrary(
    val id: Long,
    val token: SeerrPublicationToken,
    val item: SeerrLibraryItem,
    val generation: Long,
)

private sealed interface TvSeerrMutation {
    val confirmation: TvSeerrConfirmation? get() = null

    data object None : TvSeerrMutation

    data class Confirming(
        val intent: SeerrRequestIntent,
        override val confirmation: TvSeerrConfirmation,
    ) : TvSeerrMutation

    data class Submitting(
        val intent: SeerrRequestIntent,
        val detailGeneration: Long,
        private val presentation: TvSeerrConfirmation,
    ) : TvSeerrMutation {
        override val confirmation: TvSeerrConfirmation get() = presentation.copy(submitting = true)
    }

    data class Reconciling(
        val detailGeneration: Long,
        val confirming: Confirming?,
    ) : TvSeerrMutation {
        override val confirmation: TvSeerrConfirmation? get() = confirming?.confirmation
    }
}

private data class SeerrDetailSnapshot(
    val detail: SeerrDetail,
    val quality: SeerrQuality,
    val selected: Set<Int>,
    val display: TvSeerrDetailState,
)
