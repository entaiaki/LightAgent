# 天爱星Agent v2.0 - 源码全览

> 生成: 2026-06-21 | 包名: com.lightagent | Kotlin: 38 文件 | APK: 102 MB
>
> 多模型 AI 助手，支持 DeepSeek/OpenAI/通义千问/自定义，文本态工具调用，
> Room 持久化，AlarmManager 提醒，43 张可切换背景，Live2D/TTS 接口预留。

---

## 文件索引

| # | 路径 | 说明 |
|---|------|------|
| 1 | build.gradle | 项目根构建 |
| 2 | settings.gradle | 模块配置 |
| 3 | gradle.properties | Gradle 属性 |
| 4 | app/build.gradle | 应用构建 + 依赖 |
| 5 | AndroidManifest.xml | 权限 + Activity + Receiver |
| 6 | res/values/strings.xml | app_name="天爱星Agent" |
| 7 | res/values/themes.xml | 全屏深色主题 |
| 8 | res/drawable/character_default.xml | 矢量角色 |
| 9 | MainActivity.kt | 入口 |
| 10 | agent/ChatState.kt | 状态枚举 |
| 11 | agent/PlannerAgent.kt | LLM 调度 + 工具调用 |
| 12 | llm/LLMClient.kt | OkHttp 客户端 + LLMProvider 枚举 + LLMConfig |
| 13 | llm/LLMConfigStore.kt | SharedPreferences 持久化 |
| 14 | tools/Tool.kt | 工具接口 |
| 15 | tools/WeatherTool.kt | wttr.in 天气查询 |
| 16 | tools/NoteTool.kt | 本地文件记事 |
| 17 | tools/OpenAppTool.kt | 包名启动应用 |
| 18 | tools/ReminderTool.kt | AlarmManager 提醒 |
| 19 | memory/UserProfileMemory.kt | 含 AgentDatabase + UserFact + UserFactDao + UserProfileMemory |
| 20 | memory/ConversationEntity.kt | 会话 Entity |
| 21 | memory/MessageEntity.kt | 消息 Entity (FK→conversations) |
| 22 | memory/ConversationDao.kt | 会话+消息 DAO |
| 23 | memory/ConversationRepository.kt | 会话仓库 |
| 24 | memory/ReminderRepository.kt | 提醒 Entity + DAO + Repository |
| 25 | notification/ReminderReceiver.kt | BroadcastReceiver |
| 26 | notification/ReminderScheduler.kt | AlarmManager 调度 |
| 27 | live2d/Live2DController.kt | 接口 + NoOp 实现 |
| 28 | tts/TTSController.kt | 接口 + NoOp 实现 |
| 29 | ui/ChatViewModel.kt | 多会话 StateFlow |
| 30 | ui/BackgroundViewModel.kt | 背景持久化 |
| 31 | ui/ReminderViewModel.kt | 提醒 CRUD |
| 32 | ui/screen/ChatScreen.kt | 主聊天界面 v3 |
| 33 | ui/screen/ChatBackground.kt | 背景渲染(Asset/Custom/SolidColor) |
| 34 | ui/screen/BackgroundSettingsSheet.kt | 底部弹出设置 |
| 35 | ui/screen/ConversationDrawer.kt | 侧滑会话列表 |
| 36 | ui/screen/LLMSettingsScreen.kt | 模型切换设置 |
| 37 | ui/screen/ReminderScreen.kt | 提醒列表页 |
| 38 | ui/screen/SplashScreen.kt | 启动动画 |
| 39 | ui/theme/Color.kt | 调色板 |
| 40 | ui/theme/Theme.kt | Material3 深色主题 |
| 41 | ui/theme/Type.kt | 字体排版 |

---

## 1. 构建配置

### build.gradle (项目根)

```groovy
plugins {
    id 'com.android.application' version '8.2.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.22' apply false
}
```

### settings.gradle

```groovy
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "LightAgent"
include ':app'
```

### gradle.properties

```properties
android.useAndroidX=true
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
android.nonTransitiveRClass=true
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
    def composeBom = platform('androidx.compose:compose-bom:2024.02.00')
    implementation composeBom
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-graphics'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.material:material-icons-extended'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.activity:activity-compose:1.8.2'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'
    implementation 'org.json:json:20231013'
    implementation 'io.coil-kt:coil-compose:2.6.0'
}
```

---

## 2. AndroidManifest.xml

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
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
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

---

## 3. 资源文件

### res/values/strings.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">天爱星Agent</string>
</resources>
```

### res/values/themes.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.LightAgent"
        parent="@android:style/Theme.Material.NoActionBar.Fullscreen" />
</resources>
```

### res/drawable/character_default.xml
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="200dp" android:height="200dp"
    android:viewportWidth="200" android:viewportHeight="200">
    <!-- 头发 -->
    <path android:fillColor="#4A3F8F"
        android:pathData="M65,65 Q70,30 100,25 Q130,30 135,65 Q125,45 100,42 Q75,45 65,65 Z"/>
    <!-- 脸部 -->
    <path android:fillColor="#FFD6B0"
        android:pathData="M70,60 Q100,30 130,60 Q140,90 100,110 Q60,90 70,60 Z"/>
    <!-- 左眼 -->
    <path android:fillColor="#6C63FF"
        android:pathData="M79,75 A6,7 0 1,0 91,75 A6,7 0 1,0 79,75 Z"/>
    <!-- 右眼 -->
    <path android:fillColor="#6C63FF"
        android:pathData="M109,75 A6,7 0 1,0 121,75 A6,7 0 1,0 109,75 Z"/>
    <!-- 嘴巴 -->
    <path android:strokeColor="#FF8FAB" android:strokeWidth="2"
        android:fillColor="#00000000"
        android:pathData="M90,90 Q100,98 110,90"/>
    <!-- 身体 -->
    <path android:fillColor="#9B7FFF"
        android:pathData="M60,140 Q100,160 140,140 L130,200 L70,200 Z"/>
</vector>
```

---

## 4. MainActivity.kt

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

        // API Key 现在从 SharedPreferences 读取，由用户在设置界面填写

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

---

## 5. Agent 引擎

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

class PlannerAgent private constructor(
    private val llmClient: LLMClient,
    private val tools: List<Tool>,
    private val history: List<Map<String, String>>
) {
    companion object {
        fun create(context: Context,
                   history: List<Map<String, String>> = emptyList()
        ): PlannerAgent {
            val db = AgentDatabase.getInstance(context)
            val reminderRepo = ReminderRepository(db.reminderDao())
            val tools = listOf(
                WeatherTool(), NoteTool(context),
                OpenAppTool(context), ReminderTool(context, reminderRepo)
            )
            return PlannerAgent(LLMClient.getInstance(context), tools, history)
        }
    }

    private val systemPrompt = """
        You are LightAgent, a helpful AI assistant running on Android.
        You can use the following tools:
        1. get_weather(city): Get weather for a city
        2. save_note(title, content): Save a note
        3. open_app(package_name): Open an Android app
        4. add_reminder(title, note, datetime): Set a system reminder.
           datetime format: "yyyy-MM-dd HH:mm"

        To use a tool, respond with:
        TOOL: tool_name
        PARAMS: {"key": "value"}
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
        return response.lines().find { it.startsWith("TOOL:") }
            ?.removePrefix("TOOL:")?.trim()
    }

    private fun extractToolParams(response: String): org.json.JSONObject? {
        return response.lines().find { it.startsWith("PARAMS:") }
            ?.removePrefix("PARAMS:")?.trim()
            ?.let { try { org.json.JSONObject(it) } catch (_: Exception) { null } }
    }
}
```

---

## 6. LLM 层

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

enum class LLMProvider(val displayName: String, val baseUrl: String,
                       val defaultModel: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    QWEN("通义千问",
         "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
         "qwen-turbo"),
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
    val endpoint: String get() =
        if (provider == LLMProvider.CUSTOM) customUrl else provider.baseUrl
}

class LLMClient(private var config: LLMConfig) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

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

    suspend fun chat(messages: List<Map<String, String>>): String =
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            messages.forEach { m ->
                array.put(JSONObject().apply {
                    put("role", m["role"] ?: "user")
                    put("content", m["content"] ?: "")
                })
            }
            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", array)
                put("temperature", config.temperature)
                put("max_tokens", config.maxTokens)
            }
            val request = Request.Builder()
                .url(config.endpoint)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            JSONObject(responseBody).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
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
            .putInt(KEY_MAX_TOKENS, config.maxTokens).apply()
    }

    fun load(context: Context): LLMConfig {
        val p = prefs(context)
        val providerName = p.getString(KEY_PROVIDER,
            LLMProvider.DEEPSEEK.name) ?: LLMProvider.DEEPSEEK.name
        val provider = try { LLMProvider.valueOf(providerName) }
            catch (_: Exception) { LLMProvider.DEEPSEEK }
        return LLMConfig(
            provider = provider,
            apiKey = p.getString(KEY_API_KEY, "") ?: "",
            model = p.getString(KEY_MODEL, provider.defaultModel)
                ?: provider.defaultModel,
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

## 7. 工具系统

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
    override val description = "Query weather for a city using wttr.in"

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
                val json = JSONObject(body)
                val current = json.getJSONArray("current_condition")
                    .getJSONObject(0)
                val temp = current.getString("temp_C")
                val desc = current.getJSONArray("weatherDesc")
                    .getJSONObject(0).getString("value")
                "✅ $city 当前天气：$desc，${temp}°C"
            } catch (e: Exception) {
                "❌ 天气查询失败：${e.message}"
            }
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
        val content = params.optString("content", "").ifBlank {
            return "❌ 缺少 content 参数"
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm",
            Locale.getDefault()).format(Date())
        val file = File(context.filesDir, "notes.txt")
        file.appendText("[$timestamp] $content\n")
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
        val appName = params.optString("app_name", "").ifBlank {
            return "❌ 缺少 app_name 参数"
        }
        val intent = context.packageManager
            .getLaunchIntentForPackage(appName)
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
        val title = params.optString("title", "").ifBlank {
            return "Error: title is required" }
        val note = params.optString("note", "")
        val datetime = params.optString("datetime", "").ifBlank {
            return "Error: datetime is required" }
        val triggerAt = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .parse(datetime)?.time
                ?: return "Error: invalid datetime format"
        } catch (e: Exception) { return "Error: invalid datetime format" }
        if (triggerAt <= System.currentTimeMillis())
            return "Error: reminder time must be in the future"
        val reminder = repository.addReminder(title, note, triggerAt)
        ReminderScheduler.schedule(context, reminder)
        return "Reminder set: "$title" at $datetime"
    }
}
```

---

## 8. 记忆/数据库层

### memory/UserProfileMemory.kt（含 AgentDatabase）
```kotlin
package com.lightagent.memory

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "user_facts")
data class UserFact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String, val value: String,
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
    entities = [UserFact::class, ConversationEntity::class,
                MessageEntity::class, ReminderEntity::class],
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
                Room.databaseBuilder(context.applicationContext,
                    AgentDatabase::class.java, "agent_db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

class UserProfileMemory(context: Context) {
    private val dao = AgentDatabase.getInstance(context).userFactDao()
    suspend fun set(key: String, value: String) = withContext(Dispatchers.IO)
        { dao.insert(UserFact(key=key, value=value)) }
    suspend fun get(key: String): String? = withContext(Dispatchers.IO)
        { dao.getByKey(key)?.value }
    suspend fun delete(key: String) = withContext(Dispatchers.IO)
        { dao.deleteByKey(key) }
    suspend fun getAll(): List<UserFact> = withContext(Dispatchers.IO)
        { dao.getAll() }
}
```

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
import androidx.room.*
import java.util.UUID

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class,
        parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String, val role: String,
    val content: String, val timestamp: Long = System.currentTimeMillis()
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
    @Query("UPDATE conversations SET title=:title, updatedAt=:u WHERE id=:id")
    suspend fun updateConversationTitle(id: String, title: String,
        u: Long = System.currentTimeMillis())
    @Query("UPDATE conversations SET updatedAt=:u WHERE id=:id")
    suspend fun touchConversation(id: String, u: Long = System.currentTimeMillis())
    @Query("DELETE FROM conversations WHERE id=:id")
    suspend fun deleteConversation(id: String)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id=:id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
    @Query("SELECT * FROM messages WHERE conversationId=:c ORDER BY timestamp ASC")
    fun getMessagesForConversation(c: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId=:c ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationOnce(c: String): List<MessageEntity>
    @Query("DELETE FROM messages WHERE conversationId=:c")
    suspend fun deleteMessagesForConversation(c: String)
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
        dao.insertConversation(conv); return conv
    }
    suspend fun renameConversation(id: String, title: String)
        { dao.updateConversationTitle(id, title) }
    suspend fun deleteConversation(id: String) { dao.deleteConversation(id) }
    suspend fun saveMessage(cId: String, role: String, content: String): MessageEntity {
        val msg = MessageEntity(conversationId=cId, role=role, content=content)
        dao.insertMessage(msg); dao.touchConversation(cId); return msg
    }
    fun getMessagesFlow(cId: String) = dao.getMessagesForConversation(cId)
    suspend fun getMessagesOnce(cId: String) = dao.getMessagesForConversationOnce(cId)
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
    val title: String, val note: String = "", val triggerAt: Long,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity)
    @Query("UPDATE reminders SET isCompleted=:d WHERE id=:id")
    suspend fun markDone(id: String, d: Boolean = true)
    @Query("DELETE FROM reminders WHERE id=:id")
    suspend fun deleteReminder(id: String)
    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    fun getAllReminders(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE id=:id LIMIT 1")
    suspend fun getReminderById(id: String): ReminderEntity?
}

class ReminderRepository(private val dao: ReminderDao) {
    val allReminders: Flow<List<ReminderEntity>> = dao.getAllReminders()
    suspend fun addReminder(title: String, note: String = "",
        triggerAt: Long): ReminderEntity {
        val r = ReminderEntity(title=title, note=note, triggerAt=triggerAt)
        dao.insertReminder(r); return r
    }
    suspend fun markDone(id: String) = dao.markDone(id)
    suspend fun deleteReminder(id: String) = dao.deleteReminder(id)
    suspend fun getReminderById(id: String) = dao.getReminderById(id)
}
```

---

## 9. 通知系统

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
        val note  = intent.getStringExtra(EXTRA_NOTE) ?: ""
        val id    = intent.getStringExtra(EXTRA_ID) ?: return

        createNotificationChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(note.ifBlank { "点击查看" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true).build()

        val nm = context.getSystemService(
            Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id.hashCode(), notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "LightAgent 提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "AI 助手设置的提醒事项" }
            val nm = context.getSystemService(
                Context.NOTIFICATION_SERVICE) as NotificationManager
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
        val alarmManager = context.getSystemService(
            Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            putExtra(ReminderReceiver.EXTRA_NOTE,  reminder.note)
            putExtra(ReminderReceiver.EXTRA_ID,    reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, reminder.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms())
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, reminder.triggerAt, pendingIntent)
            else alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, reminder.triggerAt, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, reminder.triggerAt, pendingIntent)
        }
    }
    fun cancel(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(
            Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, reminderId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }
}
```

---

## 10. Live2D + TTS 接口

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
    override fun init(onReady: () -> Unit, onError: (Exception) -> Unit)
        { onReady() }
    override fun speak(text: String, onStart: () -> Unit,
        onPlaybackProgress: (Float) -> Unit, onDone: () -> Unit
    ) { onDone() }
    override fun stop() {}; override fun setRate(rate: Float) {}
    override fun setPitch(pitch: Float) {}
    override fun setVoice(voiceId: String) {}; override fun release() {}
}
```

---

## 11. ViewModel 层

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
    private val db = AgentDatabase.getInstance(application)
    private val repo = ConversationRepository(db.conversationDao())

    val conversations: StateFlow<List<ConversationEntity>> =
        repo.allConversations.stateIn(viewModelScope,
            SharingStarted.WhileSubscribed(5000), emptyList())

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
            val all = conversations.first { true }
            if (all.isEmpty()) createNewConversation()
            else switchConversation(all.first())
        }
    }

    fun onInputChange(value: String) { _input.value = value }

    fun sendMessage() {
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
                val agent = PlannerAgent.create(getApplication(),
                    history.dropLast(1))
                val reply = agent.chat(text)
                _messages.update { it + ChatMessage("assistant", reply) }
                repo.saveMessage(cId, "assistant", reply)
            } catch (e: Exception) {
                _messages.update {
                    it + ChatMessage("assistant", "Error: ${e.message}") }
            } finally { _isLoading.value = false }
        }
    }

    fun createNewConversation() {
        viewModelScope.launch {
            val conv = repo.createConversation(); switchConversation(conv)
        }
    }

    fun switchConversation(conv: ConversationEntity) {
        _currentConversationId.value = conv.id
        viewModelScope.launch {
            _messages.value = repo.getMessagesOnce(conv.id)
                .map { ChatMessage(it.role, it.content) }
        }
    }

    fun deleteConversation(conv: ConversationEntity) {
        viewModelScope.launch {
            repo.deleteConversation(conv.id)
            if (_currentConversationId.value == conv.id) {
                val all = repo.allConversations.first()
                if (all.isEmpty()) createNewConversation()
                else switchConversation(all.first())
            }
        }
    }
}
```

---

## 13. UI 主题

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
```

### ui/theme/Theme.kt

```kotlin
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

### ui/theme/Type.kt

```kotlin
package com.lightagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Light,
        fontSize   = 12.sp, lineHeight = 18.sp),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
)
```

---

## 14. UI 屏幕层（续）

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
fun ReminderScreen(onBack: () -> Unit,
    reminderViewModel: ReminderViewModel = viewModel()) {
    val reminders by reminderViewModel.reminders.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text("⏰ 提醒事项") },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
    }) { pad ->
        if (reminders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center) {
                Text("还没有提醒事项\n跟 AI 说"提醒我..."来添加吧 😊",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)
                .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Spacer(Modifier.height(8.dp)) }
                items(reminders, key = { it.id }) { r ->
                    ReminderCard(reminder = r,
                        onMarkDone = { reminderViewModel.markDone(r.id) },
                        onDelete = { reminderViewModel.delete(r.id) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ReminderCard(
    reminder: ReminderEntity,
    onMarkDone: () -> Unit, onDelete: () -> Unit
) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(reminder.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (reminder.isCompleted)
                        TextDecoration.LineThrough else TextDecoration.None)
                if (reminder.note.isNotEmpty())
                    Text(reminder.note,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sdf.format(Date(reminder.triggerAt)),
                    color = MaterialTheme.colorScheme.primary)
            }
            if (!reminder.isCompleted) {
                IconButton(onClick = onMarkDone) {
                    Icon(Icons.Default.Check, "完成")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "删除")
            }
        }
    }
}
```

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
    Box(modifier = Modifier.fillMaxSize()
        .background(Brush.verticalGradient(
            listOf(GradientStart, GradientMid, GradientEnd))),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha.value)) {
            Text("✨ LightAgent", color = TextPrimary,
                fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("你的轻量AI助手", color = TextSecondary, fontSize = 14.sp)
        }
    }
}
```

---

## 数据流总览

```
用户输入
  → ChatViewModel.sendMessage()
    → PlannerAgent.create(context, history).chat(text)
      → LLMClient.chat(messages) → OkHttp → OpenAI 兼容 API
      → TOOL:/PARAMS: 文本解析 → Tool.execute(JSONObject)
    → StateFlow 更新 → Compose 重组
    → Room 持久化消息
    → TTS 朗读 (预留)
```

## 最终统计

| 维度 | 数值 |
|------|------|
| Kotlin 文件 | 38 |
| Room 表 | 4 张 (user_facts, conversations, messages, reminders) |
| LLM 提供商 | 4 个 (DeepSeek/OpenAI/通义千问/自定义) |
| 工具 | 4 个 (天气/记事/开App/提醒) |
| 背景图 | 43 张 |
| 预留接口 | Live2D + TTS |
| APK 体积 | 102 MB |
| minSdk / targetSdk | 26 / 34 |
| 编译警告 | 0 |