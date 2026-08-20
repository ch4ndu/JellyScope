// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.tv.ui.focus.TvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusRestoreStatus
import com.jellyscope.tv.ui.focus.TvFocusScopeNode
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TvLibraryRecommendationFocusTest {
    @Test
    fun ribbonIdentityIncludesSectionAndRowKey() {
        val section = LibraryRecommendationSection.MovieRecommendations

        assertNotEquals(
            recommendationRibbonIdentity(section, "because-you-watched-a"),
            recommendationRibbonIdentity(section, "because-you-watched-b"),
        )
        assertNotEquals(
            recommendationRibbonIdentity(LibraryRecommendationSection.RecentlyAdded, "shared-row"),
            recommendationRibbonIdentity(section, "shared-row"),
        )
    }

    @Test
    fun duplicateAssetRestoreTargetsOnlyItsOwningRibbonAndPreservesSiblingMemory() {
        val coordinator = TvFocusCoordinator()
        val duplicateAssetKey = "item:asset-42"
        val ribbonAScopes = recommendationRibbonScopes("because-you-watched-a")
        val ribbonBScopes = recommendationRibbonScopes("because-you-watched-b")
        val ribbonA = TvFocusScopeNode(ribbonAScopes, coordinator)
        val ribbonB = TvFocusScopeNode(ribbonBScopes, coordinator)

        ribbonA.onChildFocused(duplicateAssetKey, TvFocusTargetKind.Item, semanticIndex = 2)
        ribbonB.onChildFocused(duplicateAssetKey, TvFocusTargetKind.Item, semanticIndex = 4)
        val parentId = coordinator.activeRouteEntryId
        val memory = coordinator.activeMemory
        val target = ribbonA.path(duplicateAssetKey, TvFocusTargetKind.Item, fallbackIndex = 2)

        coordinator.restoreParent(parentId, target, memory)
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.PendingGroup)

        val ribbonARequest = assertNotNull(ribbonA.restoreRequest())
        assertEquals(duplicateAssetKey, ribbonARequest.key)
        assertEquals(2, ribbonARequest.fallbackSemanticIndex)
        assertNull(ribbonB.restoreRequest())
        assertEquals(duplicateAssetKey, coordinator.activeMemory.childFor(ribbonA.scopeKey))
        assertEquals(duplicateAssetKey, coordinator.activeMemory.childFor(ribbonB.scopeKey))
        assertEquals(
            setOf(ribbonA.scopeKey, ribbonB.scopeKey),
            coordinator.activeMemory.entries
                .filter { entry -> entry.childKey == duplicateAssetKey }
                .map { entry -> entry.scopeKey }
                .toSet(),
        )
    }

    private fun recommendationRibbonScopes(rowKey: String): List<String> =
        listOf(
            "library",
            "recommended",
            "row:${recommendationRibbonIdentity(LibraryRecommendationSection.MovieRecommendations, rowKey)}",
        )
}
