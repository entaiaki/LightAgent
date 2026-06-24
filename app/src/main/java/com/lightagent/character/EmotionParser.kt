package com.lightagent.character

/**
 * 从LLM返回的文本中提取情绪标签，并清理掉标签本身
 * LLM输出格式约定：在回复末尾加 [EMOTION:xxx]
 * 例如："好的，明天天气晴朗！[EMOTION:happy]"
 */
object EmotionParser {

    private val EMOTION_REGEX = Regex("\\[EMOTION:([a-zA-Z_\\u4e00-\\u9fa5]+)]")

    data class ParseResult(
        val cleanText: String,
        val emotion: CharacterEmotion
    )

    fun parse(rawText: String): ParseResult {
        val match = EMOTION_REGEX.find(rawText)
        return if (match != null) {
            ParseResult(
                cleanText = rawText.replace(match.value, "").trim(),
                emotion = CharacterEmotion.fromTag(match.groupValues[1])
            )
        } else {
            ParseResult(
                cleanText = rawText,
                emotion = guessEmotion(rawText)
            )
        }
    }

    /**
     * LLM 没带标签时的关键词兜底猜测
     */
    private fun guessEmotion(text: String): CharacterEmotion {
        return when {
            text.contains(Regex("哈哈|太好了|棒|开心|好的！|没问题|✅|微笑|眨眼"))
                -> CharacterEmotion.HAPPY
            text.contains(Regex("嗯…|让我想想|稍等|不确定|可能|考虑"))
                -> CharacterEmotion.THINKING
            text.contains(Regex("抱歉|对不起|难过|伤心|遗憾|哭|😢|悲"))
                -> CharacterEmotion.SAD
            text.contains(Regex("生气|怒|不满意|❌|不行|可恶"))
                -> CharacterEmotion.ANGRY
            text.contains(Regex("困|累了|睡觉|晚安|晚安|😴"))
                -> CharacterEmotion.SLEEPING
            text.contains(Regex("哎呀|没想到|什么！|居然|震惊|吓"))
                -> CharacterEmotion.SWEATING
            text.contains(Regex("烦|讨厌|嫌弃|恶心|🤢"))
                -> CharacterEmotion.DISGUSTED
            text.contains(Regex("痛苦|疼|难受|折磨"))
                -> CharacterEmotion.PAINED
            text.contains(Regex("严肃|认真|重要|注意"))
                -> CharacterEmotion.SERIOUS
            else -> CharacterEmotion.IDLE
        }
    }
}
