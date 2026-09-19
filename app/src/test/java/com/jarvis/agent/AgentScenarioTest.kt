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
import com.jarvis.agent.core.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 15 acceptance scenarios from the brief, run against a fake phone.
 *
 * They exercise the real planner, the real matching and the real agent loop — only the
 * Android APIs are replaced, so a green run here means the decision-making is correct and
 * only the device plumbing is untested.
 */
class AgentScenarioTest {

    private val voice = RecordingVoice()

    private fun loop(
        phone: FakePhone,
        planner: Planner = RuleBasedPlanner(),
        gate: ConfirmationGate = AlwaysApprove
    ) = AgentLoop(
        device = phone,
        planner = planner,
        voice = voice,
        confirmation = gate,
        settleMillis = 0L
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
        assertEquals(listOf("org.telegram.messenger"), phone.launched)
        assertTrue("agent must answer out loud", voice.lines.isNotEmpty())
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

        // The same app, said the way people actually say it.
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
        val result = loop(phone).run("напечатай привет из Jarvis")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("привет из Jarvis"), phone.typedTexts)
        assertEquals(listOf("Заметка"), phone.typedInto)
    }

    @Test
    fun scenario08_scroll() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val down = loop(phone).run("прокрути вниз")
        assertEquals(AgentStatus.SUCCESS, down.status)
        assertEquals(listOf(ScrollDirection.DOWN), phone.scrolls)

        val phoneUp = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        loop(phoneUp).run("пролистай вверх")
        assertEquals(listOf(ScrollDirection.UP), phoneUp.scrolls)
    }

    @Test
    fun scenario09_navigateBack() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val result = loop(phone).run("назад")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(1, phone.backs)
    }

    @Test
    fun scenario10_findUiElement() {
        val phone = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        val found = loop(phone).run("найди Сохранить")

        assertEquals(AgentStatus.SUCCESS, found.status)
        assertTrue(voice.contains("Нашёл"))

        // And the honest answer when it is not there.
        val voice2 = RecordingVoice()
        val phone2 = FakePhone(screenProvider = { FakePhone.NOTES_SCREEN })
        AgentLoop(phone2, RuleBasedPlanner(), voice2, AlwaysApprove, settleMillis = 0L)
            .run("найди кнопку оплатить счёт")
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
                    Action.Wait(100),
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
        assertTrue(voice.contains("Ищу погоду"))
    }

    // ------------------------------------------------------------------ 12..13 confirmation

    private fun telegramPhone() = FakePhone(screenProvider = { p ->
        when {
            p.currentPackage != "org.telegram.messenger" -> FakePhone.HOME_SCREEN
            p.typedTexts.size >= 2 -> telegramChat(withSendButton = true)
            p.taps.any { it.contains("Мухаммадюсуф") } -> telegramChat(withSendButton = false)
            p.typedTexts.isNotEmpty() -> ScreenSnapshot(
                "org.telegram.messenger",
                listOf(
                    FakePhone.node(0, desc = "Поиск", viewId = "search_src_text", editable = true),
                    FakePhone.node(1, text = "Мухаммадюсуф", clickable = true),
                    FakePhone.node(2, text = "Мухаммад Али", clickable = true)
                )
            )
            p.taps.any { Text.normalize(it) == "поиск" } -> ScreenSnapshot(
                "org.telegram.messenger",
                listOf(FakePhone.node(0, desc = "Поиск", viewId = "search_src_text", editable = true))
            )
            else -> ScreenSnapshot(
                "org.telegram.messenger",
                listOf(
                    FakePhone.node(0, text = "Поиск", clickable = true),
                    FakePhone.node(1, text = "Избранное", clickable = true),
                    FakePhone.node(2, viewId = "chat_list", scrollable = true)
                )
            )
        }
    })

    private fun telegramChat(withSendButton: Boolean) = ScreenSnapshot(
        "org.telegram.messenger",
        listOfNotNull(
            FakePhone.node(0, text = "Мухаммадюсуф"),
            FakePhone.node(1, desc = "Сообщение", viewId = "chat_edit_text", editable = true),
            if (withSendButton) FakePhone.node(2, desc = "Отправить", clickable = true) else null
        )
    )

    private val sendCommand =
        "открой Telegram и напиши Мухаммадюсуфу: я опоздаю на пять минут"

    @Test
    fun scenario12_sendTelegramMessageWithConfirmation() {
        val phone = telegramPhone()
        val gate = RecordingGate(approve = true)

        val result = loop(phone, RuleBasedPlanner(), gate).run(sendCommand)

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(listOf("org.telegram.messenger"), phone.launched)
        assertEquals(1, gate.questions.size)
        assertTrue(
            "the confirmation must quote the message",
            gate.questions.first().contains("я опоздаю на пять минут")
        )
        assertEquals(
            listOf("Мухаммадюсуфу", "я опоздаю на пять минут"),
            phone.typedTexts
        )
        assertEquals("Отправить", phone.taps.last())
        assertTrue(voice.contains("отправлено"))
    }

    @Test
    fun scenario13_cancelAction() {
        val phone = telegramPhone()
        val gate = RecordingGate(approve = false)

        val result = loop(phone, RuleBasedPlanner(), gate).run(sendCommand)

        assertEquals(AgentStatus.CANCELLED, result.status)
        assertEquals(1, gate.questions.size)
        assertFalse("nothing may be sent after a refusal", phone.taps.contains("Отправить"))
        assertTrue(voice.contains("Отменено"))

        // A plain "отмена" is also a cancel, before anything happens at all.
        val voice2 = RecordingVoice()
        val phone2 = FakePhone()
        val cancelled = AgentLoop(phone2, RuleBasedPlanner(), voice2, gate, settleMillis = 0L)
            .run("Jarvis, отмена")
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
                    mapOf("message" to mapOf("role" to "assistant", "content" to planJson))
                )
            )
        )
        val transport = FakeTransport(apiBody)
        val planner = GroqPlanner(
            apiKeyProvider = { "test-key" },
            transport = transport,
            baseUrl = "https://example.invalid/chat"
        )

        val phone = FakePhone()
        // Exactly the path a spoken command takes: heard text -> Groq -> action -> speech.
        val result = loop(phone, planner).run("Jarvis, открой инстаграм")

        assertEquals(AgentStatus.SUCCESS, result.status)
        assertEquals(1, transport.requests.size)
        assertTrue(
            "the command must reach the model",
            transport.requests.first().contains("открой инстаграм")
        )
        assertEquals(
            "Bearer test-key",
            transport.headers.first()["Authorization"]
        )
        assertEquals(listOf("com.instagram.android"), phone.launched)
        assertTrue(voice.contains("Открываю Instagram"))
        assertTrue(voice.contains("Instagram открыт"))
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
        // It scrolled looking for the element before giving up...
        assertEquals(3, phone.scrolls.size)
        // ...then asked the planner again, telling it what went wrong...
        assertEquals(2, planner.requests.size)
        assertTrue(planner.requests[1].note!!.contains("Кнопка которой нет"))
        // ...and told the user instead of failing silently.
        assertTrue(voice.contains("Не вижу такую кнопку"))
        assertEquals(1, result.replans)
    }
}
