// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.downloads_allocation
import com.jellyscope.ui.generated.resources.downloads_current_account_stored
import com.jellyscope.ui.generated.resources.downloads_device_free
import com.jellyscope.ui.generated.resources.downloads_manage
import com.jellyscope.ui.generated.resources.downloads_other_accounts_stored
import com.jellyscope.ui.generated.resources.downloads_over_allocation
import com.jellyscope.ui.generated.resources.downloads_remaining_quota
import com.jellyscope.ui.generated.resources.downloads_reservations
import com.jellyscope.ui.generated.resources.downloads_total_stored
import com.jellyscope.ui.generated.resources.downloads_usage
import com.jellyscope.ui.generated.resources.downloads_usage_loading
import com.jellyscope.ui.generated.resources.downloads_usage_summary
import com.jellyscope.ui.screen.downloads.formatIntegerBytes
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadsSettingsSection(
    usage: DownloadUsage?,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSectionCard(
        title = stringResource(Res.string.downloads_usage),
        modifier = modifier,
        rows =
            listOf(
                {
                    SettingsRow(
                        icon = SettingsRowId.Downloads.icon(),
                        iconRole = SettingsRowId.Downloads.iconRole(),
                        title = stringResource(Res.string.downloads_manage),
                        value =
                            if (usage != null) {
                                downloadUsageSummary(usage)
                            } else {
                                stringResource(Res.string.downloads_usage_loading)
                            },
                        trailing = SettingsRowTrailing.Chevron,
                        onClick = onOpenDownloads,
                    )
                },
            ),
        footer = {
            if (usage != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                    DownloadSettingsValueRow(
                        stringResource(Res.string.downloads_total_stored),
                        formatIntegerBytes(usage.physicalBytes),
                    )
                    DownloadSettingsValueRow(
                        stringResource(Res.string.downloads_current_account_stored),
                        formatIntegerBytes(usage.currentAccountPhysicalBytes),
                    )
                    DownloadSettingsValueRow(
                        stringResource(Res.string.downloads_other_accounts_stored),
                        formatIntegerBytes(usage.otherAccountsPhysicalBytes),
                    )
                    DownloadSettingsValueRow(
                        stringResource(Res.string.downloads_reservations),
                        formatIntegerBytes(usage.outstandingReservationBytes),
                    )
                    DownloadSettingsValueRow(
                        stringResource(Res.string.downloads_remaining_quota),
                        usage.remainingQuotaBytes?.let { bytes -> formatIntegerBytes(bytes) }
                            ?: stringResource(Res.string.downloads_allocation),
                    )
                    DownloadSettingsValueRow(
                        stringResource(Res.string.downloads_device_free),
                        formatIntegerBytes(usage.deviceAvailableBytes),
                    )
                    if (usage.overAllocation) {
                        Text(
                            text = stringResource(Res.string.downloads_over_allocation),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DownloadSettingsValueRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun downloadUsageSummary(usage: DownloadUsage): String =
    stringResource(
        Res.string.downloads_usage_summary,
        formatIntegerBytes(usage.physicalBytes),
        formatIntegerBytes(usage.outstandingReservationBytes),
    )
