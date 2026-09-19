package com.jarvis.agent.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Text-to-speech wrapper that also tells the recogniser to stay quiet while we talk. */
class Speaker(context: Context) {

    fun interface SpeechStateListener {
        fun onSpeakingChanged(speaking: Boolean)
    }

    private val counter = AtomicLong(0)
    private var ready = false
    private var listener: SpeechStateListener? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            JarvisRuntime.log("TTS ready")
        } else {
            JarvisRuntime.log("TTS unavailable (status=$status)")
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                listener?.onSpeakingChanged(true)
            }

            override fun onDone(utteranceId: String?) {
                listener?.onSpeakingChanged(false)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                listener?.onSpeakingChanged(false)
            }
        })
    }

    fun setStateListener(l: SpeechStateListener) {
        listener = l
    }

    fun setLanguage(tag: String) {
        try {
            tts.language = Locale.forLanguageTag(tag)
        } catch (e: Exception) {
            JarvisRuntime.log("TTS language $tag not available")
        }
    }

    fun say(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            JarvisRuntime.log("(tts not ready) $text")
            return
        }
        val id = "jarvis-${counter.incrementAndGet()}"
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stop() {
        try {
            tts.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun shutdown() {
        try {
            tts.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }
}
