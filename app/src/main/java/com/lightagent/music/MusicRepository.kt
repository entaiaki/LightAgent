package com.lightagent.music

import android.content.Context
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 音乐仓库 — 音源统一接口
 *
 * 聚合本地音乐（LocalMusicSource）与远程控制（NeriPlayerBridge），
 * 对上层（MusicTool）提供统一的操作入口。
 *
 * 职责：
 * - 搜索/播放歌曲（优先本地，降级到 NeriPlayer）
 * - 管理 MusicPlaybackService 生命周期
 * - 提供统一的 StateFlow 播放状态
 */
class MusicRepository(context: Context) {

    private val localSource = LocalMusicSource(context)
    val neriBridge = NeriPlayerBridge(context)

    // Service 的引用通过静态实例获取
    val playbackService: MusicPlaybackService?
        get() = MusicPlaybackService.instance

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 当前播放状态（直接委托给 Service 的 StateFlow） */
    val playbackState: StateFlow<PlaybackState>?
        get() = MusicPlaybackService.instance?.state

    /** 歌词管理器（直接委托给 Service） */
    val lyricManager: LyricManager?
        get() = playbackService?.getLyricManager()

    // ═════════════════════════════════════════════════════
    //  搜索
    // �════════════════════════════════════════════════════

    /**
     * 统一搜索：本地 → NeriPlayer 降级
     *
     * @param query 搜索关键词（歌名/歌手/风格/心情）
     * @return 搜索到的歌曲列表 + 来源标记
     */
    suspend fun search(query: String?): SearchResult {
        if (query.isNullOrBlank()) {
            return SearchResult(local = localSource.scan(), neri = emptyList())
        }

        // 并行搜索本地和 NeriPlayer（尝试，失败则忽略）
        val local = localSource.scan(query)

        return SearchResult(
            local = local,
            neri = emptyList(), // NeriPlayer 需要特殊搜索 API，此处预留
            message = if (local.isEmpty()) "本地未找到「$query」\n可在 NeriPlayer 中搜索" else null
        )
    }

    // ═════════════════════════════════════════════════════
    //  播放控制
    // ═════════════════════════════════════════════════════

    /** 搜索并播放第一首歌 */
    suspend fun playSearch(query: String?): String {
        val result = search(query)
        val songs = result.local

        return if (songs.isNotEmpty()) {
            val service = requireService()
            service.setPlaylist(songs)
            "▶ 正在播放：${songs.first().title} — ${songs.first().artist}"
        } else if (query != null) {
            // 降级：尝试 NeriPlayer
            val success = neriBridge.searchAndPlay(query)
            if (success) "▶ 已通过 NeriPlayer 搜索「$query」"
            else "😿 未找到「$query」的歌曲，请确认本地有音乐文件或 NeriPlayer 已安装"
        } else {
            "😿 未找到歌曲"
        }
    }

    fun pause(): String {
        val s = requireService()
        s.pause()
        return "⏸ 已暂停"
    }

    fun resume(): String {
        val s = requireService()
        s.resume()
        return "▶ 继续播放"
    }

    fun next(): String {
        val s = requireService()
        s.next()
        val song = s.state.value.currentSong
        return "⏭ 下一首" + (song?.let { "：${it.title} — ${it.artist}" } ?: "")
    }

    fun previous(): String {
        val s = requireService()
        s.previous()
        val song = s.state.value.currentSong
        return "⏮ 上一首" + (song?.let { "：${it.title} — ${it.artist}" } ?: "")
    }

    fun stop(): String {
        val s = requireService()
        s.stop()
        return "⏹ 已停止播放"
    }

    fun setVolume(value: Float): String {
        val s = requireService()
        s.setVolume(value)
        return "🔊 音量：${(value * 100).toInt()}%"
    }

    fun setMode(mode: PlayMode): String {
        val s = requireService()
        s.setPlayMode(mode)
        val label = when (mode) {
            PlayMode.SEQUENCE   -> "顺序播放"
            PlayMode.SHUFFLE     -> "随机播放"
            PlayMode.REPEAT_ONE  -> "单曲循环"
            PlayMode.REPEAT_ALL  -> "列表循环"
        }
        return "🔀 播放模式：$label"
    }

    fun getCurrentInfo(): String {
        val s = playbackService
        if (s == null) return "🎵 当前未播放"
        val state = s.state.value
        val song = state.currentSong
        return if (song != null) {
            "🎵 ${song.title} — ${song.artist}  [${formatTime(state.progress)}/${formatTime(song.duration)}]"
        } else {
            "🎵 当前未播放"
        }
    }

    fun getPlaylist(): List<Song> = playbackService?.getPlaylist() ?: emptyList()

    /** 追加到播放列表 */
    fun appendToPlaylist(songs: List<Song>) {
        playbackService?.appendToPlaylist(songs)
    }

    // ═════════════════════════════════════════════════════
    //  内部
    // ═════════════════════════════════════════════════════

    private fun requireService(): MusicPlaybackService =
        playbackService ?: error("MusicPlaybackService 未绑定")

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    data class SearchResult(
        val local: List<Song>,
        val neri: List<Song>,
        val message: String? = null
    )
}
