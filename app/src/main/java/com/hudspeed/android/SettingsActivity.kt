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

    private var cameraCountPref: Preference? = null
    private var updatePref: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        cameraCountPref = findPreference("camera_count")
        updatePref = findPreference("update_cameras")

        refreshCount()

        updatePref?.setOnPreferenceClickListener {
            startUpdate()
            true
        }

        findPreference<Preference>("download_offline")?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), DownloadActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCount()
    }

    private fun refreshCount() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { repository.count() }
            cameraCountPref?.summary = if (count > 0) "$count камер в локальной базе"
                                       else "База пуста — нажмите «Обновить» при наличии GPS"
        }
    }

    private fun startUpdate() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val lat = prefs.getFloat("last_lat", 0f).toDouble()
        val lon = prefs.getFloat("last_lon", 0f).toDouble()

        if (lat == 0.0 || lon == 0.0) {
            Toast.makeText(requireContext(),
                "Сначала откройте Радар — нужны GPS-координаты", Toast.LENGTH_LONG).show()
            return
        }

        updatePref?.isEnabled = false
        updatePref?.summary = "Обновление, подождите..."
        cameraCountPref?.summary = "Загрузка..."

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.fetchCamerasNear(lat, lon, 10000)
            }
            val count = withContext(Dispatchers.IO) { repository.count() }
            cameraCountPref?.summary = "$count камер в локальной базе"
            updatePref?.summary = "Загрузить актуальные данные из OpenStreetMap"
            updatePref?.isEnabled = true
            Toast.makeText(requireContext(), "Готово! Загружено: $count камер", Toast.LENGTH_SHORT).show()
        }
    }
}
