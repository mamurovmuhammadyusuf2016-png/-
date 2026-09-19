package com.jarvis.agent.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

class MainActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var wakeWordInput: EditText
    private lateinit var languageInput: EditText
    private lateinit var requireWakeCheck: CheckBox
    private lateinit var offlineCheck: CheckBox
    private lateinit var commandInput: EditText

    private var dialog: AlertDialog? = null

    private val logListener = JarvisRuntime.LogListener { lines ->
        main.post {
            logView.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val confirmationListener = ConfirmationBus.Listener { question ->
        main.post { showConfirmation(question) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        JarvisRuntime.init(applicationContext)

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        apiKeyInput = findViewById(R.id.apiKey)
        modelInput = findViewById(R.id.model)
        baseUrlInput = findViewById(R.id.baseUrl)
        wakeWordInput = findViewById(R.id.wakeWord)
        languageInput = findViewById(R.id.language)
        requireWakeCheck = findViewById(R.id.requireWake)
        offlineCheck = findViewById(R.id.preferOffline)
        commandInput = findViewById(R.id.command)

        loadPrefs()

        findViewById<Button>(R.id.grantPermissions).setOnClickListener { requestRuntimePermissions() }
        findViewById<Button>(R.id.openAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.saveSettings).setOnClickListener {
            savePrefs()
            refreshStatus()
        }
        findViewById<Button>(R.id.startListening).setOnClickListener {
            savePrefs()
            if (!hasMicPermission()) {
                requestRuntimePermissions()
            } else {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START)
                )
                main.postDelayed({ refreshStatus() }, 400)
            }
        }
        findViewById<Button>(R.id.stopListening).setOnClickListener {
            startService(
                Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP)
            )
            main.postDelayed({ refreshStatus() }, 400)
        }
        findViewById<Button>(R.id.runCommand).setOnClickListener {
            val text = commandInput.text.toString().trim()
            if (text.isNotEmpty()) {
                savePrefs()
                JarvisRuntime.submit(text)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        JarvisRuntime.addLogListener(logListener)
        ConfirmationBus.addListener(confirmationListener)
        refreshStatus()
    }

    override fun onStop() {
        JarvisRuntime.removeLogListener(logListener)
        ConfirmationBus.removeListener(confirmationListener)
        dialog?.dismiss()
        dialog = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadPrefs() {
        val p = JarvisRuntime.requirePrefs()
        apiKeyInput.setText(p.apiKey)
        modelInput.setText(p.model)
        baseUrlInput.setText(p.baseUrl)
        wakeWordInput.setText(p.wakeWord)
        languageInput.setText(p.language)
        requireWakeCheck.isChecked = p.requireWakeWord
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
        p.preferOffline = offlineCheck.isChecked
        JarvisRuntime.speaker?.setLanguage(p.language)
    }

    private fun refreshStatus() {
        val p = JarvisRuntime.requirePrefs()
        val lines = listOf(
            check(hasMicPermission(), "Микрофон"),
            check(JarvisRuntime.accessibilityReady(), "Accessibility Service"),
            check(VoiceService.listening, "Слушает"),
            check(p.apiKey.isNotBlank() || p.baseUrl != com.jarvis.agent.core.GroqPlanner.DEFAULT_BASE_URL,
                "AI-планировщик (без него работают простые команды)")
        )
        statusView.text = lines.joinToString("\n")
    }

    private fun check(ok: Boolean, label: String): String = (if (ok) "✅ " else "❌ ") + label

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

    private fun showConfirmation(question: String?) {
        if (question == null) {
            dialog?.dismiss()
            dialog = null
            return
        }
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(this)
            .setTitle("Подтверждение")
            .setMessage(question)
            .setPositiveButton("Да") { _, _ -> ConfirmationBus.answer(true) }
            .setNegativeButton("Отмена") { _, _ -> ConfirmationBus.answer(false) }
            .setCancelable(false)
            .show()
    }
}
