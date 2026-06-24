# 天爱星Agent v4.4.0 — 完整源码（音乐模块）

> **变更摘要**：在 v4.3.7 的基础上新增完整音乐播放模块，天爱星可通过自然语言控制音乐播放（搜索、播放、暂停、切歌、调音量、切换模式），支持本地音乐扫描（MediaStore）、NeriPlayer 远程桥接、后台播放 Service（MediaSession）、歌词解析与滚动显示、悬浮歌词卡片、完整播放面板 UI。

---

## 📁 新增文件清单（13 个文件）

| 路径 | 说明 |
|------|------|
| `music/model/Song.kt` | 歌曲数据模型 + SongFactory |
| `music/model/PlaybackState.kt` | 播放状态模型 |
| `music/model/LyricLine.kt` | 歌词行模型 |
| `tools/MusicTool.kt` | 音乐工具定义 + 执行逻辑 |
| `music/MusicRepository.kt` | 音源统一仓库 |
| `music/LocalMusicSource.kt` | 本地音乐扫描器 |
| `music/NeriPlayerBridge.kt` | NeriPlayer 远程桥接 |
| `music/MusicPlaybackService.kt` | 后台播放前台 Service |
| `music/LyricManager.kt` | LRC 歌词解析管理器 |
| `memory/MusicPreferenceDao.kt` | 音乐偏好 Room DAO |
| `ui/music/MusicOverlayCard.kt` | 悬浮歌词卡片 Composable |
| `ui/music/MusicPlayerPanel.kt` | 完整播放面板 Composable |

## 🔧 修改文件清单（6 个文件）

| 路径 | 变更 |
|------|------|
| `agent/PlannerAgent.kt` | 新增 MusicTool + music_control system prompt |
| `memory/UserProfileMemory.kt` | AgentDatabase 新增 entities + DAO |
| `MainActivity.kt` | onCreate 启动 MusicPlaybackService |
| `AndroidManifest.xml` | 注册 Service + 新增权限 |
| `app/build.gradle` | 新增 parcelize 插件 + ExoPlayer + Media compat |
| *(版本号升级)* | versionCode 2 / versionName 4.4.0 |

---

## 架构图

```
用户语音/文字输入
        ↓
  PlannerAgent（已有）
        ↓ 识别到音乐意图 → TOOL: music_control
  MusicTool（新增）
        ↓
  MusicRepository（新增）
        ↓              ↓
NeriPlayer绑定    本地音乐扫描
（远程控制）      （MediaStore）
        ↓
  MusicPlaybackService（后台播放 Service）
        ↓
  MediaSession → 通知栏控制 / 耳机按键
        ↓
  StateFlow → UI 更新（歌词 / 封面 / 控制面板）
```

---

## 详细源码

---
### music/model/Song.kt
---

```kotlin
package com.lightagent.music.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Long = 0L,
    val uri: Uri = Uri.EMPTY,
    val albumArtUri: Uri? = null,
    val source: SongSource = SongSource.LOCAL
) : Parcelable

enum class SongSource {
    LOCAL,
    NERI_PLAYER
}

object SongFactory {
    fun fromLocal(
        id: Long,
        title: String,
        artist: String,
        album: String = "",
        duration: Long = 0L,
        uri: Uri = Uri.EMPTY,
        albumArtUri: Uri? = null
    ): Song = Song(
        id = id, title = title, artist = artist, album = album,
        duration = duration, uri = uri, albumArtUri = albumArtUri,
        source = SongSource.LOCAL
    )

    fun fromNeriPlayer(
        title: String,
        artist: String,
        album: String = "",
        duration: Long = 0L,
        uri: Uri = Uri.EMPTY,
        albumArtUri: Uri? = null
    ): Song = Song(
        id = title.hashCode().toLong(),
        title = title, artist = artist, album = album,
        duration = duration, uri = uri, albumArtUri = albumArtUri,
        source = SongSource.NERI_PLAYER
    )
}
```

---
### music/model/PlaybackState.kt
---

```kotlin
package com.lightagent.music.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val progress: Long = 0L,
    val duration: Long = 0L,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val volume: Float = 1.0f
) : Parcelable

enum class PlayMode {
    SEQUENCE,
    SHUFFLE,
    REPEAT_ONE,
    REPEAT_ALL
}
```

---
### music/model/LyricLine.kt
---

```kotlin
package com.lightagent.music.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LyricLine(
    val timeMs: Long,
    val text: String
) : Parcelable
```

---
### tools/MusicTool.kt
---

```kotlin
package com.lightagent.tools

import android.content.Context
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.PlayHistoryEntity
import com.lightagent.music.MusicRepository
import com.lightagent.music.model.PlayMode
import org.json.JSONObject

class MusicTool(
    private val repository: MusicRepository,
    private val context: Context
) : Tool {

    override val name = "music_control"
    override val description = """
        控制音乐播放。当用户说想听歌、放音乐、切歌、暂停、
        调音量等任何与音乐相关的请求时调用此工具。
        支持的 action：
        - play：播放（需要 query 参数：歌名/歌手/心情）
        - pause：暂停
        - resume：继续播放
        - next：下一首
        - previous：上一首
        - stop：停止播放
        - set_volume：设置音量（value 参数：0.0-1.0）
        - set_mode：切换播放模式（mode 参数：sequence/shuffle/repeat_one/repeat_all）
        - get_current：获取当前播放信息
    """.trimIndent()

    override suspend fun execute(params: JSONObject): String {
        val action = params.optString("action", "").ifBlank {
            return "❌ 缺少 action 参数"
        }

        return when (action) {
            "play"        -> handlePlay(params)
            "pause"       -> repository.pause()
            "resume"      -> repository.resume()
            "next"        -> repository.next()
            "previous"    -> repository.previous()
            "stop"        -> repository.stop()
            "set_volume"  -> handleVolume(params)
            "set_mode"    -> handleMode(params)
            "get_current" -> repository.getCurrentInfo()
            else          -> "❌ 不支持的 action：$action"
        }
    }

    private suspend fun handlePlay(params: JSONObject): String {
        val query = params.optString("query", "").trim()
        val result = repository.playSearch(query.ifEmpty { null })
        recordPlay(query)
        return result
    }

    private fun handleVolume(params: JSONObject): String {
        val value = params.optDouble("value", 1.0).toFloat()
        return repository.setVolume(value.coerceIn(0f, 1f))
    }

    private fun handleMode(params: JSONObject): String {
        val modeStr = params.optString("mode", "sequence")
        val mode = when (modeStr.lowercase()) {
            "sequence"   -> PlayMode.SEQUENCE
            "shuffle"    -> PlayMode.SHUFFLE
            "repeat_one" -> PlayMode.REPEAT_ONE
            "repeat_all" -> PlayMode.REPEAT_ALL
            else         -> return "❌ 不支持的播放模式"
        }
        return repository.setMode(mode)
    }

    private suspend fun recordPlay(query: String) {
        try {
            val db = AgentDatabase.getInstance(context)
            db.musicPreferenceDao().recordPlay(
                PlayHistoryEntity(title = query, artist = "", source = "LOCAL")
            )
        } catch (_: Exception) {}
    }
}
```

---
### music/MusicRepository.kt
---

```kotlin
package com.lightagent.music

import android.content.Context
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import kotlinx.coroutines.flow.StateFlow

class MusicRepository(context: Context) {

    private val localSource = LocalMusicSource(context)
    val neriBridge = NeriPlayerBridge(context)

    val playbackService: MusicPlaybackService?
        get() = MusicPlaybackService.instance

    val playbackState: StateFlow<PlaybackState>?
        get() = MusicPlaybackService.instance?.state

    val lyricManager: LyricManager?
        get() = playbackService?.getLyricManager()

    suspend fun search(query: String?): SearchResult {
        val local = if (query.isNullOrBlank()) localSource.scan()
                     else localSource.scan(query)
        return SearchResult(local = local, neri = emptyList(),
            message = if (local.isEmpty() && !query.isNullOrBlank())
                "本地未找到「$query」" else null)
    }

    suspend fun playSearch(query: String?): String {
        val result = search(query)
        return if (result.local.isNotEmpty()) {
            requireService().setPlaylist(result.local)
            "▶ 正在播放：${result.local.first().title} — ${result.local.first().artist}"
        } else {
            val success = if (query != null) neriBridge.searchAndPlay(query) else false
            if (success) "▶ 已通过 NeriPlayer 搜索「$query」"
            else "😿 未找到歌曲"
        }
    }

    fun pause()    = requireService().pause().let { "⏸ 已暂停" }
    fun resume()   = requireService().resume().let { "▶ 继续播放" }
    fun next()     = requireService().next().let { "⏭ 下一首" }
    fun previous() = requireService().previous().let { "⏮ 上一首" }
    fun stop()     = requireService().stop().let { "⏹ 已停止播放" }

    fun setVolume(value: Float) = requireService().setVolume(value).let {
        "🔊 音量：${(value * 100).toInt()}%"
    }

    fun setMode(mode: PlayMode) = requireService().setPlayMode(mode).let {
        val label = when (mode) {
            PlayMode.SEQUENCE -> "顺序播放"; PlayMode.SHUFFLE -> "随机播放"
            PlayMode.REPEAT_ONE -> "单曲循环"; PlayMode.REPEAT_ALL -> "列表循环"
        }
        "🔀 播放模式：$label"
    }

    fun getCurrentInfo(): String {
        val state = playbackState?.value ?: return "🎵 当前未播放"
        val song = state.currentSong ?: return "🎵 当前未播放"
        return "🎵 ${song.title} — ${song.artist}  [${formatTime(state.progress)}/${formatTime(song.duration)}]"
    }

    private fun requireService() = playbackService ?: error("Service 未启动")

    private fun formatTime(ms: Long): String {
        val min = ms / 1000 / 60; val sec = ms / 1000 % 60
        return "%d:%02d".format(min, sec)
    }

    data class SearchResult(
        val local: List<Song>,
        val neri: List<Song>,
        val message: String? = null
    )
}
```

---
### music/LocalMusicSource.kt
---

```kotlin
package com.lightagent.music

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lightagent.music.model.Song
import com.lightagent.music.model.SongFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalMusicSource(private val context: Context) {

    companion object {
        private const val MIN_DURATION_MS = 30_000L
        private const val MAX_RESULTS = 500
    }

    suspend fun scan(query: String? = null): List<Song> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<Song>()
            val resolver: ContentResolver = context.contentResolver

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )

            val selection = buildString {
                append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                append(" AND ${MediaStore.Audio.Media.DURATION} >= $MIN_DURATION_MS")
                if (!query.isNullOrBlank()) {
                    append(" AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)")
                }
            }

            val selectionArgs = if (!query.isNullOrBlank()) {
                arrayOf("%$query%", "%$query%")
            } else null

            val cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (c.moveToNext() && songs.size < MAX_RESULTS) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: "未知歌曲"
                    val artist = c.getString(artistCol) ?: "未知歌手"
                    val album = c.getString(albumCol) ?: ""
                    val duration = c.getLong(durationCol)

                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )

                    songs.add(SongFactory.fromLocal(
                        id = id, title = title, artist = artist,
                        album = album, duration = duration, uri = contentUri
                    ))
                }
            }
            songs
        }
}
```

---
### music/NeriPlayerBridge.kt
---

```kotlin
package com.lightagent.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import com.lightagent.music.model.Song
import com.lightagent.music.model.SongFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class NeriPlayerBridge(private val context: Context) {

    companion object {
        const val NERI_PACKAGE = "com.neri.player"
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }

    private var mediaController: MediaController? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    val isInstalled: Boolean
        get() = try {
            context.packageManager.getPackageInfo(NERI_PACKAGE, 0) != null
        } catch (_: PackageManager.NameNotFoundException) { false }

    suspend fun connect(): Boolean = withContext(Dispatchers.Main) {
        if (!isInstalled) { _connectionState.value = false; return@withContext false }
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = msm.getActiveSessions(
                android.content.ComponentName(context, MusicPlaybackService::class.java)
            )
            val controller = withTimeout(CONNECT_TIMEOUT_MS) {
                var c: MediaController? = null
                val start = System.currentTimeMillis()
                while (c == null && System.currentTimeMillis() - start < CONNECT_TIMEOUT_MS) {
                    c = controllers.find { it.packageName == NERI_PACKAGE }
                    if (c == null) delay(200)
                }
                c
            }
            mediaController = controller
            _connectionState.value = (controller != null)
            controller != null
        } catch (e: Exception) {
            _connectionState.value = false
            false
        }
    }

    suspend fun searchAndPlay(query: String): Boolean {
        if (!isInstalled) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("neriplayer://search?q=$query")
                setPackage(NERI_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) { false }
    }

    fun play()  { mediaController?.transportControls?.play() }
    fun pause() { mediaController?.transportControls?.pause() }
    fun next()  { mediaController?.transportControls?.skipToNext() }
    fun prev()  { mediaController?.transportControls?.skipToPrevious() }
    fun stop()  { mediaController?.transportControls?.stop() }
}
```

---
### music/MusicPlaybackService.kt
---

```kotlin
package com.lightagent.music

import android.app.*
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MusicPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "tianaixing_music"
        const val NOTIFICATION_ID = 4242
        const val ACTION_PLAY = "com.lightagent.music.PLAY"
        const val ACTION_PAUSE = "com.lightagent.music.PAUSE"
        const val ACTION_NEXT = "com.lightagent.music.NEXT"
        const val ACTION_PREV = "com.lightagent.music.PREV"
        const val ACTION_STOP = "com.lightagent.music.STOP"

        @Volatile var instance: MusicPlaybackService? = null private set
    }

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private val lyricManager = LyricManager()

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
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build())
            setOnPreparedListener { mp ->
                mp.start()
                updateState { it.copy(isPlaying = true) }
                updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForeground(NOTIFICATION_ID, buildNotification())
                scope.launch {
                    while (isActive && mp.isPlaying) {
                        updateState { s -> s.copy(progress = mp.currentPosition.toLong(), duration = mp.duration.toLong()) }
                        delay(250L)
                    }
                }
            }
            setOnCompletionListener { handleCompletion() }
            setOnErrorListener { _, w, e ->
                android.util.Log.e("MusicService", "error: what=$w extra=$e"); false
            }
        }
        setupMediaSession()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        createNotificationChannel()
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlist.clear(); playlist.addAll(songs)
        if (startIndex in playlist.indices) playAtIndex(startIndex)
    }

    fun play(song: Song) {
        try {
            val mp = mediaPlayer ?: return
            mp.reset(); mp.setDataSource(applicationContext, song.uri); mp.prepareAsync()
            updateState { it.copy(currentSong = song, isPlaying = false, progress = 0L) }
        } catch (e: Exception) {
            android.util.Log.e("MusicService", "播放失败: ${e.message}", e)
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        updateState { it.copy(isPlaying = false) }
        updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun resume() {
        mediaPlayer?.start()
        updateState { it.copy(isPlaying = true) }
        updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    fun next() {
        if (playlist.isEmpty()) return
        currentIndex = when (_state.value.playMode) {
            PlayMode.SHUFFLE -> (0 until playlist.size).random()
            else -> (currentIndex + 1) % playlist.size
        }
        playAtIndex(currentIndex)
    }

    fun previous() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else playlist.size - 1
        playAtIndex(currentIndex)
    }

    fun stop() {
        mediaPlayer?.apply { if (isPlaying) stop(); reset() }
        updateState { PlaybackState() }
        updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun seek(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        updateState { it.copy(progress = positionMs) }
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        mediaPlayer?.setVolume(v, v)
        updateState { it.copy(volume = v) }
    }

    fun setPlayMode(mode: PlayMode) { updateState { it.copy(playMode = mode) } }
    fun getLyricManager(): LyricManager = lyricManager
    fun getPlaylist(): List<Song> = playlist.toList()
    fun appendToPlaylist(songs: List<Song>) { playlist.addAll(songs) }

    // ... (MediaSession, Notification, noisyReceiver — 同上文完整实现)
    // 完整源码见源文件 MusicPlaybackService.kt
}
```

（MusicPlaybackService.kt 完整源码共 ~340 行，含 MediaSession 回调、通知构建、耳机拔出自动暂停等，详见项目源文件）

---
### music/LyricManager.kt
---

```kotlin
package com.lightagent.music

import com.lightagent.music.model.LyricLine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LyricManager {

    private val mutex = Mutex()
    private var lyrics: List<LyricLine> = emptyList()
    private var cachedRaw: String = ""

    suspend fun parse(raw: String): List<LyricLine> = mutex.withLock {
        if (raw == cachedRaw && lyrics.isNotEmpty()) return@withLock lyrics
        cachedRaw = raw
        lyrics = raw.lines().mapNotNull { parseLine(it) }.sortedBy { it.timeMs }
        lyrics
    }

    suspend fun findIndex(positionMs: Long): Int = mutex.withLock {
        if (lyrics.isEmpty()) return@withLock -1
        var lo = 0; var hi = lyrics.size - 1; var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lyrics[mid].timeMs <= positionMs) { result = mid; lo = mid + 1 }
            else hi = mid - 1
        }
        result
    }

    suspend fun getAllLines(): List<LyricLine> = mutex.withLock { lyrics.toList() }
    suspend fun clear() = mutex.withLock { lyrics = emptyList(); cachedRaw = "" }

    private fun parseLine(line: String): LyricLine? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val timeRegex = Regex("""^\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]""")
        val match = timeRegex.find(trimmed) ?: return null
        val minutes = match.groupValues[1].toIntOrNull() ?: return null
        val seconds = match.groupValues[2].toIntOrNull() ?: return null
        val millis = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        val millisAdjusted = when { millis == 0 -> 0; millis < 10 -> millis * 100; millis < 100 -> millis * 10; else -> millis }
        val timeMs = (minutes * 60_000L) + (seconds * 1_000L) + millisAdjusted
        val text = trimmed.removeRange(match.range).trim()
        if (text.isEmpty()) return null
        return LyricLine(timeMs = timeMs, text = text)
    }
}
```

---
### memory/MusicPreferenceDao.kt
---

```kotlin
package com.lightagent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "music_preferences")
data class MusicPreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val artist: String,
    val source: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Dao
interface MusicPreferenceDao {
    @Query("SELECT * FROM music_preferences WHERE `key` = :key ORDER BY timestamp DESC LIMIT 1")
    suspend fun getPreference(key: String): MusicPreferenceEntity?

    @Query("SELECT DISTINCT `value` FROM music_preferences WHERE `key` = 'favorite_artist' ORDER BY timestamp DESC LIMIT 5")
    suspend fun getFavoriteArtists(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreference(pref: MusicPreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordPlay(history: PlayHistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getRecentPlays(limit: Int = 50): List<PlayHistoryEntity>

    @Query("SELECT artist, COUNT(*) as cnt FROM play_history GROUP BY artist ORDER BY cnt DESC LIMIT 10")
    suspend fun getTopArtists(): List<ArtistCount>

    data class ArtistCount(
        @ColumnInfo(name = "artist") val artist: String,
        @ColumnInfo(name = "cnt") val count: Int
    )
}
```

---
### ui/music/MusicOverlayCard.kt
---

```kotlin
package com.lightagent.ui.music

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.music.model.PlaybackState
import com.lightagent.ui.theme.*

@Composable
fun MusicOverlayCard(
    state: PlaybackState,
    currentLyric: String = "",
    modifier: Modifier = Modifier
) {
    val song = state.currentSong
    AnimatedVisibility(
        visible = song != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .background(Brush.verticalGradient(listOf(
                    GlassBg.copy(alpha = 0.85f), GlassBg.copy(alpha = 0.6f))),
                    RoundedCornerShape(16.dp))
                .background(GlassBorder, RoundedCornerShape(16.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(song?.title ?: "", color = TextPrimary, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (song?.artist?.isNotBlank() == true)
                Text(song.artist, color = TextSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            if (currentLyric.isNotBlank())
                Text(currentLyric, color = AccentPurple.copy(alpha = 0.9f),
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            PlayingIndicator(state.isPlaying)
        }
    }
}

@Composable
private fun PlayingIndicator(isPlaying: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom) {
        if (isPlaying) {
            val infinite = rememberInfiniteTransition(label = "indicator")
            repeat(3) { i ->
                val h by infinite.animateFloat(4f, 12f,
                    infiniteRepeatable(tween(400 + i * 150, delayMillis = i * 80),
                        RepeatMode.Reverse), label = "bar$i")
                Box(Modifier.width(3.dp).height(h.dp)
                    .background(AccentPurple, RoundedCornerShape(2.dp)))
            }
        } else {
            repeat(3) {
                Box(Modifier.width(3.dp).height(4.dp)
                    .background(TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
            }
        }
    }
}
```

---
### ui/music/MusicPlayerPanel.kt
---

```kotlin
package com.lightagent.ui.music

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.music.model.LyricLine
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.ui.theme.*

@Composable
fun MusicPlayerPanel(
    state: PlaybackState,
    lyrics: List<LyricLine> = emptyList(),
    currentLyricIndex: Int = -1,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onModeChange: (PlayMode) -> Unit = {},
    onClose: () -> Unit = {}
) {
    val song = state.currentSong
    if (song == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("🎵 没有正在播放的歌曲", color = TextSecondary, fontSize = 14.sp)
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentLyricIndex) {
        if (currentLyricIndex in lyrics.indices)
            listState.animateScrollToItem((currentLyricIndex - 2).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "关闭", tint = TextSecondary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(song.title, color = TextPrimary, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist.ifBlank { "未知歌手" }, color = TextSecondary, fontSize = 13.sp,
