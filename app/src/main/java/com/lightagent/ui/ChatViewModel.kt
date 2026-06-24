package com.lightagent.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterStateHolder
import com.lightagent.character.EmotionParser
import com.lightagent.llm.LLMClient
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ConversationEntity
import com.lightagent.memory.ConversationRepository
import com.lightagent.overlay.DesktopAgentService
import com.lightagent.tts.KokoroTTS
import com.lightagent.tts.KokoroTTSManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ChatMessage(
    val role: String,
    val content: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db   = AgentDatabase.getInstance(application)
    private val repo = ConversationRepository(db.conversationDao())
    private val llm  = LLMClient.getInstance(application)

    private val ttsManager: KokoroTTSManager by lazy {
        KokoroTTSManager.getInstance(application).also {
            it.initialize()
            Log.d("ChatVM", "KokoroTTSManager initialize() 已触发")
        }
    }
    val isTalking: StateFlow<Boolean> = ttsManager.isTalking

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    fun toggleTts() {
        _ttsEnabled.update { !it }
        if (!_ttsEnabled.value) ttsManager.stop()
    }

    /** 🔥 v4.4.3 诊断入口 */
    fun runTtsDiagnostics(text: String = "你好宝宝") {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("ChatVM", "══════════ TTS 诊断开始 ══════════")
            KokoroTTS.writeDebug("══════════ TTS 诊断开始 ══════════")

            // ① 全链路诊断
            val report = ttsManager.runDiagnosticsAndSpeak(text)
            Log.d("ChatVM", report.toPrettyString())
            KokoroTTS.writeDebug(report.toPrettyString())

            // ② 路由诊断
            ttsManager.logAudioRouting()

            // ③ 测试音验证
            KokoroTTS.writeDebug("── 播放 440Hz 测试音 ──")
            ttsManager.playTestTone()

            Log.d("ChatVM", "══════════ TTS 诊断结束 ══════════")
            KokoroTTS.writeDebug("══════════ TTS 诊断结束 ══════════")
        }
    }

    val conversations: StateFlow<List<ConversationEntity>> = repo.allConversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentEmotion = MutableStateFlow(CharacterEmotion.IDLE)
    val currentEmotion: StateFlow<CharacterEmotion> = _currentEmotion

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    private var streamJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttsManager.initialize()
            } catch (e: Exception) {
                Log.w("ChatVM", "Kokoro TTS 初始化异常", e)
            }
        }
        viewModelScope.launch {
            val all = conversations.first { true }
            if (all.isEmpty()) createNewConversation()
            else switchConversation(all.first())
        }
    }

    fun updateInput(value: String) { _input.value = value }

    fun createNewConversation() = viewModelScope.launch {
        val conv = repo.createConversation("New Chat")
        _currentConversationId.value = conv.id
        _messages.value = emptyList()
    }

    fun switchConversation(conv: ConversationEntity) = viewModelScope.launch {
        _currentConversationId.value = conv.id
        val history = repo.getMessagesOnce(conv.id)
        _messages.value = history.map { ChatMessage(it.role, it.content) }
    }

    fun deleteConversation(conv: ConversationEntity) = viewModelScope.launch {
        repo.deleteConversation(conv.id)
        if (_currentConversationId.value == conv.id) {
            val remaining = conversations.value.filter { it.id != conv.id }
            if (remaining.isEmpty()) createNewConversation()
            else switchConversation(remaining.first())
        }
    }

    // ─── 发送消息（流式版本·修复 history 重复）─────────────────────────
    fun send(userInput: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val convId = _currentConversationId.value ?: return@launch
            _isLoading.value = true
            _currentEmotion.value = CharacterEmotion.THINKING
            CharacterStateHolder.emotion = CharacterEmotion.THINKING
            ttsManager.stop()

            // ── 关键修复：先快照当前历史，再把用户消息加进去 ──────────────
            // buildHistory 用快照构建，不包含待发的用户消息（避免重复）
            val historySnapshot = _messages.value.toList()

            // 保存用户消息到数据库 + 更新 UI
            repo.saveMessage(convId, "user", userInput)
            _messages.value = _messages.value + ChatMessage("user", userInput)

            // 在末尾加空 assistant 占位（流式内容会实时更新这一条）
            _messages.value = _messages.value + ChatMessage("assistant", "")

            try {
                // 用快照构建 history，userInput 作为最后一条单独加入
                val history = buildHistory(historySnapshot, userInput)
                var fullText = ""

                llm.chatStream(history).collect { chunk ->
                    fullText += chunk

                    // 实时去掉情绪标签再显示
                    val displayText = fullText
                        .replace(Regex("\\[EMOTION:[a-zA-Z_\\u4e00-\\u9fa5]+]"), "")
                        .trimEnd()

                    _messages.value = _messages.value.toMutableList().also { list ->
                        list[list.lastIndex] = ChatMessage("assistant", displayText)
                    }

                    // 喂给 TTS（去掉情绪标签）
                    val cleanChunk = chunk.replace(
                        Regex("\\[EMOTION:[a-zA-Z_\\u4e00-\\u9fa5]+]"), ""
                    )
                    if (cleanChunk.isNotBlank() && _ttsEnabled.value) {
                        ttsManager.feedStream(cleanChunk)
                    }
                }

                // 流结束：解析情绪
                val parsed = EmotionParser.parse(fullText)
                _currentEmotion.value = parsed.emotion
                CharacterStateHolder.emotion = parsed.emotion
                DesktopAgentService.updateEmotion(getApplication(), parsed.emotion)

                // 更新最终干净文本
                _messages.value = _messages.value.toMutableList().also { list ->
                    list[list.lastIndex] = ChatMessage("assistant", parsed.cleanText)
                }

                // 保存到数据库
                repo.saveMessage(convId, "assistant", parsed.cleanText)

                // 把 TTS 缓冲区剩余内容也播掉
                if (_ttsEnabled.value) ttsManager.flushStream()

                // 自动命名会话（第一轮对话）
                if (_messages.value.size <= 2) {
                    repo.renameConversation(convId, userInput.take(20).ifBlank { "新对话" })
                }

            } catch (e: CancellationException) {
                ttsManager.stop()
                Log.d("ChatVM", "流式请求被取消")
            } catch (e: Exception) {
                val err = "错误：${e.message}"
                _currentEmotion.value = CharacterEmotion.IDLE
                CharacterStateHolder.emotion = CharacterEmotion.IDLE
                _messages.value = _messages.value.toMutableList().also { list ->
                    if (list.lastOrNull()?.role == "assistant") {
                        list[list.lastIndex] = ChatMessage("assistant", err)
                    } else {
                        list.add(ChatMessage("assistant", err))
                    }
                }
                repo.saveMessage(convId, "assistant", err)
                Log.e("ChatVM", "流式请求失败", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isBlank() || _isLoading.value) return
        _input.value = ""
        send(text)
    }

    fun stopGeneration() {
        streamJob?.cancel()
        ttsManager.stop()
        _isLoading.value = false
        _currentEmotion.value = CharacterEmotion.IDLE
    }

    // ── 关键修复：接收历史快照 + 当前用户消息，不从 _messages 读 ────────
    // 这样就不会把正在显示的 assistant 空占位也发给 LLM
    private fun buildHistory(
        snapshot: List<ChatMessage>,
        newUserMessage: String
    ): List<Map<String, String>> {
        val systemPrompt = """
            You are 天爱星，一个运行在 Android 上的 AI 助手，角色来自《败犬女主太多了》。
            性格：聪明、偶尔傲娇、对用户有点在意但嘴硬。
            请始终用中文回复用户。
            
            【重要】每条回复末尾必须附加情绪标签，格式：[EMOTION:情绪英文名]
            情绪选项（16种）：
            idle / happy / thinking / sad / angry / sleeping
            sobbing / crying / depressed / distressed / drowsy
            sweating / pained / disgusted / serious / wink
        """.trimIndent()

        val result = mutableListOf<Map<String, String>>()
        result.add(mapOf("role" to "system", "content" to systemPrompt))

        // 历史消息（最近20条，只取 user/assistant，过滤掉空内容）
        snapshot.takeLast(20)
            .filter { it.content.isNotBlank() }
            .forEach { msg ->
                result.add(mapOf("role" to msg.role, "content" to msg.content))
            }

        // 当前用户消息
        result.add(mapOf("role" to "user", "content" to newUserMessage))
        return result
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.release()
    }
}
