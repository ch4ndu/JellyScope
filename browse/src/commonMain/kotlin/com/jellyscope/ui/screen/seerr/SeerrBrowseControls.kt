// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.ui.component.SelectableActionButton
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.library_filters
import com.jellyscope.ui.generated.resources.player_picker_close
import com.jellyscope.ui.generated.resources.seerr_back
import com.jellyscope.ui.generated.resources.seerr_filters
import com.jellyscope.ui.generated.resources.seerr_hide_available
import com.jellyscope.ui.generated.resources.seerr_hide_available_hint
import com.jellyscope.ui.generated.resources.seerr_manage
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeerrBrowseControls(
    section: SeerrSection,
    catalog: SeerrCatalog,
    hideAvailable: Boolean,
    browseEligible: Boolean,
    onIntent: (SeerrIntent) -> Unit,
) {
    val pickerEligible = browseEligible && section == SeerrSection.Discover
    var catalogOpen by remember(pickerEligible) { mutableStateOf(false) }
    var filtersOpen by remember(pickerEligible) { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth().padding(Dimensions.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        if (section == SeerrSection.Connection) {
            TextButton(onClick = { onIntent(SeerrIntent.SelectSection(SeerrSection.Discover)) }) {
                Text(stringResource(Res.string.seerr_back))
            }
        } else {
            listOf(SeerrSection.Discover, SeerrSection.Requests, SeerrSection.Search).forEach {
                SelectableActionButton(seerrSectionText(it), section == it, { onIntent(SeerrIntent.SelectSection(it)) })
            }
            Spacer(Modifier.weight(1f))
            if (section == SeerrSection.Discover) {
                Box {
                    TextButton(
                        enabled = pickerEligible,
                        onClick = { if (pickerEligible) catalogOpen = true },
                    ) { Text(seerrCatalogText(catalog)) }
                    DropdownMenu(catalogOpen && pickerEligible, { catalogOpen = false }) {
                        listOf(SeerrCatalog.Trending, SeerrCatalog.Movies, SeerrCatalog.Tv).forEach {
                            DropdownMenuItem(text = { Text(seerrCatalogText(it)) }, onClick = {
                                catalogOpen = false
                                onIntent(SeerrIntent.SelectCatalog(it))
                            })
                        }
                    }
                }
                TextButton(enabled = pickerEligible, onClick = {
                    if (pickerEligible) filtersOpen = true
                }) { Text(stringResource(Res.string.seerr_filters, if (hideAvailable) 1 else 0)) }
            }
            IconButton(onClick = { onIntent(SeerrIntent.SelectSection(SeerrSection.Connection)) }) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.seerr_manage))
            }
        }
    }
    if (filtersOpen && pickerEligible) {
        AlertDialog(
            onDismissRequest = { filtersOpen = false },
            title = { Text(stringResource(Res.string.library_filters)) },
            text = {
                Column {
                    FilterChip(selected = hideAvailable, onClick = {
                        onIntent(SeerrIntent.ToggleHideAvailable)
                    }, label = { Text(stringResource(Res.string.seerr_hide_available)) })
                    Text(stringResource(Res.string.seerr_hide_available_hint))
                }
            },
            confirmButton = { TextButton(onClick = { filtersOpen = false }) { Text(stringResource(Res.string.player_picker_close)) } },
        )
    }
}
