package com.lightagent

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.lightagent.llm.LLMClient
import com.lightagent.music.MusicPlaybackService
import com.lightagent.overlay.ChatOverlayController
import com.lightagent.overlay.DesktopAgentService
import com.lightagent.overlay.InteractionBridge
import com.lightagent.overlay.OverlayPermissionHelper
import com.lightagent.ui.screen.ChatScreen
import com.lightagent.ui.screen.SplashScreen
import com.lightagent.ui.theme.LightAgentTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 屏幕模式 */
enum class ScreenMode {
    /** 内屏展开 — 完整 UI：背景 + chat + 角色共存 */
    INNER_EXPANDED,
    /** 外屏/折叠 — 舞台窗口：只保留中间人物，左右裁剪 */
    OUTER_COMPACT,
    /** 无法确定 */
    UNKNOWN
}

/** 屏幕模式 CompositionLocal，供 GalGameChatLayout / FoldAwareContainer 读取 */
val LocalScreenMode = compositionLocalOf { ScreenMode.INNER_EXPANDED }

class MainActivity : ComponentActivity() {

    private val screenMode = mutableStateOf(ScreenMode.INNER_EXPANDED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LLMClient.getInstance(this)

        // ── 启动音乐后台服务 ──────────────────────────────────────
        startService(Intent(this, MusicPlaybackService::class.java))

        // ── 交互桥：桌宠点击 → 打开 App ──────────────────────────────
        InteractionBridge.onChatRequest = {
            openMainApp()  // 简化：点击桌宠 = 回 App
        }

        // ── 折叠屏检测：WindowInfoTracker + ratio fallback ────────────
        observeFoldingState()

        setContent {
            LightAgentTheme {
                CompositionLocalProvider(LocalScreenMode provides screenMode.value) {
                    var splashDone by remember { mutableStateOf(false) }
                    if (!splashDone) {
                        SplashScreen(onFinished = { splashDone = true })
                    } else {
                        ChatScreen()
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // WindowInfoTracker 自动推送
    }

    // ─── 折叠状态观察 ────────────────────────────────────────────────────
    private fun observeFoldingState() {
        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity)
                .windowLayoutInfo(this@MainActivity)
                .collectLatest { layoutInfo ->
                    val foldingFeature = layoutInfo.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()

                    screenMode.value = if (foldingFeature != null) {
                        when (foldingFeature.state) {
                            FoldingFeature.State.FLAT        -> ScreenMode.INNER_EXPANDED
                            FoldingFeature.State.HALF_OPENED -> ScreenMode.OUTER_COMPACT
                            else                             -> ScreenMode.INNER_EXPANDED
                        }
                    } else {
                        // 无折叠硬件 → 屏幕比例推断
                        detectScreenMode()
                    }
                }
        }
    }

    /** 非折叠设备：用屏幕比例判断横竖 */
    private fun detectScreenMode(): ScreenMode {
        val dm = resources.displayMetrics
        val ratio = dm.widthPixels.toFloat() / dm.heightPixels.toFloat()

        return when {
            // 竖屏/接近正方形 → 内屏展开
            ratio < 0.8f  -> ScreenMode.INNER_EXPANDED
            ratio <= 1.4f -> ScreenMode.INNER_EXPANDED
            // 明显横向 → 外屏紧凑
            ratio > 1.4f  -> ScreenMode.OUTER_COMPACT
            else          -> ScreenMode.UNKNOWN
        }
    }

    // ─── 生命周期：App 后台 → 显示桌宠 ───────────────────────────────────

    override fun onResume() {
        super.onResume()
        DesktopAgentService.hideOverlay(this)
        if (!OverlayPermissionHelper.hasPermission(this)) {
            showOverlayPermissionDialog()
        }
    }

    override fun onStop() {
        super.onStop()
        if (OverlayPermissionHelper.hasPermission(this)) {
            DesktopAgentService.start(this)
            DesktopAgentService.showOverlay(this)
        }
    }

    // ─── 权限弹窗 ─────────────────────────────────────────────────────────
    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("天爱星需要在桌面显示角色\n请在设置中授予「悬浮窗」权限")
            .setPositiveButton("去设置") { _, _ ->
                OverlayPermissionHelper.requestPermission(this)
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    /** 打开 App 自身（桌宠点击 → 回到主界面） */
    private fun openMainApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
    }
}
