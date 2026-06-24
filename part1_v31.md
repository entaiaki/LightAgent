# 天爱星Agent v3.1 — 完整源码

> **包名**: `com.lightagent`  
> **文件数**: 40 个 Kotlin 源文件 + 5 个配置文件  
> **构建状态**: 零警告  
> **Compose BOM**: 2024.09.03 (Compose 1.7.x)  
> **Kotlin**: 1.9.22 | **AGP**: 8.2.2 | **Room**: 2.6.1 | **Coil**: 2.6.0  

## 目录

1. 核心入口
2. Agent 引擎
3. LLM 客户端
4. 持久记忆 (Room)
5. 通知系统
6. 工具集
7. Live2D / TTS 接口
8. ViewModel 层
9. UI Screen 层
10. UI 组件层
11. 主题系统
12. 构建配置

---

## 1. 核心入口

### 1.1 MainActivity.kt

```kotlin
package com.lightagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.lightagent.llm.LLMClient
import com.lightagent.ui.screen.ChatScreen
import com.lightagent.ui.screen.SplashScreen
import com.lightagent.ui.theme.LightAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LLMClient.getInstance(this)
        setContent {
            LightAgentTheme {
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
```

---

## 2. Agent 引擎

### 2.1 ChatState.kt

```kotlin
package com.lightagent.agent

sealed class ChatState {
    object Idle        : ChatState()
    object Thinking    : ChatState()
    object CallingTool : ChatState()
    data class Error(val message: String) : ChatState()
}
```

### 2.2 PlannerAgent.kt

```kotlin
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
        fun create(context: Context, history: List<Map<String, String>> = emptyList()): PlannerAgent {
            val db = AgentDatabase.getInstance(context)
            val reminderRepo = ReminderRepository(db.reminderDao())
            val tools = listOf(
                WeatherTool(), NoteTool(context), OpenAppTool(context),
                ReminderTool(context, reminderRepo)
            )
            val llmClient = LLMClient.getInstance(context)
            return PlannerAgent(llmClient, tools, history)
        }
    }

    private val systemPrompt = """
        You are LightAgent，一个运行在 Android 上的 AI 助手。
        可用工具:
        1. get_weather(city) - 查询天气
        2. save_note(content) - 保存笔记
        3. open_app(package_name) - 打开应用
        4. add_reminder(title, note, datetime:"yyyy-MM-dd HH:mm") - 添加提醒
        使用时，先输出 TOOL:工具名，然后在下一行输出 PARAMS:{"key":"value"}。
        请始终用中文回复用户。
    """.trimIndent()

    suspend fun chat(userMessage: String): String {
        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to systemPrompt))
        messages.addAll(history)
        messages.add(mapOf("role" to "user", "content" to userMessage))
        var response = llmClient.chat(messages)
        repeat(3) {
            val toolName   = extractToolName(response)   ?: return response
            val toolParams = extractToolParams(response) ?: return response
            val tool = tools.find { it.name == toolName } ?: return response
            val toolResult = tool.execute(toolParams)
            messages.add(mapOf("role" to "assistant", "content" to response))
            messages.add(mapOf("role" to "tool", "content" to "工具结果: $toolResult"))
            response = llmClient.chat(messages)
        }
        return response
    }

    private fun extractToolName(response: String): String? =
        response.lines().find { it.startsWith("TOOL:") }?.removePrefix("TOOL:")?.trim()

    private fun extractToolParams(response: String): org.json.JSONObject? {
        val line = response.lines().find { it.startsWith("PARAMS:") } ?: return null
        return try { org.json.JSONObject(line.removePrefix("PARAMS:").trim()) }
        catch (_: Exception) { null }
    }
}
```

---

## 3. LLM 客户端

### 3.1 LLMClient.kt

```kotlin
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

enum class LLMProvider(val displayName: String, val baseUrl: String, val defaultModel: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-turbo"),
    CUSTOM("自定义", "", "")
}

data class LLMConfig(
    val provider: LLMProvider = LLMProvider.DEEPSEEK,
    val apiKey: String = "",
    val model: String = LLMProvider.DEEPSEEK.defaultModel,
    val customUrl: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048
) {
    val endpoint: String get() = if (provider == LLMProvider.CUSTOM) customUrl else provider.baseUrl
}

class LLMClient(private var config: LLMConfig) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun updateConfig(newConfig: LLMConfig) { config = newConfig }
    fun getConfig(): LLMConfig = config

    companion object {
        @Volatile private var INSTANCE: LLMClient? = null

        fun getInstance(context: Context? = null): LLMClient {
            return INSTANCE ?: synchronized(this) {
                val saved = context?.let { LLMConfigStore.load(it) }
                    ?: LLMConfig(apiKey = "")
                LLMClient(saved).also { INSTANCE = it }
            }
        }

        fun applyConfig(context: Context, newConfig: LLMConfig) {
            INSTANCE = LLMClient(newConfig)
            LLMConfigStore.save(context, newConfig)
        }
    }

    suspend fun chat(messages: List<Map<String, String>>): String = withContext(Dispatchers.IO) {
        callApi(JSONArray(messages))
    }

    private fun callApi(messages: JSONArray): String {
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
        }
        val url = config.endpoint.ifBlank { return "错误: API endpoint 未配置" }
        val request = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return "错误: 空响应"
        if (!response.isSuccessful)
            return "错误: HTTP ${response.code} - $responseBody"
        return JSONObject(responseBody)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }
}
```

### 3.2 LLMConfigStore.kt

```kotlin
package com.lightagent.llm

import android.content.Context
import android.content.SharedPreferences

object LLMConfigStore {
    private const val PREFS_NAME = "llm_config"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_CUSTOM_URL = "custom_url"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_MAX_TOKENS = "max_tokens"

    fun save(context: Context, config: LLMConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_CUSTOM_URL, config.customUrl)
            .putFloat(KEY_TEMPERATURE, config.temperature.toFloat())
            .putInt(KEY_MAX_TOKENS, config.maxTokens)
            .apply()
    }

    fun load(context: Context): LLMConfig {
        val p = prefs(context)
        val providerName = p.getString(KEY_PROVIDER, LLMProvider.DEEPSEEK.name) ?: LLMProvider.DEEPSEEK.name
        val provider = try { LLMProvider.valueOf(providerName) }
        catch (_: Exception) { LLMProvider.DEEPSEEK }
        return LLMConfig(
            provider = provider,
            apiKey = p.getString(KEY_API_KEY, "") ?: "",
            model = p.getString(KEY_MODEL, provider.defaultModel) ?: provider.defaultModel,
            customUrl = p.getString(KEY_CUSTOM_URL, "") ?: "",
            temperature = p.getFloat(KEY_TEMPERATURE, 0.7f).toDouble(),
            maxTokens = p.getInt(KEY_MAX_TOKENS, 2048)
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
```

---

## 4. 持久记忆 (Room)

### 4.1 ConversationEntity.kt

```kotlin
package com.lightagent.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "新的对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

### 4.2 MessageEntity.kt

```kotlin
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
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
```

### 4.3 ConversationDao.kt

```kotlin
package com.lightagent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
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

### 4.4 ConversationRepository.kt

```kotlin
package com.lightagent.memory

import kotlinx.coroutines.flow.Flow

class ConversationRepository(private val dao: ConversationDao) {
    val allConversations: Flow<List<ConversationEntity>> = dao.getAllConversations()

    suspend fun createConversation(title: String = "新的对话"): ConversationEntity {
        val conv = ConversationEntity(title = title)
        dao.insertConversation(conv)
        return conv
    }

    suspend fun renameConversation(id: String, title: String) {
        dao.updateConversationTitle(id, title)
    }

    suspend fun deleteConversation(id: String) {
        dao.deleteConversation(id)
    }

    suspend fun saveMessage(conversationId: String, role: String, content: String): MessageEntity {
        val msg = MessageEntity(conversationId = conversationId, role = role, content = content)
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

### 4.5 UserProfileMemory.kt

```kotlin
package com.lightagent.memory

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "user_facts")
data class UserFact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)

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

@Database(
    entities = [UserFact::class, ConversationEntity::class, MessageEntity::class, ReminderEntity::class],
    version = 2
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun userFactDao(): UserFactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile private var INSTANCE: AgentDatabase? = null

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "agent_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}

class UserProfileMemory(context: Context) {
    private val dao = AgentDatabase.getInstance(context).userFactDao()

    suspend fun set(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.insert(UserFact(key = key, value = value))
    }

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        dao.getByKey(key)?.value
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        dao.deleteByKey(key)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }
}
```

### 4.6 ReminderRepository.kt

```kotlin
package com.lightagent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val note: String = "",
    val triggerAt: Long,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

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

class ReminderRepository(private val dao: ReminderDao) {
    val allReminders: Flow<List<ReminderEntity>> = dao.getAllReminders()

    suspend fun addReminder(title: String, note: String = "", triggerAt: Long): ReminderEntity {
        val reminder = ReminderEntity(title = title, note = note, triggerAt = triggerAt)
        dao.insertReminder(reminder)
        return reminder
    }

    suspend fun markDone(id: String) = dao.markDone(id)
    suspend fun markDone(id: String, done: Boolean) = dao.markDone(id, done)
    suspend fun deleteReminder(id: String) = dao.deleteReminder(id)
    suspend fun getReminderById(id: String) = dao.getReminderById(id)
}
```

---

## 5. 通知系统

### 5.1 ReminderReceiver.kt

```kotlin
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

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(id.hashCode(), notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LightAgent 提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "AI 助手设置的提醒事项" }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "lightagent_reminders"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_NOTE = "extra_note"
        const val EXTRA_ID = "extra_id"
    }
}
```

### 5.2 ReminderScheduler.kt

```kotlin
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
            putExtra(ReminderReceiver.EXTRA_NOTE, reminder.note)
            putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, reminder.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms())
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pendingIntent)
            else
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, reminderId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
```

---

## 6. 工具集

### 6.1 Tool.kt

```kotlin
package com.lightagent.tools

import org.json.JSONObject

interface Tool {
    val name: String
    val description: String get() = ""
    suspend fun execute(params: JSONObject): String
}
```

### 6.2 WeatherTool.kt

```kotlin
package com.lightagent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class WeatherTool : Tool {
    private val client = OkHttpClient()
    override val name = "get_weather"

    override suspend fun execute(params: JSONObject): String = withContext(Dispatchers.IO) {
        val city = params.optString("city", "").ifBlank {
            return@withContext "❌ 缺少 city 参数"
        }
        try {
            val body = client.newCall(
                Request.Builder()
                    .url("https://wttr.in/$city?format=j1&lang=zh")
                    .build()
            ).execute().body?.string() ?: return@withContext "❌ 获取天气失败"
            val current = JSONObject(body)
                .getJSONArray("current_condition")
                .getJSONObject(0)
            "✅ $city 当前天气：" +
                current.getJSONArray("weatherDesc").getJSONObject(0).getString("value") +
                "，${current.getString("temp_C")}°C"
        } catch (e: Exception) {
            "❌ 天气查询失败：${e.message}"
        }
    }
}
```

### 6.3 NoteTool.kt

```kotlin
package com.lightagent.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class NoteTool(private val context: Context) : Tool {
    override val name = "save_note"

    override suspend fun execute(params: JSONObject): String {
        val content = params.optString("content", "").ifBlank {
            return "❌ 缺少 content 参数"
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        File(context.filesDir, "notes.txt").appendText("[$timestamp] $content\n")
        return "✅ 已保存笔记：$content"
    }
}
```

### 6.4 OpenAppTool.kt

```kotlin
package com.lightagent.tools

import android.content.Context
import android.content.Intent
import org.json.JSONObject

class OpenAppTool(private val context: Context) : Tool {
    override val name = "open_app"

    override suspend fun execute(params: JSONObject): String {
        val appName = params.optString("app_name", "").ifBlank {
            return "❌ 缺少 app_name 参数"
        }
        val intent = context.packageManager.getLaunchIntentForPackage(appName)
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

### 6.5 ReminderTool.kt

```kotlin
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

    override suspend fun execute(params: JSONObject): String {
        val title = params.optString("title", "").ifBlank {
            return "Error: title is required"
        }
        val note = params.optString("note", "")
        val datetime = params.optString("datetime", "").ifBlank {
            return "Error: datetime is required"
        }
        val triggerAt = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .parse(datetime)?.time
                ?: return "Error: invalid datetime"
        } catch (_: Exception) {
            return "Error: invalid datetime format"
        }
        if (triggerAt <= System.currentTimeMillis())
            return "Error: reminder time must be in the future"
        val reminder = repository.addReminder(title, note, triggerAt)
        ReminderScheduler.schedule(context, reminder)
        return "Reminder set: "$title" at $datetime"
    }
}
```

---

## 7. Live2D / TTS 接口

### 7.1 Live2DController.kt

```kotlin
package com.lightagent.live2d

interface Live2DController {
    val isReady: Boolean
    fun loadModel(modelPath: String)
    fun playMotion(group: String, index: Int, priority: Int = 2)
    fun setExpression(expressionId: String)
    fun setLipSync(volume: Float)
    fun setEyeFollow(x: Float, y: Float)
    fun release()
}

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

### 7.2 TTSController.kt

```kotlin
package com.lightagent.tts

interface TTSController {
    val isSpeaking: Boolean
    fun init(onReady: () -> Unit = {}, onError: (Exception) -> Unit = {})
    fun speak(
        text: String,
        onStart: () -> Unit = {},
        onPlaybackProgress: (Float) -> Unit = {},
        onDone: () -> Unit = {}
    )
    fun stop()
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    fun setVoice(voiceId: String)
    fun release()
}

class NoOpTTSController : TTSController {
    override val isSpeaking: Boolean = false
    override fun init(onReady: () -> Unit, onError: (Exception) -> Unit) { onReady() }
    override fun speak(
        text: String, onStart: () -> Unit,
        onPlaybackProgress: (Float) -> Unit, onDone: () -> Unit
    ) { onDone() }
    override fun stop() {}
    override fun setRate(rate: Float) {}
    override fun setPitch(pitch: Float) {}
    override fun setVoice(voiceId: String) {}
    override fun release() {}
}
```

---

## 8. ViewModel 层

### 8.1 ChatViewModel.kt

```kotlin
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

data class ChatMessage(val role: String, val content: String)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AgentDatabase.getInstance(application)
    private val repo = ConversationRepository(db.conversationDao())

    val conversations: StateFlow<List<ConversationEntity>> = repo.allConversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    private var plannerAgent: PlannerAgent? = null

    init {
        viewModelScope.launch {
            val all = conversations.first()
            if (all.isEmpty()) createNewConversation()
            else switchConversation(all.first())
        }
    }

    fun updateInput(value: String) {
        _input.value = value
    }

    fun send(userInput: String) = viewModelScope.launch {
        val convId = _currentConversationId.value ?: return@launch
        _isLoading.value = true
        repo.saveMessage(convId, "user", userInput)
        _messages.value = _messages.value + ChatMessage("user", userInput)
        val agent = plannerAgent ?: createAgent()
        try {
            val reply = agent.chat(userInput)
            repo.saveMessage(convId, "assistant", reply)
            _messages.value = _messages.value + ChatMessage("assistant", reply)
        } catch (e: Exception) {
            val err = "错误：${e.message}"
            repo.saveMessage(convId, "assistant", err)
            _messages.value = _messages.value + ChatMessage("assistant", err)
        } finally {
            _isLoading.value = false
        }
    }

    fun send() = send(_input.value)

    fun switchConversation(conv: ConversationEntity) = viewModelScope.launch {
        _currentConversationId.value = conv.id
        _messages.value = repo.getMessagesOnce(conv.id)
            .map { ChatMessage(it.role, it.content) }
        plannerAgent = null
    }

    fun createNewConversation() = viewModelScope.launch {
        val conv = repo.createConversation()
        _currentConversationId.value = conv.id
        _messages.value = emptyList()
        plannerAgent = null
    }

    fun deleteConversation(conv: ConversationEntity) = viewModelScope.launch {
        repo.deleteConversation(conv.id)
        if (_currentConversationId.value == conv.id) {
            val remaining = conversations.first().firstOrNull()
            if (remaining != null) switchConversation(remaining)
            else createNewConversation()
        }
    }

    private fun createAgent(): PlannerAgent {
        val convId = _currentConversationId.value ?: error("No conversation")
        val history = _messages.value.t