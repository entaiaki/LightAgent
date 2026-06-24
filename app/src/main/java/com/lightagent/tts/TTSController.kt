package com.lightagent.tts

/**
 * TTS（文字转语音）控制器接口（预留，暂未实现）
 *
 * 接入 TTS 引擎时实现此接口，替换 [NoOpTTSController]
 *
 * 候选方案：
 *   - Android 系统 TTS（TextToSpeech）
 *   - Edge-TTS / VITS / GPT-SoVITS（本地或远程推理）
 *   - 云端 API：微软 Azure TTS、阿里云、字节豆包
 *
 * 与 Live2D 联动方式：
 *   在 [onPlaybackProgress] 回调中取音量值，传给 [Live2DController.setLipSync]
 */
interface TTSController {

    /** 是否正在播放 */
    val isSpeaking: Boolean

    /**
     * 初始化 TTS 引擎
     * @param onReady 引擎就绪回调
     * @param onError 初始化失败回调
     */
    fun init(onReady: () -> Unit = {}, onError: (Exception) -> Unit = {})

    /**
     * 朗读文本
     * @param text 要朗读的文字
     * @param onStart 开始播放回调
     * @param onPlaybackProgress 播放进度回调，返回当前音量（用于口型同步）
     * @param onDone 播放完成回调
     */
    fun speak(
        text: String,
        onStart: () -> Unit = {},
        onPlaybackProgress: (volume: Float) -> Unit = {},
        onDone: () -> Unit = {}
    )

    /** 停止当前播放 */
    fun stop()

    /**
     * 设置语速
     * @param rate 语速倍率，1.0f 为正常速度
     */
    fun setRate(rate: Float)

    /**
     * 设置音调
     * @param pitch 音调，1.0f 为正常
     */
    fun setPitch(pitch: Float)

    /**
     * 设置音色/角色
     * @param voiceId 音色 ID，具体值取决于 TTS 引擎
     */
    fun setVoice(voiceId: String)

    /** 释放资源 */
    fun release()
}

/**
 * 空实现，TTS 引擎未接入时使用
 */
class NoOpTTSController : TTSController {
    override val isSpeaking: Boolean = false
    override fun init(onReady: () -> Unit, onError: (Exception) -> Unit) { onReady() }
    override fun speak(
        text: String,
        onStart: () -> Unit,
        onPlaybackProgress: (Float) -> Unit,
        onDone: () -> Unit
    ) { onDone() }
    override fun stop() {}
    override fun setRate(rate: Float) {}
    override fun setPitch(pitch: Float) {}
    override fun setVoice(voiceId: String) {}
    override fun release() {}
}
