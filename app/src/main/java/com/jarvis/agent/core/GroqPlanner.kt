package com.jarvis.agent.core

/**
 * The brain: turns a spoken command plus the current screen into a plan, using Groq.
 *
 * Groq speaks the OpenAI chat-completions dialect, and its JSON mode makes the answer
 * parseable without prompt babysitting.
 *
 * [baseUrl] is configurable so the key can live on your own proxy instead of on the phone —
 * see REQUIRED API KEYS in the README.
 */
class GroqPlanner(
    private val apiKeyProvider: () -> String,
    private val model: String = DEFAULT_MODEL,
    private val transport: HttpTransport = UrlHttpTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val maxTokens: Int = 1024,
    private val log: (String) -> Unit = {}
) : Planner {

    companion object {
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    }

    override val name: String = "groq"

    override fun plan(request: PlanRequest): Plan {
        val key = apiKeyProvider().trim()
        val headers = HashMap<String, String>()
        headers["content-type"] = "application/json"
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"

        val payload = MiniJson.stringify(
            linkedMapOf<String, Any?>(
                "model" to model,
                "temperature" to 0,
                "max_tokens" to maxTokens,
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to Prompts.SYSTEM),
                    mapOf("role" to "user", "content" to Prompts.userMessage(request))
                )
            )
        )

        val response = transport.post(baseUrl, headers, payload)
        if (response.code !in 200..299) {
            throw IllegalStateException("Groq API ${response.code}: ${response.body.take(300)}")
        }

        val text = extractText(response.body)
        log("groq answer: ${text.take(400)}")
        return PlanCodec.decode(text, name)
    }

    /** Pulls `choices[0].message.content` out of a chat-completions response. */
    fun extractText(body: String): String {
        val root = MiniJson.asObject(MiniJson.parseOrNull(body))
        val choices = MiniJson.asArray(root["choices"])
        val first = MiniJson.asObject(choices.firstOrNull())
        val message = MiniJson.asObject(first["message"])
        return MiniJson.str(message["content"])?.takeIf { it.isNotBlank() } ?: body
    }
}
