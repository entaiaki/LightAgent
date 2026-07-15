package com.entaiaki.lightagentlive.live2d

/**
 * Cubism Web Live2D 控制器 — 通过 WebView JS bridge 控制 Live2D 模型。
 *
 * TODO: Task 1.4 完整实现
 */
class CubismWebLive2DController(private val webView: Live2DWebView) : Live2DController {
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
