package com.jarvis.agent.core

/** Wake word, yes/no, cancel and "who + what" parsing. Russian and English. */
object Phrases {

    val DEFAULT_WAKE_WORDS = listOf("jarvis", "джарвис", "джервис", "жарвис", "джарвес")

    private val CANCEL = setOf(
        "отмена", "отмени", "отменить", "стоп", "хватит", "прекрати", "стой",
        "cancel", "stop", "abort", "nevermind"
    )
    /** Deliberately short: "давай", "ок" and "конечно" are conversation, not consent. */
    private val YES = setOf(
        "да", "ага", "подтверждаю", "подтверди", "отправляй", "отправь",
        "yes", "yeah", "yep", "confirm"
    )
    private val NO = setOf("нет", "неа", "не", "no", "nope", "отмена", "cancel")
    private val BACK = setOf("назад", "вернись", "обратно", "back")
    private val HOME = setOf("домой", "главный экран", "рабочий стол", "home")
    private val RECENTS = setOf("недавние", "последние приложения", "recents", "recent apps")

    /** Words that may accompany a bare navigation command without changing its meaning. */
    private val FILLERS = setOf(
        "пожалуйста", "пожалуйсто", "давай", "ка", "джарвис", "jarvis", "please", "now", "на"
    )

    /**
     * Token prefixes for verbs that change someone else's world. Matched against whole
     * words, because a substring test turns "recall" into a phone call and "postpone" into
     * publishing.
     */
    private val IRREVERSIBLE = listOf(
        "отправ", "напиш", "напис", "удал", "куп", "оплат", "заплат", "позвон", "перевед",
        "скинь", "закаж", "подтверд", "подпиш", "отпиш", "блокир", "перешл", "ответь",
        "send", "delete", "remove", "buy", "pay", "call", "purchase", "publish",
        "transfer", "order", "reply", "forward", "share"
    )

    /** Words that only look irreversible. */
    private val NOT_IRREVERSIBLE = setOf(
        "recall", "calligraphy", "postpone", "posted", "poster", "calls", "called",
        "callback", "отправленные", "отправленное", "покупки", "оплаченные"
    )

    fun stripWakeWord(command: String, wakeWords: List<String> = DEFAULT_WAKE_WORDS): String {
        var text = command.trim()
        for (w in wakeWords) {
            if (w.isBlank()) continue
            val idx = text.lowercase().indexOf(w.lowercase())
            if (idx < 0) continue
            val before = text.take(idx)
            val after = text.drop(idx + w.length)
            // Only strip a standalone word, not a syllable inside another one.
            val boundedLeft = before.isEmpty() || !before.last().isLetter()
            val boundedRight = after.isEmpty() || !after.first().isLetter()
            if (boundedLeft && boundedRight) {
                text = (before + " " + after).trim().trim(' ', ',', '.', '!', '?', '-', ':', ';')
                return text
            }
        }
        return text
    }

    fun containsWakeWord(command: String, wakeWords: List<String> = DEFAULT_WAKE_WORDS): Boolean {
        val words = Text.tokens(command)
        return wakeWords.any { w ->
            val wn = Text.normalize(w)
            wn.isNotEmpty() && words.any { it == wn }
        }
    }

    fun isCancel(command: String): Boolean = matches(command, CANCEL)

    fun isYes(command: String): Boolean = matches(command, YES)

    fun isNo(command: String): Boolean = matches(command, NO)

    fun isBack(command: String): Boolean = matches(command, BACK)

    fun isHome(command: String): Boolean = matches(command, HOME)

    fun isRecents(command: String): Boolean = matches(command, RECENTS)

    /**
     * Answers a pending yes/no question. Returns null when the utterance is not an answer,
     * or when it is both — "да нет" is Russian for "no", so an ambiguous reply is no answer
     * at all rather than a send.
     */
    fun readAnswer(command: String): Boolean? {
        val yes = isYes(command)
        val no = isNo(command)
        return when {
            no && !yes -> false
            yes && !no -> true
            else -> null
        }
    }

    fun needsConfirmation(command: String): Boolean {
        val tokens = Text.tokens(command)
        return tokens.any { token ->
            if (token in NOT_IRREVERSIBLE) return@any false
            IRREVERSIBLE.any { token.startsWith(it) }
        }
    }

    /**
     * True only for a bare navigation phrase. "назад" is Back; "прокрути назад" is a scroll
     * and must not press the system Back button.
     */
    private fun matches(command: String, set: Set<String>): Boolean {
        val n = Text.normalize(command)
        if (n.isEmpty()) return false
        if (n in set) return true
        val words = n.split(' ')
        if (words.size > 3) return false
        return words.any { it in set } && words.all { it in set || it in FILLERS }
    }

    /**
     * Splits "Мухаммадюсуфу: я опоздаю" into recipient and message.
     * Falls back to "the first word is the recipient" when there is no separator.
     */
    fun splitRecipientAndMessage(raw: String): Pair<String, String> {
        val text = raw.trim().trimEnd('.', '!', '?')

        // A colon only separates when it is not part of a time ("в 17:30").
        val colon = text.indexOf(':')
        if (colon > 0) {
            val head = text.substring(0, colon).trim()
            val tail = text.substring(colon + 1).trim()
            val timeLike = tail.take(2).all { it.isDigit() }
            if (!timeLike && head.isNotEmpty() && tail.isNotEmpty() &&
                head.split(' ').size <= 3
            ) {
                return cleanRecipient(head) to tail
            }
        }

        // "<кому> что <сообщение>" — but only when "что" comes early enough to be the
        // separator rather than a word inside the message.
        val whatIndex = text.indexOf(" что ")
        if (whatIndex > 0) {
            val head = text.substring(0, whatIndex).trim()
            val tail = text.substring(whatIndex + 5).trim()
            if (head.isNotEmpty() && tail.isNotEmpty() && head.split(' ').size <= 2) {
                return cleanRecipient(head) to tail
            }
        }

        val comma = text.indexOf(',')
        if (comma > 0) {
            val head = text.substring(0, comma).trim()
            val tail = text.substring(comma + 1).trim().removePrefix("что").trim()
            if (head.isNotEmpty() && tail.isNotEmpty() && head.split(' ').size <= 2) {
                return cleanRecipient(head) to tail
            }
        }

        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size >= 2) {
            return cleanRecipient(words.first()) to words.drop(1).joinToString(" ")
        }
        return "" to text
    }

    /** Drops a leading preposition and any punctuation the recogniser left attached. */
    private fun cleanRecipient(raw: String): String {
        val cleaned = raw.trim().trim('«', '»', '"', '\'', ',', '.', ';', ':', '!', '?')
        val words = cleaned.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        val prepositions = setOf("для", "к", "кому", "to", "for")
        val kept = if (words.size > 1 && Text.normalize(words.first()) in prepositions) {
            words.drop(1)
        } else {
            words
        }
        return kept.joinToString(" ").trim(',', '.', ';', ':')
    }

    /**
     * What to type into a contact search.
     *
     * Russian names are spoken in the dative ("Ане", "Ольге", "Мухаммадюсуфу") while the
     * contact list holds the nominative, and contact search is prefix-based — so the ending
     * has to come off or the search returns nothing.
     */
    private val CASE_TAILS = listOf("ому", "ему", "ой", "ей", "ом", "ем", "ую", "ю", "у", "е", "и", "ы", "а", "я")

    fun searchStem(recipient: String): String {
        val n = recipient.trim()
        if (n.length <= 4) return n
        val lower = n.lowercase()
        val tail = CASE_TAILS.firstOrNull { lower.endsWith(it) && n.length - it.length >= 3 }
        return if (tail != null) n.dropLast(tail.length) else n
    }
}
