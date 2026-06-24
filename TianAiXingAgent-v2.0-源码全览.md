# 天爱星Agent v2.0 — 源码全览

生成时间: 2026-06-18

Kotlin 文件: 36 个

XML 文件: 4 个

Gradle 文件: 4 个

总计: 44 个源文件

---

## 目录结构

```
app/src/main/java/com/lightagent/
├── MainActivity.kt
│   ├── lightagent\MainActivity.kt
│   ├── lightagent\agent\ChatState.kt
│   ├── lightagent\agent\PlannerAgent.kt
│   ├── lightagent\live2d\Live2DController.kt
│   ├── lightagent\llm\LLMClient.kt
│   ├── lightagent\memory\ConversationDao.kt
│   ├── lightagent\memory\ConversationEntity.kt
│   ├── lightagent\memory\ConversationRepository.kt
│   ├── lightagent\memory\MessageEntity.kt
│   ├── lightagent\memory\ReminderRepository.kt
│   ├── lightagent\memory\UserProfileMemory.kt
│   ├── lightagent\notification\ReminderReceiver.kt
│   ├── lightagent\notification\ReminderScheduler.kt
│   ├── lightagent\tools\NoteTool.kt
│   ├── lightagent\tools\OpenAppTool.kt
│   ├── lightagent\tools\ReminderTool.kt
│   ├── lightagent\tools\Tool.kt
│   ├── lightagent\tools\WeatherTool.kt
│   ├── lightagent\tts\TTSController.kt
│   ├── lightagent\ui\BackgroundViewModel.kt
│   ├── lightagent\ui\ChatViewModel.kt
│   ├── lightagent\ui\ReminderViewModel.kt
│   ├── lightagent\ui\components\CharacterPanel.kt
│   ├── lightagent\ui\components\GlassCard.kt
│   ├── lightagent\ui\components\InputBar.kt
│   ├── lightagent\ui\components\MessageBubble.kt
│   ├── lightagent\ui\components\StatusIndicator.kt
│   ├── lightagent\ui\screen\BackgroundSettingsSheet.kt
│   ├── lightagent\ui\screen\ChatBackground.kt
│   ├── lightagent\ui\screen\ChatScreen.kt
│   ├── lightagent\ui\screen\ConversationDrawer.kt
│   ├── lightagent\ui\screen\ReminderScreen.kt
│   ├── lightagent\ui\screen\SplashScreen.kt
│   ├── lightagent\ui\theme\Color.kt
│   ├── lightagent\ui\theme\Theme.kt
│   ├── lightagent\ui\theme\Type.kt
│
app/src/main/
├── AndroidManifest.xml
├── res/
│   ├── drawable/character_default.xml
│   ├── mipmap-*/ic_launcher*.png  (启动图标)
│   └── values/strings.xml, themes.xml
└── assets/backgrounds/bg_default_1~43.png  (43张背景)

gradle/
├── build.gradle (root)
├── app/build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Kotlin 源码 (36 个文件)

### `lightagent\MainActivity.kt`

```kt
package com.lightagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.lightagent.llm.LLMClient
import com.lightagent.ui.screen.ChatScreen
import com.lightagent.ui.screen.SplashScreen
import com.lightagent.ui.theme.LightAgentTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 权限结果 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── DeepSeek API Key ──
        LLMClient.apiKey = "sk-2c83754549b4455cbc943c36586db79d"

        // Android 13+ 动态申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            LightAgentTheme {
                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    SplashScreen { showSplash = false }
                } else {
                    ChatScreen()
                }
            }
        }
    }
}
```

### `lightagent\agent\ChatState.kt`

```kt
package com.lightagent.agent

sealed class ChatState {
    object Idle        : ChatState()
    object Thinking    : ChatState()
    object CallingTool : ChatState()
    data class Error(val message: String) : ChatState()
}
```

### `lightagent\agent\PlannerAgent.kt`

```kt
package com.lightagent.agent

import android.content.Context
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ReminderRepository
import com.lightagent.tools.*
import com.lightagent.llm.LLMClient

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
        You are LightAgent, a helpful AI assistant running on Android.
        You can use the following tools:

        1. get_weather(city): Get weather for a city
        2. save_note(title, content): Save a note
        3. open_app(package_name): Open an Android app
        4. add_reminder(title, note, datetime): Set a system reminder notification.
           datetime format: "yyyy-MM-dd HH:mm"
           Example: add_reminder("Buy milk", "From the store", "2025-01-20 08:00")

        To use a tool, respond with:
        TOOL: tool_name
        PARAMS: {"key": "value"}

        After getting the tool result, provide a natural response to the user.
        Always respond in the same language the user uses.
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
}
```

### `lightagent\live2d\Live2DController.kt`

```kt
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
```

### `lightagent\llm\LLMClient.kt`

```kt
package com.lightagent.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LLMClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val endpoint = "https://api.deepseek.com/v1/chat/completions"
    private val model    = "deepseek-chat"

    // ─── Singleton ─────────────────────────────────────────────────────────

    companion object {
        @Volatile
        private var INSTANCE: LLMClient? = null
        lateinit var apiKey: String

        fun getInstance(context: Context? = null): LLMClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LLMClient(apiKey).also { INSTANCE = it }
            }
        }
    }

    // ─── 通用多轮对话 ─────────────────────────────────────────────────────

    suspend fun chat(messages: List<Map<String, String>>): String = withContext(Dispatchers.IO) {
        val jsonArray = JSONArray()
        messages.forEach { msg ->
            jsonArray.put(JSONObject().apply {
                put("role", msg["role"] ?: "user")
                put("content", msg["content"] ?: "")
            })
        }
        callApi(jsonArray)
    }

    /**
     * 单轮对话
     */
    suspend fun chat(prompt: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        callApi(messages)
    }

    /**
     * 多轮对话（带历史上下文 + system prompt）
     */
    suspend fun chatWithHistory(
        history: List<Pair<String, String>>,
        systemPrompt: String = ""
    ): String = withContext(Dispatchers.IO) {

        val messages = JSONArray()

        if (systemPrompt.isNotEmpty()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        history.forEach { (role, content) ->
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }

        callApi(messages)
    }

    private fun callApi(messages: JSONArray): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", 2048)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw Exception("API 请求失败：${response.code}\n$errorBody")
        }

        val responseBody = response.body?.string()
            ?: throw Exception("响应体为空")

        // DeepSeek 返回 OpenAI 兼容格式
        return try {
            val json = JSONObject(responseBody)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            throw Exception("解析响应失败：${e.message}\n原始响应：$responseBody")
        }
    }
}
```

### `lightagent\memory\ConversationDao.kt`

```kt
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
```

### `lightagent\memory\ConversationEntity.kt`

```kt
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
```

### `lightagent\memory\ConversationRepository.kt`

```kt
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
```

### `lightagent\memory\MessageEntity.kt`

```kt
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
```

### `lightagent\memory\ReminderRepository.kt`

```kt
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

    suspend fun deleteReminder(id: String) = dao.deleteReminder(id)

    suspend fun getReminderById(id: String) = dao.getReminderById(id)
}
```

### `lightagent\memory\UserProfileMemory.kt`

```kt
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
```

### `lightagent\notification\ReminderReceiver.kt`

```kt
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
```

### `lightagent\notification\ReminderScheduler.kt`

```kt
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
```

### `lightagent\tools\NoteTool.kt`

```kt
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
```

### `lightagent\tools\OpenAppTool.kt`

```kt
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
```

### `lightagent\tools\ReminderTool.kt`

```kt
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
```

### `lightagent\tools\Tool.kt`

```kt
package com.lightagent.tools

import org.json.JSONObject

interface Tool {
    val name: String
    val description: String get() = ""
    suspend fun execute(params: JSONObject): String
}
```

### `lightagent\tools\WeatherTool.kt`

```kt
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
```

### `lightagent\tts\TTSController.kt`

```kt
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
```

### `lightagent\ui\BackgroundViewModel.kt`

```kt
package com.lightagent.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 背景来源
 */
sealed class BackgroundSource {
    /** 使用 assets 内的默认图片 */
    data class Asset(val fileName: String) : BackgroundSource()
    /** 使用用户从图库选择的图片 */
    data class Custom(val uri: Uri) : BackgroundSource()
    /** 纯色背景（降级兜底） */
    object SolidColor : BackgroundSource()
}

class BackgroundViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("bg_prefs", Context.MODE_PRIVATE)

    // assets 内预置的背景图文件名列表
    // 把图片放到 app/src/main/assets/backgrounds/ 下
    private val defaultBackgrounds = (1..43).map { "bg_default_$it.png" }

    private val _background = MutableStateFlow<BackgroundSource>(loadSaved())
    val background: StateFlow<BackgroundSource> = _background

    // ─── 随机切换默认背景 ────────────────────────────────────────────────────
    fun randomBackground() {
        val current = (_background.value as? BackgroundSource.Asset)?.fileName
        val candidates = defaultBackgrounds.filter { it != current }
        val next = candidates.random()
        _background.value = BackgroundSource.Asset(next)
        saveAsset(next)
    }

    // ─── 用户自选图片 ────────────────────────────────────────────────────────
    fun setCustomBackground(uri: Uri) {
        // 持久化 URI 读取权限
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
        } catch (_: Exception) {}

        _background.value = BackgroundSource.Custom(uri)
        prefs.edit()
            .putString(KEY_TYPE, TYPE_CUSTOM)
            .putString(KEY_URI, uri.toString())
            .apply()
    }

    // ─── 恢复默认（Asset 第一张）────────────────────────────────────────────
    fun resetToDefault() {
        val first = defaultBackgrounds.first()
        _background.value = BackgroundSource.Asset(first)
        saveAsset(first)
    }

    // ─── 持久化 ──────────────────────────────────────────────────────────────
    private fun saveAsset(fileName: String) {
        prefs.edit()
            .putString(KEY_TYPE, TYPE_ASSET)
            .putString(KEY_ASSET, fileName)
            .apply()
    }

    private fun loadSaved(): BackgroundSource {
        return when (prefs.getString(KEY_TYPE, TYPE_ASSET)) {
            TYPE_CUSTOM -> {
                val uriStr = prefs.getString(KEY_URI, null)
                if (uriStr != null) BackgroundSource.Custom(Uri.parse(uriStr))
                else BackgroundSource.Asset(defaultBackgrounds.first())
            }
            else -> {
                val asset = prefs.getString(KEY_ASSET, defaultBackgrounds.first())
                    ?: defaultBackgrounds.first()
                BackgroundSource.Asset(asset)
            }
        }
    }

    companion object {
        private const val KEY_TYPE  = "bg_type"
        private const val KEY_ASSET = "bg_asset"
        private const val KEY_URI   = "bg_uri"
        private const val TYPE_ASSET  = "asset"
        private const val TYPE_CUSTOM = "custom"
    }
}
```

### `lightagent\ui\ChatViewModel.kt`

```kt
package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.agent.PlannerAgent
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ConversationEntity
import com.lightagent.memory.ConversationRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatMessage(
    val role: String,
    val content: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db   = AgentDatabase.getInstance(application)
    private val repo = ConversationRepository(db.conversationDao())

    // ─── 会话列表 ─────────────────────────────────────────────────────────────
    val conversations: StateFlow<List<ConversationEntity>> = repo.allConversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ─── 当前会话 ID ──────────────────────────────────────────────────────────
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId

    // ─── 当前会话消息（UI 展示用）────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    // ─── Loading 状态 ─────────────────────────────────────────────────────────
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ─── 输入框 ───────────────────────────────────────────────────────────────
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    // ─── PlannerAgent（懒加载，等会话初始化后再用）───────────────────────────
    private var plannerAgent: PlannerAgent? = null

    init {
        // 启动时自动加载最近一条会话，没有则新建
        viewModelScope.launch {
            val all = conversations.first { it.isNotEmpty() || true }
            if (all.isEmpty()) {
                createNewConversation()
            } else {
                switchConversation(all.first())
            }
        }
    }

    // ─── 输入框 ───────────────────────────────────────────────────────────────
    fun updateInput(value: String) { _input.value = value }

    // ─── 新建会话 ─────────────────────────────────────────────────────────────
    fun createNewConversation() = viewModelScope.launch {
        val conv = repo.createConversation("New Chat")
        _currentConversationId.value = conv.id
        _messages.value = emptyList()
        plannerAgent = buildAgent()
    }

    // ─── 切换会话 ─────────────────────────────────────────────────────────────
    fun switchConversation(conv: ConversationEntity) = viewModelScope.launch {
        _currentConversationId.value = conv.id
        // 从数据库加载历史消息
        val history = repo.getMessagesOnce(conv.id)
        _messages.value = history.map { ChatMessage(it.role, it.content) }
        plannerAgent = buildAgent()
    }

    // ─── 删除会话 ─────────────────────────────────────────────────────────────
    fun deleteConversation(conv: ConversationEntity) = viewModelScope.launch {
        repo.deleteConversation(conv.id)
        // 如果删的是当前会话，切到最新的或新建
        if (_currentConversationId.value == conv.id) {
            val remaining = conversations.value.filter { it.id != conv.id }
            if (remaining.isEmpty()) createNewConversation()
            else switchConversation(remaining.first())
        }
    }

    // ─── 发送消息 ─────────────────────────────────────────────────────────────
    fun sendMessage(userInput: String) {
        val convId = _currentConversationId.value ?: return
        val agent  = plannerAgent ?: return

        viewModelScope.launch {
            // 1. 追加用户消息到 UI
            val userMsg = ChatMessage("user", userInput)
            _messages.value = _messages.value + userMsg
            _isLoading.value = true

            // 2. 持久化用户消息
            repo.saveMessage(convId, "user", userInput)

            // 3. 自动给会话命名（第一条消息时）
            val convTitle = conversations.value.find { it.id == convId }?.title
            if (convTitle == "New Chat" && _messages.value.size == 1) {
                val title = userInput.take(20).let { if (userInput.length > 20) "$it…" else it }
                repo.renameConversation(convId, title)
            }

            // 4. 调用 Agent
            try {
                val reply = agent.chat(userInput)
                val assistantMsg = ChatMessage("assistant", reply)
                _messages.value = _messages.value + assistantMsg

                // 5. 持久化 AI 回复
                repo.saveMessage(convId, "assistant", reply)
            } catch (e: Exception) {
                val errMsg = ChatMessage("assistant", "出错了：${e.message}")
                _messages.value = _messages.value + errMsg
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isBlank()) return
        _input.value = ""
        sendMessage(text)
    }

    // ─── 构建 Agent（带历史上下文）──────────────────────────────────────────
    private fun buildAgent(): PlannerAgent {
        val context = getApplication<Application>()
        val history = _messages.value.map {
            mapOf("role" to it.role, "content" to it.content)
        }
        return PlannerAgent.create(context, history)
    }
}
```

### `lightagent\ui\ReminderViewModel.kt`

```kt
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

class ReminderViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ReminderRepository(
        AgentDatabase.getInstance(application).reminderDao()
    )

    val reminders: StateFlow<List<ReminderEntity>> = repo.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markDone(id: String) = viewModelScope.launch {
        repo.markDone(id)
        ReminderScheduler.cancel(getApplication(), id)
    }

    fun delete(id: String) = viewModelScope.launch {
        repo.deleteReminder(id)
        ReminderScheduler.cancel(getApplication(), id)
    }
}
```

### `lightagent\ui\components\CharacterPanel.kt`

```kt
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
```

### `lightagent\ui\components\GlassCard.kt`

```kt
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
```

### `lightagent\ui\components\InputBar.kt`

```kt
package com.lightagent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
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
                imageVector        = Icons.Rounded.Send,
                contentDescription = "发送",
                tint               = if (value.isNotEmpty()) Color.White else TextHint
            )
        }
    }
}
```

### `lightagent\ui\components\MessageBubble.kt`

```kt
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
```

### `lightagent\ui\components\StatusIndicator.kt`

```kt
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
```

### `lightagent\ui\screen\BackgroundSettingsSheet.kt`

```kt
package com.lightagent.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 背景设置底部弹出菜单
 * 在 ChatScreen 顶部菜单或长按背景时触发
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsSheet(
    onDismiss: () -> Unit,
    onRandomBackground: () -> Unit,
    onCustomBackground: (Uri) -> Unit,
    onResetDefault: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onCustomBackground(uri)
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "\uD83C\uDFA8 背景设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 随机切换
            SettingsItem(
                icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                title = "随机切换背景",
                subtitle = "从预置图库随机挑一张",
                onClick = {
                    onRandomBackground()
                    onDismiss()
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 自选图片
            SettingsItem(
                icon = {
                    Icon(Icons.Default.Star, contentDescription = null)
                },
                title = "从相册选择",
                subtitle = "使用你自己的图片",
                onClick = { launcher.launch("image/*") }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 恢复默认
            SettingsItem(
                icon = {
                    Text("\u21A9", style = MaterialTheme.typography.titleMedium)
                },
                title = "恢复默认",
                subtitle = "回到第一张预置背景",
                onClick = {
                    onResetDefault()
                    onDismiss()
                }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                icon()
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

### `lightagent\ui\screen\ChatBackground.kt`

```kt
package com.lightagent.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lightagent.ui.BackgroundSource

/**
 * 聊天背景层
 * 根据 [BackgroundSource] 自动渲染：
 *   - Asset   → 从 assets 加载
 *   - Custom  → 用 Coil 加载本地 URI
 *   - SolidColor → 纯色兜底
 *
 * Live2D 模型将来叠加在此层之上，在 ChatScreen 中处理
 */
@Composable
fun ChatBackground(
    source: BackgroundSource,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (source) {
            is BackgroundSource.Asset -> {
                AssetBackground(fileName = source.fileName)
            }
            is BackgroundSource.Custom -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(source.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is BackgroundSource.SolidColor -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                )
            }
        }
    }
}

@Composable
private fun AssetBackground(fileName: String) {
    val context = LocalContext.current
    val bitmap  = remember(fileName) {
        runCatching {
            context.assets.open("backgrounds/$fileName").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        // 图片加载失败时纯色兜底
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    }
}
```

### `lightagent\ui\screen\ChatScreen.kt`

```kt
package com.lightagent.ui.screen

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.live2d.Live2DController
import com.lightagent.live2d.NoOpLive2DController
import com.lightagent.tts.NoOpTTSController
import com.lightagent.tts.TTSController
import com.lightagent.ui.BackgroundViewModel
import com.lightagent.ui.ChatViewModel
import com.lightagent.ui.ReminderViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = viewModel(),
    reminderViewModel: ReminderViewModel = viewModel(),
    backgroundViewModel: BackgroundViewModel = viewModel(),
    // Live2D 和 TTS 接口预留，默认用空实现
    // 接入真实实现时从外部注入即可
    live2DController: Live2DController = remember { NoOpLive2DController() },
    ttsController: TTSController = remember { NoOpTTSController() }
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showReminder by remember { mutableStateOf(false) }
    var showBgSheet by remember { mutableStateOf(false) }

    val conversations by chatViewModel.conversations.collectAsState()
    val currentConvId by chatViewModel.currentConversationId.collectAsState()
    val messages by chatViewModel.messages.collectAsState()
    val isLoading by chatViewModel.isLoading.collectAsState()
    val background by backgroundViewModel.background.collectAsState()
    val listState = rememberLazyListState()

    // 有新消息时自动滚底
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // TTS 随 AI 回复朗读（接口预留，NoOp 时什么都不发生）
    LaunchedEffect(messages) {
        val last = messages.lastOrNull()
        if (last != null && last.role == "assistant") {
            ttsController.speak(
                text = last.content,
                onPlaybackProgress = { volume ->
                    live2DController.setLipSync(volume)
                }
            )
        }
    }

    if (showReminder) {
        ReminderScreen(
            onBack = { showReminder = false },
            reminderViewModel = reminderViewModel
        )
        return
    }

    // 背景设置底栏
    if (showBgSheet) {
        BackgroundSettingsSheet(
            onDismiss = { showBgSheet = false },
            onRandomBackground = { backgroundViewModel.randomBackground() },
            onCustomBackground = { uri: Uri -> backgroundViewModel.setCustomBackground(uri) },
            onResetDefault = { backgroundViewModel.resetToDefault() }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentConversationId = currentConvId,
                onSelectConversation = { conv ->
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
                    showReminder = true
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        // 背景层 + 内容层叠加
        Box(modifier = Modifier.fillMaxSize()) {

            // ── 背景层 ──────────────────────────────────────────────────────
            ChatBackground(source = background)

            // ── Live2D 层占位（接入时在这里插入 Live2DView）────────────────
            // 示例：
            // if (live2DController.isReady) {
            //     Live2DComposable(
            //         controller = live2DController,
            //         modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp)
            //     )
            // }

            // ── UI 内容层 ────────────────────────────────────────────────────
            Scaffold(
                containerColor = Color.Transparent,   // Scaffold 背景透明，露出背景层
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                conversations.find { it.id == currentConvId }?.title ?: "LightAgent"
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "菜单")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showBgSheet = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "背景设置")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        )
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { Spacer(Modifier.height(8.dp)) }
                        items(messages) { msg ->
                            ChatBubble(role = msg.role, content = msg.content)
                        }
                        if (isLoading) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }

                    ChatInputBar(
                        onSend = { chatViewModel.sendMessage(it) },
                        enabled = !isLoading
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(role: String, content: String) {
    val isUser = role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatInputBar(onSend: (String) -> Unit, enabled: Boolean) {
    var text by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("输入消息…") },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text.trim())
                        text = ""
                    }
                },
                enabled = enabled && text.isNotBlank()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (enabled && text.isNotBlank())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

### `lightagent\ui\screen\ConversationDrawer.kt`

```kt
package com.lightagent.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ConversationEntity
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ConversationDrawer(
    conversations: List<ConversationEntity>,
    currentConversationId: String?,
    onSelectConversation: (ConversationEntity) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (ConversationEntity) -> Unit,
    onOpenReminders: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 48.dp, bottom = 16.dp)
    ) {
        // 标题
        Text(
            text = "💬 会话列表",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // 新建会话按钮
        OutlinedButton(
            onClick = onNewConversation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("新建会话")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 会话列表
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(conversations, key = { it.id }) { conv ->
                ConversationItem(
                    conversation = conv,
                    isSelected = conv.id == currentConversationId,
                    onSelect = { onSelectConversation(conv) },
                    onDelete = { onDeleteConversation(conv) }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 提醒事项入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenReminders() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = "提醒事项",
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text("⏰ 提醒事项", fontSize = 15.sp)
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: ConversationEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val timeStr = sdf.format(Date(conversation.updatedAt))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.title,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp
            )
            Text(
                text = timeStr,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
```

### `lightagent\ui\screen\ReminderScreen.kt`

```kt
package com.lightagent.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.memory.ReminderEntity
import com.lightagent.ui.ReminderViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(
    onBack: () -> Unit,
    reminderViewModel: ReminderViewModel = viewModel()
) {
    val reminders by reminderViewModel.reminders.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⏰ 提醒事项") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (reminders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有提醒事项\n跟 AI 说\"提醒我...\"来添加吧 😊",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(reminders, key = { it.id }) { reminder ->
                    ReminderCard(
                        reminder = reminder,
                        onMarkDone = { reminderViewModel.markDone(reminder.id) },
                        onDelete = { reminderViewModel.delete(reminder.id) }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ReminderCard(
    reminder: ReminderEntity,
    onMarkDone: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val timeStr = sdf.format(Date(reminder.triggerAt))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (reminder.isCompleted)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reminder.title,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (reminder.isCompleted) TextDecoration.LineThrough else null
                )
                if (reminder.note.isNotBlank()) {
                    Text(
                        text = reminder.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (reminder.isCompleted) TextDecoration.LineThrough else null
                    )
                }
                Text(
                    text = "🕐 $timeStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!reminder.isCompleted) {
                IconButton(onClick = onMarkDone) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "标记完成",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
```

### `lightagent\ui\screen\SplashScreen.kt`

```kt
package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: () -> Unit) {

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(800))
        delay(1200)
        alpha.animateTo(0f, tween(600))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(GradientStart, GradientMid, GradientEnd)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.alpha(alpha.value)
        ) {
            Text(
                text       = "✨ LightAgent",
                color      = TextPrimary,
                fontSize   = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text     = "你的轻量AI助手",
                color    = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}
```

### `lightagent\ui\theme\Color.kt`

```kt
package com.lightagent.ui.theme

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
```

### `lightagent\ui\theme\Theme.kt`

```kt
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
```

### `lightagent\ui\theme\Type.kt`

```kt
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
```

## XML 配置文件 (4 个)

### `app\src\main\AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/Theme.LightAgent"
        android:supportsRtl="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".notification.ReminderReceiver"
            android:exported="false" />

    </application>

</manifest>
```

### `app\src\main\res\values\strings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">天爱星Agent</string>
</resources>
```

### `app\src\main\res\values\themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.LightAgent" parent="@android:style/Theme.Material.NoActionBar.Fullscreen" />
</resources>
```

### `app\src\main\res\drawable\character_default.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="200dp"
    android:height="200dp"
    android:viewportWidth="200"
    android:viewportHeight="200">

    <!-- Body -->
    <path
        android:fillColor="#9B7FFF"
        android:pathData="M60,140 Q100,160 140,140 L130,200 L70,200 Z" />

    <!-- Head -->
    <path
        android:fillColor="#FFD6B0"
        android:pathData="M70,60 Q100,30 130,60 Q140,90 100,110 Q60,90 70,60 Z" />

    <!-- Left eye -->
    <path
        android:fillColor="#6C63FF"
        android:pathData="M79,75 A6,7 0 1,0 91,75 A6,7 0 1,0 79,75 Z" />

    <!-- Right eye -->
    <path
        android:fillColor="#6C63FF"
        android:pathData="M109,75 A6,7 0 1,0 121,75 A6,7 0 1,0 109,75 Z" />

    <!-- Left eye highlight -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M85,73 A2,2 0 1,0 89,73 A2,2 0 1,0 85,73 Z" />

    <!-- Right eye highlight -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M115,73 A2,2 0 1,0 119,73 A2,2 0 1,0 115,73 Z" />

    <!-- Mouth -->
    <path
        android:strokeColor="#FF8FAB"
        android:strokeWidth="2"
        android:fillColor="#00000000"
        android:pathData="M90,90 Q100,98 110,90" />

    <!-- Hair -->
    <path
        android:fillColor="#4A3F8F"
        android:pathData="M65,65 Q70,30 100,25 Q130,30 135,65 Q125,45 100,42 Q75,45 65,65 Z" />

    <!-- Left ear -->
    <path
        android:fillColor="#FFD6B0"
        android:pathData="M61,80 A7,9 0 1,0 75,80 A7,9 0 1,0 61,80 Z" />

    <!-- Right ear -->
    <path
        android:fillColor="#FFD6B0"
        android:pathData="M125,80 A7,9 0 1,0 139,80 A7,9 0 1,0 125,80 Z" />

</vector>
```

## Gradle 构建文件 (4 个)

### `app\build.gradle`

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlin-kapt'
}

android {
    namespace 'com.lightagent'
    compileSdk 34

    defaultConfig {
        applicationId "com.lightagent"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildFeatures {
        compose true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {

    // Compose BOM
    def composeBom = platform('androidx.compose:compose-bom:2024.02.00')
    implementation composeBom

    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-graphics'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.material:material-icons-extended'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.activity:activity-compose:1.8.2'

    // ViewModel
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

    // HTTP
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // Room（长期记忆）
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'

    // JSON
    implementation 'org.json:json:20231013'

    // Coil（图片加载）
    implementation 'io.coil-kt:coil-compose:2.6.0'
}
```

### `build.gradle`

```groovy
plugins {
    id 'com.android.application' version '8.2.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.22' apply false
}
```

### `settings.gradle`

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LightAgent"
include ':app'
```

### `gradle.properties`

```groovy
android.useAndroidX=true
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
android.nonTransitiveRClass=true
```
