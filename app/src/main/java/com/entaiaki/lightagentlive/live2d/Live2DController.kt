package com.entaiaki.lightagentlive.live2d

/**
 * Live2D 控制器接口 — 控制 Live2D 角色的表情、动作、口型。
 * 实现方: CubismWebLive2DController (通过 WebView JS bridge)
 */
interface Live2DController {
    fun loadModel(path: String)
    fun playMotion(name: String)
    fun setEmotion(emotion: String)
    fun setExpression(expression: String)

    /** 开始说话 → 口型同步开 */
    fun startSpeaking()

    /** 停止说话 → 口型同步关 */
    fun stopSpeaking()

    fun setLipSync(value: Float)
    fun setEyeFollow(x: Float, y: Float)
    fun release()
}

/** 空实现，Live2D 未接入时使用 */
class NoOpLive2DController : Live2DController {
    override fun loadModel(path: String) {}
    override fun playMotion(name: String) {}
    override fun setEmotion(emotion: String) {}
    override fun setExpression(expression: String) {}
    override fun startSpeaking() {}
    override fun stopSpeaking() {}
    override fun setLipSync(value: Float) {}
    override fun setEyeFollow(x: Float, y: Float) {}
    override fun release() {}
}
