package com.jarvis.agent.core

/** Thrown when one model is unusable right now, so the next one should be tried. */
class ModelUnavailableException(val model: String, message: String) : Exception(message)

/**
 * The brain: turns a spoken command plus the current screen into a plan, using Groq.
 *
 * Groq speaks the OpenAI chat-completions dialect, and its JSON mode makes the answer
 * parseable without prompt babysitting.
 *
 * [models] is a preference order, not a single choice: model names on a free tier come and
 * go, and a rate limit is per-model. The first one that answers wins and is remembered for
 * the rest of the session.
 *
 * [baseUrl] is configurable so the key can live on your own proxy instead of on the phone.
 */
class GroqPlanner(
    private val apiKeyProvider: () -> String,
    private val models: List<String> = DEFAULT_MODELS,
    private val transport: HttpTransport = UrlHttpTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val maxTokens: Int = 1024,
    private val log: (String) -> Unit = {}
) : Planner {

    companion object {
        /**
         * Free-tier models, best first for this job. Kimi and gpt-oss follow a strict output
         * format most reliably; the llamas are the fast, always-there fallback.
         */
        val DEFAULT_MODELS = listOf(
            "moonshotai/kimi-k2-instruct-0905",
            "openai/gpt-oss-120b",
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant"
        )

        val DEFAULT_MODEL: String get() = DEFAULT_MODELS.first()
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

        fun parseModels(raw: String): List<String> {
            val parsed = raw.split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return parsed.ifEmpty { DEFAULT_MODELS }
        }
    }

    override val name: String = "groq"

    /** The model that answered last; tried first next time. */
    @Volatile
    var activeModel: String? = null
        private set

    override fun plan(request: PlanRequest): Plan {
        val order = candidateOrder()
        var lastError: Exception? = null

        for (model in order) {
            try {
                val plan = askModel(model, request)
                if (activeModel != model) log("Groq: работает модель $model")
                activeModel = model
                return plan
            } catch (e: ModelUnavailableException) {
                log("Groq: ${e.message}")
                lastError = e
                if (activeModel == model) activeModel = null
            }
        }
        throw lastError ?: IllegalStateException("Не настроено ни одной модели Groq")
    }

    private fun candidateOrder(): List<String> {
        val current = activeModel
        return if (current == null) models else listOf(current) + models.filter { it != current }
    }

    private fun askModel(model: String, request: PlanRequest): Plan {
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

        val response = transport.post(baseUrl, authHeaders(), payload)
        if (response.code !in 200..299) {
            val detail = errorMessage(response.body).ifBlank { response.body.take(200) }
            // 404/400 = no such model, 429 = this model is rate-limited right now.
            if (response.code == 404 || response.code == 400 || response.code == 429) {
                throw ModelUnavailableException(model, "модель $model недоступна (${response.code}): $detail")
            }
            throw IllegalStateException("Groq API ${response.code}: $detail")
        }

        val text = extractText(response.body)
        log("groq($model): ${text.take(300)}")
        return PlanCodec.decode(text, name)
    }

    private fun authHeaders(): Map<String, String> {
        val key = apiKeyProvider().trim()
        val headers = HashMap<String, String>()
        headers["content-type"] = "application/json"
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        return headers
    }

    /** Model ids the key can actually use today — shown in the app so nobody has to guess. */
    fun listModels(): List<String> {
        val url = baseUrl.substringBeforeLast("/chat/completions").trimEnd('/') + "/models"
        val response = transport.get(url, authHeaders())
        if (response.code !in 200..299) {
            throw IllegalStateException(
                "Groq /models ${response.code}: ${errorMessage(response.body).ifBlank { response.body.take(200) }}"
            )
        }
        return MiniJson.asArray(MiniJson.asObject(MiniJson.parseOrNull(response.body))["data"])
            .mapNotNull { MiniJson.str(MiniJson.asObject(it)["id"]) }
            .sorted()
    }

    private fun errorMessage(body: String): String {
        val error = MiniJson.asObject(MiniJson.asObject(MiniJson.parseOrNull(body))["error"])
        return MiniJson.str(error["message"]).orEmpty()
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
