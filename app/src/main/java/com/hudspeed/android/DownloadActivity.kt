package com.hudspeed.android

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hudspeed.android.data.CameraDatabase
import com.hudspeed.android.data.CameraRepository
import kotlinx.coroutines.*
import java.io.File

class DownloadActivity : AppCompatActivity() {

    private lateinit var repository: CameraRepository
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnDownloadRU: Button
    private lateinit var btnDownloadBY: Button
    private lateinit var btnClearDb: Button
    private lateinit var btnExport: Button
    private lateinit var btnImport: Button
    private lateinit var tvTotal: TextView
    private lateinit var tvBackupInfo: TextView

    private var downloadJob: Job? = null

    // Путь к бэкап-файлу (не требует разрешений на всех Android)
    private val backupFile: File get() =
        File(getExternalFilesDir(null), "cameras_backup.db")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "База камер"

        repository = CameraRepository(CameraDatabase.getInstance(this).cameraDao())

        tvStatus     = findViewById(R.id.tvStatus)
        progressBar  = findViewById(R.id.progressBar)
        tvProgress   = findViewById(R.id.tvProgress)
        btnDownloadRU = findViewById(R.id.btnDownloadRU)
        btnDownloadBY = findViewById(R.id.btnDownloadBY)
        btnClearDb   = findViewById(R.id.btnClearDb)
        btnExport    = findViewById(R.id.btnExport)
        btnImport    = findViewById(R.id.btnImport)
        tvTotal      = findViewById(R.id.tvTotal)
        tvBackupInfo = findViewById(R.id.tvBackupInfo)

        refreshTotal()
        updateBackupInfo()

        btnDownloadRU.setOnClickListener { startDownload("RU") }
        btnDownloadBY.setOnClickListener { startDownload("BY") }
        btnClearDb.setOnClickListener { clearDatabase() }
        btnExport.setOnClickListener { exportDatabase() }
        btnImport.setOnClickListener { importDatabase() }
    }

    private fun startDownload(countryCode: String) {
        val countryName = CameraRepository.countryNames[countryCode] ?: countryCode
        downloadJob?.cancel()
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "Загрузка $countryName..."
        tvProgress.text = "Подключение..."

        downloadJob = lifecycleScope.launch {
            repository.downloadCountry(countryCode) { done, total, cameras ->
                progressBar.max = total
                progressBar.progress = done
                tvProgress.text = "$done / $total чанков  •  $cameras камер"
                tvStatus.text = "Загрузка $countryName: $done из $total"
            }
            val total = repository.count()
            tvTotal.text = "Всего в базе: $total камер"
            tvStatus.text = "Готово! $countryName загружена."
            progressBar.visibility = View.GONE
            tvProgress.text = ""
            setButtonsEnabled(true)
        }
    }

    private fun exportDatabase() {
        // Закрываем DB перед копированием
        CameraDatabase.getInstance(this).close()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile = getDatabasePath("cameras.db")
                backupFile.parentFile?.mkdirs()
                dbFile.copyTo(backupFile, overwrite = true)

                withContext(Dispatchers.Main) {
                    tvBackupInfo.text = "Бэкап сохранён:\n${backupFile.absolutePath}"
                    Toast.makeText(this@DownloadActivity,
                        "Экспорт выполнен!", Toast.LENGTH_SHORT).show()
                    updateBackupInfo()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DownloadActivity,
                        "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importDatabase() {
        if (!backupFile.exists()) {
            Toast.makeText(this, "Файл бэкапа не найден.\nСначала сделайте экспорт.",
                Toast.LENGTH_LONG).show()
            return
        }

        // Закрываем и восстанавливаем
        CameraDatabase.getInstance(this).close()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile = getDatabasePath("cameras.db")
                backupFile.copyTo(dbFile, overwrite = true)

                // Сбрасываем синглтон чтобы он открылся заново
                CameraDatabase.reset()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DownloadActivity,
                        "База восстановлена из бэкапа!", Toast.LENGTH_SHORT).show()
                    refreshTotal()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DownloadActivity,
                        "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun clearDatabase() {
        lifecycleScope.launch {
            repository.deleteAll()
            refreshTotal()
            tvStatus.text = "База очищена"
        }
    }

    private fun updateBackupInfo() {
        tvBackupInfo.text = if (backupFile.exists()) {
            val mb = backupFile.length() / 1024 / 1024
            "Бэкап: ${backupFile.name} (${mb} МБ)\n${backupFile.absolutePath}"
        } else {
            "Бэкап не найден"
        }
    }

    private fun refreshTotal() {
        lifecycleScope.launch {
            val count = repository.count()
            tvTotal.text = "Всего в базе: $count камер"
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnDownloadRU.isEnabled = enabled
        btnDownloadBY.isEnabled = enabled
        btnClearDb.isEnabled = enabled
        btnExport.isEnabled = enabled
        btnImport.isEnabled = enabled
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() { super.onDestroy(); downloadJob?.cancel() }
}
