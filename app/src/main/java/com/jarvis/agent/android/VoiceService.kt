package com.jarvis.agent.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.agent.core.Phrases

/**
 * Always-on listening loop.
 *
 * Android's [SpeechRecognizer] is built for one short utterance at a time, so "always on"
 * really means "restart it every time it finishes". That is the documented limitation; it
 * works, it just costs a little battery.
 */
class VoiceService : android.app.Service() {

    companion object {
        const val ACTION_START = "com.jarvis.agent.START_LISTENING"
        const val ACTION_STOP = "com.jarvis.agent.STOP_LISTENING"
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 7341
        private const val RESTART_DELAY_MS = 700L

        @Volatile
        var listening: Boolean = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var shuttingDown = false
    private var speaking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        JarvisRuntime.init(applicationContext)
        JarvisRuntime.speaker?.setStateListener { isSpeaking ->
            speaking = isSpeaking
            if (!isSpeaking) main.postDelayed({ startListening() }, RESTART_DELAY_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }
        startInForeground()
        shuttingDown = false
        listening = true
        JarvisRuntime.requirePrefs().let { JarvisRuntime.speaker?.setLanguage(it.language) }
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        shuttingDown = true
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (e: Exception) {
                // ignore
            }
        }
        recognizer = null
        JarvisRuntime.log("Listening stopped")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- recognition

    private fun startListening() {
        if (shuttingDown || speaking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JarvisRuntime.log("Speech recognition is not available on this device")
            return
        }
        main.post {
            if (shuttingDown) return@post
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                    it.setRecognitionListener(Listener())
                }
            }
            val prefs = JarvisRuntime.requirePrefs()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            try {
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                JarvisRuntime.log("startListening failed: ${e.message}")
                scheduleRestart(1500)
            }
        }
    }

    private fun scheduleRestart(delay: Long) {
        if (shuttingDown) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ startListening() }, delay)
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            // NO_MATCH / SPEECH_TIMEOUT are the normal "nobody said anything" case.
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RESTART_DELAY_MS
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                else -> {
                    JarvisRuntime.log("Recognizer error $error")
                    2000L
                }
            }
            scheduleRestart(delay)
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val heard = texts.firstOrNull()?.trim().orEmpty()
            if (heard.isNotEmpty()) {
                JarvisRuntime.log("heard: $heard")
                handle(heard)
            }
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    /** Wake word -> confirmation answer -> command. */
    private fun handle(heard: String) {
        val prefs = JarvisRuntime.requirePrefs()

        if (ConfirmationBus.pendingQuestion != null) {
            when {
                Phrases.isYes(heard) -> {
                    ConfirmationBus.answer(true)
                    return
                }
                Phrases.isNo(heard) -> {
                    ConfirmationBus.answer(false)
                    return
                }
            }
        }

        val wakeWords = prefs.wakeWords()
        if (prefs.requireWakeWord && !Phrases.containsWakeWord(heard, wakeWords)) {
            return
        }
        val command = Phrases.stripWakeWord(heard, wakeWords).trim()
        if (command.isEmpty()) {
            JarvisRuntime.speaker?.say("Слушаю")
            return
        }
        JarvisRuntime.submit(command)
    }

    // ---------------------------------------------------------------- foreground plumbing

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis listening",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shown while Jarvis is listening for commands"
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis слушает")
            .setContentText("Скажите «${JarvisRuntime.requirePrefs().wakeWord}, открой Telegram»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stop)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }
}
