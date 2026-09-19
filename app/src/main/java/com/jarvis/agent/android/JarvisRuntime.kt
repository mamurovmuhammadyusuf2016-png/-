package com.jarvis.agent.android

import android.content.Context
import android.util.Log
import com.jarvis.agent.core.AgentLoop
import com.jarvis.agent.core.AgentResult
import com.jarvis.agent.core.AgentStatus
import com.jarvis.agent.core.GroqPlanner
import com.jarvis.agent.core.FallbackPlanner
import com.jarvis.agent.core.Planner
import com.jarvis.agent.core.RuleBasedPlanner
import com.jarvis.agent.core.VoiceOutput
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Single wiring point: settings + planner + accessibility service + speech.
 *
 * Commands run on one background thread, because the agent loop blocks (it sleeps between
 * taps and waits for confirmations).
 */
object JarvisRuntime {

    private const val TAG = "JarvisRuntime"
    private const val MAX_LOG_LINES = 300

    fun interface LogListener {
        fun onLog(lines: List<String>)
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-agent").apply { isDaemon = true }
    }
    private val logLines = ArrayDeque<String>()
    private val logListeners = mutableListOf<LogListener>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var prefs: Prefs? = null
        private set

    @Volatile
    var speaker: Speaker? = null

    @Volatile
    var busy: Boolean = false
        private set

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = Prefs(context.applicationContext)
        if (speaker == null) speaker = Speaker(context.applicationContext)
        log("Runtime ready")
    }

    fun requirePrefs(): Prefs = prefs ?: throw IllegalStateException("JarvisRuntime is not initialised")

    fun accessibilityReady(): Boolean = JarvisAccessibilityService.isRunning()

    /** Claude when a key is configured, offline rules otherwise — and rules as the safety net. */
    fun planner(): Planner {
        val p = requirePrefs()
        val rules = RuleBasedPlanner()
        if (p.apiKey.isBlank() && p.baseUrl == GroqPlanner.DEFAULT_BASE_URL) return rules
        val claude = GroqPlanner(
            apiKeyProvider = { requirePrefs().apiKey },
            model = p.model,
            baseUrl = p.baseUrl,
            log = { log(it) }
        )
        return FallbackPlanner(claude, rules) { log("planner fallback: $it") }
    }

    /** Runs [command] on the agent thread; [onResult] is called on that same thread. */
    fun submit(command: String, onResult: (AgentResult) -> Unit = {}) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            log("Accessibility service is OFF — enable it in Settings")
            speaker?.say("Включите службу специальных возможностей для Jarvis")
            onResult(AgentResult(AgentStatus.FAILED, "Accessibility service disabled"))
            return
        }
        if (busy) {
            log("Busy, command ignored: $command")
            return
        }
        executor.execute {
            busy = true
            try {
                log("command: $command")
                val voice = VoiceOutput { text -> speaker?.say(text) }
                val loop = AgentLoop(
                    device = service,
                    planner = planner(),
                    voice = voice,
                    confirmation = ConfirmationBus,
                    log = { log(it) }
                )
                val result = loop.run(command)
                log("result: ${result.status} — ${result.message}")
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "command failed", e)
                log("error: ${e.message}")
                speaker?.say("Произошла ошибка")
                onResult(AgentResult(AgentStatus.FAILED, e.message ?: "error"))
            } finally {
                busy = false
            }
        }
    }

    // ---------------------------------------------------------------- log

    fun log(line: String) {
        Log.d(TAG, line)
        val stamped = "${timeFormat.format(Date())}  $line"
        val snapshot: List<String>
        synchronized(logLines) {
            logLines.addLast(stamped)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            snapshot = logLines.toList()
        }
        val listeners = synchronized(logListeners) { logListeners.toList() }
        for (l in listeners) l.onLog(snapshot)
    }

    fun logSnapshot(): List<String> = synchronized(logLines) { logLines.toList() }

    fun addLogListener(l: LogListener) {
        synchronized(logListeners) { logListeners.add(l) }
        l.onLog(logSnapshot())
    }

    fun removeLogListener(l: LogListener) {
        synchronized(logListeners) { logListeners.remove(l) }
    }
}
