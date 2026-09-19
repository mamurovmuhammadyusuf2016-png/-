package com.jarvis.agent

import com.jarvis.agent.core.Action
import com.jarvis.agent.core.AppEntry
import com.jarvis.agent.core.AppMatcher
import com.jarvis.agent.core.ConfirmationPolicy
import com.jarvis.agent.core.GroqPlanner
import com.jarvis.agent.core.MiniJson
import com.jarvis.agent.core.Phrases
import com.jarvis.agent.core.Plan
import com.jarvis.agent.core.PlanCodec
import com.jarvis.agent.core.PlanRequest
import com.jarvis.agent.core.Prompts
import com.jarvis.agent.core.RuleBasedPlanner
import com.jarvis.agent.core.ScreenMatcher
import com.jarvis.agent.core.ScreenSnapshot
import com.jarvis.agent.core.ScrollDirection
import com.jarvis.agent.core.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesNestedStructures() {
        val parsed = MiniJson.asObject(
            MiniJson.parse("""{"a":1,"b":[true,null,"x"],"c":{"d":-2.5}}""")
        )
        assertEquals(1, MiniJson.int(parsed["a"], 0))
        assertEquals(listOf(true, null, "x"), MiniJson.asArray(parsed["b"]))
        assertEquals("-2.5", MiniJson.str(MiniJson.asObject(parsed["c"])["d"]))
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val parsed = MiniJson.asObject(MiniJson.parse("""{"t":"a\"b\\c\ndЖ"}"""))
        assertEquals("a\"b\\c\ndЖ", parsed["t"])
    }

    @Test
    fun roundTripsThroughStringify() {
        val original = linkedMapOf<String, Any?>(
            "say" to "Открываю \"Telegram\"",
            "n" to 12,
            "list" to listOf("a", "b")
        )
        val reparsed = MiniJson.asObject(MiniJson.parse(MiniJson.stringify(original)))
        assertEquals("Открываю \"Telegram\"", reparsed["say"])
        assertEquals(12, MiniJson.int(reparsed["n"], 0))
    }

    @Test
    fun pullsJsonOutOfChattyAnswers() {
        val raw = "Sure! ```json\n{\"actions\":[{\"type\":\"home\"}]}\n``` hope that helps"
        val extracted = MiniJson.extractFirstObject(raw)
        assertNotNull(extracted)
        assertEquals("""{"actions":[{"type":"home"}]}""", extracted)
    }

    @Test
    fun badJsonDoesNotThrowThroughParseOrNull() {
        assertNull(MiniJson.parseOrNull("{not json"))
    }
}

class TextTest {

    @Test
    fun normalizeStripsPunctuationAndYo() {
        assertEquals("привет мир", Text.normalize("  Привёт, Мир!!  "))
        assertEquals("open telegram", Text.normalize("Open   Telegram."))
    }

    @Test
    fun translitBridgesAlphabets() {
        assertEquals("telegram", Text.translit("телеграм"))
        assertEquals("instagram", Text.translit("инстаграм"))
    }

    @Test
    fun similarityIsForgivingOfOneTypo() {
        assertTrue(Text.similarity("telegram", "telegrm") > 0.8)
        assertTrue(Text.similarity("telegram", "instagram") < 0.7)
    }
}

class AppMatcherTest {

    private val apps = listOf(
        AppEntry("Telegram", "org.telegram.messenger"),
        AppEntry("Instagram", "com.instagram.android"),
        AppEntry("Chrome", "com.android.chrome"),
        AppEntry("Chrome Beta", "com.chrome.beta"),
        AppEntry("ChatGPT", "com.openai.chatgpt"),
        AppEntry("Настройки", "com.android.settings"),
        AppEntry("Сообщения", "com.google.android.apps.messaging"),
        AppEntry("Мой Банк", "uz.mybank.app")
    )

    private fun pkg(query: String) = AppMatcher.resolve(query, apps)?.packageName

    @Test
    fun matchesExactLabels() {
        assertEquals("org.telegram.messenger", pkg("Telegram"))
        assertEquals("com.instagram.android", pkg("instagram"))
    }

    @Test
    fun matchesRussianSpeech() {
        assertEquals("org.telegram.messenger", pkg("телеграм"))
        assertEquals("com.instagram.android", pkg("инста"))
        assertEquals("com.openai.chatgpt", pkg("чат гпт"))
        assertEquals("com.android.settings", pkg("настройки"))
        assertEquals("com.google.android.apps.messaging", pkg("смс"))
    }

    @Test
    fun prefersTheShorterLabelOnATie() {
        assertEquals("com.android.chrome", pkg("Chrome"))
    }

    @Test
    fun worksForAppsItHasNeverHeardOf() {
        assertEquals("uz.mybank.app", pkg("мой банк"))
    }

    @Test
    fun returnsNullWhenNothingIsClose() {
        assertNull(pkg("квантовый ускоритель"))
    }
}

class ScreenMatcherTest {

    private val screen = ScreenSnapshot(
        "org.telegram.messenger",
        listOf(
            FakePhone.node(0, text = "Поиск", clickable = true),
            FakePhone.node(1, desc = "Сообщение", viewId = "chat_edit_text", editable = true),
            FakePhone.node(2, desc = "Send", clickable = true),
            FakePhone.node(3, viewId = "chat_list", scrollable = true)
        )
    )

    @Test
    fun findsByTextAndDescription() {
        assertEquals(0, ScreenMatcher.find(screen, "поиск")?.id)
        assertEquals(2, ScreenMatcher.find(screen, "send")?.id)
    }

    @Test
    fun alternativesLetOnePlanWorkInBothLanguages() {
        assertEquals(2, ScreenMatcher.find(screen, "отправить|send")?.id)
    }

    @Test
    fun findsTheRightInputField() {
        assertEquals(1, ScreenMatcher.findEditable(screen, "сообщение")?.id)
        assertEquals(1, ScreenMatcher.findEditable(screen, null)?.id)
    }

    @Test
    fun reportsNothingForAnAbsentElement() {
        val hit = ScreenMatcher.rank(screen, "оплатить кредит").firstOrNull()
        assertTrue(hit == null || hit.second < 60)
    }

    @Test
    fun summaryIsCompactAndMentionsFlags() {
        val summary = screen.summarize()
        assertTrue(summary.contains("org.telegram.messenger"))
        assertTrue(summary.contains("clickable"))
        assertTrue(summary.contains("editable"))
    }
}

class PhrasesTest {

    @Test
    fun stripsTheWakeWord() {
        assertEquals("открой Telegram", Phrases.stripWakeWord("Jarvis, открой Telegram"))
        assertEquals("открой Telegram", Phrases.stripWakeWord("Джарвис открой Telegram"))
        assertEquals("открой Telegram", Phrases.stripWakeWord("открой Telegram"))
    }

    @Test
    fun detectsWakeWord() {
        assertTrue(Phrases.containsWakeWord("джарвис, что там"))
        assertFalse(Phrases.containsWakeWord("просто разговор"))
    }

    @Test
    fun understandsYesNoAndCancel() {
        assertTrue(Phrases.isYes("да"))
        assertTrue(Phrases.isYes("ok"))
        assertTrue(Phrases.isNo("нет"))
        assertTrue(Phrases.isCancel("отмена"))
        assertFalse(Phrases.isYes("открой telegram"))
    }

    @Test
    fun splitsRecipientFromMessage() {
        assertEquals(
            "Мухаммадюсуфу" to "я опоздаю на пять минут",
            Phrases.splitRecipientAndMessage("Мухаммадюсуфу: я опоздаю на пять минут")
        )
        assertEquals(
            "Ивану" to "буду через час",
            Phrases.splitRecipientAndMessage("для Ивану, буду через час")
        )
    }

    @Test
    fun flagsIrreversibleCommands() {
        assertTrue(Phrases.needsConfirmation("напиши Ивану привет"))
        assertTrue(Phrases.needsConfirmation("delete the photo"))
        assertFalse(Phrases.needsConfirmation("открой настройки"))
    }
}

class PlanCodecTest {

    @Test
    fun decodesTheDocumentedFormat() {
        val plan = PlanCodec.decode(
            """
            {"say":"Открываю","actions":[
              {"type":"open_app","query":"Telegram"},
              {"type":"tap","target":"Поиск"},
              {"type":"type_text","text":"Иван","target":"Поиск"},
              {"type":"scroll","direction":"up","times":2},
              {"type":"confirm","text":"Отправить?"},
              {"type":"done","text":"Готово"}
            ]}
            """.trimIndent()
        )
        assertEquals("Открываю", plan.say)
        assertEquals(6, plan.actions.size)
        assertEquals(Action.OpenApp("Telegram"), plan.actions[0])
        assertEquals(Action.Scroll(ScrollDirection.UP, 2), plan.actions[3])
    }

    @Test
    fun skipsUnknownActionsInsteadOfFailing() {
        val plan = PlanCodec.decode(
            """{"actions":[{"type":"teleport"},{"type":"home"}]}"""
        )
        assertEquals(listOf(Action.Home), plan.actions)
    }

    @Test
    fun survivesMarkdownFences() {
        val plan = PlanCodec.decode("```json\n{\"actions\":[{\"type\":\"back\"}]}\n```")
        assertEquals(listOf(Action.Back), plan.actions)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val plan = Plan(
            listOf(
                Action.OpenApp("Chrome"),
                Action.TypeText("hi", "поиск"),
                Action.Wait(500),
                Action.Done("ок")
            )
        )
        assertEquals(plan.actions, PlanCodec.decode(PlanCodec.encode(plan)).actions)
    }
}

class RuleBasedPlannerTest {

    private val planner = RuleBasedPlanner()

    private fun plan(command: String) = planner.plan(PlanRequest(command))

    @Test
    fun handlesOpenCommandsInBothLanguages() {
        assertEquals(Action.OpenApp("Telegram"), plan("Открой Telegram").actions.first())
        assertEquals(Action.OpenApp("Instagram"), plan("open Instagram").actions.first())
    }

    @Test
    fun handlesNavigationPrimitives() {
        assertEquals(Action.Back, plan("назад").actions.first())
        assertEquals(Action.Home, plan("домой").actions.first())
        assertEquals(
            Action.Scroll(ScrollDirection.DOWN, 1),
            plan("прокрути вниз").actions.first()
        )
    }

    @Test
    fun buildsAConfirmedMessagePlan() {
        val actions = plan("открой Telegram и напиши Ивану: буду через час").actions
        assertTrue(actions.any { it == Action.OpenApp("Telegram") })
        assertTrue(actions.any { it is Action.TypeText && it.text == "буду через час" })
        val confirmIndex = actions.indexOfFirst { it is Action.Confirm }
        val sendIndex = actions.indexOfLast { it is Action.Tap }
        assertTrue("confirmation must come before sending", confirmIndex in 0 until sendIndex)
    }

    @Test
    fun saysItDoesNotUnderstandRatherThanGuessing() {
        val actions = plan("сделай мне красиво").actions
        assertTrue(actions.first() is Action.Fail)
    }
}

class ConfirmationPolicyTest {

    @Test
    fun insertsAConfirmationWhenThePlannerForgets() {
        val plan = Plan(listOf(Action.OpenApp("Telegram"), Action.Tap("Отправить")))
        val guarded = ConfirmationPolicy.ensureConfirmation(plan, "напиши Ивану привет")
        assertEquals(3, guarded.actions.size)
        assertTrue(guarded.actions[1] is Action.Confirm)
    }

    @Test
    fun leavesSafeCommandsAlone() {
        val plan = Plan(listOf(Action.OpenApp("Chrome"), Action.Tap("Поиск")))
        assertEquals(plan, ConfirmationPolicy.ensureConfirmation(plan, "открой Chrome"))
    }

    @Test
    fun doesNotDoubleUp() {
        val plan = Plan(listOf(Action.Confirm("точно?"), Action.Tap("Отправить")))
        assertEquals(plan, ConfirmationPolicy.ensureConfirmation(plan, "отправь сообщение"))
    }
}

class GroqPlannerTest {

    private val planJson =
        """{"say":"Открываю","actions":[{"type":"open_app","query":"Telegram"}]}"""

    private fun apiBody(content: String) = MiniJson.stringify(
        mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content))))
    )

    @Test
    fun sendsTheScreenAndTheCommandToTheModel() {
        val transport = FakeTransport(apiBody(planJson))
        val planner = GroqPlanner({ "k" }, transport = transport)
        planner.plan(
            PlanRequest(
                "открой телеграм",
                ScreenSnapshot("com.android.launcher", listOf(FakePhone.node(0, text = "Telegram")))
            )
        )
        val body = transport.requests.single()
        assertTrue(body.contains("открой телеграм"))
        assertTrue(body.contains("com.android.launcher"))
        assertTrue(body.contains("json_object"))
    }

    @Test
    fun readsChatCompletionResponses() {
        val transport = FakeTransport(apiBody(planJson))
        val plan = GroqPlanner({ "k" }, transport = transport).plan(PlanRequest("открой телеграм"))
        assertEquals(listOf(Action.OpenApp("Telegram")), plan.actions)
    }

    @Test
    fun turnsHttpErrorsIntoExceptionsSoTheFallbackCanTakeOver() {
        val transport = FakeTransport("""{"error":{"message":"bad key"}}""", code = 401)
        val planner = GroqPlanner({ "" }, transport = transport)
        try {
            planner.plan(PlanRequest("открой телеграм"))
            throw AssertionError("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun systemPromptDocumentsEveryActionTheCodecUnderstands() {
        for (type in listOf(
            "open_app", "tap", "find", "type_text", "scroll",
            "back", "home", "recents", "press_enter", "wait", "speak", "confirm", "done", "fail"
        )) {
            assertTrue("prompt must document $type", Prompts.SYSTEM.contains(type))
        }
    }
}
