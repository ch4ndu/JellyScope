// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.DetailTitleStyle
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.focus.rememberRestoreAwareBringIntoViewSpec

@Composable
internal fun <T> FocusableRibbon(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    horizontalBringIntoViewSpec: BringIntoViewSpec,
    modifier: Modifier = Modifier,
    rowListState: LazyListState = rememberLazyListState(),
    rowModifier: Modifier = Modifier,
    contentPadding: PaddingValues = detailShelfPadding(),
    focusRequester: FocusRequester? = null,
    focusContainer: DetailFocusContainer? = null,
    focusFallback: () -> List<Any> = { items.firstOrNull()?.let { item -> listOf(key(item)) } ?: emptyList() },
    itemContent: @Composable (item: T, focusModifier: Modifier) -> Unit,
) {
    val ownedFocusContainer = rememberDetailFocusContainer()
    val activeFocusContainer = focusContainer ?: ownedFocusContainer
    val restoreRequest = activeFocusContainer.restoreRequest()
    val dpadMode = isDetailDpadMode()
    val restoreAwareSpec =
        rememberRestoreAwareBringIntoViewSpec(
            horizontalBringIntoViewSpec,
            activeFocusContainer.restoreHandoffActive,
        )

    LaunchedEffect(restoreRequest, items, rowListState) {
        val request = restoreRequest ?: return@LaunchedEffect
        if (items.isEmpty() || !dpadMode) return@LaunchedEffect
        val semanticKeys = items.map { item -> key(item).toString() }
        activeFocusContainer.restoreFocus(
            request = request,
            semanticKeys = semanticKeys,
            lazySlotIndex = { it },
            revealCentered = { index ->
                rowListState.scrollToItem(index)
                val itemInfo = rowListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (itemInfo != null) {
                    val viewportCenter =
                        (rowListState.layoutInfo.viewportStartOffset + rowListState.layoutInfo.viewportEndOffset) / 2
                    rowListState.scrollToItem(index, -(viewportCenter - itemInfo.size / 2))
                }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth().then(modifier),
        verticalArrangement = Arrangement.spacedBy(DetailDimens.detailShelfTitleGap),
    ) {
        DetailText(
            text = title,
            modifier = Modifier.padding(horizontal = detailHorizontalInset()),
            style = DetailTitleStyle,
        )
        DetailBringIntoViewProvider(restoreAwareSpec) {
            LazyRow(
                state = rowListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(rowModifier)
                        .detailFocusRequester(focusRequester)
                        .detailFocusContainer(activeFocusContainer, focusFallback)
                        .then(
                            if (isDetailDpadMode()) {
                                Modifier
                            } else {
                                Modifier.desktopScrollInput(rowListState, DesktopScrollOrientation.Horizontal)
                            },
                        ),
                horizontalArrangement = Arrangement.spacedBy(DetailDimens.itemGap),
                contentPadding = contentPadding,
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> key(item) },
                ) { semanticIndex, item ->
                    val focusModifier = Modifier.detailFocusChild(activeFocusContainer, key(item), semanticIndex)
                    itemContent(item, focusModifier)
                }
            }
        }
    }
}
