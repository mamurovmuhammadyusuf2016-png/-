package com.jarvis.agent.core

enum class ScrollDirection { DOWN, UP, LEFT, RIGHT;

    companion object {
        /**
         * Reads the direction from any word in the phrase: "прокрути немного вверх" must
         * scroll up, not fall through to the default because of the extra word.
         */
        fun parse(raw: String?): ScrollDirection {
            for (word in Text.tokens(raw)) {
                when (word) {
                    "up", "вверх", "наверх", "выше", "вверху" -> return UP
                    "left", "влево", "налево" -> return LEFT
                    "right", "вправо", "направо" -> return RIGHT
                    "down", "вниз", "ниже", "внизу" -> return DOWN
                }
            }
            return DOWN
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

    /**
     * Tap an element. [nodeId] is the `#12` number from the screen listing when the planner
     * used one — matching by number cannot pick the wrong lookalike; [target] is the text
     * fallback for elements that are not on screen yet.
     */
    data class Tap(val target: String, val nodeId: Int? = null) : Action()

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
        is Tap -> if (nodeId != null) "tap(#$nodeId ${target})" else "tap(${target})"
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
    val source: String = "unknown",
    /**
     * True when the planner is sure enough that asking the model would only add latency.
     * The offline planner sets it for the commands it recognises exactly ("открой Chrome");
     * anything it merely guessed at is left false so the model gets a say.
     */
    val confident: Boolean = false
) {
    val isEmpty: Boolean get() = actions.isEmpty()
}
