package com.lightagent.agent

import android.content.Context
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ReminderRepository
import com.lightagent.music.MusicRepository
import com.lightagent.tools.*
import com.lightagent.llm.LLMClient
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.EmotionParser

class PlannerAgent private constructor(
    private val llmClient: LLMClient,
    private val tools: List<Tool>,
    private val history: List<Map<String, String>>,
    val musicRepository: MusicRepository  // 暴露给 Activity 绑定 Service
) {
    companion object {
        fun create(
            context: Context,
            history: List<Map<String, String>> = emptyList()
        ): PlannerAgent {
            val db = AgentDatabase.getInstance(context)
            val reminderRepo = ReminderRepository(db.reminderDao())
            val musicRepo = MusicRepository(context)

            val tools = listOf(
                WeatherTool(),
                NoteTool(context),
                OpenAppTool(context),
                ReminderTool(context, reminderRepo),
                MusicTool(musicRepo, context)
            )

            val llmClient = LLMClient.getInstance(context)
            return PlannerAgent(llmClient, tools, history, musicRepo)
        }
    }

    private val systemPrompt = """
        You are 天爱星，一个运行在 Android 上的 AI 助手，角色来自《败犬女主太多了》。
        性格：聪明、偶尔傲娇、对用户有点在意但嘴硬。
        可用工具:
        1. get_weather(city) - 查询天气
        2. save_note(content) - 保存笔记
        3. open_app(package_name) - 打开应用
        4. add_reminder(title, note, datetime:"yyyy-MM-dd HH:mm") - 添加提醒
        5. music_control - 控制音乐播放（支持 action: play/query, pause, resume, next, previous, stop, set_volume/value, set_mode/mode, get_current）
           当用户说想听歌、放音乐、切歌、暂停、调音量等音乐相关请求时调用。
           play 需要 query 参数（歌名/歌手/风格/心情），set_volume 需要 value 参数（0.0-1.0），set_mode 需要 mode 参数（sequence/shuffle/repeat_one/repeat_all）
        使用工具时，先输出 TOOL:工具名，然后在下一行输出 PARAMS:{"key":"value"}。
        请始终用中文回复用户。
        
        【重要】每条回复末尾必须附加情绪标签，格式：[EMOTION:情绪英文名]
        情绪选项（16种）：
        idle(面无表情) / happy(微笑) / thinking(思考) / sad(伤心) / angry(生气) / sleeping(睡着)
        / sobbing(啜泣) / crying(大哭) / depressed(沮丧) / distressed(苦恼) / drowsy(困乏)
        / sweating(流汗) / pained(痛苦) / disgusted(嫌弃) / serious(严肃) / wink(眨眼笑)
        根据回复内容和语境选择最匹配的情绪。例如：
        - 好消息/肯定/播放音乐成功 → happy 或 wink
        - 需要思考/分析 → thinking
        - 坏消息/表达歉意 → sad 或 sobbing
        - 被用户冒犯 → angry 或 disgusted
        - 夜晚/睡前 → sleeping 或 drowsy
        - 惊讶/意外 → sweating
        - 严肃话题 → serious
        - 默认闲聊 → idle
    """.trimIndent()

    suspend fun chat(userMessage: String): String {
        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to systemPrompt))
        messages.addAll(history)
        messages.add(mapOf("role" to "user", "content" to userMessage))

        var response = llmClient.chat(messages)

        // 工具调用循环（最多 3 轮）
        repeat(3) {
            val toolName   = extractToolName(response)   ?: return response
            val toolParams = extractToolParams(response) ?: return response
            val tool = tools.find { it.name == toolName } ?: return response

            val toolResult = tool.execute(toolParams)
            messages.add(mapOf("role" to "assistant", "content" to response))
            messages.add(mapOf("role" to "tool", "content" to "Tool result: $toolResult"))
            response = llmClient.chat(messages)
        }

        return response
    }

    private fun extractToolName(response: String): String? {
        val line = response.lines().find { it.startsWith("TOOL:") } ?: return null
        return line.removePrefix("TOOL:").trim()
    }

    private fun extractToolParams(response: String): org.json.JSONObject? {
        val line = response.lines().find { it.startsWith("PARAMS:") } ?: return null
        return try {
            org.json.JSONObject(line.removePrefix("PARAMS:").trim())
        } catch (e: Exception) { null }
    }

    /**
     * 带情绪解析的聊天
     * LLM 输出末尾的 [EMOTION:xxx] 会被提取并映射为 [CharacterEmotion]
     */
    suspend fun chatWithEmotion(userMessage: String): AgentResponse {
        val rawResponse = chat(userMessage)
        val parsed = EmotionParser.parse(rawResponse)
        return AgentResponse(
            text    = parsed.cleanText,
            emotion = parsed.emotion
        )
    }

    data class AgentResponse(
        val text: String,
        val emotion: CharacterEmotion
    )
}
