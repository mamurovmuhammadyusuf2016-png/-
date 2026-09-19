package com.jarvis.agent.core

data class AppEntry(val label: String, val packageName: String)

/**
 * Maps whatever the user said ("телеграм", "chat gpt", "настройки") to an installed app.
 *
 * Nothing here is app-specific magic: aliases only help common cases, and the generic
 * label/package/fuzzy matching is what makes arbitrary apps work.
 */
object AppMatcher {

    /** Extra spoken names for apps whose launcher label does not match what people say. */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "telegram" to listOf("телеграм", "телега", "тг", "tg", "telega"),
        "instagram" to listOf("инстаграм", "инста", "инстаграмм", "insta", "ig"),
        "chrome" to listOf("хром", "гугл хром", "браузер", "browser", "google chrome"),
        "chatgpt" to listOf("чатгпт", "чат гпт", "гпт", "gpt", "chat gpt", "openai", "опенай"),
        "settings" to listOf("настройки", "настройка", "параметры", "система"),
        "messages" to listOf("сообщения", "смс", "sms", "мессенджер сообщения", "messaging"),
        "whatsapp" to listOf("ватсап", "вотсап", "вацап"),
        "youtube" to listOf("ютуб", "ютьюб"),
        "phone" to listOf("телефон", "звонки", "dialer", "набор номера"),
        "camera" to listOf("камера", "фотоаппарат"),
        "gallery" to listOf("галерея", "фото", "photos", "снимки"),
        "calculator" to listOf("калькулятор"),
        "clock" to listOf("часы", "будильник", "alarm", "таймер"),
        "maps" to listOf("карты", "гугл карты", "навигатор"),
        "gmail" to listOf("почта", "джимейл", "mail"),
        "calendar" to listOf("календарь"),
        "play store" to listOf("плей маркет", "гугл плей", "play market", "playstore", "маркет"),
        "spotify" to listOf("спотифай"),
        "vk" to listOf("вконтакте", "вк"),
        "tiktok" to listOf("тикток", "тик ток")
    )

    /** Fallback package names for when the label lookup finds nothing. */
    private val PACKAGE_HINTS: Map<String, List<String>> = mapOf(
        "telegram" to listOf("org.telegram.messenger", "org.telegram.plus", "nekox.messenger"),
        "instagram" to listOf("com.instagram.android"),
        "chrome" to listOf("com.android.chrome"),
        "chatgpt" to listOf("com.openai.chatgpt"),
        "settings" to listOf("com.android.settings"),
        "messages" to listOf("com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging"),
        "whatsapp" to listOf("com.whatsapp"),
        "youtube" to listOf("com.google.android.youtube"),
        "phone" to listOf("com.google.android.dialer", "com.android.dialer"),
        "camera" to listOf("com.android.camera", "com.google.android.GoogleCamera"),
        "maps" to listOf("com.google.android.apps.maps"),
        "gmail" to listOf("com.google.android.gm"),
        "calendar" to listOf("com.google.android.calendar"),
        "play store" to listOf("com.android.vending"),
        "spotify" to listOf("com.spotify.music"),
        "vk" to listOf("com.vkontakte.android"),
        "tiktok" to listOf("com.zhiliaoapp.musically")
    )

    /** Noise words that appear in spoken commands but never in app labels. */
    private val STOP_WORDS = setOf(
        "приложение", "приложения", "прогу", "программу", "app", "the", "please",
        "пожалуйста", "пожалуйсто"
    )

    fun canonicalName(query: String): String? {
        val n = Text.normalize(query)
        if (n.isEmpty()) return null
        for ((canonical, names) in ALIASES) {
            if (n == canonical) return canonical
            if (names.any { Text.normalize(it) == n }) return canonical
        }
        val t = Text.translit(n)
        for ((canonical, names) in ALIASES) {
            if (t == Text.translit(canonical)) return canonical
            if (names.any { Text.translit(Text.normalize(it)) == t }) return canonical
        }
        return null
    }

    fun cleanQuery(query: String): String {
        val kept = Text.tokens(query).filter { it !in STOP_WORDS }
        return if (kept.isEmpty()) Text.normalize(query) else kept.joinToString(" ")
    }

    fun resolve(query: String, apps: List<AppEntry>): AppEntry? = rank(query, apps).firstOrNull()?.first

    /** Ranked candidates, best first. Exposed so the UI/log can show why a choice was made. */
    fun rank(query: String, apps: List<AppEntry>): List<Pair<AppEntry, Int>> {
        val q = cleanQuery(query)
        if (q.isEmpty()) return emptyList()
        val canonical = canonicalName(q)
        val qTranslit = Text.translit(q)
        val hintPackages = canonical?.let { PACKAGE_HINTS[it] }.orEmpty()

        val scored = ArrayList<Pair<AppEntry, Int>>()
        for (app in apps) {
            val label = Text.normalize(app.label)
            val labelTranslit = Text.translit(label)
            val pkg = app.packageName.lowercase()
            var score = 0

            if (label == q || labelTranslit == qTranslit) score = maxOf(score, 100)
            if (canonical != null && (label == canonical || labelTranslit == Text.translit(canonical))) {
                score = maxOf(score, 96)
            }
            if (hintPackages.any { it.equals(pkg, ignoreCase = true) }) score = maxOf(score, 94)
            if (label.startsWith(q) || labelTranslit.startsWith(qTranslit)) score = maxOf(score, 88)
            if (q.length >= 3 && (label.contains(q) || labelTranslit.contains(qTranslit))) {
                score = maxOf(score, 80)
            }
            if (canonical != null && q.length >= 3) {
                val c = Text.translit(canonical)
                if (labelTranslit.contains(c)) score = maxOf(score, 78)
                if (pkg.contains(c.replace(" ", ""))) score = maxOf(score, 74)
            }
            if (q.length >= 4 && pkg.contains(qTranslit.replace(" ", ""))) score = maxOf(score, 70)

            val qWords = q.split(' ').toSet()
            val labelWords = label.split(' ').toSet()
            val overlap = qWords.intersect(labelWords).size
            if (overlap > 0) score = maxOf(score, 55 + overlap * 5)

            val sim = Text.similarity(qTranslit, labelTranslit)
            if (sim >= 0.75) score = maxOf(score, (sim * 70).toInt())

            if (score > 0) scored.add(app to score)
        }

        // Prefer the shortest label among equal scores: "Chrome" beats "Chrome Beta".
        return scored.sortedWith(
            compareByDescending<Pair<AppEntry, Int>> { it.second }
                .thenBy { it.first.label.length }
                .thenBy { it.first.label }
        )
    }

    /** Package names worth trying when the app is not in the launcher list at all. */
    fun fallbackPackages(query: String): List<String> {
        val canonical = canonicalName(query) ?: Text.normalize(query)
        return PACKAGE_HINTS[canonical].orEmpty()
    }

    fun isSettingsQuery(query: String): Boolean = canonicalName(query) == "settings"
}
