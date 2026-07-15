package com.entaiaki.lightagentlive.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * TTS（文字转语音）控制器接口 — LightAgent-Live 重构版
 *
 * 职责：文字 → 音频播放，输出 PCM 数据供口型同步使用。
 * 不负责 UI，不负责音频路由管理。
 *
 * 实现方：SherpaOnnxTTSController（后续实现）
 */
interface TTSController {

    /** TTS 引擎是否已就绪（模型加载完成、可调用 speak） */
    val isReady: Boolean

    /** 当前是否正在播放语音（StateFlow，UI 可观察） */
    val isSpeaking: StateFlow<Boolean>

    /**
     * 开始朗读文本。
     *
     * @param text 要朗读的文字
     * @param onPlayback 每生成一段音频时的回调，参数为 (PCM samples, sampleRate)
     *                   PCM samples 为 FloatArray，值范围通常 [-1.0, 1.0]
     *                   sampleRate 为采样率（Hz），如 22050
     */
    fun speak(text: String, onPlayback: (FloatArray, Int) -> Unit)

    /** 立即停止当前播放（挂起函数，等待引擎完全停止） */
    suspend fun stopSpeaking()

    /** 释放引擎资源（模型卸载、内存回收） */
    fun shutdown()
}
