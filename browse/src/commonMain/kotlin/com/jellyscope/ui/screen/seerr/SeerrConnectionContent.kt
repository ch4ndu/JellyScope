// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.jellyscope.core.domain.model.SeerrConnectionPhase
import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.seerr_cancel
import com.jellyscope.ui.generated.resources.seerr_connected
import com.jellyscope.ui.generated.resources.seerr_disconnect
import com.jellyscope.ui.generated.resources.seerr_email
import com.jellyscope.ui.generated.resources.seerr_endpoint
import com.jellyscope.ui.generated.resources.seerr_error_account
import com.jellyscope.ui.generated.resources.seerr_error_auth
import com.jellyscope.ui.generated.resources.seerr_error_available
import com.jellyscope.ui.generated.resources.seerr_error_blocklisted
import com.jellyscope.ui.generated.resources.seerr_error_credentials
import com.jellyscope.ui.generated.resources.seerr_error_expired
import com.jellyscope.ui.generated.resources.seerr_error_https
import com.jellyscope.ui.generated.resources.seerr_error_in_progress
import com.jellyscope.ui.generated.resources.seerr_error_invalid_url
import com.jellyscope.ui.generated.resources.seerr_error_library
import com.jellyscope.ui.generated.resources.seerr_error_metadata
import com.jellyscope.ui.generated.resources.seerr_error_network
import com.jellyscope.ui.generated.resources.seerr_error_no_seasons
import com.jellyscope.ui.generated.resources.seerr_error_permission
import com.jellyscope.ui.generated.resources.seerr_error_persistence
import com.jellyscope.ui.generated.resources.seerr_error_qc_expired
import com.jellyscope.ui.generated.resources.seerr_error_qc_unavailable
import com.jellyscope.ui.generated.resources.seerr_error_quota
import com.jellyscope.ui.generated.resources.seerr_error_rate
import com.jellyscope.ui.generated.resources.seerr_error_redirect
import com.jellyscope.ui.generated.resources.seerr_error_requested
import com.jellyscope.ui.generated.resources.seerr_error_response
import com.jellyscope.ui.generated.resources.seerr_error_security
import com.jellyscope.ui.generated.resources.seerr_error_selection
import com.jellyscope.ui.generated.resources.seerr_error_setup
import com.jellyscope.ui.generated.resources.seerr_error_unsupported
import com.jellyscope.ui.generated.resources.seerr_error_verification
import com.jellyscope.ui.generated.resources.seerr_http
import com.jellyscope.ui.generated.resources.seerr_jellyfin_login
import com.jellyscope.ui.generated.resources.seerr_local_explanation
import com.jellyscope.ui.generated.resources.seerr_local_login
import com.jellyscope.ui.generated.resources.seerr_owner_changed
import com.jellyscope.ui.generated.resources.seerr_password
import com.jellyscope.ui.generated.resources.seerr_quick_connect
import com.jellyscope.ui.generated.resources.seerr_quick_connect_code
import com.jellyscope.ui.generated.resources.seerr_quick_connect_wait
import com.jellyscope.ui.generated.resources.seerr_quota_unavailable
import com.jellyscope.ui.generated.resources.seerr_restricted
import com.jellyscope.ui.generated.resources.seerr_setup
import com.jellyscope.ui.generated.resources.seerr_sign_in
import com.jellyscope.ui.generated.resources.seerr_uncertain
import com.jellyscope.ui.generated.resources.seerr_url
import com.jellyscope.ui.generated.resources.seerr_username
import com.jellyscope.ui.generated.resources.seerr_validate
import com.jellyscope.ui.generated.resources.seerr_validated
import com.jellyscope.ui.generated.resources.seerr_verify_connection
import com.jellyscope.ui.generated.resources.seerr_working
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeerrConnectionContent(
    state: SeerrConnectionUi,
    onIntent: (SeerrIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var identifier by remember(state.credentialRevision) { mutableStateOf("") }
    var password by remember(state.credentialRevision) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    DisposableEffect(state.credentialRevision) {
        onDispose {
            identifier = ""
            password = ""
        }
    }

    fun submit() {
        if (state.busy || identifier.isBlank() || password.isEmpty()) return
        val submitted = password
        password = ""
        keyboard?.hide()
        focus.clearFocus()
        onIntent(SeerrIntent.Login(identifier, submitted))
    }
    val field = Modifier.widthIn(max = Dimensions.formControlMaxWidth).fillMaxWidth()
    val service = state.connection.service
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimensions.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
    ) {
        item { Text(stringResource(Res.string.seerr_setup), modifier = field) }
        state.error?.let { error -> item { Text(seerrErrorText(error), color = MaterialTheme.colorScheme.error, modifier = field) } }
        if (state.connection.phase == SeerrConnectionPhase.Connected ||
            state.connection.phase == SeerrConnectionPhase.VerificationRequired
        ) {
            state.connection.user?.let { user ->
                item { Text(stringResource(Res.string.seerr_connected, user.displayName), modifier = field) }
            }
            state.connection.endpoint?.let { endpoint ->
                item { Text(stringResource(Res.string.seerr_endpoint, endpoint), modifier = field) }
            }
            if (service?.usesPlainHttp == true) item { Text(stringResource(Res.string.seerr_http), modifier = field) }
            item {
                FlowRow(modifier = field, horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                    Button(enabled = !state.busy, onClick = {
                        onIntent(SeerrIntent.RestoreConnection)
                    }) { Text(stringResource(Res.string.seerr_verify_connection)) }
                    TextButton(enabled = !state.busy, onClick = {
                        password = ""
                        onIntent(SeerrIntent.Disconnect)
                    }) { Text(stringResource(Res.string.seerr_disconnect)) }
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { onIntent(SeerrIntent.UrlChanged(it)) },
                    label = { Text(stringResource(Res.string.seerr_url)) },
                    enabled = !state.busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions =
                        KeyboardActions(onGo = {
                            keyboard?.hide()
                            focus.clearFocus()
                            onIntent(SeerrIntent.ValidateService)
                        }),
                    modifier = field,
                )
            }
            item {
                Button(enabled = !state.busy && state.url.isNotBlank(), onClick = {
                    keyboard?.hide()
                    focus.clearFocus()
                    onIntent(SeerrIntent.ValidateService)
                }) { Text(stringResource(Res.string.seerr_validate)) }
            }
            if (service != null) {
                item { Text(stringResource(Res.string.seerr_validated, service.version), modifier = field) }
                if (service.usesPlainHttp) item { Text(stringResource(Res.string.seerr_http), modifier = field) }
                item {
                    FlowRow(modifier = field, horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                        if (service.jellyfinLoginEnabled) {
                            FilterChip(
                                selected = state.method == SeerrLoginMethod.Jellyfin,
                                enabled = !state.busy,
                                onClick = {
                                    password = ""
                                    onIntent(SeerrIntent.SelectLogin(SeerrLoginMethod.Jellyfin))
                                },
                                label = { Text(stringResource(Res.string.seerr_jellyfin_login)) },
                            )
                        }
                        if (service.localLoginEnabled) {
                            FilterChip(
                                selected = state.method == SeerrLoginMethod.Local,
                                enabled = !state.busy,
                                onClick = {
                                    password = ""
                                    onIntent(SeerrIntent.SelectLogin(SeerrLoginMethod.Local))
                                },
                                label = { Text(stringResource(Res.string.seerr_local_login)) },
                            )
                        }
                    }
                }
                if (service.jellyfinLoginEnabled || service.localLoginEnabled) {
                    if (state.method ==
                        SeerrLoginMethod.Local
                    ) {
                        item { Text(stringResource(Res.string.seerr_local_explanation), modifier = field) }
                    }
                    item {
                        OutlinedTextField(
                            value = identifier,
                            onValueChange = { identifier = it },
                            enabled = !state.busy,
                            label = {
                                Text(
                                    stringResource(
                                        if (state.method ==
                                            SeerrLoginMethod.Local
                                        ) {
                                            Res.string.seerr_email
                                        } else {
                                            Res.string.seerr_username
                                        },
                                    ),
                                )
                            },
                            singleLine = true,
                            modifier = field,
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        if (state.method ==
                                            SeerrLoginMethod.Local
                                        ) {
                                            KeyboardType.Email
                                        } else {
                                            KeyboardType.Text
                                        },
                                ),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            enabled = !state.busy,
                            label = { Text(stringResource(Res.string.seerr_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { submit() }),
                            modifier = field,
                        )
                    }
                    item {
                        Button(enabled = !state.busy && identifier.isNotBlank() && password.isNotEmpty(), onClick = ::submit) {
                            Text(stringResource(Res.string.seerr_sign_in))
                        }
                    }
                }
                if (service.quickConnectSupported) {
                    item {
                        Button(enabled = !state.busy, onClick = {
                            password = ""
                            keyboard?.hide()
                            onIntent(SeerrIntent.BeginQuickConnect)
                        }) { Text(stringResource(Res.string.seerr_quick_connect)) }
                    }
                }
            }
        }
        state.quickConnect?.let { code ->
            item {
                Text(
                    stringResource(Res.string.seerr_quick_connect_code, code.code),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = field,
                )
            }
            item { Text(stringResource(Res.string.seerr_quick_connect_wait), modifier = field) }
        }
        if (state.busy) item { Text(stringResource(Res.string.seerr_working)) }
        item {
            TextButton(onClick = {
                password = ""
                identifier = ""
                keyboard?.hide()
                focus.clearFocus()
                onIntent(SeerrIntent.CancelAuthentication)
            }) {
                Text(stringResource(Res.string.seerr_cancel))
            }
        }
    }
}

@Composable
fun seerrErrorText(error: SeerrError): String =
    stringResource(
        when (error) {
            SeerrError.InvalidUrl -> Res.string.seerr_error_invalid_url
            SeerrError.UnsupportedService -> Res.string.seerr_error_unsupported
            SeerrError.SetupRequired -> Res.string.seerr_error_setup
            SeerrError.HttpsRequired -> Res.string.seerr_error_https
            SeerrError.SecurityRejected -> Res.string.seerr_error_security
            SeerrError.RedirectRejected -> Res.string.seerr_error_redirect
            SeerrError.InvalidCredentials -> Res.string.seerr_error_credentials
            SeerrError.AccountMismatch -> Res.string.seerr_error_account
            SeerrError.AuthenticationRequired -> Res.string.seerr_error_auth
            SeerrError.SessionExpired -> Res.string.seerr_error_expired
            SeerrError.VerificationUnavailable -> Res.string.seerr_error_verification
            SeerrError.QuickConnectUnavailable -> Res.string.seerr_error_qc_unavailable
            SeerrError.QuickConnectExpired -> Res.string.seerr_error_qc_expired
            SeerrError.RestrictedAccount -> Res.string.seerr_restricted
            SeerrError.PermissionDenied -> Res.string.seerr_error_permission
            SeerrError.QuotaUnavailable -> Res.string.seerr_quota_unavailable
            SeerrError.QuotaExceeded -> Res.string.seerr_error_quota
            SeerrError.AlreadyRequested -> Res.string.seerr_error_requested
            SeerrError.AlreadyAvailable -> Res.string.seerr_error_available
            SeerrError.Blocklisted -> Res.string.seerr_error_blocklisted
            SeerrError.MetadataUnavailable -> Res.string.seerr_error_metadata
            SeerrError.InvalidSelection -> Res.string.seerr_error_selection
            SeerrError.NoSeasonsAvailable -> Res.string.seerr_error_no_seasons
            SeerrError.RequestInProgress -> Res.string.seerr_error_in_progress
            SeerrError.ReconciliationRequired -> Res.string.seerr_uncertain
            SeerrError.LibraryItemUnavailable -> Res.string.seerr_error_library
            SeerrError.RateLimited -> Res.string.seerr_error_rate
            SeerrError.NetworkUnavailable -> Res.string.seerr_error_network
            SeerrError.InvalidResponse -> Res.string.seerr_error_response
            SeerrError.PersistenceFailed -> Res.string.seerr_error_persistence
            SeerrError.StaleOwner -> Res.string.seerr_owner_changed
        },
    )
