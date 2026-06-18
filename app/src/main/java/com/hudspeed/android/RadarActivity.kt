package com.hudspeed.android

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.hudspeed.android.data.Camera
import com.hudspeed.android.data.CameraDatabase
import com.hudspeed.android.data.CameraRepository
import com.hudspeed.android.databinding.ActivityRadarBinding
import com.hudspeed.android.service.LocationService
import com.hudspeed.android.utils.GeoUtils
import com.hudspeed.android.utils.VoiceAlertManager
import kotlinx.coroutines.*

class RadarActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadarBinding
    private lateinit var voiceAlert: VoiceAlertManager
    private lateinit var repository: CameraRepository

    private var serviceConnection: ServiceConnection? = null
    private var locationService: LocationService? = null
    private var isBound = false

    private var cameras: List<Camera> = emptyList()
    private var currentSpeedKmh = 0
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentBearing = 0f

    private var speedLimit = 60
    private var alertDistanceMeters = 1000f
    private var isSpeedExceeded = false

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationService.ACTION_LOCATION_UPDATE) return

            currentSpeedKmh = intent.getIntExtra(LocationService.EXTRA_SPEED, 0)
            currentLat = intent.getDoubleExtra(LocationService.EXTRA_LAT, 0.0)
            currentLon = intent.getDoubleExtra(LocationService.EXTRA_LON, 0.0)
            currentBearing = intent.getFloatExtra(LocationService.EXTRA_BEARING, 0f)

            updateUI()
            checkAlerts()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRadarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Экран не гаснет
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Полноэкранный режим
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        loadSettings()
        voiceAlert = VoiceAlertManager(this)
        repository = CameraRepository(CameraDatabase.getInstance(this).cameraDao())

        setupButtons()
        checkPermissionsAndStart()
    }

    private fun loadSettings() {
        speedLimit = prefs.getString("speed_limit", "60")?.toIntOrNull() ?: 60
        alertDistanceMeters = prefs.getString("alert_distance", "1000")?.toFloatOrNull() ?: 1000f
        binding.radarView.radarRangeMeters = prefs.getString("radar_range", "1500")
            ?.toFloatOrNull() ?: 1500f
    }

    private fun setupButtons() {
        binding.btnCameraExists.setOnClickListener {
            reportCamera(true)
        }
        binding.btnNoCameras.setOnClickListener {
            reportCamera(false)
        }
        binding.btnEdit.setOnClickListener {
            Toast.makeText(this, "Функция в разработке", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reportCamera(exists: Boolean) {
        Toast.makeText(
            this,
            if (exists) "Спасибо! Камера отмечена" else "Камера не подтверждена",
            Toast.LENGTH_SHORT
        ).show()
        // TODO: отправить репорт на сервер
    }

    private fun checkPermissionsAndStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        } else {
            startLocationService()
        }
    }

    private fun startLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                locationService = (binder as LocationService.LocalBinder).getService()
                isBound = true
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                isBound = false
            }
        }
        bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)

        registerReceiver(
            locationReceiver,
            IntentFilter(LocationService.ACTION_LOCATION_UPDATE)
        )
    }

    private fun updateUI() {
        // Скорость
        binding.tvSpeed.text = currentSpeedKmh.toString()

        // Цвет фона при превышении
        val exceeded = currentSpeedKmh > speedLimit
        if (exceeded != isSpeedExceeded) {
            isSpeedExceeded = exceeded
            binding.root.setBackgroundColor(
                if (exceeded) android.graphics.Color.parseColor("#CC0000")
                else android.graphics.Color.BLACK
            )
            if (exceeded) voiceAlert.onSpeedExceeded(currentSpeedKmh, speedLimit)
        }

        // Радар
        binding.radarView.carLat = currentLat
        binding.radarView.carLon = currentLon
        binding.radarView.carBearing = currentBearing
        binding.radarView.cameras = cameras

        // Ближайшая камера
        val nearest = GeoUtils.nearestCamera(cameras, currentLat, currentLon)
        if (nearest != null && nearest.second <= alertDistanceMeters) {
            binding.tvCameraDistance.text = "До камеры\n${GeoUtils.formatDistance(nearest.second)}"
            binding.ivCameraIcon.visibility = View.VISIBLE
            voiceAlert.onCameraDistance(nearest.second)
        } else {
            binding.tvCameraDistance.text = "Камер нет"
            binding.ivCameraIcon.visibility = View.INVISIBLE
            voiceAlert.onCameraCleared()
        }

        // Обновляем оверлей если он активен
        com.hudspeed.android.service.OverlayService.update(
            this, currentSpeedKmh,
            nearest?.second?.toInt() ?: -1,
            currentLat, currentLon, currentBearing
        )

        // Периодически обновляем базу камер
        if (currentLat != 0.0 && currentLon != 0.0) {
            loadCamerasIfNeeded()
        }
    }

    private var lastCameraFetch = 0L
    private var lastFetchLat = 0.0
    private var lastFetchLon = 0.0

    private fun loadCamerasIfNeeded() {
        val now = System.currentTimeMillis()
        val distMoved = GeoUtils.distanceBetween(lastFetchLat, lastFetchLon, currentLat, currentLon)
        // Обновляем если прошло 2 мин или проехали 1км
        if (now - lastCameraFetch < 120_000L && distMoved < 1000f) return

        lastCameraFetch = now
        lastFetchLat = currentLat
        lastFetchLon = currentLon

        lifecycleScope.launch {
            // Фетч из сети
            launch(Dispatchers.IO) {
                repository.fetchCamerasNear(currentLat, currentLon)
            }
            // Читаем из локальной БД
            val bounds = GeoUtils.boundingBox(currentLat, currentLon, 5000.0)
            cameras = repository.getCamerasInBounds(bounds[0], bounds[1], bounds[2], bounds[3])
            binding.radarView.cameras = cameras
        }
    }

    private fun checkAlerts() {
        // Дополнительная проверка — голос по расстоянию
        val nearest = GeoUtils.nearestCamera(cameras, currentLat, currentLon)
        if (nearest != null) {
            voiceAlert.onCameraDistance(nearest.second)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startLocationService()
        } else {
            Toast.makeText(this, "Нужно разрешение на геолокацию", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // Запускаем мини-радар поверх навигатора при сворачивании
        if (android.provider.Settings.canDrawOverlays(this)) {
            com.hudspeed.android.service.OverlayService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Убираем оверлей когда возвращаемся в приложение
        com.hudspeed.android.service.OverlayService.stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceAlert.destroy()
        try { unregisterReceiver(locationReceiver) } catch (e: Exception) {}
        if (isBound) {
            serviceConnection?.let { unbindService(it) }
            isBound = false
        }
    }
}
