package com.jarvis.agent.core

enum class AgentStatus { SUCCESS, CANCELLED, FAILED }

data class AgentResult(
    val status: AgentStatus,
    val message: String,
    val steps: List<String> = emptyList(),
    val spoken: List<String> = emptyList(),
    val replans: Int = 0
) {
    val success: Boolean get() = status == AgentStatus.SUCCESS
}

/**
 * Runs one spoken command end to end: plan -> act -> observe -> recover.
 *
 * Recovery is the part that matters in practice. When a target is not on screen we scroll
 * and look again; if it is still missing we ask the planner for a new plan with the current
 * screen attached; only after that do we give up and say so out loud.
 */
class AgentLoop(
    private val device: DeviceController,
    private val planner: Planner,
    private val voice: VoiceOutput,
    private val confirmation: ConfirmationGate = AlwaysApprove,
    private val log: (String) -> Unit = {},
    private val settleMillis: Long = 700L,
    private val maxReplans: Int = 2,
    private val maxScrollSearch: Int = 3,
    private val minMatchScore: Int = 60
) {

    private sealed class Outcome {
        object Continue : Outcome()
        /** The step could not be performed — ask the planner for a new plan. */
        data class Replan(val note: String) : Outcome()
        data class Stop(val status: AgentStatus, val message: String) : Outcome()
    }

    private val steps = ArrayList<String>()
    private val spoken = ArrayList<String>()

    fun run(command: String): AgentResult {
        steps.clear()
        spoken.clear()

        val cleaned = Phrases.stripWakeWord(command).trim()
        if (cleaned.isEmpty()) {
            return finish(AgentStatus.FAILED, "Пустая команда", 0)
        }
        if (Phrases.isCancel(cleaned)) {
            say("Отменяю")
            return finish(AgentStatus.CANCELLED, "Отменено пользователем", 0)
        }

        var replans = 0
        val plan = ConfirmationPolicy.ensureConfirmation(requestPlan(cleaned, null), cleaned)
        if (plan.isEmpty) {
            say("Не понял команду")
            return finish(AgentStatus.FAILED, "Планировщик вернул пустой план", replans)
        }
        plan.say?.let { say(it) }

        var actions = plan.actions
        var index = 0
        var guard = 0

        while (index < actions.size) {
            if (guard++ > 200) {
                say("Слишком много шагов, останавливаюсь")
                return finish(AgentStatus.FAILED, "Превышен лимит шагов", replans)
            }
            val action = actions[index]
            log("step: ${action.describe()}")
            when (val outcome = execute(action)) {
                is Outcome.Continue -> index++
                is Outcome.Stop -> return finish(outcome.status, outcome.message, replans)
                is Outcome.Replan -> {
                    if (replans >= maxReplans) {
                        val msg = "Не нашёл нужный элемент на экране. ${outcome.note}"
                        say("Не получилось. Не вижу нужный элемент на экране.")
                        return finish(AgentStatus.FAILED, msg, replans)
                    }
                    replans++
                    steps.add("replan: ${outcome.note}")
                    log("replan #$replans: ${outcome.note}")
                    val next = ConfirmationPolicy.ensureConfirmation(
                        requestPlan(cleaned, outcome.note),
                        cleaned
                    )
                    if (next.isEmpty) {
                        say("Не получилось выполнить команду")
                        return finish(AgentStatus.FAILED, outcome.note, replans)
                    }
                    next.say?.let { say(it) }
                    actions = next.actions
                    index = 0
                }
            }
        }

        // The plan ran out without an explicit done/fail — treat it as success.
        return finish(AgentStatus.SUCCESS, "Готово", replans)
    }

    private fun requestPlan(command: String, note: String?): Plan = planner.plan(
        PlanRequest(
            command = command,
            screen = device.screen(),
            installedApps = device.installedApps(),
            history = steps.toList(),
            note = note
        )
    )

    private fun execute(action: Action): Outcome = when (action) {
        is Action.OpenApp -> openApp(action.query)

        is Action.Tap -> {
            val node = locate(action.target)
            if (node == null) {
                Outcome.Replan("Элемент \"${action.target}\" не найден на экране")
            } else {
                val ok = device.tap(node)
                steps.add("tap ${node.label()}")
                device.sleep(settleMillis)
                if (ok) Outcome.Continue
                else Outcome.Replan("Не удалось нажать \"${action.target}\"")
            }
        }

        is Action.Find -> {
            val node = locate(action.target)
            steps.add("find ${action.target} -> ${node?.label() ?: "not found"}")
            if (node == null) {
                say("Не нашёл \"${action.target}\" на экране")
            } else {
                say("Нашёл: ${node.label()}")
            }
            Outcome.Continue
        }

        is Action.TypeText -> {
            val field = ScreenMatcher.findEditable(device.screen(), action.target)
            if (field == null) {
                Outcome.Replan("Нет поля ввода для текста \"${action.text}\"")
            } else {
                val ok = device.setText(field, action.text)
                steps.add("type \"${action.text}\" into ${field.label()}")
                device.sleep(settleMillis)
                if (ok) Outcome.Continue
                else Outcome.Replan("Не удалось ввести текст")
            }
        }

        is Action.Scroll -> {
            repeat(action.times) {
                device.scroll(action.direction)
                device.sleep(settleMillis / 2)
            }
            steps.add("scroll ${action.direction.name.lowercase()} x${action.times}")
            Outcome.Continue
        }

        Action.Back -> {
            device.back()
            steps.add("back")
            device.sleep(settleMillis)
            Outcome.Continue
        }

        Action.Home -> {
            device.home()
            steps.add("home")
            device.sleep(settleMillis)
            Outcome.Continue
        }

        Action.Recents -> {
            device.recents()
            steps.add("recents")
            device.sleep(settleMillis)
            Outcome.Continue
        }

        Action.PressEnter -> {
            device.pressEnter()
            steps.add("enter")
            device.sleep(settleMillis)
            Outcome.Continue
        }

        is Action.Wait -> {
            device.sleep(action.millis)
            steps.add("wait ${action.millis}ms")
            Outcome.Continue
        }

        is Action.Speak -> {
            say(action.text)
            Outcome.Continue
        }

        is Action.Confirm -> {
            steps.add("confirm?")
            say(action.question)
            if (confirmation.confirm(action.question)) {
                steps.add("confirmed")
                Outcome.Continue
            } else {
                say("Отменено")
                Outcome.Stop(AgentStatus.CANCELLED, "Пользователь отменил действие")
            }
        }

        is Action.Done -> {
            say(action.message)
            Outcome.Stop(AgentStatus.SUCCESS, action.message)
        }

        is Action.Fail -> {
            say(action.reason)
            Outcome.Stop(AgentStatus.FAILED, action.reason)
        }
    }

    private fun openApp(query: String): Outcome {
        val apps = device.installedApps()
        val match = AppMatcher.resolve(query, apps)
        if (match != null) {
            val launched = device.launchPackage(match.packageName)
            steps.add("open ${match.label} (${match.packageName})")
            device.sleep(settleMillis * 2)
            return if (launched) Outcome.Continue
            else Outcome.Stop(AgentStatus.FAILED, "Не удалось запустить ${match.label}")
        }

        for (pkg in AppMatcher.fallbackPackages(query)) {
            if (device.launchPackage(pkg)) {
                steps.add("open $pkg (fallback)")
                device.sleep(settleMillis * 2)
                return Outcome.Continue
            }
        }

        if (AppMatcher.isSettingsQuery(query) && device.openSystemSettings()) {
            steps.add("open system settings")
            device.sleep(settleMillis * 2)
            return Outcome.Continue
        }

        say("Не нашёл приложение $query")
        return Outcome.Stop(AgentStatus.FAILED, "Приложение \"$query\" не установлено")
    }

    /** Looks for [target]; scrolls down a few screens before admitting it is not there. */
    private fun locate(target: String): ScreenNode? {
        bestMatch(device.screen(), target)?.let { return it }
        for (attempt in 1..maxScrollSearch) {
            device.scroll(ScrollDirection.DOWN)
            device.sleep(settleMillis / 2)
            steps.add("scroll-search #$attempt for \"$target\"")
            bestMatch(device.screen(), target)?.let { return it }
        }
        return null
    }

    private fun bestMatch(snapshot: ScreenSnapshot, target: String): ScreenNode? {
        val best = ScreenMatcher.rank(snapshot, target).firstOrNull() ?: return null
        return if (best.second >= minMatchScore) best.first else null
    }

    private fun say(text: String) {
        if (text.isBlank()) return
        spoken.add(text)
        voice.say(text)
        log("say: $text")
    }

    private fun finish(status: AgentStatus, message: String, replans: Int) =
        AgentResult(status, message, steps.toList(), spoken.toList(), replans)
}
