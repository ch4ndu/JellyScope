// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import com.jellyscope.tv.R
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvFindHeader(
    queryText: String,
    onQueryTextChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    searchFieldRequester: FocusRequester,
    onSearchFieldEditingChange: (Boolean) -> Unit,
    onImeSearch: () -> Unit,
    onEditingCommitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText(
            text = stringResource(R.string.tv_search),
            style = TvScreenHeaderStyle,
            maxLines = 1,
        )
        TvInputField(
            value = queryText,
            onValueChange = onQueryTextChanged,
            label = stringResource(R.string.tv_find_query_empty),
            modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(searchFieldRequester),
            contentDescription = stringResource(R.string.tv_search),
            imeAction = ImeAction.Search,
            onImeAction = onImeSearch,
            onEditingCommitted = onEditingCommitted,
            onEditingChange = onSearchFieldEditingChange,
        )
        TvButton(
            text = stringResource(R.string.tv_find_clear),
            onClick = onClearQuery,
            enabled = queryText.isNotBlank(),
        )
    }
}
