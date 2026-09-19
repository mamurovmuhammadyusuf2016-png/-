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
    private val maxTokens: Int = 1536,
    private val log: (String) -> Unit = {}
) : Planner {

    companion object {
        /**
         * Free-tier models, best first for this job. Kimi and gpt-oss follow a strict output
         * format most reliably; the llamas are the fast, always-there fallback.
         */
        val DEFAULT_MODELS = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b"
        )

        /** Groq error codes that mean "this model, not this request". */
        private val RETRYABLE_CODES = setOf(
            "model_decommissioned", "model_not_found", "model_terminated",
            "model_not_active", "json_validate_failed"
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
                // gpt-oss bills its thinking against max_tokens; keep it out of the budget.
                "reasoning_effort" to "low",
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to Prompts.SYSTEM),
                    mapOf("role" to "user", "content" to Prompts.userMessage(request))
                )
            )
        )

        val response = transport.post(baseUrl, authHeaders(), payload)
        if (response.code !in 200..299) {
            val detail = redact(errorMessage(response.body).ifBlank { response.body.take(200) })
            val code = errorCode(response.body)
            // 404/429/413 are about this model. A 400 is only about the model when the
            // server says so — otherwise it is our request, and trying every other model
            // just hides the real error behind the last one's.
            val retryable = response.code == 404 || response.code == 429 || response.code == 413 ||
                (response.code == 400 && (code in RETRYABLE_CODES || detail.contains(model)))
            if (retryable) {
                throw ModelUnavailableException(model, "модель $model недоступна (${response.code}): $detail")
            }
            throw IllegalStateException("Groq API ${response.code}: $detail")
        }

        // A plan cut off by the token limit is not a plan; try a model with room.
        if (finishReason(response.body) == "length") {
            throw ModelUnavailableException(model, "ответ модели $model обрезан по длине")
        }

        val text = extractText(response.body)
        log("groq($model): ${redact(text.take(300))}")
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
        val root = if (baseUrl.contains("/chat/completions")) {
            baseUrl.substringBeforeLast("/chat/completions")
        } else {
            baseUrl.substringBeforeLast('/')
        }
        val url = root.trimEnd('/') + "/models"
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

    private fun errorCode(body: String): String {
        val error = MiniJson.asObject(MiniJson.asObject(MiniJson.parseOrNull(body))["error"])
        return MiniJson.str(error["code"]).orEmpty()
    }

    private fun finishReason(body: String): String {
        val root = MiniJson.asObject(MiniJson.parseOrNull(body))
        val first = MiniJson.asObject(MiniJson.asArray(root["choices"]).firstOrNull())
        return MiniJson.str(first["finish_reason"]).orEmpty()
    }

    /** An echoing proxy can put our own key in its response; it must never reach the log. */
    private fun redact(text: String): String =
        text.replace(Regex("gsk_[A-Za-z0-9_-]{8,}"), "gsk_***")

    /** Pulls `choices[0].message.content` out of a chat-completions response. */
    fun extractText(body: String): String {
        val root = MiniJson.asObject(MiniJson.parseOrNull(body))
        val choices = MiniJson.asArray(root["choices"])
        val first = MiniJson.asObject(choices.firstOrNull())
        val message = MiniJson.asObject(first["message"])
        return MiniJson.str(message["content"])?.takeIf { it.isNotBlank() } ?: body
    }
}
