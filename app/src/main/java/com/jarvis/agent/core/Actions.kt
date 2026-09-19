package com.jarvis.agent.core

enum class ScrollDirection { DOWN, UP, LEFT, RIGHT;

    companion object {
        fun parse(raw: String?): ScrollDirection = when (Text.normalize(raw)) {
            "up", "вверх", "наверх", "выше" -> UP
            "left", "влево", "налево" -> LEFT
            "right", "вправо", "направо" -> RIGHT
            else -> DOWN
        }
    }
}

/**
 * Everything the agent is allowed to do on the phone.
 *
 * The planner (LLM or offline rules) only ever emits these; the executor is the single
 * place that knows how to turn them into Accessibility calls.
 */
sealed class Action {

    /** Launch an app by human name ("Telegram", "настройки", "ChatGPT"). */
    data class OpenApp(val query: String) : Action()

    /** Tap the on-screen element that best matches [target]. */
    data class Tap(val target: String) : Action()

    /** Locate an element, scrolling if needed, and report whether it is there. */
    data class Find(val target: String) : Action()

    /** Type into [target] if given, otherwise into the focused / first editable field. */
    data class TypeText(val text: String, val target: String? = null) : Action()

    data class Scroll(val direction: ScrollDirection, val times: Int = 1) : Action()

    object Back : Action()

    object Home : Action()

    object Recents : Action()

    object PressEnter : Action()

    data class Wait(val millis: Long) : Action()

    /** Say something out loud mid-plan (progress narration). */
    data class Speak(val text: String) : Action()

    /** Stop and ask the user for a yes/no before continuing. Used before anything irreversible. */
    data class Confirm(val question: String) : Action()

    /** Finished successfully; [message] is spoken. */
    data class Done(val message: String) : Action()

    /** Cannot continue; [reason] is spoken. */
    data class Fail(val reason: String) : Action()

    fun describe(): String = when (this) {
        is OpenApp -> "open_app(${query})"
        is Tap -> "tap(${target})"
        is Find -> "find(${target})"
        is TypeText -> "type_text(${text}${if (target != null) " -> $target" else ""})"
        is Scroll -> "scroll(${direction.name.lowercase()} x$times)"
        Back -> "back()"
        Home -> "home()"
        Recents -> "recents()"
        PressEnter -> "press_enter()"
        is Wait -> "wait(${millis}ms)"
        is Speak -> "speak(${text})"
        is Confirm -> "confirm(${question})"
        is Done -> "done(${message})"
        is Fail -> "fail(${reason})"
    }
}

/** A plan is what the agent intends to do for one spoken command. */
data class Plan(
    val actions: List<Action>,
    val say: String? = null,
    val source: String = "unknown"
) {
    val isEmpty: Boolean get() = actions.isEmpty()

    companion object {
        fun of(vararg actions: Action, say: String? = null, source: String = "rules"): Plan =
            Plan(actions.toList(), say, source)
    }
}
