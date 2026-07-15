package com.entaiaki.lightagent.live2d

interface Live2DController {
    fun loadModel(path: String)
    fun playMotion(name: String)
    fun setEmotion(emotion: String)
    fun setExpression(expression: String)
    fun startSpeaking()
    fun stopSpeaking()
    fun setLipSync(value: Float)
    fun setEyeFollow(x: Float, y: Float)
    fun release()
}
