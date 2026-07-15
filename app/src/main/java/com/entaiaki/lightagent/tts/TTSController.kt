package com.entaiaki.lightagent.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * TTS（文字转语音）控制器接口 — LightAgent-Live
 *
 * 职责：文字 → 音频播放，输出 PCM 数据供口型同步使用。
 */
interface TTSController {

    /** TTS 引擎是否已就绪 */
    val isReady: Boolean

    /** 当前是否正在播放语音（StateFlow，UI 可观察） */
    val isSpeaking: StateFlow<Boolean>

    /**
     * 开始朗读文本。
     * @param text 要朗读的文字
     * @param onPlayback 每生成一段音频时的回调，参数为 (PCM samples, sampleRate)
     */
    fun speak(text: String, onPlayback: (FloatArray, Int) -> Unit)

    /** 立即停止当前播放（挂起函数，等待引擎完全停止） */
    suspend fun stopSpeaking()

    /** 释放引擎资源 */
    fun shutdown()
}
