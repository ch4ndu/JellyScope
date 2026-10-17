// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.DownloadAndInstallOpenSubtitleAction
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import com.jellyscope.core.domain.usecase.SearchOpenSubtitlesUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenSubtitleSearchUiState(
    val loading: Boolean = true,
    val results: List<OpenSubtitleSearchResult> = emptyList(),
    val downloadingFileId: String? = null,
    val error: Boolean = false,
    val quotaRemaining: Int? = null,
    val quotaResetTime: String? = null,
)

internal class DetailSubtitleSelectionOwner

internal data class DetailSubtitleSelectionToken(
    val owner: DetailSubtitleSelectionOwner,
    val revision: Int,
)

internal data class InstalledSubtitleAcknowledgement(
    val asset: LocalSubtitleAsset,
    val selectionToken: DetailSubtitleSelectionToken,
)

class OpenSubtitleSearchViewModel(
    private val request: OpenSubtitleSearchRequest,
    private val searchOpenSubtitlesUseCase: SearchOpenSubtitlesUseCase,
    private val downloadAndInstallOpenSubtitleAction: DownloadAndInstallOpenSubtitleAction,
) : ViewModel() {
    private val _state = MutableStateFlow(OpenSubtitleSearchUiState())
    val state = _state.asStateFlow()
    private val installedAcknowledgementEvents = Channel<InstalledSubtitleAcknowledgement>(Channel.BUFFERED)
    internal val installedAcknowledgements = installedAcknowledgementEvents.receiveAsFlow()
    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    init {
        search()
    }

    fun search() {
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                _state.update { it.copy(loading = true, error = false) }
                try {
                    val results = searchOpenSubtitlesUseCase(request)
                    _state.update { it.copy(loading = false, results = results) }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    openSubtitleLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "search",
                            event = "failed",
                            operation = DiagnosticOperation.OpenSubtitleSearch,
                            throwable = exception,
                        )
                    }
                    _state.update { it.copy(loading = false, error = true) }
                }
            }
    }

    internal fun install(
        result: OpenSubtitleSearchResult,
        selectionToken: DetailSubtitleSelectionToken,
    ) {
        if (!result.selectable || downloadJob?.isActive == true) return
        downloadJob =
            viewModelScope.launch {
                _state.update { it.copy(downloadingFileId = result.fileId, error = false) }
                try {
                    val installed = downloadAndInstallOpenSubtitleAction(request.context, result)
                    _state.update {
                        it.copy(
                            downloadingFileId = null,
                            quotaRemaining = installed.quotaRemaining,
                            quotaResetTime = installed.quotaResetTime,
                        )
                    }
                    if (installed.selectionApplied) {
                        installedAcknowledgementEvents.send(
                            InstalledSubtitleAcknowledgement(
                                asset = installed.asset,
                                selectionToken = selectionToken,
                            ),
                        )
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    openSubtitleLogger.w {
                        formatSafeFailureDiagnostic(
                            stage = "download-install",
                            event = "failed",
                            operation = DiagnosticOperation.OpenSubtitleDownload,
                            throwable = exception,
                        )
                    }
                    _state.update { it.copy(downloadingFileId = null, error = true) }
                }
            }
    }
}

private val openSubtitleLogger = diagnosticLogger(DiagnosticTag.OpenSubtitles)
