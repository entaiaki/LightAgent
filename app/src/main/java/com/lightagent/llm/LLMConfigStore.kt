package com.lightagent.llm

import android.content.Context
import android.content.SharedPreferences

/**
 * LLM 配置持久化（SharedPreferences）
 */
object LLMConfigStore {
    private const val PREFS_NAME = "llm_config"
    private const val KEY_PROVIDER     = "provider"
    private const val KEY_API_KEY      = "api_key"
    private const val KEY_MODEL        = "model"
    private const val KEY_CUSTOM_URL   = "custom_url"
    private const val KEY_TEMPERATURE  = "temperature"
    private const val KEY_MAX_TOKENS   = "max_tokens"

    fun save(context: Context, config: LLMConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER,    config.provider.name)
            .putString(KEY_API_KEY,     config.apiKey)
            .putString(KEY_MODEL,       config.model)
            .putString(KEY_CUSTOM_URL,  config.customUrl)
            .putFloat(KEY_TEMPERATURE,  config.temperature.toFloat())
            .putInt(KEY_MAX_TOKENS,     config.maxTokens)
            .apply()
    }

    fun load(context: Context): LLMConfig {
        val p = prefs(context)
        val providerName = p.getString(KEY_PROVIDER, LLMProvider.DEEPSEEK.name) ?: LLMProvider.DEEPSEEK.name
        val provider = try { LLMProvider.valueOf(providerName) } catch (_: Exception) { LLMProvider.DEEPSEEK }
        return LLMConfig(
            provider    = provider,
            apiKey      = p.getString(KEY_API_KEY, "") ?: "",
            model       = p.getString(KEY_MODEL, provider.defaultModel) ?: provider.defaultModel,
            customUrl   = p.getString(KEY_CUSTOM_URL, "") ?: "",
            temperature = p.getFloat(KEY_TEMPERATURE, 0.7f).toDouble(),
            maxTokens   = p.getInt(KEY_MAX_TOKENS, 2048)
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
