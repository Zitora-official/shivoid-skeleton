package com.example.bridge

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class ShivoidTtsManager(
    context: Context,
    private val onUtteranceDone: (utteranceId: String) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { onUtteranceDone(it) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
                override fun onError(utteranceId: String?, errorCode: Int) {}
            })
            isInitialized = true
        }
    }

    fun speak(
        text: String,
        langCode: String? = null,
        pitch: Float = 1.0f,
        rate: Float = 1.0f,
        utteranceId: String? = null
    ): Boolean {
        val engine = tts ?: return false
        if (!isInitialized) return false

        try {
            if (!langCode.isNullOrBlank()) {
                val locale = Locale.forLanguageTag(langCode)
                engine.language = locale
            }
            engine.setPitch(pitch.coerceIn(0.2f, 3.0f))
            engine.setSpeechRate(rate.coerceIn(0.2f, 3.0f))

            val uid = utteranceId ?: "tts_${System.currentTimeMillis()}"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
            }
            val res = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
            return res == TextToSpeech.SUCCESS
        } catch (_: Exception) {
            return false
        }
    }

    fun stop(): Boolean {
        return try {
            tts?.stop()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isSpeaking(): Boolean {
        return try {
            tts?.isSpeaking ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (_: Exception) {}
    }
}
