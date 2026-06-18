package com.hudspeed.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.hudspeed.android.DownloadActivity
import com.hudspeed.android.R
import com.hudspeed.android.data.CameraDatabase
import com.hudspeed.android.data.CameraRepository
import kotlinx.coroutines.*

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID      = "download_channel"
        const val NOTIF_ID        = 3
        const val EXTRA_COUNTRY   = "country_code"

        const val ACTION_PROGRESS = "com.hudspeed.DOWNLOAD_PROGRESS"
        const val EXTRA_CHUNKS_DONE  = "chunks_done"
        const val EXTRA_CHUNKS_TOTAL = "chunks_total"
        const val EXTRA_CAMERAS      = "cameras_total"
        const val EXTRA_FINISHED     = "finished"
        const val EXTRA_COUNTRY_NAME = "country_name"

        fun start(context: Context, countryCode: String) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_COUNTRY, countryCode)
            context.startForegroundService(intent)
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            return manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == DownloadService::class.java.name }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: CameraRepository
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        repository = CameraRepository(CameraDatabase.getInstance(this).cameraDao())
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val countryCode = intent?.getStringExtra(EXTRA_COUNTRY) ?: "RU"
        val countryName = CameraRepository.countryNames[countryCode] ?: countryCode

        startForeground(NOTIF_ID, buildNotification("Подготовка загрузки $countryName...", 0, 0))

        scope.launch {
            repository.downloadCountry(countryCode) { done, total, cameras ->
                val text = "$done / $total чанков  •  $cameras камер"
                notificationManager.notify(NOTIF_ID,
                    buildNotification("$countryName: $text", done, total))

                // Уведомляем активность если она открыта
                sendBroadcast(Intent(ACTION_PROGRESS).apply {
                    putExtra(EXTRA_CHUNKS_DONE, done)
                    putExtra(EXTRA_CHUNKS_TOTAL, total)
                    putExtra(EXTRA_CAMERAS, cameras)
                    putExtra(EXTRA_COUNTRY_NAME, countryName)
                    putExtra(EXTRA_FINISHED, false)
                })
            }

            val total = repository.count()

            // Запоминаем страну как скачанную (для автообновления)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@DownloadService)
            val saved = prefs.getStringSet("downloaded_countries", mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()
            saved.add(countryCode)
            prefs.edit().putStringSet("downloaded_countries", saved).apply()

            // Финальное уведомление
            notificationManager.notify(NOTIF_ID,
                buildNotification("$countryName загружена. Камер в базе: $total", 1, 1))

            sendBroadcast(Intent(ACTION_PROGRESS).apply {
                putExtra(EXTRA_CAMERAS, total)
                putExtra(EXTRA_COUNTRY_NAME, countryName)
                putExtra(EXTRA_FINISHED, true)
            })

            stopSelf()
        }

        return START_REDELIVER_INTENT
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, DownloadActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Radar — загрузка базы")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_speed)
            .setContentIntent(pi)
            .setOngoing(max > 0 && progress < max)
            .setProgress(max, progress, max == 0)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Загрузка базы камер",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(ch)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
