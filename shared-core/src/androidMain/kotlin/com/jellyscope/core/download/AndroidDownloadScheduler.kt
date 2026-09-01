// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.download

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jellyscope.core.domain.model.DownloadPlatformWorkIdentity
import com.jellyscope.core.domain.model.DownloadPlatformWorkKind
import com.jellyscope.core.domain.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android's one supported-platform execution host.
 *
 * API 34 and newer use one User-Initiated Data Transfer JobService.  API 33 and
 * lower use one unique foreground WorkManager request.  The SDK decision is
 * made before touching either scheduler and is intentionally exclusive: a
 * rejected UIDT schedule is reported to the caller and never routed through
 * WorkManager on the same SDK.
 */
internal class AndroidDownloadScheduler(
    context: Context,
    private val driver: DownloadExecutionDriver,
    private val queueCoordinator: DownloadQueueCoordinator,
    private val recovery: DownloadExecutionRecovery,
    private val scope: CoroutineScope,
) : DownloadExecutionHost,
    DownloadLifecycleHost {
    private val applicationContext = context.applicationContext
    private val started = AtomicBoolean(false)

    /** Starts the recovery path once for this process. */
    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            recovery.recoverBeforeFirstWake(this@AndroidDownloadScheduler)
        }
    }

    /** Android process teardown is handled by the OS; no background daemon is installed. */
    override fun stop() = Unit

    override suspend fun wakeFromUserAction(): Result<Unit> =
        recovery.recoverBeforeFirstWake(this).fold(
            onSuccess = { wake() },
            onFailure = { failure -> Result.failure(failure) },
        )

    override suspend fun wake(): Result<Unit> =
        if (!driver.hasRunnableWork()) {
            Result.success(Unit)
        } else if (androidDownloadExecutionPath(Build.VERSION.SDK_INT) == AndroidDownloadExecutionPath.Uidt) {
            scheduleUidt()
        } else {
            scheduleWorkManager()
        }

    override suspend fun checkpointAndSuspend(attempt: DownloadAttemptIdentity?): Result<Unit> = driver.checkpointAndSuspend(attempt)

    override suspend fun queryActiveWork(): Result<List<DownloadExecutionWork>> =
        try {
            if (androidDownloadExecutionPath(Build.VERSION.SDK_INT) == AndroidDownloadExecutionPath.Uidt) {
                queryUidtWork()
            } else {
                queryWorkManagerWork()
            }
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override suspend fun reassociate(work: DownloadExecutionWork): Result<Unit> =
        try {
            check(work.attempt != null) { "A native work identity without a durable attempt cannot be reassociated." }
            val identity = work.platformWorkIdentity
            val present =
                if (androidDownloadExecutionPath(Build.VERSION.SDK_INT) == AndroidDownloadExecutionPath.Uidt) {
                    identity.kind == DownloadPlatformWorkKind.AndroidUserInitiatedJob &&
                        identity.value == UIDT_WORK_ID.value &&
                        jobScheduler().getPendingJob(UIDT_JOB_ID)?.let { job ->
                            job.service == ComponentName(applicationContext, AndroidDownloadJobService::class.java) &&
                                job.isUserInitiated
                        } == true
                } else {
                    identity.kind == DownloadPlatformWorkKind.AndroidWorkManager &&
                        queryWorkInfos().any { info ->
                            info.id.toString() == identity.value &&
                                info.state == WorkInfo.State.RUNNING
                        }
                }
            if (!present) {
                Result.failure(IllegalStateException("Persisted download work is no longer active."))
            } else {
                driver.reassociate(work)
            }
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override suspend fun cancel(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> =
        try {
            if (androidDownloadExecutionPath(Build.VERSION.SDK_INT) == AndroidDownloadExecutionPath.Uidt) {
                check(platformWorkIdentity.kind == DownloadPlatformWorkKind.AndroidUserInitiatedJob) {
                    "WorkManager identity is not valid on an API-34+ UIDT host."
                }
                check(platformWorkIdentity.value == UIDT_WORK_ID.value) {
                    "The UIDT identity does not match the app-owned job."
                }
                jobScheduler().cancel(UIDT_JOB_ID)
            } else {
                check(platformWorkIdentity.kind == DownloadPlatformWorkKind.AndroidWorkManager) {
                    "UIDT identity is not valid on an API-33-and-lower host."
                }
                val workId = UUID.fromString(platformWorkIdentity.value)
                workManager().cancelWorkById(workId)
            }
            Result.success(Unit)
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    internal suspend fun cancelRequested(platformWorkIdentity: DownloadPlatformWorkIdentity): Result<Unit> =
        driver.cancelRequested(platformWorkIdentity)

    /** Called by [AndroidDownloadJobService] only after the OS accepted the UIDT job. */
    internal suspend fun executeUidt(): Result<Unit> = execute(UIDT_WORK_ID)

    /** Called by [AndroidDownloadWorker] only on API 33 and lower. */
    internal suspend fun executeWorkManager(workId: UUID): Result<Unit> =
        execute(
            androidDownloadWorkIdentity(
                path = AndroidDownloadExecutionPath.WorkManager,
                value = workId.toString(),
            ),
        )

    internal suspend fun checkpointForWork(identity: DownloadPlatformWorkIdentity): Result<Unit> {
        val attempt =
            queueCoordinator
                .allDownloads()
                .firstOrNull { record ->
                    record.platformWorkIdentity == identity &&
                        record.state == DownloadState.Downloading
                }?.let { record ->
                    DownloadAttemptIdentity(record.downloadId, record.attemptGeneration)
                }
        return checkpointAndSuspend(attempt)
    }

    internal fun foregroundInfo(workId: UUID): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(
                identity =
                    DownloadPlatformWorkIdentity(
                        kind = DownloadPlatformWorkKind.AndroidWorkManager,
                        value = workId.toString(),
                    ),
                progress = 0,
            ),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    internal fun uidtNotification(): Notification = buildNotification(identity = UIDT_WORK_ID, progress = 0)

    internal suspend fun notification(identity: DownloadPlatformWorkIdentity): Notification =
        buildNotification(identity = identity, progress = progress(identity))

    private suspend fun progress(identity: DownloadPlatformWorkIdentity): Int {
        val record =
            queueCoordinator.allDownloads().firstOrNull { item -> item.platformWorkIdentity == identity }
                ?: return 0
        if (record.reservationBytes <= 0L) return 0
        return ((record.physicalBytes.toDouble() / record.reservationBytes.toDouble()) * 1000.0)
            .toInt()
            .coerceIn(0, 1000)
    }

    private suspend fun execute(identity: DownloadPlatformWorkIdentity): Result<Unit> {
        val expectedKind =
            if (androidDownloadExecutionPath(Build.VERSION.SDK_INT) == AndroidDownloadExecutionPath.Uidt) {
                DownloadPlatformWorkKind.AndroidUserInitiatedJob
            } else {
                DownloadPlatformWorkKind.AndroidWorkManager
            }
        if (identity.kind != expectedKind) {
            return Result.failure(IllegalStateException("Platform work identity does not match the SDK path."))
        }
        var result = driver.execute(identity)
        // Keep one accepted native execution host while the common queue has
        // another active-account FIFO head.  This is deliberately not a
        // recursive wake: API-34 UIDT cannot be rescheduled from a background
        // callback, and API-33 WorkManager does not need a second request.
        while (result.isSuccess && driver.hasRunnableWork()) {
            result = driver.execute(identity)
        }
        return result
    }

    private suspend fun scheduleUidt(): Result<Unit> =
        try {
            val scheduler = jobScheduler()
            check(isForegroundProcess()) { "UIDT scheduling requires a foreground user action." }
            check(scheduler.canRunUserInitiatedJobs()) { "UIDT scheduling is unavailable on this device." }
            val pending = scheduler.getPendingJob(UIDT_JOB_ID)
            if (pending != null) {
                check(pending.service == ComponentName(applicationContext, AndroidDownloadJobService::class.java)) {
                    "A different service occupies the download job identity."
                }
                check(pending.isUserInitiated) { "A non-UIDT job occupies the download job identity." }
                return Result.success(Unit)
            }
            val job =
                JobInfo
                    .Builder(UIDT_JOB_ID, ComponentName(applicationContext, AndroidDownloadJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresStorageNotLow(true)
                    .setEstimatedNetworkBytes(estimatedPayloadBytes(), 0L)
                    .setExtras(
                        PersistableBundle().apply {
                            putString(EXTRA_PLATFORM_WORK_IDENTITY, UIDT_WORK_ID.value)
                        },
                    ).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
                "UIDT scheduling was rejected."
            }
            Result.success(Unit)
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    private suspend fun scheduleWorkManager(): Result<Unit> =
        try {
            val manager = workManager()
            val existing = queryWorkInfos()
            val hasLiveWork =
                existing.any { info ->
                    info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING
                }
            if (hasLiveWork) return Result.success(Unit)
            val request = workRequest(UUID.randomUUID())
            manager.enqueueUniqueWork(
                WORK_MANAGER_UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Result.success(Unit)
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    private fun workRequest(workId: UUID): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AndroidDownloadWorker>()
            .setId(workId)
            .setInputData(workDataOf(EXTRA_PLATFORM_WORK_IDENTITY to workId.toString()))
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            ).addTag(WORK_MANAGER_TAG)
            .build()

    private suspend fun queryUidtWork(): Result<List<DownloadExecutionWork>> {
        val pending =
            jobScheduler()
                .getAllPendingJobs()
                .filter { job ->
                    job.service == ComponentName(applicationContext, AndroidDownloadJobService::class.java) &&
                        job.isUserInitiated
                }
        val records = queueCoordinator.allDownloads()
        val work =
            pending
                .map { _ ->
                    records
                        .firstOrNull { record ->
                            record.platformWorkIdentity == UIDT_WORK_ID &&
                                (record.state == DownloadState.Downloading || record.state == DownloadState.Finalizing)
                        }?.let { record -> DownloadAttemptIdentity(record.downloadId, record.attemptGeneration) }
                        .let { attempt -> DownloadExecutionWork(attempt = attempt, platformWorkIdentity = UIDT_WORK_ID) }
                }.distinctBy { work -> work.platformWorkIdentity }
        return Result.success(work)
    }

    private suspend fun queryWorkManagerWork(): Result<List<DownloadExecutionWork>> {
        val records = queueCoordinator.allDownloads()
        val work =
            queryWorkInfos()
                .filter { info -> info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING }
                .map { info ->
                    val identity =
                        DownloadPlatformWorkIdentity(
                            kind = DownloadPlatformWorkKind.AndroidWorkManager,
                            value = info.id.toString(),
                        )
                    records
                        .firstOrNull { record ->
                            record.platformWorkIdentity == identity &&
                                (record.state == DownloadState.Downloading || record.state == DownloadState.Finalizing)
                        }?.let { record -> DownloadAttemptIdentity(record.downloadId, record.attemptGeneration) }
                        .let { attempt -> DownloadExecutionWork(attempt = attempt, platformWorkIdentity = identity) }
                }
        return Result.success(work.distinctBy { workItem -> workItem.platformWorkIdentity })
    }

    private suspend fun queryWorkInfos(): List<WorkInfo> =
        withContext(Dispatchers.IO) {
            try {
                workManager().getWorkInfosForUniqueWork(WORK_MANAGER_UNIQUE_NAME).get()
            } catch (failure: ExecutionException) {
                throw (failure.cause ?: failure)
            }
        }

    private suspend fun estimatedPayloadBytes(): Long =
        queueCoordinator
            .allDownloads()
            .filter { record ->
                record.state == DownloadState.Queued ||
                    record.state == DownloadState.Downloading ||
                    record.state == DownloadState.Paused
            }.minByOrNull { record -> record.fifoSequence }
            ?.request
            ?.admissionEstimateBytes
            ?.coerceAtLeast(1L)
            ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong()

    private fun isForegroundProcess(): Boolean {
        val activityManager =
            applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
        val processName = applicationContext.packageName
        return activityManager.runningAppProcesses.orEmpty().any { process ->
            process.processName == processName &&
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun jobScheduler(): JobScheduler = checkNotNull(applicationContext.getSystemService(JobScheduler::class.java))

    private fun workManager(): WorkManager = WorkManager.getInstance(applicationContext)

    private fun buildNotification(
        identity: DownloadPlatformWorkIdentity,
        progress: Int,
    ): Notification {
        ensureNotificationChannel()
        val cancelIntent =
            cancelPayload(identity).let { payload ->
                Intent(applicationContext, AndroidDownloadCancelReceiver::class.java)
                    .setAction(payload.action)
                    .putExtra(EXTRA_PLATFORM_WORK_KIND, payload.platformWorkKind)
                    .putExtra(EXTRA_PLATFORM_WORK_IDENTITY, payload.platformWorkIdentity)
            }
        val cancelPendingIntent =
            PendingIntent.getBroadcast(
                applicationContext,
                NOTIFICATION_ID,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            } else {
                Notification.Builder(applicationContext)
            }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.applicationInfo.loadLabel(applicationContext.packageManager))
            .setContentText(applicationContext.applicationInfo.loadLabel(applicationContext.packageManager))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, progress.coerceIn(0, 1000), false)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        applicationContext.getString(android.R.string.cancel),
                        cancelPendingIntent,
                    ).build(),
            ).build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.applicationInfo.loadLabel(applicationContext.packageManager),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        internal data class CancelPayload(
            val action: String,
            val platformWorkKind: String,
            val platformWorkIdentity: String,
        )

        internal fun cancelPayload(identity: DownloadPlatformWorkIdentity): CancelPayload =
            CancelPayload(
                action = ACTION_CANCEL,
                platformWorkKind = identity.kind.name,
                platformWorkIdentity = identity.value,
            )

        /**
         * Parses only the explicit app-owned notification action payload. The
         * receiver shares this mapping with the notification builder so cancel
         * cannot target a different identity than the visible work item.
         */
        internal fun identityFrom(intent: Intent): DownloadPlatformWorkIdentity? =
            identityFromPayload(
                action = intent.action,
                platformWorkKind = intent.getStringExtra(EXTRA_PLATFORM_WORK_KIND),
                platformWorkIdentity = intent.getStringExtra(EXTRA_PLATFORM_WORK_IDENTITY),
            )

        internal fun identityFromPayload(
            action: String?,
            platformWorkKind: String?,
            platformWorkIdentity: String?,
        ): DownloadPlatformWorkIdentity? {
            if (action != ACTION_CANCEL) return null
            val kind =
                platformWorkKind
                    ?.let { value -> runCatching { DownloadPlatformWorkKind.valueOf(value) }.getOrNull() }
                    ?: return null
            val value = platformWorkIdentity ?: return null
            return runCatching { DownloadPlatformWorkIdentity(kind, value) }.getOrNull()
        }

        internal const val ACTION_CANCEL = "com.jellyscope.core.download.CANCEL"
        internal const val EXTRA_PLATFORM_WORK_KIND = "platform_work_kind"
        internal const val EXTRA_PLATFORM_WORK_IDENTITY = "platform_work_identity"
        internal const val NOTIFICATION_CHANNEL_ID = "jellyscope_downloads"
        internal const val NOTIFICATION_ID = 0x4A53434
        internal const val UIDT_JOB_ID = 0x4A53434
        internal const val WORK_MANAGER_UNIQUE_NAME = "jellyscope-download-transfer"
        internal const val WORK_MANAGER_TAG = "jellyscope-download-transfer"
        internal val UIDT_WORK_ID =
            androidDownloadWorkIdentity(
                path = AndroidDownloadExecutionPath.Uidt,
                value = UIDT_JOB_ID.toString(),
            )
    }
}
