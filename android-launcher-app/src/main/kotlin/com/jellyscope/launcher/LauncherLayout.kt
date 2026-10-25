// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

internal const val MAX_LAYOUT_ROWS = 12

@Serializable
internal enum class LauncherAspect(
    val width: Int,
    val height: Int,
) {
    @SerialName("landscape")
    Landscape(156, 88),

    @SerialName("poster")
    Poster(105, 158),

    @SerialName("square")
    Square(160, 160),
}

@Serializable
internal enum class LauncherQuery {
    @SerialName("resume")
    Resume,

    @SerialName("nextUp")
    NextUp,

    @SerialName("latest")
    Latest,

    @SerialName("favorites")
    Favorites,

    @SerialName("library")
    Library,

    @SerialName("latestMovies")
    LatestMovies,

    @SerialName("latestEpisodes")
    LatestEpisodes,

    @SerialName("latestSeries")
    LatestSeries,
}

@Serializable
internal enum class LauncherTab {
    @SerialName("essentials")
    Essentials,

    @SerialName("jellyfin")
    Jellyfin,
    ;

    val focusKey: String get() = if (this == Essentials) "tab:essentials" else "tab:jellyfin"
}

@Serializable
internal data class LauncherLayoutRow(
    val id: String,
    val title: String,
    val visible: Boolean,
    val aspect: LauncherAspect,
    val query: LauncherQuery,
    val libraryId: String?,
    val limit: Int,
    val tab: LauncherTab? = null,
) {
    // Old wire responses and snapshots derive the same placement without migration.
    val effectiveTab: LauncherTab
        get() =
            tab ?: when (query) {
                LauncherQuery.Resume, LauncherQuery.NextUp, LauncherQuery.Favorites -> LauncherTab.Essentials
                LauncherQuery.Latest,
                LauncherQuery.Library,
                LauncherQuery.LatestMovies,
                LauncherQuery.LatestEpisodes,
                LauncherQuery.LatestSeries,
                -> LauncherTab.Jellyfin
            }

    val queryKey: String
        get() {
            val legacy =
                when (query) {
                    LauncherQuery.Resume -> "continue-watching"
                    LauncherQuery.NextUp -> "next-up"
                    LauncherQuery.Latest -> "recently-added"
                    LauncherQuery.Favorites -> "favorites"
                    LauncherQuery.Library,
                    LauncherQuery.LatestMovies,
                    LauncherQuery.LatestEpisodes,
                    LauncherQuery.LatestSeries,
                    -> null
                }
            if (id == legacy && libraryId == null && limit == MAX_ROW_ITEMS) return "$id:v1:20"
            return launcherHash("launcher-query:v1:${query.name}:${libraryId.orEmpty()}:$limit")
        }
}

@Serializable
internal data class LauncherLayout(
    val schemaVersion: Int,
    val revision: String,
    val playbackPreference: String,
    val rows: List<LauncherLayoutRow>,
) {
    fun validated(): LauncherLayout {
        require(schemaVersion in 1..2 && revision.matches(Regex("[a-fA-F0-9]{64}")))
        require(playbackPreference == "jellyscope" && rows.size <= MAX_LAYOUT_ROWS)
        require(rows.map { it.id }.distinct().size == rows.size)
        rows.forEach { row ->
            require(
                schemaVersion == 2 ||
                    row.query in
                    listOf(
                        LauncherQuery.Resume,
                        LauncherQuery.NextUp,
                        LauncherQuery.Latest,
                        LauncherQuery.Favorites,
                        LauncherQuery.Library,
                    ),
            )
            require(row.id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")))
            require(row.title.length in 1..80 && row.title.none { it.isISOControl() || it == '\u2028' || it == '\u2029' })
            require(row.limit in 1..MAX_ROW_ITEMS)
            require(row.libraryId == null || row.libraryId.matches(Regex("[a-f0-9]{32}")))
            require(row.query != LauncherQuery.Library || row.libraryId != null)
        }
        return this
    }
}

internal fun defaultLauncherLayout(): LauncherLayout =
    LauncherLayout(
        schemaVersion = 1,
        revision = "0".repeat(64),
        playbackPreference = "jellyscope",
        rows =
            listOf(
                "continue-watching" to LauncherQuery.Resume,
                "next-up" to LauncherQuery.NextUp,
                "recently-added" to LauncherQuery.Latest,
                "favorites" to LauncherQuery.Favorites,
            ).map { (id, query) ->
                LauncherLayoutRow(id, id, true, LauncherAspect.Landscape, query, null, MAX_ROW_ITEMS)
            },
    )

internal fun defaultLauncherTitle(id: String): Int? =
    when (id) {
        "continue-watching" -> R.string.play_next
        "next-up" -> R.string.up_next
        "recently-added" -> R.string.recently_added
        "favorites" -> R.string.favorites
        else -> null
    }

/** Jellyfin DTOs may use N or D GUIDs; reject permissive UUID parser shortcuts. */
internal fun canonicalLauncherLibraryId(value: String): String? {
    val compact =
        when {
            value.matches(Regex("[a-fA-F0-9]{32}")) -> value
            value.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) -> value.replace("-", "")
            else -> return null
        }.lowercase()
    val dashed = "${compact.take(
        8,
    )}-${compact.substring(8, 12)}-${compact.substring(12, 16)}-${compact.substring(16, 20)}-${compact.drop(20)}"
    return runCatching { UUID.fromString(dashed).toString().replace("-", "") }.getOrNull()
}
