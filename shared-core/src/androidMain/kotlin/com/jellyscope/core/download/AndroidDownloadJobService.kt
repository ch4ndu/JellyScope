// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** API-34+ User-Initiated Data Transfer execution host. */
class AndroidDownloadJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var executionJob: Job? = null
    private var progressJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !params.isUserInitiatedJob ||
            params.jobId != AndroidDownloadScheduler.UIDT_JOB_ID
        ) {
            return false
        }
        val scheduler = schedulerOrNull() ?: return false
        val identity = AndroidDownloadScheduler.UIDT_WORK_ID
        setNotification(
            params,
            AndroidDownloadScheduler.NOTIFICATION_ID,
            scheduler.uidtNotification(),
            JobService.JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        progressJob?.cancel()
        progressJob =
            serviceScope.launch {
                while (isActive) {
                    setNotification(
                        params,
                        AndroidDownloadScheduler.NOTIFICATION_ID,
                        scheduler.notification(identity),
                        JobService.JOB_END_NOTIFICATION_POLICY_REMOVE,
                    )
                    delay(PROGRESS_REFRESH_MILLIS)
                }
            }
        executionJob?.cancel()
        executionJob =
            serviceScope.launch {
                scheduler.executeUidt()
                progressJob?.cancel()
                jobFinished(params, false)
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val scheduler = schedulerOrNull() ?: return false
        val jobs = listOfNotNull(executionJob, progressJob)
        executionJob = null
        progressJob = null
        serviceScope.launch {
            // A normal stop callback can checkpoint.  If Task Manager Stop
            // kills the process, this callback may never arrive; startup
            // recovery therefore treats absent UIDT work as Paused and never
            // relies on this callback for correctness.
            jobs.forEach { job -> job.cancel() }
            jobs.joinAll()
            scheduler.checkpointForWork(AndroidDownloadScheduler.UIDT_WORK_ID)
        }
        return false
    }

    override fun onDestroy() {
        executionJob?.cancel()
        progressJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun schedulerOrNull(): AndroidDownloadScheduler? =
        runCatching { GlobalContext.get().get<AndroidDownloadScheduler>() }.getOrNull()

    private companion object {
        const val PROGRESS_REFRESH_MILLIS = 1_000L
    }
}
