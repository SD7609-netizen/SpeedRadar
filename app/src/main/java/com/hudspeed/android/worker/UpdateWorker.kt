package com.hudspeed.android.worker

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.*
import com.hudspeed.android.service.DownloadService
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val countries = prefs.getStringSet("downloaded_countries", emptySet()) ?: emptySet()
        if (countries.isEmpty()) return Result.success()

        for (country in countries) {
            DownloadService.start(applicationContext, country)
            Thread.sleep(3000) // небольшой разрыв между странами
        }

        prefs.edit()
            .putLong("last_auto_update_ts", System.currentTimeMillis())
            .apply()

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "camera_auto_update"

        fun schedule(context: Context, intervalDays: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateWorker>(intervalDays, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
