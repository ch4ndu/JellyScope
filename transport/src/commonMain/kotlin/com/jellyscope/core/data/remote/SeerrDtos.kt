// SPDX-License-Identifier: MPL-2.0

@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.model.SeerrAvailability
import com.jellyscope.core.domain.model.SeerrCastMember
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrCatalogPage
import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrMediaCard
import com.jellyscope.core.domain.model.SeerrMediaKey
import com.jellyscope.core.domain.model.SeerrMediaType
import com.jellyscope.core.domain.model.SeerrPermissions
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuota
import com.jellyscope.core.domain.model.SeerrQuotaKind
import com.jellyscope.core.domain.model.SeerrQuotas
import com.jellyscope.core.domain.model.SeerrRequestEntry
import com.jellyscope.core.domain.model.SeerrRequestStatus
import com.jellyscope.core.domain.model.SeerrRequestSummary
import com.jellyscope.core.domain.model.SeerrRequestsPage
import com.jellyscope.core.domain.model.SeerrSeason
import com.jellyscope.core.domain.model.SeerrServiceInfo
import com.jellyscope.core.domain.model.SeerrUser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
data class SeerrWireUser(
    val user: SeerrUser,
    val jellyfinUserId: String?,
)

@HiddenFromObjC
data class SeerrWireQuickConnect(
    val code: String,
    val secret: String,
) {
    override fun toString(): String = "SeerrWireQuickConnect([redacted])"
}

@HiddenFromObjC
data class SeerrWireDetail(
    val media: SeerrMediaCard,
    val overview: String?,
    val backdropUrl: String?,
    val seasons: List<SeerrSeason>,
    val requests: List<SeerrRequestSummary>,
    val requestMetadataComplete: Boolean,
    val jellyfinMediaId: String?,
    val jellyfinMediaId4k: String?,
    val cast: List<SeerrCastMember> = emptyList(),
)

internal class SeerrProtocolException(
    val error: SeerrError,
) : Exception()

internal fun JsonObject.toServiceInfo(
    endpoint: String,
    version: String,
): SeerrServiceInfo {
    if (boolean("initialized") == false) throw SeerrProtocolException(SeerrError.SetupRequired)
    if (boolean("initialized") != true) throw SeerrProtocolException(SeerrError.UnsupportedService)
    val mediaServerType = integer("mediaServerType")
    if (mediaServerType == 4) throw SeerrProtocolException(SeerrError.SetupRequired)
    if (mediaServerType == null || mediaServerType !in 1..3) throw SeerrProtocolException(SeerrError.UnsupportedService)
    // v3.5.1: 1=Plex, 2=Jellyfin, 3=Emby. Never send setup/server overrides.
    val jellyfin = mediaServerType == 2 && boolean("mediaServerLogin") == true
    return SeerrServiceInfo(
        endpoint = endpoint,
        version = version,
        usesPlainHttp = endpoint.startsWith("http://"),
        jellyfinLoginEnabled = jellyfin,
        localLoginEnabled = boolean("localLogin") == true,
        quickConnectSupported = jellyfin,
        movie4kEnabled = boolean("movie4kEnabled") == true,
        series4kEnabled = boolean("series4kEnabled") == true,
        specialEpisodesEnabled = boolean("enableSpecialEpisodes") == true,
    )
}

internal fun JsonObject.toUser(): SeerrWireUser {
    val id = requiredPositiveInt("id")
    if (this["jellyfinUserId"] != null && this["jellyfinUserId"] != JsonNull) {
        require(text("jellyfinUserId") != null)
    }
    val permissions = integer("permissions")?.takeIf { it >= 0 } ?: 0

    fun granted(vararg bits: Int): Boolean = permissions and 2 != 0 || bits.any { permissions and it != 0 }
    return SeerrWireUser(
        SeerrUser(
            id,
            (text("displayName") ?: text("username") ?: text("jellyfinUsername") ?: text("email")).orEmpty().take(256),
            SeerrPermissions(
                requestMovie = granted(32, 262144),
                requestTv = granted(32, 524288),
                requestMovie4k = granted(1024, 2048),
                requestTv4k = granted(1024, 4096),
                autoApproveMovie = granted(16, 128, 256),
                autoApproveTv = granted(16, 128, 512),
                autoApproveMovie4k = granted(16, 32768, 65536),
                autoApproveTv4k = granted(16, 32768, 131072),
            ),
        ),
        text("jellyfinUserId")?.takeIf { it.isNotBlank() },
    )
}

internal fun JsonObject.toCatalogPage(
    catalog: SeerrCatalog,
    requestedPage: Int,
): SeerrCatalogPage {
    val page = requiredPositiveInt("page")
    require(page == requestedPage)
    val pages = integer("totalPages")?.takeIf { it >= 0 } ?: error("Missing pagination")
    val fallbackType =
        when (catalog) {
            SeerrCatalog.Movies -> SeerrMediaType.Movie
            SeerrCatalog.Tv -> SeerrMediaType.Tv
            else -> null
        }
    val rows = array("results") ?: error("Missing results")
    require(rows.size <= 200)
    val items =
        rows
            .mapNotNull { value ->
                val row = value as? JsonObject ?: return@mapNotNull null
                val mediaType = row.text("mediaType")
                val type = (if (mediaType == null) fallbackType else mediaType.toMediaType()) ?: return@mapNotNull null
                val id = row.integer("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                row.toCard(SeerrMediaKey(type, id))
            }.distinctBy { it.key }
    return SeerrCatalogPage(items, page, pages)
}

internal fun JsonObject.toDetail(key: SeerrMediaKey): SeerrWireDetail {
    require(integer("id") == key.tmdbId)
    val info = obj("mediaInfo")
    if (info?.integer("tmdbId") != null) require(info.integer("tmdbId") == key.tmdbId)
    val rawRequests = info?.array("requests")
    val requests = rawRequests.orEmpty().map { (it as? JsonObject ?: error("Invalid request")).toRequest(key) }
    val statuses = info?.array("seasons").orEmpty().map { it as? JsonObject ?: error("Invalid season status") }
    val seasons =
        if (key.type == SeerrMediaType.Tv) {
            (array("seasons") ?: error("Missing seasons"))
                .map { value ->
                    val season = value as? JsonObject ?: error("Invalid season")
                    val number = season.integer("seasonNumber")?.takeIf { it >= 0 } ?: error("Missing season number")
                    val state = statuses.firstOrNull { it.integer("seasonNumber") == number }
                    SeerrSeason(
                        number,
                        season.text("name")?.take(512),
                        season.integer("episodeCount")?.coerceAtLeast(0) ?: 0,
                        state.availability("status"),
                        state.availability("status4k"),
                    )
                }.distinctBy { it.number }
        } else {
            emptyList()
        }
    return SeerrWireDetail(
        media = toCard(key),
        overview = text("overview")?.take(20_000),
        backdropUrl = artwork(text("backdropPath"), "w1280"),
        seasons = seasons,
        cast =
            ((this["credits"] as? JsonObject)?.get("cast") as? JsonArray).orEmpty().take(200).mapNotNull { value ->
                val member = value as? JsonObject ?: return@mapNotNull null
                val id = member.integer("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val name = member.text("name")?.take(256)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SeerrCastMember(
                    id,
                    member.text("creditId")?.take(256),
                    name,
                    member.text("character")?.take(512),
                    artwork(member.text("profilePath"), "w185"),
                )
            },
        requests = requests,
        requestMetadataComplete = info == null || (rawRequests != null && (key.type != SeerrMediaType.Tv || info.array("seasons") != null)),
        jellyfinMediaId = info?.text("jellyfinMediaId")?.takeIf(::validLibraryCandidate),
        jellyfinMediaId4k = info?.text("jellyfinMediaId4k")?.takeIf(::validLibraryCandidate),
    )
}

internal fun JsonObject.toRequest(fallbackKey: SeerrMediaKey? = null): SeerrRequestSummary {
    val media = obj("media")
    val type = media?.text("mediaType")?.toMediaType() ?: text("type")?.toMediaType() ?: fallbackKey?.type
    val id = media?.integer("tmdbId") ?: fallbackKey?.tmdbId
    val key = SeerrMediaKey(requireNotNull(type), requireNotNull(id))
    if (fallbackKey != null) require(key == fallbackKey)
    val seasons =
        if (type == SeerrMediaType.Tv) {
            (array("seasons") ?: error("Missing request seasons"))
                .map {
                    (it as? JsonObject)?.integer("seasonNumber")?.takeIf { number -> number >= 0 } ?: error("Invalid request season")
                }.distinct()
        } else {
            emptyList()
        }
    return SeerrRequestSummary(
        requiredPositiveInt("id"),
        key,
        when (boolean("is4k")) {
            true -> SeerrQuality.FourK
            false -> SeerrQuality.Standard
            null -> error("Missing request quality")
        },
        when (integer("status")) {
            1 -> SeerrRequestStatus.Pending
            2 -> SeerrRequestStatus.Approved
            3 -> SeerrRequestStatus.Declined
            4 -> SeerrRequestStatus.Failed
            5 -> SeerrRequestStatus.Completed
            else -> SeerrRequestStatus.Unrecognized
        },
        seasons,
    )
}

internal fun JsonObject.toRequestsPage(skip: Int): SeerrRequestsPage {
    val pageInfo = obj("pageInfo") ?: error("Missing pagination")
    val page = pageInfo.requiredPositiveInt("page")
    val pageSize = pageInfo.requiredPositiveInt("pageSize")
    require(pageSize == REQUEST_PAGE_SIZE && page == skip / pageSize + 1)
    val pages = pageInfo.integer("pages")?.takeIf { it >= 0 } ?: error("Invalid pagination")
    val count = pageInfo.integer("results")?.takeIf { it >= 0 } ?: error("Invalid pagination")
    val rows = array("results") ?: error("Missing requests")
    require(rows.size <= REQUEST_PAGE_SIZE)
    val entries =
        rows
            .map { value ->
                val row = value as? JsonObject ?: error("Invalid request")
                val request = row.toRequest()
                val media = row.obj("media") ?: error("Missing media")
                SeerrRequestEntry(
                    request,
                    SeerrMediaCard(request.key, null, null, null, media.availability("status"), media.availability("status4k")),
                )
            }.distinctBy { it.request.id }
    return SeerrRequestsPage(entries, page, pages, count, skip + pageSize)
}

internal fun JsonObject.toQuotas(): SeerrQuotas = SeerrQuotas(obj("movie").toQuota(), obj("tv").toQuota())

private fun JsonObject?.toQuota(): SeerrQuota {
    val unavailable = SeerrQuota(SeerrQuotaKind.Unavailable)
    val value = this ?: return unavailable
    val used = value.integer("used")?.takeIf { it >= 0 } ?: return unavailable
    val restricted = value.boolean("restricted") ?: return unavailable
    val fields = listOf("limit", "remaining", "days")
    if (fields.any { it in value && (value.integer(it) == null || (value.integer(it) ?: -1) < 0) }) return unavailable
    val limit = value.integer("limit")
    val remaining = value.integer("remaining")
    val days = value.integer("days")
    if (!restricted && remaining == null && (limit == null || limit == 0)) {
        return SeerrQuota(SeerrQuotaKind.Unlimited, used = used, limit = limit, days = days)
    }
    if (limit == null || limit <= 0 || remaining == null || remaining > limit) return unavailable
    if (remaining != (limit - used).coerceAtLeast(0) || restricted != (remaining == 0)) return unavailable
    return SeerrQuota(SeerrQuotaKind.Limited, used, limit, remaining, days, restricted)
}

private fun JsonObject.toCard(key: SeerrMediaKey): SeerrMediaCard {
    val info = obj("mediaInfo")
    return SeerrMediaCard(
        key,
        (if (key.type == SeerrMediaType.Movie) text("title") else text("name"))?.take(512),
        (if (key.type == SeerrMediaType.Movie) text("releaseDate") else text("firstAirDate"))?.take(4)?.toIntOrNull(),
        artwork(text("posterPath"), "w500"),
        info.availability("status"),
        info.availability("status4k"),
    )
}

private fun JsonObject?.availability(field: String): SeerrAvailability =
    when {
        this == null -> SeerrAvailability.Unknown
        else ->
            when (integer(field)) {
                1 -> SeerrAvailability.Unknown
                2 -> SeerrAvailability.Pending
                3 -> SeerrAvailability.Processing
                4 -> SeerrAvailability.PartiallyAvailable
                5 -> SeerrAvailability.Available
                6 -> SeerrAvailability.Blocklisted
                7 -> SeerrAvailability.Deleted
                else -> SeerrAvailability.Unrecognized
            }
    }

private fun artwork(
    path: String?,
    size: String,
): String? = path?.takeIf { it.matches(Regex("/[A-Za-z0-9_-]+\\.(jpg|png|webp)")) }?.let { "https://image.tmdb.org/t/p/$size$it" }

private fun validLibraryCandidate(value: String): Boolean = value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '-' }

private fun String.toMediaType(): SeerrMediaType? =
    when (this) {
        "movie" -> SeerrMediaType.Movie
        "tv" -> SeerrMediaType.Tv
        else -> null
    }

internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.requiredText(name: String): String = text(name)?.takeIf { it.isNotBlank() } ?: error("Missing text")

internal fun JsonObject.integer(name: String): Int? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

internal fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

internal fun JsonObject.requiredPositiveInt(name: String): Int = integer(name)?.takeIf { it > 0 } ?: error("Missing identity")

internal fun JsonObject.obj(name: String): JsonObject? {
    val value = this[name]
    require(value == null || value == JsonNull || value is JsonObject)
    return value as? JsonObject
}

internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray
