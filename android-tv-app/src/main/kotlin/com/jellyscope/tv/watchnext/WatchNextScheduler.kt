// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val UNIQUE_PERIODIC_WORK_NAME = "WatchNextPeriodicSync"
private const val UNIQUE_IMMEDIATE_WORK_NAME = "WatchNextImmediateSync"
private const val PERIODIC_INTERVAL_HOURS = 1L
private const val BACKOFF_DELAY_MINUTES = 10L

object WatchNextScheduler {
    fun schedulePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<WatchNextSyncWorker>(
                PERIODIC_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).setBackoffCriteria(
                BackoffPolicy.LINEAR,
                BACKOFF_DELAY_MINUTES,
                TimeUnit.MINUTES,
            ).build()

        WorkManager
            .getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<WatchNextSyncWorker>().build()
        WorkManager
            .getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }
}
