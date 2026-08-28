// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.usecase.ObserveAppThemeUseCase
import com.jellyscope.core.domain.usecase.ObserveSessionStateUseCase
import com.jellyscope.core.domain.usecase.ObserveTileSizeUseCase
import com.jellyscope.tv.ui.LocalTvFocusZoomEnabled
import com.jellyscope.tv.ui.TvLoginScreen
import com.jellyscope.tv.ui.TvSafeAreaContent
import com.jellyscope.tv.ui.TvServerEntryScreen
import com.jellyscope.tv.watchnext.WatchNextContract
import com.jellyscope.tv.watchnext.WatchNextScheduler
import com.jellyscope.ui.adaptive.LocalTileScale
import com.jellyscope.ui.adaptive.tvTileScaleFor
import com.jellyscope.ui.component.launch.AmbientLaunchScaffold
import com.jellyscope.ui.image.InstallJellyfinImageLoader
import com.jellyscope.ui.screen.detail.DetailInteractionMode
import com.jellyscope.ui.screen.detail.LocalDetailFocusZoomEnabled
import com.jellyscope.ui.screen.detail.LocalDetailInteractionMode
import com.jellyscope.ui.theme.JellyScopeTheme
import com.jellyscope.ui.theme.toAppColorTheme
import org.koin.android.ext.android.get
import org.koin.compose.koinInject
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner

class MainActivity : ComponentActivity() {
    private var pendingWatchNextPayload by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWatchNextPayload = intent.watchNextPayload()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
        )

        // Only lightweight startup state is resolved for the first frame; the
        // HTTP client and store registry resolve lazily in the LoggedIn branch
        // so cold start never builds them on the main thread.
        val observeSessionStateUseCase = get<ObserveSessionStateUseCase>()
        val observeAppThemeUseCase = get<ObserveAppThemeUseCase>()
        val observeTileSizeUseCase = get<ObserveTileSizeUseCase>()
        val tvUiPreferencesStore = get<TvUiPreferencesStore>()

        setContent {
            val appTheme by observeAppThemeUseCase().collectAsStateWithLifecycle()
            val tileSize by observeTileSizeUseCase().collectAsStateWithLifecycle()
            val focusZoomEnabled by tvUiPreferencesStore.focusedCardZoomEnabled.collectAsStateWithLifecycle()

            JellyScopeTheme(theme = appTheme.toAppColorTheme()) {
                CompositionLocalProvider(
                    LocalTileScale provides tvTileScaleFor(tileSize),
                    LocalTvFocusZoomEnabled provides focusZoomEnabled,
                    LocalDetailFocusZoomEnabled provides focusZoomEnabled,
                    LocalDetailInteractionMode provides DetailInteractionMode.Dpad,
                ) {
                    TvApp(
                        observeSessionStateUseCase = observeSessionStateUseCase,
                        pendingWatchNextPayload = pendingWatchNextPayload,
                        onWatchNextItemHandled = { pendingWatchNextPayload = null },
                        onPlaybackStopped = { WatchNextScheduler.enqueueImmediate(this@MainActivity) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingWatchNextPayload = intent.watchNextPayload()
    }
}

@Composable
private fun TvApp(
    observeSessionStateUseCase: ObserveSessionStateUseCase,
    pendingWatchNextPayload: String?,
    onWatchNextItemHandled: () -> Unit,
    onPlaybackStopped: () -> Unit,
) {
    val sessionState by observeSessionStateUseCase().collectAsStateWithLifecycle()
    var validatedServerInfo by remember { mutableStateOf<ServerInfo?>(null) }

    when (val state = sessionState) {
        SessionState.Restoring -> {
            TvSafeAreaContent {
                TvSpinner(modifier = Modifier.align(Alignment.Center))
            }
        }
        is SessionState.LoggedOut -> {
            AmbientLaunchScaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                imeAware = false,
            ) {
                TvSafeAreaContent(drawAppBackground = false) {
                    val serverInfo = validatedServerInfo
                    if (serverInfo == null) {
                        TvServerEntryScreen(
                            initialServerUrl = state.serverUrl ?: TvDeveloperConfig.SERVER_URL.ifBlank { null },
                            onServerValidated = { validated -> validatedServerInfo = validated },
                        )
                    } else {
                        TvLoginScreen(
                            serverInfo = serverInfo,
                            onLoggedIn = { validatedServerInfo = null },
                            onBackToServer = { validatedServerInfo = null },
                            prefillUsername = TvDeveloperConfig.USERNAME,
                            prefillPassword = TvDeveloperConfig.PASSWORD,
                        )
                    }
                }
            }
        }
        is SessionState.LoggedIn -> {
            val context = LocalContext.current
            LaunchedEffect(state.session.serverId, state.session.userId, state.boundaryEpoch) {
                WatchNextScheduler.enqueueImmediate(context)
            }
            val pendingPayload = WatchNextContract.parseAccountPayload(pendingWatchNextPayload)
            val pendingItemId =
                pendingPayload
                    ?.takeIf { payload ->
                        payload.serverId == state.session.serverId && payload.userId == state.session.userId
                    }?.itemId
            LaunchedEffect(pendingWatchNextPayload, state.session.serverId, state.session.userId) {
                if (pendingWatchNextPayload != null && pendingItemId == null) {
                    onWatchNextItemHandled()
                }
            }
            key(state.session.accountIdentity(), state.boundaryEpoch) {
                InstallJellyfinImageLoader(
                    httpClient = koinInject(),
                    serverScopedStoreRegistry = koinInject(),
                    accountIdentity = state.session.accountIdentity(),
                    boundaryEpoch = state.boundaryEpoch,
                )
                TvLoggedInApp(
                    session = state.session,
                    pendingWatchNextItemId = pendingItemId,
                    onWatchNextItemHandled = onWatchNextItemHandled,
                    onPlaybackStopped = onPlaybackStopped,
                )
            }
        }
    }
}
