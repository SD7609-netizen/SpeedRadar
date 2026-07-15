package com.hudspeed.android.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.preference.PreferenceManager
import java.util.Locale

class VoiceAlertManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var lastAlertDistanceBucket = -1

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("ru", "RU"))
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                applySavedVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { releaseAudioFocus() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { releaseAudioFocus() }
                })
            }
        }
    }

    private fun applySavedVoice() {
        val savedName = prefs.getString("tts_voice", null) ?: return
        tts?.voices?.find { it.name == savedName }?.let { tts?.voice = it }
    }

    fun onCameraDistance(distanceMeters: Float, cameraType: String = "камера") {
        if (!isReady || !prefs.getBoolean("voice_enabled", true)) return
        val bucket = when {
            distanceMeters <= 100  -> 0
            distanceMeters <= 300  -> 1
            distanceMeters <= 500  -> 2
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
        if (!isReady || !prefs.getBoolean("voice_enabled", true)) return
        speak("Превышение скорости! $speedKmh километров в час")
    }

    fun onCameraCleared() {
        lastAlertDistanceBucket = -1
        if (isReady && prefs.getBoolean("voice_camera_cleared", false)
            && prefs.getBoolean("voice_enabled", true)) {
            speak("Камер нет")
        }
    }

    /** Speaks text immediately, bypassing voice_enabled check — used for test button. */
    fun speak(text: String) {
        if (!isReady) return
        applyVolume()
        requestAudioFocus()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
    }

    // ── Volume ───────────────────────────────────────────────────────────────

    private fun applyVolume() {
        val pct = prefs.getInt("voice_volume", 80) / 100f
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (maxVol * pct).toInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    // ── Audio Focus ──────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (!prefs.getBoolean("voice_audio_focus", true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    // ── Voice selection ──────────────────────────────────────────────────────

    /** Returns available Russian TTS voices, sorted by name. */
    fun getAvailableVoices(): List<Voice> =
        tts?.voices
            ?.filter { it.locale.language == "ru" }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun applyVoice(voiceName: String) {
        tts?.voices?.find { it.name == voiceName }?.let { tts?.voice = it }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun destroy() {
        releaseAudioFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
