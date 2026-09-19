package com.jarvis.agent.android

import android.app.KeyguardManager
import android.content.Context
import com.jarvis.agent.BuildConfig
import android.util.Log
import com.jarvis.agent.core.AgentLoop
import com.jarvis.agent.core.AgentResult
import com.jarvis.agent.core.AgentStatus
import com.jarvis.agent.core.AlwaysApprove
import com.jarvis.agent.core.GroqPlanner
import com.jarvis.agent.core.LayeredPlanner
import com.jarvis.agent.core.Planner
import com.jarvis.agent.core.RuleBasedPlanner
import com.jarvis.agent.core.VoiceOutput
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single wiring point: settings + planner + accessibility service + speech.
 *
 * Commands run on one background thread, because the agent loop blocks (it sleeps between
 * taps and waits for confirmations).
 */
object JarvisRuntime {

    private const val TAG = "JarvisRuntime"
    private const val MAX_LOG_LINES = 300

    /** What the reactor on the main screen is showing. */
    enum class AgentState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    fun interface LogListener {
        fun onLog(lines: List<String>)
    }

    fun interface StateListener {
        fun onState(state: AgentState)
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-agent").apply { isDaemon = true }
    }
    private val logLines = ArrayDeque<String>()
    private val logListeners = mutableListOf<LogListener>()
    private val stateListeners = mutableListOf<StateListener>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var prefs: Prefs? = null
        private set

    @Volatile
    var speaker: Speaker? = null

    private val busyFlag = AtomicBoolean(false)

    val busy: Boolean get() = busyFlag.get()

    /** The loop currently running, so "стоп" can stop it mid-plan. */
    @Volatile
    private var activeLoop: AgentLoop? = null

    @Volatile
    var state: AgentState = AgentState.IDLE
        private set

    /** 0..1 microphone loudness, read every frame by the reactor view. */
    @Volatile
    var micLevel: Float = 0f

    fun setState(next: AgentState) {
        if (state == next) return
        state = next
        if (next != AgentState.LISTENING) micLevel = 0f
        val listeners = synchronized(stateListeners) { stateListeners.toList() }
        for (l in listeners) l.onState(next)
    }

    /** Back to whatever the idle state is: listening if the service is up, otherwise idle. */
    fun settleState() {
        setState(if (VoiceService.listening) AgentState.LISTENING else AgentState.IDLE)
    }

    fun addStateListener(l: StateListener) {
        synchronized(stateListeners) { stateListeners.add(l) }
        l.onState(state)
    }

    fun removeStateListener(l: StateListener) {
        synchronized(stateListeners) { stateListeners.remove(l) }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = Prefs(context.applicationContext)
        if (speaker == null) speaker = Speaker(context.applicationContext)
        log("Runtime ready")
    }

    fun requirePrefs(): Prefs = prefs ?: throw IllegalStateException("JarvisRuntime is not initialised")

    fun accessibilityReady(): Boolean = JarvisAccessibilityService.isRunning()

    /**
     * Offline rules answer the obvious commands instantly; Groq handles everything else.
     * Without a key the rules are all there is.
     */
    fun planner(): Planner {
        val p = requirePrefs()
        val rules = RuleBasedPlanner()
        if (p.apiKey.isBlank() && p.baseUrl == GroqPlanner.DEFAULT_BASE_URL) return rules
        return LayeredPlanner(rules, groq(), { log(it) })
    }

    /**
     * One client for the whole session, so the model that answered last is tried first next
     * time instead of re-probing the list on every command.
     */
    @Volatile
    private var cachedGroq: GroqPlanner? = null

    @Volatile
    private var cachedGroqKey: String = ""

    @Synchronized
    fun groq(): GroqPlanner {
        val p = requirePrefs()
        val signature = p.apiKey + "|" + p.model + "|" + p.baseUrl
        val existing = cachedGroq
        if (existing != null && cachedGroqKey == signature) return existing
        val fresh = GroqPlanner(
            apiKeyProvider = { requirePrefs().apiKey },
            models = p.models(),
            baseUrl = p.baseUrl,
            log = { log(it) }
        )
        cachedGroq = fresh
        cachedGroqKey = signature
        return fresh
    }

    /** Call after the settings change so the next command uses them. */
    fun invalidatePlanner() {
        cachedGroq = null
        cachedGroqKey = ""
    }

    /** Stops the plan that is running right now. */
    fun abort() {
        val loop = activeLoop
        if (loop == null) {
            log("Нечего останавливать")
            return
        }
        loop.aborted = true
        log("Останавливаю по команде")
    }

    /** Asks Groq which models this key may use, and writes them into the log. */
    fun checkModels() {
        executor.execute {
            try {
                log("Запрашиваю список моделей Groq…")
                val models = groq().listModels()
                if (models.isEmpty()) {
                    log("Groq не вернул ни одной модели")
                } else {
                    log("Доступно моделей: ${models.size}")
                    models.forEach { log("  • $it") }
                    log("Впишите подходящую в поле «Модели» (через запятую — по порядку).")
                }
            } catch (e: Exception) {
                log("Не удалось получить список моделей: ${e.message}")
            }
        }
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
        val keyguard = appContext?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) {
            log("Телефон заблокирован — команда не выполняется")
            speaker?.say("Телефон заблокирован, разблокируйте экран")
            onResult(AgentResult(AgentStatus.FAILED, "Экран заблокирован"))
            return
        }
        // Check-and-set on the caller's thread: otherwise two quick commands both pass and
        // the second one runs minutes later against a screen that has nothing to do with it.
        if (!busyFlag.compareAndSet(false, true)) {
            log("Занят, команда пропущена: $command")
            speaker?.say("Секунду, ещё выполняю прошлую команду")
            onResult(AgentResult(AgentStatus.FAILED, "Агент занят"))
            return
        }
        executor.execute {
            setState(AgentState.THINKING)
            try {
                log("command: $command")
                val voice = VoiceOutput { text -> speaker?.say(text) }
                val prefs = requirePrefs()
                val loop = AgentLoop(
                    device = service,
                    planner = planner(),
                    voice = voice,
                    confirmation = if (prefs.requireConfirmation) ConfirmationBus else AlwaysApprove,
                    log = { log(it) }
                )
                activeLoop = loop
                val result = loop.run(command)
                log("result: ${result.status} — ${result.message}")
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "command failed", e)
                log("error: ${e.message}")
                speaker?.say("Произошла ошибка")
                onResult(AgentResult(AgentStatus.FAILED, e.message ?: "error"))
            } finally {
                activeLoop = null
                busyFlag.set(false)
                settleState()
            }
        }
    }

    // ---------------------------------------------------------------- log

    /** Never let an API key reach the log, the screen or logcat. */
    private fun redact(line: String): String =
        line.replace(Regex("gsk_[A-Za-z0-9_-]{8,}"), "gsk_***")

    fun log(rawLine: String) {
        val line = redact(rawLine)
        // logcat is a shared, persistent buffer; command text and message bodies go through
        // here, so only a debug build writes them out.
        if (BuildConfig.DEBUG) Log.d(TAG, line)
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
