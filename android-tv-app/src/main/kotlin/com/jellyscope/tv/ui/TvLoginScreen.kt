// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.tv.R
import com.jellyscope.ui.component.launch.JellyScopeBrandMark
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.login.LoginError
import com.jellyscope.ui.screen.login.LoginEvent
import com.jellyscope.ui.screen.login.LoginUiState
import com.jellyscope.ui.screen.login.LoginViewModel
import com.jellyscope.ui.screen.login.QuickConnectUiError
import com.jellyscope.ui.screen.login.QuickConnectUiState
import com.jellyscope.ui.screen.login.QuickConnectViewModel
import com.jellyscope.ui.theme.AmbientLaunchTokens
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner

@Composable
fun TvLoginScreen(
    serverInfo: ServerInfo,
    onLoggedIn: () -> Unit,
    onBackToServer: () -> Unit,
    prefillUsername: String,
    prefillPassword: String,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel =
        koinViewModel(
            parameters = { parametersOf(serverInfo, prefillUsername, prefillPassword) },
        ),
    quickConnectViewModel: QuickConnectViewModel =
        koinViewModel(
            parameters = { parametersOf(serverInfo) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickConnectState by quickConnectViewModel.state.collectAsStateWithLifecycle()

    // TV remotes always have a hardware back button; there is no on-screen Back.
    // Quick Connect runs continuously here, so Back always returns to the server
    // list rather than treating Quick Connect as a cancellable overlay.
    BackHandler(enabled = !state.isSubmitting) { onBackToServer() }

    // Quick Connect is always enabled on this screen: request a code as soon as
    // the screen opens so it is available beside the password form.
    LaunchedEffect(Unit) { quickConnectViewModel.start() }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                LoginEvent.Success -> onLoggedIn()
            }
        }
    }

    LaunchedEffect(quickConnectState) {
        if (quickConnectState == QuickConnectUiState.Success) {
            onLoggedIn()
        }
    }

    TvLoginContent(
        state = state,
        quickConnectState = quickConnectState,
        onUsernameChange = viewModel::updateUsername,
        onPasswordChange = viewModel::updatePassword,
        onSubmit = viewModel::submit,
        onQuickConnectRestart = quickConnectViewModel::start,
        modifier = modifier,
    )
}

@Composable
internal fun TvLoginContent(
    state: LoginUiState,
    quickConnectState: QuickConnectUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onQuickConnectRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loginRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        loginRequester.requestFocusSafely()
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(TvDimens.launchScreenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JellyScopeBrandMark(landscape = true)
        TvLoginServerChip(serverInfo = state.serverInfo)

        TvAmbientGlassPanel(
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TvDimens.launchColumnGap),
            ) {
                // Sign in column.
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TvAmbientSectionTitle(text = stringResource(R.string.tv_sign_in))
                    TvInputField(
                        value = state.username,
                        onValueChange = onUsernameChange,
                        label = stringResource(R.string.tv_username_label),
                        enabled = !state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                        colors = AmbientTvInputFieldColors,
                        textStyle = AmbientTvBodyTextStyle,
                        glowColor = AmbientTvFocusGlowColor,
                        glowElevation = TvDimens.launchFocusGlowElevation,
                    )
                    TvInputField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = stringResource(R.string.tv_password_label),
                        enabled = !state.isSubmitting,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = AmbientTvInputFieldColors,
                        textStyle = AmbientTvBodyTextStyle,
                        glowColor = AmbientTvFocusGlowColor,
                        glowElevation = TvDimens.launchFocusGlowElevation,
                    )
                    state.error?.let { error ->
                        Text(
                            text = stringResource(error.messageResource),
                            color = AmbientLaunchTokens.error,
                            style = AmbientTvBodyTextStyle,
                            maxLines = 2,
                        )
                    }
                    TvAmbientPrimaryButton(
                        text =
                            stringResource(
                                if (state.isSubmitting) R.string.tv_logging_in else R.string.tv_login,
                            ),
                        onClick = onSubmit,
                        enabled = !state.isSubmitting,
                        loading = state.isSubmitting,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(loginRequester),
                    )
                }

                TvLoginOrDivider()

                // Quick Connect column (always enabled), vertically centered.
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TvAmbientSectionTitle(
                            text = stringResource(R.string.tv_quick_connect_title),
                            modifier =
                                Modifier
                                    .wrapContentSize()
                                    .align(Alignment.TopStart),
                        )
                        TvQuickConnectAlways(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .align(alignment = Alignment.Center),
                            state = quickConnectState,
                            onRestart = onQuickConnectRestart,
                        )
                    }
                }
            }
        }

//        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TvLoginServerChip(serverInfo: ServerInfo) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvAmbientWifiIcon()
        Column(verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap)) {
            Text(
                text = serverInfo.serverName,
                color = AmbientLaunchTokens.textPrimary,
                style = TextStyle(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            Text(
                text = serverInfo.serverUrl,
                color = AmbientLaunchTokens.textSecondary,
                style = AmbientTvBodyTextStyle,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvLoginOrDivider() {
    Column(
        modifier = Modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap),
    ) {
        Box(
            modifier =
                Modifier
                    .width(TvDimens.inputFieldUnfocusedBorder)
                    .weight(1f)
                    .background(AmbientLaunchTokens.glassBorder),
        )
        Text(
            text = stringResource(R.string.tv_or_divider),
            color = AmbientLaunchTokens.textSecondary,
            style = AmbientTvBodyTextStyle,
            maxLines = 1,
        )
        Box(
            modifier =
                Modifier
                    .width(TvDimens.inputFieldUnfocusedBorder)
                    .weight(1f)
                    .background(AmbientLaunchTokens.glassBorder),
        )
    }
}

@Composable
private fun TvQuickConnectAlways(
    modifier: Modifier = Modifier,
    state: QuickConnectUiState,
    onRestart: () -> Unit,
) {
    when (state) {
        is QuickConnectUiState.CodeShown ->
            TvQuickConnectCode(
                modifier = modifier,
                displayCode = state.displayCode,
            )

        is QuickConnectUiState.Polling ->
            TvQuickConnectCode(
                modifier = modifier,
                displayCode = state.displayCode,
            )

        QuickConnectUiState.Idle ->
            TvSpinner(size = TvDimens.launchSpinnerSize, color = AmbientLaunchTokens.accent)

        QuickConnectUiState.Success ->
            Text(
                text = stringResource(R.string.tv_quick_connect_success),
                color = AmbientLaunchTokens.accent,
                style = AmbientTvBodyTextStyle,
                maxLines = 1,
            )

        is QuickConnectUiState.Error ->
            Column(verticalArrangement = Arrangement.spacedBy(TvDimens.formGap)) {
                Text(
                    text = stringResource(state.error.messageResource),
                    color = AmbientLaunchTokens.error,
                    style = AmbientTvBodyTextStyle,
                    maxLines = 2,
                )
                TvAmbientSecondaryButton(
                    text = stringResource(R.string.tv_quick_connect_start),
                    onClick = onRestart,
                )
            }
    }
}

@Composable
private fun TvQuickConnectCode(
    modifier: Modifier,
    displayCode: String,
) {
    val shape = RoundedCornerShape(TvDimens.panelRadius)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(AmbientLaunchTokens.codeBoxFill)
                .border(TvDimens.inputFieldUnfocusedBorder, AmbientLaunchTokens.glassBorder, shape)
                .padding(
                    horizontal = TvDimens.launchPanelPadding,
                    vertical = TvDimens.launchPanelPadding.times(2),
                ),
        verticalArrangement = Arrangement.spacedBy(TvDimens.settingsValueGap),
    ) {
        TvQuickConnectCodeText(displayCode = displayCode)
        Text(
            text = stringResource(R.string.tv_quick_connect_instructions),
            color = AmbientLaunchTokens.textSecondary,
            style = AmbientTvBodyTextStyle,
            maxLines = 2,
        )
    }
}

@Composable
private fun TvQuickConnectCodeText(displayCode: String) {
    val codeStyle =
        TextStyle(
            fontSize = TvDimens.launchQuickConnectCodeSize,
            fontWeight = FontWeight.SemiBold,
        )
    val parts = displayCode.split(" ", limit = 2)
    Row {
        Text(text = parts[0], color = AmbientLaunchTokens.accent, style = codeStyle, maxLines = 1)
        if (parts.size > 1) {
            Text(text = " ", style = codeStyle, maxLines = 1)
            Text(
                text = parts[1],
                color = AmbientLaunchTokens.coral,
                style = codeStyle,
                maxLines = 1,
            )
        }
    }
}

private val LoginError.messageResource: Int
    get() =
        when (this) {
            LoginError.InvalidCredentials -> R.string.tv_error_invalid_credentials
            LoginError.NotReachable -> R.string.tv_error_not_reachable
            LoginError.ServerError -> R.string.tv_error_server
        }

private val QuickConnectUiError.messageResource: Int
    get() =
        when (this) {
            QuickConnectUiError.Expired -> R.string.tv_quick_connect_expired
            QuickConnectUiError.Unavailable -> R.string.tv_quick_connect_unavailable
            QuickConnectUiError.ServerError -> R.string.tv_error_server
        }
