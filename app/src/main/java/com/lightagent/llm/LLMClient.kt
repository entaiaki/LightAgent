package com.lightagent.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class LLMProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String
) {
    DEEPSEEK(
        displayName  = "DeepSeek",
        baseUrl      = "https://api.deepseek.com/v1/chat/completions",
        defaultModel = "deepseek-chat"
    ),
    OPENAI(
        displayName  = "OpenAI",
        baseUrl      = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-4o-mini"
    ),
    QWEN(
        displayName  = "通义千问",
        baseUrl      = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        defaultModel = "qwen-turbo"
    ),
    CUSTOM(
        displayName  = "自定义",
        baseUrl      = "",
        defaultModel = ""
    )
}

data class LLMConfig(
    val provider:    LLMProvider = LLMProvider.DEEPSEEK,
    val apiKey:      String      = "",
    val model:       String      = LLMProvider.DEEPSEEK.defaultModel,
    val customUrl:   String      = "",
    val temperature: Double      = 0.7,
    val maxTokens:   Int         = 2048
) {
    val endpoint: String
        get() {
            val raw = (if (provider == LLMProvider.CUSTOM) customUrl
                       else provider.baseUrl).trim().trimEnd('/')

            return if (raw.endsWith("/chat/completions")) raw
                   else "$raw/chat/completions"
        }
}

class LLMClient(private var config: LLMConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 流式需要更长超时
        .build()

    fun updateConfig(newConfig: LLMConfig) { config = newConfig }
    fun getConfig(): LLMConfig = config

    companion object {
        @Volatile private var INSTANCE: LLMClient? = null
        var apiKey: String = ""

        fun getInstance(context: Context? = null): LLMClient {
            return INSTANCE ?: synchronized(this) {
                val saved = context?.let { LLMConfigStore.load(it) }
                    ?: LLMConfig(apiKey = apiKey)
                LLMClient(saved).also { INSTANCE = it }
            }
        }

        fun applyConfig(context: Context, newConfig: LLMConfig) {
            LLMConfigStore.save(context, newConfig)
            INSTANCE?.updateConfig(newConfig)
        }
    }

    // ─── 非流式（原来的，保留兼容）──────────────────────────────────────────
    suspend fun chat(messages: List<Map<String, String>>): String =
        withContext(Dispatchers.IO) {
            val jsonArray = JSONArray()
            messages.forEach { msg ->
                jsonArray.put(JSONObject().apply {
                    put("role",    msg["role"]    ?: "user")
                    put("content", msg["content"] ?: "")
                })
            }
            callApi(jsonArray, stream = false)
        }

    suspend fun chat(prompt: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role",    "user")
                put("content", prompt)
            })
        }
        callApi(messages, stream = false)
    }

    suspend fun chatWithHistory(
        history:      List<Pair<String, String>>,
        systemPrompt: String = ""
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        if (systemPrompt.isNotEmpty()) {
            messages.put(JSONObject().apply {
                put("role",    "system")
                put("content", systemPrompt)
            })
        }
        history.forEach { (role, content) ->
            messages.put(JSONObject().apply {
                put("role",    role)
                put("content", content)
            })
        }
        callApi(messages, stream = false)
    }

    // ─── 流式接口（新增）：返回 Flow<String>，每次 emit 一个 chunk ──────────
    // 调用方边收边处理，不用等全部完成
    fun chatStream(messages: List<Map<String, String>>): Flow<String> = flow {
        if (config.apiKey.isBlank()) throw Exception("未填写 API Key")
        if (config.endpoint.isBlank()) throw Exception("未填写 API 地址")

        val jsonArray = JSONArray()
        messages.forEach { msg ->
            jsonArray.put(JSONObject().apply {
                put("role",    msg["role"]    ?: "user")
                put("content", msg["content"] ?: "")
            })
        }

        val body = JSONObject().apply {
            put("model",       config.model)
            put("messages",    jsonArray)
            put("temperature", config.temperature)
            put("max_tokens",  config.maxTokens)
            put("stream",      true)
        }

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type",  "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val err = response.body?.string() ?: ""
            throw Exception("API 请求失败：${response.code}\n$err")
        }

        val bodySource = response.body ?: throw Exception("响应体为空")
        val reader = bodySource.charStream().buffered()

        try {
            while (true) {
                val line = reader.readLine() ?: break

                if (!line.startsWith("data:")) continue

                val data = line.removePrefix("data:").trim()

                if (data == "[DONE]") break
                if (data.isBlank()) continue

                try {
                    val json = JSONObject(data)
                    val choice = json
                        .getJSONArray("choices")
                        .getJSONObject(0)

                    val delta = choice.optJSONObject("delta")
                    val content = delta?.optString("content", "") ?: ""

                    if (content.isNotEmpty()) {
                        emit(content)
                    }
                } catch (_: Exception) {
                    // 单行解析失败就跳过，不中断整条回复
                }
            }
        } finally {
            reader.close()
            bodySource.close()
        }
    }.flowOn(Dispatchers.IO)

    // ─── 非流式底层调用 ───────────────────────────────────────────────────────
    private fun callApi(messages: JSONArray, stream: Boolean = false): String {
        if (config.apiKey.isBlank()) throw Exception("未填写 API Key，请在设置中配置")
        if (config.endpoint.isBlank()) throw Exception("未填写 API 地址，请在设置中配置")

        val body = JSONObject().apply {
            put("model",       config.model)
            put("messages",    messages)
            put("temperature", config.temperature)
            put("max_tokens",  config.maxTokens)
            if (stream) put("stream", true)
        }

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type",  "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw Exception("API 请求失败：${response.code}\n$errorBody")
        }

        val responseBody = response.body?.string() ?: throw Exception("响应体为空")
        return try {
            JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            throw Exception("解析响应失败：${e.message}\n原始响应：$responseBody")
        }
    }
}
