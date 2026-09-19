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
 * Two rules shape everything here. **A step that did not visibly work did not work** — the
 * loop never moves on because an API call returned true. And **the user always hears the
 * outcome** — every terminal state is spoken, because an agent that fails silently is
 * indistinguishable from one that is broken.
 */
class AgentLoop(
    private val device: DeviceController,
    private val planner: Planner,
    private val voice: VoiceOutput,
    private val confirmation: ConfirmationGate,
    private val log: (String) -> Unit = {},
    /** Fixed pause where waiting for a change makes no sense (scroll, back). */
    private val settleMillis: Long = 350L,
    /** How often to re-read the screen while waiting for it to react. */
    private val pollMillis: Long = 150L,
    /** Upper bound on waiting for a screen to react to a tap or keystroke. */
    private val maxSettleMillis: Long = 1600L,
    /** Upper bound on waiting for an app to come up. */
    private val maxLaunchMillis: Long = 4000L,
    private val maxReplans: Int = 2,
    private val maxScrollSearch: Int = 3,
    private val minMatchScore: Int = ScreenMatcher.MIN_SCORE
) {

    private sealed class Outcome {
        object Continue : Outcome()
        /** The step could not be performed — ask the planner for a new plan. */
        data class Replan(val note: String) : Outcome()
        data class Stop(val status: AgentStatus, val message: String) : Outcome()
    }

    private val steps = ArrayList<String>()
    private val spoken = ArrayList<String>()

    /**
     * The field we typed into last, and what went into it. After typing a contact name into
     * a search box, that box now *contains* the name and would out-match the real chat row.
     * The key catches it; the text catches it again when the keyboard moved the field and
     * changed its key.
     */
    private var lastTypedKey: String? = null
    private var lastTypedText: String? = null

    /** The screen the current plan was written against, for resolving `#12` style targets. */
    private var planningScreen: ScreenSnapshot = ScreenSnapshot()

    /** Set from another thread to stop a plan the user changed their mind about. */
    @Volatile
    var aborted: Boolean = false

    fun run(command: String): AgentResult {
        steps.clear()
        spoken.clear()
        lastTypedKey = null
        lastTypedText = null
        aborted = false

        val cleaned = Phrases.stripWakeWord(command).trim()
        if (Text.normalize(cleaned).isEmpty()) {
            say("Не расслышал команду")
            return finish(AgentStatus.FAILED, "Пустая команда", 0)
        }
        if (Phrases.isCancel(cleaned)) {
            say("Отменяю")
            return finish(AgentStatus.CANCELLED, "Отменено пользователем", 0)
        }

        var replans = 0
        var plan = ConfirmationPolicy.ensureConfirmation(requestPlan(cleaned, null), cleaned)
        if (plan.isEmpty) {
            say("Не понял команду")
            return finish(AgentStatus.FAILED, "Планировщик вернул пустой план", replans)
        }
        plan.say?.let { say(it) }

        var actions = plan.actions
        var index = 0
        var guard = 0

        while (index < actions.size) {
            if (aborted) {
                say("Остановился")
                return finish(AgentStatus.CANCELLED, "Прервано пользователем", replans)
            }
            if (guard++ > 120) {
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
                        say("Не получилось: ${outcome.note}")
                        return finish(AgentStatus.FAILED, outcome.note, replans)
                    }
                    replans++
                    steps.add("replan: ${outcome.note}")
                    log("replan #$replans: ${outcome.note}")
                    say("Пробую по-другому")

                    val nextPlan = ConfirmationPolicy.ensureConfirmation(
                        requestPlan(cleaned, outcome.note),
                        cleaned
                    )
                    if (nextPlan.isEmpty) {
                        say("Не получилось выполнить команду")
                        return finish(AgentStatus.FAILED, outcome.note, replans)
                    }
                    // Repeating a plan that just failed only wastes the user's time.
                    if (nextPlan.actions == plan.actions) {
                        say("Не получилось: ${outcome.note}")
                        return finish(AgentStatus.FAILED, outcome.note, replans)
                    }
                    plan = nextPlan
                    nextPlan.say?.let { say(it) }
                    actions = nextPlan.actions
                    index = 0
                }
            }
        }

        // The plan ran out without an explicit done/fail.
        say("Готово")
        return finish(AgentStatus.SUCCESS, "Готово", replans)
    }

    private fun requestPlan(command: String, note: String?): Plan {
        planningScreen = device.screen()
        return planner.plan(
            PlanRequest(
                command = command,
                screen = planningScreen,
                installedApps = device.installedApps(),
                history = steps.toList(),
                note = note
            )
        )
    }

    private fun execute(action: Action): Outcome = when (action) {
        is Action.OpenApp -> openApp(action.query)

        is Action.Tap -> tapTarget(action)

        is Action.Find -> {
            val node = locate(action.target)
            steps.add("find ${action.target} -> ${node?.label() ?: "not found"}")
            if (node == null) {
                say("Не нашёл \"${action.target}\" на экране")
                Outcome.Stop(AgentStatus.FAILED, "Элемент \"${action.target}\" не найден")
            } else {
                say("Нашёл: ${node.label()}")
                Outcome.Continue
            }
        }

        is Action.TypeText -> typeText(action)

        is Action.Scroll -> {
            var moved = false
            repeat(action.times) {
                if (device.scroll(action.direction)) moved = true
                device.sleep(settleMillis)
            }
            steps.add("scroll ${action.direction.name.lowercase()} x${action.times}")
            if (moved) Outcome.Continue else Outcome.Replan("Не удалось прокрутить экран")
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
            val ok = device.pressEnter()
            steps.add("enter${if (ok) "" else " (недоступен)"}")
            if (ok) {
                device.sleep(settleMillis)
                Outcome.Continue
            } else {
                Outcome.Replan("Enter недоступен — нужна кнопка отправки на экране")
            }
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

    private fun tapTarget(action: Action.Tap): Outcome {
        val node = resolveTapTarget(action)
            ?: return Outcome.Replan("Элемент \"${action.target}\" не найден на экране")

        // Capture the screen state immediately before acting, not before searching:
        // locate() may have scrolled, and then any change would look like the tap working.
        val before = signature(device.screen())
        val ok = device.tap(node)
        steps.add("tap ${node.label()}${if (ok) "" else " (не сработало)"}")
        if (!ok) return Outcome.Replan("Не удалось нажать \"${action.target}\"")
        awaitScreenChange(before, maxSettleMillis)
        return Outcome.Continue
    }

    /** Prefers the element number the planner saw; falls back to matching by text. */
    private fun resolveTapTarget(action: Action.Tap): ScreenNode? {
        val id = action.nodeId
        if (id != null) {
            val planned = planningScreen.nodes.firstOrNull { it.id == id }
            if (planned != null) {
                val current = device.screen().nodes.firstOrNull { it.key == planned.key }
                if (current != null) return current
                log("элемент #$id уже не на экране, ищу по тексту")
            }
        }
        if (action.target.isBlank()) return null
        return locate(action.target)
    }

    private fun typeText(action: Action.TypeText): Outcome {
        val screen = device.screen()
        val before = signature(screen)
        val field = pickField(screen, action.target)
            ?: return Outcome.Replan(
                if (action.target != null) {
                    "Не нашёл поле \"${action.target}\" — похоже, нужный экран не открылся"
                } else {
                    "Нет свободного поля ввода для текста"
                }
            )

        val ok = device.setText(field, action.text)
        steps.add("type \"${action.text}\" into ${field.label()}${if (ok) "" else " (не сработало)"}")
        if (!ok) return Outcome.Replan("Не удалось ввести текст")
        // Only remember the field once the text really went in, or a retry can never use it.
        lastTypedKey = field.key
        lastTypedText = action.text
        awaitScreenChange(before, maxSettleMillis)
        return Outcome.Continue
    }

    private fun openApp(query: String): Outcome {
        val apps = device.installedApps()
        val match = AppMatcher.resolve(query, apps)

        if (match != null) {
            // Already there: waiting for a screen that will not change costs seconds.
            if (device.foregroundPackage() == match.packageName) {
                steps.add("${match.label} уже открыт")
                return Outcome.Continue
            }
            val before = signature(device.screen())
            if (device.launchPackage(match.packageName)) {
                steps.add("open ${match.label} (${match.packageName})")
                awaitAppReady(before, match.packageName)
                return Outcome.Continue
            }
            log("не удалось запустить ${match.packageName}, пробую другие варианты")
        }

        for (pkg in AppMatcher.fallbackPackages(query)) {
            if (device.foregroundPackage() == pkg) {
                steps.add("$pkg уже открыт")
                return Outcome.Continue
            }
            val before = signature(device.screen())
            if (device.launchPackage(pkg)) {
                steps.add("open $pkg (fallback)")
                awaitAppReady(before, pkg)
                return Outcome.Continue
            }
        }

        if (AppMatcher.isSettingsQuery(query) && device.openSystemSettings()) {
            steps.add("open system settings")
            awaitAppReady("", "com.android.settings")
            return Outcome.Continue
        }

        say("Не нашёл приложение $query")
        return Outcome.Stop(AgentStatus.FAILED, "Приложение \"$query\" не установлено")
    }

    /**
     * Looks for [target]; scrolls a few screens before admitting it is not there, and puts
     * the list back where it found it so a re-plan starts from the same view.
     */
    private fun locate(target: String): ScreenNode? {
        bestMatch(device.screen(), target)?.let { return it }
        var scrolled = 0
        for (attempt in 1..maxScrollSearch) {
            if (!device.scroll(ScrollDirection.DOWN)) break
            scrolled++
            device.sleep(settleMillis)
            steps.add("scroll-search #$attempt for \"$target\"")
            bestMatch(device.screen(), target)?.let { return it }
        }
        repeat(scrolled) {
            device.scroll(ScrollDirection.UP)
            device.sleep(settleMillis / 2)
        }
        return null
    }

    private fun bestMatch(snapshot: ScreenSnapshot, target: String): ScreenNode? {
        val best = ScreenMatcher.rank(
            snapshot = snapshot,
            target = target,
            excludeKeys = setOfNotNull(lastTypedKey),
            avoidTextEquals = lastTypedText
        ).firstOrNull() ?: return null
        return if (best.second >= minMatchScore) best.first else null
    }

    /**
     * Chooses the field to type into.
     *
     * A hinted input ("type the message into the message box") must land on a field we have
     * not just used: if the only candidate is the search box we filled a moment ago, the chat
     * did not open, and typing there would wipe the search instead of writing a message.
     */
    private fun pickField(screen: ScreenSnapshot, hint: String?): ScreenNode? {
        val used = setOfNotNull(lastTypedKey)
        val fresh = ScreenMatcher.findEditable(screen, hint, excludeKeys = used)
        if (fresh != null && Text.normalize(fresh.text) != Text.normalize(lastTypedText)) {
            return fresh
        }
        // Nothing new appeared. A plan that named a field meant a different field.
        if (hint != null) return null
        return ScreenMatcher.findEditable(screen, null, excludeKeys = used)
    }

    /** A cheap fingerprint of what is on screen, used to tell "it reacted" from "it didn't". */
    private fun signature(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder(snapshot.packageName ?: "?")
        sb.append('#').append(snapshot.nodes.size)
        var hash = 7
        for (n in snapshot.nodes) {
            hash = hash * 31 + (n.viewId?.hashCode() ?: 0)
            hash = hash * 31 + (n.text?.hashCode() ?: 0)
            hash = hash * 31 + (n.contentDescription?.hashCode() ?: 0)
        }
        sb.append('/').append(hash)
        return sb.toString()
    }

    /**
     * Waits only as long as the screen actually needs. A fast app continues after one poll
     * instead of sitting out a fixed pause; a slow one gets up to [maxMillis].
     */
    private fun awaitScreenChange(before: String, maxMillis: Long) {
        var waited = 0L
        while (waited < maxMillis) {
            device.sleep(pollMillis)
            waited += pollMillis
            if (signature(device.screen()) != before) {
                device.sleep(pollMillis)
                return
            }
        }
    }

    /**
     * An app is "up" when its window has content — a launched-but-blank window is not
     * something to start tapping at.
     */
    private fun awaitAppReady(before: String, packageName: String) {
        var waited = 0L
        while (waited < maxLaunchMillis) {
            device.sleep(pollMillis)
            waited += pollMillis
            val current = device.screen()
            val arrived = current.packageName == packageName && current.nodes.isNotEmpty()
            if (arrived) {
                device.sleep(pollMillis)
                return
            }
            if (before.isNotEmpty() && signature(current) != before && current.nodes.size > 3) return
        }
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
