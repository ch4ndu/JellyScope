// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal sealed interface TvSubtitlesChange {
    data class Available(
        val language: String,
        val selectedAssetId: String?,
    ) : TvSubtitlesChange

    data object Unsupported : TvSubtitlesChange

    data object Failed : TvSubtitlesChange

    data class LanguageSelected(
        val language: String,
    ) : TvSubtitlesChange

    data object SearchStarted : TvSubtitlesChange

    data class SearchLoaded(
        val results: List<TvOpenSubtitleResult>,
    ) : TvSubtitlesChange

    data object SearchFailed : TvSubtitlesChange

    data class InstallStarted(
        val fileId: String,
    ) : TvSubtitlesChange

    data class Installed(
        val selectionApplied: Boolean,
        val assetId: String,
        val quotaRemaining: Int?,
        val quotaResetTime: String?,
    ) : TvSubtitlesChange

    data object InstallFailed : TvSubtitlesChange

    data object SelectionStarted : TvSubtitlesChange

    data class SelectionFinished(
        val succeeded: Boolean,
        val assetId: String?,
    ) : TvSubtitlesChange

    data class DeleteStarted(
        val assetId: String,
    ) : TvSubtitlesChange

    data class DeleteFinished(
        val succeeded: Boolean,
        val clearedSelection: Boolean,
        val assetId: String,
    ) : TvSubtitlesChange

    data class SyncStarted(
        val assetId: String,
    ) : TvSubtitlesChange

    data class SyncFinished(
        val succeeded: Boolean,
        val assetId: String,
    ) : TvSubtitlesChange

    data class AssetsLoaded(
        val rows: List<TvLocalSubtitle>,
    ) : TvSubtitlesChange

    data class Reset(
        val selectionRevision: Long,
    ) : TvSubtitlesChange
}

internal fun reduceTvSubtitles(
    current: TvSubtitlesState,
    change: TvSubtitlesChange,
): TvSubtitlesState =
    when (change) {
        is TvSubtitlesChange.Available ->
            with(change) {
                current.copy(
                    availability = TvSubtitlesAvailability.Available,
                    selectedLanguage = language,
                    selectedLocalAssetId = selectedAssetId,
                )
            }
        TvSubtitlesChange.Unsupported ->
            run {
                current.copy(availability = TvSubtitlesAvailability.Unsupported)
            }
        TvSubtitlesChange.Failed ->
            run {
                current.copy(availability = TvSubtitlesAvailability.Failed)
            }
        is TvSubtitlesChange.LanguageSelected ->
            with(change) {
                current.copy(selectedLanguage = language)
            }
        TvSubtitlesChange.SearchStarted ->
            run {
                current.copy(
                    isSearching = true,
                    searchResults = emptyList(),
                    searchError = false,
                    installationError = false,
                )
            }
        is TvSubtitlesChange.SearchLoaded ->
            with(change) {
                current.copy(
                    isSearching = false,
                    searchResults = results,
                    searchError = false,
                )
            }
        TvSubtitlesChange.SearchFailed ->
            run {
                current.copy(
                    isSearching = false,
                    searchResults = emptyList(),
                    searchError = true,
                )
            }
        is TvSubtitlesChange.InstallStarted ->
            with(change) {
                current.copy(
                    installingFileId = fileId,
                    installationError = false,
                )
            }
        is TvSubtitlesChange.Installed ->
            with(change) {
                current.copy(
                    localSubtitles =
                        current.localSubtitles.map { asset ->
                            asset.copy(selected = selectionApplied && asset.id == assetId)
                        },
                    selectedLocalAssetId =
                        if (selectionApplied) assetId else current.selectedLocalAssetId,
                    installingFileId = null,
                    installationError = false,
                    quotaRemaining = quotaRemaining,
                    quotaResetTime = quotaResetTime,
                    selectionRevision =
                        if (selectionApplied) current.selectionRevision + 1L else current.selectionRevision,
                    selectionAssetId =
                        if (selectionApplied) assetId else current.selectionAssetId,
                )
            }
        TvSubtitlesChange.InstallFailed ->
            run {
                current.copy(
                    installingFileId = null,
                    installationError = true,
                )
            }
        TvSubtitlesChange.SelectionStarted ->
            run {
                current.copy(
                    isSelecting = true,
                    selectionError = false,
                )
            }
        is TvSubtitlesChange.SelectionFinished ->
            with(change) {
                current.copy(
                    localSubtitles =
                        if (succeeded) {
                            current.localSubtitles.map { asset -> asset.copy(selected = asset.id == assetId) }
                        } else {
                            current.localSubtitles
                        },
                    selectedLocalAssetId = if (succeeded) assetId else current.selectedLocalAssetId,
                    isSelecting = false,
                    selectionError = !succeeded,
                    selectionRevision = if (succeeded) current.selectionRevision + 1L else current.selectionRevision,
                    selectionAssetId = if (succeeded) assetId else current.selectionAssetId,
                )
            }
        is TvSubtitlesChange.DeleteStarted ->
            with(change) {
                current.copy(
                    deletingAssetId = assetId,
                    deleteErrorAssetId = null,
                )
            }
        is TvSubtitlesChange.DeleteFinished ->
            with(change) {
                current.copy(
                    localSubtitles = if (succeeded) current.localSubtitles.filterNot { it.id == assetId } else current.localSubtitles,
                    selectedLocalAssetId = if (clearedSelection) null else current.selectedLocalAssetId,
                    deletingAssetId = null,
                    deleteErrorAssetId = assetId.takeUnless { succeeded },
                    selectionRevision = if (clearedSelection) current.selectionRevision + 1L else current.selectionRevision,
                    selectionAssetId = if (clearedSelection) null else current.selectionAssetId,
                )
            }
        is TvSubtitlesChange.SyncStarted ->
            with(change) {
                current.copy(
                    syncingAssetId = assetId,
                    syncErrorAssetId = null,
                )
            }
        is TvSubtitlesChange.SyncFinished ->
            with(change) {
                current.copy(
                    syncingAssetId = null,
                    syncErrorAssetId = assetId.takeUnless { succeeded },
                )
            }
        is TvSubtitlesChange.AssetsLoaded ->
            with(change) {
                current.copy(localSubtitles = rows)
            }
        is TvSubtitlesChange.Reset -> TvSubtitlesState(selectionRevision = change.selectionRevision)
    }
