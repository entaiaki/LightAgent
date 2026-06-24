package com.lightagent.overlay

import android.view.MotionEvent
import kotlin.math.max

/**
 * 桌宠拖拽 + 惯性控制器
 *
 * - ACTION_DOWN → 记录起始位置和时间
 * - ACTION_MOVE → 计算速度 + 增量回调
 * - ACTION_UP   → 惯性衰减动画 + 吸边
 *
 * 每 16ms 一帧（≈60fps），速度衰减系数 0.92
 */
class FloatingTouchController(
    private val onMove: (dx: Float, dy: Float) -> Unit,
    private val onUp  : () -> Unit = {}
) {
    private var lastX     = 0f
    private var lastY     = 0f
    private var lastTime  = 0L
    private var velocityX = 0f
    private var velocityY = 0f
    private var isDragging = false

    /** 拖拽阈值：移动超过此像素才算拖拽 */
    var dragThreshold = 12f

    fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX      = event.rawX
                lastY      = event.rawY
                lastTime   = System.currentTimeMillis()
                velocityX  = 0f
                velocityY  = 0f
                isDragging = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY

                if (!isDragging && (kotlin.math.abs(dx) > dragThreshold || kotlin.math.abs(dy) > dragThreshold)) {
                    isDragging = true
                }

                if (isDragging) {
                    val now = System.currentTimeMillis()
                    val dt  = max(1L, now - lastTime)

                    velocityX = dx / dt * 16f   // 归一化到 ~16ms 帧
                    velocityY = dy / dt * 16f

                    lastX   = event.rawX
                    lastY   = event.rawY
                    lastTime = now

                    onMove(dx, dy)
                }
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    startInertia()
                }
                onUp()
                true
            }

            else -> false
        }
    }

    /** 是否正在拖拽中（用于区分拖拽 vs 点击） */
    val dragging: Boolean get() = isDragging

    // ══════════════════════════════════════════════════════════════════════════
    // 惯性动画
    // ══════════════════════════════════════════════════════════════════════════

    private fun startInertia() {
        Thread {
            var vx = velocityX
            var vy = velocityY

            // 最多 60 帧（约 1 秒），速度指数衰减
            var running = true
            var frame = 0
            while (running && frame < 60) {
                vx *= 0.92f
                vy *= 0.92f

                if (kotlin.math.abs(vx) < 1f && kotlin.math.abs(vy) < 1f) {
                    running = false
                } else {
                    onMove(vx, vy)
                    Thread.sleep(16)
                }
                frame++
            }

            snapToEdge()
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 吸边
    // ══════════════════════════════════════════════════════════════════════════

    private fun snapToEdge() {
        // 预留：根据当前位置吸附到屏幕左/右边缘
    }
}
