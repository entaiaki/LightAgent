package com.lightagent.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView

/**
 * 悬浮聊天框控制器
 *
 * 点击桌宠 → ChatOverlayController.show(context) → 可输入聊天框弹出
 * 关闭按钮 → ChatOverlayController.hide()
 *
 * v4.3.1 改：FLAG_NOT_FOCUSABLE → FLAG_LAYOUT_NO_LIMITS
 *           + SOFT_INPUT_ADJUST_RESIZE（键盘弹出时自动压缩布局）
 */
object ChatOverlayController {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    fun show(context: Context) {
        if (overlayView != null) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val composeView = ComposeView(context).apply {
            setContent {
                ChatOverlay(onClose = { hide() })
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        overlayView = composeView
        wm.addView(composeView, params)
    }

    fun hide() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        windowManager = null
    }

    val isShowing: Boolean
        get() = overlayView != null
}
