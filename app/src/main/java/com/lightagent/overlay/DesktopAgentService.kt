package com.lightagent.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.view.WindowManager.LayoutParams
import android.util.TypedValue
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lightagent.MainActivity
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterStateHolder

class DesktopAgentService : Service() {

    private lateinit var windowManager: WindowManager
    private var petView: View? = null
    private val lifecycleOwner = OverlayLifecycleOwner()
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        lifecycleOwner.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground()
                val emotionName = intent.getStringExtra(EXTRA_EMOTION)
                if (emotionName != null) {
                    CharacterStateHolder.emotion = emotionToEnum(emotionName)
                }
                if (petView == null) {
                    buildPetView()
                }
                showPet()
            }

            ACTION_SHOW -> {
                ensureForeground()
                if (petView == null) buildPetView()
                showPet()
            }

            ACTION_HIDE -> {
                hidePet()
            }

            ACTION_UPDATE_EMOTION -> {
                val emotionName = intent.getStringExtra(EXTRA_EMOTION) ?: return START_STICKY
                CharacterStateHolder.emotion = emotionToEnum(emotionName)
            }

            ACTION_STOP -> {
                removePetView()
                PetCommandCenter.hide()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removePetView()
        ChatOverlayController.hide()
        lifecycleOwner.onDestroy()
    }

    private fun showPet() {
        PetCommandCenter.show()
    }

    private fun hidePet() {
        PetCommandCenter.hide()
    }

    /** 只移除桌宠窗口，不杀 Service，下次 resume→stop 循环会自然重建 */
    private fun dismissPetWindowOnly() {
        PetCommandCenter.hide()
        petView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        petView = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 构建桌宠 View — v4.3.6：极简触控（X 关闭 + 点角色回 App）
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildPetView() {
        val params = LayoutParams(
            dpToPx(90),
            dpToPx(160),
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dpToPx(16)
            y = dpToPx(80)
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                if (PetCommandCenter.isVisible) {
                    DesktopPetView()
                }
            }
        }

        // ── X 按钮命中判定 ──────────────────────────────────────────
        fun isInCloseZone(x: Float, y: Float): Boolean {
            val vw = composeView.width
            val vh = composeView.height
            if (vw <= 0 || vh <= 0) return false
            val size = dpToPx(30).toFloat()
            return x >= vw - size && y <= size
        }

        // ── 极简触控：点 X = 关闭，点角色 = 打开 App ──────────────
        composeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    if (isInCloseZone(event.x, event.y)) {
                        dismissPetWindowOnly()
                    } else {
                        openMainApp()
                    }
                }
            }
            true
        }

        petView = composeView
        windowManager.addView(composeView, params)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════════════════════════════════════

    private fun removePetView() {
        petView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        petView = null
        if (isForegroundStarted) {
            lifecycleOwner.onPause()
            lifecycleOwner.onStop()
            isForegroundStarted = false
        }
    }

    private fun openMainApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
    }

    private fun ensureForeground() {
        if (!isForegroundStarted) {
            startForeground(NOTIF_ID, buildNotification())
            isForegroundStarted = true
            lifecycleOwner.onStart()
            lifecycleOwner.onResume()
        }
    }

    private fun emotionToEnum(name: String): CharacterEmotion =
        try { CharacterEmotion.valueOf(name) } catch (_: Exception) { CharacterEmotion.IDLE }

    private fun buildNotification(): Notification {
        val channelId = "desktop_pet"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "桌宠服务", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("天爱星")
            .setContentText("桌宠运行中")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .build()
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    // ══════════════════════════════════════════════════════════════════════════
    // 同伴方法
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        private const val NOTIF_ID = 1001

        const val ACTION_START           = "action_start"
        const val ACTION_SHOW            = "action_show"
        const val ACTION_HIDE            = "action_hide"
        const val ACTION_UPDATE_EMOTION  = "action_update_emotion"
        const val ACTION_STOP            = "action_stop"
        const val EXTRA_EMOTION          = "extra_emotion"

        fun start(context: Context) {
            context.startService(
                Intent(context, DesktopAgentService::class.java).apply {
                    action = ACTION_START
                }
            )
        }

        fun showOverlay(context: Context) {
            context.startService(
                Intent(context, DesktopAgentService::class.java).apply {
                    action = ACTION_SHOW
                }
            )
        }

        fun hideOverlay(context: Context) {
            context.startService(
                Intent(context, DesktopAgentService::class.java).apply {
                    action = ACTION_HIDE
                }
            )
        }

        fun updateEmotion(context: Context, emotion: CharacterEmotion) {
            context.startService(
                Intent(context, DesktopAgentService::class.java).apply {
                    action = ACTION_UPDATE_EMOTION
                    putExtra(EXTRA_EMOTION, emotion.name)
                }
            )
        }
    }
}
