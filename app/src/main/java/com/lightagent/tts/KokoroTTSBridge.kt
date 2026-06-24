package com.lightagent.tts

import android.content.Context
import com.lightagent.tts.KokoroTTSManager

/**
 * Kokoro TTS 桥接 — 单例封装，简化调用
 *
 * Usage:
 *   // Application.onCreate() 时初始化一次
 *   KokoroTTSBridge.init(applicationContext)
 *
 *   // 任意位置播放
 *   KokoroTTSBridge.speak("你好")
 */
object KokoroTTSBridge {

    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    fun speak(text: String) {
        val context = ctx ?: return
        KokoroTTSManager.getInstance(context).feedStream(text)
    }
}
