// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.jellyscope.core.domain.model.SeerrDetail
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.component.AmbientColorExtractor
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.DetailBodyStyle
import com.jellyscope.ui.component.DetailHeadlineStyle
import com.jellyscope.ui.component.DetailHeroBackdrop
import com.jellyscope.ui.component.DetailHeroLayout
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.HeroDetailBackButton
import com.jellyscope.ui.component.PublicArtworkOwner
import com.jellyscope.ui.component.publicArtworkRequest
import com.jellyscope.ui.component.rememberArtworkAmbientColor
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.seerr_back
import com.jellyscope.ui.generated.resources.seerr_untitled
import com.jellyscope.ui.screen.detail.DetailDimens
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** The detail entry owns artwork for the whole scrolling surface, independently of browse settings. */
@Composable
fun SeerrDetailSurface(
    session: Session,
    state: SeerrDetailUi,
    modifier: Modifier = Modifier,
    isTv: Boolean = false,
    extractor: AmbientColorExtractor = koinInject(),
    content: @Composable BoxScope.() -> Unit,
) {
    val account = session.accountIdentity()
    val entry = "seerr:${state.entryId}:${state.key.stableId}"
    val url = seerrArtworkUrl(state.detail?.backdropUrl) ?: seerrArtworkUrl(state.detail?.media?.posterUrl)
    val owner = url?.let { PublicArtworkOwner(account, entry, it) }
    val ambient = rememberArtworkAmbientColor(account, entry, url, extractor, owner)
    val compact = !isTv && LocalWindowWidthTier.current == WindowWidthTier.Compact
    Box(modifier.fillMaxSize()) {
        AmbientLayer(ambientColor = ambient.color, animationOwnerKey = entry)
        DetailHeroBackdrop(
            artworkUrl = url,
            imageRequest = { source -> publicArtworkRequest(PublicArtworkOwner(account, entry, source)) },
            onImageLoaded = ambient::onImageLoaded,
            modifier = Modifier.align(Alignment.TopEnd),
            compact = compact,
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeerrDetailHero(
    detail: SeerrDetail,
    onBack: () -> Unit,
    showBack: Boolean = true,
    isTv: Boolean = false,
    passiveQuality: SeerrQuality? = null,
    actions: @Composable () -> Unit = {},
) {
    val compact = !isTv && LocalWindowWidthTier.current == WindowWidthTier.Compact
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = if (isTv) Dimensions.zero else Dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(DetailDimens.detailSectionGap),
        ) {
            DetailHeroLayout(
                topPadding = if (showBack) Dimensions.minTouchTarget else DetailDimens.detailHeroTopPadding,
                compact = compact,
            ) {
                DetailText(
                    detail.media.title ?: stringResource(Res.string.seerr_untitled),
                    style = DetailHeadlineStyle.copy(fontWeight = FontWeight.Bold),
                    maxLines = if (isTv) 2 else Int.MAX_VALUE,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.labelGlyphSpacing),
                ) {
                    DetailText(
                        listOfNotNull(detail.media.year?.toString(), seerrMediaTypeText(detail.media.key.type)).joinToString(" · "),
                        color = LocalJellyfinPalette.current.textSecondary,
                    )
                    passiveQuality?.let { quality ->
                        Surface(
                            shape = RoundedCornerShape(Dimensions.badgePadding),
                            color = LocalJellyfinPalette.current.surfaceRaised,
                            contentColor = LocalJellyfinPalette.current.textPrimary,
                        ) {
                            DetailText(
                                seerrQualityText(quality),
                                modifier =
                                    Modifier.padding(
                                        horizontal = Dimensions.badgeHorizontalPadding,
                                        vertical = Dimensions.badgeVerticalPadding,
                                    ),
                            )
                        }
                    }
                }
                detail.overview?.takeIf { it.isNotBlank() }?.let {
                    DetailText(
                        it,
                        style = DetailBodyStyle,
                        color = LocalJellyfinPalette.current.textSecondary,
                        maxLines = if (isTv) 3 else Int.MAX_VALUE,
                    )
                }
            }
            actions()
        }
        if (showBack) HeroDetailBackButton(stringResource(Res.string.seerr_back), onBack, Modifier.align(Alignment.TopStart))
    }
}
