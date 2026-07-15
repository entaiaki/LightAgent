package com.entaiaki.lightagentlive.tts

/**
 * TTS 分句工具 — 从 OmniVoice 移植
 *
 * 将长文本按中英文常见句末标点分割成适合 TTS 引擎处理的短句。
 * 每句不超过 [MAX_CHUNK_LENGTH] 个字符。
 */
object TtsSentenceSplitter {

    /** 中英文常见句末标点 */
    private val SPLIT_CHARS = setOf('.', '!', '?', '。', '！', '？', '；', ';')

    /** 单句最大字符数（超出则强制在最近标点处截断） */
    private const val MAX_CHUNK_LENGTH = 100

    /**
     * 将 [text] 按句末标点分割，保证每段不超过 MAX_CHUNK_LENGTH。
     * 如果原文不超过上限则原样返回。
     */
    fun split(text: String): List<String> {
        if (text.length <= MAX_CHUNK_LENGTH) return listOf(text)

        val sentences = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            var end = minOf(start + MAX_CHUNK_LENGTH, text.length)

            if (end < text.length) {
                // 在 chunk 范围内往回找最近的句末标点
                var splitPos = -1
                for (i in end downTo start + 1) {
                    if (text[i - 1] in SPLIT_CHARS) {
                        splitPos = i
                        break
                    }
                }
                if (splitPos > start) end = splitPos
            }

            val chunk = text.substring(start, end).trim()
            if (chunk.isNotEmpty()) sentences.add(chunk)
            start = end
        }

        return sentences
    }
}
