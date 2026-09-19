package com.jarvis.agent.core

/** Text helpers shared by app matching and on-screen element matching. */
object Text {

    /** Lowercase, drop punctuation, normalise `ё`, collapse whitespace. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            val c = if (ch == 'ё') 'е' else ch
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == '+' -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    fun tokens(raw: String?): List<String> {
        val n = normalize(raw)
        return if (n.isEmpty()) emptyList() else n.split(' ')
    }

    /** 0.0 .. 1.0 similarity based on edit distance. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest.toDouble()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    /**
     * Icon-only buttons carry no text at all — a send arrow is literally "➤". Normalising
     * that gives an empty string and the element becomes unmatchable, which is how the agent
     * ended up tapping a message bubble instead of Send. Give the common glyphs words.
     */
    // Keyed by String, not Char: an emoji is a surrogate pair and cannot be a Char literal.
    private val GLYPHS: List<Pair<String, String>> = listOf(
        "\u27A4" to "send отправить", "\u27A2" to "send отправить", "\u25B6" to "send отправить",
        "\u2192" to "send отправить далее", "\u21E7" to "send отправить", "\u2191" to "send отправить",
        "\u2713" to "ok готово подтвердить", "\u2714" to "ok готово подтвердить",
        "\u2715" to "close закрыть", "\u2716" to "close закрыть", "\u00D7" to "close закрыть",
        "\u22EE" to "menu меню ещё", "\u22EF" to "menu меню ещё", "\u2261" to "menu меню",
        "\u2630" to "menu меню", "\u2190" to "back назад", "\u2039" to "back назад",
        "+" to "add добавить новый",
        "\uD83D\uDD0D" to "search поиск", "\uD83D\uDD0E" to "search поиск",
        "\u2699" to "settings настройки", "\uD83C\uDFE0" to "home домой"
    )

    /** Words for any glyphs in [raw], or an empty string when there are none. */
    fun glyphWords(raw: String?): String {
        if (raw.isNullOrEmpty() || raw.length > 4) return ""
        return GLYPHS.filter { raw.contains(it.first) }
            .joinToString(" ") { it.second }
    }

    /**
     * Drops one trailing Russian vowel so a dative recipient meets the nominative contact:
     * "Ане" and "Аня" both become "Ан", "Ольге" and "Ольга" both become "Ольг".
     */
    fun stem(raw: String): String {
        if (raw.length < 3) return raw
        val last = raw.last()
        return if (last in "аеиоуыэюяaeiouy") raw.dropLast(1) else raw
    }

    /** Very small Cyrillic -> Latin transliteration so "телеграм" can meet "telegram". */
    fun translit(raw: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l",
            'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
            'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e",
            'ю' to "yu", 'я' to "ya"
        )
        val sb = StringBuilder()
        for (c in raw.lowercase()) {
            val replaced = map[if (c == 'ё') 'е' else c]
            if (replaced != null) sb.append(replaced) else sb.append(c)
        }
        return sb.toString()
    }
}
