// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun LauncherLoginScreen(
    state: LauncherUiState,
    focus: LauncherFocus,
    model: LauncherViewModel,
    modifier: Modifier = Modifier,
) {
    var server by remember { mutableStateOf(state.serverUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = !state.restoring && !state.busy && !state.signedIn && !state.cleanupPending
    val cancel = {
        password = ""
        keyboard?.hide()
        model.cancelLogin()
    }
    val submit = {
        if (canSubmit) {
            keyboard?.hide()
            model.login(server, username, password)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            password = ""
            keyboard?.hide()
            focus.reset()
        }
    }
    LaunchedEffect(state.focusGeneration) { focus.request(focus.current ?: "server") }
    LaunchedEffect(canSubmit) {
        if (!canSubmit && focus.current == "submit") focus.request("cancel")
    }
    BackHandler(enabled = WindowInsets.ime.getBottom(LocalDensity.current) == 0, onBack = cancel)
    val fields = listOf("server", "username", "password")
    Column(
        modifier.imePadding().verticalScroll(rememberScrollState()).padding(32.dp).widthIn(max = 640.dp).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) {
                false
            } else {
                val index = fields.indexOf(focus.current)
                when (it.key) {
                    Key.DirectionUp -> {
                        focus.request(if (index >= 0) fields.getOrNull(index - 1) ?: "settings" else "password")
                        true
                    }
                    Key.DirectionDown -> {
                        if (index >= 0) focus.request(fields.getOrNull(index + 1) ?: if (canSubmit) "submit" else "cancel")
                        true
                    }
                    else -> false
                }
            }
        },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.sign_in), style = MaterialTheme.typography.headlineMedium)
        LauncherLoginField(stringResource(R.string.server_url), server, { server = it }, focus, "server", KeyboardType.Uri)
        if (server.trim().startsWith("http://", true)) Text(stringResource(R.string.http_notice))
        LauncherLoginField(stringResource(R.string.username), username, { username = it }, focus, "username", KeyboardType.Text)
        LauncherLoginField(stringResource(R.string.password), password, { password = it }, focus, "password", KeyboardType.Password, submit)
        state.authError?.let { Text(stringResource(it.messageResource()), color = MaterialTheme.colorScheme.error) }
        if (state.busy) Text(stringResource(R.string.signing_in))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = submit,
                enabled = canSubmit,
                modifier = Modifier.launcherFocus(focus, "submit"),
            ) { Text(stringResource(R.string.sign_in)) }
            Button(onClick = cancel, modifier = Modifier.launcherFocus(focus, "cancel")) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun LauncherLoginField(
    label: String,
    value: String,
    change: (String) -> Unit,
    focus: LauncherFocus,
    key: String,
    type: KeyboardType,
    submit: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        BasicTextField(
            value = value,
            onValueChange = change,
            modifier =
                Modifier
                    .widthIn(min = 320.dp)
                    .launcherFocus(focus, key)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics {
                        contentDescription =
                            label
                    }.background(
                        MaterialTheme.colorScheme.surface,
                    ).border(
                        if (focused) 2.dp else 1.dp,
                        if (focused) Color.White else MaterialTheme.colorScheme.primary,
                    ).padding(14.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (type == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (submit == null) ImeAction.Next else ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onNext = { focus.request(if (key == "server") "username" else "password") },
                    onDone = { submit?.invoke() },
                ),
        )
    }
}

private fun LauncherAuthError.messageResource(): Int =
    when (this) {
        LauncherAuthError.InvalidServer -> R.string.auth_invalid_server
        LauncherAuthError.Unreachable -> R.string.auth_unreachable
        LauncherAuthError.Credentials -> R.string.auth_credentials
        LauncherAuthError.Server -> R.string.auth_server
        LauncherAuthError.Cleanup -> R.string.auth_cleanup
    }
