// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal const val LAUNCHER_REFRESH_INTERVAL_MINUTES = 30L
private const val UNIQUE_REFRESH_WORK = "LauncherPeriodicRefresh"

internal object LauncherRefreshScheduler {
    fun schedule(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<LauncherRefreshWorker>(LAUNCHER_REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10L, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_REFRESH_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_REFRESH_WORK)
    }
}
