// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.usecase.ObserveAppThemeUseCase
import com.jellyscope.core.domain.usecase.ObserveSessionStateUseCase
import com.jellyscope.ui.component.SafeAreaContent
import com.jellyscope.ui.component.launch.AmbientLaunchScaffold
import com.jellyscope.ui.image.InstallJellyfinImageLoader
import com.jellyscope.ui.navigation.InitialDetailNavigationEvent
import com.jellyscope.ui.navigation.LoggedInNavHost
import com.jellyscope.ui.screen.home.SessionRestoringContent
import com.jellyscope.ui.screen.login.LoginScreen
import com.jellyscope.ui.screen.serverentry.ServerEntryScreen
import com.jellyscope.ui.theme.JellyScopeTheme
import com.jellyscope.ui.theme.toAppColorTheme
import io.ktor.client.HttpClient
import org.koin.compose.koinInject

/**
 * The whole application, shared across every platform. Applies the selected
 * theme, then drives the session lifecycle: restore an existing session,
 * otherwise show server entry -> login, and hand off to the shared logged-in
 * navigation once authenticated.
 *
 * Platform entry points (Android `MainActivity`, iOS `MainViewController`, and
 * future desktop `main`) do nothing more than start Koin, apply any
 * platform-only window setup, and call this. Keep platform roots this thin so
 * the app flow lives in one place.
 *
 * @param initialServerUrl prefilled into the server-entry field on first launch.
 * @param prefillUsername / [prefillPassword] optional dev-login conveniences.
 * @param initialPlaybackItemId optional development convenience that opens the
 * normal player route after a stored session is restored.
 * @param initialDetailItemId optional deep-link target that opens the normal
 * item detail route after a stored session is restored.
 * @param initialDetailEvent optional consumable deep-link target. The event is
 * acknowledged after its route is installed so the same item can be delivered
 * again as a new event without replaying after an account boundary.
 * @param onDetailNavigationBoundaryChanged informs platform deep-link owners
 * about the active account boundary before logged-in navigation consumes an event.
 */
@Composable
fun JellyScopeApp(
    initialServerUrl: String? = null,
    prefillUsername: String = "",
    prefillPassword: String = "",
    initialPlaybackItemId: String? = null,
    initialDetailItemId: String? = null,
    initialDetailEvent: InitialDetailNavigationEvent? = null,
    onInitialDetailEventConsumed: (Long) -> Unit = {},
    onDetailNavigationBoundaryChanged: (
        accountIdentity: com.jellyscope.core.domain.model.AccountIdentity?,
        boundaryEpoch: Long?,
    ) -> Unit = { _, _ -> },
) {
    val observeAppThemeUseCase = koinInject<ObserveAppThemeUseCase>()
    val appTheme by observeAppThemeUseCase().collectAsStateWithLifecycle()

    JellyScopeTheme(theme = appTheme.toAppColorTheme()) {
        val observeSessionStateUseCase = koinInject<ObserveSessionStateUseCase>()
        val httpClient = koinInject<HttpClient>()
        val serverScopedStoreRegistry = koinInject<ServerScopedStoreRegistry>()

        val sessionState by observeSessionStateUseCase().collectAsStateWithLifecycle()
        var validatedServerInfo by remember { mutableStateOf<ServerInfo?>(null) }

        when (val state = sessionState) {
            SessionState.Restoring ->
                SafeAreaContent {
                    SessionRestoringContent()
                }
            is SessionState.LoggedOut -> {
                SideEffect { onDetailNavigationBoundaryChanged(null, null) }
                val serverInfo = validatedServerInfo
                AmbientLaunchScaffold {
                    if (serverInfo == null) {
                        ServerEntryScreen(
                            initialServerUrl = state.serverUrl ?: initialServerUrl,
                            onServerValidated = { validated -> validatedServerInfo = validated },
                        )
                    } else {
                        LoginScreen(
                            serverInfo = serverInfo,
                            onLoggedIn = { validatedServerInfo = null },
                            onBackToServer = { validatedServerInfo = null },
                            prefillUsername = prefillUsername,
                            prefillPassword = prefillPassword,
                        )
                    }
                }
            }
            is SessionState.LoggedIn -> {
                SideEffect {
                    onDetailNavigationBoundaryChanged(state.session.accountIdentity(), state.boundaryEpoch)
                }
                key(state.session.accountIdentity(), state.boundaryEpoch) {
                    InstallJellyfinImageLoader(
                        httpClient = httpClient,
                        serverScopedStoreRegistry = serverScopedStoreRegistry,
                        accountIdentity = state.session.accountIdentity(),
                        boundaryEpoch = state.boundaryEpoch,
                    )
                    LoggedInNavHost(
                        session = state.session,
                        boundaryEpoch = state.boundaryEpoch,
                        onLogoutComplete = { validatedServerInfo = null },
                        initialPlaybackItemId = initialPlaybackItemId,
                        initialDetailItemId = initialDetailItemId,
                        initialDetailEvent = initialDetailEvent,
                        onInitialDetailEventConsumed = onInitialDetailEventConsumed,
                    )
                }
            }
        }
    }
}
