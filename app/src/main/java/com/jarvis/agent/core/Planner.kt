package com.jarvis.agent.core

/** Everything the planner is allowed to know about the current moment. */
data class PlanRequest(
    val command: String,
    val screen: ScreenSnapshot = ScreenSnapshot(),
    val installedApps: List<AppEntry> = emptyList(),
    val history: List<String> = emptyList(),
    /** Set when we are re-planning after a failure, e.g. "target 'Send' not found". */
    val note: String? = null
)

interface Planner {
    val name: String
    fun plan(request: PlanRequest): Plan
}

/**
 * Fast path first, model second.
 *
 * "Открой Telegram" is unambiguous — answering it locally makes it instant instead of
 * waiting on a network round trip. Anything the fast planner is not sure about, and every
 * re-plan after a failure, goes to the model; if the model is unreachable we still fall
 * back to whatever the fast planner came up with.
 */
class LayeredPlanner(
    private val fast: Planner,
    private val smart: Planner,
    private val log: (String) -> Unit = {}
) : Planner {

    override val name: String get() = "${fast.name}+${smart.name}"

    override fun plan(request: PlanRequest): Plan {
        val quick = try {
            fast.plan(request)
        } catch (e: Exception) {
            log("${fast.name} failed: ${e.message}")
            null
        }

        // A re-plan means the obvious answer already failed — always think harder.
        if (request.note == null && quick != null && quick.confident && !quick.isEmpty) {
            return quick
        }

        return try {
            val considered = smart.plan(request)
            if (considered.isEmpty) quick ?: considered else considered
        } catch (e: Exception) {
            log("${smart.name} недоступен: ${e.message}")
            quick ?: Plan(
                listOf(Action.Fail("Не понял команду, и нет связи с ИИ")),
                null,
                name
            )
        }
    }
}
