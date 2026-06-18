package com.hudspeed.android

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hudspeed.android.data.CameraDatabase
import com.hudspeed.android.data.CameraRepository
import kotlinx.coroutines.*

class DownloadActivity : AppCompatActivity() {

    private lateinit var repository: CameraRepository
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnDownloadRU: Button
    private lateinit var btnDownloadBY: Button
    private lateinit var btnClearDb: Button
    private lateinit var tvTotal: TextView

    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Загрузка базы камер"

        repository = CameraRepository(CameraDatabase.getInstance(this).cameraDao())

        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        btnDownloadRU = findViewById(R.id.btnDownloadRU)
        btnDownloadBY = findViewById(R.id.btnDownloadBY)
        btnClearDb = findViewById(R.id.btnClearDb)
        tvTotal = findViewById(R.id.tvTotal)

        refreshTotal()

        btnDownloadRU.setOnClickListener { startDownload("RU") }
        btnDownloadBY.setOnClickListener { startDownload("BY") }

        btnClearDb.setOnClickListener {
            lifecycleScope.launch {
                repository.deleteAll()
                refreshTotal()
                tvStatus.text = "База очищена"
            }
        }
    }

    private fun startDownload(countryCode: String) {
        val countryName = CameraRepository.countryNames[countryCode] ?: countryCode
        downloadJob?.cancel()

        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "Загрузка $countryName..."
        tvProgress.text = "0 / ? чанков"

        downloadJob = lifecycleScope.launch {
            repository.downloadCountry(countryCode) { done, total, cameras ->
                progressBar.max = total
                progressBar.progress = done
                tvProgress.text = "$done / $total чанков • $cameras камер"
                tvStatus.text = "Загрузка $countryName: $done из $total..."
            }

            val total = repository.count()
            tvTotal.text = "Всего в базе: $total камер"
            tvStatus.text = "Готово! $countryName загружена."
            progressBar.visibility = View.GONE
            tvProgress.text = ""
            setButtonsEnabled(true)

            // Сохраняем для Settings
            getSharedPreferences("camera_db", MODE_PRIVATE).edit()
                .putInt("total_count", total).apply()
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnDownloadRU.isEnabled = enabled
        btnDownloadBY.isEnabled = enabled
        btnClearDb.isEnabled = enabled
    }

    private fun refreshTotal() {
        lifecycleScope.launch {
            val count = repository.count()
            tvTotal.text = "Всего в базе: $count камер"
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
    }
}
