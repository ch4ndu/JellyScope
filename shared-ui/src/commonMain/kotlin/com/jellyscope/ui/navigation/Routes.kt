// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent

object Routes {
    val Home = "home"
    val Discover = "discover"
    val LibraryRoot = "library-root"
    val Find = "find"
    val Downloads = "downloads"
    val Settings = "settings"
    val ItemIdArgument = "itemId"
    val SeriesIdArgument = "seriesId"
    val SeasonIdArgument = "seasonId"
    val CollectionIdArgument = "collectionId"
    val PersonIdArgument = "personId"
    val ParentIdArgument = "parentId"
    val TitleArgument = "title"
    val GenreArgument = "genre"
    val StudioIdArgument = "studioId"
    val RowArgument = "row"
    val StartTicksArgument = "startTicks"
    val MediaSourceIdArgument = "msId"
    val AudioStreamIndexArgument = "audioStreamIndex"
    val SubtitleStreamIndexArgument = "subtitleStreamIndex"
    val SubtitleAssetIdArgument = "subtitleAssetId"
    val QueueArgument = "queue"
    val QueueKeyArgument = "queueKey"
    val OfflineDownloadIdArgument = "offlineDownloadId"
    val DownloadIdArgument = "downloadId"
    val OfflineRestartArgument = "offlineRestart"
    val DownloadDetail = "download-detail/{$DownloadIdArgument}"
    val LibraryTypeArgument = "libraryType"
    val Detail = "detail/{$ItemIdArgument}"
    val SeriesGraph = "series-graph/{$SeriesIdArgument}"
    val Series = "$SeriesGraph/overview"
    val Season = "$SeriesGraph/season/{$SeasonIdArgument}"
    val Collection = "collection/{$CollectionIdArgument}?title={$TitleArgument}"
    val Person = "person/{$PersonIdArgument}"
    val Library = "library/{$ParentIdArgument}?title={$TitleArgument}&libraryType={$LibraryTypeArgument}"
    val FilteredLibrary =
        "library-filter?title={$TitleArgument}&genre={$GenreArgument}&studioId={$StudioIdArgument}" +
            "&parentId={$ParentIdArgument}&libraryType={$LibraryTypeArgument}"
    val Grid = "grid/{$RowArgument}"
    val ViewAll = Grid
    val Player =
        "player/{$ItemIdArgument}?startTicks={$StartTicksArgument}&msId={$MediaSourceIdArgument}" +
            "&audioStreamIndex={$AudioStreamIndexArgument}&subtitleStreamIndex={$SubtitleStreamIndexArgument}" +
            "&subtitleAssetId={$SubtitleAssetIdArgument}" +
            "&queue={$QueueArgument}&queueKey={$QueueKeyArgument}" +
            "&offlineDownloadId={$OfflineDownloadIdArgument}&offlineRestart={$OfflineRestartArgument}"

    fun downloadDetail(downloadId: DownloadId) = "download-detail/${downloadId.value.encodeRouteValue()}"

    fun detail(itemId: String) = "detail/$itemId"

    fun seriesGraph(seriesId: String) = "series-graph/${seriesId.encodeRouteValue()}"

    fun series(seriesId: String) = "${seriesGraph(seriesId)}/overview"

    fun season(
        seriesId: String,
        seasonId: String,
    ) = "${seriesGraph(seriesId)}/season/${seasonId.encodeRouteValue()}"

    fun collection(
        collectionId: String,
        title: String? = null,
    ): String =
        buildString {
            append("collection/")
            append(collectionId.encodeRouteValue())
            title
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value ->
                    append("?title=")
                    append(value.encodeRouteValue())
                }
        }

    fun person(personId: String) = "person/${personId.encodeRouteValue()}"

    fun library(
        parentId: String,
        title: String? = null,
        collectionType: LibraryCollectionType = LibraryCollectionType.Other,
    ): String =
        buildString {
            append("library/")
            append(parentId.encodeRouteValue())
            title
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value ->
                    append("?title=")
                    append(value.encodeRouteValue())
                }
            append(if (title.isNullOrBlank()) "?" else "&")
            append("libraryType=")
            append(collectionType.name)
        }

    fun filteredLibraryForGenre(
        genre: String,
        title: String = genre,
        parentId: String? = null,
        collectionType: LibraryCollectionType = LibraryCollectionType.Other,
    ): String =
        buildString {
            append("library-filter?")
            title
                .takeIf { value -> value.isNotBlank() }
                ?.let { value ->
                    append("title=")
                    append(value.encodeRouteValue())
                    append("&")
                }
            append("genre=")
            append(genre.encodeRouteValue())
            parentId?.takeIf { value -> value.isNotBlank() }?.let { value ->
                append("&parentId=")
                append(value.encodeRouteValue())
                append("&libraryType=")
                append(collectionType.name)
            }
        }

    fun filteredLibraryForStudio(
        studioId: String,
        title: String,
    ): String =
        buildString {
            append("library-filter?")
            title
                .takeIf { value -> value.isNotBlank() }
                ?.let { value ->
                    append("title=")
                    append(value.encodeRouteValue())
                    append("&")
                }
            append("studioId=")
            append(studioId.encodeRouteValue())
        }

    fun grid(rowName: String) = "grid/${rowName.encodeRouteValue()}"

    fun viewAll(rowName: String) = grid(rowName)

    fun player(
        itemId: String,
        startTicks: Long,
        mediaSourceId: String?,
        initialAudioStreamIndex: Int? = null,
        initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
        queue: List<String> = emptyList(),
        queueKey: String? = null,
        offlineDownloadId: DownloadId? = null,
        offlineRestartFromBeginning: Boolean = false,
    ): String =
        buildString {
            append("player/")
            append(itemId.encodeRouteValue())
            append("?startTicks=")
            append(startTicks)
            mediaSourceId
                ?.takeIf { id -> id.isNotBlank() }
                ?.let { id ->
                    append("&msId=")
                    append(id.encodeRouteValue())
                }
            initialAudioStreamIndex?.let { streamIndex ->
                append("&audioStreamIndex=")
                append(streamIndex)
            }
            when (initialSubtitleSelection) {
                is SubtitleSelectionIntent.LocalAsset -> {
                    append("&subtitleAssetId=")
                    append(initialSubtitleSelection.assetId.encodeRouteValue())
                }
                else ->
                    initialSubtitleSelection.wireIndexOrNull()?.let { streamIndex ->
                        append("&subtitleStreamIndex=")
                        append(streamIndex)
                    }
            }
            queue
                .filter { id -> id.isNotBlank() }
                .takeIf { ids -> ids.size > 1 }
                ?.let { ids ->
                    append("&queue=")
                    append(ids.joinToString(separator = ",") { id -> id.encodeRouteValue() })
                }
            queueKey
                ?.takeIf { key -> key.isNotBlank() }
                ?.let { key ->
                    append("&queueKey=")
                    append(key.encodeRouteValue())
                }
            offlineDownloadId?.let { downloadId ->
                append("&offlineDownloadId=")
                append(downloadId.value.encodeRouteValue())
                if (offlineRestartFromBeginning) append("&offlineRestart=true")
            }
        }

    fun decodeRouteValue(value: String): String = value.decodeRouteValue()

    fun offlineDownloadId(value: String?): DownloadId? =
        value
            ?.let(::decodeRouteValue)
            ?.takeIf(String::isNotBlank)
            ?.let { decoded -> runCatching { DownloadId(decoded) }.getOrNull() }
}

private fun String.encodeRouteValue(): String =
    buildString {
        this@encodeRouteValue.forEach { char ->
            append(
                when (char) {
                    ' ' -> "%20"
                    '/' -> "%2F"
                    '?' -> "%3F"
                    '&' -> "%26"
                    '=' -> "%3D"
                    '%' -> "%25"
                    '#' -> "%23"
                    ',' -> "%2C"
                    else -> char.toString()
                },
            )
        }
    }

private fun String.decodeRouteValue(): String =
    replace("%2C", ",")
        .replace("%23", "#")
        .replace("%25", "%")
        .replace("%3D", "=")
        .replace("%26", "&")
        .replace("%3F", "?")
        .replace("%2F", "/")
        .replace("%20", " ")
