package com.hudspeed.android

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var ttsVoicePref: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        cameraCountPref = findPreference("camera_count")
        updatePref = findPreference("update_cameras")
        autoUpdateStatusPref = findPreference("auto_update_status")
        ttsVoicePref = findPreference("tts_voice")

        refreshCount()
        refreshAutoUpdateStatus()
        refreshVoiceSummary()

        // ── Голосовые настройки ──────────────────────────────────────────

        findPreference<Preference>("tts_voice")?.setOnPreferenceClickListener {
            if (voiceAlert == null) voiceAlert = VoiceAlertManager(requireContext())
            lifecycleScope.launch {
                delay(700)
                showVoiceSelectionDialog()
            }
            true
        }

        findPreference<Preference>("voice_test")?.setOnPreferenceClickListener {
            if (voiceAlert == null) voiceAlert = VoiceAlertManager(requireContext())
            lifecycleScope.launch {
                delay(600)
                voiceAlert?.speak("Камера через пятьсот метров. Камера!")
            }
            true
        }

        // ── База камер ───────────────────────────────────────────────────

        updatePref?.setOnPreferenceClickListener {
            startUpdate()
            true
        }

        findPreference<Preference>("download_offline")?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), DownloadActivity::class.java))
            true
        }

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
    }

    override fun onResume() {
        super.onResume()
        refreshCount()
        refreshVoiceSummary()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceAlert?.destroy()
    }

    // ── Voice helpers ────────────────────────────────────────────────────────

    private fun refreshVoiceSummary() {
        val saved = PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .getString("tts_voice", null)
        ttsVoicePref?.summary = if (saved.isNullOrEmpty()) "По умолчанию" else saved
    }

    private fun showVoiceSelectionDialog() {
        val voices = voiceAlert?.getAvailableVoices() ?: emptyList()
        if (voices.isEmpty()) {
            Toast.makeText(requireContext(),
                "Доступных русских голосов не найдено.\nПроверьте настройки TTS в системе.",
                Toast.LENGTH_LONG).show()
            return
        }

        // Build display names: strip locale prefix, humanise engine suffix
        val displayNames = voices.map { v ->
            val parts = v.name.split("-")
            if (parts.size >= 5) parts.subList(3, parts.size).joinToString("-")
            else v.name
        }.toTypedArray()

        val savedName = PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .getString("tts_voice", null)
        val current = voices.indexOfFirst { it.name == savedName }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Голос озвучивания")
            .setSingleChoiceItems(displayNames, current) { dialog, which ->
                val selected = voices[which]
                PreferenceManager
                    .getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putString("tts_voice", selected.name)
                    .apply()
                ttsVoicePref?.summary = displayNames[which]
                voiceAlert?.applyVoice(selected.name)
                // Preview the selected voice
                lifecycleScope.launch {
                    delay(300)
                    voiceAlert?.speak("Голос выбран")
                }
                dialog.dismiss()
            }
            .setNeutralButton("По умолчанию") { _, _ ->
                PreferenceManager
                    .getDefaultSharedPreferences(requireContext())
                    .edit().remove("tts_voice").apply()
                ttsVoicePref?.summary = "По умолчанию"
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ── DB helpers ───────────────────────────────────────────────────────────

    private fun refreshAutoUpdateStatus() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val ts = prefs.getLong("last_auto_update_ts", 0L)
        autoUpdateStatusPref?.summary = if (ts == 0L) "Не выполнялось"
        else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))
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
