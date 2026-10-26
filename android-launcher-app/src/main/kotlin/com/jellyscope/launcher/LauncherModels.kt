// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.serialization.Serializable
import java.security.MessageDigest

internal const val MAX_ROW_ITEMS = 20
internal const val MAX_HERO_OVERVIEW = 1200
internal const val MAX_HERO_RUNTIME_MINUTES = 10_080
internal const val HOME_CONTENT = "home:content"
internal const val JELLYFIN_CONNECT = "jellyfin:connect"
internal const val JELLYFIN_RECOVERY = "jellyfin:recovery"
internal const val JELLYFIN_REFRESH = "jellyfin:refresh"
internal const val JELLYFIN_SETTINGS = "jellyfin:settings"
internal val launcherHeaderKeys = LauncherTab.entries.map { it.focusKey }.toSet() + setOf("header-account", "settings")

@Serializable
internal data class LauncherCard(
    val id: String,
    val title: String,
    val imageTag: String? = null,
    val backdrop: Boolean = false,
    val progress: Float? = null,
    val primaryTag: String? = null,
    val backdropTag: String? = null,
    val overview: String? = null,
    val seriesName: String? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val officialRating: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Serializable
internal data class LauncherRowSnapshot(
    val id: String,
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
    val layout: LauncherLayout? = null,
)

internal enum class LauncherRowStatus { Loading, Fresh, Cached, Failed, Denied }

internal data class LauncherRow(
    val layout: LauncherLayoutRow,
    val localTitleResource: Int? = null,
    val cards: List<LauncherCard> = emptyList(),
    val status: LauncherRowStatus = LauncherRowStatus.Loading,
) {
    val id: String get() = layout.id
    val focusKey: String get() = "row:$id"
}

internal data class LauncherBoundary(
    val account: AccountIdentity,
    val epoch: Long,
) {
    val key: String get() = "${launcherAccountKey(account)}:$epoch"
}

internal data class LauncherRowWork(
    val layout: LauncherLayoutRow,
    val queryKey: String,
    val generation: Long,
) {
    val id: String get() = layout.id
}

internal data class LauncherRefreshLayout(
    val work: List<LauncherRowWork>,
    val invalidateArtwork: Boolean,
)

internal enum class LauncherConfigurationStatus { Current, Cached, Defaults, Unavailable, Invalid, Unsupported }

internal data class LauncherMediaState(
    val boundaryKey: String? = null,
    val rows: List<LauncherRow> = emptyList(),
    val refreshing: Boolean = false,
    val lastRefreshMs: Long? = null,
    val configurationStatus: LauncherConfigurationStatus = LauncherConfigurationStatus.Defaults,
)

internal data class LauncherApp(
    val componentKey: String,
    val label: String,
    val icon: ImageBitmap?,
    val banner: ImageBitmap? = null,
)

internal data class LauncherAppTarget(
    val componentKey: String,
    val anchor: Rect,
)

internal sealed interface LauncherAppEdit {
    data class Menu(
        val target: LauncherAppTarget,
    ) : LauncherAppEdit

    data class Moving(
        val componentKey: String,
    ) : LauncherAppEdit
}

internal sealed interface LauncherAppFocusTarget {
    val generation: Int

    data class App(
        val componentKey: String,
        override val generation: Int,
    ) : LauncherAppFocusTarget {
        val key: String
            get() = "apps:$componentKey"
    }

    data class HomeContent(
        override val generation: Int,
        val appsOnly: Boolean = false,
    ) : LauncherAppFocusTarget

    data class Header(
        val key: String,
        override val generation: Int,
    ) : LauncherAppFocusTarget
}

internal data class LauncherSettingsFocusTarget(
    val generation: Int,
)

internal data class LauncherAppsState(
    val catalog: List<LauncherApp> = emptyList(),
    val durable: Boolean = true,
    val loaded: Boolean = false,
)

internal enum class LauncherRoute { Home, Settings, Login }

internal enum class LauncherLoginOrigin { Settings, Jellyfin }

internal enum class LauncherDialog {
    SystemSettings,
    HomeSettingsUnavailable,
    AppUnavailable,
    PlaybackUnavailable,
    DetailsUnsupported,
    PlaybackFailed,
    PlaybackStale,
    Logout,
    CleanupFailed,
}

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
    val homeResetGeneration: Int = 0,
    val homeInvoker: String = HOME_CONTENT,
    val selectedTab: LauncherTab = LauncherTab.Essentials,
    val homeTabActivation: Boolean = false,
    val loginOrigin: LauncherLoginOrigin? = null,
    val appEdit: LauncherAppEdit? = null,
    val appEditEpoch: Int = 0,
    val appFocusTarget: LauncherAppFocusTarget? = null,
    val orderWriting: Boolean = false,
    val settingsFocusTarget: LauncherSettingsFocusTarget? = null,
    val preferences: LauncherPreferenceState = LauncherPreferenceState(),
) {
    val backgroundSuppressed: Boolean
        get() = route != LauncherRoute.Home || appEdit is LauncherAppEdit.Menu || dialog != null

    val homeRows: List<LauncherRow>
        get() = rowsFor(selectedTab)

    fun rowsFor(tab: LauncherTab): List<LauncherRow> = if (signedIn) media.rows.filter { it.layout.effectiveTab == tab } else emptyList()

    fun homeActions(tab: LauncherTab): List<String> =
        when {
            tab == LauncherTab.Essentials || rowsFor(tab).any { it.cards.isNotEmpty() } || restoring -> emptyList()
            cleanupPending -> listOf(JELLYFIN_RECOVERY)
            !signedIn && busy -> listOf(JELLYFIN_SETTINGS)
            !signedIn -> listOf(JELLYFIN_CONNECT)
            else -> listOf(JELLYFIN_REFRESH, JELLYFIN_SETTINGS)
        }

    fun homeContentKeys(tab: LauncherTab): List<String> =
        (if (tab == LauncherTab.Essentials) apps.catalog.map { "apps:${it.componentKey}" } else emptyList()) +
            rowsFor(tab).flatMap { row -> row.cards.map { "${row.focusKey}:${it.id}" } } + homeActions(tab)

    val editedApp: LauncherApp?
        get() {
            val key =
                when (val edit = appEdit) {
                    is LauncherAppEdit.Menu -> edit.target.componentKey
                    is LauncherAppEdit.Moving -> edit.componentKey
                    else -> null
                }
            return apps.catalog.firstOrNull { it.componentKey == key }
        }
}

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
