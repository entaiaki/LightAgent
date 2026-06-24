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
 * 后台音乐播放 Service（前台服务）
 *
 * 职责：
 * - MediaPlayer 生命周期管理
 * - MediaSession 注册（耳机按键/通知栏）
 * - 前台通知保持 Service 存活
 * - StateFlow 暴露播放状态给 UI
 */
class MusicPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID   = "tianaixing_music"
        const val NOTIFICATION_ID = 4242
        const val ACTION_PLAY  = "com.lightagent.music.PLAY"
        const val ACTION_PAUSE = "com.lightagent.music.PAUSE"
        const val ACTION_NEXT  = "com.lightagent.music.NEXT"
        const val ACTION_PREV  = "com.lightagent.music.PREV"
        const val ACTION_STOP  = "com.lightagent.music.STOP"

        /** 全局单例引用（供 MusicRepository 使用） */
        @Volatile
        var instance: MusicPlaybackService? = null
            private set
    }

    // ── 播放器 ─────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private val lyricManager = LyricManager()
    private lateinit var audioManager: AudioManager

    // ── 音频焦点 ───────────────────────────────────────────
    private var hasAudioFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久失去焦点（如其他 App 开始播放）
                pause()
                hasAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 暂时失去焦点（如 TTS 播报、通知音）
                if (_state.value.isPlaying) pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂失去焦点但可降低音量（如导航提示）
                mediaPlayer?.setVolume(0.3f, 0.3f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 重新获得焦点
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

    // ── 状态流 ─────────────────────────────────────────────
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // ── 播放列表 ───────────────────────────────────────────
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
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
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

    // ═══════════════════════════════════════════════════════
    //  MediaPlayer 监听
    // ═══════════════════════════════════════════════════════
    private fun MediaPlayer.setupListeners() {
        setOnPreparedListener { mp ->
            if (!requestAudioFocus()) {
                android.util.Log.w("MusicService", "无法获取音频焦点")
            }
            mp.start()
            updateState { it.copy(isPlaying = true) }
            updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification())
            // 进度更新协程
            scope.launch {
                while (isActive && mp.isPlaying) {
                    updateState { s ->
                        s.copy(progress = mp.currentPosition.toLong(), duration = mp.duration.toLong())
                    }
                    delay(250L)
                }
            }
        }

        setOnCompletionListener {
            handleCompletion()
        }

        setOnErrorListener { _, what, extra ->
            android.util.Log.e("MusicService", "MediaPlayer error: what=$what extra=$extra")
            false
        }
    }

    private fun handleCompletion() {
        val mode = _state.value.playMode
        when (mode) {
            PlayMode.REPEAT_ONE -> {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
            }
            PlayMode.REPEAT_ALL, PlayMode.SEQUENCE -> {
                if (playlist.isNotEmpty()) {
                    val nextIdx = if (mode == PlayMode.SEQUENCE) {
                        (currentIndex + 1) % playlist.size
                    } else {
                        currentIndex + 1
                    }
                    if (nextIdx < playlist.size) {
                        playAtIndex(nextIdx)
                    } else {
                        stop()
                    }
                }
            }
            PlayMode.SHUFFLE -> {
                if (playlist.isNotEmpty()) {
                    playAtIndex((0 until playlist.size).random())
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  MediaSession
    // ═══════════════════════════════════════════════════════
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "TianAiXingMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()  { resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { seek(pos) }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionPlaybackState(state: Int) {
        val pb = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, _state.value.progress, 1.0f)
            .build()
        mediaSession.setPlaybackState(pb)
    }

    // ═══════════════════════════════════════════════════════
    //  公开控制方法
    // ═══════════════════════════════════════════════════════

    /** 设置播放列表并从指定索引开始播放 */
    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlist.clear()
        playlist.addAll(songs)
        if (startIndex in playlist.indices) {
            playAtIndex(startIndex)
        }
    }

    /** 获取当前播放列表 */
    fun getPlaylist(): List<Song> = playlist.toList()

    /** 追加到播放列表 */
    fun appendToPlaylist(songs: List<Song>) {
        playlist.addAll(songs)
    }

    /** 播放指定索引 */
    fun playAtIndex(index: Int) {
        if (index !in playlist.indices) return
        currentIndex = index
        val song = playlist[index]
        play(song)
    }

    /** 播放单曲 */
    fun play(song: Song) {
        try {
            val mp = mediaPlayer ?: return
            mp.reset()
            mp.setDataSource(applicationContext, song.uri)
            mp.prepareAsync()
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
        updateNotification(forceForeground = false)
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
        mediaPlayer?.apply {
            if (isPlaying) stop()
            reset()
        }
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

    fun setPlayMode(mode: PlayMode) {
        updateState { it.copy(playMode = mode) }
    }

    /** 获取歌词管理器 */
    fun getLyricManager(): LyricManager = lyricManager

    // ═══════════════════════════════════════════════════════
    //  通知
    // ═══════════════════════════════════════════════════════
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "天爱星音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台音乐播放控制"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val song = _state.value.currentSong
        val playPauseAction = if (_state.value.isPlaying) ACTION_PAUSE else ACTION_PLAY

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song?.title ?: "未在播放")
            .setContentText(song?.artist ?: "")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(_state.value.isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "上一首", pendingIntent(ACTION_PREV))
            .addAction(
                if (_state.value.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (_state.value.isPlaying) "暂停" else "播放",
                pendingIntent(playPauseAction)
            )
            .addAction(android.R.drawable.ic_media_next, "下一首", pendingIntent(ACTION_NEXT))
            .addAction(android.R.drawable.ic_delete, "关闭", pendingIntent(ACTION_STOP))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification(forceForeground: Boolean = false) {
        val nm = NotificationManagerCompat.from(this)
        nm.notify(NOTIFICATION_ID, buildNotification())
        if (forceForeground) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun pendingIntent(action: String): PendingIntent {
        val intent = Intent(action).apply { setPackage(packageName) }
        return PendingIntent.getBroadcast(this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handleAction(intent: Intent) {
        when (intent.action) {
            ACTION_PLAY  -> resume()
            ACTION_PAUSE -> pause()
            ACTION_NEXT  -> next()
            ACTION_PREV  -> previous()
            ACTION_STOP  -> stop()
        }
    }

    // ── 噪声接收器（耳机拔出自动暂停）──────────────────
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleAction(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        abandonAudioFocus()
        instance = null
        scope.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.isActive = false
        mediaSession.release()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── 内部辅助 ──────────────────────────────────────────
    private inline fun updateState(transform: (PlaybackState) -> PlaybackState) {
        _state.update(transform)
    }
}
