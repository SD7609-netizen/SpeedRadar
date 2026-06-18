package com.hudspeed.android

import android.content.*
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hudspeed.android.data.CameraDatabase
import com.hudspeed.android.data.CameraRepository
import com.hudspeed.android.service.DownloadService
import com.hudspeed.android.utils.ImportManager
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
    private lateinit var btnImportFile: Button
    private lateinit var tvImportStatus: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvBackupInfo: TextView

    // Файлпикер для импорта CSV/KML/GPX
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFile(uri)
    }

    private val backupFile: File get() =
        File(getExternalFilesDir(null), "cameras_backup.db")

    // Приёмник прогресса от фонового сервиса
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val finished = intent?.getBooleanExtra(DownloadService.EXTRA_FINISHED, false) ?: false
            val done     = intent?.getIntExtra(DownloadService.EXTRA_CHUNKS_DONE, 0) ?: 0
            val total    = intent?.getIntExtra(DownloadService.EXTRA_CHUNKS_TOTAL, 0) ?: 0
            val cameras  = intent?.getIntExtra(DownloadService.EXTRA_CAMERAS, 0) ?: 0
            val name     = intent?.getStringExtra(DownloadService.EXTRA_COUNTRY_NAME) ?: ""

            if (finished) {
                tvTotal.text    = "Всего в базе: $cameras камер"
                tvStatus.text   = "Готово! $name загружена."
                tvProgress.text = ""
                progressBar.visibility = View.GONE
                setButtonsEnabled(true)
            } else {
                progressBar.visibility = View.VISIBLE
                progressBar.max      = total
                progressBar.progress = done
                tvProgress.text = "$done / $total чанков  •  $cameras камер"
                tvStatus.text   = "Загрузка $name: $done из $total"
                refreshTotal()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "База камер"

        repository = CameraRepository(CameraDatabase.getInstance(this).cameraDao())

        tvStatus      = findViewById(R.id.tvStatus)
        progressBar   = findViewById(R.id.progressBar)
        tvProgress    = findViewById(R.id.tvProgress)
        btnDownloadRU = findViewById(R.id.btnDownloadRU)
        btnDownloadBY = findViewById(R.id.btnDownloadBY)
        btnClearDb    = findViewById(R.id.btnClearDb)
        btnExport      = findViewById(R.id.btnExport)
        btnImport      = findViewById(R.id.btnImport)
        btnImportFile  = findViewById(R.id.btnImportFile)
        tvImportStatus = findViewById(R.id.tvImportStatus)
        tvTotal        = findViewById(R.id.tvTotal)
        tvBackupInfo   = findViewById(R.id.tvBackupInfo)

        registerReceiver(progressReceiver, IntentFilter(DownloadService.ACTION_PROGRESS))

        refreshTotal()
        updateBackupInfo()
        syncWithRunningService()

        btnDownloadRU.setOnClickListener { startDownload("RU") }
        btnDownloadBY.setOnClickListener { startDownload("BY") }
        btnClearDb.setOnClickListener   { clearDatabase() }
        btnExport.setOnClickListener    { exportDatabase() }
        btnImport.setOnClickListener    { importDatabase() }
        btnImportFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf(
                "text/csv", "text/plain", "application/vnd.google-earth.kml+xml",
                "application/gpx+xml", "application/octet-stream", "*/*"
            ))
        }
    }

    // Если сервис уже качает — показываем это в UI
    private fun syncWithRunningService() {
        if (DownloadService.isRunning(this)) {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            tvStatus.text = "Загрузка идёт в фоне..."
            setButtonsEnabled(false)
        }
    }

    private fun startDownload(countryCode: String) {
        val name = CameraRepository.countryNames[countryCode] ?: countryCode
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        tvStatus.text   = "Запуск загрузки $name..."
        tvProgress.text = ""

        // Запускаем фоновый сервис — он продолжит даже если выйдешь из экрана
        DownloadService.start(this, countryCode)
    }

    private fun importFile(uri: android.net.Uri) {
        tvImportStatus.text = "Читаем файл..."
        btnImportFile.isEnabled = false

        lifecycleScope.launch {
            val cameras = withContext(Dispatchers.IO) {
                ImportManager.parseFile(this@DownloadActivity, uri)
            }

            if (cameras.isEmpty()) {
                tvImportStatus.text = "Камеры не найдены. Проверьте формат файла."
                btnImportFile.isEnabled = true
                return@launch
            }

            tvImportStatus.text = "Сохраняем ${cameras.size} камер..."

            withContext(Dispatchers.IO) {
                // Вставляем пачками по 500 для производительности
                cameras.chunked(500).forEach { chunk ->
                    repository.insertAll(chunk)
                }
            }

            val total = withContext(Dispatchers.IO) { repository.count() }
            tvTotal.text = "Всего в базе: $total камер"
            tvImportStatus.text = "Импортировано: ${cameras.size} камер (всего в базе: $total)"
            btnImportFile.isEnabled = true

            Toast.makeText(this@DownloadActivity,
                "Импорт завершён: ${cameras.size} камер", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportDatabase() {
        CameraDatabase.getInstance(this).close()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile = getDatabasePath("cameras.db")
                backupFile.parentFile?.mkdirs()
                dbFile.copyTo(backupFile, overwrite = true)
                withContext(Dispatchers.Main) {
                    updateBackupInfo()
                    Toast.makeText(this@DownloadActivity,
                        "Экспорт выполнен!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DownloadActivity,
                        "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
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
        CameraDatabase.getInstance(this).close()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile = getDatabasePath("cameras.db")
                backupFile.copyTo(dbFile, overwrite = true)
                CameraDatabase.reset()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DownloadActivity,
                        "База восстановлена!", Toast.LENGTH_SHORT).show()
                    refreshTotal()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DownloadActivity,
                        "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun refreshTotal() {
        lifecycleScope.launch {
            val count = repository.count()
            tvTotal.text = "Всего в базе: $count камер"
        }
    }

    private fun updateBackupInfo() {
        tvBackupInfo.text = if (backupFile.exists()) {
            val mb = backupFile.length() / 1024 / 1024
            "Бэкап: ${mb} МБ\n${backupFile.absolutePath}"
        } else "Бэкап не найден"
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnDownloadRU.isEnabled  = enabled
        btnDownloadBY.isEnabled  = enabled
        btnClearDb.isEnabled     = enabled
        btnExport.isEnabled      = enabled
        btnImport.isEnabled      = enabled
        btnImportFile.isEnabled  = enabled
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // НЕ отменяем загрузку при выходе — сервис продолжает работать
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(progressReceiver) } catch (e: Exception) {}
    }
}
