package com.entaiaki.lightagent

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.entaiaki.lightagent.agent.EmotionAnalyzer
import com.entaiaki.lightagent.live2d.CubismWebLive2DController
import com.entaiaki.lightagent.live2d.Live2DController
import com.entaiaki.lightagent.live2d.Live2DWebView
import com.entaiaki.lightagent.service.FloatingPetService
import com.entaiaki.lightagent.tts.SherpaOnnxTTSController
import com.entaiaki.lightagent.tts.TTSController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class Message(val role: String, val content: String)

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val inputText: String = "",
    val isFloatingMode: Boolean = false,
    val isSpeaking: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var live2dController: Live2DController? = null
    private var ttsController: TTSController? = null

    private val audioChannel = Channel<Pair<FloatArray, Int>>(Channel.UNLIMITED)

    init {
        startAudioPlaybackLoop()
    }

    // ========== Lifecycle ==========

    fun initTTS(context: Context) {
        if (ttsController == null) {
            val controller = SherpaOnnxTTSController(context)
            if (controller.isReady) {
                ttsController = controller
                observeTtsSpeakingState()
                Log.d(TAG, "TTS controller initialized successfully")
            } else {
                Log.w(TAG, "TTS init failed – running without speech")
            }
        }
    }

    fun attachLive2DController(controller: Live2DController) {
        live2dController = controller
    }

    fun detachLive2DController() {
        live2dController = null
    }

    fun attachLive2DWebView(webView: Live2DWebView) {
        live2dController = CubismWebLive2DController(webView)
    }

    // ========== Chat ==========

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        if (_uiState.value.isSpeaking) stopSpeaking()

        val userMsg = Message("user", text)
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                inputText = "",
                isLoading = true
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            streamChatResponse(text)
        }
    }

    private suspend fun streamChatResponse(userMessage: String) {
        val apiUrl = "http://192.168.1.8:11434/api/chat"
        val modelName = "qwen2.5:7b"

        val messagesArray = JSONArray()
        _uiState.value.messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        val requestBody = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("stream", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(apiUrl)
            .post(requestBody)
            .build()

        var fullResponse = ""
        val assistantPlaceholder = Message("assistant", "")
        _uiState.update { it.copy(messages = it.messages + assistantPlaceholder) }

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    updateLastMessage("Error: ${response.code}")
                    return
                }
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val json = JSONObject(line!!)
                    val delta = json.optJSONObject("message")?.optString("content", "") ?: ""
                    fullResponse += delta
                    updateLastMessage(fullResponse)
                    if (json.optBoolean("done", false)) break
                }
            }
        } catch (e: Exception) {
            updateLastMessage("Network error: ${e.message}")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
            if (fullResponse.isNotEmpty()) {
                speakAndAnimate(fullResponse)
            }
        }
    }

    // ========== TTS + Emotion + Lip Sync ==========

    private fun speakAndAnimate(text: String) {
        val controller = ttsController ?: return
        val emotion = EmotionAnalyzer.analyze(text)
        live2dController?.setEmotion(emotion)
        live2dController?.startSpeaking()
        controller.speak(text) { samples, sampleRate ->
            audioChannel.trySend(Pair(samples, sampleRate))
        }
    }

    fun stopSpeaking() {
        viewModelScope.launch {
            ttsController?.stopSpeaking()
            live2dController?.stopSpeaking()
        }
    }

    // ========== 音频播放 Loop ==========

    private fun startAudioPlaybackLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            for ((samples, sampleRate) in audioChannel) {
                playAudioSamples(samples, sampleRate)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun playAudioSamples(samples: FloatArray, sampleRate: Int) {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            audioTrack.play()
            audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            audioTrack.stop()
            audioTrack.release()
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback failed: ${e.message}")
        }
    }

    // ========== TTS 状态同步到 UI ==========

    private fun observeTtsSpeakingState() {
        viewModelScope.launch {
            ttsController?.isSpeaking?.collect { speaking ->
                _uiState.update { it.copy(isSpeaking = speaking) }
                if (!speaking) live2dController?.stopSpeaking()
            }
        }
    }

    // ========== Floating Pet ==========

    fun toggleFloatingMode(context: Context) {
        val isFloating = _uiState.value.isFloatingMode
        if (isFloating) {
            context.stopService(Intent(context, FloatingPetService::class.java))
            _uiState.update { it.copy(isFloatingMode = false) }
        } else {
            context.startForegroundService(Intent(context, FloatingPetService::class.java))
            _uiState.update { it.copy(isFloatingMode = true) }
        }
    }

    // ========== Helpers ==========

    private fun updateLastMessage(content: String) {
        _uiState.update { state ->
            val msgs = state.messages.toMutableList()
            if (msgs.isNotEmpty()) msgs[msgs.size - 1] = msgs.last().copy(content = content)
            state.copy(messages = msgs)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsController?.shutdown()
        audioChannel.close()
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
