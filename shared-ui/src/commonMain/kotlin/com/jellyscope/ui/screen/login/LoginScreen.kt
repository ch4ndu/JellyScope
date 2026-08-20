// SPDX-License-Identifier: MPL-2.0

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.jellyscope.ui.screen.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.component.launch.AmbientGhostButton
import com.jellyscope.ui.component.launch.AmbientGlassPanel
import com.jellyscope.ui.component.launch.AmbientPrimaryButton
import com.jellyscope.ui.component.launch.AmbientTextField
import com.jellyscope.ui.component.launch.JellyScopeBrandMark
import com.jellyscope.ui.component.launch.LaunchIcons
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.login_back_button
import com.jellyscope.ui.generated.resources.login_button
import com.jellyscope.ui.generated.resources.login_error_invalid_credentials
import com.jellyscope.ui.generated.resources.login_error_not_reachable
import com.jellyscope.ui.generated.resources.login_error_server
import com.jellyscope.ui.generated.resources.login_hide_password
import com.jellyscope.ui.generated.resources.login_loading
import com.jellyscope.ui.generated.resources.login_or
import com.jellyscope.ui.generated.resources.login_password_label
import com.jellyscope.ui.generated.resources.login_quick_connect_code_cd
import com.jellyscope.ui.generated.resources.login_quick_connect_error_expired
import com.jellyscope.ui.generated.resources.login_quick_connect_error_server
import com.jellyscope.ui.generated.resources.login_quick_connect_error_unavailable
import com.jellyscope.ui.generated.resources.login_quick_connect_instructions
import com.jellyscope.ui.generated.resources.login_quick_connect_retry
import com.jellyscope.ui.generated.resources.login_quick_connect_title
import com.jellyscope.ui.generated.resources.login_show_password
import com.jellyscope.ui.generated.resources.login_sign_in
import com.jellyscope.ui.generated.resources.login_submit_cd
import com.jellyscope.ui.generated.resources.login_username_label
import com.jellyscope.ui.theme.AmbientLaunchDimens
import com.jellyscope.ui.theme.AmbientLaunchTokens
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LoginScreen(
    serverInfo: ServerInfo,
    onLoggedIn: () -> Unit,
    onBackToServer: () -> Unit,
    modifier: Modifier = Modifier,
    prefillUsername: String = "",
    prefillPassword: String = "",
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

    // System back (Android predictive back / gesture) returns to server entry
    // rather than exiting the app, since the logged-out flow is state-driven, not
    // navigation-backed. Disabled mid-submit so a login in flight isn't abandoned.
    BackHandler(enabled = !state.isSubmitting) { onBackToServer() }

    // Quick Connect is always enabled: request a code as soon as the screen opens.
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

    LoginContent(
        state = state,
        quickConnectState = quickConnectState,
        onUsernameChange = viewModel::updateUsername,
        onPasswordChange = viewModel::updatePassword,
        onSubmit = viewModel::submit,
        onQuickConnectStart = quickConnectViewModel::start,
        onBackToServer = onBackToServer,
        modifier = modifier,
    )
}

@Composable
fun LoginContent(
    state: LoginUiState,
    quickConnectState: QuickConnectUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onQuickConnectStart: () -> Unit,
    onBackToServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalWindowWidthTier.current == WindowWidthTier.Compact) {
        CompactLoginContent(
            state = state,
            quickConnectState = quickConnectState,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onSubmit = onSubmit,
            onQuickConnectStart = onQuickConnectStart,
            onBackToServer = onBackToServer,
            modifier = modifier,
        )
    } else {
        ExpandedLoginContent(
            state = state,
            quickConnectState = quickConnectState,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onSubmit = onSubmit,
            onQuickConnectStart = onQuickConnectStart,
            onBackToServer = onBackToServer,
            modifier = modifier,
        )
    }
}

@Composable
private fun CompactLoginContent(
    state: LoginUiState,
    quickConnectState: QuickConnectUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onQuickConnectStart: () -> Unit,
    onBackToServer: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimensions.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.panelGap),
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = Dimensions.formControlMaxWidth)
                    .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.brandMarkGap),
        ) {
            JellyScopeBrandMark(landscape = false)
            ServerIdentity(serverInfo = state.serverInfo, centered = true)
        }
        AmbientGlassPanel(modifier = Modifier.widthIn(max = Dimensions.formControlMaxWidth)) {
            SignInColumn(
                state = state,
                onUsernameChange = onUsernameChange,
                onPasswordChange = onPasswordChange,
                onSubmit = onSubmit,
            )
            OrDivider(vertical = false)
            QuickConnectSection(state = quickConnectState, onStart = onQuickConnectStart)
        }
        BackToServersLink(enabled = !state.isSubmitting, onClick = onBackToServer)
    }
}

@Composable
private fun ExpandedLoginContent(
    state: LoginUiState,
    quickConnectState: QuickConnectUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onQuickConnectStart: () -> Unit,
    onBackToServer: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(Dimensions.adaptiveExpandedScreenPadding),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .widthIn(max = AmbientLaunchDimens.expandedLoginMaxWidth)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.panelGap),
        ) {
            JellyScopeBrandMark(landscape = true)
            ServerIdentity(serverInfo = state.serverInfo, centered = false)
            AmbientGlassPanel {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.panelGap),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
                    ) {
                        SignInColumn(
                            state = state,
                            onUsernameChange = onUsernameChange,
                            onPasswordChange = onPasswordChange,
                            onSubmit = onSubmit,
                        )
                    }
                    OrDivider(vertical = true)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        QuickConnectSection(state = quickConnectState, onStart = onQuickConnectStart)
                    }
                }
            }
            BackToServersLink(enabled = !state.isSubmitting, onClick = onBackToServer)
        }
    }
}

@Composable
private fun ServerIdentity(
    serverInfo: ServerInfo,
    centered: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!centered) {
            Icon(
                imageVector = LaunchIcons.Lan,
                contentDescription = null,
                modifier = Modifier.size(AmbientLaunchDimens.cardIconSize),
                tint = AmbientLaunchTokens.accent,
            )
        }
        Column(
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(
                text = serverInfo.serverName,
                color = AmbientLaunchTokens.textPrimary,
                style = TextStyle(fontSize = AmbientLaunchDimens.sectionTitleSize, fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = serverInfo.serverUrl,
                color = AmbientLaunchTokens.textSecondary,
                style = TextStyle(fontSize = AmbientLaunchDimens.bodyTextSize),
            )
        }
    }
}

@Composable
private fun SignInColumn(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val showPassword = stringResource(Res.string.login_show_password)
    val hidePassword = stringResource(Res.string.login_hide_password)

    SectionHeading(text = stringResource(Res.string.login_sign_in))
    AmbientTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        label = stringResource(Res.string.login_username_label),
        enabled = !state.isSubmitting,
        contentDescription = stringResource(Res.string.login_username_label),
    )
    AmbientTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        label = stringResource(Res.string.login_password_label),
        enabled = !state.isSubmitting,
        isError = state.error != null,
        contentDescription = stringResource(Res.string.login_password_label),
        visualTransformation =
            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingContent = {
            Icon(
                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (passwordVisible) hidePassword else showPassword,
                modifier =
                    Modifier
                        .size(AmbientLaunchDimens.cardIconSize)
                        .clickable { passwordVisible = !passwordVisible },
                tint = AmbientLaunchTokens.textSecondary,
            )
        },
    )
    LoginErrorText(state.error)
    AmbientPrimaryButton(
        label =
            stringResource(
                if (state.isSubmitting) Res.string.login_loading else Res.string.login_button,
            ),
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isSubmitting,
        loading = state.isSubmitting,
        contentDescription = stringResource(Res.string.login_submit_cd),
    )
}

@Composable
private fun QuickConnectSection(
    state: QuickConnectUiState,
    onStart: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap)) {
        SectionHeading(text = stringResource(Res.string.login_quick_connect_title))
        QuickConnectBox(state = state, onStart = onStart)
    }
}

@Composable
private fun QuickConnectBox(
    state: QuickConnectUiState,
    onStart: () -> Unit,
) {
    val shape = RoundedCornerShape(AmbientLaunchDimens.controlRadius)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AmbientLaunchTokens.codeBoxFill)
                .border(AmbientLaunchDimens.controlBorder, AmbientLaunchTokens.glassBorder, shape)
                .padding(AmbientLaunchDimens.panelPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
    ) {
        when (state) {
            is QuickConnectUiState.CodeShown -> QuickConnectCode(state.displayCode)
            is QuickConnectUiState.Polling -> QuickConnectCode(state.displayCode)
            QuickConnectUiState.Idle ->
                CircularProgressIndicator(
                    color = AmbientLaunchTokens.accent,
                    strokeWidth = AmbientLaunchDimens.buttonSpinnerStroke,
                )
            QuickConnectUiState.Success -> Unit
            is QuickConnectUiState.Error -> {
                Text(
                    text = stringResource(state.error.stringResource),
                    color = AmbientLaunchTokens.error,
                    style = TextStyle(fontSize = AmbientLaunchDimens.bodyTextSize),
                    textAlign = TextAlign.Center,
                )
                AmbientGhostButton(
                    label = stringResource(Res.string.login_quick_connect_retry),
                    onClick = onStart,
                )
            }
        }
    }
}

@Composable
private fun QuickConnectCode(displayCode: String) {
    val codeContentDescription = stringResource(Res.string.login_quick_connect_code_cd, displayCode)
    val codeStyle =
        TextStyle(
            fontSize = AmbientLaunchDimens.quickConnectCodeSize,
            fontWeight = FontWeight.SemiBold,
        )
    val parts = displayCode.split(" ", limit = 2)
    Row(modifier = Modifier.semantics { contentDescription = codeContentDescription }) {
        Text(text = parts[0], color = AmbientLaunchTokens.accent, style = codeStyle)
        if (parts.size > 1) {
            Text(text = " ", style = codeStyle)
            Text(text = parts[1], color = AmbientLaunchTokens.coral, style = codeStyle)
        }
    }
    Text(
        text = stringResource(Res.string.login_quick_connect_instructions),
        color = AmbientLaunchTokens.textSecondary,
        style = TextStyle(fontSize = AmbientLaunchDimens.bodyTextSize),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = AmbientLaunchTokens.textPrimary,
        style = TextStyle(fontSize = AmbientLaunchDimens.sectionTitleSize, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
private fun OrDivider(vertical: Boolean) {
    val orText = stringResource(Res.string.login_or)
    if (vertical) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
        ) {
            Box(Modifier.width(AmbientLaunchDimens.controlBorder).weight(1f).background(AmbientLaunchTokens.glassBorder))
            Text(orText, color = AmbientLaunchTokens.textSecondary)
            Box(Modifier.width(AmbientLaunchDimens.controlBorder).weight(1f).background(AmbientLaunchTokens.glassBorder))
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(AmbientLaunchDimens.controlBorder).background(AmbientLaunchTokens.glassBorder))
            Text(orText, color = AmbientLaunchTokens.textSecondary)
            Box(Modifier.weight(1f).height(AmbientLaunchDimens.controlBorder).background(AmbientLaunchTokens.glassBorder))
        }
    }
}

@Composable
private fun BackToServersLink(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = "‹ " + stringResource(Res.string.login_back_button),
        modifier =
            Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(AmbientLaunchDimens.controlGap),
        color = AmbientLaunchTokens.accent,
        style = TextStyle(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
private fun LoginErrorText(error: LoginError?) {
    error?.let {
        Text(
            text = stringResource(error.stringResource),
            color = AmbientLaunchTokens.error,
            style = TextStyle(fontSize = AmbientLaunchDimens.bodyTextSize),
        )
    }
}

private val LoginError.stringResource: StringResource
    get() =
        when (this) {
            LoginError.InvalidCredentials -> Res.string.login_error_invalid_credentials
            LoginError.NotReachable -> Res.string.login_error_not_reachable
            LoginError.ServerError -> Res.string.login_error_server
        }

private val QuickConnectUiError.stringResource: StringResource
    get() =
        when (this) {
            QuickConnectUiError.Expired -> Res.string.login_quick_connect_error_expired
            QuickConnectUiError.Unavailable -> Res.string.login_quick_connect_error_unavailable
            QuickConnectUiError.ServerError -> Res.string.login_quick_connect_error_server
        }
