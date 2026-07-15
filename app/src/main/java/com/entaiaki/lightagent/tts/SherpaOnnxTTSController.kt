package com.entaiaki.lightagent.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * sherpa-onnx TTS 控制器。
 *
 * 依赖 sherpa-onnx JAR (app/libs/) + VITS 模型 (assets/tts/)。
 * 当前 stub：isReady=false，待模型 + JNI so 就位后启用。
 */
class SherpaOnnxTTSController(private val context: Context) : TTSController {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override val isReady: Boolean get() = _isSpeaking.value || ttsReady

    private var ttsReady = false
    private var speakingJob: Job? = null

    init {
        Log.w(TAG, "sherpa-onnx stub mode — add model + JNI libs to enable")
    }

    override fun speak(text: String, onPlayback: (FloatArray, Int) -> Unit) {
        if (!isReady) return
        speakingJob?.cancel()

        speakingJob = scope.launch {
            _isSpeaking.value = true
            try {
                val sentences = TtsSentenceSplitter.split(text)
                for (sentence in sentences) {
                    if (!_isSpeaking.value) break
                    // TODO: engine.generate(sentence, 0, 1.0f)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS generation failed: ${e.message}")
            } finally {
                _isSpeaking.value = false
            }
        }
    }

    override suspend fun stopSpeaking() {
        speakingJob?.cancelAndJoin()
        _isSpeaking.value = false
    }

    override fun shutdown() {
        speakingJob?.cancel()
    }

    companion object {
        private const val TAG = "SherpaOnnxTTS"
    }
}
