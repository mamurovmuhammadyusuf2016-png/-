package com.jarvis.agent.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.jarvis.agent.core.Phrases

/**
 * Always-on listening loop.
 *
 * Android's [SpeechRecognizer] is built for one short utterance at a time, so "always on"
 * really means "restart it every time it finishes".
 *
 * The restart is the delicate part. Calling `startListening` while a session is still alive
 * is answered with ERROR_CLIENT (5), and a naive loop then spins on that error forever.
 * Hence: exactly one in-flight session at a time ([sessionActive]), exactly one pending
 * restart ([restartRunnable]), a fresh recogniser object after a client error, and a
 * growing delay so a device that simply refuses to listen is not hammered.
 */
class VoiceService : android.app.Service() {

    companion object {
        const val ACTION_START = "com.jarvis.agent.START_LISTENING"
        const val ACTION_STOP = "com.jarvis.agent.STOP_LISTENING"
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 7341
        private const val RESTART_DELAY_MS = 250L
        /** No session may last longer than this without a callback. */
        private const val SESSION_WATCHDOG_MS = 15_000L
        /** After a finished command, the next sentence needs no wake word. */
        private const val FOLLOW_UP_MS = 9_000L
        private const val MAX_BACKOFF_MS = 30_000L
        /** After this many client errors in a row, say out loud what the user must fix. */
        private const val CLIENT_ERRORS_BEFORE_HINT = 4

        @Volatile
        var listening: Boolean = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { startListening() }

    private var recognizer: SpeechRecognizer? = null

    /** True between startListening() and the matching onResults/onError. */
    @Volatile
    private var sessionActive = false

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var speaking = false

    private var consecutiveClientErrors = 0
    private var hintGiven = false

    /** Until this moment the wake word is not required — see [FOLLOW_UP_MS]. */
    private var followUpUntil = 0L

    /** Fires when the recogniser accepted a session and then never called back at all. */
    private val watchdog = Runnable {
        if (sessionActive) {
            JarvisRuntime.log("Распознаватель не ответил — пересоздаю")
            sessionActive = false
            destroyRecognizer()
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        JarvisRuntime.init(applicationContext)
        // TTS callbacks arrive on a binder thread, and SpeechRecognizer may only be touched
        // from the main thread — calling cancel() off-main threw, was swallowed, and left a
        // live session behind. That leftover session is what answered ERROR_CLIENT (5).
        JarvisRuntime.speaker?.setStateListener { isSpeaking ->
            main.post {
                speaking = isSpeaking
                if (isSpeaking) {
                    JarvisRuntime.setState(JarvisRuntime.AgentState.SPEAKING)
                    cancelSession()
                } else {
                    if (!JarvisRuntime.busy) JarvisRuntime.settleState()
                    scheduleRestart(RESTART_DELAY_MS)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (!hasMicPermission()) {
            // Android 14+ throws when a microphone foreground service starts without it,
            // which happens on a START_STICKY restart after the user revoked the permission.
            JarvisRuntime.log("Нет доступа к микрофону — останавливаюсь")
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        shuttingDown = false
        listening = true
        consecutiveClientErrors = 0
        hintGiven = false
        JarvisRuntime.speaker?.setLanguage(JarvisRuntime.requirePrefs().language)
        JarvisRuntime.log("Listening started")
        JarvisRuntime.setState(JarvisRuntime.AgentState.LISTENING)
        scheduleRestart(200)
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        shuttingDown = true
        listening = false
        sessionActive = false
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(watchdog)
        destroyRecognizer()
        JarvisRuntime.micLevel = 0f
        JarvisRuntime.setState(JarvisRuntime.AgentState.IDLE)
        JarvisRuntime.log("Listening stopped")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- recognition

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun scheduleRestart(delay: Long) {
        if (shuttingDown) return
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, delay)
    }

    /** 600ms, 1.2s, 2.4s … capped, so a device that keeps refusing is not hammered. */
    private fun backoffMillis(): Long {
        if (consecutiveClientErrors <= 0) return RESTART_DELAY_MS
        val shift = (consecutiveClientErrors - 1).coerceAtMost(6)
        return (RESTART_DELAY_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (e: Exception) {
                // The service may already be gone; nothing useful to do.
            }
        }
        recognizer = null
    }

    private fun cancelSession() {
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(watchdog)
        sessionActive = false
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startListening() {
        if (shuttingDown || speaking) return
        main.post {
            if (shuttingDown || speaking) return@post
            // The single most important guard: one session at a time, or ERROR_CLIENT.
            if (sessionActive) return@post

            if (!hasMicPermission()) {
                JarvisRuntime.log("Нет разрешения на микрофон — откройте Jarvis и выдайте доступ")
                stopEverything()
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                JarvisRuntime.log(
                    "На устройстве нет службы распознавания речи. " +
                        "Установите/включите приложение Google и выберите его в " +
                        "Настройки → Приложения → Приложения по умолчанию → Голосовой ввод."
                )
                scheduleRestart(10_000)
                return@post
            }

            val r = recognizer ?: try {
                SpeechRecognizer.createSpeechRecognizer(this).also { created ->
                    created.setRecognitionListener(Listener())
                    recognizer = created
                }
            } catch (e: Exception) {
                JarvisRuntime.log("Не удалось создать распознаватель: ${e.message}")
                scheduleRestart(3000)
                return@post
            }

            val prefs = JarvisRuntime.requirePrefs()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, prefs.language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                // Google's default trailing silence is over a second; most of the "it is
                // so slow" feeling is spent here.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500)
                if (prefs.preferOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }

            }

            try {
                sessionActive = true
                r.startListening(intent)
                main.removeCallbacks(watchdog)
                main.postDelayed(watchdog, SESSION_WATCHDOG_MS)
            } catch (e: Exception) {
                sessionActive = false
                JarvisRuntime.log("startListening failed: ${e.message}")
                destroyRecognizer()
                scheduleRestart(2000)
            }
        }
    }

    /** ERROR_CLIENT usually means the recogniser object is wedged — throw it away. */
    private fun handleClientError() {
        consecutiveClientErrors++
        destroyRecognizer()
        if (consecutiveClientErrors >= CLIENT_ERRORS_BEFORE_HINT && !hintGiven) {
            hintGiven = true
            val hint = "Распознавание речи не запускается. Проверьте, что в " +
                "Настройки → Приложения → Приложения по умолчанию → Голосовой ввод " +
                "выбрано приложение Google, и что у него есть доступ к микрофону."
            JarvisRuntime.log(hint)
            JarvisRuntime.speaker?.say("Не могу запустить распознавание речи. Подробности в журнале.")
        }
        scheduleRestart(backoffMillis())
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "таймаут сети"
        SpeechRecognizer.ERROR_NETWORK -> "нет сети"
        SpeechRecognizer.ERROR_AUDIO -> "ошибка записи звука"
        SpeechRecognizer.ERROR_SERVER -> "ошибка сервера распознавания"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT (5): служба распознавания отказала"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "тишина"
        SpeechRecognizer.ERROR_NO_MATCH -> "ничего не распознано"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "распознаватель занят"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "нет разрешения на микрофон"
        else -> "код $error"
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // A session really started, so whatever was wedged is fine again.
            consecutiveClientErrors = 0
            if (!JarvisRuntime.busy) JarvisRuntime.setState(JarvisRuntime.AgentState.LISTENING)
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // The API reports roughly -2..10 dB; map it to 0..1 for the reactor.
            JarvisRuntime.micLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            sessionActive = false
            main.removeCallbacks(watchdog)
            JarvisRuntime.micLevel = 0f
            when (error) {
                // The normal "nobody said anything" case — not worth a log line.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveClientErrors = 0
                    scheduleRestart(RESTART_DELAY_MS)
                }

                SpeechRecognizer.ERROR_CLIENT -> {
                    JarvisRuntime.log("Распознавание: ${describeError(error)}, пересоздаю распознаватель")
                    if (!JarvisRuntime.busy) JarvisRuntime.setState(JarvisRuntime.AgentState.ERROR)
                    handleClientError()
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    JarvisRuntime.log("Распознавание: ${describeError(error)}")
                    destroyRecognizer()
                    scheduleRestart(1500)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    JarvisRuntime.log("Распознавание: ${describeError(error)} — выдайте доступ в приложении")
                    JarvisRuntime.speaker?.say("Нет доступа к микрофону")
                    stopEverything()
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    JarvisRuntime.log("Распознавание: ${describeError(error)}")
                    scheduleRestart(3000)
                }

                else -> {
                    JarvisRuntime.log("Распознавание: ${describeError(error)}")
                    scheduleRestart(2000)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            sessionActive = false
            main.removeCallbacks(watchdog)
            consecutiveClientErrors = 0
            JarvisRuntime.micLevel = 0f
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val heard = texts.firstOrNull()?.trim().orEmpty()
            if (heard.isNotEmpty()) {
                JarvisRuntime.log("heard: $heard")
                handle(heard)
            }
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    /** Confirmation answer -> cancel -> wake word -> command. */
    private fun handle(heard: String) {
        val prefs = JarvisRuntime.requirePrefs()
        val wakeWords = prefs.wakeWords()
        val addressed = Phrases.containsWakeWord(heard, wakeWords)
        val withoutWake = Phrases.stripWakeWord(heard, wakeWords).trim()

        val pendingToken = ConfirmationBus.currentToken
        if (ConfirmationBus.pendingQuestion != null) {
            // "да нет" is Russian for "no": an ambiguous reply is not an approval.
            when (Phrases.readAnswer(withoutWake)) {
                true -> {
                    ConfirmationBus.answer(true, pendingToken)
                    return
                }
                false -> {
                    ConfirmationBus.answer(false, pendingToken)
                    return
                }
                null -> Unit
            }
        }

        // Stopping must work even while a command is running, so it is checked before busy.
        if (Phrases.isCancel(withoutWake)) {
            JarvisRuntime.abort()
            return
        }

        val inFollowUp = android.os.SystemClock.uptimeMillis() < followUpUntil
        if (prefs.requireWakeWord && !addressed && !inFollowUp) return

        if (withoutWake.isEmpty()) {
            followUpUntil = android.os.SystemClock.uptimeMillis() + FOLLOW_UP_MS
            JarvisRuntime.speaker?.say("Слушаю")
            return
        }
        followUpUntil = 0L
        JarvisRuntime.submit(withoutWake) {
            // A finished command opens a short window where no wake word is needed.
            followUpUntil = android.os.SystemClock.uptimeMillis() + FOLLOW_UP_MS
        }
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
