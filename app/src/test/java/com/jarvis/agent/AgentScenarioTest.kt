package com.jarvis.agent

import com.jarvis.agent.core.Action
import com.jarvis.agent.core.AgentLoop
import com.jarvis.agent.core.AgentResult
import com.jarvis.agent.core.AgentStatus
import com.jarvis.agent.core.AlwaysApprove
import com.jarvis.agent.core.ConfirmationGate
import com.jarvis.agent.core.GroqPlanner
import com.jarvis.agent.core.MiniJson
import com.jarvis.agent.core.Plan
import com.jarvis.agent.core.Planner
import com.jarvis.agent.core.RuleBasedPlanner
import com.jarvis.agent.core.ScreenSnapshot
import com.jarvis.agent.core.ScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 15 acceptance scenarios from the brief, plus the regressions for the bug seen on a
 * real phone, run against a fake phone.
 *
 * The fake is deliberately unhelpful: a field keeps the text typed into it, a result row is
 * a clickable container with a separate text child, and the chat opens only if the row
 * itself was tapped. That is what makes these tests able to fail.
 */
class AgentScenarioTest {

    private val voice = RecordingVoice()

    private companion object {
        const val TG = "org.telegram.messenger"
        const val SEARCH = "$TG:id/search_src_text"
        const val CHAT_INPUT = "$TG:id/chat_edit_text"
    }

    private fun loop(
        phone: FakePhone,
        planner: Planner = RuleBasedPlanner(),
        gate: ConfirmationGate = AlwaysApprove
    ) = AgentLoop(
        device = phone,
        planner = planner,
        voice = voice,
        confirmation = gate
    )

    private fun openApp(command: String): Pair<FakePhone, AgentResult> {
        val phone = FakePhone()
        val result = loop(phone).run(command)
        return phone to result
    }

    // ------------------------------------------------------------------ 1..6 open apps

    @Test
    fun scenario01_openTelegram() {
        val (phone, result) = openApp("Jarvis, открой Telegram.")
        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf(TG), phone.launched)
        assertTrue(voice.startingWith("Открываю"))
    }

    @Test
    fun scenario02_openInstagram() {
        val (phone, result) = openApp("Открой Instagram")
        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("com.instagram.android"), phone.launched)
    }

    @Test
    fun scenario03_openChrome() {
        val (phone, result) = openApp("Открой Chrome")
        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("com.android.chrome"), phone.launched)
    }

    @Test
    fun scenario04_openChatGpt() {
        val (phone, result) = openApp("Открой ChatGPT")
        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("com.openai.chatgpt"), phone.launched)

        val (phone2, result2) = openApp("открой чат гпт")
        assertEquals(AgentStatus.SUCCESS, result2.status)
        assertEquals(listOf("com.openai.chatgpt"), phone2.launched)
    }

    @Test
    fun scenario05_openSettings() {
        val (phone, result) = openApp("Открой настройки")
        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("com.android.settings"), phone.launched)
    }

    @Test
    fun scenario06_openMessages() {
        val (phone, result) = openApp("Открой сообщения")
        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("com.google.android.apps.messaging"), phone.launched)

        val (phone2, result2) = openApp("открой смс")
        assertEquals(AgentStatus.SUCCESS, result2.status)
        assertEquals(listOf("com.google.android.apps.messaging"), phone2.launched)
    }

    // ------------------------------------------------------------------ 7..10 primitives

    @Test
    fun scenario07_typeText() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val result = loop(phone).run("напечатай привет мир")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("привет мир"), phone.typedTexts)
        assertEquals(listOf("Заметка"), phone.typedInto)
    }

    @Test
    fun scenario08_scroll() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val down = loop(phone).run("прокрути вниз")
        assertEquals(AgentStatus.SUCCESS, down.status)
        assertEquals(listOf(ScrollDirection.DOWN), phone.scrolls)

        val phoneUp = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val up = loop(phoneUp).run("пролистай немного вверх")
        assertEquals(AgentStatus.SUCCESS, up.status)
        assertEquals("a modifier word must not flip the direction", listOf(ScrollDirection.UP), phoneUp.scrolls)
    }

    @Test
    fun scenario09_navigateBack() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val result = loop(phone).run("назад")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(1, phone.backs)
        // "прокрути назад" is a scroll, not the system Back button.
        val phone2 = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        loop(phone2).run("прокрути назад")
        assertEquals(0, phone2.backs)
        assertEquals(listOf(ScrollDirection.UP), phone2.scrolls)
    }

    @Test
    fun scenario10_findUiElement() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val found = loop(phone).run("найди Сохранить")

        assertEquals(AgentStatus.SUCCESS, found.status)
        assertTrue(voice.said("Нашёл: Сохранить"))

        val voice2 = RecordingVoice()
        val phone2 = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val missing = AgentLoop(phone2, RuleBasedPlanner(), voice2, AlwaysApprove)
            .run("найди кнопку оплатить счёт")
        assertEquals("not finding something is not success", AgentStatus.FAILED, missing.status)
        assertTrue(voice2.lines.none { it.startsWith("Нашёл:") })
        assertTrue(voice2.contains("Не нашёл"))
    }

    // ------------------------------------------------------------------ 11 multi-step

    @Test
    fun scenario11_multiStepTask() {
        val phone = FakePhone(screenProvider = { p ->
            when {
                p.currentPackage != "com.android.chrome" -> FakePhone.HOME_SCREEN
                p.taps.isEmpty() -> ScreenSnapshot(
                    "com.android.chrome",
                    listOf(
                        FakePhone.node(0, desc = "Поиск или введите адрес", clickable = true),
                        FakePhone.node(1, text = "Закладки", clickable = true)
                    )
                )
                else -> ScreenSnapshot(
                    "com.android.chrome",
                    listOf(
                        FakePhone.node(
                            0,
                            text = p.textIn("com.android.chrome:id/url_bar"),
                            viewId = "com.android.chrome:id/url_bar",
                            desc = "Строка поиска",
                            editable = true
                        )
                    )
                )
            }
        })

        val planner = ScriptedPlanner(
            Plan(
                listOf(
                    Action.OpenApp("Chrome"),
                    Action.Tap("поиск|search"),
                    Action.TypeText("погода в Ташкенте"),
                    Action.PressEnter,
                    Action.Done("Ищу погоду в Ташкенте")
                ),
                say = "Открываю Chrome"
            )
        )

        val result = loop(phone, planner).run("открой Chrome и поищи погоду в Ташкенте")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("com.android.chrome"), phone.launched)
        assertEquals(1, phone.taps.size)
        assertEquals(listOf("погода в Ташкенте"), phone.typedTexts)
        assertEquals(1, phone.enters)
        assertTrue(voice.said("Ищу погоду в Ташкенте"))
    }

    // ------------------------------------------------------------------ Telegram world

    /**
     * A Telegram that behaves like the real one: the search box keeps the query, the result
     * row is a container plus a text child, and only tapping the row opens the chat.
     */
    private fun telegramWorld(
        contact: String = "Twingo",
        rowAppears: Boolean = true,
        rowAfterScrolls: Int = 0,
        chatOpens: Boolean = true,
        searchHasViewId: Boolean = true
    ): (FakePhone) -> ScreenSnapshot = { p ->
        val query = if (searchHasViewId) p.textIn(SEARCH) else p.typedTexts.firstOrNull()
        val searchOpen = query != null || p.tappedNodes.any { it.text == "Поиск" }
        val rowTapped = p.tappedNodes.any { it.text == contact && !it.editable }
        val matches = query != null && contact.startsWith(query, ignoreCase = true)
        val shown = matches && rowAppears && p.scrolls.count { it == ScrollDirection.DOWN } >= rowAfterScrolls
        val message = p.textIn(CHAT_INPUT)
        when {
            p.currentPackage != TG -> FakePhone.HOME_SCREEN
            chatOpens && rowTapped -> ScreenSnapshot(TG, listOfNotNull(
                FakePhone.node(0, text = contact),
                FakePhone.node(1, text = message, desc = "Сообщение", viewId = CHAT_INPUT, editable = true),
                if (message != null) FakePhone.node(2, desc = "Отправить", clickable = true) else null
            ))
            searchOpen -> ScreenSnapshot(TG, buildList {
                add(
                    FakePhone.node(
                        0,
                        text = query,
                        desc = "Поиск",
                        viewId = if (searchHasViewId) SEARCH else null,
                        editable = true,
                        focused = true
                    )
                )
                if (shown) {
                    add(FakePhone.node(1, clickable = true, className = "android.widget.FrameLayout"))
                    add(FakePhone.node(2, text = contact))
                }
            })
            else -> ScreenSnapshot(TG, listOf(
                FakePhone.node(0, text = "Поиск", clickable = true),
                FakePhone.node(1, text = "Избранное", clickable = true),
                FakePhone.node(2, viewId = "$TG:id/chat_list", scrollable = true)
            ))
        }
    }

    private val sendCommand = "открой Telegram и напиши Twingo: я опоздаю на пять минут"

    // ------------------------------------------------------------------ 12..13 confirmation

    @Test
    fun scenario12_sendTelegramMessageWithConfirmation() {
        val phone = FakePhone(screenProvider = telegramWorld())
        val gate = RecordingGate(approve = true)

        val result = loop(phone, RuleBasedPlanner(), gate).run(sendCommand)

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf(TG), phone.launched)
        assertEquals(listOf("Поиск", "Twingo", "Отправить"), phone.taps)
        // The name into the search box, the message into the chat input — by identity.
        assertEquals(listOf(SEARCH, CHAT_INPUT), phone.typedNodes.map { it.viewId })
        assertEquals("the search query must survive", "Twingo", phone.textIn(SEARCH))
        assertEquals("я опоздаю на пять минут", phone.textIn(CHAT_INPUT))
        assertEquals(1, gate.questions.size)
        assertTrue(gate.questions.first().contains("я опоздаю на пять минут"))
        assertTrue(voice.contains("отправлено"))
    }

    @Test
    fun scenario13_cancelAction() {
        val phone = FakePhone(screenProvider = telegramWorld())
        val gate = RecordingGate(approve = false)

        val result = loop(phone, RuleBasedPlanner(), gate).run(sendCommand)

        assertEquals(AgentStatus.CANCELLED, result.status)
        // Everything up to the question really happened...
        assertEquals(listOf("Поиск", "Twingo"), phone.taps)
        assertEquals("я опоздаю на пять минут", phone.textIn(CHAT_INPUT))
        // ...and the send did not.
        assertEquals(1, gate.questions.size)
        assertEquals("Отменено", voice.lines.last())

        val voice2 = RecordingVoice()
        val phone2 = FakePhone()
        val cancelled = AgentLoop(phone2, RuleBasedPlanner(), voice2, gate).run("Jarvis, отмена")
        assertEquals(AgentStatus.CANCELLED, cancelled.status)
        assertTrue(phone2.launched.isEmpty())
    }

    // ------------------------------------------------------------------ 14 full AI round trip

    @Test
    fun scenario14_voiceToAiToActionToVoice() {
        val planJson = MiniJson.stringify(
            linkedMapOf(
                "say" to "Открываю Instagram",
                "actions" to listOf(
                    mapOf("type" to "open_app", "query" to "Instagram"),
                    mapOf("type" to "done", "text" to "Instagram открыт")
                )
            )
        )
        val apiBody = MiniJson.stringify(
            mapOf(
                "choices" to listOf(
                    mapOf(
                        "finish_reason" to "stop",
                        "message" to mapOf("role" to "assistant", "content" to planJson)
                    )
                )
            )
        )
        val transport = FakeTransport(apiBody)
        val planner = GroqPlanner(
            apiKeyProvider = { "test-key" },
            transport = transport,
            baseUrl = "https://example.invalid/v1/chat/completions"
        )

        val phone = FakePhone()
        val result = loop(phone, planner).run("Jarvis, открой инстаграм")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.first().contains("открой инстаграм"))
        assertEquals("Bearer test-key", transport.headers.first()["Authorization"])
        assertEquals(listOf("com.instagram.android"), phone.launched)
        assertTrue(voice.said("Открываю Instagram"))
        assertTrue(voice.said("Instagram открыт"))
    }

    // ------------------------------------------------------------------ 15 recovery

    @Test
    fun scenario15_recoversWhenElementIsMissing() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val planner = ScriptedPlanner(
            Plan(listOf(Action.Tap("Кнопка которой нет"), Action.Done("ок"))),
            Plan(listOf(Action.Fail("Не вижу такую кнопку, скажите иначе")))
        )

        val result = loop(phone, planner).run("нажми кнопку которой нет")

        assertEquals(AgentStatus.FAILED, result.status)
        // It scrolled looking for the element, then put the list back where it was...
        assertEquals(3, phone.scrolls.count { it == ScrollDirection.DOWN })
        assertEquals(3, phone.scrolls.count { it == ScrollDirection.UP })
        // ...then asked the planner again, telling it what went wrong...
        assertEquals(2, planner.requests.size)
        assertTrue(planner.requests[1].note!!.contains("Кнопка которой нет"))
        // ...and told the user instead of failing silently.
        assertTrue(voice.said("Не вижу такую кнопку, скажите иначе"))
        assertEquals(1, result.replans)
    }

    // ------------------------------------------------------------------ regressions

    @Test
    fun regression_theMessageNeverGoesBackIntoTheSearchBox() {
        // The reported bug: the agent typed the contact name into search, then deleted it
        // and typed the message there. Reachable only when the field keeps its text.
        val phone = FakePhone(screenProvider = telegramWorld(chatOpens = false))
        val gate = RecordingGate(approve = true)

        val result = loop(phone, RuleBasedPlanner(), gate).run(sendCommand)

        assertEquals(AgentStatus.FAILED, result.status)
        assertEquals("only the recipient was ever typed", listOf("Twingo"), phone.typedTexts)
        assertEquals("Twingo", phone.textIn(SEARCH))
        assertTrue("nothing may be sent", phone.taps.none { it == "Отправить" })
        assertTrue("and nothing may be confirmed", gate.questions.isEmpty())
    }

    @Test
    fun regression_anUntargetedTypeTextDoesNotReuseTheFieldItJustFilled() {
        // Exactly the shape a model emits: type_text with no "target".
        val phone = FakePhone(screenProvider = telegramWorld(chatOpens = false))
        val planner = ScriptedPlanner(
            Plan(
                listOf(
                    Action.OpenApp("Telegram"),
                    Action.Tap("Поиск"),
                    Action.TypeText("Twingo"),
                    Action.Tap("Twingo"),
                    Action.TypeText("я опоздаю на пять минут"),
                    Action.Confirm("Отправить?"),
                    Action.Tap("отправить|send"),
                    Action.Done("Отправлено")
                ),
                say = "Пишу Twingo",
                source = "llm"
            ),
            Plan(listOf(Action.Fail("Чат не открылся")))
        )

        val result = loop(phone, planner).run(sendCommand)

        assertEquals("the search query must never be overwritten", "Twingo", phone.textIn(SEARCH))
        assertEquals(AgentStatus.FAILED, result.status)
    }

    @Test
    fun regression_aFieldWithoutAViewIdIsStillNotATapTarget() {
        // No viewId means the key falls back to class+position, which the keyboard can move.
        val phone = FakePhone(screenProvider = telegramWorld(searchHasViewId = false))
        val result = loop(phone, RuleBasedPlanner(), RecordingGate(approve = true)).run(sendCommand)

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertTrue("the row, not the field", phone.tappedNodes.none { it.editable })
    }

    @Test
    fun regression_aTapThePhoneRefusesIsNotSuccess() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        phone.tapResult = { false }
        val planner = ScriptedPlanner(
            Plan(listOf(Action.Tap("Сохранить"), Action.Done("ок"))),
            Plan(listOf(Action.Fail("Кнопка не нажимается")))
        )

        val result = loop(phone, planner).run("нажми сохранить")

        assertEquals(AgentStatus.FAILED, result.status)
        assertEquals(1, result.replans)
        assertTrue(voice.said("Кнопка не нажимается"))
    }

    @Test
    fun regression_aContactBelowTheFoldIsFoundByScrolling() {
        val phone = FakePhone(screenProvider = telegramWorld(rowAfterScrolls = 2))
        val result = loop(phone, RuleBasedPlanner(), RecordingGate(approve = true)).run(sendCommand)

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(2, phone.scrolls.count { it == ScrollDirection.DOWN })
        assertEquals(0, result.replans)
    }

    @Test
    fun regression_silenceOnAConfirmationIsARefusal() {
        val phone = FakePhone(screenProvider = telegramWorld())
        val gate = SilentGate()

        val result = loop(phone, RuleBasedPlanner(), gate).run(sendCommand)

        assertEquals(AgentStatus.CANCELLED, result.status)
        assertEquals(1, gate.questions.size)
        assertTrue(phone.taps.none { it == "Отправить" })
    }

    @Test
    fun regression_anAppAlreadyInFrontIsNotRelaunched() {
        val phone = FakePhone(screenProvider = { FakePhone.HOME_SCREEN })
        phone.currentPackage = TG
        val result = loop(phone).run("открой телеграм")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertTrue("no relaunch, and no waiting for a screen that will not change",
            phone.launched.isEmpty())
        assertTrue(phone.sleptMillis < 1000L)
    }

    @Test
    fun regression_twoCommandsInARowDoNotLeakTheLastUsedField() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val typing = Plan(listOf(Action.TypeText("раз", "заметка"), Action.Done("ок")))
        val agent = loop(phone, ScriptedPlanner(typing))

        assertEquals(AgentStatus.SUCCESS, agent.run("напечатай раз").status)
        assertEquals(AgentStatus.SUCCESS, agent.run("напечатай раз").status)
        assertEquals(listOf("раз", "раз"), phone.typedTexts)
        assertEquals(listOf("Заметка", "Заметка"), phone.typedInto)
    }

    @Test
    fun regression_theAgentStillWorksWhenTheModelIsDown() {
        val phone = FakePhone()
        val planner = com.jarvis.agent.core.LayeredPlanner(RuleBasedPlanner(), ExplodingPlanner("groq"))
        val result = loop(phone, planner).run("Jarvis, открой Telegram")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf(TG), phone.launched)
    }

    @Test
    fun regression_aRepeatedPlanIsNotRetriedForever() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val sameEveryTime = Plan(listOf(Action.Tap("Нет такого"), Action.Done("ок")))
        val planner = ScriptedPlanner(sameEveryTime, sameEveryTime, sameEveryTime)

        val result = loop(phone, planner).run("нажми нет такого")

        assertEquals(AgentStatus.FAILED, result.status)
        assertEquals("one replan, then stop repeating itself", 1, result.replans)
        assertFalse(voice.lines.isEmpty())
    }
}
