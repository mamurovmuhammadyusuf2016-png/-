package com.jarvis.agent.core

/**
 * Offline planner. No network, no API key — it covers the everyday commands so the agent is
 * useful the second it is installed, and it is the safety net when the model is unreachable.
 *
 * It also decides what *not* to answer. A plan marked `confident` is executed immediately
 * without asking the model, so false confidence is worse than no answer: "прокрути назад"
 * must not press the system Back button, and "скажи который час" must not type "который час"
 * into whatever field happens to be focused.
 */
class RuleBasedPlanner : Planner {

    override val name: String = "rules"

    // Stems, not fixed forms: people say "напишу", "напишешь", "отправлю", "передам", and a
    // recogniser will hand over whichever ending it heard.
    private val openVerbs =
        "откро[а-яё]*|открыть|запуст[а-яё]*|включ[а-яё]*|покаж[а-яё]*|зайди в|зайди|перейди в|open|launch|start|go to|show"
    private val writeVerbs =
        "напиш[а-яё]*|напис[а-яё]*|отправ[а-яё]*|перед[аеёи][а-яё]*|скинь|send|write|text|message"
    private val typeVerbs = "напечата[а-яё]*|введи|впиши|type|input|enter text"

    /** Apps where "напиши X: текст" means sending a message to a person. */
    private val messengers = setOf("telegram", "whatsapp", "messages", "vk", "instagram")

    private val reScroll = Regex(
        "(?iu)^\\s*(?:прокрути(?:ть)?|пролистай|полистай|листай|проскролл[ьия]?|скролл|scroll|swipe)(?![\\p{L}])\\s*(.*)$",
        RegexOption.IGNORE_CASE
    )
    private val reOpenAndWrite = Regex(
        "(?iu)^\\s*(?:$openVerbs)\\s+(.+?)\\s+(?:и|and|,)?\\s*(?:$writeVerbs)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val reWrite = Regex(
        "(?iu)^\\s*(?:$writeVerbs)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val reOpen = Regex("(?iu)^\\s*(?:$openVerbs)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reType = Regex("(?iu)^\\s*(?:$typeVerbs)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reFind = Regex("(?iu)^\\s*(?:найди|найти|поищи|find|locate)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reTap = Regex(
        "(?iu)^\\s*(?:нажми|нажать|кликни|тапни|выбери|tap|click|press|select)\\s+(?:на\\s+|on\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    override fun plan(request: PlanRequest): Plan {
        val raw = Phrases.stripWakeWord(request.command).trim().trimEnd('.', '!', '?')
        val normalized = Text.normalize(raw)
        if (normalized.isEmpty()) return Plan(emptyList(), null, name)

        // Scrolling first: "прокрути назад" is a scroll, not the Back button.
        reScroll.find(raw)?.let { m ->
            val direction = ScrollDirection.parse(m.groupValues[1])
            return Plan(
                listOf(Action.Scroll(direction), Action.Done("Готово")),
                null,
                name,
                confident = true
            )
        }

        if (Phrases.isCancel(normalized)) {
            return Plan(listOf(Action.Done("Отменено")), "Отменено", name, confident = true)
        }
        if (Phrases.isBack(normalized)) {
            return Plan(listOf(Action.Back, Action.Done("Готово")), null, name, confident = true)
        }
        if (Phrases.isHome(normalized)) {
            return Plan(listOf(Action.Home, Action.Done("Готово")), null, name, confident = true)
        }
        if (Phrases.isRecents(normalized)) {
            return Plan(listOf(Action.Recents, Action.Done("Готово")), null, name, confident = true)
        }

        reOpenAndWrite.find(raw)?.let { m ->
            val app = cleanTarget(m.groupValues[1])
            val canonical = AppMatcher.canonicalName(app)
            if (canonical in messengers) {
                return messagePlan(app, m.groupValues[2])
            }
            // "открой галерею и отправь фото маме" is not a chat — let the model plan it.
            return Plan(
                listOf(Action.OpenApp(app), Action.Done("Открываю $app")),
                null,
                name,
                confident = false
            )
        }

        reWrite.find(raw)?.let { m ->
            // No app named: only meaningful if a messenger is already in front.
            val current = request.screen.packageName
            val inMessenger = current != null &&
                messengers.any { canon -> AppMatcher.fallbackPackages(canon).contains(current) }
            return if (inMessenger) {
                messagePlan(null, m.groupValues[1])
            } else {
                Plan(
                    listOf(Action.Fail("Скажите, в каком приложении написать")),
                    null,
                    name,
                    confident = false
                )
            }
        }

        reFind.find(raw)?.let { m ->
            val target = cleanTarget(m.groupValues[1])
            val visible = ScreenMatcher.find(request.screen, target) != null
            return Plan(listOf(Action.Find(target)), null, name, confident = visible)
        }

        reTap.find(raw)?.let { m ->
            val target = cleanTarget(m.groupValues[1])
            val visible = ScreenMatcher.find(request.screen, target) != null
            return Plan(
                listOf(Action.Tap(target), Action.Done("Готово")),
                null,
                name,
                confident = visible
            )
        }

        reOpen.find(raw)?.let { m ->
            val spoken = cleanTarget(m.groupValues[1])
            val (app, score, rest) = longestAppPrefix(spoken, request.installedApps)

            if (app == null || score < AppMatcher.MIN_SCORE) {
                return Plan(
                    listOf(Action.OpenApp(spoken), Action.Done("Открываю $spoken")),
                    "Открываю $spoken",
                    name,
                    confident = false
                )
            }

            // "открой телеграм напишу Ивану ..." — the tail is a second command, not part of
            // the app's name. Without this the whole sentence became the app name and the
            // agent answered "нет такого приложения".
            if (rest.isNotEmpty()) {
                val tail = reWrite.find(rest)
                if (tail != null && AppMatcher.canonicalName(app.label) in messengers) {
                    return messagePlan(app.label, tail.groupValues[1])
                }
                return Plan(
                    listOf(Action.OpenApp(app.label), Action.Done("Открываю ${app.label}")),
                    "Открываю ${app.label}",
                    name,
                    confident = false
                )
            }

            return Plan(
                listOf(Action.OpenApp(app.label), Action.Done("Открываю ${app.label}")),
                "Открываю ${app.label}",
                name,
                confident = score >= AppMatcher.CONFIDENT_SCORE
            )
        }

        reType.find(raw)?.let { m ->
            val text = m.groupValues[1].trim()
            // Only sure when there is an obvious place for the text to go.
            val focusedField = request.screen.nodes.any { it.editable && it.focused }
            return Plan(
                listOf(Action.TypeText(text), Action.Done("Напечатано")),
                null,
                name,
                confident = focusedField
            )
        }

        // Last resort: a bare app name ("телеграм") means "open it" — but only when the
        // match is unambiguous, otherwise "почта" launches the wrong mail app.
        val bestApp = AppMatcher.rank(normalized, request.installedApps).firstOrNull()
        if (normalized.split(' ').size <= 2 && (bestApp?.second ?: 0) >= 94) {
            val app = bestApp!!.first.label
            return Plan(
                listOf(Action.OpenApp(app), Action.Done("Открываю $app")),
                null,
                name,
                confident = true
            )
        }

        return Plan(listOf(Action.Fail("Не понял команду: $raw")), null, name)
    }

    /**
     * "Мухаммадюсуфу: я опоздаю" -> find the chat, type the text, confirm, send.
     *
     * The search box gets the *stem* of the name, because contact search matches prefixes
     * and the name is spoken in the dative ("Ане" will never find "Аня").
     */
    private fun messagePlan(app: String?, rest: String): Plan {
        val (recipient, message) = Phrases.splitRecipientAndMessage(rest)
        if (recipient.isBlank() || message.isBlank()) {
            return Plan(
                listOfNotNull(
                    app?.let { Action.OpenApp(it) },
                    Action.Fail("Не понял, кому и что написать")
                ),
                null,
                name
            )
        }
        val query = Phrases.searchStem(recipient)
        val actions = ArrayList<Action>()
        if (app != null) {
            actions.add(Action.Speak("Открываю $app"))
            actions.add(Action.OpenApp(app))
        }
        actions.add(Action.Tap("поиск|search|найти"))
        actions.add(Action.TypeText(query))
        actions.add(Action.Tap(query))
        actions.add(Action.TypeText(message, "сообщение|message|написать сообщение|write a message"))
        actions.add(Action.Confirm("Отправить $recipient: $message?"))
        actions.add(Action.Tap("отправить|send"))
        actions.add(Action.Done("Сообщение отправлено"))
        return Plan(actions, null, name, confident = false)
    }

    /**
     * Splits "Telegram напишу Ивану привет" into the app and the leftover, by trying
     * progressively longer prefixes and keeping the best-scoring one.
     */
    private fun longestAppPrefix(
        spoken: String,
        apps: List<AppEntry>
    ): Triple<AppEntry?, Int, String> {
        val words = spoken.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return Triple(null, 0, "")
        var best: AppEntry? = null
        var bestScore = 0
        var used = 0
        for (n in 1..minOf(words.size, 4)) {
            val candidate = words.take(n).joinToString(" ")
            val hit = AppMatcher.rank(candidate, apps).firstOrNull() ?: continue
            if (hit.second > bestScore) {
                bestScore = hit.second
                best = hit.first
                used = n
            }
        }
        return Triple(best, bestScore, words.drop(used).joinToString(" "))
    }

    private fun cleanTarget(raw: String): String =
        raw.trim().trim('«', '»', '"', '\'', '.', ',', '!', '?').trim()
}
