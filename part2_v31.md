akeLast(10)
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
