// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.OfflineArtworkRole
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.usecase.ReadDownloadArtworkUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvDownloadArtworkRequest(
    val session: Session,
    val downloadId: DownloadId,
    val attemptGeneration: Long,
    val role: OfflineArtworkRole,
)

/**
 * Per-view bridge for one saved download image. It never exposes a path or a
 * remote URL, and it is intentionally separate from the observed download rows.
 */
data class TvDownloadArtworkState(
    val imageBytes: ByteArray? = null,
    val isLoading: Boolean = true,
    val readGeneration: Long = 0L,
)

class TvDownloadArtworkPresenter(
    session: Session,
    private val downloadId: DownloadId,
    val attemptGeneration: Long,
    val role: OfflineArtworkRole,
    private val readDownloadArtwork: ReadDownloadArtworkUseCase,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val accountIdentity = session.accountIdentity()
    private val _state = MutableStateFlow(TvDownloadArtworkState())
    val state: StateFlow<TvDownloadArtworkState> = _state.asStateFlow()

    private var loadGeneration = 0L
    private var loadJob: Job? = null

    init {
        load()
    }

    fun watchState(onChange: (TvDownloadArtworkState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        val generation = ++loadGeneration
        loadJob?.cancel()
        _state.value = TvDownloadArtworkState(isLoading = true, readGeneration = generation)
        loadJob =
            scope.launch {
                val bytes =
                    try {
                        withContext(workDispatcher) {
                            readDownloadArtwork(
                                accountIdentity = accountIdentity,
                                downloadId = downloadId,
                                role = role,
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        tvDownloadArtworkLogger.w {
                            formatSafeFailureDiagnostic(
                                stage = "read-artwork",
                                event = "failed",
                                operation = DiagnosticOperation.DownloadLoad,
                                throwable = error,
                            )
                        }
                        null
                    }
                if (generation != loadGeneration) return@launch
                _state.value = TvDownloadArtworkState(imageBytes = bytes, isLoading = false, readGeneration = generation)
            }
    }

    /** Cancels a view that is no longer visible without retaining its image bytes. */
    fun cancel() {
        loadGeneration += 1L
        loadJob?.cancel()
        loadJob = null
        _state.value = TvDownloadArtworkState(isLoading = false, readGeneration = loadGeneration)
    }

    override fun close() {
        cancel()
        super.close()
    }
}

private val tvDownloadArtworkLogger = diagnosticLogger(DiagnosticTag.DownloadsViewModel)
