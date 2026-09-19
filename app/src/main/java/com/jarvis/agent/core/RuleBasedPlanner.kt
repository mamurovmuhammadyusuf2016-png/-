package com.jarvis.agent.core

/**
 * Offline planner. No network, no API key — it covers the everyday commands so the agent
 * is useful the second it is installed, and it is the safety net when the model is
 * unreachable.
 */
class RuleBasedPlanner : Planner {

    override val name: String = "rules"

    private val openVerbs = "открой|открыть|откройте|запусти|запустить|включи|включить|покажи|зайди в|зайди|перейди в|open|launch|start|go to|show"
    private val writeVerbs = "напиши|написать|напишите|отправь|отправить|передай|скажи|send|write|text|message"
    private val typeVerbs = "напечатай|введи|набери|впиши|type|input|enter text"

    private val reOpenAndWrite = Regex(
        "(?iu)^\\s*(?:$openVerbs)\\s+(.+?)\\s+(?:и|and|,)?\\s*(?:$writeVerbs)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val reWriteInApp = Regex(
        "(?iu)^\\s*(?:$writeVerbs)\\s+(?:в|in)\\s+(\\S+)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val reOpen = Regex("(?iu)^\\s*(?:$openVerbs)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reType = Regex("(?iu)^\\s*(?:$typeVerbs|$writeVerbs)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reFind = Regex("(?iu)^\\s*(?:найди|найти|поищи|где|find|locate)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reTap = Regex(
        "(?iu)^\\s*(?:нажми|нажать|кликни|тапни|выбери|tap|click|press|select)\\s+(?:на\\s+|on\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val reScroll = Regex(
        "(?iu)^\\s*(?:прокрути|крути|пролистай|листай|скролл|проскролль|scroll|swipe)\\s*(.*)$",
        RegexOption.IGNORE_CASE
    )

    override fun plan(request: PlanRequest): Plan {
        val raw = Phrases.stripWakeWord(request.command).trim().trimEnd('.', '!', '?')
        val normalized = Text.normalize(raw)
        if (normalized.isEmpty()) return Plan(emptyList(), null, name)

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

        reScroll.find(raw)?.let { m ->
            val direction = ScrollDirection.parse(m.groupValues[1])
            return Plan(
                listOf(Action.Scroll(direction), Action.Done("Готово")),
                null,
                name,
                confident = true
            )
        }

        reOpenAndWrite.find(raw)?.let { m ->
            val app = m.groupValues[1]
            val rest = m.groupValues[2]
            return messagePlan(app, rest)
        }

        reWriteInApp.find(raw)?.let { m ->
            return messagePlan(m.groupValues[1], m.groupValues[2])
        }

        reFind.find(raw)?.let { m ->
            return Plan(
                listOf(Action.Find(cleanTarget(m.groupValues[1]))),
                null,
                name,
                confident = true
            )
        }

        reTap.find(raw)?.let { m ->
            val target = cleanTarget(m.groupValues[1])
            return Plan(
                listOf(Action.Tap(target), Action.Done("Готово")),
                null,
                name,
                confident = true
            )
        }

        reOpen.find(raw)?.let { m ->
            val app = cleanTarget(m.groupValues[1])
            // Only shortcut the model when we can actually see that app on the phone.
            val known = AppMatcher.resolve(app, request.installedApps) != null
            return Plan(
                listOf(Action.OpenApp(app), Action.Done("Открываю $app")),
                "Открываю $app",
                name,
                confident = known
            )
        }

        reType.find(raw)?.let { m ->
            val text = m.groupValues[1].trim()
            return Plan(
                listOf(Action.TypeText(text), Action.Done("Напечатано")),
                null,
                name,
                confident = true
            )
        }

        // Last resort: treat a bare app-like phrase ("телеграм") as "open it".
        val bestApp = AppMatcher.rank(normalized, request.installedApps).firstOrNull()
        if (normalized.split(' ').size <= 3 && (bestApp?.second ?: 0) >= 80) {
            return Plan(
                listOf(Action.OpenApp(normalized), Action.Done("Открываю")),
                null,
                name,
                confident = true
            )
        }

        return Plan(
            listOf(Action.Fail("Не понял команду: $raw")),
            null,
            name
        )
    }

    /** "Мухаммадюсуфу: я опоздаю" -> find the chat, type the text, confirm, send. */
    private fun messagePlan(app: String, rest: String): Plan {
        val (recipient, message) = Phrases.splitRecipientAndMessage(rest)
        val appName = cleanTarget(app)
        if (recipient.isBlank() || message.isBlank()) {
            return Plan(
                listOf(
                    Action.OpenApp(appName),
                    Action.Fail("Не понял, кому и что написать")
                ),
                null,
                name
            )
        }
        return Plan(
            listOf(
                Action.Speak("Открываю $appName"),
                Action.OpenApp(appName),
                Action.Wait(400),
                Action.Tap("поиск|search|найти"),
                Action.TypeText(recipient),
                Action.Wait(400),
                Action.Tap(recipient),
                Action.Wait(400),
                Action.TypeText(message, "сообщение|message|написать сообщение|write a message"),
                Action.Confirm("Отправить $recipient сообщение: $message?"),
                Action.Tap("отправить|send"),
                Action.Done("Сообщение отправлено")
            ),
            null,
            name
        )
    }

    private fun cleanTarget(raw: String): String =
        raw.trim().trim('«', '»', '"', '\'', '.', ',', '!', '?').trim()
}
