package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.llm.LLMClient
import com.lightagent.llm.LLMConfig
import com.lightagent.llm.LLMConfigStore
import com.lightagent.llm.LLMProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LLMSettings(
    val apiKey         : String  = "",
    val baseUrl        : String  = "",
    val modelName      : String  = "",
    val temperature    : Float   = 0.7f,
    val maxTokens      : Int     = 2048,
    val stream         : Boolean = true,
    val contextEnabled : Boolean = true
)

class LLMSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settings = MutableStateFlow(loadFromStore())
    val settings: StateFlow<LLMSettings> = _settings.asStateFlow()

    private fun loadFromStore(): LLMSettings {
        val config = LLMConfigStore.load(getApplication())
        return LLMSettings(
            apiKey       = config.apiKey,
            baseUrl      = config.customUrl.ifBlank { providerBaseUrl(config.provider) },
            modelName    = config.model,
            temperature  = config.temperature.toFloat(),
            maxTokens    = config.maxTokens
        )
    }

    fun updateApiKey(v: String)      { _settings.value = _settings.value.copy(apiKey = v) }
    fun updateBaseUrl(v: String)     { _settings.value = _settings.value.copy(baseUrl = v) }
    fun updateModelName(v: String)   { _settings.value = _settings.value.copy(modelName = v) }
    fun updateTemperature(v: Float)  { _settings.value = _settings.value.copy(temperature = v) }
    fun updateMaxTokens(v: Int)      { _settings.value = _settings.value.copy(maxTokens = v) }
    fun updateStream(v: Boolean)     { _settings.value = _settings.value.copy(stream = v) }
    fun updateContext(v: Boolean)    { _settings.value = _settings.value.copy(contextEnabled = v) }

    fun save() {
        val s = _settings.value
        viewModelScope.launch {
            // 推断 provider
            val provider = when {
                s.baseUrl.contains("deepseek", ignoreCase = true) -> LLMProvider.DEEPSEEK
                s.baseUrl.contains("dashscope", ignoreCase = true) -> LLMProvider.QWEN
                else -> LLMProvider.CUSTOM
            }
            val config = LLMConfig(
                provider    = provider,
                apiKey      = s.apiKey,
                model       = s.modelName,
                customUrl   = s.baseUrl,
                temperature = s.temperature.toDouble(),
                maxTokens   = s.maxTokens
            )
            LLMConfigStore.save(getApplication(), config)
            LLMClient.getInstance().updateConfig(config)
        }
    }

    private fun providerBaseUrl(provider: LLMProvider): String = when (provider) {
        LLMProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
        LLMProvider.OPENAI   -> "https://api.openai.com/v1"
        LLMProvider.QWEN     -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
        LLMProvider.CUSTOM   -> ""
    }
}
