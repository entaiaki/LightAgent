package com.lightagent.live2d

/**
 * Live2D 模型控制器接口（预留，暂未实现）
 *
 * 接入 Live2D SDK 时实现此接口，替换 [NoOpLive2DController]
 *
 * 典型用法：
 *   - 在 ChatScreen 的背景层插入 Live2DView（GLSurfaceView 或 TextureView）
 *   - 通过此接口驱动模型表情/动作，与 AI 回复联动
 */
interface Live2DController {

    /** 是否已加载模型 */
    val isReady: Boolean

    /**
     * 加载 Live2D 模型
     * @param modelPath assets 内模型路径，例如 "live2d/hiyori/hiyori.model3.json"
     */
    fun loadModel(modelPath: String)

    /**
     * 播放动作
     * @param group 动作组名，例如 "Idle"、"TapBody"
     * @param index 动作组内序号
     * @param priority 优先级：1=IDLE, 2=NORMAL, 3=FORCE
     */
    fun playMotion(group: String, index: Int, priority: Int = 2)

    /**
     * 设置表情
     * @param expressionId 表情 ID，例如 "smile"、"surprised"
     */
    fun setExpression(expressionId: String)

    /**
     * 驱动口型同步（配合 TTS 使用）
     * @param volume 当前音量 0.0f ~ 1.0f
     */
    fun setLipSync(volume: Float)

    /**
     * 视线跟随（跟随手指/固定点）
     * @param x 归一化 x 坐标 -1.0f ~ 1.0f
     * @param y 归一化 y 坐标 -1.0f ~ 1.0f
     */
    fun setEyeFollow(x: Float, y: Float)

    /** 释放资源 */
    fun release()
}

/**
 * 空实现，Live2D SDK 未接入时使用
 * 所有方法为无操作（NoOp），不影响主流程
 */
class NoOpLive2DController : Live2DController {
    override val isReady: Boolean = false
    override fun loadModel(modelPath: String) {}
    override fun playMotion(group: String, index: Int, priority: Int) {}
    override fun setExpression(expressionId: String) {}
    override fun setLipSync(volume: Float) {}
    override fun setEyeFollow(x: Float, y: Float) {}
    override fun release() {}
}
