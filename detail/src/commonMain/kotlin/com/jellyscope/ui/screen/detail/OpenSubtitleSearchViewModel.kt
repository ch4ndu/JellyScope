// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.DownloadAndInstallOpenSubtitleAction
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

    internal fun accept(intent: OpenSubtitleSearchIntent) {
        when (intent) {
            OpenSubtitleSearchIntent.Search -> search()
            is OpenSubtitleSearchIntent.Install -> install(intent.result, intent.selectionToken)
        }
    }

    private fun reduce(change: OpenSubtitleSearchChange) {
        _state.update { current -> reduceOpenSubtitleSearch(current, change) }
    }

    init {
        search()
    }

    private fun search() {
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                reduce(OpenSubtitleSearchChange.SearchStarted)
                try {
                    val results = searchOpenSubtitlesUseCase(request)
                    reduce(OpenSubtitleSearchChange.SearchLoaded(results))
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
                    reduce(OpenSubtitleSearchChange.SearchFailed)
                }
            }
    }

    private fun install(
        result: OpenSubtitleSearchResult,
        selectionToken: DetailSubtitleSelectionToken,
    ) {
        if (!result.selectable || downloadJob?.isActive == true) return
        downloadJob =
            viewModelScope.launch {
                reduce(OpenSubtitleSearchChange.InstallStarted(result.fileId))
                try {
                    val installed = downloadAndInstallOpenSubtitleAction(request.context, result)
                    reduce(OpenSubtitleSearchChange.Installed(installed.quotaRemaining, installed.quotaResetTime))
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
                    reduce(OpenSubtitleSearchChange.InstallFailed)
                }
            }
    }
}

private val openSubtitleLogger = diagnosticLogger(DiagnosticTag.OpenSubtitles)
