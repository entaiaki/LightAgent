package com.entaiaki.lightagentlive.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * sherpa-onnx TTS 控制器 — 离线文字转语音。
 *
 * 依赖: sherpa-onnx Java API JAR (需手动放入 app/libs/ 并添加到 build.gradle)
 * 当前为 stub 模式，isReady 始终 false，不阻塞编译。
 *
 * TODO: 添加 sherpa-onnx-java JAR 后启用真实实现
 */
class SherpaOnnxTTSController(private val context: Context) : TTSController {

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override val isReady: Boolean = false

    init {
        Log.w(TAG, "sherpa-onnx JAR not available — TTS running in stub mode")
    }

    override fun speak(text: String, onPlayback: (FloatArray, Int) -> Unit) {
        // Stub — 待 sherpa-onnx JAR 就位后启用
    }

    override suspend fun stopSpeaking() {
        _isSpeaking.value = false
    }

    override fun shutdown() {}

    companion object {
        private const val TAG = "SherpaOnnxTTS"
    }
}
