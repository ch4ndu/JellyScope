// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerState
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.isTvDownloadsVisible
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.delay
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailText as TvText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvNavigationDrawer(
    session: Session,
    drawerState: DrawerState,
    selected: TvRailDestination?,
    onSearchSelected: () -> Unit,
    onDiscoverSelected: () -> Unit,
    onFavoritesSelected: () -> Unit,
    onHomeSelected: () -> Unit,
    onDownloadsSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    modifier: Modifier = Modifier,
    libraries: List<Library> = emptyList(),
    onLibrarySelected: (Library) -> Unit = {},
    selectedLibraryId: String? = null,
    focusFallbackDestination: TvRailDestination? = null,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onTargetFocused: (TvRailTarget) -> Unit = {},
    selectedItemFocusRequester: FocusRequester? = null,
    contentRightFocusRequester: FocusRequester? = null,
    onExitRight: (() -> Boolean)? = null,
    underlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val selectedLibraryInDrawer =
        selectedLibraryId != null && libraries.any { library -> library.id == selectedLibraryId }
    val hasSelectedFocusTarget = selected != null || selectedLibraryInDrawer
    val requestContentRightFocus =
        onExitRight ?: contentRightFocusRequester?.let { requester ->
            { requester.requestFocusSafely() }
        }

    fun focusRequesterFor(destination: TvRailDestination): FocusRequester? =
        selectedItemFocusRequester.takeIf {
            selected == destination ||
                (!hasSelectedFocusTarget && focusFallbackDestination == destination)
        }

    val expanded = drawerState.currentValue == DrawerValue.Open
    val drawerWidth by animateDpAsState(
        targetValue =
            if (expanded) {
                TvDimens.drawerExpandedWidth
            } else {
                TvDimens.drawerCollapsedWidth
            },
        animationSpec = tween(durationMillis = TV_DRAWER_WIDTH_ANIMATION_MS),
        label = "tv-drawer-width",
    )

    BoxWithConstraints(modifier = modifier) {
        val visibleContentWidth = maxOf(0.dp, maxWidth - drawerWidth)
        val measuredContentWidth = maxOf(0.dp, maxWidth - TvDimens.drawerCollapsedWidth)
        Box(modifier = Modifier.fillMaxSize(), content = underlay)
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .background(
                            if (expanded) {
                                LocalJellyfinPalette.current.navy
                            } else {
                                Color.Transparent
                            },
                        ).verticalScroll(rememberScrollState())
                        .focusProperties {
                            onEnter = {
                                selectedItemFocusRequester?.requestFocusSafely()
                            }
                        }.then(
                            selectedItemFocusRequester?.let { requester ->
                                Modifier.focusRestorer(requester)
                            } ?: Modifier.focusRestorer(),
                        ).focusGroup()
                        .onFocusChanged { state ->
                            drawerState.setValue(
                                if (state.hasFocus) {
                                    DrawerValue.Open
                                } else {
                                    DrawerValue.Closed
                                },
                            )
                            onFocusStateChanged(state.hasFocus)
                        }.padding(vertical = TvDimens.drawerVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(TvDimens.drawerSectionGap),
            ) {
                TvDrawerUserBlock(
                    userName = session.userName,
                    expanded = expanded,
                    modifier = Modifier.fillMaxWidth(),
                )
                TvDrawerItem(
                    label = stringResource(R.string.tv_search),
                    icon = TvDrawerIconKind.Search,
                    selected = selected == TvRailDestination.Find,
                    expanded = expanded,
                    onClick = onSearchSelected,
                    onFocused = { onTargetFocused(TvRailTarget.Find) },
                    focusRequester = focusRequesterFor(TvRailDestination.Find),
                    rightFocusRequester = contentRightFocusRequester,
                    onExitRight = requestContentRightFocus,
                )
                TvDrawerItem(
                    label = stringResource(R.string.tv_home_tab),
                    icon = TvDrawerIconKind.Home,
                    selected = selected == TvRailDestination.Home,
                    expanded = expanded,
                    onClick = onHomeSelected,
                    onFocused = { onTargetFocused(TvRailTarget.Home) },
                    focusRequester = focusRequesterFor(TvRailDestination.Home),
                    rightFocusRequester = contentRightFocusRequester,
                    onExitRight = requestContentRightFocus,
                )
                TvDrawerItem(
                    label = stringResource(R.string.tv_favorites),
                    icon = TvDrawerIconKind.Favorite,
                    selected = selected == TvRailDestination.Favorites,
                    expanded = expanded,
                    onClick = onFavoritesSelected,
                    onFocused = { onTargetFocused(TvRailTarget.Favorites) },
                    focusRequester = focusRequesterFor(TvRailDestination.Favorites),
                    rightFocusRequester = contentRightFocusRequester,
                    onExitRight = requestContentRightFocus,
                )
                if (isTvDownloadsVisible(session)) {
                    TvDrawerItem(
                        label = stringResource(R.string.tv_downloads_tab),
                        icon = TvDrawerIconKind.Downloads,
                        selected = selected == TvRailDestination.Downloads,
                        expanded = expanded,
                        onClick = onDownloadsSelected,
                        onFocused = { onTargetFocused(TvRailTarget.Downloads) },
                        focusRequester = focusRequesterFor(TvRailDestination.Downloads),
                        rightFocusRequester = contentRightFocusRequester,
                        onExitRight = requestContentRightFocus,
                    )
                }
                libraries.forEach { library ->
                    val librarySelected = library.id == selectedLibraryId
                    val libraryTarget = TvRailTarget.Library(library.id)
                    TvDrawerItem(
                        label = library.name,
                        icon = TvDrawerIconKind.Library,
                        selected = librarySelected,
                        expanded = expanded,
                        onClick = { onLibrarySelected(library) },
                        onFocused = { onTargetFocused(libraryTarget) },
                        focusRequester = selectedItemFocusRequester.takeIf { librarySelected },
                        rightFocusRequester = contentRightFocusRequester,
                        onExitRight = requestContentRightFocus,
                    )
                }
                TvDrawerItem(
                    label = stringResource(R.string.tv_discover_tab),
                    icon = TvDrawerIconKind.Discover,
                    selected = selected == TvRailDestination.Discover,
                    expanded = expanded,
                    onClick = onDiscoverSelected,
                    onFocused = { onTargetFocused(TvRailTarget.Discover) },
                    focusRequester = focusRequesterFor(TvRailDestination.Discover),
                    rightFocusRequester = contentRightFocusRequester,
                    onExitRight = requestContentRightFocus,
                )
                TvDrawerItem(
                    label = stringResource(R.string.tv_settings_tab),
                    icon = TvDrawerIconKind.Settings,
                    selected = selected == TvRailDestination.Settings,
                    expanded = expanded,
                    onClick = onSettingsSelected,
                    onFocused = { onTargetFocused(TvRailTarget.Settings) },
                    focusRequester = focusRequesterFor(TvRailDestination.Settings),
                    rightFocusRequester = contentRightFocusRequester,
                    onExitRight = requestContentRightFocus,
                )
            }
            TvDrawerContentSlot(
                visibleWidth = visibleContentWidth,
                measuredWidth = measuredContentWidth,
                content = content,
            )
        }
    }
}

@Composable
private fun TvDrawerContentSlot(
    visibleWidth: Dp,
    measuredWidth: Dp,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier =
            Modifier
                .width(visibleWidth)
                .fillMaxHeight()
                .clipToBounds(),
    ) { measurables, constraints ->
        val contentWidth = measuredWidth.roundToPx().coerceAtLeast(0)
        val placeables =
            measurables.map { measurable ->
                measurable.measure(
                    constraints.copy(
                        minWidth = contentWidth,
                        maxWidth = contentWidth,
                    ),
                )
            }
        layout(width = constraints.maxWidth, height = constraints.maxHeight) {
            placeables.forEach { placeable -> placeable.placeRelative(0, 0) }
        }
    }
}

enum class TvRailDestination {
    Find,
    Discover,
    Favorites,
    Home,
    Downloads,
    Settings,
}

@Composable
private fun TvDrawerUserBlock(
    userName: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .padding(start = if (expanded) TvDimens.drawerUserExpandedStartPadding else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(
                        if (expanded) {
                            TvDimens.drawerUserExpandedAvatarSize
                        } else {
                            TvDimens.drawerUserCollapsedAvatarSize
                        },
                    ).clip(CircleShape)
                    .background(LocalJellyfinPalette.current.cyan),
            contentAlignment = Alignment.Center,
        ) {
            TvText(
                text =
                    userName
                        .firstOrNull()
                        ?.uppercaseChar()
                        ?.toString()
                        .orEmpty(),
                style = TvBodyStyle.copy(fontWeight = FontWeight.Bold),
                color = LocalJellyfinPalette.current.onFocusedLight,
                maxLines = 1,
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.width(TvDimens.drawerUserNameGap))
            TvText(
                text = userName,
                style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvDrawerItem(
    label: String,
    icon: TvDrawerIconKind,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
    onExitRight: (() -> Boolean)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val palette = LocalJellyfinPalette.current
    val contentColor =
        when {
            focused -> palette.onFocusedLight
            selected -> palette.cyan
            else -> palette.textSecondary
        }

    LaunchedEffect(focused) {
        if (focused) {
            delay(TV_DRAWER_TARGET_DWELL_MS.toLong())
            if (focused) {
                onFocused()
            }
        }
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TvDimens.drawerItemHeight)
                .clip(RoundedCornerShape(TvDimens.drawerItemRadius))
                .background(if (focused) Color.White else Color.Transparent)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(
                    rightFocusRequester?.let { requester ->
                        Modifier.focusProperties { right = requester }
                    } ?: Modifier,
                ).onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionRight &&
                        onExitRight?.invoke() == true
                }.onFocusChanged { state ->
                    focused = state.isFocused
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).focusable()
                .semantics {
                    contentDescription = label
                    role = Role.Button
                }.padding(
                    start = if (expanded) TvDimens.drawerItemExpandedStartPadding else 0.dp,
                    end = if (expanded) TvDimens.drawerItemExpandedEndPadding else 0.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        TvDrawerIcon(
            icon = icon,
            color = contentColor,
            modifier = Modifier.size(TvDimens.drawerIconSize),
        )
        if (expanded) {
            Spacer(modifier = Modifier.width(TvDimens.drawerLabelGap))
            TvText(
                text = label,
                style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvDrawerIcon(
    icon: TvDrawerIconKind,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector =
            when (icon) {
                TvDrawerIconKind.Search -> TvIcons.Magnify
                TvDrawerIconKind.Discover -> TvIcons.Compass
                TvDrawerIconKind.Favorite -> TvIcons.HeartOutline
                TvDrawerIconKind.Home -> TvIcons.Home
                TvDrawerIconKind.Downloads -> TvIcons.Download
                TvDrawerIconKind.Library -> TvIcons.ViewGrid
                TvDrawerIconKind.Settings -> TvIcons.Cog
            },
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}

private enum class TvDrawerIconKind {
    Search,
    Discover,
    Favorite,
    Home,
    Downloads,
    Library,
    Settings,
}

private const val TV_DRAWER_WIDTH_ANIMATION_MS = 180
private const val TV_DRAWER_TARGET_DWELL_MS = 500
