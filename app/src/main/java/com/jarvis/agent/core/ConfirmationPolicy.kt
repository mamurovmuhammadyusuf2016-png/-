package com.jarvis.agent.core

/**
 * The agent asks before it does something another person will see.
 *
 * The check is on the *plan*, not only on the words the user said: a model plan that taps
 * "Отправить" is a send whether or not the command contained a verb we recognise, and a
 * confirmation placed after that tap is not a confirmation at all.
 */
object ConfirmationPolicy {

    /** Targets that mean "this is the irreversible step". */
    private val DESTRUCTIVE_TARGETS = listOf(
        "отправ", "удал", "купи", "оплат", "заплат", "позвон", "перевед", "подтверд",
        "опубликов", "заказ", "send", "delete", "remove", "buy", "pay", "call",
        "confirm", "publish", "post", "order", "transfer"
    )

    fun isDestructiveTap(action: Action): Boolean {
        if (action !is Action.Tap) return false
        val tokens = Text.tokens(action.target)
        return tokens.any { token -> DESTRUCTIVE_TARGETS.any { token.startsWith(it) } }
    }

    fun ensureConfirmation(plan: Plan, command: String): Plan {
        val risky = Phrases.needsConfirmation(command) || plan.actions.any { isDestructiveTap(it) }
        if (!risky) return plan

        val actions = plan.actions.toMutableList()
        val firstConfirm = actions.indexOfFirst { it is Action.Confirm }

        // The step the question is about: the first destructive tap, or failing that the
        // last tap / enter, or failing that the first thing that changes anything.
        var guardAt = actions.indexOfFirst { isDestructiveTap(it) }
        if (guardAt < 0) guardAt = actions.indexOfLast { it is Action.Tap || it is Action.PressEnter }
        if (guardAt < 0) guardAt = actions.indexOfFirst { it is Action.TypeText }
        if (guardAt < 0) guardAt = 0

        // A confirmation the planner put after the damage protects nothing.
        if (firstConfirm in 0 until guardAt) return plan
        if (firstConfirm >= guardAt) actions.removeAt(firstConfirm)

        actions.add(guardAt, Action.Confirm(questionFor(command)))
        return plan.copy(actions = actions)
    }

    fun questionFor(command: String): String = "Подтвердите: $command"
}
