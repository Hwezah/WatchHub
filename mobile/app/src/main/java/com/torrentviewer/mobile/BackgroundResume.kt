package com.torrentviewer.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Downloads that carry on with the app closed, as 1DM's do. Android's job scheduler (WorkManager) keeps its
 * jobs through reboots and runs them only once there is a network (Wi-Fi, when downloads keep to Wi-Fi), so:
 * a download waiting for its next try is woken at that time even if Android closed the app meanwhile; one that
 * failed offline is woken the moment the network is back; and an hourly check picks up anything left unfinished
 * — after a reboot, or after Android's six-hour daily limit on background downloading stopped the service.
 * Paused downloads are left alone: paused means paused.
 */
object BackgroundResume {
    private const val SOON = "downloads-resume"
    private const val WATCH = "downloads-watch"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (Downloads.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Wakes the downloads in [minutes] (0: as soon as there is a network), replacing any earlier wake-up. */
    fun soon(context: Context, minutes: Long) = runCatching {
        val work = OneTimeWorkRequestBuilder<ResumeWorker>().setInitialDelay(minutes, TimeUnit.MINUTES).setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, work)
    }

    /** The hourly check, while anything is left to download; set once, kept through reboots. */
    fun watch(context: Context) = runCatching {
        val work = PeriodicWorkRequestBuilder<ResumeWorker>(1, TimeUnit.HOURS).setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WATCH, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    /** Nothing left to download (or all paused): no more wake-ups. */
    fun stop(context: Context) = runCatching {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(SOON)
            cancelUniqueWork(WATCH)
        }
    }
}

/**
 * One wake-up: the app's services started (as when it opens), the waiting downloads asked to carry on, and the
 * process held for them for up to [HOLD_MS] — Android lets a job run about ten minutes. Most of the time the
 * download service takes over and keeps going past that; where Android refuses it that (the six-hour limit
 * reached), the download goes on in bursts like this one, what's saved kept between them.
 */
class ResumeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.Main) { Services.init(applicationContext) }
        Downloads.wake()
        val end = System.currentTimeMillis() + HOLD_MS
        // A moment for them to start, then held while any is running.
        delay(5_000)
        while (System.currentTimeMillis() < end && Downloads.running) delay(15_000)
        // Still unfinished and nothing holding the process: another burst shortly.
        if (Downloads.unfinished && DownloadService.instance == null) BackgroundResume.soon(applicationContext, 1)
        return Result.success()
    }

    private companion object {
        const val HOLD_MS = 9 * 60 * 1000L
    }
}
