package com.lightagent

import android.app.Application
import com.lightagent.tts.KokoroTTSManager
import kotlinx.coroutines.*

/**
 * App 启动时在后台预加载 Kokoro 模型
 * 这样第一次说话时不会有明显延迟
 */
class LightAgentApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        CoroutineScope(Dispatchers.IO).launch {
            KokoroTTSManager.getInstance(this@LightAgentApplication).initialize()
        }
    }
}
