// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import com.jellyscope.core.domain.model.DOWNLOAD_BYTES_PER_GB
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.ui.TV_DOWNLOADS_MANAGE_FOCUS_KEY
import com.jellyscope.tv.ui.TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY
import com.jellyscope.tv.ui.TvRailDestination
import com.jellyscope.tv.ui.TvRailTarget
import com.jellyscope.tv.ui.toRailTarget
import com.jellyscope.tv.ui.tvDownloadCustomQuotaBytes
import com.jellyscope.tv.ui.tvDownloadFocusKey
import com.jellyscope.tv.ui.tvDownloadLazySlotIndex
import com.jellyscope.tv.ui.tvDownloadQuotaOptions
import com.jellyscope.tv.ui.tvDownloadSemanticFocusKeys
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvDownloadsRouteFocusTest {
    @Test
    fun quotaPolicyKeepsPresetsAsShortcutsAndAcceptsAnyCapacityValidWholeGbValue() {
        val safeMaximum = 12L * DOWNLOAD_BYTES_PER_GB

        assertTrue(5L * DOWNLOAD_BYTES_PER_GB in tvDownloadQuotaOptions(null, safeMaximum))
        assertEquals(3L * DOWNLOAD_BYTES_PER_GB, tvDownloadCustomQuotaBytes("3", safeMaximum))
        assertEquals(12L * DOWNLOAD_BYTES_PER_GB, tvDownloadCustomQuotaBytes(" 12 ", safeMaximum))
        assertNull(tvDownloadCustomQuotaBytes("", safeMaximum))
        assertNull(tvDownloadCustomQuotaBytes("-1", safeMaximum))
        assertNull(tvDownloadCustomQuotaBytes("0", safeMaximum))
        assertNull(tvDownloadCustomQuotaBytes("13", safeMaximum))
        assertNull(tvDownloadCustomQuotaBytes("1.5", safeMaximum))
        assertNull(tvDownloadCustomQuotaBytes("three", safeMaximum))
        assertNull(tvDownloadCustomQuotaBytes(Long.MAX_VALUE.toString(), safeMaximum))
    }

    @Test
    fun downloadsRouteRailFocusAndOfflineArgumentUseStableOpaqueIdentity() {
        val downloadId = DownloadId("download_01")
        val session = Session("server", "server", "Server", "user", "User", "token", "device")

        assertTrue(isTvRailRoute(TvRoute.Downloads.name))
        assertEquals(TvRailDestination.Downloads, selectedTvRailDestination(TvRoute.Downloads.name))
        assertEquals(TvRailTarget.Downloads, TvRailDestination.Downloads.toRailTarget())
        assertFalse(isTvDownloadsVisible(session))
        assertTrue(isTvDownloadsVisible(session.copy(enableContentDownloading = true)))
        assertFalse(isTvRailRoute(TvRoute.Downloads.name, enableContentDownloading = false))
        assertNull(selectedTvRailDestination(TvRoute.Downloads.name, enableContentDownloading = false))
        assertEquals("download:download_01", tvDownloadFocusKey(downloadId))
        assertEquals(TV_DOWNLOADS_MANAGE_FOCUS_KEY, "downloads:manage")
        assertEquals(TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY, "downloads:resume-all")

        // Manage is slot 0. Each non-empty status section inserts one header
        // before its stable download-ID rows.
        val sectionSizes = listOf(2, 1, 2)
        assertEquals(0, tvDownloadLazySlotIndex(semanticIndex = 0, sectionSizes = sectionSizes))
        assertEquals(2, tvDownloadLazySlotIndex(semanticIndex = 1, sectionSizes = sectionSizes))
        assertEquals(3, tvDownloadLazySlotIndex(semanticIndex = 2, sectionSizes = sectionSizes))
        assertEquals(5, tvDownloadLazySlotIndex(semanticIndex = 3, sectionSizes = sectionSizes))
        assertEquals(7, tvDownloadLazySlotIndex(semanticIndex = 4, sectionSizes = sectionSizes))
        assertEquals(8, tvDownloadLazySlotIndex(semanticIndex = 5, sectionSizes = sectionSizes))

        assertEquals(
            listOf(TV_DOWNLOADS_MANAGE_FOCUS_KEY, TV_DOWNLOADS_RESUME_ALL_FOCUS_KEY),
            tvDownloadSemanticFocusKeys(emptyList(), includeResumeAll = true),
        )
        assertEquals(1, tvDownloadLazySlotIndex(semanticIndex = 1, sectionSizes = sectionSizes, includeResumeAll = true))
        assertEquals(3, tvDownloadLazySlotIndex(semanticIndex = 2, sectionSizes = sectionSizes, includeResumeAll = true))

        assertEquals(downloadId, downloadId.value.toTvOfflineDownloadIdOrNull())
        assertNull("../download".toTvOfflineDownloadIdOrNull())
        assertNull("".toTvOfflineDownloadIdOrNull())
    }
}
