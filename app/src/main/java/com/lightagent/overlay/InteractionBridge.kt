package com.lightagent.overlay

/**
 * 交互桥 — 统一桌宠点击 → 聊天框弹出
 *
 * 注册：MainActivity.onCreate 中设置 onChatRequest
 * 触发：桌宠点击 → InteractionBridge.requestChat()
 */
object InteractionBridge {

    var onChatRequest: (() -> Unit)? = null

    fun requestChat() {
        onChatRequest?.invoke()
    }
}
