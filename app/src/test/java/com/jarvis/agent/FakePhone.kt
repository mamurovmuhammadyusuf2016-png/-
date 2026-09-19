package com.jarvis.agent

import com.jarvis.agent.core.AppEntry
import com.jarvis.agent.core.Bounds
import com.jarvis.agent.core.ConfirmationGate
import com.jarvis.agent.core.DeviceController
import com.jarvis.agent.core.HttpResponse
import com.jarvis.agent.core.HttpTransport
import com.jarvis.agent.core.Plan
import com.jarvis.agent.core.PlanRequest
import com.jarvis.agent.core.Planner
import com.jarvis.agent.core.ScreenNode
import com.jarvis.agent.core.ScreenSnapshot
import com.jarvis.agent.core.ScrollDirection
import com.jarvis.agent.core.VoiceOutput

/** A phone made of data. Everything the agent does to it is recorded, nothing sleeps. */
class FakePhone(
    private val apps: List<AppEntry> = DEFAULT_APPS,
    var screenProvider: (FakePhone) -> ScreenSnapshot = { HOME_SCREEN }
) : DeviceController {

    val launched = ArrayList<String>()
    val taps = ArrayList<String>()
    val typedTexts = ArrayList<String>()
    val typedInto = ArrayList<String>()
    val scrolls = ArrayList<ScrollDirection>()
    var backs = 0
    var homes = 0
    var recents = 0
    var enters = 0
    var settingsOpened = false
    var sleptMillis = 0L
    var currentPackage: String? = "com.android.launcher"

    override fun installedApps(): List<AppEntry> = apps

    override fun launchPackage(packageName: String): Boolean {
        if (apps.none { it.packageName == packageName }) return false
        launched.add(packageName)
        currentPackage = packageName
        return true
    }

    override fun openSystemSettings(): Boolean {
        settingsOpened = true
        currentPackage = "com.android.settings"
        return true
    }

    override fun screen(): ScreenSnapshot = screenProvider(this)

    override fun tap(node: ScreenNode): Boolean {
        taps.add(node.label())
        return true
    }

    override fun setText(node: ScreenNode, text: String): Boolean {
        typedInto.add(node.label())
        typedTexts.add(text)
        return true
    }

    override fun scroll(direction: ScrollDirection): Boolean {
        scrolls.add(direction)
        return true
    }

    override fun back(): Boolean { backs++; return true }

    override fun home(): Boolean { homes++; return true }

    override fun recents(): Boolean { recents++; return true }

    override fun pressEnter(): Boolean { enters++; return true }

    override fun sleep(millis: Long) { sleptMillis += millis }

    companion object {
        val DEFAULT_APPS = listOf(
            AppEntry("Telegram", "org.telegram.messenger"),
            AppEntry("Instagram", "com.instagram.android"),
            AppEntry("Chrome", "com.android.chrome"),
            AppEntry("ChatGPT", "com.openai.chatgpt"),
            AppEntry("Настройки", "com.android.settings"),
            AppEntry("Сообщения", "com.google.android.apps.messaging"),
            AppEntry("YouTube", "com.google.android.youtube"),
            AppEntry("Камера", "com.android.camera")
        )

        fun node(
            id: Int,
            text: String? = null,
            desc: String? = null,
            viewId: String? = null,
            clickable: Boolean = false,
            editable: Boolean = false,
            scrollable: Boolean = false
        ) = ScreenNode(
            id = id,
            text = text,
            contentDescription = desc,
            viewId = viewId,
            className = if (editable) "android.widget.EditText" else "android.widget.TextView",
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            bounds = Bounds(0, id * 100, 1080, id * 100 + 90)
        )

        val HOME_SCREEN = ScreenSnapshot(
            "com.android.launcher",
            listOf(
                node(0, text = "Telegram", clickable = true),
                node(1, text = "Chrome", clickable = true)
            )
        )

        /** A generic screen with one text field, used by the "type text" scenario. */
        val NOTES_SCREEN = ScreenSnapshot(
            "com.example.notes",
            listOf(
                node(0, desc = "Заметка", viewId = "com.example.notes:id/note_input", editable = true),
                node(1, text = "Сохранить", clickable = true),
                node(2, viewId = "com.example.notes:id/list", scrollable = true)
            )
        )
    }
}

class RecordingVoice : VoiceOutput {
    val lines = ArrayList<String>()
    override fun say(text: String) { lines.add(text) }
    fun joined(): String = lines.joinToString(" | ")
    fun contains(fragment: String): Boolean =
        lines.any { it.contains(fragment, ignoreCase = true) }
}

/** Returns canned plans in order; the last one repeats. Records what it was asked. */
class ScriptedPlanner(private vararg val plans: Plan) : Planner {
    override val name: String = "scripted"
    val requests = ArrayList<PlanRequest>()

    override fun plan(request: PlanRequest): Plan {
        requests.add(request)
        val index = (requests.size - 1).coerceAtMost(plans.size - 1)
        return plans[index]
    }
}

class RecordingGate(private val approve: Boolean) : ConfirmationGate {
    val questions = ArrayList<String>()
    override fun confirm(question: String): Boolean {
        questions.add(question)
        return approve
    }
}

/** Replays a canned Claude Messages API response and records the request body. */
class FakeTransport(private val responseBody: String, private val code: Int = 200) : HttpTransport {
    val requests = ArrayList<String>()
    val headers = ArrayList<Map<String, String>>()

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        requests.add(body)
        this.headers.add(headers)
        return HttpResponse(code, responseBody)
    }
}
