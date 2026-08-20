// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.content.ContentValues
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O])
class WatchNextEngagementTimeTest {
    @Test
    fun repeatedPassiveUpdatePreservesInitialEngagementTime() {
        val initialEngagementTime = 1_000L
        val firstSync = desiredProgram(engagementTime = initialEngagementTime)
        val existingProgram =
            WatchNextProgram
                .Builder()
                .setInternalProviderId(ITEM_ID)
                .setLastEngagementTimeUtcMillis(initialEngagementTime)
                .build()
        val secondSync = desiredProgram(engagementTime = 2_000L)

        val updateValues = secondSync.contentValuesForReconciliationUpdate(existingProgram)

        assertEquals(initialEngagementTime, updateValues.getAsLong(ENGAGEMENT_TIME_COLUMN))
        assertEquals(2_000L, secondSync.contentValues.getAsLong(ENGAGEMENT_TIME_COLUMN))
        assertEquals(initialEngagementTime, firstSync.contentValues.getAsLong(ENGAGEMENT_TIME_COLUMN))
    }

    @Test
    fun passiveUpdateWithoutExistingEngagementDoesNotInventOne() {
        val existingProgram =
            WatchNextProgram
                .Builder()
                .setInternalProviderId(ITEM_ID)
                .build()

        val updateValues =
            desiredProgram(engagementTime = 2_000L)
                .contentValuesForReconciliationUpdate(existingProgram)

        assertFalse(updateValues.containsKey(ENGAGEMENT_TIME_COLUMN))
    }

    private fun desiredProgram(engagementTime: Long): DesiredWatchNextProgram =
        DesiredWatchNextProgram(
            itemId = ITEM_ID,
            contentValues =
                ContentValues().apply {
                    put(ENGAGEMENT_TIME_COLUMN, engagementTime)
                },
        )

    private companion object {
        const val ITEM_ID = "episode-1"
        const val ENGAGEMENT_TIME_COLUMN =
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
    }
}
