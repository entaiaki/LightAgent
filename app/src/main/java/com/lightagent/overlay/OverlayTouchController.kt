package com.lightagent.overlay

import android.view.MotionEvent
import kotlin.math.abs

/**
 * 悬浮窗 3 层触摸控制器 — v4.3.6
 *
 * ┌──────────────────────────┐
 * │ OverlayRoot              │
 * │  ┌──── Close(X) ──────┐ │  ← isOnCloseArea() 精确区域判断
 * │  └────────────────────┘ │
 * │  ┌── Character Layer ─┐ │  ← 单击 → onOpenChat
 * │  └────────────────────┘ │
 * │  Background (drag only) │  ← 拖拽 → FloatingTouchController
 * └──────────────────────────┘
 *
 * 防误触关闭（双保险）：
 *   #1 区域限制  — 手指必须在 isOnCloseArea() 范围内
 *   #2 明确点击  — dx<10px && dy<10px && dt<250ms（非拖动）
 *   #3 二次确认  — 第一次点击 X → toast；短时间内再点 X → 真正关闭
 *
 * 使用（替代 GestureDetector + setOnTouchListener）：
 *   composeView.setOnTouchListener { _, event -> touchController.onTouchEvent(event) }
 */
class OverlayTouchController(
    private val onOpenChat: () -> Unit,
    private val onClose: () -> Unit,
    private val onCloseHint: (() -> Unit)? = null,     // 第一次点X → 显示提示
    private val onDragMove: (dx: Float, dy: Float) -> Unit,
    private val onDragUp: () -> Unit = {},
    private val onLongPress: (() -> Unit)? = null,  // 长按身体区域（非 X）
    private val isOnCloseArea: (x: Float, y: Float) -> Boolean
) {
    private val floatTouch = FloatingTouchController(
        onMove = onDragMove,
        onUp   = onDragUp
    )

    // ── 点击状态 ──────────────────────────────────────────────────────
    private var downX      = 0f
    private var downY      = 0f
    private var downTime   = 0L
    private var longPressTriggered = false

    // ── 二次确认状态 ──────────────────────────────────────────────────
    private var closePending     = false
    private var closePendingTime = 0L

    companion object {
        const val CLICK_DIST_THRESHOLD  = 10f    // px
        const val CLICK_TIME_THRESHOLD  = 250L   // ms
        const val DOUBLE_CONFIRM_WINDOW = 3000L  // ms 内再次点击 X 生效
        const val LONG_PRESS_TIME       = 500L   // ms
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 公开入口
    // ══════════════════════════════════════════════════════════════════════════

    fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                downX      = event.x
                downY      = event.y
                downTime   = System.currentTimeMillis()
                longPressTriggered = false
                floatTouch.onTouchEvent(event)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                floatTouch.onTouchEvent(event)
                // 一旦拖动开始就取消关闭待确认
                if (floatTouch.dragging) cancelClosePending()

                // ── 长按检测：非 X 区域静置 ≥ LONG_PRESS_TIME ─────────
                if (!longPressTriggered && onLongPress != null) {
                    val dt = System.currentTimeMillis() - downTime
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx < CLICK_DIST_THRESHOLD &&
                        dy < CLICK_DIST_THRESHOLD &&
                        dt >= LONG_PRESS_TIME &&
                        !isOnCloseArea(event.x, event.y)) {
                        longPressTriggered = true
                        cancelClosePending()
                        onLongPress.invoke()
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                val dt = System.currentTimeMillis() - downTime

                val isStationary = dx < CLICK_DIST_THRESHOLD && dy < CLICK_DIST_THRESHOLD
                val isClick      = isStationary && dt < CLICK_TIME_THRESHOLD

                if (longPressTriggered) {
                    // 长按已触发 → 什么都不做
                } else if (isClick) {
                    handleClick(event)
                } else if (!isStationary || floatTouch.dragging) {
                    floatTouch.onTouchEvent(event)  // 惯性
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                floatTouch.onTouchEvent(event)
                cancelClosePending()
                true
            }

            else -> false
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 点击路由
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleClick(e: MotionEvent) {
        if (isOnCloseArea(e.x, e.y)) {
            handleCloseClick()
        } else {
            cancelClosePending()
            onOpenChat()
        }
    }

    private fun handleCloseClick() {
        val now = System.currentTimeMillis()

        // 二次确认生效中 → 执行关闭
        if (closePending && (now - closePendingTime) < DOUBLE_CONFIRM_WINDOW) {
            closePending = false
            onClose()
            return
        }

        // 第一次点击 X → 触发提示
        closePending = true
        closePendingTime = now
        onCloseHint?.invoke()
    }

    private fun cancelClosePending() {
        closePending = false
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 状态
    // ══════════════════════════════════════════════════════════════════════════

    val isDragging: Boolean get() = floatTouch.dragging
}
