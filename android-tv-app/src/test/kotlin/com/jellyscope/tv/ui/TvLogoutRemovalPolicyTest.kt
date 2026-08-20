// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadRemovalConfirmation
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvLogoutRemovalPolicyTest {
    @Test
    fun previewSummaryUsesTheExactRetainedCountAndBytesAcrossAccounts() {
        val preview =
            DownloadRemovalPreview(
                membershipRevision = 7L,
                confirmations =
                    listOf(
                        confirmation(serverId = "server-a", userId = "user-a", count = 2L, bytes = 1_024L),
                        confirmation(serverId = "server-b", userId = "user-b", count = 3L, bytes = 2_048L),
                    ),
            )

        assertEquals(
            TvLogoutRemovalSummary(recordCount = 5L, displayedBytes = 3_072L),
            tvLogoutRemovalSummary(preview),
        )
    }

    @Test
    fun confirmDispatchesOnlyTheRetainedDownloadConfirmationCallback() {
        var confirmationCalls = 0
        var dismissalCalls = 0

        val dismissalRequested =
            dispatchTvLogoutRemovalDialogAction(
                action = TvLogoutRemovalDialogAction.Confirm,
                dismissalRequested = false,
                onConfirm = { confirmationCalls++ },
                onDismiss = { dismissalCalls++ },
            )

        assertFalse(dismissalRequested)
        assertEquals(1, confirmationCalls)
        assertEquals(0, dismissalCalls)
    }

    @Test
    fun dismissalInvokesTheReleasePathOnceThenRestoresTheLogoutTileAfterPreviewClears() {
        var releaseCalls = 0
        var queueEligible = false
        var focusRestoreCalls = 0
        val dismissLogoutRemoval = {
            releaseCalls++
            queueEligible = true
        }

        var dismissalRequested =
            dispatchTvLogoutRemovalDialogAction(
                action = TvLogoutRemovalDialogAction.Dismiss,
                dismissalRequested = false,
                onConfirm = {},
                onDismiss = dismissLogoutRemoval,
            )

        assertTrue(dismissalRequested)
        assertTrue(queueEligible)
        assertEquals(1, releaseCalls)
        assertFalse(
            completeTvLogoutRemovalDismissalIfReady(
                dismissalRequested = dismissalRequested,
                previewPresent = true,
                onComplete = { focusRestoreCalls++ },
            ),
        )
        assertEquals(0, focusRestoreCalls)

        dismissalRequested =
            dispatchTvLogoutRemovalDialogAction(
                action = TvLogoutRemovalDialogAction.Dismiss,
                dismissalRequested = dismissalRequested,
                onConfirm = {},
                onDismiss = dismissLogoutRemoval,
            )
        assertEquals(1, releaseCalls)
        assertTrue(
            completeTvLogoutRemovalDismissalIfReady(
                dismissalRequested = dismissalRequested,
                previewPresent = false,
                onComplete = { focusRestoreCalls++ },
            ),
        )
        assertEquals(1, focusRestoreCalls)
        assertEquals(TvSettingsTileId.Logout, tvSettingsDialogRestoreTarget(TvSettingsTileId.Logout))
    }

    private fun confirmation(
        serverId: String,
        userId: String,
        count: Long,
        bytes: Long,
    ): DownloadRemovalConfirmation =
        DownloadRemovalConfirmation(
            accountIdentity = AccountIdentity(serverId, userId),
            membershipRevision = 7L,
            recordCount = count,
            displayedBytes = bytes,
        )
}
