package com.jarvis.agent.core

/**
 * The agent asks before it does something another person will see.
 *
 * The planner may add its own [Action.Confirm]; if it forgets and the command looks
 * irreversible, we insert one right before the final tap.
 */
object ConfirmationPolicy {

    fun ensureConfirmation(plan: Plan, command: String): Plan {
        if (!Phrases.needsConfirmation(command)) return plan
        if (plan.actions.any { it is Action.Confirm }) return plan

        val insertAt = plan.actions.indexOfLast { it is Action.Tap || it is Action.PressEnter }
        if (insertAt < 0) return plan

        val actions = plan.actions.toMutableList()
        actions.add(insertAt, Action.Confirm(questionFor(command)))
        return plan.copy(actions = actions)
    }

    fun questionFor(command: String): String = "Подтвердите: $command"
}
