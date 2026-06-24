package com.lightagent.character

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 全局角色状态中心
 *
 * emotion — 当前情绪（App 内和桌宠共享）
 * role    — 当前角色 ID（映射到 CharacterPackRegistry）
 *
 * Usage:
 *   CharacterStateHolder.emotion = CharacterEmotion.HAPPY    // 直接赋值
 *   CharacterStateHolder.setEmotion(CharacterEmotion.HAPPY)  // 或方法调用
 */
object CharacterStateHolder {

    var emotion: CharacterEmotion by mutableStateOf(CharacterEmotion.IDLE)
    var role: String by mutableStateOf(CharacterPackRegistry.default.id)

    val currentPack: CharacterPack
        get() = CharacterPackRegistry.findById(role)
}
