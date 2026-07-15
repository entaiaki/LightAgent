package com.entaiaki.lightagent.tts

/**
 * TTS 分句工具 — 将长文本按中英文常见句末标点分割。
 */
object TtsSentenceSplitter {

    private val SPLIT_CHARS = setOf('.', '!', '?', '。', '！', '？', '；', ';')
    private const val MAX_CHUNK_LENGTH = 100

    fun split(text: String): List<String> {
        if (text.length <= MAX_CHUNK_LENGTH) return listOf(text)

        val sentences = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            var end = minOf(start + MAX_CHUNK_LENGTH, text.length)

            if (end < text.length) {
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
