package com.jarvis.agent.core

/**
 * Turns the model's JSON answer into a [Plan].
 *
 * Deliberately forgiving: unknown action types are skipped instead of failing the whole
 * plan, and prose around the JSON is tolerated.
 */
object PlanCodec {

    fun decode(raw: String, source: String = "llm"): Plan {
        val json = MiniJson.extractFirstObject(raw) ?: raw
        val root = MiniJson.asObject(MiniJson.parseOrNull(json))
        if (root.isEmpty()) return Plan(emptyList(), null, source)

        val say = MiniJson.str(root["say"])?.takeIf { it.isNotBlank() }
        val raw = MiniJson.asArray(root["actions"])
        val actions = ArrayList<Action>()
        for (item in raw) {
            decodeAction(MiniJson.asObject(item))?.let { actions.add(it) }
        }
        // Never execute a half-understood plan: a dropped step in the middle turns
        // "search, tap the chat, type, confirm, send" into something else entirely.
        if (actions.size != raw.size) {
            return Plan(
                listOf(Action.Fail("Не понял ответ ИИ, попробуйте сформулировать иначе")),
                say,
                source
            )
        }
        return Plan(actions, say, source)
    }

    fun decodeAction(o: Map<String, Any?>): Action? {
        val type = Text.normalize(MiniJson.str(o["type"]) ?: MiniJson.str(o["action"]))
        if (type.isEmpty()) return null
        val target = MiniJson.str(o["target"]) ?: MiniJson.str(o["element"])
            ?: MiniJson.str(o["field"]) ?: MiniJson.str(o["query"]) ?: MiniJson.str(o["label"])
        val nodeId = MiniJson.int(o["id"], -1).takeIf { it >= 0 }
        val text = MiniJson.str(o["text"]) ?: MiniJson.str(o["value"]) ?: MiniJson.str(o["message"])

        return when (type.replace(' ', '_')) {
            "open_app", "openapp", "launch_app", "open" ->
                (target ?: text)?.let { Action.OpenApp(it) }

            "tap", "click", "press", "touch" ->
                if (nodeId != null) Action.Tap(target.orEmpty(), nodeId)
                else target?.let { Action.Tap(it) }

            "find", "find_element", "locate", "search_element" ->
                target?.let { Action.Find(it) }

            "type_text", "type", "input", "write", "set_text" ->
                text?.let { Action.TypeText(it, target) }

            "scroll", "swipe" ->
                Action.Scroll(
                    ScrollDirection.parse(MiniJson.str(o["direction"]) ?: target),
                    MiniJson.int(o["times"], 1).coerceIn(1, 10)
                )

            "back" -> Action.Back
            "home" -> Action.Home
            "recents", "recent_apps" -> Action.Recents
            "press_enter", "enter", "submit", "search_submit" -> Action.PressEnter
            // The loop already waits for the screen; a model-requested pause is a nudge, not a nap.
            "wait", "sleep", "delay" -> Action.Wait(MiniJson.long(o["millis"], 400L).coerceIn(50L, 2_000L))
            "speak", "say" -> (text ?: target)?.let { Action.Speak(it) }
            "confirm", "ask", "confirmation" -> Action.Confirm(text ?: target ?: "Подтвердить действие?")
            "done", "finish", "complete" -> Action.Done(text ?: target ?: "Готово")
            "fail", "error", "cannot" -> Action.Fail(text ?: target ?: "Не удалось выполнить")
            else -> null
        }
    }

    /** Encodes a plan back to JSON. Used for logs and for few-shot examples in the prompt. */
    fun encode(plan: Plan): String {
        val actions = plan.actions.map { encodeAction(it) }
        val root = LinkedHashMap<String, Any?>()
        if (plan.say != null) root["say"] = plan.say
        root["actions"] = actions
        return MiniJson.stringify(root)
    }

    fun encodeAction(a: Action): Map<String, Any?> = when (a) {
        is Action.OpenApp -> mapOf("type" to "open_app", "query" to a.query)
        is Action.Tap -> if (a.nodeId != null) {
            mapOf("type" to "tap", "id" to a.nodeId, "target" to a.target)
        } else {
            mapOf("type" to "tap", "target" to a.target)
        }
        is Action.Find -> mapOf("type" to "find", "target" to a.target)
        is Action.TypeText -> if (a.target != null) {
            mapOf("type" to "type_text", "text" to a.text, "target" to a.target)
        } else {
            mapOf("type" to "type_text", "text" to a.text)
        }
        is Action.Scroll -> mapOf(
            "type" to "scroll",
            "direction" to a.direction.name.lowercase(),
            "times" to a.times
        )
        Action.Back -> mapOf("type" to "back")
        Action.Home -> mapOf("type" to "home")
        Action.Recents -> mapOf("type" to "recents")
        Action.PressEnter -> mapOf("type" to "press_enter")
        is Action.Wait -> mapOf("type" to "wait", "millis" to a.millis)
        is Action.Speak -> mapOf("type" to "speak", "text" to a.text)
        is Action.Confirm -> mapOf("type" to "confirm", "text" to a.question)
        is Action.Done -> mapOf("type" to "done", "text" to a.message)
        is Action.Fail -> mapOf("type" to "fail", "text" to a.reason)
    }
}
