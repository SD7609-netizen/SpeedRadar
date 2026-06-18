package com.hudspeed.android

import android.content.*
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.hudspeed.android.databinding.ActivitySpeedometerBinding
import com.hudspeed.android.service.LocationService

class SpeedometerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpeedometerBinding

    private var totalDistanceMeters = 0f
    private var avgSpeedSum = 0f
    private var avgSpeedCount = 0
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var maxSpeed = 0

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationService.ACTION_LOCATION_UPDATE) return

            val speed = intent.getIntExtra(LocationService.EXTRA_SPEED, 0)
            val lat = intent.getDoubleExtra(LocationService.EXTRA_LAT, 0.0)
            val lon = intent.getDoubleExtra(LocationService.EXTRA_LON, 0.0)

            // Одометр
            if (lastLat != 0.0) {
                val d = com.hudspeed.android.utils.GeoUtils
                    .distanceBetween(lastLat, lastLon, lat, lon)
                if (d < 200f) totalDistanceMeters += d // фильтр прыжков GPS
            }
            lastLat = lat
            lastLon = lon

            // Средняя скорость
            if (speed > 5) {
                avgSpeedSum += speed
                avgSpeedCount++
            }
            if (speed > maxSpeed) maxSpeed = speed

            updateUI(speed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpeedometerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        registerReceiver(
            locationReceiver,
            IntentFilter(LocationService.ACTION_LOCATION_UPDATE)
        )

        binding.btnBack.setOnClickListener { finish() }

        binding.btnReset.setOnClickListener {
            totalDistanceMeters = 0f
            avgSpeedSum = 0f
            avgSpeedCount = 0
            maxSpeed = 0
        }
    }

    private fun updateUI(speedKmh: Int) {
        binding.tvSpeed.text = speedKmh.toString()
        binding.tvMaxSpeed.text = "Макс: $maxSpeed"

        val avgSpeed = if (avgSpeedCount > 0) (avgSpeedSum / avgSpeedCount).toInt() else 0
        binding.tvAvgSpeed.text = "Сред: $avgSpeed"

        val km = totalDistanceMeters / 1000f
        binding.tvDistance.text = String.format("Путь: %.1f км", km)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(locationReceiver) } catch (e: Exception) {}
    }
}
