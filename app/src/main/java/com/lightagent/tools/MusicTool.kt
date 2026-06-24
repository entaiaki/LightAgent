package com.lightagent.tools

import android.content.Context
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.MusicPreferenceDao
import com.lightagent.memory.PlayHistoryEntity
import com.lightagent.music.MusicRepository
import com.lightagent.music.model.PlayMode
import org.json.JSONObject

/**
 * 音乐控制工具 — 天爱星调用音乐的入口
 *
 * 用户说出与音乐相关的意图时，PlannerAgent 会调用此 tool。
 * 支持：播放、暂停、切歌、调音量、切换模式、获取当前播放信息。
 */
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
            return "❌ 缺少 action 参数，可用操作：play / pause / resume / next / previous / stop / set_volume / set_mode / get_current"
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
        val result = repository.playSearch(query.takeIf { it.isNotEmpty() })
        // 记录播放历史
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
            else         -> return "❌ 不支持的播放模式：$modeStr（可用：sequence / shuffle / repeat_one / repeat_all）"
        }
        return repository.setMode(mode)
    }

    private suspend fun recordPlay(query: String) {
        try {
            val db = AgentDatabase.getInstance(context)
            val dao = db.musicPreferenceDao()
            val history = PlayHistoryEntity(
                title  = query,
                artist = "",
                source = "LOCAL"
            )
            dao.recordPlay(history)
        } catch (_: Exception) {
            // 历史记录失败不影响播放
        }
    }
}
