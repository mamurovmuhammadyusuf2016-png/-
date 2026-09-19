package com.jarvis.agent.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.agent.R
import com.jarvis.agent.core.GroqPlanner

class MainActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var reactor: ReactorView
    private lateinit var stateLabel: TextView
    private lateinit var stateHint: TextView
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var primaryAction: Button
    private lateinit var settingsPanel: View
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var wakeWordInput: EditText
    private lateinit var languageInput: EditText
    private lateinit var requireWakeCheck: CheckBox
    private lateinit var confirmationCheck: CheckBox
    private lateinit var offlineCheck: CheckBox
    private lateinit var commandInput: EditText

    private var dialog: AlertDialog? = null

    private val logListener = JarvisRuntime.LogListener { lines ->
        main.post {
            if (isFinishing || isDestroyed) return@post
            logView.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val stateListener = JarvisRuntime.StateListener { state ->
        main.post {
            if (isFinishing || isDestroyed) return@post
            applyState(state)
        }
    }

    private val confirmationListener = ConfirmationBus.Listener { question, token ->
        main.post {
            if (isFinishing || isDestroyed) return@post
            showConfirmation(question, token)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        JarvisRuntime.init(applicationContext)

        reactor = findViewById(R.id.reactor)
        stateLabel = findViewById(R.id.stateLabel)
        stateHint = findViewById(R.id.stateHint)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        primaryAction = findViewById(R.id.primaryAction)
        settingsPanel = findViewById(R.id.settingsPanel)
        apiKeyInput = findViewById(R.id.apiKey)
        modelInput = findViewById(R.id.model)
        baseUrlInput = findViewById(R.id.baseUrl)
        wakeWordInput = findViewById(R.id.wakeWord)
        languageInput = findViewById(R.id.language)
        requireWakeCheck = findViewById(R.id.requireWake)
        confirmationCheck = findViewById(R.id.requireConfirmation)
        offlineCheck = findViewById(R.id.preferOffline)
        commandInput = findViewById(R.id.command)

        reactor.levelProvider = { JarvisRuntime.micLevel }

        loadPrefs()

        primaryAction.setOnClickListener { toggleListening() }

        findViewById<Button>(R.id.runCommand).setOnClickListener { runTypedCommand() }
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runTypedCommand()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.grantPermissions).setOnClickListener { requestRuntimePermissions() }
        findViewById<Button>(R.id.openAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.toggleSettings).setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.saveSettings).setOnClickListener {
            savePrefs()
            refreshStatus()
            JarvisRuntime.log("Настройки сохранены")
        }
        findViewById<Button>(R.id.checkModels).setOnClickListener {
            savePrefs()
            JarvisRuntime.checkModels()
        }
    }

    override fun onStart() {
        super.onStart()
        JarvisRuntime.addLogListener(logListener)
        JarvisRuntime.addStateListener(stateListener)
        ConfirmationBus.addListener(confirmationListener)
        refreshStatus()
    }

    override fun onStop() {
        JarvisRuntime.removeLogListener(logListener)
        JarvisRuntime.removeStateListener(stateListener)
        ConfirmationBus.removeListener(confirmationListener)
        main.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------- actions

    private fun toggleListening() {
        savePrefs()
        if (VoiceService.listening) {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
        } else if (!hasMicPermission()) {
            requestRuntimePermissions()
            return
        } else {
            ContextCompat.startForegroundService(
                this,
                Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START)
            )
        }
        main.postDelayed({ refreshStatus() }, 400)
    }

    private fun runTypedCommand() {
        val text = commandInput.text.toString().trim()
        if (text.isEmpty()) return
        savePrefs()
        commandInput.setText("")
        JarvisRuntime.submit(text)
    }

    // ---------------------------------------------------------------- state

    private fun applyState(state: JarvisRuntime.AgentState) {
        reactor.mode = when (state) {
            JarvisRuntime.AgentState.IDLE -> ReactorView.Mode.IDLE
            JarvisRuntime.AgentState.LISTENING -> ReactorView.Mode.LISTENING
            JarvisRuntime.AgentState.THINKING -> ReactorView.Mode.THINKING
            JarvisRuntime.AgentState.SPEAKING -> ReactorView.Mode.SPEAKING
            JarvisRuntime.AgentState.ERROR -> ReactorView.Mode.ERROR
        }
        val (label, hint) = when (state) {
            JarvisRuntime.AgentState.IDLE ->
                "ОЖИДАНИЕ" to "Нажмите «Начать слушать»"
            JarvisRuntime.AgentState.LISTENING ->
                "СЛУШАЮ" to "Скажите «${JarvisRuntime.requirePrefs().wakeWord}, открой Telegram»"
            JarvisRuntime.AgentState.THINKING ->
                "ДУМАЮ" to "Подбираю действия"
            JarvisRuntime.AgentState.SPEAKING ->
                "ОТВЕЧАЮ" to "…"
            JarvisRuntime.AgentState.ERROR ->
                "ОШИБКА" to "Подробности в журнале"
        }
        stateLabel.text = label
        stateHint.text = hint
        primaryAction.text = if (VoiceService.listening) "Остановить" else "Начать слушать"
    }

    private fun refreshStatus() {
        val p = JarvisRuntime.requirePrefs()
        val aiReady = p.apiKey.isNotBlank() || p.baseUrl != GroqPlanner.DEFAULT_BASE_URL
        statusView.text = listOf(
            check(hasMicPermission(), "Микрофон"),
            check(JarvisRuntime.accessibilityReady(), "Accessibility Service"),
            check(VoiceService.listening, "Прослушивание"),
            check(aiReady, "Groq (без него — только простые команды)")
        ).joinToString("\n")
        applyState(JarvisRuntime.state)
    }

    private fun check(ok: Boolean, label: String): String = (if (ok) "◉  " else "○  ") + label

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    // ---------------------------------------------------------------- settings

    private fun loadPrefs() {
        val p = JarvisRuntime.requirePrefs()
        apiKeyInput.setText(p.apiKey)
        modelInput.setText(p.model)
        baseUrlInput.setText(p.baseUrl)
        wakeWordInput.setText(p.wakeWord)
        languageInput.setText(p.language)
        requireWakeCheck.isChecked = p.requireWakeWord
        confirmationCheck.isChecked = p.requireConfirmation
        offlineCheck.isChecked = p.preferOffline
    }

    private fun savePrefs() {
        val p = JarvisRuntime.requirePrefs()
        p.apiKey = apiKeyInput.text.toString()
        p.model = modelInput.text.toString()
        p.baseUrl = baseUrlInput.text.toString()
        p.wakeWord = wakeWordInput.text.toString()
        p.language = languageInput.text.toString()
        p.requireWakeWord = requireWakeCheck.isChecked
        p.requireConfirmation = confirmationCheck.isChecked
        p.preferOffline = offlineCheck.isChecked
        JarvisRuntime.speaker?.setLanguage(p.language)
        JarvisRuntime.invalidatePlanner()
    }

    private fun showConfirmation(question: String?, token: Long) {
        if (question == null) {
            dialog?.dismiss()
            dialog = null
            return
        }
        if (isFinishing || isDestroyed) return
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(this)
            .setTitle("Подтверждение")
            .setMessage(question)
            .setPositiveButton("Да") { _, _ -> ConfirmationBus.answer(true, token) }
            .setNegativeButton("Отмена") { _, _ -> ConfirmationBus.answer(false, token) }
            .setCancelable(false)
            .show()
    }
}
