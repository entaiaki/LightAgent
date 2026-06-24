# 天爱星Agent v3.0 — 完整源码

> 导出日期：2026-06-21
> Compose BOM：2024.09.03 (Compose 1.7.x)
> Kotlin 源文件：38
> APK 体积：~102 MB（含 43 张背景图）
> minSdk/targetSdk：26/34

---

## 1. Gradle 配置

### gradle.properties

```properties
android.useAndroidX=true
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
android.nonTransitiveRClass=true
android.overridePathCheck=true
```

### app/build.gradle

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

    buildFeatures { compose true }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = '17' }
}

dependencies {
    // Compose BOM → Compose 1.7.x
    def composeBom = platform('androidx.compose:compose-bom:2024.09.03')
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

    // Room
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'

    // JSON
    implementation 'org.json:json:20231013'

    // Coil
    implementation 'io.coil-kt:coil-compose:2.6.0'
}
```

---

## 2. 入口

### MainActivity.kt

```kotlin
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
import com.lightagent.ui.screen.ChatScreen
import com.lightagent.ui.screen.SplashScreen
import com.lightagent.ui.theme.LightAgentTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 权限结果 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

---

## 3. Agent 层

### agent/ChatState.kt

```kotlin
package com.lightagent.agent

sealed class ChatState {
    object Idle        : ChatState()
    object Thinking    : ChatState()
    object CallingTool : ChatState()
    data class Error(val message: String) : ChatState()
}
```

### agent/PlannerAgent.kt

```kotlin
package com.lightagent.agent

import android.content.Context
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ReminderRepository
import com.lightagent.tools.*
import com.lightagent.llm.LLMClient
import org.json.JSONObject

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

    private fun extractToolParams(response: String): JSONObject? {
        val line = response.lines().find { it.startsWith("PARAMS:") } ?: return null
        val json = line.removePrefix("PARAMS:").trim()
        return try { JSONObject(json) } catch (_: Exception) { null }
    }
}
```

---

## 4. LLM 层

### llm/LLMClient.kt

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

enum class LLMProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String
) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-turbo"),
    CUSTOM("自定义", "", "")
}

data class LLMConfig(
    val provider:    LLMProvider = LLMProvider.DEEPSEEK,
    val apiKey:      String      = "",
    val model:       String      = LLMProvider.DEEPSEEK.defaultModel,
    val customUrl:   String      = "",
    val temperature: Double      = 0.7,
    val maxTokens:   Int         = 2048
) {
    val endpoint: String get() = if (provider == LLMProvider.CUSTOM) customUrl else provider.baseUrl
}

class LLMClient(private var config: LLMConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60,  TimeUnit.SECONDS)
        .build()

    fun updateConfig(newConfig: LLMConfig) { config = newConfig }
    fun getConfig(): LLMConfig = config

    companion object {
        @Volatile private var INSTANCE: LLMClient? = null
        var apiKey: String = ""

        fun getInstance(context: Context? = null): LLMClient {
            return INSTANCE ?: synchronized(this) {
                val saved = context?.let { LLMConfigStore.load(it) } ?: LLMConfig(apiKey = apiKey)
                LLMClient(saved).also { INSTANCE = it }
            }
        }
    }

    suspend fun chat(messages: List<Map<String, String>>): String = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                messages.forEach { m -> put(JSONObject(m)) }
            })
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
        }

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $body")
            val json = JSONObject(body)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            "❌ LLM error: ${e.message}"
        }
    }
}
```

### llm/LLMConfigStore.kt

```kotlin
package com.lightagent.llm

import android.content.Context
import android.content.SharedPreferences

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
```

---

## 5. Memory 层（Room 数据库）

### memory/ConversationEntity.kt

```kotlin
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

### memory/MessageEntity.kt

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

### memory/ConversationDao.kt

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

### memory/ConversationRepository.kt

```kotlin
package com.lightagent.memory

import kotlinx.coroutines.flow.Flow

class ConversationRepository(private val dao: ConversationDao) {

    val allConversations: Flow<List<ConversationEntity>> = dao.getAllConversations()

    suspend fun createConversation(title: String = "New Chat"): ConversationEntity {
        val conv = ConversationEntity(title = title)
        dao.insertConversation(conv)
        return conv
    }

    suspend fun renameConversation(id: String, title: String) { dao.updateConversationTitle(id, title) }

    suspend fun deleteConversation(id: String) { dao.deleteConversation(id) }

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

### memory/ReminderRepository.kt

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
        val r = ReminderEntity(title = title, note = note, triggerAt = triggerAt)
        dao.insertReminder(r); return r
    }

    suspend fun markDone(id: String) = dao.markDone(id)
    suspend fun deleteReminder(id: String) = dao.deleteReminder(id)
    suspend fun getReminderById(id: String) = dao.getReminderById(id)
}
```

### memory/UserProfileMemory.kt

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
        fun getInstance(context: Context): AgentDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "agent_db")
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
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

    suspend fun delete(key: String) = withContext(Dispatchers.IO) { dao.deleteByKey(key) }

    suspend fun getAll(): List<UserFact> = withContext(Dispatchers.IO) { dao.getAll() }
}
```

---

## 6. 工具层

### tools/Tool.kt

```kotlin
package com.lightagent.tools

import org.json.JSONObject

interface Tool {
    val name: String
    val description: String get() = ""
    suspend fun execute(params: JSONObject): String
}
```

### tools/WeatherTool.kt

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
        val city = params.optString("city", "").ifBlank { return@withContext "❌ 缺少 city 参数" }
        try {
            val request = Request.Builder().url("https://wttr.in/$city?format=j1&lang=zh").build()
            val body = client.newCall(request).execute().body?.string() ?: return@withContext "❌ 获取天气失败"
            val json    = JSONObject(body)
            val current = json.getJSONArray("current_condition").getJSONObject(0)
            val temp = current.getString("temp_C")
            val desc = current.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
            "✅ $city 当前天气：$desc，${temp}°C"
        } catch (e: Exception) { "❌ 天气查询失败：${e.message}" }
    }
}
```

### tools/NoteTool.kt

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
        val content = params.optString("content", "").ifBlank { return "❌ 缺少 content 参数" }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        File(context.filesDir, "notes.txt").appendText("[$timestamp] $content\n")
        return "✅ 已保存笔记：$content"
    }
}
```

### tools/OpenAppTool.kt

```kotlin
package com.lightagent.tools

import android.content.Context
import android.content.Intent
import org.json.JSONObject

class OpenAppTool(private val context: Context) : Tool {
    override val name = "open_app"

    override suspend fun execute(params: JSONObject): String {
        val appName = params.optString("app_name", "").ifBlank { return "❌ 缺少 app_name 参数" }
        val intent = context.packageManager.getLaunchIntentForPackage(appName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ 已打开 $appName"
        } else "❌ 找不到应用：$appName"
    }
}
```

### tools/ReminderTool.kt

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
        val title    = params.optString("title", "").ifBlank { return "Error: title is required" }
        val note     = params.optString("note", "")
        val datetime = params.optString("datetime", "").ifBlank { return "Error: datetime is required" }

        val triggerAt = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(datetime)?.time
                ?: return "Error: invalid datetime format"
        } catch (_: Exception) { return "Error: invalid datetime format" }

        if (triggerAt <= System.currentTimeMillis()) return "Error: reminder time must be in the future"

        val reminder = repository.addReminder(title, note, triggerAt)
        ReminderScheduler.schedule(context, reminder)
        return "Reminder set: "$title" at $datetime"
    }
}
```

---

## 7. 通知层

### notification/ReminderReceiver.kt

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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(note.ifBlank { "点击查看" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true).build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id.hashCode(), notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "LightAgent 提醒", NotificationManager.IMPORTANCE_HIGH
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

### notification/ReminderScheduler.kt

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
            putExtra(ReminderReceiver.EXTRA_NOTE,  reminder.note)
            putExtra(ReminderReceiver.EXTRA_ID,    reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, reminder.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }
}
```

---

## 8. Live2D + TTS 接口（预留）

### live2d/Live2DController.kt

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

### tts/TTSController.kt

```kotlin
package com.lightagent.tts

interface TTSController {
    val isSpeaking: Boolean
    fun init(onReady: () -> Unit = {}, onError: (Exception) -> Unit = {})
    fun speak(text: String, onStart: () -> Unit = {},
        onPlaybackProgress: (Float) -> Unit = {}, onDone: () -> Unit = {})
    fun stop(); fun setRate(rate: Float); fun setPitch(pitch: Float)
    fun setVoice(voiceId: String); fun release()
}

class NoOpTTSController : TTSController {
    override val isSpeaking: Boolean = false
    override fun init(onReady: () -> Unit, onError: (Exception) -> Unit) { onReady() }
    override fun speak(text: String, onStart: () -> Unit,
        onPlaybackProgress: (Float) -> Unit, onDone: () -> Unit) { onDone() }
    override fun stop() {}; override fun setRate(rate: Float) {}
    override fun setPitch(pitch: Float) {}
    override fun setVoice(voiceId: String) {}; override fun release() {}
}
```

---

## 9. ViewModel 层

### ui/ChatViewModel.kt

```kotlin
package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.agent.PlannerAgent
import com.lightagent.memory.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatMessage(val role: String, val content: String)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db   = AgentDatabase.getInstance(application)
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
            val all = conversations.first { it.isNotEmpty() || true }
            if (all.isEmpty()) createNewConversation()
            else switchConversation(all.first())
        }
    }

    fun updateInput(value: String) { _input.value = value }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty() || _isLoading.value) return
        _input.value = ""
        val cId = _currentConversationId.value ?: return
        viewModelScope.launch {
            _messages.update { it + ChatMessage("user", text) }
            repo.saveMessage(cId, "user", text)
            _isLoading.value = true
            try {
                val history = repo.getMessagesOnce(cId)
                    .map { mapOf("role" to it.role, "content" to it.content) }
                val agent = PlannerAgent.create(getApplication(), history.dropLast(1))
                val reply = agent.chat(text)
                _messages.update { it + ChatMessage("assistant", reply) }
                repo.saveMessage(cId, "assistant", reply)
            } catch (e: Exception) {
                _messages.update { it + ChatMessage("assistant", "Error: ${e.message}") }
            } finally { _isLoading.value = false }
        }
    }

    fun createNewConversation() {
        viewModelScope.launch { val c = repo.createConversation(); switchConversation(c) }
    }

    fun switchConversation(conv: ConversationEntity) {
        _currentConversationId.value = conv.id
        viewModelScope.launch {
            _messages.value = repo.getMessagesOnce(conv.id).map { ChatMessage(it.role, it.content) }
        }
    }

    fun deleteConversation(conv: ConversationEntity) {
        viewModelScope.launch {
            repo.deleteConversation(conv.id)
            if (_currentConversationId.value == conv.id) {
                val all = repo.allConversations.first()
                if (all.isEmpty()) createNewConversation() else switchConversation(all.first())
            }
        }
    }
}
```

### ui/ReminderViewModel.kt

```kotlin
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
    private val repo = ReminderRepository(AgentDatabase.getInstance(application).reminderDao())
    val reminders: StateFlow<List<ReminderEntity>> = repo.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markDone(id: String) = viewModelScope.launch {
        repo.markDone(id); ReminderScheduler.cancel(getApplication(), id)
    }
    fun delete(id: String) = viewModelScope.launch {
        repo.deleteReminder(id); ReminderScheduler.cancel(getApplication(), id)
    }
}
```

### ui/BackgroundViewModel.kt

```kotlin
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

class BackgroundViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("bg_prefs", Context.MODE_PRIVATE)
    private val defaultBackgrounds = (1..43).map { "bg_default_$it.png" }

    private val _background = MutableStateFlow<BackgroundSource>(loadSaved())
    val background: StateFlow<BackgroundSource> = _background

    fun randomBackground() {
        val current = (_background.value as? BackgroundSource.Asset)?.fileName
        val next = defaultBackgrounds.filter { it != current }.random()
        _background.value = BackgroundSource.Asset(next); saveAsset(next)
    }

    fun setCustomBackground(uri: Uri) {
        try { getApplication<Application>().contentResolver
            .takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        _background.value = BackgroundSource.Custom(uri)
        prefs.edit().putString(KEY_TYPE, TYPE_CUSTOM).putString(KEY_URI, uri.toString()).apply()
    }

    fun resetToDefault() {
        val first = defaultBackgrounds.first()
        _background.value = BackgroundSource.Asset(first); saveAsset(first)
    }

    private fun loadSaved(): BackgroundSource = when (prefs.getString(KEY_TYPE, TYPE_ASSET)) {
        TYPE_CUSTOM -> {
            val uriStr = prefs.getString(KEY_URI, null)
            if (uriStr != null) BackgroundSource.Custom(Uri.parse(uriStr))
            else BackgroundSource.Asset(defaultBackgrounds.first())
        }
        else -> BackgroundSource.Asset(prefs.getString(KEY_ASSET, defaultBackgrounds.first()) ?: defaultBackgrounds.first())
    }

    private fun saveAsset(name: String) { prefs.edit().putString(KEY_TYPE, TYPE_ASSET).putString(KEY_ASSET, name).apply() }

    companion object {
        private const val KEY_TYPE = "bg_type"; private const val TYPE_ASSET = "asset"
        private const val KEY_URI = "bg_uri"; private const val TYPE_CUSTOM = "custom"
        private const val KEY_ASSET = "bg_asset"
    }
}
```

---

## 10. UI 主题

### ui/theme/Color.kt

```kotlin
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

// ── 动画令牌 ──
object AnimTokens {
    const val StaggerBase = 60L        // stagger 逐项延迟基数 (ms)
    const val MessageSlideInY = 24f    // 新消息弹入偏移
    const val FadeDuration = 200       // 渐变动画时长 (ms)
    const val SelectionDuration = 180  // 选中/未选中过渡时长 (ms)
    const val BouncyDamping = 0.55f    // 弹簧阻尼比率
    const val BouncyStiffness = 380f   // 弹簧刚度
}
```

### ui/theme/Theme.kt

```kotlin
package com.lightagent.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentPurple, secondary = AccentBlue,
    background = DeepNavy, surface = GlassBg,
    onPrimary = Color.White, onBackground = TextPrimary, onSurface = TextPrimary,
)

@Composable
fun LightAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, typography = AppTypography, content = content)
}
```

### ui/theme/Type.kt

```kotlin
package com.lightagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 12.sp, lineHeight = 18.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
)
```

---

## 11. UI 屏幕

### ui/screen/SplashScreen.kt

```kotlin
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
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(GradientStart, GradientMid, GradientEnd))),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(alpha.value)) {
            Text("✨ LightAgent", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("你的轻量AI助手", color = TextSecondary, fontSize = 14.sp)
        }
    }
}
```

### ui/screen/ChatBackground.kt

```kotlin
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

@Composable
fun ChatBackground(source: BackgroundSource, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        when (source) {
            is BackgroundSource.Asset -> AssetBackground(fileName = source.fileName)
            is BackgroundSource.Custom -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(source.uri).crossfade(true).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            is BackgroundSource.SolidColor ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }
}

@Composable
private fun AssetBackground(fileName: String) {
    val context = LocalContext.current
    val bitmap = remember(fileName) {
        runCatching { context.assets.open("backgrounds/$fileName").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}
```

### ui/screen/BackgroundSettingsSheet.kt

```kotlin
package com.lightagent.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsSheet(
    onDismiss: () -> Unit, onRandomBackground: () -> Unit,
    onCustomBackground: (Uri) -> Unit, onResetDefault: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { onCustomBackground(uri); onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("🎨 背景设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))
            SettingsItem(icon = { Icon(Icons.Default.Refresh, null) }, title = "随机切换背景",
                subtitle = "从预置图库随机挑一张", onClick = { onRandomBackground(); onDismiss() })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SettingsItem(icon = { Icon(Icons.Default.Star, null) }, title = "从相册选择",
                subtitle = "使用你自己的图片", onClick = { launcher.launch("image/*") })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SettingsItem(icon = { Text("↩", style = MaterialTheme.typography.titleMedium) },
                title = "恢复默认", subtitle = "回到第一张预置背景", onClick = { onResetDefault(); onDismiss() })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsItem(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        icon(); Spacer(Modifier.width(16.dp))
        Column { Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
```

### ui/screen/ConversationDrawer.kt

```kotlin
package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.theme.AnimTokens
import kotlinx.coroutines.delay

@Composable
fun ConversationDrawer(
    conversations: List<ConversationEntity>, currentConversationId: String?,
    onSelectConversation: (ConversationEntity) -> Unit, onNewConversation: () -> Unit,
    onDeleteConversation: (ConversationEntity) -> Unit, onOpenReminders: () -> Unit,
    onOpenSettings: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxHeight().width(280.dp).padding(vertical = 16.dp)) {
        Text("💬 会话列表", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        TextButton(onClick = onNewConversation, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("新建会话")
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(conversations, key = { _, c -> c.id }) { index, conv ->
                StaggeredItem(index = index) {
                    ConversationItem(conv = conv, isSelected = conv.id == currentConversationId,
                        onSelect = { onSelectConversation(conv) }, onDelete = { onDeleteConversation(conv) })
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth().clickable { onOpenReminders() }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("⏰", fontSize = 18.sp); Spacer(Modifier.width(12.dp)); Text("提醒事项", fontSize = 15.sp)
        }
        Row(Modifier.fillMaxWidth().clickable { onOpenSettings() }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp)); Text("⚙️ 模型设置", fontSize = 15.sp)
        }
    }
}

@Composable
private fun StaggeredItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val translateY by animateFloatAsState(
        targetValue = if (visible) 0f else 24f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "staggerY$index")
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration), label = "staggerA$index")
    LaunchedEffect(Unit) { delay((index.coerceAtMost(8) * AnimTokens.StaggerBase).toLong()); visible = true }
    Box(Modifier.graphicsLayer { translationY = translateY; this.alpha = alpha }) { content() }
}

@Composable
private fun ConversationItem(conv: ConversationEntity, isSelected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    val bgAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(AnimTokens.SelectionDuration), label = "convBg")
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clickable { onSelect() },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = bgAlpha * 0.15f),
        tonalElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(conv.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1)
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "删除", Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

### ui/screen/ReminderScreen.kt

```kotlin
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
fun ReminderScreen(onBack: () -> Unit, reminderViewModel: ReminderViewModel = viewModel()) {
    val reminders by reminderViewModel.reminders.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("⏰ 提醒事项") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
    }) { padding ->
        if (reminders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有提醒事项\n跟 AI 说"提醒我..."来添加吧 😊", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Spacer(Modifier.height(8.dp)) }
                items(reminders, key = { it.id }) { r -> ReminderCard(r,
                    onMarkDone = { reminderViewModel.markDone(r.id) },
                    onDelete = { reminderViewModel.delete(r.id) }) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ReminderCard(reminder: ReminderEntity, onMarkDone: () -> Unit, onDelete: () -> Unit) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(reminder.title, style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (reminder.isCompleted) TextDecoration.LineThrough else TextDecoration.None)
                if (reminder.note.isNotEmpty()) Text(reminder.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sdf.format(Date(reminder.triggerAt)), color = MaterialTheme.colorScheme.primary)
            }
            if (!reminder.isCompleted) IconButton(onClick = onMarkDone) { Icon(Icons.Default.Check, "完成") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
        }
    }
}
```

### ui/screen/LLMSettingsScreen.kt

```kotlin
package com.lightagent.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lightagent.llm.LLMClient
import com.lightagent.llm.LLMConfig
import com.lightagent.llm.LLMConfigStore
import com.lightagent.llm.LLMProvider
import kotlinx.coroutines.delay as coroutineDelay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val currentConfig = remember { LLMClient.getInstance(context).getConfig() }
    var selectedProvider by remember { mutableStateOf(currentConfig.provider) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var model by remember { mutableStateOf(currentConfig.model) }
    var customUrl by remember { mutableStateOf(currentConfig.customUrl) }
    var temperature by remember { mutableStateOf(currentConfig.temperature.toFloat()) }
    var maxTokens by remember { mutableStateOf(currentConfig.maxTokens.toString()) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedProvider) {
        if (selectedProvider != LLMProvider.CUSTOM) model = selectedProvider.defaultModel
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("⚙️ 模型设置") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = {
                    val newConfig = LLMConfig(
                        provider = selectedProvider, apiKey = apiKey.trim(), model = model.trim(),
                        customUrl = customUrl.trim(), temperature = temperature.toDouble(),
                        maxTokens = maxTokens.toIntOrNull() ?: 2048)
                    LLMClient.getInstance(context).updateConfig(newConfig)
                    LLMConfigStore.save(context, newConfig)
                    showSaved = true
                }) { Icon(Icons.Default.Check, "保存") }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🤖 提供商", style = MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(onClick = { providerMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedProvider.displayName)
                }
                DropdownMenu(expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false }) {
                    LLMProvider.values().forEach { p ->
                        DropdownMenuItem(text = { Text(p.displayName) }, onClick = {
                            selectedProvider = p; providerMenuExpanded = false
                        })
                    }
                }
            }
            Text("🔑 API Key", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(if (apiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) } })
            Text("🧠 模型", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth())
            if (selectedProvider == LLMProvider.CUSTOM) {
                Text("🌐 自定义 Endpoint", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = customUrl, onValueChange = { customUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://your-api.com/v1/chat/completions") })
            }
            Text("🌡️ Temperature: $temperature", style = MaterialTheme.typography.titleSmall)
            Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f, steps = 19)
            Text("📏 Max Tokens", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (showSaved) {
                Text("✅ 配置已保存", color = MaterialTheme.colorScheme.primary)
                LaunchedEffect(Unit) { coroutineDelay(2000); showSaved = false }
            }
        }
    }
}
```

---

## 12. ChatScreen (v3.0 动效升级版)

```kotlin
package com.lightagent.ui.screen

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.live2d.Live2DController
import com.lightagent.live2d.NoOpLive2DController
import com.lightagent.tts.NoOpTTSController
import com.lightagent.tts.TTSController
import com.lightagent.ui.BackgroundViewModel
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.ChatViewModel
import com.lightagent.ui.ReminderViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = viewModel(),
    reminderViewModel: ReminderViewModel = viewModel(),
    backgroundViewModel: BackgroundViewModel = viewModel(),
    live2DController: Live2DController = remember { NoOpLive2DController() },
    ttsController: TTSController = remember { NoOpTTSController() }
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showReminder by remember { mutableStateOf(false) }
    var showBgSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val conversations by chatViewModel.conversations.collectAsState()
    val currentConvId by chatViewModel.currentConversationId.collectAsState()
    val messages by chatViewModel.messages.collectAsState()
    val isLoading by chatViewModel.isLoading.collectAsState()
    val inputText by chatViewModel.input.collectAsState()
    val background by backgroundViewModel.background.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size) }

    LaunchedEffect(messages) {
        val last = messages.lastOrNull()
        if (last != null && last.role == "assistant")
            ttsController.speak(last.content, onPlaybackProgress = { volume -> live2DController.setLipSync(volume) })
    }

    if (showReminder) { ReminderScreen(onBack = { showReminder = false }, reminderViewModel); return }
    if (showSettings) { LLMSettingsScreen(onBack = { showSettings = false }); return }
    if (showBgSheet) {
        BackgroundSettingsSheet(
            onDismiss = { showBgSheet = false }, onRandomBackground = { backgroundViewModel.randomBackground() },
            onCustomBackground = { uri: Uri -> backgroundViewModel.setCustomBackground(uri) },
            onResetDefault = { backgroundViewModel.resetToDefault() })
    }

    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ConversationDrawer(
            conversations = conversations, currentConversationId = currentConvId,
            onSelectConversation = { conv -> chatViewModel.switchConversation(conv); scope.launch { drawerState.close() } },
            onNewConversation = { chatViewModel.createNewConversation(); scope.launch { drawerState.close() } },
            onDeleteConversation = { conv -> chatViewModel.deleteConversation(conv) },
            onOpenReminders = { showReminder = true; scope.launch { drawerState.close() } },
            onOpenSettings = { showSettings = true; scope.launch { drawerState.close() } })
    }) {
        Box(Modifier.fillMaxSize()) {
            ChatBackground(source = background)
            Scaffold(containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text(conversations.find { it.id == currentConvId }?.title ?: "LightAgent") },
                        navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "菜单") } },
                        actions = { IconButton(onClick = { scope.launch { drawerState.close() }; showBgSheet = true }) { Icon(Icons.Default.MoreVert, "背景") } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
                },
                bottomBar = {
                    ChatInputBar(text = inputText, isLoading = isLoading, enabled = !isLoading,
                        onTextChange = { chatViewModel.updateInput(it) }, onSend = { chatViewModel.send() })
                }
            ) { padding ->
                LazyColumn(state = listState, modifier = Modifier
@Composable
private fun ChatInputBar(text: String, isLoading: Boolean, enabled: Boolean,
    onTextChange: (String) -> Unit, onSend: () -> Unit) {
    val sendBtnScale by animateFloatAsState(
        targetValue = when { isLoading -> 0.85f; text.isBlank() -> 0.9f; else -> 1f },
        animationSpec = spring(dampingRatio = AnimTokens.BouncyDamping, stiffness = AnimTokens.BouncyStiffness), label = "sendScale")
    val sendBtnAlpha by animateFloatAsState(
        targetValue = if (text.isBlank() && !isLoading) 0.3f else 1f,
        animationSpec = tween(AnimTokens.FadeDuration), label = "sendAlpha")
    val sendBtnRotation by animateFloatAsState(targetValue = if (isLoading) 90f else 0f, animationSpec = tween(300), label = "sendRotate")
    val sendIcon = if (isLoading) Icons.Default.Close else Icons.AutoMirrored.Filled.Send
    val sendIconTint by animateColorAsState(targetValue = if (isLoading) StatusThinking else AccentPurple,
        animationSpec = tween(AnimTokens.FadeDuration), label = "sendIconTint")

    Surface(Modifier.fillMaxWidth(), color = GlassBg, tonalElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = onTextChange, modifier = Modifier.weight(1f), enabled = enabled,
                placeholder = { Text("给天爱星发送消息...", color = TextHint) },
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp), shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentPurple.copy(alpha = 0.5f),
                    unfocusedBorderColor = GlassBorder, focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent, cursorColor = AccentPurple), maxLines = 5)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSend, enabled = text.isNotBlank() || isLoading,
                modifier = Modifier.size(44.dp).graphicsLayer { scaleX = sendBtnScale; scaleY = sendBtnScale; this.alpha = sendBtnAlpha; rotationZ = sendBtnRotation }) {
                Icon(sendIcon, contentDescription = if (isLoading) "停止" else "发送", tint = sendIconTint, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// ═══ 动画气泡 ═══
@Composable
private fun AnimatedMessageBubble(message: ChatMessage, index: Int, isLatest: Boolean, isStreaming: Boolean) {
    var visible by remember { mutableStateOf(false) }
    val isUser = message.role == "user"
    val slideY by animateFloatAsState(targetValue = if (visible) 0f else AnimTokens.MessageSlideInY,
        animationSpec = spring(dampingRatio = AnimTokens.BouncyDamping, stiffness = AnimTokens.BouncyStiffness), label = "msgSlideY$index")
    val alpha by animateFloatAsState(targetValue = if (visible) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration), label = "msgAlpha$index")
    LaunchedEffect(Unit) { delay((index.coerceAtMost(15) * AnimTokens.StaggerBase / 2).toLong()); visible = true }

    val bubbleBg = if (isUser) UserBubble else GlassBg
    val bubbleShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 16.dp)
    val cursorAlpha by rememberInfiniteTransition().animateFloat(initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(500), repeatMode = RepeatMode.Reverse), label = "cursor")

    Row(Modifier.fillMaxWidth().graphicsLayer { translationY = slideY; this.alpha = alpha }.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(Modifier.widthIn(max = 300.dp), shape = bubbleShape, color = bubbleBg) {
            Column(Modifier.padding(12.dp)) {
                Text(message.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                if (isStreaming && isLatest) {
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.width(8.dp).height(14.dp).alpha(cursorAlpha).background(AccentPurple))
                }
            }
        }
    }
}

// ═══ 思考跳动三点 ═══
@Composable
private fun ThinkingDots() {
    Row(Modifier.background(GlassBg, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by rememberInfiniteTransition(label = "dot$i").animateFloat(initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(animation = tween(durationMillis = 600, delayMillis = i * 200),
                repeatMode = RepeatMode.Reverse), label = "dotAlpha$i")
            Box(Modifier.size(8.dp).alpha(alpha).background(AccentPurple, CircleShape))
        }
    }
}
```

---

## 13. 旧版组件（保留但不参与主流程）

### ui/components/CharacterPanel.kt

```kotlin
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
fun CharacterPanel(state: ChatState, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val offsetY by infiniteTransition.animateFloat(initialValue = 0f, targetValue = -12f,
        animationSpec = infiniteRepeatable(animation = tween(2000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "offsetY")
    val alpha by animateFloatAsState(targetValue = if (state is ChatState.Thinking) 0.6f else 1f,
        animationSpec = tween(400), label = "alpha")
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(painter = painterResource(id = R.drawable.character_default), contentDescription = "Agent",
            modifier = Modifier.height(200.dp).alpha(alpha).graphicsLayer { translationY = offsetY })
    }
}
```

### ui/components/StatusIndicator.kt

```kotlin
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
    val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(animation = tween(600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "scale")
    val (color, label) = when (state) {
        is ChatState.Thinking -> StatusThinking to "🧠 思考中"
        is ChatState.CallingTool -> StatusTool to "🧰 执行工具"
        is ChatState.Error -> StatusTool to "❌ 出错了"
        else -> StatusIdle to "💫 就绪"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.size(8.dp).scale(if (state !is ChatState.Idle) scale else 1f).background(color, CircleShape))
        Spacer(Modifier.width(6.dp)); Text(text = label, color = color, fontSize = 12.sp)
    }
}
```

### ui/components/GlassCard.kt

```kotlin
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
fun GlassCard(modifier: Modifier = Modifier, cornerRadius: Dp = 16.dp, content: @Composable () -> Unit) {
    Box(modifier = modifier.clip(RoundedCornerShape(cornerRadius))
        .background(Brush.linearGradient(listOf(Color(0x44FFFFFF), Color(0x11FFFFFF))))
        .border(0.5.dp, GlassBorder, RoundedCornerShape(cornerRadius)).padding(12.dp)) { content() }
}
```

### ui/components/MessageBubble.kt

```kotlin
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Box(Modifier.widthIn(max = 280.dp)
            .clip(RoundedCornerShape(topStart = if (isUser) 16.dp else 4.dp, topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(if (isUser) UserBubble else AssistantBubble).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
```

### ui/components/InputBar.kt

```kotlin
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
fun InputBar(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, enabled: Boolean = true) {
    val borderColor by animateColorAsState(targetValue = if (value.isNotEmpty()) AccentPurple else GlassBorder, label = "border")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x11FFFFFF))))
            .border(1.dp, borderColor, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (value.isEmpty()) Text("和 AI 说点什么...", color = TextHint, fontSize = 14.sp)
            BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled,
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(AccentPurple), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSend, enabled = value.isNotEmpty() && enabled,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(
                if (value.isNotEmpty()) Brush.linearGradient(listOf(AccentPurple, AccentBlue)) else Brush.linearGradient(listOf(GlassBg, GlassBg)))) {
            Icon(Icons.AutoMirrored.Rounded.Send, "发送", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}
```

---

## 数据流总览

```
用户输入
  → ChatViewModel.send()
    → PlannerAgent.create(context, history).chat(text)
      → LLMClient.chat(messages) → OkHttp → OpenAI 兼容 API
      → TOOL:/PARAMS: 文本解析 → Tool.execute(JSONObject)
    → StateFlow 更新 → Compose 重组（带动画）
    → Room 持久化消息
    → TTS 朗读 (预留)
```

## 动效清单（v3.0 新增 — Compose 1.7.x）

| 动效 | API | 触发场景 |
|------|-----|----------|
| Stagger 列表进场 | `spring()` + `delay(index*60ms)` | 抽屉打开时会话项逐个弹入 |
| 气泡弹入 | `graphicsLayer` + `spring()` | 新消息从下方滑入 + 淡入 |
| 三点跳动 | `infiniteRepeatable` | LLM 思考中气泡末尾三点 |
| 发送按钮变形 | `animateFloatAsState`(scale+rotate+alpha) | 加载→停止/空→低亮 |
| 打字光标 | `infiniteRepeatable` blink | AI 流式输出时闪烁 |
| 选中态过渡 | `animateFloatAsState` bgAlpha | 会话选中/取消背景色渐变 |

## 最终统计

| 维度 | 数值 |
|------|------|
| Kotlin 源文件 | 38 |
| Compose BOM | 2024.09.03 (Compose 1.7.x) |
| Room 表 | 4 张 (user_facts, conversations, messages, reminders) |
| LLM 提供商 | 4 个 (DeepSeek/OpenAI/通义千问/自定义) |
| 工具 | 4 个 (天气/记事/开App/提醒) |
| 背景图 | 43 张 |
| 预留接口 | Live2D + TTS |
| APK 体积 | ~102 MB |
| minSdk / targetSdk | 26 / 34 |
| 编译警告 | 0 |
