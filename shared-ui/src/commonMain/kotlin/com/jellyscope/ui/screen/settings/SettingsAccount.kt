// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.jellyscope.core.domain.model.AccountSession
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.login_password_label
import com.jellyscope.ui.generated.resources.login_username_label
import com.jellyscope.ui.generated.resources.server_entry_url_label
import com.jellyscope.ui.generated.resources.settings_account
import com.jellyscope.ui.generated.resources.settings_account_active
import com.jellyscope.ui.generated.resources.settings_account_active_value
import com.jellyscope.ui.generated.resources.settings_account_add_password_cd
import com.jellyscope.ui.generated.resources.settings_account_add_server_cd
import com.jellyscope.ui.generated.resources.settings_account_add_submit
import com.jellyscope.ui.generated.resources.settings_account_add_title
import com.jellyscope.ui.generated.resources.settings_account_add_username_cd
import com.jellyscope.ui.generated.resources.settings_account_download_removal_confirm
import com.jellyscope.ui.generated.resources.settings_account_download_removal_message
import com.jellyscope.ui.generated.resources.settings_account_download_removal_title
import com.jellyscope.ui.generated.resources.settings_account_error_account_not_found
import com.jellyscope.ui.generated.resources.settings_account_error_invalid_credentials
import com.jellyscope.ui.generated.resources.settings_account_error_not_reachable
import com.jellyscope.ui.generated.resources.settings_account_error_removal
import com.jellyscope.ui.generated.resources.settings_account_error_server
import com.jellyscope.ui.generated.resources.settings_account_remove
import com.jellyscope.ui.generated.resources.settings_account_server_label
import com.jellyscope.ui.generated.resources.settings_account_sign_out
import com.jellyscope.ui.generated.resources.settings_account_sign_out_message
import com.jellyscope.ui.generated.resources.settings_account_switch
import com.jellyscope.ui.generated.resources.settings_accounts
import com.jellyscope.ui.generated.resources.settings_active_account
import com.jellyscope.ui.generated.resources.settings_add_account
import com.jellyscope.ui.generated.resources.settings_picker_cancel
import com.jellyscope.ui.generated.resources.settings_server
import com.jellyscope.ui.generated.resources.settings_server_url
import com.jellyscope.ui.generated.resources.settings_sign_out
import com.jellyscope.ui.generated.resources.settings_signed_in_user
import com.jellyscope.ui.screen.account.AccountUiError
import com.jellyscope.ui.screen.account.AccountUiState
import com.jellyscope.ui.screen.downloads.formatIntegerBytes
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AccountSettingsSection(
    state: SettingsUiState,
    accountState: AccountUiState,
    onLogout: () -> Unit,
    onAddAccount: (String, String, String) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onSignOutAccount: (String) -> Unit,
    onConfirmAccountRemoval: () -> Unit = {},
    onDismissAccountRemoval: () -> Unit = {},
    openRow: SettingsRowId?,
    onOpenRow: (SettingsRowId) -> Unit,
    onDismiss: () -> Unit,
) {
    var accountDialogSubmitted by remember { mutableStateOf(false) }
    var addDialogSubmitted by remember { mutableStateOf(false) }
    var addOperationStarted by remember { mutableStateOf(false) }

    LaunchedEffect(openRow) {
        if (openRow != SettingsRowId.ActiveAccount) {
            accountDialogSubmitted = false
        }
        if (openRow != SettingsRowId.AddAccount) {
            addDialogSubmitted = false
            addOperationStarted = false
        }
    }
    LaunchedEffect(openRow, accountState.isAddingAccount, accountState.error, addDialogSubmitted) {
        if (openRow == SettingsRowId.AddAccount && accountState.isAddingAccount) {
            addOperationStarted = true
        }
        if (
            openRow == SettingsRowId.AddAccount &&
            addDialogSubmitted &&
            addOperationStarted &&
            !accountState.isAddingAccount &&
            accountState.error == null
        ) {
            addDialogSubmitted = false
            addOperationStarted = false
            onDismiss()
        }
    }

    val activeAccount = accountState.accounts.firstOrNull { account -> account.isActive }
    val activeAccountValue =
        stringResource(
            Res.string.settings_account_active_value,
            activeAccount?.userName ?: state.userName,
            activeAccount?.serverName ?: state.serverName,
        )

    SettingsSectionCard(
        title = stringResource(Res.string.settings_account),
        rows =
            listOf(
                {
                    SettingsRow(
                        icon = SettingsRowId.Server.icon(),
                        iconRole = SettingsRowId.Server.iconRole(),
                        title = stringResource(Res.string.settings_server),
                        value = stringResource(Res.string.settings_server_url, state.serverUrl),
                        trailing = SettingsRowTrailing.None,
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.SignedInUser.icon(),
                        iconRole = SettingsRowId.SignedInUser.iconRole(),
                        title = stringResource(Res.string.settings_signed_in_user),
                        value = state.userName,
                        trailing = SettingsRowTrailing.None,
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.ActiveAccount.icon(),
                        iconRole = SettingsRowId.ActiveAccount.iconRole(),
                        title = stringResource(Res.string.settings_active_account),
                        value = activeAccountValue,
                        trailing = SettingsRowTrailing.Chevron,
                        onClick = { onOpenRow(SettingsRowId.ActiveAccount) },
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.AddAccount.icon(),
                        iconRole = SettingsRowId.AddAccount.iconRole(),
                        title = stringResource(Res.string.settings_add_account),
                        trailing = SettingsRowTrailing.Chevron,
                        onClick = { onOpenRow(SettingsRowId.AddAccount) },
                    )
                },
                {
                    SettingsRow(
                        icon = SettingsRowId.SignOut.icon(),
                        iconRole = SettingsRowId.SignOut.iconRole(),
                        title = stringResource(Res.string.settings_sign_out),
                        trailing = SettingsRowTrailing.Chevron,
                        onClick = { onOpenRow(SettingsRowId.SignOut) },
                        enabled = !state.isLoggingOut,
                    )
                },
            ),
    )

    if (openRow == SettingsRowId.ActiveAccount) {
        AccountsDialog(
            state = accountState,
            showError = accountDialogSubmitted,
            onSwitchAccount = {
                accountDialogSubmitted = true
                onSwitchAccount(it)
            },
            onSignOutAccount = {
                accountDialogSubmitted = true
                onSignOutAccount(it)
            },
            onDismiss = {
                accountDialogSubmitted = false
                onDismiss()
            },
        )
        accountState.removalPreview?.let { preview ->
            AccountRemovalConfirmationDialog(
                preview = preview,
                onConfirm = onConfirmAccountRemoval,
                onDismiss = onDismissAccountRemoval,
            )
        }
    }
    if (openRow == SettingsRowId.AddAccount) {
        AddAccountDialog(
            state = accountState,
            showError = addDialogSubmitted,
            onAddAccount = { serverUrl, username, password ->
                addDialogSubmitted = true
                onAddAccount(serverUrl, username, password)
            },
            onDismiss = {
                if (!accountState.isAddingAccount) {
                    addDialogSubmitted = false
                    addOperationStarted = false
                    onDismiss()
                }
            },
        )
    }
    if (openRow == SettingsRowId.SignOut) {
        SignOutDialog(
            isLoggingOut = state.isLoggingOut,
            onConfirm = {
                onLogout()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun AccountsDialog(
    state: AccountUiState,
    showError: Boolean,
    onSwitchAccount: (String) -> Unit,
    onSignOutAccount: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_accounts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                if (showError) {
                    state.error?.let { error ->
                        Text(
                            text = stringResource(error.stringResource),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // Keep every account action reachable at large font scales.
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = Dimensions.settingsPickerListMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
                ) {
                    state.accounts.forEach { account ->
                        key(account.accountId) {
                            AccountDialogEntry(
                                account = account,
                                switching = state.switchingAccountId == account.accountId,
                                signingOut = state.signingOutAccountId == account.accountId,
                                anyOperationInFlight =
                                    state.switchingAccountId != null || state.signingOutAccountId != null,
                                onSwitchAccount = { onSwitchAccount(account.accountId) },
                                onSignOutAccount = { onSignOutAccount(account.accountId) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.settings_picker_cancel))
            }
        },
    )
}

@Composable
private fun AccountDialogEntry(
    account: AccountSession,
    switching: Boolean,
    signingOut: Boolean,
    anyOperationInFlight: Boolean,
    onSwitchAccount: () -> Unit,
    onSignOutAccount: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = account.userName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(Res.string.settings_account_server_label, account.serverName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (account.isActive) {
                Text(
                    text = stringResource(Res.string.settings_account_active),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onSwitchAccount,
                enabled = !account.isActive && !anyOperationInFlight,
                modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
            ) {
                if (switching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimensions.controlButtonIconSize),
                        strokeWidth = Dimensions.settingsRowProgressStroke,
                    )
                } else {
                    Text(stringResource(Res.string.settings_account_switch))
                }
            }
            OutlinedButton(
                onClick = onSignOutAccount,
                enabled = !anyOperationInFlight,
                modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
            ) {
                if (signingOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimensions.controlButtonIconSize),
                        strokeWidth = Dimensions.settingsRowProgressStroke,
                    )
                } else {
                    Text(stringResource(Res.string.settings_account_remove))
                }
            }
        }
    }
}

@Composable
private fun AddAccountDialog(
    state: AccountUiState,
    showError: Boolean,
    onAddAccount: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_account_add_title)) },
        text = {
            AddAccountForm(
                isAddingAccount = state.isAddingAccount,
                error = if (showError) state.error else null,
                onAddAccount = onAddAccount,
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isAddingAccount,
            ) {
                Text(stringResource(Res.string.settings_picker_cancel))
            }
        },
    )
}

@Composable
private fun SignOutDialog(
    isLoggingOut: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_sign_out)) },
        text = { Text(stringResource(Res.string.settings_account_sign_out_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isLoggingOut) {
                Text(stringResource(Res.string.settings_account_sign_out))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.settings_picker_cancel))
            }
        },
    )
}

@Composable
private fun AccountRemovalConfirmationDialog(
    preview: DownloadRemovalPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmation = preview.confirmations.firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_account_download_removal_title)) },
        text = {
            Text(
                stringResource(
                    Res.string.settings_account_download_removal_message,
                    confirmation?.recordCount ?: 0L,
                    formatIntegerBytes(confirmation?.displayedBytes ?: 0L),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.settings_account_download_removal_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.settings_picker_cancel))
            }
        },
    )
}

@Composable
private fun AddAccountForm(
    isAddingAccount: Boolean,
    error: AccountUiError?,
    onAddAccount: (String, String, String) -> Unit,
) {
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val serverContentDescription = stringResource(Res.string.settings_account_add_server_cd)
    val usernameContentDescription = stringResource(Res.string.settings_account_add_username_cd)
    val passwordContentDescription = stringResource(Res.string.settings_account_add_password_cd)

    Column(
        modifier = Modifier.widthIn(max = Dimensions.formControlMaxWidth),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        error?.let { accountError ->
            Text(
                text = stringResource(accountError.stringResource),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { value -> serverUrl = value },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = serverContentDescription },
            enabled = !isAddingAccount,
            singleLine = true,
            label = { Text(stringResource(Res.string.server_entry_url_label)) },
        )
        OutlinedTextField(
            value = username,
            onValueChange = { value -> username = value },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = usernameContentDescription },
            enabled = !isAddingAccount,
            singleLine = true,
            label = { Text(stringResource(Res.string.login_username_label)) },
        )
        OutlinedTextField(
            value = password,
            onValueChange = { value -> password = value },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = passwordContentDescription },
            enabled = !isAddingAccount,
            singleLine = true,
            label = { Text(stringResource(Res.string.login_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = { onAddAccount(serverUrl, username, password) },
            enabled =
                !isAddingAccount &&
                    serverUrl.isNotBlank() &&
                    username.isNotBlank() &&
                    password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimensions.minTouchTarget),
        ) {
            if (isAddingAccount) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimensions.controlButtonIconSize),
                    strokeWidth = Dimensions.settingsRowProgressStroke,
                )
            } else {
                Text(stringResource(Res.string.settings_account_add_submit))
            }
        }
    }
}

private val AccountUiError.stringResource: StringResource
    get() =
        when (this) {
            AccountUiError.InvalidCredentials -> Res.string.settings_account_error_invalid_credentials
            AccountUiError.NotReachable -> Res.string.settings_account_error_not_reachable
            AccountUiError.AccountNotFound -> Res.string.settings_account_error_account_not_found
            AccountUiError.RemovalFailed -> Res.string.settings_account_error_removal
            AccountUiError.ServerError -> Res.string.settings_account_error_server
        }
