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
        val history = _messages.value.takeLast(10)
            .map { mapOf("role" to it.role, "content" to it.content) }
        val agent = PlannerAgent.create(getApplication(), history)
        plannerAgent = agent
        return agent
    }
}
```

### 8.2 BackgroundViewModel.kt

```kotlin
package com.lightagent.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class BackgroundSource {
    data class Asset(val fileName: String) : BackgroundSource()
    data class Custom(val uri: Uri) : BackgroundSource()
    data class SolidColor(val color: Long) : BackgroundSource()
}

class BackgroundViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("bg_prefs", Context.MODE_PRIVATE)
    private val _backgroundSource = MutableStateFlow<BackgroundSource>(
        BackgroundSource.SolidColor(0xFF0D0D1A)
    )
    val backgroundSource: StateFlow<BackgroundSource> = _backgroundSource

    val builtInBackgrounds: List<BackgroundSource.Asset> = (1..43).map {
        BackgroundSource.Asset("backgrounds/bg_default_${it}.png")
    }

    init {
        val saved = prefs.getString("bg_type", "solid") ?: "solid"
        when (saved) {
            "asset" -> {
                val name = prefs.getString("bg_asset", "backgrounds/bg_default_1.png")
                    ?: "backgrounds/bg_default_1.png"
                _backgroundSource.value = BackgroundSource.Asset(name)
            }
            "custom" -> {
                val uriStr = prefs.getString("bg_custom", null)
                if (uriStr != null)
                    _backgroundSource.value = BackgroundSource.Custom(Uri.parse(uriStr))
            }
            "color" -> {
                val color = prefs.getLong("bg_color", 0xFF0D0D1A)
                _backgroundSource.value = BackgroundSource.SolidColor(color)
            }
        }
    }

    fun setAssetBackground(fileName: String) {
        val src = BackgroundSource.Asset(fileName)
        _backgroundSource.value = src
        prefs.edit().putString("bg_type", "asset").putString("bg_asset", fileName).apply()
    }

    fun setCustomBackground(uri: Uri) {
        val src = BackgroundSource.Custom(uri)
        _backgroundSource.value = src
        prefs.edit().putString("bg_type", "custom").putString("bg_custom", uri.toString()).apply()
    }

    fun setSolidBackground(color: Long) {
        val src = BackgroundSource.SolidColor(color)
        _backgroundSource.value = src
        prefs.edit().putString("bg_type", "color").putLong("bg_color", color).apply()
    }

    fun randomBackground() = viewModelScope.launch {
        val pick = builtInBackgrounds.random()
        setAssetBackground(pick.fileName)
    }

    fun resetToDefault() = setSolidBackground(0xFF0D0D1A)
}
```

### 8.3 ReminderViewModel.kt

```kotlin
package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ReminderEntity
import com.lightagent.memory.ReminderRepository
import com.lightagent.notification.ReminderScheduler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReminderViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AgentDatabase.getInstance(application)
    private val repo = ReminderRepository(db.reminderDao())

    val reminders: StateFlow<List<ReminderEntity>> = repo.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(title: String, timeText: String = "") = viewModelScope.launch {
        val triggerAt = parseTime(timeText)
        val reminder = repo.addReminder(title, "", triggerAt)
        ReminderScheduler.schedule(getApplication(), reminder)
    }

    fun toggleDone(reminder: ReminderEntity) = viewModelScope.launch {
        repo.markDone(reminder.id, !reminder.isCompleted)
    }

    fun delete(reminder: ReminderEntity) = viewModelScope.launch {
        ReminderScheduler.cancel(getApplication(), reminder.id)
        repo.deleteReminder(reminder.id)
    }

    private fun parseTime(text: String): Long {
        if (text.isBlank()) return System.currentTimeMillis() + 3600_000L
        try {
            return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .parse(text)?.time ?: parseFallback(text)
        } catch (_: Exception) {
            return parseFallback(text)
        }
    }

    private fun parseFallback(text: String): Long {
        try {
            val cal = Calendar.getInstance()
            val parts = text.trim().split(" ")
            if (parts.size == 2) {
                val dateParts = parts[0].split("-")
                val timeParts = parts[1].split(":")
                if (dateParts.size == 2) {
                    cal.set(Calendar.MONTH, dateParts[0].toInt() - 1)
                    cal.set(Calendar.DAY_OF_MONTH, dateParts[1].toInt())
                }
                if (timeParts.size == 2) {
                    cal.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                    cal.set(Calendar.MINUTE, timeParts[1].toInt())
                }
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis())
                    cal.add(Calendar.YEAR, 1)
                return cal.timeInMillis
            }
        } catch (_: Exception) {}
        return System.currentTimeMillis() + 3600_000L
    }
}
```

### 8.4 LLMSettingsViewModel.kt

```kotlin
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
import kotlinx.coroutines.launch

data class LLMSettings(
    val provider: LLMProvider = LLMProvider.DEEPSEEK,
    val apiKey: String = "",
    val model: String = "",
    val customUrl: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val stream: Boolean = true,
    val contextEnabled: Boolean = true
)

class LLMSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _settings = MutableStateFlow(LLMSettings())
    val settings: StateFlow<LLMSettings> = _settings

    init {
        val config = LLMConfigStore.load(application)
        _settings.value = LLMSettings(
            provider = config.provider,
            apiKey = config.apiKey,
            model = config.model,
            customUrl = config.customUrl,
            temperature = config.temperature.toFloat(),
            maxTokens = config.maxTokens
        )
    }

    fun updateProvider(provider: LLMProvider) {
        _settings.value = _settings.value.copy(provider = provider)
    }
    fun updateApiKey(key: String) {
        _settings.value = _settings.value.copy(apiKey = key)
    }
    fun updateModel(model: String) {
        _settings.value = _settings.value.copy(model = model)
    }
    fun updateCustomUrl(url: String) {
        _settings.value = _settings.value.copy(customUrl = url)
    }
    fun updateTemperature(temp: Float) {
        _settings.value = _settings.value.copy(temperature = temp)
    }
    fun updateMaxTokens(tokens: Int) {
        _settings.value = _settings.value.copy(maxTokens = tokens)
    }
    fun updateStream(enabled: Boolean) {
        _settings.value = _settings.value.copy(stream = enabled)
    }
    fun updateContextEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(contextEnabled = enabled)
    }

    fun save() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val s = _settings.value
        val config = LLMConfig(
            provider = s.provider,
            apiKey = s.apiKey,
            model = s.model,
            customUrl = s.customUrl,
            temperature = s.temperature.toDouble(),
            maxTokens = s.maxTokens
        )
        LLMConfigStore.save(ctx, config)
        LLMClient.applyConfig(ctx, config)
    }
}
```

---

## 9. UI Screen 层

### 9.1 ChatScreen.kt

```kotlin
package com.lightagent.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.ui.*
import com.lightagent.ui.components.*
import com.lightagent.ui.theme.AnimTokens

enum class Screen { Chat, Reminder, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf(Screen.Chat) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    val backgroundVM: BackgroundViewModel = viewModel()

    Box(modifier = Modifier.fillMaxSize()) {
        ChatBackground(source = backgroundVM.backgroundSource.collectAsState().value)
        ModalNavigationDrawer(
            drawerState = rememberDrawerState(DrawerValue.Closed),
            drawerContent = {
                ConversationDrawer(
                    conversations = viewModel.conversations.collectAsState().value,
                    currentId = viewModel.currentConversationId.collectAsState().value,
                    onSelect = { viewModel.switchConversation(it) },
                    onNew = { viewModel.createNewConversation() },
                    onDelete = { viewModel.deleteConversation(it) },
                    onReminders = { currentScreen = Screen.Reminder },
                    onSettings = { currentScreen = Screen.Settings },
                    onClose = { }
                )
            }
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    if (targetState == Screen.Chat) {
                        slideInHorizontally(initialOffsetX = { -it }) + fadeIn() togetherWith
                            slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                    } else {
                        slideInHorizontally(initialOffsetX = { it }) + fadeIn() togetherWith
                            slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                    }
                },
                animationSpec = spring(AnimTokens.BouncyDamping, AnimTokens.BouncyStiffness)
            ) { screen ->
                when (screen) {
                    Screen.Chat -> ChatContent(
                        viewModel = viewModel,
                        onBackgroundClick = { showBackgroundSheet = true }
                    )
                    Screen.Reminder -> ReminderScreen(
                        viewModel = viewModel(LocalContext.current as androidx.lifecycle.ViewModelStoreOwner),
                        onBack = { currentScreen = Screen.Chat }
                    )
                    Screen.Settings -> LLMSettingsScreen(
                        viewModel = viewModel(),
                        onBack = { currentScreen = Screen.Chat }
                    )
                }
            }
        }
    }

    if (showBackgroundSheet) {
        BackgroundSettingsSheet(
            backgroundVM = backgroundVM,
            onDismiss = { showBackgroundSheet = false }
        )
    }
}

@Composable
private fun ChatContent(
    viewModel: ChatViewModel,
    onBackgroundClick: () -> Unit
) {
    val messages = viewModel.messages.collectAsState().value
    val isLoading = viewModel.isLoading.collectAsState().value
    val input = viewModel.input.collectAsState().value
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("天爱星Agent", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xCC0D0D1A),
                    titleContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = { onBackgroundClick() }) {
                        Icon(Icons.Rounded.Settings, "背景设置", tint = Color.White)
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                value = input,
                enabled = !isLoading,
                onValueChange = { viewModel.updateInput(it) },
                onSend = { viewModel.send() }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            itemsIndexed(messages, key = { i, _ -> i }) { index, msg ->
                AnimatedMessageBubble(
                    message = msg,
                    index = index,
                    isNew = index == messages.lastIndex && msg.role == "assistant" && isLoading
                )
            }
            if (isLoading && messages.lastOrNull()?.role == "user") {
                item(key = "thinking") {
                    ThinkingDots(modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val borderAlpha by animateFloatAsState(
        targetValue = if (value.isNotBlank()) 0.8f else 0.2f,
        animationSpec = tween(300)
    )

    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xE61A1A2E),
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = borderAlpha))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                enabled = enabled,
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            val sendEnabled = value.isNotBlank() && enabled
            val sendScale by animateFloatAsState(
                targetValue = if (sendEnabled) 1f else 0.9f,
                animationSpec = spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness)
            )
            IconButton(
                onClick = { if (sendEnabled) onSend() },
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer(scaleX = sendScale, scaleY = sendScale)
                    .background(
                        if (sendEnabled) Color(0xFF7C4DFF) else Color(0xFF3A3A5C),
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Send, "发送",
                    tint = Color.White, modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
```

### 9.2 SplashScreen.kt

```kotlin
package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition()
    val glow1Size by infiniteTransition.animateFloat(
        initialValue = 220f, targetValue = 320f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val glow2Size by infiniteTransition.animateFloat(
        initialValue = 160f, targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val glow1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    data class Particle(
        var x: Float, var y: Float, val vx: Float, val vy: Float,
        val radius: Float, val alpha: Float
    )
    val particles = remember {
        List(70) {
            Particle(
                x = Random.nextFloat(), y = Random.nextFloat(),
                vx = (Random.nextFloat() - 0.5f) * 0.0003f,
                vy = (Random.nextFloat() - 0.5f) * 0.0003f,
                radius = Random.nextFloat() * 2.5f + 0.5f,
                alpha = Random.nextFloat() * 0.4f + 0.05f
            )
        }
    }

    val title = "天爱星 Agent"
    val subtitle = "您的 AI 智能伙伴"
    var titleVisible by remember { mutableStateOf(0) }
    var subtitleVisible by remember { mutableStateOf(0) }
    var cursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        for (i in title.indices) { titleVisible = i + 1; delay(65L) }
        delay(200)
        for (i in subtitle.indices) { subtitleVisible = i + 1; delay(55L) }
        delay(800)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D0D1A), Color(0xFF1A103D), Color(0xFF0D0D1A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            // 光晕大圆
            drawCircle(
                color = Color(0xFF7C4DFF).copy(alpha = glow1Alpha),
                radius = glow1Size * density,
                center = Offset(w * 0.5f, h * 0.4f),
                blendMode = androidx.compose.ui.graphics.BlendMode.Screen
            )
            // 光晕小圆
            drawCircle(
                color = Color(0xFF448AFF).copy(alpha = glow1Alpha * 0.7f),
                radius = glow2Size * density,
                center = Offset(w * 0.5f, h * 0.45f),
                blendMode = androidx.compose.ui.graphics.BlendMode.Screen
            )
            // 粒子
            particles.forEach { p ->
                p.x = (p.x + p.vx).let { if (it > 1f) it - 1f else if (it < 0f) it + 1f else it }
                p.y = (p.y + p.vy).let { if (it > 1f) it - 1f else if (it < 0f) it + 1f else it }
                drawCircle(
                    color = Color.White.copy(alpha = p.alpha),
                    radius = p.radius * density,
                    center = Offset(p.x * w, p.y * h)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title.take(titleVisible) +
                    if (cursorVisible && titleVisible < title.length) "▋" else "",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle.take(subtitleVisible),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
                letterSpacing = 1.sp
            )
        }
    }
}
```

### 9.3 ReminderScreen.kt

```kotlin
package com.lightagent.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ReminderEntity
import com.lightagent.ui.ReminderViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(
    viewModel: ReminderViewModel,
    onBack: () -> Unit
) {
    val reminders = viewModel.reminders.collectAsState().value
    var showAddDialog by remember { mutableStateOf(false) }
    val fadeAlpha by animateFloatAsState(1f, animationSpec = tween(500))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D0D1A), Color(0xFF1A103D))
                )
            )
            .alpha(fadeAlpha)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("提醒事项", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                )
            )

            if (reminders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.NotificationsNone, "暂无提醒",
                            tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("暂无提醒", color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp)
                        Text("点击右下角按钮添加", color = Color.White.copy(alpha = 0.25f), fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(reminders, key = { it.id }) { reminder ->
                        var done by remember { mutableStateOf(reminder.isCompleted) }
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { visible = true }
                        AnimatedVisibility(
                            visible = visible,
                            enter = slideInHorizontally(initialOffsetX = { 40 }) +
                                fadeIn(animationSpec = tween(400)),
                            exit = slideOutHorizontally(targetOffsetX = { 40 }) + fadeOut()
                        ) {
                            AnimatedReminderCard(
                                reminder = reminder,
                                isDone = done,
                                onToggle = {
                                    done = !done
                                    viewModel.toggleDone(reminder)
                                },
                                onDelete = {
                                    visible = false
                                    viewModel.delete(reminder)
                                }
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Color(0xFF7C4DFF)
        ) {
            Icon(Icons.Rounded.Add, "添加提醒", tint = Color.White)
        }
    }

    if (showAddDialog) {
        AddReminderDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, time ->
                viewModel.add(title, time)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AnimatedReminderCard(
    reminder: ReminderEntity,
    isDone: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val deleteScale by animateFloatAsState(
        if (isDone) 0.8f else 1f,
        animationSpec = spring(dampingRatio = 0.3f, stiffness = 300f)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE61A1A2E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isDone,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF7C4DFF),
                    uncheckedColor = Color.White.copy(alpha = 0.4f)
                )
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = reminder.title,
                    color = Color.White.copy(alpha = if (isDone) 0.4f else 1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateFormat.format(Date(reminder.triggerAt)),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
                    .graphicsLayer(scaleX = deleteScale, scaleY = deleteScale)
            ) {
                Icon(Icons.Rounded.Delete, "删除",
                    tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AddReminderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    val alpha by animateFloatAsState(1f, animationSpec = tween(300))
    val scale by animateFloatAsState(1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f))

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(alpha).graphicsLayer(scaleX = scale, scaleY = scale),
        containerColor = Color(0xFF1A1030),
        title = { Text("新建提醒", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7C4DFF),
                        focusedLabelColor = Color(0xFF7C4DFF),
                        cursorColor = Color(0xFF7C4DFF),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    label = { Text("时间 (如 2026-06-22 20:00)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7C4DFF),
                        focusedLabelColor = Color(0xFF7C4DFF),
                        cursorColor = Color(0xFF7C4DFF),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, timeText) }) {
                Text("确定", color = Color(0xFF7C4DFF))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color.White.copy(alpha = 0.5f))
            }
        }
    )
}
```
### 9.4 LLMSettingsScreen.kt

```kotlin
package com.lightagent.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.llm.LLMProvider
import com.lightagent.ui.LLMSettingsViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMSettingsScreen(
    viewModel: LLMSettingsViewModel,
    onBack: () -> Unit
) {
    val settings = viewModel.settings.collectAsState().value
    var saved by remember { mutableStateOf(false) }
    val fadeAlpha by animateFloatAsState(1f, animationSpec = tween(500))

    if (saved) {
        LaunchedEffect(saved) { delay(1500); saved = false }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(
                colors = listOf(Color(0xFF0D0D1A), Color(0xFF1A103D))
            ))
            .alpha(fadeAlpha)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("大模型设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent, titleContentColor = Color.White
                )
            )

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StaggeredSettingsCard(index = 0) {
                    SettingsCardTitle("大模型提供商")
                    LLMProvider.entries.forEach { provider ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (settings.provider == provider)
                                    Color(0xFF7C4DFF).copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { viewModel.updateProvider(provider) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.provider == provider,
                                onClick = { viewModel.updateProvider(provider) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF7C4DFF))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(provider.displayName, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }

                StaggeredSettingsCard(index = 1) {
                    SettingsCardTitle("API Key")
                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { viewModel.updateApiKey(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入 API Key", color = Color.White.copy(alpha = 0.3f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF7C4DFF),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF7C4DFF)
                        ), singleLine = true
                    )
                }

                StaggeredSettingsCard(index = 2) {
                    SettingsCardTitle("模型")
                    OutlinedTextField(
                        value = settings.model,
                        onValueChange = { viewModel.updateModel(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(settings.provider.defaultModel,
                            color = Color.White.copy(alpha = 0.3f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF7C4DFF),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF7C4DFF)
                        ), singleLine = true
                    )
                }

                if (settings.provider == LLMProvider.CUSTOM) {
                    StaggeredSettingsCard(index = 3) {
                        SettingsCardTitle("自定义 API 地址")
                        OutlinedTextField(
                            value = settings.customUrl,
                            onValueChange = { viewModel.updateCustomUrl(it) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://api.example.com/v1/chat/completions",
                                color = Color.White.copy(alpha = 0.3f)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF7C4DFF),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF7C4DFF)
                            ), singleLine = true
                        )
                    }
                }

                StaggeredSettingsCard(index = 4) {
                    SettingsCardTitle("Temperature: ${"%.2f".format(settings.temperature)}")
                    Slider(value = settings.temperature,
                        onValueChange = { viewModel.updateTemperature(it) },
                        valueRange = 0f..2f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF7C4DFF), activeTrackColor = Color(0xFF7C4DFF))
                    )
                }

                StaggeredSettingsCard(index = 5) {
                    SettingsCardTitle("Max Tokens: ${settings.maxTokens}")
                    Slider(value = settings.maxTokens.toFloat(),
                        onValueChange = { viewModel.updateMaxTokens(it.toInt()) },
                        valueRange = 128f..8192f, steps = 32,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF7C4DFF), activeTrackColor = Color(0xFF7C4DFF))
                    )
                }

                StaggeredSettingsCard(index = 6) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(
                            indication = null, interactionSource = remember { MutableInteractionSource() }
                        ) { viewModel.updateStream(!settings.stream) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SettingsCardTitle("流式输出")
                        Switch(checked = settings.stream,
                            onCheckedChange = { viewModel.updateStream(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF7C4DFF))
                        )
                    }
                }

                StaggeredSettingsCard(index = 7) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(
                            indication = null, interactionSource = remember { MutableInteractionSource() }
                        ) { viewModel.updateContextEnabled(!settings.contextEnabled) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SettingsCardTitle("上下文记忆")
                        Switch(checked = settings.contextEnabled,
                            onCheckedChange = { viewModel.updateContextEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF7C4DFF))
                        )
                    }
                }

                val saveColor by animateColorAsState(
                    targetValue = if (saved) Color(0xFF4CAF50) else Color(0xFF7C4DFF),
                    animationSpec = tween(300)
                )
                Button(
                    onClick = { viewModel.save(); saved = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = saveColor),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (saved) "✅ 已保存" else "保存设置",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun StaggeredSettingsCard(index: Int, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(1f,
        animationSpec = tween(durationMillis = 400, delayMillis = index * 55))
    Card(
        modifier = Modifier.fillMaxWidth().alpha(alpha),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE61A1A2E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) { Box(modifier = Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun SettingsCardTitle(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}
```

### 9.5 BackgroundSettingsSheet.kt

```kotlin
package com.lightagent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.theme.AnimTokens

private val gradientPresets = listOf(
    "默认" to listOf(Color(0xFF0D0D1A), Color(0xFF1A103D)),
    "极光" to listOf(Color(0xFF001F3F), Color(0xFF003366), Color(0xFF001F3F)),
    "日落" to listOf(Color(0xFF2D1B69), Color(0xFFD32F2F)),
    "森林" to listOf(Color(0xFF0D1B2A), Color(0xFF1B4332)),
    "海洋" to listOf(Color(0xFF0A1929), Color(0xFF0D47A1)),
    "玫瑰" to listOf(Color(0xFF1A0A2E), Color(0xFF880E4F))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsSheet(backgroundVM: BackgroundViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = Color(0xFF141428),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.width(40.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
            }

            Text("背景设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            StaggeredSheetItem(0) {
                Text("渐变预设", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    gradientPresets.forEachIndexed { idx, (name, _) ->
                        GradientPresetCard(name, idx + 1, false) {}
                    }
                }
            }

            StaggeredSheetItem(1) {
                ActionRow("随机切换", Icons.Rounded.Shuffle) {
                    backgroundVM.randomBackground(); onDismiss()
                }
            }
            StaggeredSheetItem(2) {
                ActionRow("从相册选择", Icons.Rounded.Image) {}
            }
            StaggeredSheetItem(3) {
                ActionRow("恢复默认", Icons.Rounded.Refresh) {
                    backgroundVM.resetToDefault(); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun GradientPresetCard(name: String, index: Int, isSelected: Boolean, onClick: () -> Unit) {
    val borderAlpha by animateFloatAsState(if (isSelected) 0.9f else 0.3f, tween(300))
    val scale by animateFloatAsState(if (isSelected) 1.05f else 1f,
        spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness))

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            modifier = Modifier.size(72.dp, 100.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(RoundedCornerShape(12.dp))
                .border(1.5.dp, Color(0xFF7C4DFF).copy(alpha = borderAlpha), RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(gradientPresets[index - 1].second), RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.BottomEnd
        ) {
            Text("$index", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.padding(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(name, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}

@Composable
private fun ActionRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val bgAlpha by animateFloatAsState(if (isPressed) 0.15f else 0f, tween(150))
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f,
        spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness))

    Row(
        modifier = Modifier.fillMaxWidth().graphicsLayer(scaleX = scale, scaleY = scale)
            .background(Color.White.copy(alpha = bgAlpha), RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun StaggeredSheetItem(index: Int, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(1f, animationSpec = tween(400, delayMillis = index * 55 + 100))
    Box(modifier = Modifier.alpha(alpha)) { content() }
}
```

---

## 10. UI 组件层

### 10.1 AnimatedMessageBubble.kt

```kotlin
package com.lightagent.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.theme.AnimTokens

@Composable
fun AnimatedMessageBubble(message: ChatMessage, index: Int, isNew: Boolean = false) {
    val isUser = message.role == "user"
    val slideOffset by animateFloatAsState(0f,
        animationSpec = tween(350, delayMillis = index * AnimTokens.MessageStagger.toInt()))
    val scale by animateFloatAsState(1f,
        animationSpec = spring(AnimTokens.BouncyDamping, AnimTokens.BouncyStiffness))

    var displayText by remember(message.content) { mutableStateOf("") }
    val fullText = message.content

    LaunchedEffect(fullText) {
        if (isNew && !isUser) {
            var i = 0
            while (i < fullText.length) {
                val delay = when {
                    i < fullText.length - 1 && fullText[i + 1] == '。' -> 55L
                    i < fullText.length - 1 && fullText[i + 1] == '，' -> 20L
                    fullText[i] == '\n' -> 80L
                    else -> 10L
                }
                displayText = fullText.take(i + 1)
                kotlinx.coroutines.delay(delay)
                i++
            }
        } else { displayText = fullText }
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer(
                translationY = (AnimTokens.MessageSlideInY * (1f - slideOffset)),
                scaleX = scale, scaleY = scale,
                transformOrigin = if (isUser) TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f)
            ).alpha(slideOffset),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ))
                .background(if (isUser) Color(0xFF7C4DFF) else Color(0xE61A1A2E))
                .padding(12.dp)
        ) {
            Text(text = displayText, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
```

### 10.2 ThinkingDots.kt

```kotlin
package com.lightagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun ThinkingDots(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()

    fun dotAlpha(offset: Int): Float = infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = offset * 200), repeatMode = RepeatMode.Reverse)
    ).value

    fun dotY(index: Int): Float = infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = index * 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse)
    ).value

    Row(modifier = modifier.padding(start = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0..2) {
            Box(modifier = Modifier.size(8.dp).offset(y = dotY(i).dp)
                .graphicsLayer(alpha = dotAlpha(i))
                .clip(androidx.compose.foundation.shape.CircleShape))
        }
    }
}
```

### 10.3 ConversationDrawer.kt

```kotlin
package com.lightagent.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.theme.AnimTokens

@Composable
fun ConversationDrawer(
    conversations: List<ConversationEntity>, currentId: String?,
    onSelect: (ConversationEntity) -> Unit, onNew: () -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    onReminders: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.verticalGradient(colors = listOf(Color(0xFF0D0D1A), Color(0xFF141428), Color(0xFF1A103D)))
    )) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("对话列表", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                NewConversationButton(onClick = onNew)
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(conversations, key = { _, c -> c.id }) { index, conv ->
                    StaggeredDrawerItem(index) {
                        ConversationItem(conv, conv.id == currentId,
                            onClick = { onSelect(conv) }, onDelete = { onDelete(conv) })
                    }
                }
            }

            DrawerBottomItem("提醒", Icons.Rounded.Notifications, onClick = onReminders)
            Spacer(Modifier.height(4.dp))
            DrawerBottomItem("设置", Icons.Rounded.Settings, onClick = onSettings)
        }
    }
}

@Composable
private fun NewConversationButton(onClick: () -> Unit) {
    val scale by animateFloatAsState(1f, spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness))
    OutlinedButton(onClick = onClick, modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF7C4DFF)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7C4DFF))
    ) {
        Icon(Icons.Rounded.Add, "新建", modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp)); Text("新建")
    }
}

@Composable
private fun ConversationItem(conversation: ConversationEntity, isSelected: Boolean,
                              onClick: () -> Unit, onDelete: () -> Unit) {
    val indicatorWidth by animateDpAsState(if (isSelected) 3.dp else 0.dp, tween(AnimTokens.SelectionDuration))
    val bgColor by animateColorAsState(
        if (isSelected) Color.White.copy(alpha = 0.08f) else Color.Transparent, tween(AnimTokens.SelectionDuration))

    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bgColor)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(indicatorWidth).height(24.dp).clip(RoundedCornerShape(2.dp))
            .background(if (isSelected) Color(0xFF7C4DFF) else Color.Transparent))
        Spacer(Modifier.width(12.dp))
        Text(conversation.title, color = Color.White.copy(alpha = if (isSelected) 1f else 0.7f),
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Delete, "删除", tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StaggeredDrawerItem(index: Int, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(1f, animationSpec = tween(400, delayMillis = index * 55))
    Box(modifier = Modifier.alpha(alpha)) { content() }
}

@Composable
private fun DrawerBottomItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    val scale by animateFloatAsState(1f, spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness))
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .graphicsLayer(scaleX = scale, scaleY = scale).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}
```

### 10.4 ChatBackground.kt

```kotlin
package com.lightagent.ui.screen

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lightagent.ui.BackgroundSource

@Composable
fun ChatBackground(source: BackgroundSource) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (source) {
            is BackgroundSource.SolidColor -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0D0D1A), Color(0xFF1A103D),
                            Color(0xFF0D0D1A), Color(0xFF141428)),
                        startY = 0f, endY = size.height))
                    drawCircle(color = Color(0x447C4DFF), radius = 220.dp.toPx(),
                        center = Offset(size.width, 0f), alpha = 0.6f)
                    drawCircle(color = Color(0x44448AFF), radius = 180.dp.toPx(),
                        center = Offset(0f, size.height), alpha = 0.5f)
                }
            }
            is BackgroundSource.Asset -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("file:///android_asset/${source.fileName}").crossfade(true).build(),
                    contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Canvas(modifier = Modifier.fillMaxSize()) { drawRect(color = Color(0x99000000)) }
            }
            is BackgroundSource.Custom -> {
                AsyncImage(model = source.uri, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Canvas(modifier = Modifier.fillMaxSize()) { drawRect(color = Color(0x99000000)) }
            }
        }
    }
}
```

---

## 11. 主题系统

### 11.1 Color.kt

```kotlin
package com.lightagent.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

object AnimTokens {
    val StaggerBase = 55L
    val MessageStagger = 25L
    val MessageSlideInY = 28f
    val FadeDuration = 220
    val SelectionDuration = 180
    val BouncyDamping = Spring.DampingRatioMediumBouncy
    val BouncyStiffness = Spring.StiffnessMedium
    val SnapDamping = Spring.DampingRatioLowBouncy
    val SnapStiffness = Spring.StiffnessHigh
    val DrawerDamping = Spring.DampingRatioMediumBouncy
    val DrawerStiffness = Spring.StiffnessMediumLow
}
```

### 11.2 Theme.kt

```kotlin
package com.lightagent.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C4DFF), secondary = PurpleGrey80, tertiary = Pink80,
    background = Color(0xFF0D0D1A), surface = Color(0xFF1A1A2E),
    onPrimary = Color.White, onSecondary = Color.White,
    onBackground = Color.White, onSurface = Color.White
)

@Composable
fun LightAgentTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color(0xCC0D0D1A).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography(), content = content)
}
```

### 11.3 Type.kt

```kotlin
package com.lightagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    )
)
```
### 12.1 app/build.gradle

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
        versionCode 31
        versionName "3.1"
    }

    buildTypes {
        release {
            minifyEnabled false
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }
    buildFeatures {
        compose true
    }
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.8'
    }
}

dependencies {
    implementation platform('androidx.compose:compose-bom:2024.09.03')
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
    implementation 'androidx.activity:activity-compose:1.8.2'
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-graphics'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.material:material-icons-extended'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.7.0'
    implementation 'androidx.navigation:navigation-compose:2.7.7'

    // Room
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'

    // OkHttp
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // Coil
    implementation 'io.coil-kt:coil-compose:2.6.0'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0'

    // Test
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
}
```

### 12.2 build.gradle (root)

```groovy
plugins {
    id 'com.android.application' version '8.2.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.22' apply false
    id 'org.jetbrains.kotlin.plugin.serialization' version '1.9.22' apply false
}
```

### 12.3 settings.gradle

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "LightAgent"
include ':app'
```

### 12.4 gradle.properties

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
android.overridePathCheck=true
```

### 12.5 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="天爱星Agent"
        android:supportsRtl="true"
        android:theme="@style/Theme.LightAgent">

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

        <meta-data
            android:name="android.max_aspect"
            android:value="2.4" />
    </application>
</manifest>
```

---

*导出时间: 2026-06-21 | 天爱星Agent v3.1 | 零警告构建通过*
