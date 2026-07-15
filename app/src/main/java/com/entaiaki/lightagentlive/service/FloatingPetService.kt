package com.entaiaki.lightagentlive.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 桌宠悬浮窗服务 — 管理悬浮窗生命周期。
 *
 * TODO: Task 4.1 完整实现
 */
class FloatingPetService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
