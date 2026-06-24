package com.lightagent.tts

/**
 * TTS 健康检查报告 — v4.4.3
 */
data class TTSHealthReport(
    val modelOk: Boolean,
    val styleOk: Boolean,
    val voiceOk: Boolean,
    val waveformOk: Boolean,
    val pcmOk: Boolean,
    val audioTrackOk: Boolean,
    val soundDetected: Boolean,
    val overallOk: Boolean,
    val log: String
) {
    companion object {
        fun empty(): TTSHealthReport = TTSHealthReport(
            modelOk = false,
            styleOk = false,
            voiceOk = false,
            waveformOk = false,
            pcmOk = false,
            audioTrackOk = false,
            soundDetected = false,
            overallOk = false,
            log = ""
        )
    }

    fun toPrettyString(): String = buildString {
        appendLine("═══ TTS 健康报告 ═══")
        appendLine("Model:       ${if (modelOk)      "✅ OK" else "❌ FAIL"}")
        appendLine("Style:       ${if (styleOk)       "✅ OK" else "❌ FAIL"}")
        appendLine("Voice:       ${if (voiceOk)       "✅ OK" else "❌ FAIL"}")
        appendLine("Waveform:    ${if (waveformOk)    "✅ OK" else "❌ FAIL"}")
        appendLine("PCM:         ${if (pcmOk)         "✅ OK" else "❌ FAIL"}")
        appendLine("AudioTrack:  ${if (audioTrackOk)  "✅ OK" else "❌ FAIL"}")
        appendLine("Sound:       ${if (soundDetected) "✅ OK" else "❌ FAIL"}")
        appendLine("────────────────────")
        appendLine("RESULT:      ${if (overallOk) "🎉 SUCCESS" else "❌ FAIL"}")
        appendLine()
        if (log.isNotEmpty()) {
            appendLine("--- 详细信息 ---")
            appendLine(log)
        }
    }
}
