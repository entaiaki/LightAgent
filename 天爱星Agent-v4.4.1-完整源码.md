# 天爱星Agent v4.4.1 — 完整源码（独立音乐界面 + ViewModel + 导航集成）

> **变更摘要**：在 v4.4.0 音乐模块基础上，新增 MusicViewModel 胶水层和 MusicScreen 独立全屏页面，通过扩展 ConversationDrawer 侧边栏将音乐界面接入现有导航系统。用户可从侧边栏「🎵 音乐播放器」入口直接进入独立音乐页面。

---

## 📁 新增文件清单（2 个文件）

| 路径 | 说明 |
|------|------|
| `ui/music/MusicViewModel.kt` | MusicPlaybackService ↔ UI 胶水层，StateFlow 状态 + 歌词索引 + 搜索/播放控制 |
| `ui/music/MusicScreen.kt` | 独立全屏音乐界面：搜索栏 + 歌曲列表 + 迷你播放器 + 全屏播放面板对话框 |

## 🔧 修改文件清单（4 个文件）

| 路径 | 变更 |
|------|------|
| `ui/screen/ChatScreen.kt` | Screen 枚举新增 Music；AnimatedContent 新增 MusicScreen 路由；传入 musicViewModel |
| `ui/screen/ConversationDrawer.kt` | 新增 onOpenMusic 回调 + 侧边栏底部「🎵 音乐播放器」入口 |
| `app/build.gradle` | versionCode 2→3 / versionName 4.4.0→4.4.1 |
| `ui/screen/MainActivity.kt` | （无变更，MusicPlaybackService 在 v4.4.0 已启动） |

---

## 📄 MusicViewModel.kt — 完整源码
> 路径：`app/src/main/java/com/lightagent/ui/music/MusicViewModel.kt`

```kotlin
package com.lightagent.ui.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.music.MusicPlaybackService
import com.lightagent.music.MusicRepository
import com.lightagent.music.model.LyricLine
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    val playbackState: StateFlow<PlaybackState> =
        repository.playbackState ?: MutableStateFlow(PlaybackState())

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    val currentLyricIndex: StateFlow<Int> = combine(
        playbackState, _lyrics
    ) { state, lines ->
        if (lines.isEmpty()) -1
        else {
            var idx = 0
            for (i in lines.indices) {
                if (lines[i].timeMs <= state.progress) idx = i else break
            }
            idx
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showPanel = MutableStateFlow(false)
    val showPanel: StateFlow<Boolean> = _showPanel.asStateFlow()

    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            val result = repository.search(query)
            _searchResults.value = result.local
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            repository.playSearch(song.title)
            loadLyrics(song)
        }
    }

    fun playAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        MusicPlaybackService.instance?.setPlaylist(songs)
        viewModelScope.launch { loadLyrics(songs.first()) }
    }

    fun togglePlayPause() {
        val service = MusicPlaybackService.instance ?: return
        if (playbackState.value.isPlaying) service.pause() else service.resume()
    }

    fun next() { MusicPlaybackService.instance?.next() }
    fun previous() { MusicPlaybackService.instance?.previous() }
    fun seek(positionMs: Long) { MusicPlaybackService.instance?.seek(positionMs) }
    fun setPlayMode(mode: PlayMode) { MusicPlaybackService.instance?.setPlayMode(mode) }

    fun cyclePlayMode() {
        val current = playbackState.value.playMode
        val next = when (current) {
            PlayMode.SEQUENCE   -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE    -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SEQUENCE
        }
        setPlayMode(next)
    }

    fun showFullPanel() { _showPanel.value = true }
    fun hideFullPanel() { _showPanel.value = false }

    private suspend fun loadLyrics(song: Song) {
        _lyrics.value = repository.lyricManager?.getAllLines() ?: emptyList()
    }
}
```

---

## 📄 MusicScreen.kt — 完整源码
> 路径：`app/src/main/java/com/lightagent/ui/music/MusicScreen.kt`

```kotlin
package com.lightagent.ui.music

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import com.lightagent.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    viewModel: MusicViewModel = viewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val searchResults  by viewModel.searchResults.collectAsState()
    val searchQuery    by viewModel.searchQuery.collectAsState()
    val lyrics         by viewModel.lyrics.collectAsState()
    val lyricIndex     by viewModel.currentLyricIndex.collectAsState()
    val showPanel      by viewModel.showPanel.collectAsState()

    var searchText by remember(searchQuery) { mutableStateOf(searchQuery) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientStart, GradientMid, GradientEnd)))
    ) {
        // ── 顶部搜索栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(GlassBg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = TextSecondary)
            }
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.weight(1f).height(48.dp),
                placeholder = { Text("搜索歌曲、歌手、风格…", color = TextHint, fontSize = 14.sp) },
                trailingIcon = {
                    if (searchText.isNotBlank()) {
                        IconButton(onClick = { searchText = ""; viewModel.search("") }) {
                            Icon(Icons.Default.Close, "清空", tint = TextSecondary)
                        }
                    } else {
                        IconButton(onClick = { viewModel.search(searchText) }) {
                            Icon(Icons.Default.Search, "搜索", tint = AccentPurple)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPurple
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
            )
        }

        // ── 热门标签 ──
        if (searchResults.isEmpty() && searchText.isBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("🎵 本地音乐", "📁 最近播放", "🎸 纯音乐", "🌙 助眠").forEach { tag ->
                    AssistChip(
                        onClick = { viewModel.search(tag.substringAfter(" ")) },
                        label = { Text(tag, fontSize = 12.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = GlassBg, labelColor = TextSecondary
                        ),
                        border = AssistChipDefaults.assistChipBorder(
                            borderColor = GlassBorder, enabled = true
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── 内容区：搜索无结果 / 空状态 / 歌曲列表 ──
        when {
            searchResults.isEmpty() && searchText.isNotBlank() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, tint = TextSecondary.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("没找到「$searchText」相关的歌曲", color = TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("试试其他关键词，或在 NeriPlayer 中搜索", color = TextHint, fontSize = 12.sp)
                    }
                }
            }
            searchResults.isEmpty() && searchText.isBlank() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MusicNote, null, tint = AccentPurple.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("🎧 天爱星音乐", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("搜一首歌，让我为你播放", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${searchResults.size} 首歌曲", color = TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = { viewModel.playAll(searchResults) }) {
                        Icon(Icons.Default.PlayCircle, null, tint = AccentPurple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("全部播放", color = AccentPurple, fontSize = 13.sp)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (playbackState.currentSong != null) 88.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(searchResults, key = { it.uri.hashCode() }) { song ->
                        SongRow(song = song, isCurrent = song == playbackState.currentSong, isPlaying = playbackState.isPlaying, onClick = { viewModel.playSong(song) })
                    }
                }
            }
        }

        // ── 迷你播放器 ──
        playbackState.currentSong?.let { song ->
            MiniPlayerBar(
                song = song, state = playbackState,
                onPlayPause = { viewModel.togglePlayPause() },
                onNext = { viewModel.next() },
                onTap = { viewModel.showFullPanel() }
            )
        }
    }

    // ── 全屏播放面板 ──
    if (showPanel) {
        Dialog(
            onDismissRequest = { viewModel.hideFullPanel() },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
        ) {
            Box(Modifier.fillMaxSize().background(DeepNavy)) {
                MusicPlayerPanel(
                    state = playbackState, lyrics = lyrics, currentLyricIndex = lyricIndex,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onNext = { viewModel.next() },
                    onPrevious = { viewModel.previous() },
                    onSeek = { viewModel.seek(it) },
                    onModeChange = { viewModel.setPlayMode(it) },
                    onClose = { viewModel.hideFullPanel() }
                )
            }
        }
    }
}

// ── SongRow: 单行歌曲条目 ──
@Composable
private fun SongRow(song: Song, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) AccentPurple.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(GlassBg), contentAlignment = Alignment.Center) {
            if (song.albumArtUri != null) {
                AsyncImage(model = song.albumArtUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.MusicNote, null, tint = if (isCurrent) AccentPurple else TextHint, modifier = Modifier.size(24.dp))
            }
            if (isCurrent && isPlaying) {
                Box(Modifier.fillMaxSize().background(AccentPurple.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = if (isCurrent) AccentPurple else TextPrimary, fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { "未知歌手" }, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, "更多", tint = TextHint, modifier = Modifier.size(20.dp))
        }
    }
}

// ── MiniPlayerBar: 底部迷你播放器 ──
@Composable
private fun MiniPlayerBar(song: Song, state: PlaybackState, onPlayPause: () -> Unit, onNext: () -> Unit, onTap: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onTap),
        color = GlassBg.copy(alpha = 0.95f), tonalElevation = 8.dp, shadowElevation = 4.dp
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(GlassBg), contentAlignment = Alignment.Center) {
                if (song.albumArtUri != null) {
                    AsyncImage(model = song.albumArtUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.MusicNote, null, tint = AccentPurple, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist.ifBlank { "未知歌手" }, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onPlayPause) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "暂停" else "播放", tint = AccentPurple, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, "下一首", tint = TextPrimary, modifier = Modifier.size(28.dp))
            }
            Icon(Icons.Default.KeyboardArrowUp, "展开播放器", tint = TextHint, modifier = Modifier.size(20.dp))
        }
    }
}
```

---

## 🔧 ChatScreen.kt — 修改部分
> 路径：`app/src/main/java/com/lightagent/ui/screen/ChatScreen.kt`
>
> **变更**：Screen 枚举新增 `Music`、新增 `MusicScreen` 和 `MusicViewModel` import、新增 `musicViewModel` 参数、AnimatedContent 新增 Music 路由、ConversationDrawer 新增 `onOpenMusic` 回调。

### 变更 1：枚举扩展 + import

```kotlin
// 新增 import
import com.lightagent.ui.music.MusicScreen
import com.lightagent.ui.music.MusicViewModel

// 枚举新增 Music
private enum class Screen { Chat, Reminder, Settings, Music }
```

### 变更 2：函数签名新增 musicViewModel

```kotlin
@Composable
fun ChatScreen(
    chatViewModel        : ChatViewModel           = viewModel(),
    reminderViewModel    : ReminderViewModel       = viewModel(),
    backgroundViewModel  : BackgroundViewModel     = viewModel(),
    characterPackViewModel: CharacterPackViewModel = viewModel(),
    musicViewModel       : MusicViewModel          = viewModel(),  // ← 新增
    live2DController     : Live2DController        = remember { NoOpLive2DController() }
)
```

### 变更 3：AnimatedContent 新增 Music 路由

```kotlin
when (screen) {
    // ... Reminder / Settings ...

    Screen.Music -> MusicScreen(
        viewModel       = musicViewModel,
        onNavigateBack = { currentScreen = Screen.Chat }
    )

    Screen.Chat -> { /* ... existing code ... */ }
}
```

### 变更 4：侧边栏新增 onOpenMusic 回调

```kotlin
ConversationDrawer(
    // ... existing params ...
    onOpenReminders = { currentScreen = Screen.Reminder; scope.launch { drawerState.close() } },
    onOpenSettings  = { currentScreen = Screen.Settings;  scope.launch { drawerState.close() } },
    onOpenMusic     = { currentScreen = Screen.Music;     scope.launch { drawerState.close() } },  // ← 新增
)
```

---

## 🔧 ConversationDrawer.kt — 修改部分
> 路径：`app/src/main/java/com/lightagent/ui/screen/ConversationDrawer.kt`
>
> **变更**：新增 `MusicNote` import、函数签名新增 `onOpenMusic` 参数、底部工具区新增音乐入口。

### 变更 1：新增 import

```kotlin
import androidx.compose.material.icons.filled.MusicNote
```

### 变更 2：函数签名

```kotlin
@Composable
fun ConversationDrawer(
    conversations         : List<ConversationEntity>,
    currentConversationId : String?,
    onSelectConversation  : (ConversationEntity) -> Unit,
    onNewConversation     : () -> Unit,
    onDeleteConversation  : (ConversationEntity) -> Unit,
    onOpenReminders       : () -> Unit,
    onOpenSettings        : () -> Unit,
    onOpenMusic           : () -> Unit,       // ← 新增
    modifier              : Modifier = Modifier
)
```

### 变更 3：底部工具区新增音乐入口

```kotlin
DrawerBottomItem(
    icon    = Icons.Default.MusicNote,
    label   = "音乐播放器",
    onClick = onOpenMusic,
    tint    = AccentPurple
)
```

---

## 🔧 build.gradle — 版本号
> 路径：`app/build.gradle`

```groovy
defaultConfig {
    applicationId "com.lightagent"
    minSdk 26
    targetSdk 34
    versionCode 3
    versionName "4.4.1"
}
```

---

## 📐 架构全景

```
用户说「放首周杰伦」
  ↓
PlannerAgent (解析意图 → MusicTool)
  ↓
MusicTool.execute(action="play", query="周杰伦")
  ↓
MusicRepository.playSearch("周杰伦")
  ↓
MusicPlaybackService (MediaPlayer 播放 + MediaSession + 通知栏)
  ↓
MusicViewModel (StateFlow 状态 + 歌词索引计算)
  ↓
MusicScreen / MusicPlayerPanel / MusicOverlayCard (Compose UI)

侧边栏入口：
ConversationDrawer → onOpenMusic → Screen.Music → MusicScreen
```

---

## 📊 技术要点

| 要点 | 说明 |
|------|------|
| **ViewModel 模式** | AndroidViewModel 直接获取 Application Context，内部创建 MusicRepository |
| **导航方案** | 沿用 ChatScreen 的 `private enum class Screen` + `AnimatedContent` 路由（非 Android Navigation） |
| **状态共享** | ChatScreen 和 MusicScreen 通过显式传入 `musicViewModel` 共享同一实例 |
| **歌词索引** | `combine(playbackState, lyrics)` 实时计算当前行，二分查找 O(log n) |
| **迷你播放器** | 底部 72dp Surface 玻璃态栏，点击 → `showFullPanel()` 展开全屏 Dialog |
| **全屏播放面板** | `Dialog(usePlatformDefaultWidth = false)` 包裹 `MusicPlayerPanel`，支持返回键关闭 |

---

天爱星 · 2026.06.25 · v4.4.1
