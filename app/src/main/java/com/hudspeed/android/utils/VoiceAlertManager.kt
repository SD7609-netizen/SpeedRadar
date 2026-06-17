package com.hudspeed.android.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceAlertManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    private var lastAlertDistanceBucket = -1 // чтобы не повторять одно и то же

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("ru", "RU"))
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    fun onCameraDistance(distanceMeters: Float, cameraType: String = "камера") {
        if (!isReady) return

        val bucket = when {
            distanceMeters <= 100 -> 0
            distanceMeters <= 300 -> 1
            distanceMeters <= 500 -> 2
            distanceMeters <= 1000 -> 3
            else -> 4
        }

        if (bucket == lastAlertDistanceBucket || bucket == 4) return
        lastAlertDistanceBucket = bucket

        val text = when (bucket) {
            0 -> "$cameraType!"
            1 -> "$cameraType через сто метров"
            2 -> "$cameraType через пятьсот метров"
            3 -> "$cameraType через один километр"
            else -> return
        }

        speak(text)
    }

    fun onSpeedExceeded(speedKmh: Int, limitKmh: Int) {
        if (!isReady) return
        speak("Превышение скорости! $speedKmh километров в час")
    }

    fun onCameraCleared() {
        lastAlertDistanceBucket = -1
    }

    fun speak(text: String) {
        if (!isReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private val cameraName = "камера контроля скорости"
}
