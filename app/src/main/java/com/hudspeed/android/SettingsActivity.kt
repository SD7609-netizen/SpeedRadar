package com.hudspeed.android

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.hudspeed.android.data.CameraDatabase
import com.hudspeed.android.data.CameraRepository
import com.hudspeed.android.utils.VoiceAlertManager
import com.hudspeed.android.worker.UpdateWorker
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

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
    private var voiceAlert: VoiceAlertManager? = null

    private var cameraCountPref: Preference? = null
    private var updatePref: Preference? = null
    private var autoUpdateStatusPref: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        cameraCountPref = findPreference("camera_count")
        updatePref = findPreference("update_cameras")
        autoUpdateStatusPref = findPreference("auto_update_status")

        refreshCount()
        refreshAutoUpdateStatus()

        updatePref?.setOnPreferenceClickListener {
            startUpdate()
            true
        }

        findPreference<Preference>("download_offline")?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), DownloadActivity::class.java))
            true
        }

        // Автообновление — переключатель
        findPreference<SwitchPreferenceCompat>("auto_update_enabled")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    val days = PreferenceManager
                        .getDefaultSharedPreferences(requireContext())
                        .getString("auto_update_interval", "7")?.toLongOrNull() ?: 7L
                    val countries = PreferenceManager
                        .getDefaultSharedPreferences(requireContext())
                        .getStringSet("downloaded_countries", emptySet()) ?: emptySet()
                    if (countries.isEmpty()) {
                        Toast.makeText(requireContext(),
                            "Сначала скачайте базу (Россия / Беларусь)", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    UpdateWorker.schedule(requireContext(), days)
                    Toast.makeText(requireContext(),
                        "Автообновление включено", Toast.LENGTH_SHORT).show()
                } else {
                    UpdateWorker.cancel(requireContext())
                    Toast.makeText(requireContext(),
                        "Автообновление отключено", Toast.LENGTH_SHORT).show()
                }
                true
            }

        // Автообновление — смена частоты
        findPreference<ListPreference>("auto_update_interval")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = PreferenceManager
                    .getDefaultSharedPreferences(requireContext())
                    .getBoolean("auto_update_enabled", false)
                if (enabled) {
                    val days = (newValue as String).toLongOrNull() ?: 7L
                    UpdateWorker.schedule(requireContext(), days)
                }
                true
            }

        // Кнопка проверки голоса
        findPreference<Preference>("voice_test")?.setOnPreferenceClickListener {
            if (voiceAlert == null) {
                voiceAlert = VoiceAlertManager(requireContext())
            }
            // Небольшая задержка чтобы TTS успел инициализироваться
            lifecycleScope.launch {
                kotlinx.coroutines.delay(600)
                voiceAlert?.speak("Камера через пятьсот метров. Камера!")
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCount()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceAlert?.destroy()
    }

    private fun refreshAutoUpdateStatus() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val ts = prefs.getLong("last_auto_update_ts", 0L)
        autoUpdateStatusPref?.summary = if (ts == 0L) "Не выполнялось"
        else {
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            fmt.format(Date(ts))
        }
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
        // Читаем как String — без потери точности координат
        val lat = prefs.getString("last_lat_str", "0")?.toDoubleOrNull() ?: 0.0
        val lon = prefs.getString("last_lon_str", "0")?.toDoubleOrNull() ?: 0.0

        if (lat == 0.0 || lon == 0.0) {
            Toast.makeText(requireContext(),
                "Сначала откройте Радар — нужны GPS-координаты", Toast.LENGTH_LONG).show()
            return
        }

        updatePref?.isEnabled = false
        updatePref?.summary = "Обновление (радиус 10 км)..."
        cameraCountPref?.summary = "Загрузка..."

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                repository.fetchCamerasNear(lat, lon, 10000)
            }
            val total = withContext(Dispatchers.IO) { repository.count() }

            cameraCountPref?.summary = "$total камер в локальной базе"
            updatePref?.summary = "Загрузить актуальные данные из OpenStreetMap"
            updatePref?.isEnabled = true

            val msg = when {
                found == -1 -> "Ошибка сети — проверьте интернет"
                found == 0  -> "Камер в радиусе 10 км не найдено в OSM.\nПопробуйте скачать всю Россию офлайн."
                else        -> "Найдено: $found камер. Всего в базе: $total"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }
}
