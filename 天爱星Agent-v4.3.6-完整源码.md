# 天爱星Agent v4.3.6 — 完整源码

---
## LightAgentApplication.kt
---

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


---
## MainActivity.kt
---

package com.lightagent

import android.app.AlertDialog
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
import com.lightagent.overlay.ChatOverlayController
import com.lightagent.overlay.DesktopAgentService
import com.lightagent.overlay.InteractionBridge
import com.lightagent.overlay.OverlayPermissionHelper
import com.lightagent.ui.screen.ChatScreen
import com.lightagent.ui.screen.SplashScreen
import com.lightagent.ui.theme.LightAgentTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 折叠屏状态 CompositionLocal，供子组件（GalGameChatLayout）读取 */
val LocalIsFolded = compositionLocalOf { false }

class MainActivity : ComponentActivity() {

    // 折叠屏状态：true = 折叠态（半开/闭合），false = 展开（全屏）
    private val isFolded = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LLMClient.getInstance(this)

        // ── 交互桥：桌宠点击 → 悬浮聊天框 ──────────────────────────────
        InteractionBridge.onChatRequest = {
            ChatOverlayController.show(applicationContext)
        }

        // ── 折叠屏检测：Jetpack WindowInfoTracker（官方 API）───────────
        observeFoldingState()

        setContent {
            LightAgentTheme {
                CompositionLocalProvider(LocalIsFolded provides isFolded.value) {
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

    // ─── 折叠屏切换：系统配置变化时触发，不重建 Activity ────────────────────
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // WindowInfoTracker 会自动推送新状态，无需手动检测
    }

    // ─── 折叠状态观察（Jetpack WindowManager 官方 API）────────────────────
    private fun observeFoldingState() {
        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity)
                .windowLayoutInfo(this@MainActivity)
                .collectLatest { layoutInfo ->
                    val foldingFeature = layoutInfo.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()

                    isFolded.value = if (foldingFeature != null) {
                        // HALF_OPENED 或 FLAT 以外 → 折叠态
                        foldingFeature.state != FoldingFeature.State.FLAT
                    } else {
                        // 无折叠特征 → 普通设备 → 从屏幕比例推断
                        fallbackDetectFolded()
                    }
                }
        }
    }

    /** 非折叠设备 fallback：用屏幕比例粗略判断横竖 */
    private fun fallbackDetectFolded(): Boolean {
        val dm = resources.displayMetrics
        val ratio = dm.widthPixels.toFloat() / dm.heightPixels.toFloat()
        // width > height → 横屏（类似折叠外屏体验）
        return ratio > 1.0f
    }

    override fun onResume() {
        super.onResume()
        DesktopAgentService.hideOverlay(this)

        if (!OverlayPermissionHelper.hasPermission(this)) {
            showOverlayPermissionDialog()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (OverlayPermissionHelper.hasPermission(this)) {
            DesktopAgentService.start(this)
            DesktopAgentService.showOverlay(this)
        }
    }

    // ─── 权限弹窗 ───────────────────────────────────────────────────────────
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
}


---
## agent\ChatState.kt
---

package com.lightagent.agent

sealed class ChatState {
    object Idle        : ChatState()
    object Thinking    : ChatState()
    object CallingTool : ChatState()
    data class Error(val message: String) : ChatState()
}


---
## agent\PlannerAgent.kt
---

package com.lightagent.agent

import android.content.Context
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ReminderRepository
import com.lightagent.tools.*
import com.lightagent.llm.LLMClient
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.EmotionParser

class PlannerAgent private constructor(
    private val llmClient: LLMClient,
    private val tools: List<Tool>,
    private val history: List<Map<String, String>>
) {
    companion object {
        fun create(
            context: Context,
            history: List<Map<String, String>> = emptyList()
        ): PlannerAgent {
            val db = AgentDatabase.getInstance(context)
            val reminderRepo = ReminderRepository(db.reminderDao())

            val tools = listOf(
                WeatherTool(),
                NoteTool(context),
                OpenAppTool(context),
                ReminderTool(context, reminderRepo)
            )

            val llmClient = LLMClient.getInstance(context)
            return PlannerAgent(llmClient, tools, history)
        }
    }

    private val systemPrompt = """
        You are 天爱星，一个运行在 Android 上的 AI 助手，角色来自《败犬女主太多了》。
        性格：聪明、偶尔傲娇、对用户有点在意但嘴硬。
        可用工具:
        1. get_weather(city) - 查询天气
        2. save_note(content) - 保存笔记
        3. open_app(package_name) - 打开应用
        4. add_reminder(title, note, datetime:"yyyy-MM-dd HH:mm") - 添加提醒
        使用工具时，先输出 TOOL:工具名，然后在下一行输出 PARAMS:{"key":"value"}。
        请始终用中文回复用户。
        
        【重要】每条回复末尾必须附加情绪标签，格式：[EMOTION:情绪英文名]
        情绪选项（16种）：
        idle(面无表情) / happy(微笑) / thinking(思考) / sad(伤心) / angry(生气) / sleeping(睡着)
        / sobbing(啜泣) / crying(大哭) / depressed(沮丧) / distressed(苦恼) / drowsy(困乏)
        / sweating(流汗) / pained(痛苦) / disgusted(嫌弃) / serious(严肃) / wink(眨眼笑)
        根据回复内容和语境选择最匹配的情绪。例如：
        - 好消息/肯定 → happy 或 wink
        - 需要思考/分析 → thinking
        - 坏消息/表达歉意 → sad 或 sobbing
        - 被用户冒犯 → angry 或 disgusted
        - 夜晚/睡前 → sleeping 或 drowsy
        - 惊讶/意外 → sweating
        - 严肃话题 → serious
        - 默认闲聊 → idle
    """.trimIndent()

    suspend fun chat(userMessage: String): String {
        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to systemPrompt))
        messages.addAll(history)
        messages.add(mapOf("role" to "user", "content" to userMessage))

        var response = llmClient.chat(messages)

        // 工具调用循环（最多 3 轮）
        repeat(3) {
            val toolName   = extractToolName(response)   ?: return response
            val toolParams = extractToolParams(response) ?: return response
            val tool = tools.find { it.name == toolName } ?: return response

            val toolResult = tool.execute(toolParams)
            messages.add(mapOf("role" to "assistant", "content" to response))
            messages.add(mapOf("role" to "tool", "content" to "Tool result: $toolResult"))
            response = llmClient.chat(messages)
        }

        return response
    }

    private fun extractToolName(response: String): String? {
        val line = response.lines().find { it.startsWith("TOOL:") } ?: return null
        return line.removePrefix("TOOL:").trim()
    }

    private fun extractToolParams(response: String): org.json.JSONObject? {
        val line = response.lines().find { it.startsWith("PARAMS:") } ?: return null
        return try {
            org.json.JSONObject(line.removePrefix("PARAMS:").trim())
        } catch (e: Exception) { null }
    }

    /**
     * 带情绪解析的聊天
     * LLM 输出末尾的 [EMOTION:xxx] 会被提取并映射为 [CharacterEmotion]
     */
    suspend fun chatWithEmotion(userMessage: String): AgentResponse {
        val rawResponse = chat(userMessage)
        val parsed = EmotionParser.parse(rawResponse)
        return AgentResponse(
            text    = parsed.cleanText,
            emotion = parsed.emotion
        )
    }

    data class AgentResponse(
        val text: String,
        val emotion: CharacterEmotion
    )
}


---
## character\CharacterEmotion.kt
---

package com.lightagent.character

/**
 * 天爱星的情绪状态（16 种）
 * 对应 res/drawable/tianaixing_*.png
 */
enum class CharacterEmotion(val assetName: String, val label: String) {
    IDLE("tianaixing_idle", "面无表情"),
    HAPPY("tianaixing_happy", "微笑"),
    THINKING("tianaixing_thinking", "思考"),
    SAD("tianaixing_sad", "伤心"),
    ANGRY("tianaixing_angry", "生气"),
    SLEEPING("tianaixing_sleeping", "睡着"),
    SOBBING("tianaixing_sobbing", "啜泣"),
    CRYING("tianaixing_crying", "大哭"),
    DEPRESSED("tianaixing_depressed", "沮丧"),
    DISTRESSED("tianaixing_distressed", "苦恼"),
    DROWSY("tianaixing_drowsy", "困乏"),
    SWEATING("tianaixing_sweating", "流汗"),
    PAINED("tianaixing_pained", "痛苦"),
    DISGUSTED("tianaixing_disgusted", "嫌弃"),
    SERIOUS("tianaixing_serious", "严肃"),
    WINK("tianaixing_wink", "眨眼笑");

    companion object {
        /**
         * 从LLM返回的情绪标签字符串解析枚举
         * 例如 "[EMOTION:happy]" → HAPPY
         */
        fun fromTag(tag: String): CharacterEmotion {
            val t = tag.trim().lowercase()
            return when {
                t.contains("happy") || t.contains("开心") || t.contains("高兴")
                    || t.contains("微笑") || t.contains("哈哈") || t.contains("棒")
                    || t.contains("眨眼笑") -> HAPPY
                t.contains("think") || t.contains("思考") || t.contains("不确定") -> THINKING
                t.contains("sad") || t.contains("伤心") || t.contains("难过")
                    || t.contains("悲") -> SAD
                t.contains("angry") || t.contains("生气") || t.contains("不满")
                    || t.contains("怒") -> ANGRY
                t.contains("sleep") || t.contains("睡") || t.contains("困乏")
                    || t.contains("drowsy") -> SLEEPING
                t.contains("sob") || t.contains("啜泣") || t.contains("抽泣") -> SOBBING
                t.contains("cry") || t.contains("大哭") || t.contains("痛哭") -> CRYING
                t.contains("depressed") || t.contains("沮丧") || t.contains("低落") -> DEPRESSED
                t.contains("distress") || t.contains("苦恼") || t.contains("烦")
                    || t.contains("困扰") -> DISTRESSED
                t.contains("sweat") || t.contains("流汗") || t.contains("冒汗")
                    || t.contains("汗") -> SWEATING
                t.contains("pain") || t.contains("痛苦") || t.contains("疼") -> PAINED
                t.contains("disgust") || t.contains("嫌弃") || t.contains("恶心")
                    || t.contains("讨厌") -> DISGUSTED
                t.contains("serious") || t.contains("严肃") || t.contains("正经") -> SERIOUS
                t.contains("wink") || t.contains("眨眼") || t.contains("调皮") -> WINK
                t.contains("惊讶") || t.contains("震惊") || t.contains("吓") -> SWEATING
                t.contains("害羞") || t.contains("脸红") -> HAPPY
                else -> IDLE
            }
        }
    }
}


---
## character\CharacterPack.kt
---

package com.lightagent.character

/**
 * 角色包定义
 *
 * 图片放在 assets/characters/{folder}/{情绪名小写}.png
 * 例：assets/characters/tianaixing/happy.png
 *
 * @param id        唯一标识（用于持久化）
 * @param name      显示名称
 * @param description 简短介绍
 * @param folder    assets/characters/ 下的子目录名
 * @param previewEmotion 预览时使用的情绪（默认 IDLE）
 * @param supportedEmotions 该套图支持的情绪集合（不支持的回退到 IDLE）
 */
data class CharacterPack(
    val id               : String,
    val name             : String,
    val description      : String,
    val folder           : String,
    val previewEmotion   : CharacterEmotion = CharacterEmotion.IDLE,
    val supportedEmotions: Set<CharacterEmotion> = CharacterEmotion.entries.toSet(),
    val assetExtension   : String = "png"
) {
    /**
     * 获取指定情绪对应的 assets 路径
     * 若该套图不支持此情绪，回退到 IDLE
     */
    fun assetPath(emotion: CharacterEmotion): String {
        val target = if (emotion in supportedEmotions) emotion else CharacterEmotion.IDLE
        return "characters/$folder/${target.name.lowercase()}.$assetExtension"
    }

    /** 预览图 assets 路径 */
    val previewAssetPath: String
        get() = assetPath(previewEmotion)
}

/**
 * 全局角色包注册表
 *
 * 新增角色只需要：
 *   1. 把图片放进 assets/characters/{folder}/
 *   2. 在这里 add 一个 CharacterPack
 *
 * 图片命名规范（16种，全小写）：
 *   idle.png  happy.png  thinking.png  sad.png  angry.png
 *   sleeping.png  sobbing.png  crying.png  depressed.png
 *   distressed.png  drowsy.png  sweating.png  pained.png
 *   disgusted.png  serious.png  wink.png
 */
object CharacterPackRegistry {

    val packs: List<CharacterPack> = listOf(

        // ── 天爱星（默认，原版）──────────────────────────────────────────
        CharacterPack(
            id          = "tianaixing",
            name        = "天爱星",
            description = "来自《败犬女主太多了》\n聪明、偶尔傲娇",
            folder      = "tianaixing"
        ),

        // ── 立绘1（完整16情绪）──────────────────────────────────────────
        CharacterPack(
            id          = "illust1",
            name        = "立绘1",
            description = "立绘包1\n全套16种情绪",
            folder      = "illust1",
            previewEmotion = CharacterEmotion.HAPPY,
            assetExtension = "jpg"
        ),

        // ── 立绘2（12情绪，缺 thinking/sleeping/sweating/pained）─────────
        CharacterPack(
            id          = "illust2",
            name        = "立绘2",
            description = "立绘包2\n12种情绪",
            folder      = "illust2",
            previewEmotion = CharacterEmotion.HAPPY,
            assetExtension = "jpg",
            supportedEmotions = setOf(
                CharacterEmotion.IDLE, CharacterEmotion.HAPPY,
                CharacterEmotion.SAD, CharacterEmotion.ANGRY,
                CharacterEmotion.SOBBING, CharacterEmotion.CRYING,
                CharacterEmotion.DROWSY, CharacterEmotion.DEPRESSED,
                CharacterEmotion.DISGUSTED, CharacterEmotion.DISTRESSED,
                CharacterEmotion.SERIOUS, CharacterEmotion.WINK
            )
        ),
    )

    val default: CharacterPack get() = packs.first()

    fun findById(id: String): CharacterPack =
        packs.find { it.id == id } ?: default
}


---
## character\CharacterPackManager.kt
---

package com.lightagent.character

import android.content.Context
import android.content.SharedPreferences

/**
 * 当前角色包持久化（SharedPreferences）
 * 与 LLMConfigStore 同一思路，保持一致性
 */
object CharacterPackManager {

    private const val PREFS_NAME  = "character_pack"
    private const val KEY_PACK_ID = "selected_pack_id"

    fun save(context: Context, pack: CharacterPack) {
        prefs(context).edit()
            .putString(KEY_PACK_ID, pack.id)
            .apply()
    }

    fun load(context: Context): CharacterPack {
        val id = prefs(context).getString(KEY_PACK_ID, CharacterPackRegistry.default.id)
            ?: CharacterPackRegistry.default.id
        return CharacterPackRegistry.findById(id)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}


---
## character\CharacterPlaceholder.kt
---

package com.lightagent.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 立绘图片还没准备好时的占位组件
 * 显示情绪名称和 emoji，用于验证切换逻辑
 */
@Composable
fun CharacterPlaceholder(
    emotion: CharacterEmotion,
    modifier: Modifier = Modifier
) {
    val bgColor = when (emotion) {
        CharacterEmotion.IDLE       -> Color(0xFF2A2A4A)
        CharacterEmotion.HAPPY      -> Color(0xFF2A4A2A)
        CharacterEmotion.THINKING   -> Color(0xFF2A3A4A)
        CharacterEmotion.SAD        -> Color(0xFF3A4A6A)
        CharacterEmotion.ANGRY      -> Color(0xFF4A2A2A)
        CharacterEmotion.SLEEPING   -> Color(0xFF1A1A2A)
        CharacterEmotion.SOBBING    -> Color(0xFF3A4A6A)
        CharacterEmotion.CRYING     -> Color(0xFF2A4A6A)
        CharacterEmotion.DEPRESSED  -> Color(0xFF3A3A5A)
        CharacterEmotion.DISTRESSED -> Color(0xFF4A3A3A)
        CharacterEmotion.DROWSY     -> Color(0xFF2A2A3A)
        CharacterEmotion.SWEATING   -> Color(0xFF4A4A3A)
        CharacterEmotion.PAINED     -> Color(0xFF4A2A3A)
        CharacterEmotion.DISGUSTED  -> Color(0xFF3A4A2A)
        CharacterEmotion.SERIOUS    -> Color(0xFF2A2A4A)
        CharacterEmotion.WINK       -> Color(0xFF2A4A2A)
    }

    val emoji = when (emotion) {
        CharacterEmotion.IDLE       -> "😐"
        CharacterEmotion.HAPPY      -> "😊"
        CharacterEmotion.THINKING   -> "🤔"
        CharacterEmotion.SAD        -> "😢"
        CharacterEmotion.ANGRY      -> "😤"
        CharacterEmotion.SLEEPING   -> "😴"
        CharacterEmotion.SOBBING    -> "🥺"
        CharacterEmotion.CRYING     -> "😭"
        CharacterEmotion.DEPRESSED  -> "😞"
        CharacterEmotion.DISTRESSED -> "😣"
        CharacterEmotion.DROWSY     -> "🥱"
        CharacterEmotion.SWEATING   -> "😅"
        CharacterEmotion.PAINED     -> "😖"
        CharacterEmotion.DISGUSTED  -> "😒"
        CharacterEmotion.SERIOUS    -> "🧐"
        CharacterEmotion.WINK       -> "😉"
    }

    Box(
        modifier = modifier
            .padding(bottom = 32.dp)
            .background(bgColor, RoundedCornerShape(24.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 64.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "天爱星\n${emotion.label}",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "立绘就位\n（${emotion.assetName}.png）",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}


---
## character\CharacterStateHolder.kt
---

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


---
## character\CharacterView.kt
---

package com.lightagent.character

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 天爱星立绘组件 — v4.0 多角色包版本
 *
 * 图片来源从 R.drawable 改为 assets/characters/{pack.folder}/{emotion}.png
 * 好处：添加新角色无需重新编译，只要放图片 + 注册 CharacterPack 即可
 *
 * 动画（呼吸浮动、说话脉冲）由外层 GalGameChatLayout 统一驱动。
 */
@Composable
fun CharacterView(
    emotion      : CharacterEmotion,
    pack         : CharacterPack    = CharacterPackRegistry.default,
    modifier     : Modifier         = Modifier,
    isTalking    : Boolean          = false,
    contentScale : ContentScale     = ContentScale.Fit
) {
    val context = LocalContext.current

    // 用 pack + emotion 作为 key，切换时重新解码
    val bitmap = remember(pack.id, emotion) {
        runCatching {
            context.assets.open(pack.assetPath(emotion)).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap             = bitmap.asImageBitmap(),
            contentDescription = "${pack.name} - ${emotion.label}",
            modifier           = modifier,
            contentScale       = contentScale,
            alignment          = Alignment.BottomCenter
        )
    } else {
        // assets 中找不到图片时显示占位，避免白屏
        CharacterFallbackPlaceholder(
            pack     = pack,
            emotion  = emotion,
            modifier = modifier
        )
    }
}

/**
 * 找不到立绘时的兜底占位
 * 显示角色名 + 情绪 emoji，确保 UI 不崩溃
 */
@Composable
private fun CharacterFallbackPlaceholder(
    pack    : CharacterPack,
    emotion : CharacterEmotion,
    modifier: Modifier = Modifier
) {
    val emoji = when (emotion) {
        CharacterEmotion.IDLE       -> "\uD83D\uDE10"
        CharacterEmotion.HAPPY      -> "\uD83D\uDE0A"
        CharacterEmotion.THINKING   -> "\uD83E\uDD14"
        CharacterEmotion.SAD        -> "\uD83D\uDE22"
        CharacterEmotion.ANGRY      -> "\uD83D\uDE24"
        CharacterEmotion.SLEEPING   -> "\uD83D\uDE34"
        CharacterEmotion.SOBBING    -> "\uD83E\uDD7A"
        CharacterEmotion.CRYING     -> "\uD83D\uDE2D"
        CharacterEmotion.DEPRESSED  -> "\uD83D\uDE1E"
        CharacterEmotion.DISTRESSED -> "\uD83D\uDE30"
        CharacterEmotion.DROWSY     -> "\uD83D\uDE2A"
        CharacterEmotion.SWEATING   -> "\uD83D\uDE05"
        CharacterEmotion.PAINED     -> "\uD83D\uDE16"
        CharacterEmotion.DISGUSTED  -> "\uD83D\uDE12"
        CharacterEmotion.SERIOUS    -> "\uD83E\uDDD0"
        CharacterEmotion.WINK       -> "\uD83D\uDE09"
    }

    Box(
        modifier         = modifier
            .background(Color(0xFF1A1A2E), RoundedCornerShape(16.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 56.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text     = pack.name,
                color    = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
            Text(
                text     = emotion.label,
                color    = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp
            )
        }
    }
}


---
## character\EmotionBridge.kt
---

package com.lightagent.character

/**
 * 情绪桥接 — 将 LLM 解析出的情绪写入全局 CharacterStateHolder
 *
 * 调用点：ChatViewModel 收到流式回复后
 *
 * Usage:
 *   EmotionBridge.apply(parsed.emotion)
 */
object EmotionBridge {

    fun apply(emotion: CharacterEmotion) {
        CharacterStateHolder.emotion = emotion
    }
}


---
## character\EmotionParser.kt
---

package com.lightagent.character

/**
 * 从LLM返回的文本中提取情绪标签，并清理掉标签本身
 * LLM输出格式约定：在回复末尾加 [EMOTION:xxx]
 * 例如："好的，明天天气晴朗！[EMOTION:happy]"
 */
object EmotionParser {

    private val EMOTION_REGEX = Regex("\\[EMOTION:([a-zA-Z_\\u4e00-\\u9fa5]+)]")

    data class ParseResult(
        val cleanText: String,
        val emotion: CharacterEmotion
    )

    fun parse(rawText: String): ParseResult {
        val match = EMOTION_REGEX.find(rawText)
        return if (match != null) {
            ParseResult(
                cleanText = rawText.replace(match.value, "").trim(),
                emotion = CharacterEmotion.fromTag(match.groupValues[1])
            )
        } else {
            ParseResult(
                cleanText = rawText,
                emotion = guessEmotion(rawText)
            )
        }
    }

    /**
     * LLM 没带标签时的关键词兜底猜测
     */
    private fun guessEmotion(text: String): CharacterEmotion {
        return when {
            text.contains(Regex("哈哈|太好了|棒|开心|好的！|没问题|✅|微笑|眨眼"))
                -> CharacterEmotion.HAPPY
            text.contains(Regex("嗯…|让我想想|稍等|不确定|可能|考虑"))
                -> CharacterEmotion.THINKING
            text.contains(Regex("抱歉|对不起|难过|伤心|遗憾|哭|😢|悲"))
                -> CharacterEmotion.SAD
            text.contains(Regex("生气|怒|不满意|❌|不行|可恶"))
                -> CharacterEmotion.ANGRY
            text.contains(Regex("困|累了|睡觉|晚安|晚安|😴"))
                -> CharacterEmotion.SLEEPING
            text.contains(Regex("哎呀|没想到|什么！|居然|震惊|吓"))
                -> CharacterEmotion.SWEATING
            text.contains(Regex("烦|讨厌|嫌弃|恶心|🤢"))
                -> CharacterEmotion.DISGUSTED
            text.contains(Regex("痛苦|疼|难受|折磨"))
                -> CharacterEmotion.PAINED
            text.contains(Regex("严肃|认真|重要|注意"))
                -> CharacterEmotion.SERIOUS
            else -> CharacterEmotion.IDLE
        }
    }
}


---
## character\RoleManager.kt
---

package com.lightagent.character

/**
 * 角色切换管理器
 *
 * 委托给 CharacterPackRegistry，保持数据定义在注册表一处。
 * 新增角色只需在 CharacterPackRegistry.packs 里加一行。
 */
object RoleManager {

    /** 所有可用角色 ID 列表 */
    val roles: List<String>
        get() = CharacterPackRegistry.packs.map { it.id }

    /** 切换到指定角色 */
    fun switch(roleId: String) {
        val exists = CharacterPackRegistry.packs.any { it.id == roleId }
        if (exists) {
            CharacterStateHolder.role = roleId
        }
    }
}


---
## live2d\Live2DController.kt
---

package com.lightagent.live2d

/**
 * Live2D 模型控制器接口（预留，暂未实现）
 *
 * 接入 Live2D SDK 时实现此接口，替换 [NoOpLive2DController]
 *
 * 典型用法：
 *   - 在 ChatScreen 的背景层插入 Live2DView（GLSurfaceView 或 TextureView）
 *   - 通过此接口驱动模型表情/动作，与 AI 回复联动
 */
interface Live2DController {

    /** 是否已加载模型 */
    val isReady: Boolean

    /**
     * 加载 Live2D 模型
     * @param modelPath assets 内模型路径，例如 "live2d/hiyori/hiyori.model3.json"
     */
    fun loadModel(modelPath: String)

    /**
     * 播放动作
     * @param group 动作组名，例如 "Idle"、"TapBody"
     * @param index 动作组内序号
     * @param priority 优先级：1=IDLE, 2=NORMAL, 3=FORCE
     */
    fun playMotion(group: String, index: Int, priority: Int = 2)

    /**
     * 设置表情
     * @param expressionId 表情 ID，例如 "smile"、"surprised"
     */
    fun setExpression(expressionId: String)

    /**
     * 驱动口型同步（配合 TTS 使用）
     * @param volume 当前音量 0.0f ~ 1.0f
     */
    fun setLipSync(volume: Float)

    /**
     * 视线跟随（跟随手指/固定点）
     * @param x 归一化 x 坐标 -1.0f ~ 1.0f
     * @param y 归一化 y 坐标 -1.0f ~ 1.0f
     */
    fun setEyeFollow(x: Float, y: Float)

    /** 释放资源 */
    fun release()
}

/**
 * 空实现，Live2D SDK 未接入时使用
 * 所有方法为无操作（NoOp），不影响主流程
 */
class NoOpLive2DController : Live2DController {
    override val isReady: Boolean = false
    override fun loadModel(modelPath: String) {}
    override fun playMotion(group: String, index: Int, priority: Int) {}
    override fun setExpression(expressionId: String) {}
    override fun setLipSync(volume: Float) {}
    override fun setEyeFollow(x: Float, y: Float) {}
    override fun release() {}
}


---
## llm\LLMClient.kt
---

package com.lightagent.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class LLMProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String
) {
    DEEPSEEK(
        displayName  = "DeepSeek",
        baseUrl      = "https://api.deepseek.com/v1/chat/completions",
        defaultModel = "deepseek-chat"
    ),
    OPENAI(
        displayName  = "OpenAI",
        baseUrl      = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-4o-mini"
    ),
    QWEN(
        displayName  = "通义千问",
        baseUrl      = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        defaultModel = "qwen-turbo"
    ),
    CUSTOM(
        displayName  = "自定义",
        baseUrl      = "",
        defaultModel = ""
    )
}

data class LLMConfig(
    val provider:    LLMProvider = LLMProvider.DEEPSEEK,
    val apiKey:      String      = "",
    val model:       String      = LLMProvider.DEEPSEEK.defaultModel,
    val customUrl:   String      = "",
    val temperature: Double      = 0.7,
    val maxTokens:   Int         = 2048
) {
    val endpoint: String
        get() {
            val raw = (if (provider == LLMProvider.CUSTOM) customUrl
                       else provider.baseUrl).trim().trimEnd('/')

            return if (raw.endsWith("/chat/completions")) raw
                   else "$raw/chat/completions"
        }
}

class LLMClient(private var config: LLMConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 流式需要更长超时
        .build()

    fun updateConfig(newConfig: LLMConfig) { config = newConfig }
    fun getConfig(): LLMConfig = config

    companion object {
        @Volatile private var INSTANCE: LLMClient? = null
        var apiKey: String = ""

        fun getInstance(context: Context? = null): LLMClient {
            return INSTANCE ?: synchronized(this) {
                val saved = context?.let { LLMConfigStore.load(it) }
                    ?: LLMConfig(apiKey = apiKey)
                LLMClient(saved).also { INSTANCE = it }
            }
        }

        fun applyConfig(context: Context, newConfig: LLMConfig) {
            LLMConfigStore.save(context, newConfig)
            INSTANCE?.updateConfig(newConfig)
        }
    }

    // ─── 非流式（原来的，保留兼容）──────────────────────────────────────────
    suspend fun chat(messages: List<Map<String, String>>): String =
        withContext(Dispatchers.IO) {
            val jsonArray = JSONArray()
            messages.forEach { msg ->
                jsonArray.put(JSONObject().apply {
                    put("role",    msg["role"]    ?: "user")
                    put("content", msg["content"] ?: "")
                })
            }
            callApi(jsonArray, stream = false)
        }

    suspend fun chat(prompt: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role",    "user")
                put("content", prompt)
            })
        }
        callApi(messages, stream = false)
    }

    suspend fun chatWithHistory(
        history:      List<Pair<String, String>>,
        systemPrompt: String = ""
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        if (systemPrompt.isNotEmpty()) {
            messages.put(JSONObject().apply {
                put("role",    "system")
                put("content", systemPrompt)
            })
        }
        history.forEach { (role, content) ->
            messages.put(JSONObject().apply {
                put("role",    role)
                put("content", content)
            })
        }
        callApi(messages, stream = false)
    }

    // ─── 流式接口（新增）：返回 Flow<String>，每次 emit 一个 chunk ──────────
    // 调用方边收边处理，不用等全部完成
    fun chatStream(messages: List<Map<String, String>>): Flow<String> = flow {
        if (config.apiKey.isBlank()) throw Exception("未填写 API Key")
        if (config.endpoint.isBlank()) throw Exception("未填写 API 地址")

        val jsonArray = JSONArray()
        messages.forEach { msg ->
            jsonArray.put(JSONObject().apply {
                put("role",    msg["role"]    ?: "user")
                put("content", msg["content"] ?: "")
            })
        }

        val body = JSONObject().apply {
            put("model",       config.model)
            put("messages",    jsonArray)
            put("temperature", config.temperature)
            put("max_tokens",  config.maxTokens)
            put("stream",      true)
        }

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type",  "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val err = response.body?.string() ?: ""
            throw Exception("API 请求失败：${response.code}\n$err")
        }

        val bodySource = response.body ?: throw Exception("响应体为空")
        val reader = bodySource.charStream().buffered()

        try {
            while (true) {
                val line = reader.readLine() ?: break

                if (!line.startsWith("data:")) continue

                val data = line.removePrefix("data:").trim()

                if (data == "[DONE]") break
                if (data.isBlank()) continue

                try {
                    val json = JSONObject(data)
                    val choice = json
                        .getJSONArray("choices")
                        .getJSONObject(0)

                    val delta = choice.optJSONObject("delta")
                    val content = delta?.optString("content", "") ?: ""

                    if (content.isNotEmpty()) {
                        emit(content)
                    }
                } catch (_: Exception) {
                    // 单行解析失败就跳过，不中断整条回复
                }
            }
        } finally {
            reader.close()
            bodySource.close()
        }
    }.flowOn(Dispatchers.IO)

    // ─── 非流式底层调用 ───────────────────────────────────────────────────────
    private fun callApi(messages: JSONArray, stream: Boolean = false): String {
        if (config.apiKey.isBlank()) throw Exception("未填写 API Key，请在设置中配置")
        if (config.endpoint.isBlank()) throw Exception("未填写 API 地址，请在设置中配置")

        val body = JSONObject().apply {
            put("model",       config.model)
            put("messages",    messages)
            put("temperature", config.temperature)
            put("max_tokens",  config.maxTokens)
            if (stream) put("stream", true)
        }

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type",  "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw Exception("API 请求失败：${response.code}\n$errorBody")
        }

        val responseBody = response.body?.string() ?: throw Exception("响应体为空")
        return try {
            JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            throw Exception("解析响应失败：${e.message}\n原始响应：$responseBody")
        }
    }
}


---
## llm\LLMConfigStore.kt
---

package com.lightagent.llm

import android.content.Context
import android.content.SharedPreferences

/**
 * LLM 配置持久化（SharedPreferences）
 */
object LLMConfigStore {
    private const val PREFS_NAME = "llm_config"
    private const val KEY_PROVIDER     = "provider"
    private const val KEY_API_KEY      = "api_key"
    private const val KEY_MODEL        = "model"
    private const val KEY_CUSTOM_URL   = "custom_url"
    private const val KEY_TEMPERATURE  = "temperature"
    private const val KEY_MAX_TOKENS   = "max_tokens"

    fun save(context: Context, config: LLMConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER,    config.provider.name)
            .putString(KEY_API_KEY,     config.apiKey)
            .putString(KEY_MODEL,       config.model)
            .putString(KEY_CUSTOM_URL,  config.customUrl)
            .putFloat(KEY_TEMPERATURE,  config.temperature.toFloat())
            .putInt(KEY_MAX_TOKENS,     config.maxTokens)
            .apply()
    }

    fun load(context: Context): LLMConfig {
        val p = prefs(context)
        val providerName = p.getString(KEY_PROVIDER, LLMProvider.DEEPSEEK.name) ?: LLMProvider.DEEPSEEK.name
        val provider = try { LLMProvider.valueOf(providerName) } catch (_: Exception) { LLMProvider.DEEPSEEK }
        return LLMConfig(
            provider    = provider,
            apiKey      = p.getString(KEY_API_KEY, "") ?: "",
            model       = p.getString(KEY_MODEL, provider.defaultModel) ?: provider.defaultModel,
            customUrl   = p.getString(KEY_CUSTOM_URL, "") ?: "",
            temperature = p.getFloat(KEY_TEMPERATURE, 0.7f).toDouble(),
            maxTokens   = p.getInt(KEY_MAX_TOKENS, 2048)
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}


---
## memory\ConversationDao.kt
---

package com.lightagent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    // ── 会话 CRUD ────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationTitle(id: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    // ── 消息 CRUD ────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationOnce(conversationId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
}


---
## memory\ConversationEntity.kt
---

package com.lightagent.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


---
## memory\ConversationRepository.kt
---

package com.lightagent.memory

import kotlinx.coroutines.flow.Flow

class ConversationRepository(private val dao: ConversationDao) {

    val allConversations: Flow<List<ConversationEntity>> = dao.getAllConversations()

    suspend fun createConversation(title: String = "New Chat"): ConversationEntity {
        val conv = ConversationEntity(title = title)
        dao.insertConversation(conv)
        return conv
    }

    suspend fun renameConversation(id: String, title: String) {
        dao.updateConversationTitle(id, title)
    }

    suspend fun deleteConversation(id: String) {
        dao.deleteConversation(id)  // CASCADE 自动删消息
    }

    suspend fun saveMessage(conversationId: String, role: String, content: String): MessageEntity {
        val msg = MessageEntity(
            conversationId = conversationId,
            role = role,
            content = content
        )
        dao.insertMessage(msg)
        dao.touchConversation(conversationId)
        return msg
    }

    fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>> =
        dao.getMessagesForConversation(conversationId)

    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity> =
        dao.getMessagesForConversationOnce(conversationId)
}


---
## memory\MessageEntity.kt
---

package com.lightagent.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: String,           // "user" | "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)


---
## memory\ReminderRepository.kt
---

package com.lightagent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// ─── Entity ───────────────────────────────────────────────────────────────────

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val note: String = "",
    val triggerAt: Long,        // 触发时间（毫秒时间戳）
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// ─── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface ReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity)

    @Query("UPDATE reminders SET isCompleted = :done WHERE id = :id")
    suspend fun markDone(id: String, done: Boolean = true)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String)

    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    fun getAllReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: String): ReminderEntity?
}

// ─── Repository ───────────────────────────────────────────────────────────────

class ReminderRepository(private val dao: ReminderDao) {

    val allReminders: Flow<List<ReminderEntity>> = dao.getAllReminders()

    suspend fun addReminder(title: String, note: String = "", triggerAt: Long): ReminderEntity {
        val reminder = ReminderEntity(
            title = title,
            note = note,
            triggerAt = triggerAt
        )
        dao.insertReminder(reminder)
        return reminder
    }

    suspend fun markDone(id: String) = dao.markDone(id)
    suspend fun markDone(id: String, done: Boolean) = dao.markDone(id, done)

    suspend fun deleteReminder(id: String) = dao.deleteReminder(id)

    suspend fun getReminderById(id: String) = dao.getReminderById(id)
}


---
## memory\UserProfileMemory.kt
---

package com.lightagent.memory

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════
// Entity
// ═══════════════════════════════════════

@Entity(tableName = "user_facts")
data class UserFact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════
// DAO
// ═══════════════════════════════════════

@Dao
interface UserFactDao {

    @Query("SELECT * FROM user_facts ORDER BY timestamp DESC")
    suspend fun getAll(): List<UserFact>

    @Query("SELECT * FROM user_facts WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): UserFact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: UserFact)

    @Query("DELETE FROM user_facts WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM user_facts")
    suspend fun clearAll()
}

// ═══════════════════════════════════════
// Database
// ═══════════════════════════════════════

@Database(
    entities = [
        UserFact::class,
        ConversationEntity::class,
        MessageEntity::class,
        ReminderEntity::class
    ],
    version = 2
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun userFactDao(): UserFactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var INSTANCE: AgentDatabase? = null

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "agent_db"
                )
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

// ═══════════════════════════════════════
// Repository (对外接口)
// ═══════════════════════════════════════

class UserProfileMemory(context: Context) {

    private val dao = AgentDatabase.getInstance(context).userFactDao()

    suspend fun set(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.insert(UserFact(key = key, value = value))
    }

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        dao.getByKey(key)?.value
    }

    suspend fun getAll(): List<UserFact> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    /**
     * 生成注入 prompt 的用户画像摘要
     */
    suspend fun buildProfileSummary(): String = withContext(Dispatchers.IO) {
        val facts = dao.getAll()
        if (facts.isEmpty()) return@withContext ""
        "用户信息：\n" + facts.joinToString("\n") { "- ${it.key}：${it.value}" }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }
}


---
## notification\ReminderReceiver.kt
---

package com.lightagent.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lightagent.MainActivity

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "提醒"
        val note  = intent.getStringExtra(EXTRA_NOTE)  ?: ""
        val id    = intent.getStringExtra(EXTRA_ID)    ?: return

        createNotificationChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(note.ifBlank { "点击查看" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id.hashCode(), notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LightAgent 提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "AI 助手设置的提醒事项" }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID  = "lightagent_reminders"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_NOTE  = "extra_note"
        const val EXTRA_ID    = "extra_id"
    }
}


---
## notification\ReminderScheduler.kt
---

package com.lightagent.notification

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lightagent.memory.ReminderEntity

object ReminderScheduler {

    @SuppressLint("ScheduleExactAlarm")
    fun schedule(context: Context, reminder: ReminderEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            putExtra(ReminderReceiver.EXTRA_NOTE,  reminder.note)
            putExtra(ReminderReceiver.EXTRA_ID,    reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 精确闹钟（Android 12+ 需要权限 SCHEDULE_EXACT_ALARM）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerAt,
                    pendingIntent
                )
            } else {
                // 降级：非精确闹钟
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerAt,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.triggerAt,
                pendingIntent
            )
        }
    }

    fun cancel(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}


---
## overlay\ChatOverlay.kt
---

package com.lightagent.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.lightagent.agent.PlannerAgent
import com.lightagent.character.CharacterStateHolder
import com.lightagent.ui.theme.*
import kotlinx.coroutines.launch

data class OverlayChatMessage(
    val role: String,
    val content: String
)

@Composable
fun ChatOverlay(
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val messages = remember {
        mutableStateListOf(
            OverlayChatMessage(
                role = "assistant",
                content = "哼，终于想起点我了？说吧，找我干嘛～"
            )
        )
    }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(min = 420.dp, max = 620.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = DeepNavy
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // ── 顶栏 ──────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "天爱星",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭聊天框",
                            tint = TextPrimary
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // ── 消息列表 ──────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { msg ->
                        OverlayMessageBubble(msg)
                    }

                    if (isLoading) {
                        item {
                            OverlayMessageBubble(
                                OverlayChatMessage(
                                    role = "assistant",
                                    content = "让我想想……"
                                )
                            )
                        }
                    }
                }

                // ── 输入栏 ───────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("和天爱星说点什么……")
                        },
                        singleLine = false,
                        maxLines = 3,
                        textStyle = LocalTextStyle.current.copy(
                            color = TextPrimary
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                            cursorColor = AccentPurple
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty() || isLoading) return@IconButton

                            input = ""
                            messages.add(
                                OverlayChatMessage(role = "user", content = text)
                            )

                            scope.launch {
                                isLoading = true
                                try {
                                    val history = messages
                                        .dropLast(1)
                                        .filter { it.content.isNotBlank() }
                                        .map {
                                            mapOf("role" to it.role, "content" to it.content)
                                        }

                                    val agent = PlannerAgent.create(
                                        context = context.applicationContext,
                                        history = history
                                    )

                                    val result = agent.chatWithEmotion(text)

                                    CharacterStateHolder.emotion = result.emotion

                                    messages.add(
                                        OverlayChatMessage(
                                            role = "assistant",
                                            content = result.text
                                        )
                                    )
                                } catch (e: Exception) {
                                    messages.add(
                                        OverlayChatMessage(
                                            role = "assistant",
                                            content = "出错了：${e.message ?: "未知错误"}"
                                        )
                                    )
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (isLoading) {
                                TextPrimary.copy(alpha = 0.35f)
                            } else {
                                AccentPurple
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayMessageBubble(
    message: OverlayChatMessage
) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = if (isUser) {
                AccentPurple
            } else {
                Color.White.copy(alpha = 0.10f)
            }
        ) {
            Text(
                text = message.content,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}


---
## overlay\ChatOverlayController.kt
---

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


---
## overlay\DesktopAgentService.kt
---

package com.lightagent.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.WindowManager.LayoutParams
import android.util.TypedValue
import android.widget.Toast
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
    // 构建桌宠 View — v4.3.6：3层触摸模型
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

        val mainHandler = Handler(Looper.getMainLooper())

        // ── X 按钮命中判定（局部坐标）──────────────────────────────────
        fun isInCloseZone(x: Float, y: Float): Boolean {
            val vw = composeView.width
            val vh = composeView.height
            if (vw <= 0 || vh <= 0) return false
            val size = dpToPx(30).toFloat()
            return x >= vw - size && y <= size
        }

        // ── 3 层触摸控制器 ──────────────────────────────────────────
        val touchController = OverlayTouchController(
            onOpenChat = {
                InteractionBridge.requestChat()
            },
            onClose = {
                dismissPetWindowOnly()
            },
            onCloseHint = {
                mainHandler.post {
                    Toast.makeText(
                        this@DesktopAgentService,
                        "再点一次 X 关闭桌宠",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDragMove = { dx, dy ->
                mainHandler.post {
                    params.x += dx.toInt()
                    params.y += dy.toInt()
                    try {
                        windowManager.updateViewLayout(composeView, params)
                    } catch (_: Exception) {}
                }
            },
            onDragUp = {},
            onLongPress = {
                mainHandler.post { showCloseMenu() }
            },
            isOnCloseArea = ::isInCloseZone
        )

        composeView.setOnTouchListener { _, event ->
            touchController.onTouchEvent(event)
        }

        petView = composeView
        windowManager.addView(composeView, params)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 长按菜单
    // ══════════════════════════════════════════════════════════════════════════

    private fun showCloseMenu() {
        AlertDialog.Builder(this)
            .setTitle("桌宠控制")
            .setItems(arrayOf("打开 App", "彻底退出桌宠")) { _, which ->
                when (which) {
                    0 -> openMainApp()
                    1 -> {
                        hidePet()
                        stopSelf()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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


---
## overlay\DesktopPetView.kt
---

package com.lightagent.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lightagent.character.CharacterStateHolder
import com.lightagent.ui.CharacterBackground

/**
 * 桌宠悬浮窗 UI — v4.3.4
 *
 * - 呼吸浮动 + 摇摆动画
 * - 右上角 X：Compose 独立 touch，消费事件防穿透到 Service 层
 * - 身体点击由 Service 层 GestureDetector 处理 → InteractionBridge
 * - 从 CharacterStateHolder 读取全局角色+情绪
 */
@Composable
fun DesktopPetView() {
    val currentEmotion = CharacterStateHolder.emotion
    val currentRole    = CharacterStateHolder.role

    // ── 呼吸浮动 ──────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "pet_anim")

    val floatY by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = -6f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pet_floatY"
    )

    val swayX by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3200, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pet_swayX"
    )

    val swayRot by infiniteTransition.animateFloat(
        initialValue  = -0.8f,
        targetValue   = 0.8f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3200, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pet_swayRot"
    )

    Box(
        modifier = Modifier
            .size(width = 90.dp, height = 160.dp)
    ) {
        // ── 角色图 + 动画 ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = floatY
                    translationX = swayX
                    rotationZ    = swayRot
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
        ) {
            AnimatedContent(
                targetState    = currentEmotion,
                transitionSpec = {
                    fadeIn(tween(300)) + slideInVertically(
                        initialOffsetY = { it / 8 },
                        animationSpec  = tween(300)
                    ) togetherWith
                    fadeOut(tween(250)) + slideOutVertically(
                        targetOffsetY = { -it / 8 },
                        animationSpec  = tween(250)
                    )
                },
                label = "pet_emotion"
            ) { emotion ->
                CharacterBackground(
                    role         = currentRole,
                    emotion      = emotion,
                    contentScale = ContentScale.Fit
                )
            }
        }

        // ── 右上角 X：独立 touch，事件由 Service 层按坐标分流 ──────
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(26.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭桌宠",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}


---
## overlay\FloatingTouchController.kt
---

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


---
## overlay\InteractionBridge.kt
---

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


---
## overlay\OverlayCharacter.kt
---

package com.lightagent.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterView

@Composable
fun OverlayCharacter(
    emotion : CharacterEmotion
) {
    // ── 持续呼吸浮动 ──────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "overlay_anim")

    val floatY by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = -6f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay_floatY"
    )

    // ── 轻微左右摇摆（增加生命感）────────────────────────────────────────
    val swayX by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3200, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay_swayX"
    )

    // ── 轻微旋转（配合摇摆，更自然）──────────────────────────────────────
    val swayRot by infiniteTransition.animateFloat(
        initialValue  = -0.8f,
        targetValue   = 0.8f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3200, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay_swayRot"
    )

    Box(
        modifier = Modifier
            .size(width = 90.dp, height = 160.dp)
            .graphicsLayer {
                translationY = floatY
                translationX = swayX
                rotationZ    = swayRot
            }
    ) {
        // ── 情绪切换：淡入淡出 + 轻微上移 ───────────────────────────────
        AnimatedContent(
            targetState    = emotion,
            transitionSpec = {
                (fadeIn(tween(300)) + slideInVertically(
                    animationSpec  = tween(300, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 10 }
                )).togetherWith(
                    fadeOut(tween(200))
                )
            },
            label = "overlay_emotion"
        ) { currentEmotion ->
            CharacterView(
                emotion   = currentEmotion,
                modifier  = Modifier.fillMaxSize(),
                isTalking = false
            )
        }

        // ── 底部名称标签 ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text      = "天爱星",
                color     = Color.White.copy(alpha = 0.8f),
                fontSize  = 10.sp,
                textAlign = TextAlign.Center
            )
        }

        // ── 情绪角标（右上角）───────────────────────────────────────────
        EmotionBadge(
            emotion  = emotion,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
        )
    }
}

// ── 情绪角标（16 种情绪）─────────────────────────────────────────────────────
@Composable
private fun EmotionBadge(
    emotion  : CharacterEmotion,
    modifier : Modifier = Modifier
) {
    val emoji = when (emotion) {
        CharacterEmotion.IDLE       -> null
        CharacterEmotion.HAPPY      -> "😊"
        CharacterEmotion.THINKING   -> "🤔"
        CharacterEmotion.SAD        -> "😢"
        CharacterEmotion.ANGRY      -> "😤"
        CharacterEmotion.SLEEPING   -> "😴"
        CharacterEmotion.SOBBING    -> "🥺"
        CharacterEmotion.CRYING     -> "😭"
        CharacterEmotion.DEPRESSED  -> "😞"
        CharacterEmotion.DISTRESSED -> "😰"
        CharacterEmotion.DROWSY     -> "😪"
        CharacterEmotion.SWEATING   -> "😓"
        CharacterEmotion.PAINED     -> "😖"
        CharacterEmotion.DISGUSTED  -> "🤢"
        CharacterEmotion.SERIOUS    -> "😐"
        CharacterEmotion.WINK       -> "😉"
    }

    AnimatedVisibility(
        visible = emoji != null,
        enter   = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(tween(200)),
        exit    = scaleOut(tween(150)) + fadeOut(tween(150)),
        modifier = modifier
    ) {
        Text(
            text      = emoji ?: "",
            fontSize  = 12.sp,
            textAlign = TextAlign.Center,
            modifier  = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        )
    }
}


---
## overlay\OverlayLifecycleOwner.kt
---

package com.lightagent.overlay

import androidx.lifecycle.*
import androidx.savedstate.*

/**
 * Service 里使用 ComposeView 必须提供 LifecycleOwner
 * 这个类模拟 Activity 的生命周期给 ComposeView 用
 */
class OverlayLifecycleOwner : SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart()  = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause()  = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop()   = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}


---
## overlay\OverlayPermissionHelper.kt
---

package com.lightagent.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 悬浮窗权限辅助工具
 */
object OverlayPermissionHelper {

    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}


---
## overlay\OverlayTouchController.kt
---

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


---
## overlay\PetCommandCenter.kt
---

package com.lightagent.overlay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 桌宠全局可见性控制
 *
 * 读：PetCommandCenter.isVisible（App 内和 Service 都能读）
 * 写：show() / hide() / toggle()
 */
object PetCommandCenter {

    var isVisible by mutableStateOf(false)
        private set

    fun show() {
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }

    fun toggle() {
        isVisible = !isVisible
    }
}


---
## tools\NoteTool.kt
---

package com.lightagent.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class NoteTool(private val context: Context) : Tool {

    override val name = "save_note"
    override val description = "Save a text note. Parameters: content (string)"

    override suspend fun execute(params: JSONObject): String {
        val content = params.optString("content", "").ifBlank {
            return "❌ 缺少 content 参数"
        }

        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            Locale.getDefault()
        ).format(Date())

        val file = File(context.filesDir, "notes.txt")
        file.appendText("[$timestamp] $content\n")

        return "✅ 已保存笔记：$content"
    }
}


---
## tools\OpenAppTool.kt
---

package com.lightagent.tools

import android.content.Context
import android.content.Intent
import org.json.JSONObject

class OpenAppTool(private val context: Context) : Tool {

    override val name = "open_app"
    override val description = "Open a mobile app by its package name. Parameters: app_name (string)"

    override suspend fun execute(params: JSONObject): String {
        val appName = params.optString("app_name", "").ifBlank {
            return "❌ 缺少 app_name 参数"
        }

        val intent = context.packageManager
            .getLaunchIntentForPackage(appName)

        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ 已打开 $appName"
        } else {
            "❌ 找不到应用：$appName"
        }
    }
}


---
## tools\ReminderTool.kt
---

package com.lightagent.tools

import android.content.Context
import com.lightagent.memory.ReminderRepository
import com.lightagent.notification.ReminderScheduler
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ReminderTool(
    private val context: Context,
    private val repository: ReminderRepository
) : Tool {

    override val name = "add_reminder"
    override val description = """
        Add a reminder that will trigger a system notification at a specified time.
        Use this when the user asks to be reminded about something.
        Parameters:
          - title (string, required): short reminder title
          - note (string, optional): extra detail
          - datetime (string, required): date and time in format "yyyy-MM-dd HH:mm"
    """.trimIndent()

    override suspend fun execute(params: JSONObject): String {
        val title    = params.optString("title", "").ifBlank { return "Error: title is required" }
        val note     = params.optString("note", "")
        val datetime = params.optString("datetime", "").ifBlank { return "Error: datetime is required" }

        val triggerAt = try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.parse(datetime)?.time ?: return "Error: invalid datetime format, use yyyy-MM-dd HH:mm"
        } catch (e: Exception) {
            return "Error: invalid datetime format, use yyyy-MM-dd HH:mm"
        }

        if (triggerAt <= System.currentTimeMillis()) {
            return "Error: reminder time must be in the future"
        }

        val reminder = repository.addReminder(title, note, triggerAt)
        ReminderScheduler.schedule(context, reminder)

        return "Reminder set: \"$title\" at $datetime"
    }
}


---
## tools\Tool.kt
---

package com.lightagent.tools

import org.json.JSONObject

interface Tool {
    val name: String
    val description: String get() = ""
    suspend fun execute(params: JSONObject): String
}


---
## tools\WeatherTool.kt
---

package com.lightagent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class WeatherTool : Tool {

    private val client = OkHttpClient()

    override val name = "get_weather"
    override val description = "Query weather for a city using wttr.in. Parameters: city (string)"

    override suspend fun execute(params: JSONObject): String =
        withContext(Dispatchers.IO) {

            val city = params.optString("city", "").ifBlank {
                return@withContext "❌ 缺少 city 参数"
            }

            val url = "https://wttr.in/$city?format=j1&lang=zh"

            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                    ?: return@withContext "❌ 获取天气失败"

                val json    = JSONObject(body)
                val current = json.getJSONArray("current_condition")
                    .getJSONObject(0)

                val temp    = current.getString("temp_C")
                val desc    = current.getJSONArray("weatherDesc")
                    .getJSONObject(0)
                    .getString("value")

                "✅ $city 当前天气：$desc，${temp}°C"

            } catch (e: Exception) {
                "❌ 天气查询失败：${e.message}"
            }
        }
}


---
## tts\KokoroTTS.kt
---

package com.lightagent.tts

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro TTS — Android ONNX 本地推理
 * 模型：onnx-community/Kokoro-82M-v1.0-ONNX
 *
 * 使用流程：
 * 1. KokoroTTS.getInstance(context).initialize()
 * 2. synthesize(text) → FloatArray（PCM 音频数据，24000Hz，单声道）
 * 3. 用 AudioTrack 或写成 WAV 播放
 */
class KokoroTTS private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTTS"
        private const val SAMPLE_RATE = 24000

        // ── 实际 assets 文件名 ─────────────────────────────────────────────
        private const val MODEL_FILE  = "kokoro/model_q8f16.onnx"
        private const val TOKEN_FILE  = "kokoro/tokenizer.json"
        private const val VOICE_DIR   = "kokoro/voices"

        @Volatile
        private var instance: KokoroTTS? = null

        fun getInstance(context: Context): KokoroTTS =
            instance ?: synchronized(this) {
                instance ?: KokoroTTS(context.applicationContext).also { instance = it }
            }
    }

    private var ortSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null
    private var tokenMap: MutableMap<String, Long> = mutableMapOf()
    private var voiceCache: MutableMap<String, FloatArray> = mutableMapOf()
    private var isInitialized = false

    var currentVoice = "zf_xiaobei"   // 默认中文女声小北
    var speed = 1.0f

    // ═════════════════════════════════════════════════════════════════════════
    // 初始化
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) return@withContext true
            Log.d(TAG, "Kokoro TTS 初始化开始…")

            // 1. 复制 assets 到缓存
            val modelFile  = copyAsset(MODEL_FILE)
            val tokenFile  = copyAsset(TOKEN_FILE)

            // 2. ONNX Runtime
            ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                    Log.d(TAG, "NNAPI 加速已启用")
                } catch (_: Exception) {
                    Log.w(TAG, "NNAPI 不可用，回退 CPU")
                }
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnv!!.createSession(modelFile.absolutePath, opts)
            Log.d(TAG, "ONNX 模型加载成功")

            // 3. 加载 token 表（JSON vocab）
            tokenMap = loadVocab(tokenFile)
            Log.d(TAG, "Token 表：${tokenMap.size} 个")

            // 4. 扫描 voices 目录，延迟加载
            val voiceDir = File(context.cacheDir, VOICE_DIR)
            if (!voiceDir.exists()) voiceDir.mkdirs()
            Log.d(TAG, "声音目录：${voiceDir.absolutePath}")

            isInitialized = true
            Log.d(TAG, "Kokoro TTS 初始化完成 ✅")
            true
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
            false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 合成
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun synthesize(text: String): FloatArray = withContext(Dispatchers.Default) {
        check(isInitialized) { "KokoroTTS 未初始化" }
        val session = ortSession!!
        val env     = ortEnv!!

        // 1. 文本 → tokens
        val ids = textToTokens(text)
        require(ids.isNotEmpty()) { "文本 token 化失败: $text" }
        Log.d(TAG, "「$text」→ ${ids.size} tokens")

        // 2. 加载声音向量（按需）
        val style = loadVoice(currentVoice)
        Log.d(TAG, "声音「$currentVoice」维度: ${style.size}")

        // 3. 构建输入
        val inputIds  = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()),
                        longArrayOf(1, ids.size.toLong()))
        val styleVec  = OnnxTensor.createTensor(env, FloatBuffer.wrap(style),
                        longArrayOf(1, style.size.toLong()))
        val speedVec  = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)),
                        longArrayOf(1))

        // 4. 推理
        val output = session.run(mapOf(
            "input_ids" to inputIds,
            "style"     to styleVec,
            "speed"     to speedVec
        ))

        // 5. 取音频
        val audioTensor = (output.first().value as Array<*>)[0] as FloatArray

        // 释放
        inputIds.close(); styleVec.close(); speedVec.close(); output.close()
        audioTensor
    }

    suspend fun synthesizeToFile(text: String, outputFile: File): File =
        withContext(Dispatchers.IO) {
            val pcm = synthesize(text)
            writeWav(pcm, outputFile, SAMPLE_RATE)
            outputFile
        }

    // ═════════════════════════════════════════════════════════════════════════
    // 文本 → Token
    // ═════════════════════════════════════════════════════════════════════════

    private fun textToTokens(text: String): List<Long> {
        val tokens = mutableListOf<Long>()
        // 句子开头
        tokenMap["$"]?.let { tokens.add(it) }
        for (ch in text) {
            val id = tokenMap[ch.toString()]
            if (id != null) tokens.add(id)
            else tokenMap[" "]?.let { tokens.add(it) }  // 未知字符→空格
        }
        tokenMap["$"]?.let { tokens.add(it) }  // 句子结尾
        return tokens
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 加载资源
    // ═════════════════════════════════════════════════════════════════════════

    /** 从 tokenizer.json 的 model.vocab 加载 */
    private fun loadVocab(file: File): MutableMap<String, Long> {
        val json  = JSONObject(file.readText())
        val vocab = json.getJSONObject("model").getJSONObject("vocab")
        val map   = mutableMapOf<String, Long>()
        for (key in vocab.keys()) {
            map[key] = vocab.getLong(key)
        }
        return map
    }

    /** 按需加载声音 .bin → FloatArray */
    private fun loadVoice(name: String): FloatArray {
        voiceCache[name]?.let { return it }

        // 1. 先尝试缓存目录（从 assets/voices/xxx.bin 复制过来的）
        val cacheFile = copyAsset("$VOICE_DIR/$name.bin")
        val floats = FloatArray((cacheFile.length() / 4).toInt())
        cacheFile.inputStream().use { input ->
            val buf = ByteArray(4)
            for (i in floats.indices) {
                input.read(buf)
                floats[i] = bytesToFloat(buf)
            }
        }
        voiceCache[name] = floats
        Log.d(TAG, "声音「$name」已加载，${floats.size} 维")
        return floats
    }

    /** 复制 assets 到 cacheDir，避免重复复制 */
    private fun copyAsset(assetPath: String): File {
        val cacheFile = File(context.cacheDir, assetPath)
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile
        cacheFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "复制 asset → ${cacheFile.absolutePath}")
        return cacheFile
    }

    private fun bytesToFloat(bytes: ByteArray): Float =
        java.lang.Float.intBitsToFloat(
            ((bytes[0].toInt() and 0xFF))
                or ((bytes[1].toInt() and 0xFF) shl 8)
                or ((bytes[2].toInt() and 0xFF) shl 16)
                or ((bytes[3].toInt() and 0xFF) shl 24)
        )

    // ═════════════════════════════════════════════════════════════════════════
    // WAV 写入
    // ═════════════════════════════════════════════════════════════════════════

    private fun writeWav(pcm: FloatArray, file: File, sampleRate: Int) {
        val shorts = ShortArray(pcm.size) { i ->
            (pcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
        val dataSize = shorts.size * 2

        file.outputStream().use { out ->
            // RIFF header
            out.write("RIFF".toByteArray())
            out.write(intToLe(36 + dataSize))
            out.write("WAVE".toByteArray())

            // fmt chunk
            out.write("fmt ".toByteArray())
            out.write(intToLe(16))              // subchunk size
            out.write(shortToBytes(1))             // PCM format
            out.write(shortToBytes(1))             // mono
            out.write(intToLe(sampleRate))
            out.write(intToLe(sampleRate * 2))  // byte rate
            out.write(shortToBytes(2))             // block align
            out.write(shortToBytes(16))            // bits/sample

            // data chunk
            out.write("data".toByteArray())
            out.write(intToLe(dataSize))
            for (s in shorts) out.write(shortToBytes(s.toInt()))
        }
    }

    private fun intToLe(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    private fun shortToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte()
    )

    fun release() {
        ortSession?.close()
        ortEnv?.close()
        isInitialized = false
    }
}


---
## tts\KokoroTTSBridge.kt
---

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


---
## tts\KokoroTTSController.kt
---

package com.lightagent.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*

/**
 * Kokoro TTS 控制器 — 实现 TTSController
 * 本地 ONNX 推理，无需网络
 */
class KokoroTTSController(private val context: Context) : TTSController {

    private val tts = KokoroTTS.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    override var isSpeaking: Boolean = false
        private set

    override fun init(onReady: () -> Unit, onError: (Exception) -> Unit) {
        scope.launch {
            try {
                val ok = tts.initialize()
                if (ok) onReady() else onError(Exception("KokoroTTS 初始化失败"))
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    override fun speak(
        text: String,
        onStart: () -> Unit,
        onPlaybackProgress: (volume: Float) -> Unit,
        onDone: () -> Unit
    ) {
        playJob?.cancel()
        playJob = scope.launch(Dispatchers.IO) {
            try {
                isSpeaking = true
                onStart()

                // 合成 PCM
                val pcm = tts.synthesize(text)

                // AudioTrack 播放
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(pcm.size * 4)
                    .build()

                audioTrack = track
                track.play()

                // 写入数据
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)

                // 播放时回调音量（简化版：统一中等音量）
                onPlaybackProgress(0.7f)

                // 等待播放完
                track.stop()
                track.release()
                audioTrack = null

                onDone()
            } catch (e: Exception) {
                Log.e("KokoroTTS", "播放失败", e)
                onDone()
            } finally {
                isSpeaking = false
            }
        }
    }

    override fun stop() {
        playJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isSpeaking = false
    }

    override fun setRate(rate: Float) { tts.speed = rate }
    override fun setPitch(pitch: Float) { /* Kokoro 不支持音调 */ }
    override fun setVoice(voiceId: String) { tts.currentVoice = voiceId }

    override fun release() {
        stop()
        tts.release()
        scope.cancel()
    }
}


---
## tts\KokoroTTSManager.kt
---

package com.lightagent.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Kokoro TTS 播放管理器 v3.5
 * 核心改进：流式断句逻辑 + 队列消费
 */
class KokoroTTSManager(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTTSManager"

        @Volatile
        private var instance: KokoroTTSManager? = null

        fun getInstance(context: Context): KokoroTTSManager =
            instance ?: synchronized(this) {
                instance ?: KokoroTTSManager(context.applicationContext).also { instance = it }
            }

        // 主断句标点：遇到就切一句送 TTS
        private val SENTENCE_ENDINGS = setOf('。', '！', '？', '…', '\n', '.', '!', '?')

        // 次级断句：逗号/分号/冒号，缓冲积累超过阈值才切
        private val MINOR_BREAKS = setOf('，', ',', '；', ';', ':', '：')

        // 次级断句的最小字符数阈值
        private const val MINOR_BREAK_MIN_LEN = 15

        // 无标点强制断句阈值
        private const val FORCE_BREAK_LEN = 50
    }

    private val kokoro = KokoroTTS.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isTalking = MutableStateFlow(false)
    val isTalking: StateFlow<Boolean> = _isTalking

    // 流式缓冲区：StringBuilder 比 String += 高效
    private val streamBuffer = StringBuilder()

    // 播放队列
    private val sentenceQueue = ArrayDeque<String>()
    private var isPlaying      = false
    private var isInitialized  = false
    private var mediaPlayer: MediaPlayer? = null

    // ─── 初始化 ──────────────────────────────────────────────────────────

    suspend fun initialize(): Boolean {
        isInitialized = try {
            kokoro.initialize()
            Log.d(TAG, "Kokoro TTS 初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Kokoro TTS 初始化失败", e)
            false
        }
        return isInitialized
    }

    // ─── 流式喂文字（LLM 每收到一个 chunk 就调用一次）──────────────────
    //
    // 核心逻辑：
    //   1. 把 chunk 追加到 streamBuffer
    //   2. 扫描 buffer，遇到主断句标点（。！？…）→ 切出一句加入队列
    //   3. 没有主标点但有次标点（，；：）且积累 ≥15 字 → 也切一句
    //   4. 实在没标点但超过 50 字 → 强制切断
    fun feedStream(chunk: String) {
        if (!isInitialized) return
        if (chunk.isBlank()) return

        streamBuffer.append(chunk)

        while (streamBuffer.isNotEmpty()) {
            val buf = streamBuffer.toString()

            // 1) 找第一个主断句标点
            val breakIdx = buf.indexOfFirst { it in SENTENCE_ENDINGS }

            if (breakIdx >= 0) {
                val sentence = buf.substring(0, breakIdx + 1).trim()
                streamBuffer.delete(0, breakIdx + 1)
                if (sentence.isNotBlank()) enqueue(sentence)
                continue
            }

            // 2) 没有主标点，检查次级断句
            val minorIdx = buf.indexOfFirst { it in MINOR_BREAKS }
            if (minorIdx >= MINOR_BREAK_MIN_LEN) {
                val sentence = buf.substring(0, minorIdx + 1).trim()
                streamBuffer.delete(0, minorIdx + 1)
                if (sentence.isNotBlank()) enqueue(sentence)
                continue
            }

            // 3) 超过阈值强制切断
            if (buf.length > FORCE_BREAK_LEN) {
                val sentence = buf.substring(0, FORCE_BREAK_LEN).trim()
                streamBuffer.delete(0, FORCE_BREAK_LEN)
                if (sentence.isNotBlank()) enqueue(sentence)
                continue
            }

            // buffer 还不够长，等下一个 chunk
            break
        }
    }

    // ─── 流式结束时调用：把 buffer 剩余内容也播掉 ───────────────────────

    fun flushStream() {
        if (!isInitialized) return
        val remaining = streamBuffer.toString().trim()
        streamBuffer.clear()
        if (remaining.isNotBlank()) {
            enqueue(remaining)
        }
    }

    // ─── 一次性播放（非流式场景，兼容旧 API）────────────────────────────

    fun speak(text: String) {
        if (!isInitialized || text.isBlank()) return
        stop()
        // 按标点切分后喂入流式管道
        feedStream(text)
        flushStream()
    }

    // ─── 停止并清空 ─────────────────────────────────────────────────────

    fun stop() {
        sentenceQueue.clear()
        streamBuffer.clear()
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        isPlaying    = false
        _isTalking.value = false
    }

    // ─── 释放资源 ───────────────────────────────────────────────────────

    fun release() {
        stop()
        scope.cancel()
        try { kokoro.release() } catch (_: Exception) {}
        isInitialized = false
    }

    // ═════════════════════════════════════════════════════════════════════
    // 内部：队列管理 + TTS 合成 + 逐句播放
    // ═════════════════════════════════════════════════════════════════════

    private fun enqueue(sentence: String) {
        sentenceQueue.addLast(sentence)
        if (!isPlaying) consumeQueue()
    }

    private fun consumeQueue() {
        if (isPlaying || sentenceQueue.isEmpty()) return
        isPlaying = true

        scope.launch {
            _isTalking.value = true
            try {
                while (sentenceQueue.isNotEmpty()) {
                    val sentence = sentenceQueue.removeFirst()
                    try {
                        playSentence(sentence)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "播放句子失败：$sentence", e)
                        // 单句失败不中断队列
                    }
                }
            } finally {
                isPlaying         = false
                _isTalking.value = sentenceQueue.isNotEmpty()
            }
        }
    }

    // 合成 + 播放一句（挂起直到播完或失败）
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun playSentence(sentence: String) {
        return suspendCancellableCoroutine { cont ->
            try {
                val outFile = File(context.cacheDir, "kokoro_${System.currentTimeMillis()}.wav")

                // 在 IO 线程合成
                scope.launch(Dispatchers.IO) {
                    try {
                        kokoro.synthesizeToFile(sentence, outFile)

                        // 切到主线程播放
                        withContext(Dispatchers.Main) {
                            playWav(outFile) {
                                outFile.delete()
                                if (cont.isActive) cont.resume(Unit) {}
                            }
                        }
                    } catch (e: Exception) {
                        outFile.delete()
                        if (cont.isActive) cont.cancel(e)
                    }
                }

                cont.invokeOnCancellation {
                    // 取消时立即停止当前播放
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.cancel(e)
            }
        }
    }

    private fun playWav(file: File, onComplete: () -> Unit) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                onComplete()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer 错误: what=$what extra=$extra")
                onComplete()
                true
            }
            prepare()
            start()
        }
    }
}


---
## tts\TTSController.kt
---

package com.lightagent.tts

/**
 * TTS（文字转语音）控制器接口（预留，暂未实现）
 *
 * 接入 TTS 引擎时实现此接口，替换 [NoOpTTSController]
 *
 * 候选方案：
 *   - Android 系统 TTS（TextToSpeech）
 *   - Edge-TTS / VITS / GPT-SoVITS（本地或远程推理）
 *   - 云端 API：微软 Azure TTS、阿里云、字节豆包
 *
 * 与 Live2D 联动方式：
 *   在 [onPlaybackProgress] 回调中取音量值，传给 [Live2DController.setLipSync]
 */
interface TTSController {

    /** 是否正在播放 */
    val isSpeaking: Boolean

    /**
     * 初始化 TTS 引擎
     * @param onReady 引擎就绪回调
     * @param onError 初始化失败回调
     */
    fun init(onReady: () -> Unit = {}, onError: (Exception) -> Unit = {})

    /**
     * 朗读文本
     * @param text 要朗读的文字
     * @param onStart 开始播放回调
     * @param onPlaybackProgress 播放进度回调，返回当前音量（用于口型同步）
     * @param onDone 播放完成回调
     */
    fun speak(
        text: String,
        onStart: () -> Unit = {},
        onPlaybackProgress: (volume: Float) -> Unit = {},
        onDone: () -> Unit = {}
    )

    /** 停止当前播放 */
    fun stop()

    /**
     * 设置语速
     * @param rate 语速倍率，1.0f 为正常速度
     */
    fun setRate(rate: Float)

    /**
     * 设置音调
     * @param pitch 音调，1.0f 为正常
     */
    fun setPitch(pitch: Float)

    /**
     * 设置音色/角色
     * @param voiceId 音色 ID，具体值取决于 TTS 引擎
     */
    fun setVoice(voiceId: String)

    /** 释放资源 */
    fun release()
}

/**
 * 空实现，TTS 引擎未接入时使用
 */
class NoOpTTSController : TTSController {
    override val isSpeaking: Boolean = false
    override fun init(onReady: () -> Unit, onError: (Exception) -> Unit) { onReady() }
    override fun speak(
        text: String,
        onStart: () -> Unit,
        onPlaybackProgress: (Float) -> Unit,
        onDone: () -> Unit
    ) { onDone() }
    override fun stop() {}
    override fun setRate(rate: Float) {}
    override fun setPitch(pitch: Float) {}
    override fun setVoice(voiceId: String) {}
    override fun release() {}
}


---
## ui\BackgroundViewModel.kt
---

package com.lightagent.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class BackgroundSource {
    data class Asset(val fileName: String) : BackgroundSource()
    data class Custom(val uri: Uri) : BackgroundSource()
    object SolidColor : BackgroundSource()
}

// ── 背景套系定义 ──────────────────────────────────────────────────────────────
// 每个套系有名字、emoji、以及对应的图片文件名列表
// 图片放在 assets/backgrounds/{套系文件夹}/ 下
data class BackgroundTheme(
    val id       : String,
    val name     : String,
    val emoji    : String,
    val folder   : String,         // assets/backgrounds/{folder}/
    val fileNames: List<String>    // folder 内的文件名
)

val ALL_BACKGROUND_THEMES = listOf(
    BackgroundTheme(
        id        = "night",
        name      = "深夜星空",
        emoji     = "🌌",
        folder    = "night",
        fileNames = (1..6).map { "night_$it.png" }
    ),
    BackgroundTheme(
        id        = "sakura",
        name      = "樱花物语",
        emoji     = "🌸",
        folder    = "sakura",
        fileNames = (1..6).map { "sakura_$it.png" }
    ),
    BackgroundTheme(
        id        = "ocean",
        name      = "深海之境",
        emoji     = "🌊",
        folder    = "ocean",
        fileNames = (1..6).map { "ocean_$it.png" }
    ),
    BackgroundTheme(
        id        = "forest",
        name      = "幽静森林",
        emoji     = "🌿",
        folder    = "forest",
        fileNames = (1..6).map { "forest_$it.png" }
    ),
    BackgroundTheme(
        id        = "cyberpunk",
        name      = "赛博霓虹",
        emoji     = "🌃",
        folder    = "cyberpunk",
        fileNames = (1..6).map { "cyberpunk_$it.png" }
    ),
    BackgroundTheme(
        id        = "plain",
        name      = "纯色渐变",
        emoji     = "🎨",
        folder    = "",            // 无图片，用渐变色
        fileNames = emptyList()
    )
)

class BackgroundViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("bg_prefs", Context.MODE_PRIVATE)

    private val _background = MutableStateFlow<BackgroundSource>(loadSaved())
    val background: StateFlow<BackgroundSource> = _background

    // 当前选中的套系
    private val _currentTheme = MutableStateFlow(loadSavedTheme())
    val currentTheme: StateFlow<BackgroundTheme> = _currentTheme

    // 所有套系
    val themes: List<BackgroundTheme> = ALL_BACKGROUND_THEMES

    // ── 切换套系（随机选一张该套系的图）────────────────────────────────────
    fun selectTheme(theme: BackgroundTheme) {
        _currentTheme.value = theme
        prefs.edit().putString(KEY_THEME_ID, theme.id).apply()

        if (theme.fileNames.isEmpty()) {
            // 纯色套系 → 用渐变色兜底
            _background.value = BackgroundSource.SolidColor
            prefs.edit().putString(KEY_TYPE, TYPE_SOLID).apply()
        } else {
            val file = theme.fileNames.random()
            val path = "${theme.folder}/$file"
            _background.value = BackgroundSource.Asset(path)
            saveAsset(path)
        }
    }

    // ── 在当前套系内随机切换 ─────────────────────────────────────────────────
    fun randomInCurrentTheme() {
        val theme = _currentTheme.value
        if (theme.fileNames.isEmpty()) return
        val current = (_background.value as? BackgroundSource.Asset)?.fileName
        val candidates = theme.fileNames.map { "${theme.folder}/$it" }.filter { it != current }
        val next = candidates.randomOrNull() ?: theme.fileNames.first().let { "${theme.folder}/$it" }
        _background.value = BackgroundSource.Asset(next)
        saveAsset(next)
    }

    // ── 选中套系内指定图片 ───────────────────────────────────────────────────
    fun selectSpecificAsset(assetPath: String) {
        _background.value = BackgroundSource.Asset(assetPath)
        saveAsset(assetPath)
    }

    // ── 用户自选图片 ─────────────────────────────────────────────────────────
    fun setCustomBackground(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
        } catch (_: Exception) {}
        _background.value = BackgroundSource.Custom(uri)
        prefs.edit()
            .putString(KEY_TYPE, TYPE_CUSTOM)
            .putString(KEY_URI, uri.toString())
            .apply()
    }

    // ── 恢复默认 ─────────────────────────────────────────────────────────────
    fun resetToDefault() {
        val defaultTheme = ALL_BACKGROUND_THEMES.first()
        selectTheme(defaultTheme)
    }

    // ── 兼容旧接口 ───────────────────────────────────────────────────────────
    fun randomBackground() = randomInCurrentTheme()

    // ── 持久化 ───────────────────────────────────────────────────────────────
    private fun saveAsset(path: String) {
        prefs.edit()
            .putString(KEY_TYPE, TYPE_ASSET)
            .putString(KEY_ASSET, path)
            .apply()
    }

    private fun loadSaved(): BackgroundSource {
        return when (prefs.getString(KEY_TYPE, TYPE_SOLID)) {
            TYPE_CUSTOM -> {
                val uriStr = prefs.getString(KEY_URI, null)
                if (uriStr != null) BackgroundSource.Custom(Uri.parse(uriStr))
                else BackgroundSource.SolidColor
            }
            TYPE_ASSET -> {
                val asset = prefs.getString(KEY_ASSET, "") ?: ""
                if (asset.isNotBlank()) BackgroundSource.Asset(asset)
                else BackgroundSource.SolidColor
            }
            else -> BackgroundSource.SolidColor
        }
    }

    private fun loadSavedTheme(): BackgroundTheme {
        val savedId = prefs.getString(KEY_THEME_ID, ALL_BACKGROUND_THEMES.first().id) ?: ""
        return ALL_BACKGROUND_THEMES.find { it.id == savedId } ?: ALL_BACKGROUND_THEMES.first()
    }

    companion object {
        private const val KEY_TYPE     = "bg_type"
        private const val KEY_ASSET    = "bg_asset"
        private const val KEY_URI      = "bg_uri"
        private const val KEY_THEME_ID = "bg_theme_id"
        private const val TYPE_ASSET   = "asset"
        private const val TYPE_CUSTOM  = "custom"
        private const val TYPE_SOLID   = "solid"
    }
}


---
## ui\CharacterBackground.kt
---

package com.lightagent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterPackRegistry
import com.lightagent.character.CharacterView

/**
 * 角色立绘背景层
 *
 * 从 assets/characters/{pack.folder}/{emotion}.png 动态加载，
 * 不依赖 R.drawable，新增角色只需放图片 + 注册 CharacterPack。
 *
 * Usage:
 *   CharacterBackground(role = "tianaixing", emotion = CharacterStateHolder.emotion)
 *
 * @param role         角色 ID（如 "tianaixing"），映射到 CharacterPackRegistry
 * @param emotion      当前情绪
 * @param modifier     透传给 CharacterView
 * @param isTalking    说话脉冲（默认关闭，Chat 上下文可传入）
 * @param contentScale 缩放模式（默认 Fill，铺满)
 */
@Composable
fun CharacterBackground(
    role         : String,
    emotion      : CharacterEmotion,
    modifier     : Modifier    = Modifier,
    isTalking    : Boolean     = false,
    contentScale : ContentScale = ContentScale.Crop
) {
    val pack = CharacterPackRegistry.findById(role)

    CharacterView(
        emotion      = emotion,
        pack         = pack,
        modifier     = modifier.fillMaxSize(),
        isTalking    = isTalking,
        contentScale = contentScale
    )
}


---
## ui\CharacterPackViewModel.kt
---

package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lightagent.character.CharacterPack
import com.lightagent.character.CharacterPackManager
import com.lightagent.character.CharacterPackRegistry
import com.lightagent.character.CharacterStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CharacterPackViewModel(application: Application) : AndroidViewModel(application) {

    /** 所有可用角色包 */
    val packs: List<CharacterPack> = CharacterPackRegistry.packs

    /** 当前选中的角色包 */
    private val _currentPack = MutableStateFlow(
        CharacterPackManager.load(application)
    )
    val currentPack: StateFlow<CharacterPack> = _currentPack.asStateFlow()

    /** 切换角色包并持久化 */
    fun selectPack(pack: CharacterPack) {
        _currentPack.value = pack
        CharacterStateHolder.role = pack.id
        CharacterPackManager.save(getApplication(), pack)
    }
}


---
## ui\ChatViewModel.kt
---

package com.lightagent.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterStateHolder
import com.lightagent.character.EmotionParser
import com.lightagent.llm.LLMClient
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ConversationEntity
import com.lightagent.memory.ConversationRepository
import com.lightagent.overlay.DesktopAgentService
import com.lightagent.tts.KokoroTTSManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ChatMessage(
    val role: String,
    val content: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db   = AgentDatabase.getInstance(application)
    private val repo = ConversationRepository(db.conversationDao())
    private val llm  = LLMClient.getInstance(application)

    private val ttsManager = KokoroTTSManager.getInstance(application)
    val isTalking: StateFlow<Boolean> = ttsManager.isTalking

    val conversations: StateFlow<List<ConversationEntity>> = repo.allConversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentEmotion = MutableStateFlow(CharacterEmotion.IDLE)
    val currentEmotion: StateFlow<CharacterEmotion> = _currentEmotion

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    private var streamJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttsManager.initialize()
            } catch (e: Exception) {
                Log.w("ChatVM", "Kokoro TTS 初始化异常", e)
            }
        }
        viewModelScope.launch {
            val all = conversations.first { true }
            if (all.isEmpty()) createNewConversation()
            else switchConversation(all.first())
        }
    }

    fun updateInput(value: String) { _input.value = value }

    fun createNewConversation() = viewModelScope.launch {
        val conv = repo.createConversation("New Chat")
        _currentConversationId.value = conv.id
        _messages.value = emptyList()
    }

    fun switchConversation(conv: ConversationEntity) = viewModelScope.launch {
        _currentConversationId.value = conv.id
        val history = repo.getMessagesOnce(conv.id)
        _messages.value = history.map { ChatMessage(it.role, it.content) }
    }

    fun deleteConversation(conv: ConversationEntity) = viewModelScope.launch {
        repo.deleteConversation(conv.id)
        if (_currentConversationId.value == conv.id) {
            val remaining = conversations.value.filter { it.id != conv.id }
            if (remaining.isEmpty()) createNewConversation()
            else switchConversation(remaining.first())
        }
    }

    // ─── 发送消息（流式版本·修复 history 重复）─────────────────────────
    fun send(userInput: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val convId = _currentConversationId.value ?: return@launch
            _isLoading.value = true
            _currentEmotion.value = CharacterEmotion.THINKING
            CharacterStateHolder.emotion = CharacterEmotion.THINKING
            ttsManager.stop()

            // ── 关键修复：先快照当前历史，再把用户消息加进去 ──────────────
            // buildHistory 用快照构建，不包含待发的用户消息（避免重复）
            val historySnapshot = _messages.value.toList()

            // 保存用户消息到数据库 + 更新 UI
            repo.saveMessage(convId, "user", userInput)
            _messages.value = _messages.value + ChatMessage("user", userInput)

            // 在末尾加空 assistant 占位（流式内容会实时更新这一条）
            _messages.value = _messages.value + ChatMessage("assistant", "")

            try {
                // 用快照构建 history，userInput 作为最后一条单独加入
                val history = buildHistory(historySnapshot, userInput)
                var fullText = ""

                llm.chatStream(history).collect { chunk ->
                    fullText += chunk

                    // 实时去掉情绪标签再显示
                    val displayText = fullText
                        .replace(Regex("\\[EMOTION:[a-zA-Z_\\u4e00-\\u9fa5]+]"), "")
                        .trimEnd()

                    _messages.value = _messages.value.toMutableList().also { list ->
                        list[list.lastIndex] = ChatMessage("assistant", displayText)
                    }

                    // 喂给 TTS（去掉情绪标签）
                    val cleanChunk = chunk.replace(
                        Regex("\\[EMOTION:[a-zA-Z_\\u4e00-\\u9fa5]+]"), ""
                    )
                    if (cleanChunk.isNotBlank()) {
                        ttsManager.feedStream(cleanChunk)
                    }
                }

                // 流结束：解析情绪
                val parsed = EmotionParser.parse(fullText)
                _currentEmotion.value = parsed.emotion
                CharacterStateHolder.emotion = parsed.emotion
                DesktopAgentService.updateEmotion(getApplication(), parsed.emotion)

                // 更新最终干净文本
                _messages.value = _messages.value.toMutableList().also { list ->
                    list[list.lastIndex] = ChatMessage("assistant", parsed.cleanText)
                }

                // 保存到数据库
                repo.saveMessage(convId, "assistant", parsed.cleanText)

                // 把 TTS 缓冲区剩余内容也播掉
                ttsManager.flushStream()

                // 自动命名会话（第一轮对话）
                if (_messages.value.size <= 2) {
                    repo.renameConversation(convId, userInput.take(20).ifBlank { "新对话" })
                }

            } catch (e: CancellationException) {
                ttsManager.stop()
                Log.d("ChatVM", "流式请求被取消")
            } catch (e: Exception) {
                val err = "错误：${e.message}"
                _currentEmotion.value = CharacterEmotion.IDLE
                CharacterStateHolder.emotion = CharacterEmotion.IDLE
                _messages.value = _messages.value.toMutableList().also { list ->
                    if (list.lastOrNull()?.role == "assistant") {
                        list[list.lastIndex] = ChatMessage("assistant", err)
                    } else {
                        list.add(ChatMessage("assistant", err))
                    }
                }
                repo.saveMessage(convId, "assistant", err)
                Log.e("ChatVM", "流式请求失败", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isBlank() || _isLoading.value) return
        _input.value = ""
        send(text)
    }

    fun stopGeneration() {
        streamJob?.cancel()
        ttsManager.stop()
        _isLoading.value = false
        _currentEmotion.value = CharacterEmotion.IDLE
    }

    // ── 关键修复：接收历史快照 + 当前用户消息，不从 _messages 读 ────────
    // 这样就不会把正在显示的 assistant 空占位也发给 LLM
    private fun buildHistory(
        snapshot: List<ChatMessage>,
        newUserMessage: String
    ): List<Map<String, String>> {
        val systemPrompt = """
            You are 天爱星，一个运行在 Android 上的 AI 助手，角色来自《败犬女主太多了》。
            性格：聪明、偶尔傲娇、对用户有点在意但嘴硬。
            请始终用中文回复用户。
            
            【重要】每条回复末尾必须附加情绪标签，格式：[EMOTION:情绪英文名]
            情绪选项（16种）：
            idle / happy / thinking / sad / angry / sleeping
            sobbing / crying / depressed / distressed / drowsy
            sweating / pained / disgusted / serious / wink
        """.trimIndent()

        val result = mutableListOf<Map<String, String>>()
        result.add(mapOf("role" to "system", "content" to systemPrompt))

        // 历史消息（最近20条，只取 user/assistant，过滤掉空内容）
        snapshot.takeLast(20)
            .filter { it.content.isNotBlank() }
            .forEach { msg ->
                result.add(mapOf("role" to msg.role, "content" to msg.content))
            }

        // 当前用户消息
        result.add(mapOf("role" to "user", "content" to newUserMessage))
        return result
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.release()
    }
}


---
## ui\LLMSettingsViewModel.kt
---

package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.llm.LLMClient
import com.lightagent.llm.LLMConfig
import com.lightagent.llm.LLMConfigStore
import com.lightagent.llm.LLMProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LLMSettings(
    val apiKey         : String  = "",
    val baseUrl        : String  = "",
    val modelName      : String  = "",
    val temperature    : Float   = 0.7f,
    val maxTokens      : Int     = 2048,
    val stream         : Boolean = true,
    val contextEnabled : Boolean = true
)

class LLMSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settings = MutableStateFlow(loadFromStore())
    val settings: StateFlow<LLMSettings> = _settings.asStateFlow()

    private fun loadFromStore(): LLMSettings {
        val config = LLMConfigStore.load(getApplication())
        return LLMSettings(
            apiKey       = config.apiKey,
            baseUrl      = config.customUrl.ifBlank { providerBaseUrl(config.provider) },
            modelName    = config.model,
            temperature  = config.temperature.toFloat(),
            maxTokens    = config.maxTokens
        )
    }

    fun updateApiKey(v: String)      { _settings.value = _settings.value.copy(apiKey = v) }
    fun updateBaseUrl(v: String)     { _settings.value = _settings.value.copy(baseUrl = v) }
    fun updateModelName(v: String)   { _settings.value = _settings.value.copy(modelName = v) }
    fun updateTemperature(v: Float)  { _settings.value = _settings.value.copy(temperature = v) }
    fun updateMaxTokens(v: Int)      { _settings.value = _settings.value.copy(maxTokens = v) }
    fun updateStream(v: Boolean)     { _settings.value = _settings.value.copy(stream = v) }
    fun updateContext(v: Boolean)    { _settings.value = _settings.value.copy(contextEnabled = v) }

    fun save() {
        val s = _settings.value
        viewModelScope.launch {
            // 推断 provider
            val provider = when {
                s.baseUrl.contains("deepseek", ignoreCase = true) -> LLMProvider.DEEPSEEK
                s.baseUrl.contains("dashscope", ignoreCase = true) -> LLMProvider.QWEN
                else -> LLMProvider.CUSTOM
            }
            val config = LLMConfig(
                provider    = provider,
                apiKey      = s.apiKey,
                model       = s.modelName,
                customUrl   = s.baseUrl,
                temperature = s.temperature.toDouble(),
                maxTokens   = s.maxTokens
            )
            LLMConfigStore.save(getApplication(), config)
            LLMClient.getInstance().updateConfig(config)
        }
    }

    private fun providerBaseUrl(provider: LLMProvider): String = when (provider) {
        LLMProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
        LLMProvider.OPENAI   -> "https://api.openai.com/v1"
        LLMProvider.QWEN     -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
        LLMProvider.CUSTOM   -> ""
    }
}


---
## ui\ReminderViewModel.kt
---

package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ReminderEntity
import com.lightagent.memory.ReminderRepository
import com.lightagent.notification.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReminderViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ReminderRepository(
        AgentDatabase.getInstance(application).reminderDao()
    )

    val reminders: StateFlow<List<ReminderEntity>> = repo.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(title: String, timeText: String) = viewModelScope.launch {
        val triggerAt = parseTimeInput(timeText)
        repo.addReminder(title = title, triggerAt = triggerAt)
    }

    fun toggleDone(entity: ReminderEntity) = viewModelScope.launch {
        repo.markDone(id = entity.id, done = !entity.isCompleted)
    }

    fun delete(entity: ReminderEntity) = viewModelScope.launch {
        repo.deleteReminder(entity.id)
        ReminderScheduler.cancel(getApplication(), entity.id)
    }

    // ── 简易时间解析：用户输入自由文本 → 毫秒时间戳 ────────────────────────
    private fun parseTimeInput(input: String): Long {
        if (input.isBlank()) return System.currentTimeMillis() + 3600_000
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        // 尝试标准格式
        sdf.parse(input)?.let { return it.time }
        // 尝试 "MM-dd HH:mm"
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
        sdf.parse("$year-$input")?.let { return it.time }
        // 兜底
        return System.currentTimeMillis() + 3600_000
    }
}


---
## ui\components\CharacterPanel.kt
---

package com.lightagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lightagent.R
import com.lightagent.agent.ChatState

@Composable
fun CharacterPanel(
    state: ChatState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")

    val offsetY by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = -12f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offsetY"
    )

    val alpha by animateFloatAsState(
        targetValue   = if (state is ChatState.Thinking) 0.6f else 1f,
        animationSpec = tween(400),
        label         = "alpha"
    )

    Box(
        modifier          = modifier.fillMaxWidth(),
        contentAlignment  = Alignment.Center
    ) {
        Image(
            painter           = painterResource(id = R.drawable.character_default),
            contentDescription = "Agent",
            modifier          = Modifier
                .height(200.dp)
                .alpha(alpha)
                .graphicsLayer { translationY = offsetY }
        )
    }
}


---
## ui\components\GlassCard.kt
---

package com.lightagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightagent.ui.theme.GlassBorder

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0x44FFFFFF),
                        Color(0x11FFFFFF)
                    )
                )
            )
            .border(
                width = 0.5.dp,
                color = GlassBorder,
                shape = RoundedCornerShape(cornerRadius)
            )
            .padding(12.dp)
    ) {
        content()
    }
}


---
## ui\components\InputBar.kt
---

package com.lightagent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.theme.*

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true
) {
    val borderColor by animateColorAsState(
        targetValue = if (value.isNotEmpty()) AccentPurple else GlassBorder,
        label       = "border"
    )

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0x33FFFFFF), Color(0x11FFFFFF))
                    )
                )
                .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (value.isEmpty()) {
                Text("和 AI 说点什么...", color = TextHint, fontSize = 14.sp)
            }
            BasicTextField(
                value         = value,
                onValueChange = onValueChange,
                enabled       = enabled,
                textStyle     = LocalTextStyle.current.copy(
                    color    = TextPrimary,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(AccentPurple),
                modifier    = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick  = onSend,
            enabled  = value.isNotEmpty() && enabled,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (value.isNotEmpty())
                        Brush.linearGradient(listOf(AccentPurple, AccentBlue))
                    else
                        Brush.linearGradient(listOf(GlassBg, GlassBg))
                )
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Rounded.Send,
                contentDescription = "发送",
                tint               = if (value.isNotEmpty()) Color.White else TextHint
            )
        }
    }
}


---
## ui\components\MessageBubble.kt
---

package com.lightagent.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.theme.*

@Composable
fun MessageBubble(role: String, content: String) {

    val isUser = role == "user"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart    = if (isUser) 16.dp else 4.dp,
                        topEnd      = if (isUser) 4.dp  else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd   = 16.dp
                    )
                )
                .background(
                    if (isUser) UserBubble else AssistantBubble
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text     = content,
                color    = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}


---
## ui\components\StatusIndicator.kt
---

package com.lightagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.agent.ChatState
import com.lightagent.ui.theme.*

@Composable
fun StatusIndicator(state: ChatState) {

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val scale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.4f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val (color, label) = when (state) {
        is ChatState.Thinking    -> StatusThinking to "🧠 思考中"
        is ChatState.CallingTool -> StatusTool     to "🧰 执行工具"
        is ChatState.Error       -> StatusTool     to "❌ 出错了"
        else                     -> StatusIdle     to "💫 就绪"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(if (state !is ChatState.Idle) scale else 1f)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = color, fontSize = 12.sp)
    }
}


---
## ui\screen\AnimatedMessageBubble.kt
---

package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun AnimatedMessageBubble(
    message    : ChatMessage,
    index      : Int,
    isLatest   : Boolean = false,
    isStreaming : Boolean = false
) {
    val isUser = message.role == "user"

    // ── 1. 弹入动画 ──────────────────────────────────────────────────────────
    var appeared by remember { mutableStateOf(false) }

    val translateY by animateFloatAsState(
        targetValue   = if (appeared) 0f else AnimTokens.MessageSlideInY,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "bubbleY"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (appeared) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration),
        label         = "bubbleAlpha"
    )
    val scale by animateFloatAsState(
        targetValue   = if (appeared) 1f else 0.90f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "bubbleScale"
    )

    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(6) * AnimTokens.MessageStagger))
        appeared = true
    }

    // ── 2. 打字机效果（仅最新 assistant 消息）───────────────────────────────
    var displayedText by remember(message.content) {
        mutableStateOf(if (isLatest) "" else message.content)
    }

    LaunchedEffect(message.content, isLatest) {
        if (!isLatest) { displayedText = message.content; return@LaunchedEffect }
        displayedText = ""
        message.content.forEach { char ->
            displayedText += char
            delay(when (char) {
                '。','！','？','.','!','?' -> 55L
                '，',',','；',';'          -> 20L
                '\n'                       -> 80L
                else                       -> 10L
            })
        }
    }

    // ── 布局 ─────────────────────────────────────────────────────────────────
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY    = translateY
                this.alpha      = alpha
                scaleX          = scale
                scaleY          = scale
                transformOrigin = if (isUser)
                    TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f)
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart    = if (isUser) 18.dp else 4.dp,
                topEnd      = if (isUser) 4.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd   = 18.dp
            ),
            color          = if (isUser) UserBubble else AssistantBubble,
            tonalElevation = if (isUser) 0.dp else 2.dp,
            modifier       = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text  = if (isLatest && !isStreaming) displayedText else message.content,
                    color = if (isUser) Color.White else TextPrimary,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
                )
                if (isStreaming && isLatest) {
                    Spacer(Modifier.height(6.dp))
                    ThinkingDots()
                }
            }
        }
    }
}

// ── 三点弹跳（上下跳动 + 缩放），可由 ChatScreen 直接引用 ──────────────────
@Composable
fun ThinkingDots(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "dots")

    val offsets = (0..2).map { i ->
        inf.animateFloat(
            initialValue  = 0f,
            targetValue   = -9f,
            animationSpec = infiniteRepeatable(
                animation  = tween(420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 140)
            ),
            label = "dot$i"
        )
    }

    val scales = (0..2).map { i ->
        inf.animateFloat(
            initialValue  = 1f,
            targetValue   = 1.35f,
            animationSpec = infiniteRepeatable(
                animation  = tween(420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 140)
            ),
            label = "dotScale$i"
        )
    }

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        offsets.forEachIndexed { i, offset ->
            Surface(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        translationY = offset.value
                        scaleX       = scales[i].value
                        scaleY       = scales[i].value
                    },
                shape = RoundedCornerShape(50),
                color = AccentPurple.copy(alpha = 0.85f)
            ) {}
        }
    }
}


---
## ui\screen\BackgroundSettingsSheet.kt
---

package com.lightagent.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.ui.ALL_BACKGROUND_THEMES
import com.lightagent.ui.BackgroundSource
import com.lightagent.ui.BackgroundTheme
import com.lightagent.ui.BackgroundViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

// 每个套系的代表渐变色（用于卡片预览）
private val themePreviewColors = mapOf(
    "night"     to listOf(Color(0xFF0D1B2A), Color(0xFF1B2A4A), Color(0xFF2E4A7A)),
    "sakura"    to listOf(Color(0xFFFFB7C5), Color(0xFFFF8FA3), Color(0xFFFF6B8A)),
    "ocean"     to listOf(Color(0xFF003566), Color(0xFF0077B6), Color(0xFF00B4D8)),
    "forest"    to listOf(Color(0xFF1B4332), Color(0xFF2D6A4F), Color(0xFF52B788)),
    "cyberpunk" to listOf(Color(0xFF10002B), Color(0xFF3C096C), Color(0xFFE040FB)),
    "plain"     to listOf(Color(0xFF667EEA), Color(0xFF764BA2), Color(0xFFF093FB))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsSheet(
    onDismiss: () -> Unit,
    bgViewModel: BackgroundViewModel = viewModel()
) {
    val currentTheme by bgViewModel.currentTheme.collectAsState()
    val background   by bgViewModel.background.collectAsState()
    val themes        = bgViewModel.themes

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { bgViewModel.setCustomBackground(it) }
    }

    ModalBottomSheet(
        onDismissRequest  = onDismiss,
        containerColor    = Color(0xFF1A1A2E),
        contentColor      = Color.White,
        dragHandle        = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {

            // ── 标题行 ───────────────────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text       = "背景套系",
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 套系卡片横向列表 ─────────────────────────────────────────────
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding        = PaddingValues(horizontal = 2.dp)
            ) {
                itemsIndexed(themes) { _, theme ->
                    ThemeCard(
                        theme      = theme,
                        isSelected = theme.id == currentTheme.id,
                        onClick    = { bgViewModel.selectTheme(theme) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 当前套系预览区 ────────────────────────────────────────────────
            if (currentTheme.fileNames.isNotEmpty()) {
                Text(
                    text     = "「${currentTheme.emoji} ${currentTheme.name}」套系预览",
                    fontSize = 14.sp,
                    color    = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(10.dp))

                // 套系内所有图片的小缩略网格
                LazyVerticalGrid(
                    columns              = GridCells.Fixed(3),
                    modifier             = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement  = Arrangement.spacedBy(8.dp),
                    userScrollEnabled    = false
                ) {
                    itemsIndexed(currentTheme.fileNames) { _, fileName ->
                        val assetPath    = "${currentTheme.folder}/$fileName"
                        val isCurrentBg  = (background as? BackgroundSource.Asset)
                                            ?.fileName == assetPath

                        AssetThumbnail(
                            assetPath  = assetPath,
                            isSelected = isCurrentBg,
                            onClick    = {
                                bgViewModel.selectSpecificAsset(assetPath)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 随机换一张按钮
                OutlinedButton(
                    onClick = { bgViewModel.randomInCurrentTheme() },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border   = androidx.compose.foundation.BorderStroke(
                        1.dp, Color.White.copy(alpha = 0.3f)
                    )
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("在「${currentTheme.name}」内随机换一张")
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))

            // ── 自选图片 ─────────────────────────────────────────────────────
            OutlinedButton(
                onClick  = { imagePicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                ),
                border   = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.3f)
                )
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("从相册自选图片")
            }

            // 当前自定义图片状态
            if (background is BackgroundSource.Custom) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text  = "✓ 已使用自定义图片",
                        fontSize = 13.sp,
                        color    = Emerald
                    )
                    TextButton(onClick = { bgViewModel.resetToDefault() }) {
                        Text("恢复默认", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 套系选择卡片 ──────────────────────────────────────────────────────────────
@Composable
private fun ThemeCard(
    theme     : BackgroundTheme,
    isSelected: Boolean,
    onClick   : () -> Unit
) {
    val colors = themePreviewColors[theme.id]
        ?: listOf(Color(0xFF667EEA), Color(0xFF764BA2))

    val scale by animateFloatAsState(
        targetValue  = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label        = "theme_card_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(colors)
                )
                .then(
                    if (isSelected) Modifier.border(
                        2.dp,
                        Color.White,
                        RoundedCornerShape(16.dp)
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(theme.emoji, fontSize = 28.sp)

            // 右上角选中勾
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint     = Color(0xFF1A1A2E),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text       = theme.name,
            fontSize   = 11.sp,
            color      = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            textAlign  = TextAlign.Center,
            maxLines   = 1
        )
    }
}

// ── 套系内单张图片缩略图 ──────────────────────────────────────────────────────
@Composable
private fun AssetThumbnail(
    assetPath : String,
    isSelected: Boolean,
    onClick   : () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 用 coil 从 assets 加载图片
    val model = remember(assetPath) {
        coil.request.ImageRequest.Builder(context)
            .data("file:///android_asset/backgrounds/$assetPath")
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .then(
                if (isSelected) Modifier.border(
                    2.dp, Color.White, RoundedCornerShape(10.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        coil.compose.AsyncImage(
            model             = model,
            contentDescription = null,
            contentScale      = ContentScale.Crop,
            modifier          = Modifier.fillMaxSize()
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.15f))
            )
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint     = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
            )
        }
    }
}


---
## ui\screen\CharacterPackSheet.kt
---

package com.lightagent.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterPack
import com.lightagent.ui.CharacterPackViewModel
import com.lightagent.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterPackSheet(
    onDismiss : () -> Unit,
    viewModel : CharacterPackViewModel = viewModel()
) {
    val currentPack by viewModel.currentPack.collectAsState()
    val packs        = viewModel.packs

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A2E),
        contentColor     = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {

            // ── 标题行 ───────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text       = "角色切换",
                        fontSize   = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White
                    )
                    Text(
                        text     = "当前：${currentPack.name}",
                        fontSize = 12.sp,
                        color    = Color.White.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            Spacer(Modifier.height(20.dp))

            if (packs.size == 1) {
                // 只有一套时给个提示
                OnlyOnePackHint()
            } else {
                // 角色包横向卡片列表
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding        = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                ) {
                    items(packs, key = { it.id }) { pack ->
                        CharacterPackCard(
                            pack       = pack,
                            isSelected = pack.id == currentPack.id,
                            onClick    = {
                                viewModel.selectPack(pack)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 单个角色包卡片 ────────────────────────────────────────────────────────────
@Composable
private fun CharacterPackCard(
    pack      : CharacterPack,
    isSelected: Boolean,
    onClick   : () -> Unit
) {
    val context = LocalContext.current

    // 选中时弹簧放大
    val scale by animateFloatAsState(
        targetValue   = if (isSelected) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ),
        label = "packCardScale"
    )

    // 预览图（IDLE 情绪）
    val previewBitmap = remember(pack.id) {
        runCatching {
            context.assets.open(pack.previewAssetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(onClick = onClick)
    ) {
        // 卡片主体
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2A2A4A))
                .then(
                    if (isSelected) Modifier.border(
                        2.dp, AccentPurple, RoundedCornerShape(16.dp)
                    ) else Modifier.border(
                        1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)
                    )
                )
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap             = previewBitmap.asImageBitmap(),
                    contentDescription = pack.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
                // 底部渐变遮罩，让名字更清晰
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xCC000000)
                                )
                            )
                        )
                )
            } else {
                // 没有图片时显示大 emoji 占位
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("\uD83C\uDFAD", fontSize = 40.sp)
                }
            }

            // 选中勾
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(AccentPurple),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint     = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // 卡片内底部角色名
            Text(
                text      = pack.name,
                color     = Color.White,
                fontSize  = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp, start = 6.dp, end = 6.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // 卡片下方描述
        Text(
            text      = pack.description,
            fontSize  = 11.sp,
            color     = if (isSelected)
                            AccentPurple.copy(alpha = 0.9f)
                        else
                            Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.width(120.dp),
            lineHeight = 15.sp
        )
    }
}

// ── 只有一套角色时的提示 ──────────────────────────────────────────────────────
@Composable
private fun OnlyOnePackHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("\uD83C\uDFAD", fontSize = 36.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                text     = "目前只有一套角色",
                color    = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text     = "在 CharacterPackRegistry 中注册更多角色包即可",
                color    = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}


---
## ui\screen\ChatBackground.kt
---

package com.lightagent.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lightagent.ui.BackgroundSource
import com.lightagent.ui.theme.AccentBlue
import com.lightagent.ui.theme.AccentPurple

/**
 * 聊天背景层 — v3.1
 * Asset/Custom 来源渲染图片 + 暗色蒙层，
 * SolidColor / 默认 → 纵向渐变 + 装饰光晕。
 */
@Composable
fun ChatBackground(
    source: BackgroundSource
) {
    when (source) {
        is BackgroundSource.Asset -> AssetBackground(fileName = source.fileName)
        is BackgroundSource.Custom -> CustomBackground(uri = source.uri)
        is BackgroundSource.SolidColor -> DefaultGradientBackground()
    }
}

// ── Asset 来源：从 assets/backgrounds/ 解码 ──────────────────────────────────
@Composable
private fun AssetBackground(fileName: String) {
    val context = LocalContext.current
    val bitmap = remember(fileName) {
        runCatching {
            context.assets.open("backgrounds/$fileName").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 暗色蒙层，确保文字可读
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
            )
        } else {
            DefaultGradientBackground()
        }
    }
}

// ── Custom 来源：Coil 异步加载本地 URI ──────────────────────────────────────
@Composable
private fun CustomBackground(uri: android.net.Uri) {
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(uri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 暗色蒙层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
        )
    }
}

// ── 默认渐变背景 + 装饰光晕 ────────────────────────────────────────────────
@Composable
private fun DefaultGradientBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f   to Color(0xFF0A0E1A),
                    0.4f to Color(0xFF1A0A2E),
                    0.75f to Color(0xFF0D1B2A),
                    1f   to Color(0xFF0A0E1A)
                )
            )
    ) {
        // 右上角装饰光晕
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(220.dp)
                .offset(x = 60.dp, y = (-40).dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentPurple.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )
        // 左下角装饰光晕
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(180.dp)
                .offset(x = (-40).dp, y = 40.dp)
                .blur(70.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentBlue.copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )
    }
}


---
## ui\screen\ChatScreen.kt
---

package com.lightagent.ui.screen

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterView
import com.lightagent.live2d.Live2DController
import com.lightagent.live2d.NoOpLive2DController
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.BackgroundSource
import com.lightagent.ui.BackgroundViewModel
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.CharacterPackViewModel
import com.lightagent.ui.ChatViewModel
import com.lightagent.ui.ReminderViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.launch

// ── 页面路由枚举 ─────────────────────────────────────────────────────────────
private enum class Screen { Chat, Reminder, Settings }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    chatViewModel       : ChatViewModel          = viewModel(),
    reminderViewModel   : ReminderViewModel      = viewModel(),
    backgroundViewModel : BackgroundViewModel    = viewModel(),
    characterPackViewModel: CharacterPackViewModel = viewModel(),
    live2DController    : Live2DController       = remember { NoOpLive2DController() }
) {
    val drawerState    = rememberDrawerState(DrawerValue.Closed)
    val scope          = rememberCoroutineScope()
    var showBgSheet    by remember { mutableStateOf(false) }
    var showCharSheet  by remember { mutableStateOf(false) }
    var currentScreen  by remember { mutableStateOf(Screen.Chat) }

    val conversations  by chatViewModel.conversations.collectAsState()
    val currentConvId  by chatViewModel.currentConversationId.collectAsState()
    val messages       by chatViewModel.messages.collectAsState()
    val isLoading      by chatViewModel.isLoading.collectAsState()
    val inputText      by chatViewModel.input.collectAsState()
    val currentEmotion by chatViewModel.currentEmotion.collectAsState()
    val isTalking      by chatViewModel.isTalking.collectAsState()
    val currentPack    by characterPackViewModel.currentPack.collectAsState()
    val listState      = rememberLazyListState()

    // 新消息自动滚底
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    if (showBgSheet) {
        BackgroundSettingsSheet(
            onDismiss   = { showBgSheet = false },
            bgViewModel = backgroundViewModel
        )
    }

    if (showCharSheet) {
        CharacterPackSheet(
            onDismiss = { showCharSheet = false },
            viewModel = characterPackViewModel
        )
    }

    // ── 页面路由 + 跨页面动画 ──────────────────────────────────────────────
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {

        AnimatedContent(
            targetState    = currentScreen,
            transitionSpec = {
                when {
                    initialState == Screen.Chat ->
                        (slideInHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness    = Spring.StiffnessMediumLow
                            )
                        ) { it } + fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally { -it / 3 } + fadeOut(tween(180))
                            )
                    else ->
                        (slideInHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness    = Spring.StiffnessMediumLow
                            )
                        ) { -it } + fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally { it / 3 } + fadeOut(tween(180))
                            )
                }.using(SizeTransform(clip = false))
            },
            label = "screenTransition"
        ) { screen ->

            when (screen) {

                Screen.Reminder -> ReminderScreen(
                    onBack            = { currentScreen = Screen.Chat },
                    reminderViewModel = reminderViewModel
                )

                Screen.Settings -> LLMSettingsScreen(
                    onBack = { currentScreen = Screen.Chat }
                )

                Screen.Chat -> {
                    ModalNavigationDrawer(
                        drawerState   = drawerState,
                        drawerContent = {
                            ConversationDrawer(
                                conversations        = conversations,
                                currentConversationId = currentConvId,
                                onSelectConversation  = { conv ->
                                    chatViewModel.switchConversation(conv)
                                    scope.launch { drawerState.close() }
                                },
                                onNewConversation = {
                                    chatViewModel.createNewConversation()
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteConversation = { conv ->
                                    chatViewModel.deleteConversation(conv)
                                },
                                onOpenReminders = {
                                    currentScreen = Screen.Reminder
                                    scope.launch { drawerState.close() }
                                },
                                onOpenSettings = {
                                    currentScreen = Screen.Settings
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    ) {
                        GalGameChatLayout(
                            messages        = messages,
                            isLoading       = isLoading,
                            inputText       = inputText,
                            currentEmotion  = currentEmotion,
                            isTalking       = isTalking,
                            characterPack   = currentPack,
                            conversations   = conversations,
                            currentConvId   = currentConvId,
                            listState       = listState,
                            onOpenDrawer    = { scope.launch { drawerState.open() } },
                            onOpenBgSheet   = { showBgSheet = true },
                            onOpenCharSheet = { showCharSheet = true },
                            onTextChange    = { chatViewModel.updateInput(it) },
                            onSend          = { chatViewModel.send() }
                        )
                    }
                }
            }
        }
    }
}


---
## ui\screen\ConversationDrawer.kt
---

package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ConversationDrawer(
    conversations         : List<ConversationEntity>,
    currentConversationId : String?,
    onSelectConversation  : (ConversationEntity) -> Unit,
    onNewConversation     : () -> Unit,
    onDeleteConversation  : (ConversationEntity) -> Unit,
    onOpenReminders       : () -> Unit,
    onOpenSettings        : () -> Unit,
    modifier              : Modifier = Modifier
) {
    // 抽屉整体：玻璃态背景 + 紫色渐变侧边光
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(
                Brush.verticalGradient(
                    0f   to Color(0xCC1A0A2E),
                    0.5f to Color(0xBB0D1020),
                    1f   to Color(0xCC0A0E1A)
                )
            )
    ) {
        // 侧边紫色光晕装饰
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(120.dp)
                .offset(x = 40.dp, y = (-20).dp)
                .blur(50.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentPurple.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 20.dp)
        ) {

            // ── 顶部标题 ──────────────────────────────────────────────────
            Text(
                text       = "✨ 会话",
                color      = TextPrimary,
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(4.dp))

            // ── 新建会话按钮 ──────────────────────────────────────────────
            NewConversationButton(onClick = onNewConversation)

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color     = GlassBorder,
                modifier  = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            // ── 会话列表（stagger 弹入）──────────────────────────────────
            LazyColumn(
                modifier       = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    items = conversations,
                    key   = { _, c -> c.id }
                ) { index, conv ->
                    StaggeredDrawerItem(index = index) {
                        ConversationItem(
                            conv       = conv,
                            isSelected = conv.id == currentConversationId,
                            onSelect   = { onSelectConversation(conv) },
                            onDelete   = { onDeleteConversation(conv) }
                        )
                    }
                }
            }

            // ── 底部工具区 ────────────────────────────────────────────────
            HorizontalDivider(
                color    = GlassBorder,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            DrawerBottomItem(
                icon    = Icons.Default.Notifications,
                label   = "提醒事项",
                onClick = onOpenReminders
            )
            DrawerBottomItem(
                icon    = Icons.Default.Settings,
                label   = "模型设置",
                onClick = onOpenSettings,
                tint    = AccentPurple
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── 新建会话按钮：hover 时发光 ────────────────────────────────────────────────
@Composable
private fun NewConversationButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }

    val btnScale by animateFloatAsState(
        targetValue   = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "newBtnScale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .graphicsLayer { scaleX = btnScale; scaleY = btnScale },
        shape          = RoundedCornerShape(14.dp),
        color          = AccentPurple.copy(alpha = 0.18f),
        border         = BorderStroke(
            1.dp, AccentPurple.copy(alpha = 0.4f)
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint               = AccentPurple,
                modifier           = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text       = "新建会话",
                color      = AccentPurple,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Stagger 进场包装器 ────────────────────────────────────────────────────────
@Composable
private fun StaggeredDrawerItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    val translateX by animateFloatAsState(
        targetValue   = if (visible) 0f else -30f,
        animationSpec = spring(
            dampingRatio = AnimTokens.DrawerDamping,
            stiffness    = AnimTokens.DrawerStiffness
        ),
        label = "drawerX$index"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration),
        label         = "drawerA$index"
    )

    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(10) * AnimTokens.StaggerBase)
        visible = true
    }

    Box(modifier = Modifier.graphicsLayer {
        translationX = translateX
        this.alpha   = alpha
    }) {
        content()
    }
}

// ── 单个会话项 ────────────────────────────────────────────────────────────────
@Composable
private fun ConversationItem(
    conv      : ConversationEntity,
    isSelected: Boolean,
    onSelect  : () -> Unit,
    onDelete  : () -> Unit
) {
    val bgAlpha by animateFloatAsState(
        targetValue   = if (isSelected) 1f else 0f,
        animationSpec = tween(AnimTokens.SelectionDuration),
        label         = "convBg"
    )
    // 选中时左侧亮条宽度
    val indicatorWidth by animateDpAsState(
        targetValue   = if (isSelected) 3.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "indicator"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentPurple.copy(alpha = bgAlpha * 0.18f))
            .clickable { onSelect() }
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧选中指示条
        Box(
            modifier = Modifier
                .width(indicatorWidth)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AccentPurple)
        )

        Spacer(Modifier.width(10.dp))

        Text(
            text     = conv.title,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
            color    = if (isSelected) AccentPurple else TextSecondary,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )

        IconButton(
            onClick  = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                modifier           = Modifier.size(15.dp),
                tint               = TextSecondary.copy(alpha = 0.5f)
            )
        }
    }
}

// ── 底部工具栏条目 ────────────────────────────────────────────────────────────
@Composable
private fun DrawerBottomItem(
    icon   : ImageVector,
    label  : String,
    onClick: () -> Unit,
    tint   : Color = TextSecondary
) {
    var pressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "bottomItemScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable {
                pressed = true
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = tint,
            modifier           = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, color = tint, fontSize = 14.sp)
    }
}


---
## ui\screen\GalGameChatLayout.kt
---

package com.lightagent.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.LocalIsFolded
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterPack
import com.lightagent.character.CharacterView
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.theme.*

// ══════════════════════════════════════════════════════════════════════════════
// Galgame 核心布局 v4.1
//
// 层次结构（从底到顶）：
// 1. 纯色渐变打底（深夜蓝紫，不铺背景图）
// 2. 立绘充满全屏（ContentScale.Fit，底部对齐）← 立绘就是视觉主角
// 3. 底部渐变蒙层（确保消息区域可读）
// 4. UI层（顶栏 + 消息列表 + 输入栏）
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalGameChatLayout(
    messages        : List<ChatMessage>,
    isLoading       : Boolean,
    inputText       : String,
    currentEmotion  : CharacterEmotion,
    isTalking       : Boolean,
    characterPack   : CharacterPack,
    conversations   : List<ConversationEntity>,
    currentConvId   : String?,
    listState       : LazyListState,
    onOpenDrawer    : () -> Unit,
    onOpenBgSheet   : () -> Unit,
    onOpenCharSheet : () -> Unit,
    onTextChange    : (String) -> Unit,
    onSend          : () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // ── 第一层：纯色渐变打底 ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0D0D1A),
                            Color(0xFF1A1A3E),
                            Color(0xFF0D0D1A)
                        )
                    )
                )
        )

        // ── 第二层：立绘（视觉主角，充满全屏）────────────────────────────
        GalBackgroundCharacter(
            emotion       = currentEmotion,
            isTalking     = isTalking,
            characterPack = characterPack
        )

        // ── 第三层：底部渐变蒙层（下半遮罩，保证气泡可读）────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.35f to Color(0x18000000),
                        0.55f to Color(0x77000000),
                        0.72f to Color(0xBB000000),
                        1.00f to Color(0xF2000000)
                    )
                )
        )

        // ── 第四层：UI层 ──────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {

            ChatTopBar(
                conversations   = conversations,
                currentConvId   = currentConvId,
                onOpenDrawer    = onOpenDrawer,
                onOpenBgSheet   = onOpenBgSheet,
                onOpenCharSheet = onOpenCharSheet
            )

            ChatMessageList(
                messages  = messages,
                isLoading = isLoading,
                listState = listState,
                modifier  = Modifier.weight(1f)
            )

            GalInputBar(
                inputText    = inputText,
                onTextChange = onTextChange,
                onSend       = onSend
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 立绘层
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalBackgroundCharacter(
    emotion       : CharacterEmotion,
    isTalking     : Boolean,
    characterPack : CharacterPack
) {
    val isFolded = LocalIsFolded.current

    val infiniteTransition = rememberInfiniteTransition(label = "gal_char")

    // 呼吸浮动
    val floatY by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = if (isTalking) -8f else -4f,
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = if (isTalking) 500 else 2800,
                easing         = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gal_floatY"
    )

    // 说话时微微脉冲缩放
    val talkScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = if (isTalking) 1.008f else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = if (isTalking) 300 else 800,
                easing         = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gal_talkScale"
    )

    // 情绪切换 + 角色切换时淡入淡出
    AnimatedContent(
        targetState    = Pair(characterPack.id, emotion),
        transitionSpec = {
            (fadeIn(tween(380)) + slideInVertically(
                animationSpec  = tween(380, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 10 }
            )).togetherWith(fadeOut(tween(280)))
        },
        modifier = Modifier.fillMaxSize(),
        label    = "gal_emotion_anim"
    ) { (_, targetEmotion) ->
        Box(
            modifier         = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            CharacterView(
                emotion      = targetEmotion,
                pack         = characterPack,
                isTalking    = isTalking,
                modifier     = Modifier
                    .fillMaxSize()
                    // 折叠屏留底部padding，普通屏不需要
                    .padding(bottom = if (isFolded) 64.dp else 0.dp)
                    .graphicsLayer {
                        translationY = floatY
                        scaleX       = talkScale
                        scaleY       = talkScale
                    },
                contentScale = if (isFolded) ContentScale.Crop else ContentScale.Fit
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 顶栏
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    conversations   : List<ConversationEntity>,
    currentConvId   : String?,
    onOpenDrawer    : () -> Unit,
    onOpenBgSheet   : () -> Unit,
    onOpenCharSheet : () -> Unit
) {
    val convTitle = conversations.find { it.id == currentConvId }?.title ?: "天爱星"

    TopAppBar(
        title = {
            Text(
                text  = convTitle,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium
            )
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector        = Icons.Default.Menu,
                    contentDescription = "菜单",
                    tint               = TextPrimary
                )
            }
        },
        actions = {
            // 角色切换
            IconButton(onClick = onOpenCharSheet) {
                Icon(
                    imageVector        = Icons.Default.Person,
                    contentDescription = "切换角色",
                    tint               = TextPrimary
                )
            }
            // 背景切换（保留，以备后用）
            IconButton(onClick = onOpenBgSheet) {
                Icon(
                    imageVector        = Icons.Default.Image,
                    contentDescription = "背景",
                    tint               = TextPrimary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 消息气泡
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalMessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color  = if (isUser)
                        Color(0xCC6B4EFF)   // 用户：紫色半透明
                    else
                        Color(0xCC1E1E3A),  // AI：深蓝半透明
                    shape  = RoundedCornerShape(
                        topStart    = if (isUser) 18.dp else 4.dp,
                        topEnd      = if (isUser) 4.dp  else 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd   = 18.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text  = message.content,
                color = Color.White,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 消息列表
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ChatMessageList(
    messages  : List<ChatMessage>,
    isLoading : Boolean,
    listState : LazyListState,
    modifier  : Modifier = Modifier
) {
    LazyColumn(
        state               = listState,
        modifier            = modifier.fillMaxWidth(),
        contentPadding      = PaddingValues(
            start  = 12.dp,
            end    = 12.dp,
            top    = 56.dp,
            bottom = 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(messages) { _, message ->
            GalMessageBubble(message = message)
        }
        if (isLoading) {
            item {
                GalTypingIndicator()
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 正在输入指示器
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalTypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier              = Modifier.padding(start = 8.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue  = 0.3f,
                targetValue   = 1f,
                animationSpec = infiniteRepeatable(
                    animation         = tween(600),
                    repeatMode        = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 200)
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(Color.White, RoundedCornerShape(3.dp))
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 输入栏
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalInputBar(
    inputText    : String,
    onTextChange : (String) -> Unit,
    onSend       : () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value       = inputText,
            onValueChange = onTextChange,
            modifier    = Modifier.weight(1f),
            placeholder = {
                Text(
                    text  = "说点什么…",
                    color = Color.White.copy(alpha = 0.4f),
                    style = TextStyle(fontSize = 14.sp)
                )
            },
            textStyle   = TextStyle(color = Color.White, fontSize = 14.sp),
            colors      = OutlinedTextFieldDefaults.colors(
                focusedBorderColor     = AccentPurple.copy(alpha = 0.8f),
                unfocusedBorderColor   = Color.White.copy(alpha = 0.2f),
                cursorColor            = AccentPurple,
                focusedContainerColor  = Color(0x44000000),
                unfocusedContainerColor = Color(0x33000000)
            ),
            shape       = RoundedCornerShape(24.dp),
            maxLines    = 4
        )

        // 发送按钮
        val canSend = inputText.isNotBlank()
        IconButton(
            onClick  = { if (canSend) onSend() },
            modifier = Modifier
                .size(48.dp)
                .background(
                    color  = if (canSend) AccentPurple else Color.White.copy(alpha = 0.15f),
                    shape  = RoundedCornerShape(24.dp)
                )
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint               = Color.White,
                modifier           = Modifier.size(20.dp)
            )
        }
    }
}


---
## ui\screen\LLMSettingsScreen.kt
---

package com.lightagent.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.ui.LLMSettingsViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMSettingsScreen(
    onBack            : () -> Unit,
    settingsViewModel : LLMSettingsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f   to Color(0xFF0A0E1A),
                    0.5f to Color(0xFF1A0A2E),
                    1f   to Color(0xFF0A0E1A)
                )
            )
    ) {
        // 左上角装饰光晕
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(160.dp)
                .offset(x = (-40).dp, y = (-20).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentBlue.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "⚙️ 模型设置",
                            color      = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        AnimatedBackButton(onClick = onBack)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 每个设置项用 stagger 弹入
                SettingsItem(index = 0) {
                    SettingsTextField(
                        label    = "API Key",
                        value    = settings.apiKey,
                        onChange = { settingsViewModel.updateApiKey(it) },
                        hint     = "sk-..."
                    )
                }
                SettingsItem(index = 1) {
                    SettingsTextField(
                        label    = "Base URL",
                        value    = settings.baseUrl,
                        onChange = { settingsViewModel.updateBaseUrl(it) },
                        hint     = "https://api.openai.com/v1"
                    )
                }
                SettingsItem(index = 2) {
                    SettingsTextField(
                        label    = "模型名称",
                        value    = settings.modelName,
                        onChange = { settingsViewModel.updateModelName(it) },
                        hint     = "gpt-4o"
                    )
                }
                SettingsItem(index = 3) {
                    SettingsSlider(
                        label    = "Temperature",
                        value    = settings.temperature,
                        range    = 0f..2f,
                        onChange = { settingsViewModel.updateTemperature(it) }
                    )
                }
                SettingsItem(index = 4) {
                    SettingsSlider(
                        label    = "Max Tokens",
                        value    = settings.maxTokens.toFloat(),
                        range    = 256f..8192f,
                        onChange = { settingsViewModel.updateMaxTokens(it.toInt()) },
                        isInt    = true
                    )
                }
                SettingsItem(index = 5) {
                    SettingsSwitch(
                        label    = "流式输出（Stream）",
                        checked  = settings.stream,
                        onChange = { settingsViewModel.updateStream(it) }
                    )
                }
                SettingsItem(index = 6) {
                    SettingsSwitch(
                        label    = "记忆上下文",
                        checked  = settings.contextEnabled,
                        onChange = { settingsViewModel.updateContext(it) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 保存按钮
                SettingsItem(index = 7) {
                    SaveButton(onClick = { settingsViewModel.save() })
                }
            }
        }
    }
}

// ── stagger 进场包装 ──────────────────────────────────────────────────────────
@Composable
private fun SettingsItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    val translateY by animateFloatAsState(
        targetValue   = if (visible) 0f else 22f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "settingsY$index"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration),
        label         = "settingsA$index"
    )

    LaunchedEffect(Unit) {
        delay(index * AnimTokens.StaggerBase + 80L)
        visible = true
    }

    Box(modifier = Modifier.graphicsLayer {
        translationY = translateY
        this.alpha   = alpha
    }) {
        content()
    }
}

// ── 文本输入设置项 ────────────────────────────────────────────────────────────
@Composable
private fun SettingsTextField(
    label    : String,
    value    : String,
    onChange : (String) -> Unit,
    hint     : String = ""
) {
    val borderAlpha by animateFloatAsState(
        targetValue   = if (value.isNotBlank()) 0.75f else 0.25f,
        animationSpec = tween(200),
        label         = "tfBorder_$label"
    )

    OutlinedTextField(
        value         = value,
        onValueChange = onChange,
        label         = { Text(label, color = TextSecondary, fontSize = 13.sp) },
        placeholder   = { Text(hint, color = TextHint, fontSize = 13.sp) },
        modifier      = Modifier.fillMaxWidth(),
        singleLine    = true,
        shape         = RoundedCornerShape(14.dp),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor    = AccentPurple.copy(alpha = borderAlpha),
            unfocusedBorderColor  = GlassBorder.copy(alpha = borderAlpha),
            focusedTextColor      = TextPrimary,
            unfocusedTextColor    = TextPrimary,
            focusedContainerColor   = GlassBg,
            unfocusedContainerColor = GlassBg,
            cursorColor           = AccentPurple
        )
    )
}

// ── Slider 设置项 ─────────────────────────────────────────────────────────────
@Composable
private fun SettingsSlider(
    label    : String,
    value    : Float,
    range    : ClosedFloatingPointRange<Float>,
    onChange : (Float) -> Unit,
    isInt    : Boolean = false
) {
    Surface(
        shape          = RoundedCornerShape(14.dp),
        color          = GlassBg,
        tonalElevation = 2.dp,
        modifier       = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(label, color = TextPrimary, fontSize = 14.sp)
                Text(
                    text       = if (isInt) value.toInt().toString()
                                 else "%.2f".format(value),
                    color      = AccentPurple,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            Slider(
                value        = value,
                onValueChange = onChange,
                valueRange   = range,
                colors       = SliderDefaults.colors(
                    thumbColor         = AccentPurple,
                    activeTrackColor   = AccentPurple,
                    inactiveTrackColor = GlassBorder
                )
            )
        }
    }
}

// ── Switch 设置项 ─────────────────────────────────────────────────────────────
@Composable
private fun SettingsSwitch(
    label    : String,
    checked  : Boolean,
    onChange : (Boolean) -> Unit
) {
    // 整行点击时 scale 弹簧反馈
    val rowScale by animateFloatAsState(
        targetValue   = 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "switchRow_$label"
    )

    Surface(
        shape          = RoundedCornerShape(14.dp),
        color          = GlassBg,
        tonalElevation = 2.dp,
        modifier       = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextPrimary, fontSize = 14.sp)
            Switch(
                checked         = checked,
                onCheckedChange = onChange,
                colors          = SwitchDefaults.colors(
                    checkedThumbColor       = Color.White,
                    checkedTrackColor       = AccentPurple,
                    uncheckedThumbColor     = TextSecondary,
                    uncheckedTrackColor     = GlassBorder
                )
            )
        }
    }
}

// ── 返回按钮（弹性动画）────────────────────────────────────────────────────────
@Composable
private fun AnimatedBackButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val btnScale by animateFloatAsState(
        targetValue   = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "backScale"
    )
    IconButton(
        onClick  = {
            pressed = true
            onClick()
        },
        modifier = Modifier.graphicsLayer {
            scaleX = btnScale
            scaleY = btnScale
        }
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint               = TextPrimary
        )
    }
}

// ── 保存按钮：点击弹簧 + 颜色脉冲 ───────────────────────────────────────────
@Composable
private fun SaveButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    var saved   by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "saveScale"
    )
    val btnColor by animateColorAsState(
        targetValue   = if (saved) StatusTool else AccentPurple,
        animationSpec = tween(300),
        label         = "saveColor"
    )

    LaunchedEffect(pressed) {
        if (pressed) { delay(150); pressed = false }
    }
    LaunchedEffect(saved) {
        if (saved) { delay(1500); saved = false }
    }

    Button(
        onClick = {
            pressed = true
            saved   = true
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape  = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = btnColor)
    ) {
        Text(
            text       = if (saved) "✅ 已保存" else "保存设置",
            fontSize   = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White
        )
    }
}


---
## ui\screen\ReminderScreen.kt
---

package com.lightagent.ui.screen

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ReminderEntity
import com.lightagent.ui.ReminderViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(
    onBack            : () -> Unit,
    reminderViewModel : ReminderViewModel
) {
    val reminders by reminderViewModel.reminders.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    // ── 页面整体淡入 ──────────────────────────────────────────────────────────
    var pageVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pageVisible = true }

    val pageAlpha by animateFloatAsState(
        targetValue   = if (pageVisible) 1f else 0f,
        animationSpec = tween(350),
        label         = "reminderPageAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = pageAlpha }
            .background(
                Brush.verticalGradient(
                    0f   to Color(0xFF0A0E1A),
                    0.5f to Color(0xFF1A0A2E),
                    1f   to Color(0xFF0A0E1A)
                )
            )
    ) {
        // 右上角装饰光晕
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(200.dp)
                .offset(x = 50.dp, y = (-30).dp)
                .run {
                    if (Build.VERSION.SDK_INT >= 31) blur(70.dp) else this
                }
                .background(
                    Brush.radialGradient(
                        listOf(AccentPurple.copy(alpha = 0.2f), Color.Transparent)
                    ),
                    RoundedCornerShape(50)
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "⏰ 提醒事项",
                            color      = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            floatingActionButton = {
                // FAB：弹簧缩放进场
                var fabVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(300)
                    fabVisible = true
                }
                val fabScale by animateFloatAsState(
                    targetValue   = if (fabVisible) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = AnimTokens.BouncyDamping,
                        stiffness    = AnimTokens.BouncyStiffness
                    ),
                    label = "fabScale"
                )
                FloatingActionButton(
                    onClick            = { showAddDialog = true },
                    containerColor     = AccentPurple,
                    contentColor       = Color.White,
                    modifier           = Modifier.graphicsLayer {
                        scaleX = fabScale; scaleY = fabScale
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加提醒")
                }
            }
        ) { padding ->
            LazyColumn(
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical   = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (reminders.isEmpty()) {
                    item {
                        EmptyReminderHint()
                    }
                } else {
                    itemsIndexed(
                        items = reminders,
                        key   = { _, r -> r.id }
                    ) { index, reminder ->
                        StaggeredReminderItem(index = index) {
                            AnimatedReminderCard(
                                title     = reminder.title,
                                time      = formatReminderTime(reminder),
                                isDone    = reminder.isCompleted,
                                onToggle  = { reminderViewModel.toggleDone(reminder) },
                                onDelete  = { reminderViewModel.delete(reminder) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 添加提醒弹窗 ──────────────────────────────────────────────────────────
    if (showAddDialog) {
        AddReminderDialog(
            onConfirm = { title, time ->
                reminderViewModel.add(title, time)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

// ── 时间格式化 ────────────────────────────────────────────────────────────────
private fun formatReminderTime(entity: ReminderEntity): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(entity.triggerAt))
}

// ── 空状态提示 ────────────────────────────────────────────────────────────────
@Composable
private fun EmptyReminderHint() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(200)
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label         = "emptyAlpha"
    )
    val offsetY by animateFloatAsState(
        targetValue   = if (visible) 0f else 20f,
        animationSpec = tween(400),
        label         = "emptyY"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp)
            .graphicsLayer { this.alpha = alpha; translationY = offsetY },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔔", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "还没有提醒事项",
                color    = TextSecondary,
                fontSize = 16.sp
            )
            Text(
                "点击右下角 + 添加",
                color    = TextHint,
                fontSize = 13.sp
            )
        }
    }
}

// ── Stagger 包装器（从下方弹入）──────────────────────────────────────────────
@Composable
private fun StaggeredReminderItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    val translateY by animateFloatAsState(
        targetValue   = if (visible) 0f else 30f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "remY$index"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration),
        label         = "remA$index"
    )

    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * AnimTokens.StaggerBase)
        visible = true
    }

    Box(modifier = Modifier.graphicsLayer {
        translationY = translateY
        this.alpha   = alpha
    }) { content() }
}

// ── 提醒卡片：勾选弹簧 + 删除滑出 ───────────────────────────────────────────
@Composable
private fun AnimatedReminderCard(
    title   : String,
    time    : String,
    isDone  : Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    // 完成态：卡片向右平移 + 透明度
    val doneOffsetX by animateFloatAsState(
        targetValue   = if (isDone) 8f else 0f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "doneX"
    )
    val doneAlpha by animateFloatAsState(
        targetValue   = if (isDone) 0.5f else 1f,
        animationSpec = tween(AnimTokens.SelectionDuration),
        label         = "doneAlpha"
    )
    // 勾选框缩放弹簧
    val checkScale by animateFloatAsState(
        targetValue   = if (isDone) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "checkScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = doneOffsetX
                alpha        = doneAlpha
            },
        shape          = RoundedCornerShape(16.dp),
        color          = GlassBg,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 勾选框
            Checkbox(
                checked         = isDone,
                onCheckedChange = { onToggle() },
                modifier        = Modifier.graphicsLayer {
                    scaleX = checkScale; scaleY = checkScale
                },
                colors = CheckboxDefaults.colors(
                    checkedColor   = AccentPurple,
                    uncheckedColor = TextSecondary
                )
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text           = title,
                    color          = if (isDone) TextSecondary else TextPrimary,
                    fontSize       = 15.sp,
                    fontWeight     = FontWeight.Medium,
                    textDecoration = if (isDone) TextDecoration.LineThrough
                                     else TextDecoration.None
                )
                if (time.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text     = time,
                        color    = TextHint,
                        fontSize = 12.sp
                    )
                }
            }

            // 删除按钮：按下缩放反馈
            var delPressed by remember { mutableStateOf(false) }
            val delScale by animateFloatAsState(
                targetValue   = if (delPressed) 0.8f else 1f,
                animationSpec = spring(
                    dampingRatio = AnimTokens.SnapDamping,
                    stiffness    = AnimTokens.SnapStiffness
                ),
                label = "delScale"
            )
            IconButton(
                onClick  = {
                    delPressed = true
                    onDelete()
                },
                modifier = Modifier.graphicsLayer {
                    scaleX = delScale; scaleY = delScale
                }
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint               = TextSecondary.copy(alpha = 0.5f),
                    modifier           = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── 添加提醒弹窗：弹入动画 ───────────────────────────────────────────────────
@Composable
private fun AddReminderDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleInput by remember { mutableStateOf("") }
    var timeInput  by remember { mutableStateOf("") }

    // 弹窗弹入
    var dialogVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { dialogVisible = true }

    val dialogScale by animateFloatAsState(
        targetValue   = if (dialogVisible) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "dialogScale"
    )
    val dialogAlpha by animateFloatAsState(
        targetValue   = if (dialogVisible) 1f else 0f,
        animationSpec = tween(220),
        label         = "dialogAlpha"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.graphicsLayer {
            scaleX = dialogScale
            scaleY = dialogScale
            alpha  = dialogAlpha
        },
        containerColor = Color(0xFF1A1030),
        title = {
            Text("添加提醒", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = titleInput,
                    onValueChange = { titleInput = it },
                    label         = { Text("提醒内容", color = TextSecondary) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentPurple,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        cursorColor          = AccentPurple
                    )
                )
                OutlinedTextField(
                    value         = timeInput,
                    onValueChange = { timeInput = it },
                    label         = { Text("时间（可选）", color = TextSecondary) },
                    placeholder   = { Text("例：明天 09:00", color = TextHint) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentPurple,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        cursorColor          = AccentPurple
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (titleInput.isNotBlank()) onConfirm(titleInput, timeInput) },
                enabled = titleInput.isNotBlank()
            ) {
                Text("添加", color = AccentPurple, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}


---
## ui\screen\SplashScreen.kt
---

package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

// ── 粒子数据（启动时随机生成一次，不随重组变化）──────────────────────────────
private data class SplashParticle(
    val normX  : Float,  // 归一化坐标 0-1
    val normY  : Float,
    val radius : Float,
    val baseAlpha: Float,
    val speed  : Float,
    val angle  : Float   // 运动方向（弧度）
)

@Composable
fun SplashScreen(onFinished: () -> Unit) {

    // ── 粒子：只初始化一次 ───────────────────────────────────────────────────
    val particles = remember {
        List(70) {
            SplashParticle(
                normX     = Random.nextFloat(),
                normY     = Random.nextFloat(),
                radius    = Random.nextFloat() * 2.5f + 0.8f,
                baseAlpha = Random.nextFloat() * 0.55f + 0.1f,
                speed     = Random.nextFloat() * 0.00025f + 0.00008f,
                angle     = Random.nextFloat() * 2f * PI.toFloat()
            )
        }
    }

    // ── 时间轴驱动粒子漂移 ───────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "splash_inf")
    val particleTime by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 10_000f,
        animationSpec = infiniteRepeatable(tween(100_000, easing = LinearEasing)),
        label         = "pTime"
    )

    // ── 呼吸光圈：大圆缩放 + alpha ───────────────────────────────────────────
    val pulseScale by infiniteTransition.animateFloat(
        initialValue  = 0.82f,
        targetValue   = 1.18f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.12f,
        targetValue   = 0.40f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label         = "pulseA"
    )

    // ── 整体淡入淡出 ─────────────────────────────────────────────────────────
    val screenAlpha = remember { Animatable(0f) }

    // ── 逐字文本状态 ─────────────────────────────────────────────────────────
    val title    = "✨ 天爱星 Agent"
    val subtitle = "你的轻量 AI 助手"
    var titleVisible    by remember { mutableIntStateOf(0) }
    var subtitleVisible by remember { mutableIntStateOf(0) }
    var showCursor      by remember { mutableStateOf(true) }

    // ── 光标闪烁 ─────────────────────────────────────────────────────────────
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 0f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label         = "cursor"
    )

    // ── 主序列 ───────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        screenAlpha.animateTo(1f, tween(700))                 // 淡入

        // 标题逐字
        repeat(title.length) { i ->
            titleVisible = i + 1
            delay(if (title[i] == ' ') 40L else 65L)
        }
        delay(180)

        // 副标题逐字
        repeat(subtitle.length) { i ->
            subtitleVisible = i + 1
            delay(55L)
        }
        showCursor = false                                    // 打完后隐藏光标
        delay(900)

        screenAlpha.animateTo(0f, tween(500))                // 淡出
        onFinished()
    }

    // ─── 渲染 ────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(screenAlpha.value)
            .background(
                Brush.radialGradient(
                    0f   to Color(0xFF1A0A2E),
                    0.6f to Color(0xFF0D1020),
                    1f   to Color(0xFF0A0E1A)
                )
            ),
        contentAlignment = Alignment.Center
    ) {

        // ── 粒子层 ───────────────────────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            particles.forEach { p ->
                val x = ((p.normX + cos(p.angle) * p.speed * particleTime) % 1f + 1f) % 1f
                val y = ((p.normY + sin(p.angle) * p.speed * particleTime) % 1f + 1f) % 1f
                // 靠近中心的粒子更亮（营造景深感）
                val dist = sqrt((x - 0.5f).pow(2) + (y - 0.5f).pow(2))
                val alphaFactor = 1f - (dist * 0.8f).coerceIn(0f, 0.7f)
                drawCircle(
                    color  = AccentPurple.copy(alpha = p.baseAlpha * alphaFactor),
                    radius = p.radius,
                    center = Offset(x * size.width, y * size.height)
                )
            }
        }

        // ── 呼吸光圈（模糊大圆做光晕）───────────────────────────────────────
        Canvas(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                .blur(48.dp)
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF6C63FF).copy(alpha = pulseAlpha),
                        Color(0xFF5EAEFF).copy(alpha = pulseAlpha * 0.4f),
                        Color.Transparent
                    )
                )
            )
        }

        // ── 第二层更小的光圈（增加层次感）────────────────────────────────────
        Canvas(
            modifier = Modifier
                .size(140.dp)
                .graphicsLayer {
                    scaleX = 2f - pulseScale  // 反相，一大一小交替
                    scaleY = 2f - pulseScale
                }
                .blur(28.dp)
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AccentBlue.copy(alpha = pulseAlpha * 0.6f),
                        Color.Transparent
                    )
                )
            )
        }

        // ── 文字层 ───────────────────────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 标题：逐字显示 + 字母间距
            Text(
                text          = title.take(titleVisible),
                color         = TextPrimary,
                fontSize      = 36.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            // 副标题：逐字 + 闪烁光标
            if (subtitleVisible > 0 || titleVisible == title.length) {
                Text(
                    text = buildAnnotatedString {
                        append(subtitle.take(subtitleVisible))
                        if (showCursor && subtitleVisible < subtitle.length) {
                            withStyle(SpanStyle(
                                color = AccentPurple.copy(alpha = cursorAlpha)
                            )) { append("▋") }
                        }
                    },
                    color         = TextSecondary,
                    fontSize      = 15.sp,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}


---
## ui\theme\Color.kt
---

package com.lightagent.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.Color

val DeepNavy          = Color(0xFF0A0E1A)
val GradientStart     = Color(0xFF0A0E1A)
val GradientMid       = Color(0xFF1A0A2E)
val GradientEnd       = Color(0xFF0D1B2A)
val GlassBg           = Color(0x33FFFFFF)
val GlassBorder       = Color(0x22FFFFFF)
val UserBubble        = Color(0xFF6C63FF)
val AssistantBubble   = Color(0x44FFFFFF)
val TextPrimary       = Color(0xFFEFEFFF)
val TextSecondary     = Color(0xAAB0B8FF)
val TextHint          = Color(0x88FFFFFF)
val StatusThinking    = Color(0xFFFFD166)
val StatusTool        = Color(0xFF06D6A0)
val StatusIdle        = Color(0xFF8EC5FC)
val AccentPurple      = Color(0xFF9B7FFF)
val AccentBlue        = Color(0xFF5EAEFF)
val Emerald           = Color(0xFF50C878)

object AnimTokens {
    // ── stagger / fade ──────────────────────────────────────────────────────
    const val StaggerBase       = 55L    // 抽屉列表每项延迟 (ms)
    const val MessageStagger    = 25L    // 消息列表每项延迟 (ms)，比抽屉快
    const val MessageSlideInY   = 28f    // 新消息弹入起始偏移 px
    const val FadeDuration      = 220    // 通用淡变时长 (ms)
    const val SelectionDuration = 180    // 选中态过渡时长 (ms)

    // ── 弹簧：气泡 / 按钮（欠阻尼，有轻微回弹）────────────────────────────
    const val BouncyDamping     = Spring.DampingRatioMediumBouncy  // 0.5f
    const val BouncyStiffness   = Spring.StiffnessMedium           // 400f

    // ── 弹簧：快速响应（按钮缩放，不要过多回弹）────────────────────────────
    const val SnapDamping       = Spring.DampingRatioLowBouncy     // 0.75f
    const val SnapStiffness     = Spring.StiffnessHigh             // 1000f

    // ── 弹簧：抽屉 stagger（稍微重一点）────────────────────────────────────
    const val DrawerDamping     = Spring.DampingRatioMediumBouncy
    const val DrawerStiffness   = Spring.StiffnessMediumLow        // 200f，慢而有弹性
}


---
## ui\theme\Theme.kt
---

package com.lightagent.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary       = AccentPurple,
    secondary     = AccentBlue,
    background    = DeepNavy,
    surface       = GlassBg,
    onPrimary     = Color.White,
    onBackground  = TextPrimary,
    onSurface     = TextPrimary,
)

@Composable
fun LightAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        content     = content
    )
}


---
## ui\theme\Type.kt
---

package com.lightagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 15.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Light,
        fontSize   = 12.sp,
        lineHeight = 18.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 16.sp
    )
)


