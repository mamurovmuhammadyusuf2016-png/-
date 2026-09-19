package com.jarvis.agent

import com.jarvis.agent.core.Action
import com.jarvis.agent.core.AppEntry
import com.jarvis.agent.core.AppMatcher
import com.jarvis.agent.core.ConfirmationPolicy
import com.jarvis.agent.core.GroqPlanner
import com.jarvis.agent.core.LayeredPlanner
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
        assertEquals("""{"actions":[{"type":"home"}]}""", MiniJson.extractFirstObject(raw))
    }

    @Test
    fun badJsonDoesNotThrowThroughParseOrNull() {
        assertNull(MiniJson.parseOrNull("{not json"))
    }

    @Test
    fun aTruncatedAnswerCannotProduceAPartialPlan() {
        // A plan cut off mid-string must not execute the destructive half of itself.
        val truncated = """{"say":"ok","actions":[{"type":"tap","target":"Отпра"""
        assertNull(MiniJson.parseOrNull(truncated))
        assertTrue(PlanCodec.decode(truncated).actions.all { it is Action.Fail })
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

    @Test
    fun stemBridgesRussianCaseEndings() {
        assertEquals(Text.stem("аня"), Text.stem("ане"))
        assertEquals(Text.stem("ольга"), Text.stem("ольге"))
        // But different names must stay different.
        assertFalse(Text.stem("маша") == Text.stem("даша"))
    }

    @Test
    fun glyphOnlyButtonsGetWords() {
        assertTrue(Text.glyphWords("➤").contains("send"))
        assertTrue(Text.glyphWords("✓").contains("готово"))
        assertEquals("", Text.glyphWords("Отправить"))
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
        assertEquals("org.telegram.messenger", pkg("телеграмм"))
        assertEquals("com.instagram.android", pkg("инста"))
        assertEquals("com.openai.chatgpt", pkg("чат гпт"))
        assertEquals("com.android.settings", pkg("настройки"))
        assertEquals("com.google.android.apps.messaging", pkg("смс"))
    }

    @Test
    fun prefersTheClosestLabelOnATie() {
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

    @Test
    fun aSubPageIsNotTheApp() {
        // "открой настройки вайфая" must not silently open the Settings root and claim
        // success — the model can plan the extra taps, the matcher cannot.
        assertNull(pkg("настройки вайфая"))
        assertNull(pkg("последние сообщения от мамы"))
    }
}

class ScreenMatcherTest {

    private val chat = ScreenSnapshot(
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
        assertEquals(0, ScreenMatcher.find(chat, "поиск")?.id)
        assertEquals(2, ScreenMatcher.find(chat, "send")?.id)
    }

    @Test
    fun alternativesLetOnePlanWorkInBothLanguages() {
        assertEquals(2, ScreenMatcher.find(chat, "отправить|send")?.id)
    }

    @Test
    fun findsTheRightInputField() {
        assertEquals(1, ScreenMatcher.findEditable(chat, "сообщение")?.id)
        assertEquals(1, ScreenMatcher.findEditable(chat, null)?.id)
    }

    @Test
    fun reportsNothingForAnAbsentElement() {
        assertNull(ScreenMatcher.find(chat, "оплатить кредит"))
    }

    // ---- the failures seen on a real phone

    private val searchResults = ScreenSnapshot(
        "org.telegram.messenger",
        listOf(
            // The search box now holds what was typed into it — this is the trap.
            FakePhone.node(0, text = "Twingo", desc = "Поиск", viewId = "search_src_text", editable = true),
            FakePhone.node(1, clickable = true, className = "android.widget.FrameLayout"),
            FakePhone.node(2, text = "Twingo")
        )
    )

    @Test
    fun theFieldHoldingWhatWeTypedIsNotTheSearchResult() {
        assertEquals(
            2,
            ScreenMatcher.find(searchResults, "Twingo", avoidTextEquals = "Twingo")?.id
        )
        assertEquals(
            2,
            ScreenMatcher.find(searchResults, "Twingo", excludeKeys = setOf("search_src_text#0"))?.id
        )
    }

    @Test
    fun aMessageBubbleMentioningSendingIsNotTheSendButton() {
        val screen = ScreenSnapshot(
            "org.telegram.messenger",
            listOf(
                FakePhone.node(0, text = "Скинь отчёт, надо отправить до 6", clickable = true),
                FakePhone.node(1, desc = "➤", viewId = "chat_send_btn", clickable = true)
            )
        )
        assertEquals(1, ScreenMatcher.find(screen, "отправить|send")?.id)
    }

    @Test
    fun doesNotTapTheButtonThatClearsTheSearch() {
        val screen = ScreenSnapshot(
            "com.android.settings",
            listOf(
                FakePhone.node(0, viewId = "search_close_btn", desc = "Убрать запрос", clickable = true),
                FakePhone.node(1, viewId = "search_src_text", editable = true, clickable = true)
            )
        )
        assertEquals(1, ScreenMatcher.find(screen, "поиск|search")?.id)
    }

    @Test
    fun aHintBeatsAFocusedField() {
        val screen = ScreenSnapshot(
            "org.telegram.messenger",
            listOf(
                FakePhone.node(0, desc = "Поиск в чате", viewId = "search", editable = true, focused = true),
                FakePhone.node(1, desc = "Сообщение", viewId = "chat", editable = true)
            )
        )
        assertEquals(1, ScreenMatcher.findEditable(screen, "сообщение|message")?.id)
    }

    @Test
    fun shortNamesDoNotFuzzyMatchEachOther() {
        val screen = ScreenSnapshot(
            "org.telegram.messenger",
            listOf(
                FakePhone.node(0, text = "Даша", clickable = true),
                FakePhone.node(1, text = "Саша", clickable = true)
            )
        )
        assertNull("Маша is not Даша", ScreenMatcher.find(screen, "Маша"))
    }

    @Test
    fun aDativeNameStillFindsTheContact() {
        val screen = ScreenSnapshot(
            "org.telegram.messenger",
            listOf(FakePhone.node(0, text = "Аня", clickable = true))
        )
        assertEquals(0, ScreenMatcher.find(screen, "Ане")?.id)
    }

    @Test
    fun aKeyIsStableAcrossALayoutShift() {
        val before = FakePhone.node(0, viewId = "chat_edit_text", editable = true)
        val after = before.copy(bounds = before.bounds.copy(top = before.bounds.top - 2))
        assertEquals(before.key, after.key)
    }

    @Test
    fun summaryKeepsTheInputAndTheSendButton() {
        // A chat is mostly message bubbles; the two elements that matter must survive.
        val bubbles = (0 until 60).map { FakePhone.node(it, text = "сообщение $it") }
        val screen = ScreenSnapshot(
            "org.telegram.messenger",
            bubbles + listOf(
                FakePhone.node(90, desc = "Сообщение", viewId = "entry", editable = true),
                FakePhone.node(91, desc = "Отправить", viewId = "send", clickable = true)
            )
        )
        val summary = screen.summarize()
        assertTrue("the composer must reach the model", summary.contains("id=entry"))
        assertTrue("so must the send button", summary.contains("id=send"))
    }
}

class PhrasesTest {

    @Test
    fun stripsTheWakeWord() {
        assertEquals("открой Telegram", Phrases.stripWakeWord("Jarvis, открой Telegram"))
        assertEquals("открой Telegram", Phrases.stripWakeWord("Джарвис открой Telegram"))
        assertEquals("открой Telegram", Phrases.stripWakeWord("открой Telegram, джарвис"))
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
        assertTrue(Phrases.isNo("нет"))
        assertTrue(Phrases.isCancel("отмена"))
        assertFalse(Phrases.isYes("открой telegram"))
    }

    @Test
    fun anAmbiguousAnswerIsNotAnApproval() {
        // "да нет" is Russian for "no". Neither is an answer here.
        assertNull(Phrases.readAnswer("да нет"))
        assertNull(Phrases.readAnswer("да не надо"))
        assertEquals(true, Phrases.readAnswer("да"))
        assertEquals(false, Phrases.readAnswer("нет"))
        assertNull(Phrases.readAnswer("открой хром"))
    }

    @Test
    fun aBareWordIsNavigation_aPhraseIsNot() {
        assertTrue(Phrases.isBack("назад"))
        assertFalse("this is a scroll", Phrases.isBack("прокрути назад"))
        assertTrue(Phrases.isHome("домой"))
        assertFalse("this is a route", Phrases.isHome("маршрут домой"))
        assertFalse(Phrases.isCancel("stop the music"))
    }

    @Test
    fun splitsRecipientFromMessage() {
        assertEquals(
            "Мухаммадюсуфу" to "я опоздаю на пять минут",
            Phrases.splitRecipientAndMessage("Мухаммадюсуфу: я опоздаю на пять минут")
        )
        assertEquals(
            "Твинго" to "я приду через пять минут",
            Phrases.splitRecipientAndMessage("Твинго что я приду через пять минут")
        )
        assertEquals(
            "Ивану" to "буду через час",
            Phrases.splitRecipientAndMessage("для Ивану, буду через час")
        )
        assertEquals(
            "Твинго" to "я приду",
            Phrases.splitRecipientAndMessage("Твинго, что я приду")
        )
    }

    @Test
    fun aTimeIsNotASeparator() {
        assertEquals("маме", Phrases.splitRecipientAndMessage("маме в 17:30 буду").first)
    }

    @Test
    fun aMidSentenceChtoIsNotASeparator() {
        assertEquals("маме", Phrases.splitRecipientAndMessage("маме я думаю что опоздаю").first)
    }

    @Test
    fun searchStemDropsTheCaseEnding() {
        assertEquals("Иван", Phrases.searchStem("Ивану"))
        assertEquals("Ольг", Phrases.searchStem("Ольге"))
        assertEquals("Мухаммадюсуф", Phrases.searchStem("Мухаммадюсуфу"))
        assertEquals("Twingo", Phrases.searchStem("Twingo"))
    }

    @Test
    fun flagsIrreversibleCommandsWithoutFalseAlarms() {
        assertTrue(Phrases.needsConfirmation("напиши Ивану привет"))
        assertTrue(Phrases.needsConfirmation("delete the photo"))
        assertTrue(Phrases.needsConfirmation("переведи 5000 рублей маме"))
        assertTrue(Phrases.needsConfirmation("закажи такси"))
        assertFalse(Phrases.needsConfirmation("открой настройки"))
        assertFalse(Phrases.needsConfirmation("recall the last page"))
        assertFalse(Phrases.needsConfirmation("покажи отправленные"))
    }
}

class PlanCodecTest {

    @Test
    fun decodesTheDocumentedFormat() {
        val plan = PlanCodec.decode(
            """
            {"say":"Открываю","actions":[
              {"type":"open_app","query":"Telegram"},
              {"type":"tap","id":7},
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
        assertEquals(7, (plan.actions[1] as Action.Tap).nodeId)
        assertEquals(Action.Scroll(ScrollDirection.UP, 2), plan.actions[3])
    }

    @Test
    fun anUnknownActionFailsThePlanInsteadOfVanishing() {
        // Dropping a step silently turns "search, tap, type, confirm, send" into something
        // else entirely — refuse the whole plan instead.
        val plan = PlanCodec.decode("""{"actions":[{"type":"teleport"},{"type":"home"}]}""")
        assertEquals(1, plan.actions.size)
        assertTrue(plan.actions.single() is Action.Fail)
    }

    @Test
    fun readsTheTargetUnderAnySpelling() {
        val plan = PlanCodec.decode(
            """{"actions":[{"type":"type_text","text":"привет","element":"Сообщение"}]}"""
        )
        assertEquals("Сообщение", (plan.actions.single() as Action.TypeText).target)
    }

    @Test
    fun survivesMarkdownFences() {
        val plan = PlanCodec.decode("```json\n{\"actions\":[{\"type\":\"back\"}]}\n```")
        assertEquals(listOf(Action.Back), plan.actions)
    }

    @Test
    fun clampsAnAbsurdWait() {
        val plan = PlanCodec.decode("""{"actions":[{"type":"wait","millis":600000}]}""")
        assertEquals(2000L, (plan.actions.single() as Action.Wait).millis)
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
    private val apps = FakePhone.DEFAULT_APPS

    private fun plan(command: String) =
        planner.plan(PlanRequest(command, installedApps = apps))

    @Test
    fun handlesOpenCommandsInBothLanguages() {
        assertEquals(Action.OpenApp("Telegram"), plan("Открой Telegram").actions.first())
        assertEquals(Action.OpenApp("Instagram"), plan("open Instagram").actions.first())
    }

    @Test
    fun handlesNavigationPrimitives() {
        assertEquals(Action.Back, plan("назад").actions.first())
        assertEquals(Action.Home, plan("домой").actions.first())
        assertEquals(Action.Scroll(ScrollDirection.DOWN, 1), plan("прокрути вниз").actions.first())
        assertEquals(Action.Scroll(ScrollDirection.UP, 1), plan("прокрути немного вверх").actions.first())
    }

    @Test
    fun isConfidentOnlyAboutAppsItCanActuallySee() {
        assertTrue(plan("Открой Telegram").confident)
        assertFalse("an app we cannot see must go to the model", plan("Открой Ватсап").confident)
        assertFalse(
            "with no app list there is nothing to be confident about",
            planner.plan(PlanRequest("Открой Telegram")).confident
        )
    }

    @Test
    fun doesNotBlindlyTypeAMessageItWasNotAskedToType() {
        // "напиши маме что скоро буду" used to type the words into whatever field was
        // focused — possibly someone else's open chat.
        val p = plan("напиши маме что скоро буду")
        assertFalse(p.confident)
        assertTrue(p.actions.none { it is Action.TypeText })
    }

    @Test
    fun aQuestionIsNotATypingCommand() {
        val p = plan("скажи который час")
        assertTrue(p.actions.none { it is Action.TypeText && it.text == "который час" })
    }

    @Test
    fun buildsAConfirmedMessagePlan() {
        val p = plan("открой Telegram и напиши Ивану: буду через час")
        val actions = p.actions
        assertFalse("sending must get the model's opinion first", p.confident)
        assertTrue(actions.any { it == Action.OpenApp("Telegram") })
        assertTrue(actions.any { it is Action.TypeText && it.text == "буду через час" })
        // The search gets the stem, because contact search matches prefixes.
        assertTrue(actions.any { it is Action.TypeText && it.text == "Иван" })
        val confirmIndex = actions.indexOfFirst { it is Action.Confirm }
        val sendIndex = actions.indexOfLast { it is Action.Tap }
        assertTrue("confirmation must come before sending", confirmIndex in 0 until sendIndex)
    }

    @Test
    fun aNonMessengerIsNotAChat() {
        val p = plan("открой Камера и отправь фото маме")
        assertTrue(p.actions.none { it is Action.Confirm })
    }

    @Test
    fun theRestOfTheSentenceIsNotPartOfTheAppName() {
        // Reported from a real phone: "открой Telegram напишу Твинк что я опаздываю"
        // became a request to open an app literally called
        // "Telegram напишу Твинк что я опаздываю", and the agent answered
        // "нет такого приложения Telegram".
        val p = plan("открой Telegram напишу Твинк что я опаздываю на пять минут")
        assertTrue("Telegram must still be opened", p.actions.any { it == Action.OpenApp("Telegram") })
        assertTrue(
            "and the rest must become the message",
            p.actions.any { it is Action.TypeText && it.text == "я опаздываю на пять минут" }
        )
        assertTrue(p.actions.any { it is Action.Confirm })
    }

    @Test
    fun anyEndingOfTheVerbIsUnderstood() {
        for (verb in listOf("напиши", "напишу", "напишешь", "отправь", "отправлю", "передам")) {
            val p = plan("открой Telegram $verb Ивану привет")
            assertTrue("«$verb» must open Telegram", p.actions.any { it == Action.OpenApp("Telegram") })
            assertTrue("«$verb» must reach the message", p.actions.any { it is Action.TypeText })
        }
    }

    @Test
    fun anUnknownTailStillOpensTheApp() {
        // Even when the rest makes no sense to the rules, the app the user named opens.
        val p = plan("открой Chrome и найди там что нибудь про погоду")
        assertTrue(p.actions.any { it == Action.OpenApp("Chrome") })
        assertFalse("the model should get a say about the rest", p.confident)
    }

    @Test
    fun saysItDoesNotUnderstandRatherThanGuessing() {
        assertTrue(plan("сделай мне красиво").actions.first() is Action.Fail)
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
    fun guardsASendEvenWhenTheWordsSoundedHarmless() {
        // The plan taps "Отправить": that is a send whatever the command said.
        val plan = Plan(listOf(Action.OpenApp("Telegram"), Action.Tap("Отправить")))
        val guarded = ConfirmationPolicy.ensureConfirmation(plan, "разберись с этим")
        assertTrue(guarded.actions.any { it is Action.Confirm })
    }

    @Test
    fun aConfirmationAfterTheSendIsMovedBeforeIt() {
        val plan = Plan(
            listOf(
                Action.TypeText("привет"),
                Action.Tap("отправить|send"),
                Action.Confirm("Отправить?")
            )
        )
        val guarded = ConfirmationPolicy.ensureConfirmation(plan, "напиши Ивану привет")
        val confirmIndex = guarded.actions.indexOfFirst { it is Action.Confirm }
        val sendIndex = guarded.actions.indexOfFirst { ConfirmationPolicy.isDestructiveTap(it) }
        assertTrue("asking after the message is gone is not asking", confirmIndex < sendIndex)
    }

    @Test
    fun anIrreversiblePlanWithoutATapIsStillGuarded() {
        val plan = Plan(listOf(Action.OpenApp("Telegram"), Action.TypeText("привет"), Action.Done("ок")))
        val guarded = ConfirmationPolicy.ensureConfirmation(plan, "напиши Ивану привет")
        assertTrue(guarded.actions.any { it is Action.Confirm })
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

class LayeredPlannerTest {

    private fun confident(vararg actions: Action) =
        Plan(actions.toList(), null, "rules", confident = true)

    private fun unsure(vararg actions: Action) =
        Plan(actions.toList(), null, "rules", confident = false)

    @Test
    fun aConfidentFastPlanNeverReachesTheModel() {
        val fast = ScriptedPlanner(confident(Action.OpenApp("Telegram")))
        val smart = ScriptedPlanner(Plan(listOf(Action.Home)))
        val plan = LayeredPlanner(fast, smart).plan(PlanRequest("открой telegram"))

        assertEquals(listOf(Action.OpenApp("Telegram")), plan.actions)
        assertTrue("no network round trip for the obvious", smart.requests.isEmpty())
    }

    @Test
    fun anUnsureFastPlanIsSentToTheModel() {
        val fast = ScriptedPlanner(unsure(Action.Fail("Не понял команду")))
        val smart = ScriptedPlanner(Plan(listOf(Action.Home)))
        val plan = LayeredPlanner(fast, smart).plan(PlanRequest("сделай мне красиво"))

        assertEquals(listOf(Action.Home), plan.actions)
        assertEquals(1, smart.requests.size)
    }

    @Test
    fun aReplanAlwaysGoesToTheModel() {
        val fast = ScriptedPlanner(confident(Action.Tap("Отправить")))
        val smart = ScriptedPlanner(Plan(listOf(Action.Fail("Не вижу кнопку"))))
        val plan = LayeredPlanner(fast, smart).plan(
            PlanRequest("нажми отправить", note = "Элемент \"Отправить\" не найден")
        )

        assertEquals(1, smart.requests.size)
        assertEquals("Элемент \"Отправить\" не найден", smart.requests.single().note)
        assertEquals(listOf(Action.Fail("Не вижу кнопку")), plan.actions)
    }

    @Test
    fun anUnreachableModelFallsBackToTheRules() {
        val fast = ScriptedPlanner(unsure(Action.OpenApp("Telegram")))
        val logs = ArrayList<String>()
        val plan = LayeredPlanner(fast, ExplodingPlanner("groq"), { logs.add(it) })
            .plan(PlanRequest("открой telegram"))

        assertEquals(listOf(Action.OpenApp("Telegram")), plan.actions)
        assertTrue(logs.any { it.contains("groq") })
    }

    @Test
    fun anUnreachableModelSaysSoInsteadOfHidingBehindNePonyal() {
        val fast = ScriptedPlanner(unsure(Action.Fail("Не понял команду: кхм")))
        val plan = LayeredPlanner(fast, ExplodingPlanner("groq")).plan(PlanRequest("кхм"))
        val failure = plan.actions.single() as Action.Fail
        assertTrue("the real cause must reach the user", failure.reason.contains("связи"))
    }
}

class GroqPlannerTest {

    private val planJson =
        """{"say":"Открываю","actions":[{"type":"open_app","query":"Telegram"}]}"""

    private fun apiBody(content: String, finish: String = "stop") = MiniJson.stringify(
        mapOf(
            "choices" to listOf(
                mapOf("finish_reason" to finish, "message" to mapOf("content" to content))
            )
        )
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
    fun aBadRequestIsNotBlamedOnEveryModelInTurn() {
        // A 400 that is about our request must fail once, not burn the whole model list and
        // then report the last model's error.
        val transport = FakeTransport("""{"error":{"message":"invalid messages","code":"invalid_request_error"}}""", code = 400)
        try {
            GroqPlanner({ "k" }, transport = transport).plan(PlanRequest("открой телеграм"))
            throw AssertionError("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("400"))
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun aDecommissionedModelMovesOnToTheNext() {
        val transport = FakeTransport("""{"error":{"message":"gone","code":"model_decommissioned"}}""", code = 400)
        try {
            GroqPlanner({ "k" }, transport = transport).plan(PlanRequest("открой телеграм"))
            throw AssertionError("expected an exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("недоступна"))
        }
        assertEquals(GroqPlanner.DEFAULT_MODELS.size, transport.requests.size)
    }

    @Test
    fun aTruncatedAnswerIsRejected() {
        val transport = FakeTransport(apiBody(planJson, finish = "length"))
        try {
            GroqPlanner({ "k" }, transport = transport).plan(PlanRequest("открой телеграм"))
            throw AssertionError("expected an exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("обрезан"))
        }
    }

    @Test
    fun authErrorsFailFast() {
        val transport = FakeTransport("""{"error":{"message":"bad key"}}""", code = 401)
        try {
            GroqPlanner({ "" }, transport = transport).plan(PlanRequest("открой телеграм"))
            throw AssertionError("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("401"))
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun systemPromptDocumentsEveryActionItAsksFor() {
        for (type in listOf(
            "open_app", "tap", "type_text", "scroll", "back", "home",
            "press_enter", "confirm", "done", "fail"
        )) {
            assertTrue("prompt must document $type", Prompts.SYSTEM.contains(type))
        }
        assertNotNull(PlanCodec.decodeAction(mapOf("type" to "tap", "id" to 3)))
    }
}
