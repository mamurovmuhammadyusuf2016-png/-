package com.jarvis.agent.android

import android.content.Context
import com.jarvis.agent.BuildConfig
import com.jarvis.agent.core.GroqPlanner
import com.jarvis.agent.core.Phrases

/** Everything the user can configure, stored in SharedPreferences. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    /**
     * Groq key. Defaults to whatever was baked in at build time from local.properties /
     * the GROQ_API_KEY env var; the settings screen overrides it.
     */
    var apiKey: String
        get() = sp.getString(KEY_API, BuildConfig.GROQ_API_KEY).orEmpty()
            .ifBlank { BuildConfig.GROQ_API_KEY }
        set(value) = sp.edit().putString(KEY_API, value.trim()).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, GroqPlanner.DEFAULT_MODEL)
            .orEmpty().ifBlank { GroqPlanner.DEFAULT_MODEL }
        set(value) = sp.edit().putString(KEY_MODEL, value.trim()).apply()

    /** Point this at your own proxy to keep the API key off the phone. */
    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, GroqPlanner.DEFAULT_BASE_URL)
            .orEmpty().ifBlank { GroqPlanner.DEFAULT_BASE_URL }
        set(value) = sp.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var wakeWord: String
        get() = sp.getString(KEY_WAKE, "Jarvis").orEmpty().ifBlank { "Jarvis" }
        set(value) = sp.edit().putString(KEY_WAKE, value.trim()).apply()

    /** When true, a spoken command must start with the wake word. */
    var requireWakeWord: Boolean
        get() = sp.getBoolean(KEY_REQUIRE_WAKE, true)
        set(value) = sp.edit().putBoolean(KEY_REQUIRE_WAKE, value).apply()

    var language: String
        get() = sp.getString(KEY_LANG, "ru-RU").orEmpty().ifBlank { "ru-RU" }
        set(value) = sp.edit().putString(KEY_LANG, value.trim()).apply()

    fun wakeWords(): List<String> =
        (listOf(wakeWord) + Phrases.DEFAULT_WAKE_WORDS).filter { it.isNotBlank() }.distinct()

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_BASE_URL = "base_url"
        const val KEY_WAKE = "wake_word"
        const val KEY_REQUIRE_WAKE = "require_wake"
        const val KEY_LANG = "language"
    }
}
