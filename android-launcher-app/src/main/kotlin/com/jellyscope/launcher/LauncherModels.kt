// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

internal const val MAX_ROW_ITEMS = 20
internal const val MAX_APP_PINS = 24

@Serializable
internal enum class LauncherRowId(
    val stableId: String,
) {
    @SerialName("continue-watching")
    ContinueWatching("continue-watching"),

    @SerialName("next-up")
    NextUp("next-up"),

    @SerialName("recently-added")
    RecentlyAdded("recently-added"),

    @SerialName("favorites")
    Favorites("favorites"),
    ;

    val queryKey: String get() = "$stableId:v1:20"
}

@Serializable
internal data class LauncherCard(
    val id: String,
    val title: String,
    val imageTag: String? = null,
    val backdrop: Boolean = false,
    val progress: Float? = null,
)

@Serializable
internal data class LauncherRowSnapshot(
    val id: LauncherRowId,
    val queryKey: String,
    val cards: List<LauncherCard>,
    val updatedAtMs: Long,
)

@Serializable
internal data class LauncherSnapshot(
    val version: Int,
    val source: String,
    val policyFingerprint: String? = null,
    val rows: List<LauncherRowSnapshot> = emptyList(),
)

internal enum class LauncherRowStatus { Loading, Fresh, Cached, Failed, Denied }

internal data class LauncherRow(
    val id: LauncherRowId,
    val cards: List<LauncherCard> = emptyList(),
    val status: LauncherRowStatus = LauncherRowStatus.Loading,
)

internal data class LauncherBoundary(
    val account: AccountIdentity,
    val epoch: Long,
) {
    val key: String get() = "${launcherAccountKey(account)}:$epoch"
}

internal data class LauncherRowWork(
    val id: LauncherRowId,
    val queryKey: String,
    val generation: Long,
)

internal data class LauncherMediaState(
    val boundaryKey: String? = null,
    val rows: List<LauncherRow> = emptyList(),
    val refreshing: Boolean = false,
    val lastRefreshMs: Long? = null,
)

internal data class LauncherApp(
    val componentKey: String,
    val label: String,
    val icon: ImageBitmap?,
)

internal data class LauncherAppsState(
    val catalog: List<LauncherApp> = emptyList(),
    val pins: List<LauncherApp> = emptyList(),
)

internal enum class LauncherRoute { Home, Apps, Settings, Login }

internal enum class LauncherDialog { SystemSettings, AppUnavailable, PlaybackPending, Logout, CleanupFailed }

internal enum class LauncherAuthError { InvalidServer, Unreachable, Credentials, Server, Cleanup }

internal data class LauncherUiState(
    val route: LauncherRoute = LauncherRoute.Home,
    val restoring: Boolean = true,
    val signedIn: Boolean = false,
    val accountLabel: String = "",
    val serverUrl: String = "",
    val boundaryKey: String? = null,
    val media: LauncherMediaState = LauncherMediaState(),
    val apps: LauncherAppsState = LauncherAppsState(),
    val busy: Boolean = false,
    val cleanupPending: Boolean = false,
    val authError: LauncherAuthError? = null,
    val dialog: LauncherDialog? = null,
    val focusGeneration: Int = 0,
    val homeInvoker: String = "apps:+",
)

internal fun launcherHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun launcherAccountKey(account: AccountIdentity): String =
    launcherHash("${account.serverId.length}:${account.serverId}${account.userId}")

internal fun launcherFailure(
    stage: String,
    throwable: Throwable,
) {
    Log.w("Launcher", "stage=$stage event=failed exceptionType=${throwable.javaClass.simpleName}")
}
