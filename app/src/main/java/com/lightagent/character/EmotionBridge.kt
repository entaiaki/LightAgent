package com.lightagent.character

/**
 * 情绪桥接 — 将 LLM 解析出的情绪写入全局 CharacterStateHolder
 *
 * 调用点：ChatViewModel 收到流式回复后
 *
 * Usage:
 *   EmotionBridge.apply(parsed.emotion)
 */
object EmotionBridge {

    fun apply(emotion: CharacterEmotion) {
        CharacterStateHolder.emotion = emotion
    }
}
