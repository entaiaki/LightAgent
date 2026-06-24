package com.lightagent.music

import com.lightagent.music.model.LyricLine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 歌词管理器
 *
 * 负责 LRC 解析、当前歌词行查找、歌词缓存。
 * 线程安全（Mutex 保护内部状态）。
 */
class LyricManager {

    private val mutex = Mutex()
    private var lyrics: List<LyricLine> = emptyList()
    private var cachedRaw: String = ""

    /**
     * 解析 LRC 格式歌词并缓存
     *
     * @param raw 原始 LRC 文本（每行格式：[mm:ss.xx]text）
     * @return 解析后的歌词行列表
     */
    suspend fun parse(raw: String): List<LyricLine> = mutex.withLock {
        if (raw == cachedRaw && lyrics.isNotEmpty()) return@withLock lyrics

        cachedRaw = raw
        lyrics = raw.lines().mapNotNull { line ->
            parseLine(line)
        }.sortedBy { it.timeMs }

        // 没有有效歌词时返回空
        lyrics
    }

    /**
     * 根据当前播放进度查找对应歌词行索引
     *
     * @param positionMs 当前播放位置（毫秒）
     * @return 对应的歌词行索引，-1 表示无匹配
     */
    suspend fun findIndex(positionMs: Long): Int = mutex.withLock {
        if (lyrics.isEmpty()) return@withLock -1

        // 二分查找：找到最后一个 timeMs <= positionMs 的行
        var lo = 0
        var hi = lyrics.size - 1
        var result = 0

        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lyrics[mid].timeMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        result
    }

    /**
     * 获取全部歌词
     */
    suspend fun getAllLines(): List<LyricLine> = mutex.withLock {
        lyrics.toList()
    }

    /**
     * 清空歌词缓存
     */
    suspend fun clear() = mutex.withLock {
        lyrics = emptyList()
        cachedRaw = ""
    }

    // ─── 私有辅助 ──────────────────────────────────────────────

    /**
     * 解析单行 LRC 格式
     * 支持：`[01:23.45]歌词文本` 或 `[01:23]歌词文本`
     */
    private fun parseLine(line: String): LyricLine? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        // 匹配时间标签 [mm:ss.xx] 或 [mm:ss]
        val timeRegex = Regex("""^\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]""")
        val match = timeRegex.find(trimmed) ?: return null

        val minutes = match.groupValues[1].toIntOrNull() ?: return null
        val seconds = match.groupValues[2].toIntOrNull() ?: return null
        val millis  = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

        // 毫秒：如果只有2位数，补成3位（例如 .45 → 450ms）
        val millisAdjusted = when {
            millis == 0    -> 0
            millis < 10    -> millis * 100
            millis < 100   -> millis * 10
            else           -> millis
        }

        val timeMs = (minutes * 60_000L) + (seconds * 1_000L) + millisAdjusted

        // 提取文本（去除时间标签后）
        val text = trimmed.removeRange(match.range).trim()
        if (text.isEmpty()) return null

        return LyricLine(timeMs = timeMs, text = text)
    }
}
