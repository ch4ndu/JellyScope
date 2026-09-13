// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadState
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.usecase.GetKidsCatalogueUseCase
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.domain.usecase.ObserveDownloadsUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.toMediaCardUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface KidsCatalogueState {
    data object Loading : KidsCatalogueState

    data object Empty : KidsCatalogueState

    data object Error : KidsCatalogueState

    data object Content : KidsCatalogueState
}

sealed interface KidsOfflineState {
    data object Idle : KidsOfflineState

    data object Error : KidsOfflineState
}

data class KidsWatchItem(
    val card: MediaCardUi,
    val offlineDownloadId: DownloadId? = null,
)

data class KidsWatchUiState(
    val catalogueState: KidsCatalogueState = KidsCatalogueState.Loading,
    val offlineState: KidsOfflineState = KidsOfflineState.Idle,
    val selectedItemId: String,
    val recommendations: List<KidsWatchItem> = emptyList(),
    val offlineFallback: List<KidsWatchItem> = emptyList(),
    val hasMissingOfflineArtifacts: Boolean = false,
)

/** Account- and route-bound recommendation state for a single Kids watch page. */
class KidsWatchViewModel(
    private val session: Session,
    private val boundaryEpoch: Long,
    initialItemId: String,
    private val initialOfflineDownloadId: DownloadId?,
    private val getKidsCatalogueUseCase: GetKidsCatalogueUseCase,
    private val observeDownloadsUseCase: ObserveDownloadsUseCase,
    private val getOfflinePlaybackPlanUseCase: GetOfflinePlaybackPlanUseCase,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val accountIdentity: AccountIdentity = session.accountIdentity()
    private val _state = MutableStateFlow(KidsWatchUiState(selectedItemId = initialItemId))
    val state: StateFlow<KidsWatchUiState> = _state.asStateFlow()

    private var remoteOrder: List<KidsWatchItem> = emptyList()
    private val downloadQualifications = mutableMapOf<KidsDownloadAttempt, KidsDownloadQualification>()
    private val suppressedDownloadAttempts = mutableSetOf<KidsDownloadAttempt>()
    private var latestDownloadRecords: List<DownloadRecord> = emptyList()
    private var offlineOrderIds: List<String> = emptyList()
    private var offlinePool: List<KidsWatchItem> = emptyList()
    private var catalogueGeneration = 0L
    private var projectionGeneration = 0L
    private var offlineRefreshGeneration = 0L
    private var catalogueJob: Job? = null
    private var projectionJob: Job? = null
    private var downloadsJob: Job? = null
    private var offlineRefreshJob: Job? = null
    private val imageUrlBuilder = JellyfinImageUrlBuilder()

    init {
        if (session.enableContentDownloading) {
            observeDownloads()
        }
        if (initialOfflineDownloadId == null) {
            loadCatalogue()
        } else {
            _state.update { current -> current.copy(catalogueState = KidsCatalogueState.Empty) }
        }
    }

    fun retryCatalogue() {
        retryOfflineQualification()
        if (initialOfflineDownloadId == null) {
            loadCatalogue()
        }
    }

    fun retryOfflineQualification() {
        offlineRefreshGeneration += 1L
        suppressedDownloadAttempts.clear()
        downloadQualifications.clear()
        _state.update { current -> current.copy(hasMissingOfflineArtifacts = false) }
        if (state.value.offlineState == KidsOfflineState.Error) {
            downloadsJob?.cancel()
            observeDownloads()
        }
        if (latestDownloadRecords.isNotEmpty()) {
            offlineRefreshJob?.cancel()
            val records = latestDownloadRecords
            offlineRefreshJob = viewModelScope.launch { updateOfflinePool(records) }
        }
    }

    fun selectAccepted(itemId: String) {
        if (itemId.isBlank()) return
        _state.update { current -> current.copy(selectedItemId = itemId) }
        rebuildProjection()
    }

    fun removeOfflineTarget(downloadId: DownloadId) {
        offlineRefreshGeneration += 1L
        offlineRefreshJob?.cancel()
        val attempts =
            latestDownloadRecords
                .asSequence()
                .filter { record -> record.downloadId == downloadId }
                .map { record -> KidsDownloadAttempt(record.downloadId, record.attemptGeneration) }
                .toSet()
        suppressedDownloadAttempts += attempts
        attempts.forEach { attempt -> downloadQualifications[attempt] = KidsDownloadQualification.Missing }
        offlinePool = offlinePool.filterNot { item -> item.offlineDownloadId == downloadId }
        _state.update { current -> current.copy(hasMissingOfflineArtifacts = true) }
        rebuildProjection()
    }

    private fun loadCatalogue() {
        val generation = ++catalogueGeneration
        catalogueJob?.cancel()
        _state.update { current -> current.copy(catalogueState = KidsCatalogueState.Loading) }
        catalogueJob =
            viewModelScope.launch {
                val result =
                    try {
                        getKidsCatalogueUseCase(accountIdentity, boundaryEpoch)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (exception: Throwable) {
                        playerViewModelLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "kids-catalogue",
                                event = "failed",
                                operation = DiagnosticOperation.GetKidsCatalogue,
                                throwable = exception,
                            )
                        }
                        null
                    }
                if (generation != catalogueGeneration) return@launch
                result?.fold(
                    onSuccess = { items ->
                        val mappedAndShuffled =
                            withContext(workDispatcher) {
                                val mapped =
                                    items.map { item ->
                                        KidsWatchItem(
                                            item.toMediaCardUi(session, imageUrlBuilder).copy(
                                                runtimeText =
                                                    item.runtime
                                                        ?.inWholeMilliseconds
                                                        ?.takeIf { it > 0L }
                                                        ?.let(::formatDuration),
                                            ),
                                        )
                                    }
                                mapped.shuffled()
                            }
                        if (generation != catalogueGeneration) return@fold
                        remoteOrder = mappedAndShuffled
                        _state.update { current ->
                            current.copy(
                                catalogueState = if (remoteOrder.isEmpty()) KidsCatalogueState.Empty else KidsCatalogueState.Content,
                            )
                        }
                        rebuildProjection()
                    },
                    onFailure = { exception ->
                        playerViewModelLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "kids-catalogue",
                                event = "failed",
                                operation = DiagnosticOperation.GetKidsCatalogue,
                                throwable = exception,
                            )
                        }
                        remoteOrder = emptyList()
                        _state.update { current ->
                            current.copy(
                                catalogueState = KidsCatalogueState.Error,
                            )
                        }
                        rebuildProjection()
                    },
                ) ?: run {
                    if (generation != catalogueGeneration) return@launch
                    remoteOrder = emptyList()
                    _state.update { current ->
                        current.copy(catalogueState = KidsCatalogueState.Error)
                    }
                    rebuildProjection()
                }
            }
    }

    private fun observeDownloads() {
        downloadsJob =
            viewModelScope.launch {
                observeDownloadsUseCase(accountIdentity)
                    .catch { exception ->
                        if (exception is CancellationException) throw exception
                        playerViewModelLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "kids-download-observe",
                                event = "failed",
                                operation = DiagnosticOperation.DownloadObserve,
                                throwable = exception,
                            )
                        }
                        _state.update { current -> current.copy(offlineState = KidsOfflineState.Error) }
                    }.collect { records ->
                        latestDownloadRecords = records
                        offlineRefreshJob?.cancel()
                        offlineRefreshJob = viewModelScope.launch { updateOfflinePool(records) }
                    }
            }
    }

    private suspend fun updateOfflinePool(records: List<DownloadRecord>) {
        val refreshGeneration = ++offlineRefreshGeneration
        val completedAttempts =
            records
                .asSequence()
                .filter { record ->
                    record.businessKey.accountIdentity == accountIdentity &&
                        record.state == DownloadState.Completed &&
                        record.request.snapshot.itemKind in setOf(MediaKind.Movie, MediaKind.Episode)
                }.map { record -> KidsDownloadAttempt(record.downloadId, record.attemptGeneration) }
                .toSet()
        val suppressedAttempts = suppressedDownloadAttempts.toSet()
        val candidateRecords =
            withContext(workDispatcher) {
                records
                    .asSequence()
                    .filter { record ->
                        record.businessKey.accountIdentity == accountIdentity &&
                            record.state == DownloadState.Completed &&
                            record.request.snapshot.itemKind in setOf(MediaKind.Movie, MediaKind.Episode) &&
                            KidsDownloadAttempt(record.downloadId, record.attemptGeneration) !in suppressedAttempts
                    }.sortedByDescending { record -> record.updatedAtEpochMs }
                    .toList()
            }
        if (refreshGeneration != offlineRefreshGeneration) return
        downloadQualifications.keys.retainAll(completedAttempts)
        suppressedDownloadAttempts.retainAll(completedAttempts)

        candidateRecords.forEach { record ->
            val key = KidsDownloadAttempt(record.downloadId, record.attemptGeneration)
            if (key !in downloadQualifications) {
                val qualified =
                    try {
                        getOfflinePlaybackPlanUseCase(accountIdentity, record.downloadId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (exception: Throwable) {
                        playerViewModelLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "kids-offline-qualification",
                                event = "failed",
                                operation = DiagnosticOperation.DownloadLoad,
                                throwable = exception,
                            )
                        }
                        null
                    }
                downloadQualifications[key] =
                    if (qualified?.attemptGeneration == record.attemptGeneration) {
                        KidsDownloadQualification.Valid
                    } else {
                        KidsDownloadQualification.Missing
                    }
            }
            if (refreshGeneration != offlineRefreshGeneration) return
        }
        val qualificationSnapshot = downloadQualifications.toMap()
        val previousOrder = offlineOrderIds
        val projection =
            withContext(workDispatcher) {
                val newestValidByItemId = linkedMapOf<String, KidsWatchItem>()
                candidateRecords.forEach { record ->
                    val key = KidsDownloadAttempt(record.downloadId, record.attemptGeneration)
                    if (
                        qualificationSnapshot[key] == KidsDownloadQualification.Valid &&
                        record.businessKey.itemId !in newestValidByItemId
                    ) {
                        newestValidByItemId[record.businessKey.itemId] = record.toKidsWatchItem()
                    }
                }
                val retainedOrder = previousOrder.filter(newestValidByItemId::containsKey)
                val addedOrder = (newestValidByItemId.keys - retainedOrder.toSet()).shuffled()
                val order = retainedOrder + addedOrder
                KidsOfflineProjection(order, order.mapNotNull(newestValidByItemId::get))
            }
        if (refreshGeneration != offlineRefreshGeneration) return
        offlineOrderIds = projection.orderIds
        offlinePool = projection.items
        val hasMissingOfflineArtifacts =
            candidateRecords.any { record ->
                qualificationSnapshot[KidsDownloadAttempt(record.downloadId, record.attemptGeneration)] ==
                    KidsDownloadQualification.Missing
            } ||
                suppressedDownloadAttempts.any { attempt -> attempt in completedAttempts }
        _state.update { current ->
            current.copy(
                offlineState = KidsOfflineState.Idle,
                hasMissingOfflineArtifacts = hasMissingOfflineArtifacts,
            )
        }
        if (initialOfflineDownloadId != null) {
            _state.update { current ->
                current.copy(catalogueState = if (offlinePool.isEmpty()) KidsCatalogueState.Empty else KidsCatalogueState.Content)
            }
        }
        rebuildProjection()
    }

    private fun rebuildProjection() {
        projectionJob?.cancel()
        val generation = ++projectionGeneration
        val selectedId = state.value.selectedItemId
        val remoteOrderSnapshot = remoteOrder
        val offlineSnapshot = offlinePool
        projectionJob =
            viewModelScope.launch {
                val projection =
                    withContext(workDispatcher) {
                        projectKidsWatchItems(
                            selectedId = selectedId,
                            remoteOrder = remoteOrderSnapshot,
                            offlinePool = offlineSnapshot,
                            initialOfflineDownloadId = initialOfflineDownloadId,
                        )
                    }
                if (generation != projectionGeneration || selectedId != state.value.selectedItemId) return@launch
                _state.update { current ->
                    if (selectedId != current.selectedItemId) {
                        current
                    } else {
                        current.copy(
                            recommendations = projection.recommendations,
                            offlineFallback = projection.offlineFallback,
                        )
                    }
                }
            }
    }

    override fun onCleared() {
        catalogueJob?.cancel()
        projectionJob?.cancel()
        downloadsJob?.cancel()
        offlineRefreshJob?.cancel()
        super.onCleared()
    }
}

private data class KidsDownloadAttempt(
    val downloadId: DownloadId,
    val attemptGeneration: Long,
)

private enum class KidsDownloadQualification {
    Valid,
    Missing,
}

private data class KidsOfflineProjection(
    val orderIds: List<String>,
    val items: List<KidsWatchItem>,
)

private data class KidsWatchProjection(
    val recommendations: List<KidsWatchItem>,
    val offlineFallback: List<KidsWatchItem>,
)

private fun projectKidsWatchItems(
    selectedId: String,
    remoteOrder: List<KidsWatchItem>,
    offlinePool: List<KidsWatchItem>,
    initialOfflineDownloadId: DownloadId?,
): KidsWatchProjection {
    val recommendations =
        (if (initialOfflineDownloadId == null) remoteOrder else offlinePool)
            .filterNot { item -> item.card.id == selectedId }
    val offlineFallback = offlinePool.filterNot { item -> item.card.id == selectedId }
    return KidsWatchProjection(
        recommendations = recommendations,
        offlineFallback = offlineFallback,
    )
}

private fun DownloadRecord.toKidsWatchItem(): KidsWatchItem {
    val snapshot = request.snapshot
    val subtitle = listOfNotNull(snapshot.seriesName, snapshot.episodeLabel).joinToString(" ").ifBlank { null }
    val kind =
        when (snapshot.itemKind) {
            MediaKind.Movie -> MediaCardKind.Movie
            MediaKind.Episode -> MediaCardKind.Episode
            MediaKind.Series -> MediaCardKind.Series
            MediaKind.Other -> MediaCardKind.Other
        }
    return KidsWatchItem(
        card =
            MediaCardUi(
                id = businessKey.itemId,
                title = snapshot.title,
                subtitle = subtitle,
                progressFraction = null,
                watched = localWatched,
                unplayedCount = null,
                imageUrl = null,
                kind = kind,
                episodeLabel = snapshot.episodeLabel,
                seriesName = snapshot.seriesName,
            ),
        offlineDownloadId = downloadId,
    )
}
