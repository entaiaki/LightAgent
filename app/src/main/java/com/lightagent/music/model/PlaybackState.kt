package com.lightagent.music.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 播放状态 — 全局单一数据源
 *
 * 通过 StateFlow 暴露给 UI / 桌宠 / 天爱星 Agent
 */
@Parcelize
data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val progress: Long = 0L,         // 毫秒
    val duration: Long = 0L,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val volume: Float = 1.0f
) : Parcelable

/**
 * 播放模式
 */
enum class PlayMode {
    /** 顺序播放 */
    SEQUENCE,
    /** 随机播放 */
    SHUFFLE,
    /** 单曲循环 */
    REPEAT_ONE,
    /** 列表循环 */
    REPEAT_ALL
}
