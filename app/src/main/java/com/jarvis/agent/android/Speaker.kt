package com.jarvis.agent.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Text-to-speech, and the "am I talking right now" flag the recogniser needs.
 *
 * Everything here is written so the microphone can never be left switched off: [speaking]
 * is raised synchronously by [say] and always comes back down, whether the engine reports
 * completion, reports an error, or says nothing at all.
 */
class Speaker(context: Context) {

    fun interface SpeechStateListener {
        fun onSpeakingChanged(speaking: Boolean)
    }

    private val counter = AtomicLong(0)
    private val outstanding = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private val queued = ArrayDeque<String>()

    @Volatile
    private var ready = false

    @Volatile
    private var listener: SpeechStateListener? = null

    @Volatile
    private var pendingLanguage: String? = null

    @Volatile
    var speaking: Boolean = false
        private set

    private val watchdog = Runnable {
        if (speaking) {
            JarvisRuntime.log("TTS не сообщил об окончании — снимаю блокировку микрофона")
            outstanding.set(0)
            setSpeaking(false)
        }
    }

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            JarvisRuntime.log("TTS готов")
            pendingLanguage?.let { applyLanguage(it) }
            drainQueue()
        } else {
            JarvisRuntime.log("TTS недоступен (status=$status)")
            // Nothing will ever speak; do not hold the microphone hostage.
            outstanding.set(0)
            setSpeaking(false)
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                setSpeaking(true)
            }

            override fun onDone(utteranceId: String?) {
                finishOne()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishOne()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishOne()
            }
        })
    }

    fun setStateListener(l: SpeechStateListener) {
        listener = l
    }

    fun setLanguage(tag: String) {
        pendingLanguage = tag
        if (ready) applyLanguage(tag)
    }

    private fun applyLanguage(tag: String) {
        try {
            val result = tts.setLanguage(Locale.forLanguageTag(tag))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                JarvisRuntime.log("Голос для $tag не установлен — скачайте его в настройках синтеза речи")
            }
        } catch (e: Exception) {
            JarvisRuntime.log("Не удалось выбрать язык синтеза $tag")
        }
    }

    fun say(text: String) {
        if (text.isBlank()) return
        outstanding.incrementAndGet()
        // Synchronously, so the recogniser is suppressed before the first syllable.
        setSpeaking(true)
        armWatchdog(text)

        if (!ready) {
            synchronized(queued) { queued.add(text) }
            return
        }
        val id = "jarvis-${counter.incrementAndGet()}"
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) {
            JarvisRuntime.log("TTS отклонил фразу")
            finishOne()
        }
    }

    private fun drainQueue() {
        val pending = synchronized(queued) {
            val copy = queued.toList()
            queued.clear()
            copy
        }
        for (text in pending) {
            val id = "jarvis-${counter.incrementAndGet()}"
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                finishOne()
            }
        }
    }

    private fun finishOne() {
        if (outstanding.decrementAndGet() <= 0) {
            outstanding.set(0)
            handler.removeCallbacks(watchdog)
            setSpeaking(false)
        }
    }

    /** Speech is roughly 12 characters a second; give it that plus a wide margin. */
    private fun armWatchdog(text: String) {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 3000L + text.length * 90L)
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        listener?.onSpeakingChanged(value)
    }

    fun stop() {
        try {
            tts.stop()
        } catch (e: Exception) {
            // ignore
        }
        synchronized(queued) { queued.clear() }
        outstanding.set(0)
        handler.removeCallbacks(watchdog)
        setSpeaking(false)
    }

    fun shutdown() {
        stop()
        try {
            tts.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }
}
