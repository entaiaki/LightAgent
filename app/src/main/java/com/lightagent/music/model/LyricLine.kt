package com.lightagent.music.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 歌词行 — 单句时间 + 文本
 */
@Parcelize
data class LyricLine(
    val timeMs: Long,     // 时间戳（毫秒）
    val text: String      // 歌词文本
) : Parcelable
