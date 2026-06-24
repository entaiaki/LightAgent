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

/**
 * 音乐模块 ViewModel — UI 与 MusicPlaybackService 之间的胶水层
 *
 * 职责：
 * - 暴露播放状态 StateFlow（来自 Service）
 * - 管理歌词索引计算
 * - 处理搜索 / 播放 / 模式切换操作
 * - 控制全屏播放面板的显隐
 */
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    // ── 播放状态（直连 Service StateFlow） ────────────────────
    val playbackState: StateFlow<PlaybackState> =
        repository.playbackState ?: MutableStateFlow(PlaybackState())

    // ── 歌词 ─────────────────────────────────────────────────
    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    /** 当前歌词行索引（基于播放进度实时计算） */
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

    // ── 搜索 ─────────────────────────────────────────────────
    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── 全屏播放面板显隐 ────────────────────────────────────
    private val _showPanel = MutableStateFlow(false)
    val showPanel: StateFlow<Boolean> = _showPanel.asStateFlow()

    // ═══════════════════════════════════════════════════════
    //  公开操作
    // ═══════════════════════════════════════════════════════

    /** 搜索歌曲 */
    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            val result = repository.search(query)
            _searchResults.value = result.local
        }
    }

    /** 播放单首歌曲（搜索并播放第一匹配） */
    fun playSong(song: Song) {
        viewModelScope.launch {
            repository.playSearch(song.title)
            loadLyrics(song)
        }
    }

    /** 播放整组歌曲 */
    fun playAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        MusicPlaybackService.instance?.setPlaylist(songs)
        viewModelScope.launch {
            loadLyrics(songs.first())
        }
    }

    /** 播放 / 暂停切换 */
    fun togglePlayPause() {
        val service = MusicPlaybackService.instance ?: return
        if (playbackState.value.isPlaying) service.pause()
        else service.resume()
    }

    /** 下一首 */
    fun next() { MusicPlaybackService.instance?.next() }

    /** 上一首 */
    fun previous() { MusicPlaybackService.instance?.previous() }

    /** 拖动进度 */
    fun seek(positionMs: Long) {
        MusicPlaybackService.instance?.seek(positionMs)
    }

    /** 设置播放模式 */
    fun setPlayMode(mode: PlayMode) {
        MusicPlaybackService.instance?.setPlayMode(mode)
    }

    /** 循环切换播放模式：顺序 → 随机 → 全列表循环 → 单曲循环 → 顺序 */
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

    /** 展开全屏播放面板 */
    fun showFullPanel() { _showPanel.value = true }

    /** 关闭全屏播放面板 */
    fun hideFullPanel() { _showPanel.value = false }

    // ═══════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════

    /** 加载指定歌曲的歌词到 StateFlow */
    private suspend fun loadLyrics(song: Song) {
        _lyrics.value = repository.lyricManager?.getAllLines() ?: emptyList()
    }
}
