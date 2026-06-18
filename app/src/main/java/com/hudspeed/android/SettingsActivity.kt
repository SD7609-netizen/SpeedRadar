package com.hudspeed.android

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.hudspeed.android.data.CameraDatabase
import com.hudspeed.android.data.CameraRepository
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private val repository by lazy {
        CameraRepository(CameraDatabase.getInstance(requireContext()).cameraDao())
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Показываем количество камер в базе
        updateCameraCount()

        // Кнопка обновления базы
        findPreference<Preference>("update_cameras")?.setOnPreferenceClickListener {
            it.summary = "Обновление..."
            it.isEnabled = false
            lifecycleScope.launch {
                // Берём последние известные координаты из SharedPreferences
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val lat = prefs.getFloat("last_lat", 0f).toDouble()
                val lon = prefs.getFloat("last_lon", 0f).toDouble()

                if (lat == 0.0 || lon == 0.0) {
                    Toast.makeText(requireContext(),
                        "Сначала откройте радар с GPS", Toast.LENGTH_LONG).show()
                } else {
                    repository.fetchCamerasNear(lat, lon, 10000)
                    updateCameraCount()
                    Toast.makeText(requireContext(), "База обновлена!", Toast.LENGTH_SHORT).show()
                }
                it.summary = "Загрузить актуальные данные из OpenStreetMap"
                it.isEnabled = true
            }
            true
        }
    }

    private fun updateCameraCount() {
        lifecycleScope.launch {
            val count = repository.count()
            findPreference<Preference>("camera_count")?.summary =
                if (count > 0) "В локальной базе: $count камер" else "База пуста — откройте радар с GPS"
        }
    }
}
