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
