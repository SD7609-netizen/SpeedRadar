package com.hudspeed.android.service

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.hudspeed.android.MainActivity
import com.hudspeed.android.R
import com.hudspeed.android.data.Camera
import com.hudspeed.android.ui.MiniRadarView

/**
 * Плавающий мини-радар поверх любого приложения (Яндекс.Навигатор и др.)
 * Запускается при сворачивании RadarActivity или включении фонового режима.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "overlay_channel"
        const val ACTION_UPDATE = "com.hudspeed.OVERLAY_UPDATE"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_CAMERA_DIST = "camera_dist"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_BEARING = "bearing"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(context)) return
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun update(context: Context, speedKmh: Int, cameraDistMeters: Int,
                   lat: Double, lon: Double, bearing: Float,
                   cameras: List<Camera> = emptyList()) {
            context.sendBroadcast(Intent(ACTION_UPDATE).apply {
                putExtra(EXTRA_SPEED, speedKmh)
                putExtra(EXTRA_CAMERA_DIST, cameraDistMeters)
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LON, lon)
                putExtra(EXTRA_BEARING, bearing)
            })
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var tvSpeed: TextView
    private lateinit var tvCamera: TextView
    private lateinit var miniRadar: MiniRadarView

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val speed = intent?.getIntExtra(EXTRA_SPEED, 0) ?: 0
            val dist = intent?.getIntExtra(EXTRA_CAMERA_DIST, -1) ?: -1
            val lat = intent?.getDoubleExtra(EXTRA_LAT, 0.0) ?: 0.0
            val lon = intent?.getDoubleExtra(EXTRA_LON, 0.0) ?: 0.0
            val bearing = intent?.getFloatExtra(EXTRA_BEARING, 0f) ?: 0f

            tvSpeed.text = "$speed"
            tvCamera.text = if (dist >= 0) "${dist}м" else "—"
            tvCamera.setTextColor(if (dist in 0..300) Color.RED else Color.parseColor("#FF8800"))

            miniRadar.carLat = lat
            miniRadar.carLon = lon
            miniRadar.carBearing = bearing
        }
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(2, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlayView()
        registerReceiver(updateReceiver, IntentFilter(ACTION_UPDATE))
    }

    private fun setupOverlayView() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_widget, null)
        tvSpeed = overlayView!!.findViewById(R.id.tv_overlay_speed)
        tvCamera = overlayView!!.findViewById(R.id.tv_overlay_camera)
        miniRadar = overlayView!!.findViewById(R.id.mini_radar)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 8
            y = 200
        }

        // Перетаскивание пальцем
        overlayView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Speed Radar оверлей",
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                getSystemService(NotificationManager::class.java).createNotificationChannel(this)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Radar активен")
            .setContentText("Мини-радар работает поверх навигатора")
            .setSmallIcon(R.drawable.ic_speed)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        runCatching { unregisterReceiver(updateReceiver) }
    }
}
