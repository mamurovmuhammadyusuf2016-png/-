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

/** Tries [primary] and silently falls back to [fallback] when it fails or returns nothing. */
class FallbackPlanner(
    private val primary: Planner,
    private val fallback: Planner,
    private val onError: (String) -> Unit = {}
) : Planner {
    override val name: String get() = "${primary.name}+${fallback.name}"

    override fun plan(request: PlanRequest): Plan {
        try {
            val plan = primary.plan(request)
            if (!plan.isEmpty) return plan
            onError("${primary.name} returned an empty plan, using ${fallback.name}")
        } catch (e: Exception) {
            onError("${primary.name} failed: ${e.message}")
        }
        return fallback.plan(request)
    }
}
