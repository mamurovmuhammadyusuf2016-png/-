package com.jarvis.agent.core

/** Wake word, yes/no, cancel and "who + what" parsing. Russian and English. */
object Phrases {

    val DEFAULT_WAKE_WORDS = listOf("jarvis", "джарвис", "джервис", "жарвис", "джарвес")

    private val CANCEL = setOf(
        "отмена", "отмени", "отменить", "стоп", "хватит", "не надо", "не нужно",
        "прекрати", "стой", "cancel", "stop", "abort", "nevermind", "never mind", "forget it"
    )
    private val YES = setOf(
        "да", "ага", "давай", "подтверждаю", "подтверди", "отправляй", "конечно", "ок", "окей",
        "yes", "yeah", "yep", "ok", "okay", "confirm", "do it", "send it", "go ahead"
    )
    private val NO = setOf(
        "нет", "не", "неа", "не надо", "отмена", "no", "nope", "don t", "dont", "cancel"
    )
    private val BACK = setOf("назад", "вернись", "обратно", "back", "go back", "previous")
    private val HOME = setOf(
        "домой", "на главный экран", "главный экран", "рабочий стол", "home", "home screen", "go home"
    )
    private val RECENTS = setOf(
        "недавние", "последние приложения", "недавние приложения", "recents", "recent apps"
    )

    /** Verbs that change someone else's world — these always ask before acting. */
    private val IRREVERSIBLE = listOf(
        "отправ", "напиши", "написать", "удали", "удалить", "купи", "оплат", "позвони",
        "переведи деньги", "send", "delete", "remove", "buy", "pay", "call ", "purchase",
        "post ", "publish"
    )

    fun stripWakeWord(command: String, wakeWords: List<String> = DEFAULT_WAKE_WORDS): String {
        var text = command.trim()
        for (w in wakeWords) {
            val n = Text.normalize(text)
            val wn = Text.normalize(w)
            if (wn.isEmpty()) continue
            if (n == wn) return ""
            if (n.startsWith("$wn ")) {
                // Cut the same number of leading characters from the original string.
                val idx = text.lowercase().indexOf(w.lowercase())
                if (idx >= 0) {
                    text = text.substring(idx + w.length)
                    return text.trimStart(' ', ',', '.', '!', '?', '-', ':', ';')
                }
            }
        }
        return text
    }

    fun containsWakeWord(command: String, wakeWords: List<String> = DEFAULT_WAKE_WORDS): Boolean {
        val n = Text.normalize(command)
        return wakeWords.any { n.contains(Text.normalize(it)) }
    }

    fun isCancel(command: String): Boolean = matches(command, CANCEL)

    fun isYes(command: String): Boolean = matches(command, YES)

    fun isNo(command: String): Boolean = matches(command, NO) || isCancel(command)

    fun isBack(command: String): Boolean = matches(command, BACK)

    fun isHome(command: String): Boolean = matches(command, HOME)

    fun isRecents(command: String): Boolean = matches(command, RECENTS)

    fun needsConfirmation(command: String): Boolean {
        val n = Text.normalize(command)
        return IRREVERSIBLE.any { n.contains(Text.normalize(it)) }
    }

    private fun matches(command: String, set: Set<String>): Boolean {
        val n = Text.normalize(command)
        if (n.isEmpty()) return false
        if (n in set) return true
        // Allow "отмена пожалуйста" / "yes please".
        val words = n.split(' ')
        if (words.size <= 3 && words.any { it in set }) return true
        return false
    }

    /**
     * Splits "Мухаммадюсуфу: я опоздаю на пять минут" into recipient and message.
     * Falls back to "first word is the recipient" when there is no separator.
     */
    fun splitRecipientAndMessage(raw: String): Pair<String, String> {
        val text = raw.trim().trimEnd('.', '!', '?')
        val separators = listOf(":", " что ", " — ", " - ", ",")
        for (sep in separators) {
            val idx = text.indexOf(sep)
            if (idx > 0) {
                val recipient = text.substring(0, idx).trim()
                val message = text.substring(idx + sep.length).trim()
                if (recipient.isNotEmpty() && message.isNotEmpty()) {
                    return normalizeRecipient(recipient) to message
                }
            }
        }
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size >= 2) {
            return normalizeRecipient(words.first()) to words.drop(1).joinToString(" ")
        }
        return "" to text
    }

    /** Drops the leading preposition: "для Ивана" / "to John" -> "Ивана" / "John". */
    private fun normalizeRecipient(raw: String): String {
        val words = raw.trim().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        val first = Text.normalize(words.first())
        val prepositions = setOf("для", "к", "кому", "to", "for")
        return if (words.size > 1 && first in prepositions) {
            words.drop(1).joinToString(" ")
        } else {
            words.joinToString(" ")
        }
    }
}
