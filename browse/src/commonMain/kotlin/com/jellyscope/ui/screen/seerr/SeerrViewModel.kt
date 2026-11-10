// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrOwner
import com.jellyscope.core.domain.model.SeerrPublicationToken
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrRequestIntent
import com.jellyscope.core.domain.model.SeerrResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.model.isFullyAvailable
import com.jellyscope.core.domain.usecase.GetSeerrCatalogUseCase
import com.jellyscope.core.domain.usecase.GetSeerrConnectionUseCase
import com.jellyscope.core.domain.usecase.GetSeerrDetailUseCase
import com.jellyscope.core.domain.usecase.GetSeerrPersonUseCase
import com.jellyscope.core.domain.usecase.GetSeerrRequestsUseCase
import com.jellyscope.core.domain.usecase.GetSeerrSimilarUseCase
import com.jellyscope.core.domain.usecase.ObserveSessionStateUseCase
import com.jellyscope.core.domain.usecase.ResolveSeerrLibraryItemUseCase
import com.jellyscope.core.domain.usecase.ValidateSeerrPublicationUseCase
import com.jellyscope.ui.screen.person.PersonHeaderUi
import com.jellyscope.ui.screen.person.PersonUiState
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

class SeerrViewModel(
    session: Session,
    boundaryEpoch: Long,
    private val getConnection: GetSeerrConnectionUseCase,
    private val getCatalog: GetSeerrCatalogUseCase,
    private val getDetail: GetSeerrDetailUseCase,
    private val getSimilar: GetSeerrSimilarUseCase,
    private val getRequests: GetSeerrRequestsUseCase,
    private val getPerson: GetSeerrPersonUseCase,
    private val resolveLibraryItem: ResolveSeerrLibraryItemUseCase,
    private val validatePublication: ValidateSeerrPublicationUseCase,
    private val validateService: ValidateSeerrServiceAction,
    private val restoreConnection: RestoreSeerrConnectionAction,
    private val connect: ConnectSeerrAction,
    private val beginQuickConnect: BeginSeerrQuickConnectAction,
    private val completeQuickConnect: CompleteSeerrQuickConnectAction,
    private val cancelAuthentication: CancelSeerrAuthenticationAction,
    private val disconnect: DisconnectSeerrAction,
    private val requestMedia: RequestSeerrMediaAction,
    private val reconcileRequest: ReconcileSeerrRequestAction,
    private val observeSessionState: ObserveSessionStateUseCase,
) : ViewModel() {
    private val owner = SeerrOwner(session.accountIdentity(), boundaryEpoch)
    private val mutableState = MutableStateFlow(SeerrUiState(restricted = session.maxParentalRating != null))
    val state: StateFlow<SeerrUiState> = mutableState.asStateFlow()
    private val libraryChannel = Channel<SeerrLibraryEffect>(Channel.BUFFERED)
    val libraryEffects = libraryChannel.receiveAsFlow()
    private var token: SeerrPublicationToken? = null
    private var managementJob: Job? = null
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null
    private var libraryJob: Job? = null
    private var managementGeneration = 0L
    private var listGeneration = 0L
    private var detailGeneration = 0L
    private var effectSequence = 0L
    private var pendingEffect: Long? = null
    private var authToken: SeerrPublicationToken? = null
    private var relatedJob: Job? = null
    private var relatedRevision = 0L
    private var visible = false
    private var entrySequence = 0L

    private data class HistoryEntry(
        val detail: SeerrDetailUi,
        val person: SeerrPersonUi? = null,
    )

    private val history = mutableListOf<HistoryEntry>()

    init {
        observePolicy(observeSessionState().value)
        viewModelScope.launch { observeSessionState().collect(::observePolicy) }
        if (state.value.ownerValid) manage { getConnection(owner) }
    }

    fun accept(intent: SeerrIntent) {
        observePolicy(observeSessionState().value)
        if (intent == SeerrIntent.Disposed) {
            visible = false
            cancelRelated()
            cancelLibrary()
            cancelAuth()
            return
        }
        if (!state.value.ownerValid) return
        if (state.value.person != null &&
            intent !is SeerrIntent.OpenCredit &&
            intent !is SeerrIntent.PersonFocused &&
            intent !is SeerrIntent.RememberPerson &&
            intent != SeerrIntent.RetryPerson &&
            intent != SeerrIntent.Back &&
            intent != SeerrIntent.Resumed &&
            intent !is SeerrIntent.SelectSection
        ) {
            return
        }
        when (intent) {
            is SeerrIntent.OpenPerson -> openPerson(intent.id)
            is SeerrIntent.OpenCredit -> openCredit(intent.id)
            SeerrIntent.RetryPerson -> loadPerson()
            is SeerrIntent.PersonFocused -> state.value.person?.let { reduce(SeerrChange.Person(it.copy(focusedId = intent.id))) }
            is SeerrIntent.RememberPerson ->
                state.value.person?.let {
                    reduce(SeerrChange.Person(it.copy(scroll = intent.scroll, movieScroll = intent.movies, seriesScroll = intent.series)))
                }
            SeerrIntent.Resumed -> {
                visible = true
                if (state.value.detail
                        ?.related
                        ?.phase == SeerrRelatedPhase.NotAttempted
                ) {
                    loadRelated()
                }
            }
            SeerrIntent.ToggleHideAvailable -> {
                if (state.value.section != SeerrSection.Discover ||
                    state.value.catalog == SeerrCatalog.Search ||
                    state.value.detail != null
                ) {
                    return
                }
                reduce(SeerrChange.HideAvailable(!state.value.hideAvailable))
                val feed = state.value.discover
                val rows = visibleRows(feed.rawRows)
                reduce(
                    SeerrChange.Feed(
                        SeerrSection.Discover,
                        feed.copy(
                            rows = rows,
                            scroll = SeerrScrollPosition(),
                            selectedId = null,
                            resetRevision =
                                feed.resetRevision + 1,
                        ),
                    ),
                )
                reduce(SeerrChange.Focus(rows.firstOrNull()?.id))
            }
            is SeerrIntent.RememberSeasonScroll -> updateDetail { it.copy(seasonScroll = intent.position) }
            is SeerrIntent.RememberCastScroll -> updateDetail { it.copy(castScroll = intent.position) }
            is SeerrIntent.OpenSimilar -> openSimilar(intent.id)
            SeerrIntent.RetrySimilar -> loadRelated()
            is SeerrIntent.RememberRelatedScroll -> updateDetail { it.copy(related = it.related.copy(scroll = intent.position)) }

            is SeerrIntent.SelectSection -> {
                if (state.value.detail?.submitting == true) return
                history.clear()
                cancelReads()
                if (state.value.section == SeerrSection.Connection) cancelAuth()
                reduce(SeerrChange.Section(intent.section))
                if (!state.value.feed.loaded) loadFeed()
            }
            is SeerrIntent.SelectCatalog -> {
                if (!catalogAllowed() || state.value.section != SeerrSection.Discover || intent.catalog == SeerrCatalog.Search) return
                cancelReads()
                reduce(SeerrChange.Catalog(intent.catalog))
                loadFeed()
            }
            is SeerrIntent.QueryChanged -> {
                if (!catalogAllowed() || state.value.section != SeerrSection.Search || state.value.detail != null) return
                cancelList()
                reduce(SeerrChange.Query(intent.text))
            }
            SeerrIntent.SubmitSearch -> {
                val query = state.value.query.trim()
                if (query.isEmpty() || !catalogAllowed() || state.value.detail?.submitting == true) return
                cancelReads()
                reduce(SeerrChange.Section(SeerrSection.Search))
                reduce(SeerrChange.Query(query))
                reduce(SeerrChange.Catalog(SeerrCatalog.Search, query))
                loadFeed()
            }
            SeerrIntent.ClearSearch -> {
                if (state.value.detail?.submitting == true) return
                cancelReads()
                reduce(SeerrChange.Query(""))
                reduce(SeerrChange.Section(SeerrSection.Search))
                reduce(SeerrChange.Catalog(SeerrCatalog.Search))
            }
            SeerrIntent.Refresh -> state.value.detail?.let { loadDetail(it.key) } ?: loadFeed()
            SeerrIntent.LoadMore -> if (state.value.feed.hasMore && !state.value.feed.loading) loadFeed(more = true)
            is SeerrIntent.OpenDetail -> openDetail(intent.row)
            SeerrIntent.Back -> back()
            is SeerrIntent.SelectQuality -> {
                val detail = state.value.detail ?: return
                if (!detail.selectionEnabled || intent.quality !in detail.requestQualities) return
                cancelLibrary()
                updateDetail {
                    it.copy(
                        quality = intent.quality,
                        selectedSeasons = emptySet(),
                        mutation = SeerrMutation.None,
                        error = null,
                        reviewVisible = false,
                        reviewSeason = null,
                        libraryPickerVisible = false,
                        qualityPickerVisible = false,
                        focusedId = "quality",
                        focusRevision =
                            it.focusRevision + 1,
                    )
                }
            }
            is SeerrIntent.ToggleSeason ->
                updateDetail {
                    val row = it.seasons.firstOrNull { row -> row.season.number == intent.number }
                    if (!it.reviewEnabled || row == null) {
                        it
                    } else if (!row.enabled) {
                        it.copy(reviewVisible = true, reviewSeason = intent.number, mutation = SeerrMutation.None)
                    } else {
                        it.copy(
                            selectedSeasons =
                                if (intent.number in
                                    it.selectedSeasons
                                ) {
                                    it.selectedSeasons - intent.number
                                } else {
                                    it.selectedSeasons + intent.number
                                },
                        )
                    }
                }
            SeerrIntent.ConfirmRequest ->
                updateDetail {
                    if (it.reviewEnabled) {
                        it.copy(
                            reviewVisible = true,
                            reviewSeason = null,
                            mutation =
                                if (it.canSubmit) {
                                    SeerrMutation.Confirming(SeerrRequestIntent(it.key, it.quality, it.selectedSeasonNumbers))
                                } else {
                                    SeerrMutation.None
                                },
                        )
                    } else {
                        it
                    }
                }
            SeerrIntent.SubmitRequest -> submit()
            SeerrIntent.ChooseQuality ->
                updateDetail {
                    if (it.selectionEnabled && it.requestQualities.size > 1) it.copy(qualityPickerVisible = true) else it
                }
            SeerrIntent.ChooseSeasons ->
                updateDetail {
                    if (!it.reviewVisible || !it.chooseSeasons || it.submitting) {
                        it
                    } else {
                        val target = it.seasons.firstOrNull { row -> row.enabled } ?: it.seasons.firstOrNull()
                        it.copy(
                            reviewVisible = false,
                            reviewSeason = null,
                            mutation = SeerrMutation.None,
                            focusedId = target?.let { row -> "season:${row.season.number}" } ?: "request",
                            focusRevision = it.focusRevision + 1,
                        )
                    }
                }
            SeerrIntent.Reconcile -> reconcile()
            SeerrIntent.OpenLibrary -> {
                val detail = state.value.detail ?: return
                if (detail.libraryQualities.size == 1) {
                    openLibrary(detail.libraryQualities.single())
                } else if (detail.libraryQualities.size > 1 && detail.reviewEnabled) {
                    updateDetail { it.copy(libraryPickerVisible = true) }
                }
            }
            is SeerrIntent.OpenLibraryQuality -> openLibrary(intent.quality)
            is SeerrIntent.UrlChanged -> {
                if (state.value.connection.busy || state.value.connected) return
                token = null
                connection {
                    it.copy(
                        url = intent.text,
                        connection = SeerrConnection(SeerrConnectionPhase.Disconnected),
                        error = null,
                        credentialRevision =
                            it.credentialRevision + 1,
                    )
                }
            }
            is SeerrIntent.SelectLogin ->
                connection {
                    if (it.busy) it else it.copy(method = intent.method, credentialRevision = it.credentialRevision + 1, error = null)
                }
            SeerrIntent.ValidateService -> validate()
            is SeerrIntent.Login -> login(intent)
            SeerrIntent.BeginQuickConnect -> quickConnect()
            SeerrIntent.CancelAuthentication -> {
                if (authToken != null) {
                    cancelAuth()
                } else {
                    managementGeneration++
                    managementJob?.cancel()
                    connection {
                        it.copy(
                            busy = false,
                            authenticating = false,
                            quickConnect = null,
                            credentialRevision =
                                it.credentialRevision + 1,
                        )
                    }
                }
            }
            SeerrIntent.RestoreConnection -> manage { restoreConnection(owner) }
            SeerrIntent.Disconnect -> {
                authToken = null
                manage { disconnect(owner) }
            }
            is SeerrIntent.RememberScroll -> {
                if (intent.section != null && intent.section != state.value.section) return
                if (intent.detail) {
                    state.value.detail?.let { reduce(SeerrChange.Detail(it.copy(scroll = intent.position))) }
                } else {
                    reduce(SeerrChange.Feed(state.value.section, state.value.feed.copy(scroll = intent.position)))
                }
            }
            is SeerrIntent.Focused -> {
                reduce(SeerrChange.Focus(intent.id))
                if (state.value.detail == null && state.value.section != SeerrSection.Connection) {
                    reduce(SeerrChange.Feed(state.value.section, state.value.feed.copy(selectedId = intent.id)))
                }
                updateDetail { it.copy(focusedId = intent.id) }
            }
            SeerrIntent.Disposed -> Unit
        }
    }

    /** The collector calls this after receiving one effect, on Main, immediately before navigation. */
    suspend fun consumeLibraryEffect(
        effect: SeerrLibraryEffect,
        onOpenLibrary: (SeerrLibraryItem) -> Unit,
    ) {
        if (pendingEffect != effect.id) return
        pendingEffect = null
        // Keep Similar admission closed until publication and synchronous navigation finish.
        try {
            if (!catalogAllowed() ||
                effect.id != effectSequence ||
                effect.detailGeneration != detailGeneration ||
                state.value.detail?.key != effect.item.key
            ) {
                return
            }
            validatePublication.publish(effect.token) {
                if (catalogAllowed() &&
                    effect.id == effectSequence &&
                    effect.detailGeneration == detailGeneration &&
                    state.value.detail?.key == effect.item.key &&
                    token == effect.token
                ) {
                    onOpenLibrary(effect.item)
                }
            }
        } finally {
            if (
                effect.id == effectSequence &&
                effect.detailGeneration == detailGeneration &&
                state.value.detail?.key == effect.item.key
            ) {
                updateDetail { it.copy(resolvingLibrary = false) }
            }
        }
    }

    private fun reduce(change: SeerrChange) {
        if (change is SeerrChange.ClearContent || change is SeerrChange.Catalog) history.clear()
        mutableState.update { reduceSeerr(it, change) }
    }

    private fun connection(transform: (SeerrConnectionUi) -> SeerrConnectionUi) =
        reduce(SeerrChange.Connection(transform(state.value.connection)))

    private fun updateDetail(transform: (SeerrDetailUi) -> SeerrDetailUi) {
        state.value.detail?.let { reduce(SeerrChange.Detail(transform(it).project(state.value.connection.connection))) }
    }

    private fun catalogAllowed(): Boolean {
        val live = observeSessionState().value as? SessionState.LoggedIn ?: return false
        return state.value.ownerValid &&
            !state.value.restricted &&
            live.boundaryEpoch == owner.boundaryEpoch &&
            live.session.accountIdentity() == owner.account &&
            live.session.maxParentalRating == null &&
            state.value.connected
    }

    private fun observePolicy(live: SessionState) {
        val loggedIn = live as? SessionState.LoggedIn
        val matches =
            loggedIn != null && loggedIn.boundaryEpoch == owner.boundaryEpoch && loggedIn.session.accountIdentity() == owner.account
        val valid = state.value.ownerValid && matches
        val restricted = loggedIn?.session?.maxParentalRating != null
        if (valid == state.value.ownerValid && restricted == state.value.restricted) return
        cancelReads()
        mutationJob?.cancel()
        cancelAuth()
        if (!valid) {
            managementGeneration++
            managementJob?.cancel()
            token = null
            connection { SeerrConnectionUi(credentialRevision = it.credentialRevision + 1) }
        }
        reduce(SeerrChange.Policy(valid, restricted))
        reduce(SeerrChange.ClearContent)
        connection { it.copy(credentialRevision = it.credentialRevision + 1) }
        if (valid && !restricted && catalogAllowed()) loadFeed()
    }

    private fun cancelList() {
        listGeneration++
        listJob?.cancel()
        listJob = null
        reduce(SeerrChange.Feed(SeerrSection.Discover, state.value.discover.copy(loading = false)))
        reduce(SeerrChange.Feed(SeerrSection.Requests, state.value.requests.copy(loading = false)))
        reduce(SeerrChange.Feed(SeerrSection.Search, state.value.search.copy(loading = false)))
    }

    private fun cancelReads() {
        cancelRelated()
        cancelList()
        detailGeneration++
        detailJob?.cancel()
        cancelLibrary()
    }

    private fun cancelLibrary() {
        // Independent of detail ownership: hiding Requests must not cancel its POST.
        effectSequence++
        libraryJob?.cancel()
        libraryJob = null
        pendingEffect = null
        while (libraryChannel.tryReceive().isSuccess) Unit
        if (state.value.detail?.resolvingLibrary == true) updateDetail { it.copy(resolvingLibrary = false) }
    }

    private fun applyConnection(value: SeerrConnection) {
        authToken = null
        connection {
            it.copy(
                connection = value,
                url = value.endpoint ?: it.url,
                busy = false,
                authenticating = false,
                quickConnect = null,
                error = null,
                credentialRevision = it.credentialRevision + 1,
            )
        }
        if (value.phase != SeerrConnectionPhase.Connected) {
            history.clear()
            cancelReads()
            reduce(SeerrChange.ClearContent)
        }
    }

    private suspend fun <T> publish(
        result: SeerrResult<T>,
        catalog: Boolean,
        current: () -> Boolean,
        failed: (SeerrError) -> Unit,
        success: (T) -> Unit,
    ) {
        if (!current() || !state.value.ownerValid) return
        when (result) {
            is SeerrResult.Success ->
                validatePublication.publish(result.token, catalog) {
                    if (current() && state.value.ownerValid && (!catalog || catalogAllowed())) {
                        token = result.token
                        success(result.value)
                    }
                }
            is SeerrResult.Failure -> {
                if (result.error == SeerrError.StaleOwner) {
                    cancelReads()
                    token = null
                    reduce(SeerrChange.ClearContent)
                    connection { SeerrConnectionUi(credentialRevision = it.credentialRevision + 1) }
                    return
                }
                val replacement = result.token
                val snapshot = result.connection
                if (replacement != null && snapshot != null) {
                    // Only management data may use catalog=false, including an expiry snapshot.
                    validatePublication.publish(replacement, catalog = false) {
                        if (current() && state.value.ownerValid) {
                            token = replacement
                            applyConnection(snapshot)
                            connection { it.copy(error = result.error) }
                        }
                    }
                }
                if (!current()) return
                val publicationToken = replacement ?: token
                if (publicationToken != null) {
                    validatePublication.publish(publicationToken, catalog) {
                        if (current() && state.value.ownerValid && (!catalog || catalogAllowed())) {
                            token = publicationToken
                            failed(result.error)
                        }
                    }
                } else if (!catalog && state.value.ownerValid) {
                    failed(result.error)
                }
            }
        }
    }

    private fun manage(operation: suspend () -> SeerrResult<SeerrConnection>) {
        cancelReads()
        reduce(SeerrChange.ClearContent)
        managementJob?.cancel()
        val generation = ++managementGeneration
        connection { it.copy(busy = true, error = null, quickConnect = null, credentialRevision = it.credentialRevision + 1) }
        managementJob =
            viewModelScope.launch {
                publish(operation(), false, { generation == managementGeneration }, { error ->
                    connection { it.copy(busy = false, authenticating = false, error = error) }
                }, ::applyConnection)
                if (generation == managementGeneration && catalogAllowed() && state.value.section != SeerrSection.Connection) loadFeed()
            }
    }

    private fun validate() {
        if (state.value.connection.busy) return
        cancelReads()
        reduce(SeerrChange.ClearContent)
        managementJob?.cancel()
        val generation = ++managementGeneration
        val url = state.value.connection.url
        connection { it.copy(busy = true, error = null, credentialRevision = it.credentialRevision + 1) }
        managementJob =
            viewModelScope.launch {
                publish(validateService(owner, url), false, { generation == managementGeneration }, { error ->
                    connection { it.copy(busy = false, error = error) }
                }) { service ->
                    connection {
                        it.copy(
                            busy = false,
                            connection =
                                SeerrConnection(
                                    SeerrConnectionPhase.AwaitingLogin,
                                    endpoint = service.endpoint,
                                    service = service,
                                ),
                            method = if (service.jellyfinLoginEnabled) SeerrLoginMethod.Jellyfin else SeerrLoginMethod.Local,
                        )
                    }
                }
            }
    }

    private fun login(intent: SeerrIntent.Login) {
        val captured = token ?: return
        val connection = state.value.connection
        val service = connection.connection.service ?: return
        val allowed = if (connection.method == SeerrLoginMethod.Jellyfin) service.jellyfinLoginEnabled else service.localLoginEnabled
        if (!allowed || connection.busy || intent.identifier.isBlank() || intent.password.isEmpty()) return
        authToken = captured
        this.connection { it.copy(authenticating = true) }
        manage { connect(captured, connection.method, intent.identifier.trim(), intent.password) }
    }

    private fun quickConnect() {
        val captured = token ?: return
        if (state.value.connection.busy ||
            state.value.connection.connection.service
                ?.quickConnectSupported != true
        ) {
            return
        }
        cancelReads()
        managementJob?.cancel()
        val generation = ++managementGeneration
        authToken = captured
        connection { it.copy(busy = true, authenticating = true, error = null, credentialRevision = it.credentialRevision + 1) }
        managementJob =
            viewModelScope.launch {
                var pollingToken: SeerrPublicationToken? = null
                publish(beginQuickConnect(captured), false, { generation == managementGeneration }, { error ->
                    connection { it.copy(busy = false, authenticating = false, error = error) }
                }) { code ->
                    pollingToken = token
                    authToken = token
                    connection { it.copy(quickConnect = code) }
                }
                val polling = pollingToken ?: return@launch
                publish(completeQuickConnect(polling), false, { generation == managementGeneration }, { error ->
                    connection { it.copy(busy = false, authenticating = false, quickConnect = null, error = error) }
                }, ::applyConnection)
            }
    }

    private fun cancelAuth() {
        val captured = authToken ?: return
        authToken = null
        managementJob?.cancel()
        val generation = ++managementGeneration
        connection { it.copy(busy = true, authenticating = false, quickConnect = null, credentialRevision = it.credentialRevision + 1) }
        // Cleanup must finish even when the retained ViewModel is being cleared.
        managementJob =
            viewModelScope.launch(NonCancellable) {
                val result = cancelAuthentication(captured)
                publish(result, false, {
                    generation == managementGeneration
                }, { error -> connection { it.copy(busy = false, error = error) } }, ::applyConnection)
            }
    }

    private fun loadFeed(more: Boolean = false) {
        val captured = token ?: return
        if (!catalogAllowed() || state.value.section == SeerrSection.Connection || state.value.detail != null) return
        cancelList()
        val generation = listGeneration
        val section = state.value.section
        val previous = state.value.feed
        val catalog = if (section == SeerrSection.Search) SeerrCatalog.Search else state.value.catalog
        val query = if (section == SeerrSection.Search) state.value.submittedQuery else ""
        if (section == SeerrSection.Search && query.isBlank()) return
        val page = if (more) previous.page + 1 else 1
        reduce(SeerrChange.Feed(section, previous.copy(loading = true, error = null)))
        listJob =
            viewModelScope.launch {
                val current = {
                    generation == listGeneration &&
                        state.value.section == section &&
                        state.value.detail == null &&
                        (section != SeerrSection.Search || state.value.submittedQuery == query) &&
                        (section != SeerrSection.Discover || state.value.catalog == catalog)
                }
                val failed: (
                    SeerrError,
                ) -> Unit = { error -> reduce(SeerrChange.Feed(section, state.value.feed.copy(loading = false, error = error))) }
                if (section == SeerrSection.Requests) {
                    publish(getRequests(captured, if (more) previous.nextSkip else 0), true, current, failed) { value ->
                        val retained = if (more) state.value.feed else SeerrFeedUi(resetRevision = previous.resetRevision + 1)
                        val rows = (retained.rawRows + value.items.map { SeerrRow(it.media, it.request) }).distinctBy { it.id }
                        if (!more) reduce(SeerrChange.Focus(rows.firstOrNull()?.id))
                        reduce(
                            SeerrChange.Feed(
                                section,
                                retained.copy(
                                    rawRows = rows,
                                    rows = visibleRows(rows),
                                    loading = false,
                                    error = null,
                                    loaded = true,
                                    page = value.page,
                                    totalPages = value.totalPages,
                                    nextSkip = value.nextSkip,
                                ),
                            ),
                        )
                    }
                } else {
                    publish(getCatalog(captured, catalog, page, query), true, current, failed) { value ->
                        val retained = if (more) state.value.feed else SeerrFeedUi(resetRevision = previous.resetRevision + 1)
                        val rows = (retained.rawRows + value.items.map { SeerrRow(it) }).distinctBy { it.id }
                        if (!more) reduce(SeerrChange.Focus(rows.firstOrNull()?.id))
                        reduce(
                            SeerrChange.Feed(
                                section,
                                retained.copy(
                                    rawRows = rows,
                                    rows = visibleRows(rows),
                                    loading = false,
                                    error = null,
                                    loaded = true,
                                    page = value.page,
                                    totalPages = value.totalPages,
                                ),
                            ),
                        )
                    }
                }
            }
    }

    private fun openDetail(row: SeerrRow) {
        if (!catalogAllowed() ||
            state.value.detail != null ||
            state.value.feed.rows
                .none { it.id == row.id && it.media.key == row.media.key }
        ) {
            return
        }
        history.clear()
        cancelReads()
        reduce(SeerrChange.Feed(state.value.section, state.value.feed.copy(selectedId = row.id)))
        reduce(SeerrChange.Focus(null))
        reduce(SeerrChange.Detail(SeerrDetailUi(row.media.key, entryId = ++entrySequence)))
        loadDetail(row.media.key)
    }

    private fun loadDetail(
        key: SeerrMediaKey,
        retainedError: SeerrError? = null,
    ) {
        val captured = token ?: return
        if (!catalogAllowed() || state.value.detail?.submitting == true) return
        cancelReads()
        val generation = detailGeneration
        updateDetail { it.copy(loading = true, admissionFresh = false, error = retainedError, mutation = SeerrMutation.None) }
        detailJob =
            viewModelScope.launch {
                publish(getDetail(captured, key), true, { generation == detailGeneration && state.value.detail?.key == key }, { error ->
                    updateDetail { it.copy(loading = false, admissionFresh = false, error = error) }
                }) { detail ->
                    updateDetail {
                        it.copy(
                            detail = detail,
                            loading = false,
                            error = retainedError,
                            admissionFresh = detail.media.key == key,
                            uncertain = detail.reconciliationRequired || state.value.connection.connection.unresolvedRequest != null,
                        )
                    }
                    val review = state.value.detail
                    if (review?.reviewVisible == true && review.reviewSeason == null) accept(SeerrIntent.ConfirmRequest)
                    if (state.value.detail
                            ?.related
                            ?.phase == SeerrRelatedPhase.NotAttempted
                    ) {
                        loadRelated()
                    }
                }
            }
    }

    private fun back() {
        if (state.value.section == SeerrSection.Connection && state.value.detail == null) {
            accept(SeerrIntent.SelectSection(SeerrSection.Discover))
            return
        }
        if (state.value.connection.authenticating) {
            cancelAuth()
            return
        }
        if (state.value.person != null) {
            cancelReads()
            reduce(SeerrChange.Person(null))
            val parent = state.value.detail ?: return
            reduce(SeerrChange.Focus(parent.focusedId))
            loadDetail(parent.key)
            return
        }
        val detail = state.value.detail ?: return
        if (detail.submitting) return
        if (detail.qualityPickerVisible) {
            updateDetail { it.copy(qualityPickerVisible = false, focusedId = "quality", focusRevision = it.focusRevision + 1) }
        } else if (detail.libraryPickerVisible) {
            updateDetail { it.copy(libraryPickerVisible = false, focusedId = "library", focusRevision = it.focusRevision + 1) }
        } else if (detail.reviewVisible) {
            val target =
                detail.reviewSeason?.let { number ->
                    val season =
                        detail.seasons.firstOrNull { it.season.number == number }
                            ?: detail.seasons.firstOrNull { it.season.number > number }
                            ?: detail.seasons.firstOrNull()
                    season?.let { "season:${it.season.number}" }
                } ?: "request"
            updateDetail {
                it.copy(
                    mutation = SeerrMutation.None,
                    reviewVisible = false,
                    reviewSeason = null,
                    focusedId = target,
                    focusRevision = it.focusRevision + 1,
                )
            }
            reduce(SeerrChange.Focus(target))
        } else {
            cancelReads()
            val parent = history.removeLastOrNull()
            if (parent == null) {
                reduce(SeerrChange.Detail(null))
                reduce(SeerrChange.Focus(state.value.feed.selectedId))
            } else {
                reduce(SeerrChange.Detail(parent.detail.copy(admissionFresh = false, loading = false)))
                reduce(SeerrChange.Person(parent.person))
                reduce(SeerrChange.Focus(parent.detail.focusedId))
                if (parent.person == null) loadDetail(parent.detail.key)
            }
        }
    }

    private fun submit() {
        val captured = token ?: return
        val detail = state.value.detail ?: return
        val intent = detail.confirmation ?: return
        if (!catalogAllowed() || !detail.reviewCanSubmit || detail.submitting || mutationJob?.isActive == true) return
        if (intent != SeerrRequestIntent(detail.key, detail.quality, detail.selectedSeasonNumbers)) return
        cancelRelated()
        val generation = detailGeneration
        cancelLibrary()
        updateDetail {
            it.copy(
                mutation = SeerrMutation.Submitting(intent, generation),
                admissionFresh = false,
                error = null,
                notice = null,
            )
        }
        mutationJob =
            viewModelScope.launch {
                publish(
                    requestMedia(
                        captured,
                        intent,
                    ),
                    true,
                    { generation == detailGeneration && state.value.detail?.key == intent.key },
                    { error ->
                        updateDetail {
                            it.copy(
                                admissionFresh = false,
                                mutation = SeerrMutation.None,
                                reviewVisible = true,
                                error = error,
                                uncertain =
                                    error == SeerrError.ReconciliationRequired || it.uncertain,
                            )
                        }
                    },
                ) {
                    updateDetail {
                        it.copy(
                            mutation = SeerrMutation.None,
                            reviewVisible = false,
                            reviewSeason = null,
                            notice = SeerrNotice.Created,
                        )
                    }
                    // Reads after proven creation are safe; the POST itself is never retried.
                }
                if (generation == detailGeneration) {
                    if (state.value.detail?.notice == SeerrNotice.Created) {
                        loadDetail(intent.key)
                    } else if (state.value.detail?.error == SeerrError.NoSeasonsAvailable) {
                        loadDetail(intent.key, SeerrError.NoSeasonsAvailable)
                    }
                }
            }
    }

    private fun reconcile() {
        val captured = token ?: return
        if (!catalogAllowed() ||
            state.value.detail == null ||
            state.value.detail?.loading == true ||
            state.value.detail?.reconciling == true ||
            state.value.detail?.submitting == true
        ) {
            return
        }
        cancelReads()
        val generation = detailGeneration
        updateDetail { it.copy(mutation = SeerrMutation.Reconciling(generation), admissionFresh = false, error = null) }
        detailJob =
            viewModelScope.launch {
                var reconciled = false
                publish(reconcileRequest(captured), true, { generation == detailGeneration }, { error ->
                    updateDetail { it.copy(mutation = SeerrMutation.None, admissionFresh = false, error = error, uncertain = true) }
                }) { detail ->
                    reconciled = !detail.reconciliationRequired
                    if (reconciled) connection { it.copy(connection = it.connection.copy(unresolvedRequest = null)) }
                    updateDetail {
                        // Reconciliation can return another title's unresolved request; never relabel it.
                        it.copy(
                            detail = if (it.key == detail.media.key) detail else it.detail,
                            admissionFresh = reconciled && it.key == detail.media.key,
                            uncertain = !reconciled,
                            mutation = SeerrMutation.None,
                            notice = if (reconciled) SeerrNotice.Reconciled else null,
                        )
                    }
                }
                if (reconciled && generation == detailGeneration) {
                    state.value.detail
                        ?.key
                        ?.let { loadDetail(it) }
                }
            }
    }

    private fun openLibrary(quality: SeerrQuality) {
        val captured = token ?: return
        val detail = state.value.detail ?: return
        if (!catalogAllowed() ||
            quality !in detail.libraryQualities ||
            detail.resolvingLibrary ||
            detail.submitting ||
            detail.loading ||
            detail.reconciling
        ) {
            return
        }
        cancelRelated()
        cancelLibrary()
        val generation = detailGeneration
        val effectId = effectSequence
        updateDetail { it.copy(resolvingLibrary = true, libraryPickerVisible = false, error = if (it.admissionFresh) null else it.error) }
        libraryJob =
            viewModelScope.launch {
                publish(resolveLibraryItem(captured, detail.key, quality), true, {
                    effectId == effectSequence &&
                        generation == detailGeneration &&
                        state.value.detail?.key == detail.key
                }, { error ->
                    updateDetail { it.copy(resolvingLibrary = false, error = error) }
                }) { item ->
                    val effect = SeerrLibraryEffect(effectId, token ?: captured, generation, item)
                    pendingEffect = effect.id
                    if (!libraryChannel.trySend(effect).isSuccess) {
                        pendingEffect = null
                        updateDetail { it.copy(resolvingLibrary = false) }
                    }
                }
            }
    }

    private fun visibleRows(rows: List<SeerrRow>): List<SeerrRow> =
        if (state.value.section == SeerrSection.Discover && state.value.catalog != SeerrCatalog.Search && state.value.hideAvailable) {
            rows.filterNot { it.media.isFullyAvailable() }
        } else {
            rows
        }

    private fun cancelRelated() {
        relatedRevision++
        relatedJob?.cancel()
        relatedJob = null
        updateDetail {
            if (it.related.phase ==
                SeerrRelatedPhase.Loading
            ) {
                it.copy(related = it.related.copy(phase = SeerrRelatedPhase.Interrupted))
            } else {
                it
            }
        }
    }

    private fun loadRelated() {
        val captured = token ?: return
        val current = state.value.detail ?: return
        if (!visible ||
            pendingEffect != null ||
            !catalogAllowed() ||
            !current.relatedEnabled ||
            current.related.phase == SeerrRelatedPhase.Loading ||
            relatedJob?.isActive == true
        ) {
            return
        }
        val entry = current.entryId
        val revision = ++relatedRevision
        updateDetail { it.copy(related = it.related.copy(phase = SeerrRelatedPhase.Loading)) }
        relatedJob =
            viewModelScope.launch {
                val owns = { revision == relatedRevision && state.value.detail?.entryId == entry && token == captured }
                publish(getSimilar(captured, current.key), true, owns, {
                    updateDetail { it.copy(related = it.related.copy(phase = SeerrRelatedPhase.Error)) }
                }) { page ->
                    updateDetail {
                        it.copy(
                            related =
                                it.related.copy(
                                    phase = SeerrRelatedPhase.Ready,
                                    rows =
                                        page.items.map { media ->
                                            SeerrRow(media)
                                        },
                                ),
                        )
                    }
                }
            }
    }

    private fun openSimilar(id: String) {
        val current = state.value.detail ?: return
        if (!catalogAllowed() || !current.relatedEnabled) return
        val row = current.related.rows.firstOrNull { it.id == id } ?: return
        cancelReads()
        val snapshot = state.value.detail ?: return
        history.add(HistoryEntry(snapshot.copy(mutation = SeerrMutation.None, notice = null, admissionFresh = false)))
        reduce(SeerrChange.Detail(SeerrDetailUi(row.media.key, entryId = ++entrySequence)))
        reduce(SeerrChange.Focus(null))
        loadDetail(row.media.key)
    }

    private fun openPerson(id: Int) {
        val detail = state.value.detail ?: return
        if (!catalogAllowed() || !detail.relatedEnabled || detail.detail?.cast?.none { it.id == id } != false) return
        cancelReads()
        updateDetail { it.copy(admissionFresh = false) }
        reduce(SeerrChange.Person(SeerrPersonUi(id, ++entrySequence)))
        loadPerson()
    }

    private fun loadPerson() {
        val captured = token ?: return
        val person = state.value.person ?: return
        if (!catalogAllowed()) return
        cancelReads()
        val generation = detailGeneration
        reduce(SeerrChange.Person(person.copy(loading = true, error = null, presentation = PersonUiState.Loading)))
        detailJob =
            viewModelScope.launch {
                publish(
                    getPerson(captured, person.id),
                    true,
                    { generation == detailGeneration && state.value.person?.entryId == person.entryId },
                    { error ->
                        state.value.person?.let {
                            reduce(
                                SeerrChange.Person(it.copy(loading = false, error = error, presentation = PersonUiState.Error())),
                            )
                        }
                    },
                ) { value ->
                    state.value.person?.let {
                        val movies = value.movies.map { media -> SeerrRow(media) }
                        val series = value.series.map { media -> SeerrRow(media) }
                        reduce(
                            SeerrChange.Person(
                                it.copy(
                                    person = value,
                                    movies = movies,
                                    series = series,
                                    credits = (movies + series).associate { row -> row.id to row.media },
                                    presentation =
                                        PersonUiState.Content(
                                            movies.map { row -> row.card },
                                            series.map { row -> row.card },
                                            PersonHeaderUi(
                                                "tmdb:${value.tmdbId}",
                                                value.name,
                                                value.biography,
                                                seerrArtworkUrl(value.profileUrl),
                                                null,
                                            ),
                                        ),
                                    loading = false,
                                    error = null,
                                ),
                            ),
                        )
                    }
                }
            }
    }

    private fun openCredit(id: String) {
        val person = state.value.person ?: return
        val parent = state.value.detail ?: return
        if (!catalogAllowed() || person.loading || person.error != null) return
        val row = (person.movies + person.series).firstOrNull { it.id == id } ?: return
        cancelReads()
        history.add(HistoryEntry(parent, person))
        reduce(SeerrChange.Person(null))
        reduce(SeerrChange.Detail(SeerrDetailUi(row.media.key, entryId = ++entrySequence)))
        reduce(SeerrChange.Focus(null))
        loadDetail(row.media.key)
    }

    override fun onCleared() {
        cancelRelated()
        history.clear()
        cancelLibrary()
        cancelAuth()
        libraryChannel.close()
        super.onCleared()
    }
}
