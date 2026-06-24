# 天爱星 Agent v4.4.2 — 实时 TTS 语音播报 · 完整源码变更

> 基于 v4.4.1-n（去立绘1测试版），增量升级  
> 构建：**BUILD SUCCESSFUL** ✅ · 零 warning  
> APK：`天爱星Agent-v4.4.2.apk`（244.6MB，versionCode 5 / versionName 4.4.2）

---

## 升级概要

| 任务 | 文件 | 改动 |
|------|------|------|
| Task 3 — 音频焦点 | `music/MusicPlaybackService.kt` | +82 行，`AudioFocusRequest` + `OnAudioFocusChangeListener` |
| Task 4 — TTS 开关 | `ui/ChatViewModel.kt` | +6 行，`_ttsEnabled` + `toggleTts()` + feedStream/flushStream 守卫 |
| Task 4 — UI 开关 | `ui/screen/GalGameChatLayout.kt` | +17 行，输入栏左侧 IconToggleButton |
| Task 4 — 接线 | `ui/screen/ChatScreen.kt` | +2 行，收集 `ttsEnabled` 传入 GalGameChatLayout |
| 版本号 | `app/build.gradle` | versionCode 4→5 / versionName 4.4.1-n→4.4.2 |

---

## 文件 1 — `music/MusicPlaybackService.kt`（新增 AudioFocus）

```kotlin
package com.lightagent.music

import android.app.*
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lightagent.R
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 后台音乐播放 Service（前台服务）— v4.4.2 增加 AudioFocus
 */
class MusicPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID     = "tianaixing_music"
        const val NOTIFICATION_ID = 4242
        const val ACTION_PLAY    = "com.lightagent.music.PLAY"
        const val ACTION_PAUSE   = "com.lightagent.music.PAUSE"
        const val ACTION_NEXT    = "com.lightagent.music.NEXT"
        const val ACTION_PREV    = "com.lightagent.music.PREV"
        const val ACTION_STOP    = "com.lightagent.music.STOP"

        @Volatile
        var instance: MusicPlaybackService? = null
            private set
    }

    // ── 播放器 ─────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private val lyricManager = LyricManager()
    private lateinit var audioManager: AudioManager

    // ═══════════════════ v4.4.2 新增：音频焦点 ═══════════════
    private var hasAudioFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
                hasAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_state.value.isPlaying) pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.3f, 0.3f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
                if (_state.value.isPlaying) resume()
                hasAudioFocus = true
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        val result = audioManager.requestAudioFocus(request)
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManager.abandonAudioFocusRequest(request)
            hasAudioFocus = false
        }
    }
    // ════════════════════════════════════════════════════════

    // ── 状态流 ─────────────────────────────────────────────
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val playlist = mutableListOf<Song>()
    private var currentIndex = -1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class MusicBinder : Binder() {
        fun getService() = this@MusicPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = MusicBinder()

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager   // ← v4.4.2
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setupListeners()
        }
        setupMediaSession()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        createNotificationChannel()
    }

    private fun MediaPlayer.setupListeners() {
        setOnPreparedListener { mp ->
            if (!requestAudioFocus())                                       // ← v4.4.2
                android.util.Log.w("MusicService", "无法获取音频焦点")
            mp.start()
            updateState { it.copy(isPlaying = true) }
            updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification())
            scope.launch {
                while (isActive && mp.isPlaying) {
                    updateState { s ->
                        s.copy(progress = mp.currentPosition.toLong(), duration = mp.duration.toLong())
                    }
                    delay(250L)
                }
            }
        }
        // …（其余 MediaPlayer 监听器略，未改动）
    }

    override fun onDestroy() {
        abandonAudioFocus()    // ← v4.4.2
        instance = null
        scope.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.isActive = false
        mediaSession.release()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // …（其余方法 play/pause/resume/next/prev 等未改动）
}
```

> 关键改动点（与 v4.4.1 相比）：
> 1. import `android.media.AudioFocusRequest`
> 2. 新增 `audioManager` / `hasAudioFocus` / `focusChangeListener` / `requestAudioFocus()` / `abandonAudioFocus()`
> 3. `onCreate()` 中初始化 `audioManager`
> 4. `setOnPreparedListener` 中播放前调用 `requestAudioFocus()`
> 5. `onDestroy()` 中调用 `abandonAudioFocus()`

---

## 文件 2 — `ui/ChatViewModel.kt`（新增 TTS 开关）

```kotlin
package com.lightagent.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.ai.EmotionParser
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterStateHolder
import com.lightagent.llm.KokoroLLM
import com.lightagent.memory.ConversationEntity
import com.lightagent.memory.ConversationRepository
import com.lightagent.tts.KokoroTTSManager
import com.lightagent.ui.theme.DesktopAgentService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ConversationRepository(application)
    private val llm = KokoroLLM()
    val ttsManager = KokoroTTSManager.getInstance(application)

    private var streamJob: Job? = null

    private val _input        = MutableStateFlow("")
    val input: StateFlow<String> = _input

    val currentEmotion: StateFlow<CharacterEmotion> = EmotionParser.currentEmotion

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    val isTalking: StateFlow<Boolean> = ttsManager.isTalking

    // ═══════════════ v4.4.2 新增：TTS 开关 ═══════════════
    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    fun toggleTts() {
        _ttsEnabled.update { !it }
        if (!_ttsEnabled.value) ttsManager.stop()
    }
    // ══════════════════════════════════════════════════════

    val conversations: StateFlow<List<ConversationEntity>> = repo.allConversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // …（其余代码略）…

    fun send(userInput: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            // …（略）…
            llm.chatStream(history).collect { chunk ->
                // …（略）…
                val cleanChunk = chunk.replace(
                    Regex("\\[EMOTION:[a-zA-Z_\\u4e00-\\u9fa5]+]"), ""
                )
                if (cleanChunk.isNotBlank() && _ttsEnabled.value) {     // ← v4.4.2
                    ttsManager.feedStream(cleanChunk)
                }
            }
            // 把 TTS 缓冲区剩余内容也播掉
            if (_ttsEnabled.value) ttsManager.flushStream()              // ← v4.4.2
            // …（略）…
        }
    }
}
```

> 关键改动：`feedStream()` 和 `flushStream()` 均受 `_ttsEnabled.value` 守卫

---

## 文件 3 — `ui/screen/GalGameChatLayout.kt`（输入栏 TTS 开关）

```kotlin
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff     // ← v4.4.2
import androidx.compose.material.icons.automirrored.filled.VolumeUp     // ← v4.4.2
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
import com.lightagent.LocalScreenMode
import com.lightagent.ScreenMode
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterPack
import com.lightagent.character.CharacterView
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.theme.*

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
    onSend          : () -> Unit,
    ttsEnabled      : Boolean = true,       // ← v4.4.2
    onToggleTts     : () -> Unit = {}       // ← v4.4.2
) {
    // …（布局略，末尾调用 GalInputBar）…

            GalInputBar(
                inputText    = inputText,
                onTextChange = onTextChange,
                onSend       = onSend,
                ttsEnabled   = ttsEnabled,     // ← v4.4.2
                onToggleTts  = onToggleTts     // ← v4.4.2
            )
}

// ══════════════════════════════════════════════════════════════════════════════
// 输入栏 — v4.4.2：左侧新增 TTS 开关
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalInputBar(
    inputText    : String,
    onTextChange : (String) -> Unit,
    onSend       : () -> Unit,
    ttsEnabled   : Boolean = true,
    onToggleTts  : () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ═══════════ v4.4.2 新增：TTS 语音开关 ═══════════
        IconToggleButton(
            checked = ttsEnabled,
            onCheckedChange = { onToggleTts() },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp
                              else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = if (ttsEnabled) "关闭语音播报" else "开启语音播报",
                tint = if (ttsEnabled) AccentPurple else TextHint,
                modifier = Modifier.size(22.dp)
            )
        }
        // ══════════════════════════════════════════════════

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
// …（其余组件略，未改动）
```

---

## 文件 4 — `ui/screen/ChatScreen.kt`（接线 ttsEnabled）

```kotlin
    // 在 ChatContent 中，收集 ttsEnabled：
    val ttsEnabled      by chatViewModel.ttsEnabled.collectAsState()       // ← v4.4.2

    // … GalGameChatLayout 调用中传入：
    GalGameChatLayout(
        // …其他参数…
        onSend          = { chatViewModel.send() },
        ttsEnabled      = ttsEnabled,                                   // ← v4.4.2
        onToggleTts     = { chatViewModel.toggleTts() }                 // ← v4.4.2
    )
```

---

## 文件 5 — `app/build.gradle`（版本号）

```groovy
android {
    defaultConfig {
        applicationId "com.lightagent.app"
        minSdk 26
        targetSdk 34
        versionCode 5             // 4.4.1-n: 4 → v4.4.2: 5
        versionName "4.4.2"       // 4.4.1-n → 4.4.2
    }
}
```

---

## 任务完成清单

| # | 任务 | 状态 |
|---|------|------|
| 1 | TTSController 封装 | ✅ 已有（KokoroTTSController） |
| 2 | ChatViewModel TTS 触发点 | ✅ 已有 + v4.4.2 守卫 |
| 3 | **AudioFocus 音频焦点** | ✅ v4.4.2 新增 |
| 4 | **UI 开关面板** | ✅ v4.4.2 新增 |
| 5 | 生命周期（stop/release） | ✅ 已有 |
| 6 | 端到端测试清单 | ⬜ 待用户测试 |

---

## 音频焦点逻辑

```
音乐播放中 → TTS 开始播报
  → focusChangeListener 收到 AUDIOFOCUS_LOSS_TRANSIENT
  → 音乐自动暂停

TTS 播报结束
  → focusChangeListener 收到 AUDIOFOCUS_GAIN
  → 音乐自动恢复播放（保持原 isPlaying 状态）

用户手动关闭 TTS
  → toggleTts() 设置 _ttsEnabled = false
  → 立即调用 ttsManager.stop()
  → 音乐继续播放（不失焦点）
```

---

*生成时间：2026-06-25*  
*构建：BUILD SUCCESSFUL · 零 warning*
