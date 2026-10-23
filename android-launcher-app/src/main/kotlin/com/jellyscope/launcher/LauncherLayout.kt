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
    Landscape(176, 99),

    @SerialName("poster")
    Poster(128, 192),

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
) {
    val queryKey: String
        get() {
            val legacy =
                when (query) {
                    LauncherQuery.Resume -> "continue-watching"
                    LauncherQuery.NextUp -> "next-up"
                    LauncherQuery.Latest -> "recently-added"
                    LauncherQuery.Favorites -> "favorites"
                    LauncherQuery.Library -> null
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
        require(schemaVersion == 1 && revision.matches(Regex("[a-fA-F0-9]{64}")))
        require(playbackPreference == "jellyscope" && rows.size <= MAX_LAYOUT_ROWS)
        require(rows.map { it.id }.distinct().size == rows.size)
        rows.forEach { row ->
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
        "continue-watching" -> R.string.continue_watching
        "next-up" -> R.string.next_up
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
