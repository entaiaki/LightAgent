package com.lightagent.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import com.lightagent.music.model.Song
import com.lightagent.music.model.SongFactory
import com.lightagent.music.model.SongSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * NeriPlayer 远程控制桥接
 *
 * 通过 MediaSession 绑定 NeriPlayer，实现：
 * - 远程搜索/播放
 * - 播放状态同步
 * - 当前播放歌曲信息获取
 *
 * NeriPlayer 未安装时自动降级，不崩溃。
 */
class NeriPlayerBridge(private val context: Context) {

    companion object {
        /** NeriPlayer 的包名 */
        const val NERI_PACKAGE = "com.neri.player"  // 根据实际安装修改

        /** MediaSession token 连接超时（毫秒） */
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }

    private var mediaController: MediaController? = null
    private var isConnected = false

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    /** 检查 NeriPlayer 是否已安装 */
    val isInstalled: Boolean
        get() = try {
            context.packageManager.getPackageInfo(NERI_PACKAGE, 0) != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * 尝试连接到 NeriPlayer 的 MediaSession
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.Main) {
        if (!isInstalled) {
            _connectionState.value = false
            return@withContext false
        }

        try {
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = mediaSessionManager.getActiveSessions(
                android.content.ComponentName(context, MusicPlaybackService::class.java)
            )

            val neriController = withTimeout(CONNECT_TIMEOUT_MS) {
                var c: MediaController? = null
                // 在给定时间内每 200ms 轮询一次
                val start = System.currentTimeMillis()
                while (c == null && System.currentTimeMillis() - start < CONNECT_TIMEOUT_MS) {
                    c = controllers.find { it.packageName == NERI_PACKAGE }
                    if (c == null) delay(200)
                }
                c
            }

            mediaController = neriController
            isConnected = (neriController != null)
            _connectionState.value = isConnected
            isConnected
        } catch (e: Exception) {
            android.util.Log.w("NeriBridge", "连接失败: ${e.message}")
            _connectionState.value = false
            false
        }
    }

    /** 断开连接 */
    fun disconnect() {
        mediaController = null
        isConnected = false
        _connectionState.value = false
    }

    /** 在 NeriPlayer 中搜索并播放 */
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
        } catch (e: Exception) {
            android.util.Log.w("NeriBridge", "搜索调用失败: ${e.message}")
            false
        }
    }

    // ── 播放控制（通过 MediaController） ──────────────────
    fun play()  { mediaController?.transportControls?.play() }
    fun pause() { mediaController?.transportControls?.pause() }
    fun next()  { mediaController?.transportControls?.skipToNext() }
    fun prev()  { mediaController?.transportControls?.skipToPrevious() }
    fun stop()  { mediaController?.transportControls?.stop() }

    /**
     * 获取当前 NeriPlayer 正在播放的歌曲信息
     */
    fun getNeriCurrentSong(): Song? {
        val metadata = mediaController?.metadata ?: return null
        return SongFactory.fromNeriPlayer(
            title  = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "未知",
            artist  = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "未知",
            album   = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        )
    }
}
