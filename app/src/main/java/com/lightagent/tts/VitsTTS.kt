package com.lightagent.tts

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * VITS Piper 中文 TTS — zh_CN-huayan-x_low
 * 采样率：16000Hz，单声道
 * 输入：音素 ID（从 lexicon.txt + tokens.txt 查表）
 * 输出：PCM FloatArray
 */
class VitsTTS private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VitsTTS"
        const val SAMPLE_RATE = 16000

        @Volatile private var instance: VitsTTS? = null
        fun getInstance(context: Context): VitsTTS =
            instance ?: synchronized(this) {
                instance ?: VitsTTS(context.applicationContext).also { instance = it }
            }
    }

    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()

    // 音素表：phoneme string → token id
    private val phonemeMap = mutableMapOf<String, Long>()

    // 词典：中文词 → 音素列表
    private val lexicon = mutableMapOf<String, List<String>>()

    var isInitialized = false
        private set

    // ── 初始化 ─────────────────────────────────────────────────────────────
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "VitsTTS 初始化开始…")

            val modelPath = copyAsset("vits/model.onnx")
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try { addNnapi() } catch (_: Exception) {}
            }
            session = env.createSession(modelPath, opts)

            Log.d(TAG, "输入节点: ${session!!.inputNames}")
            Log.d(TAG, "输出节点: ${session!!.outputNames}")

            loadTokens()
            loadLexicon()

            isInitialized = true
            Log.d(TAG, "VitsTTS 初始化完成 ✅ phonemes=${phonemeMap.size} words=${lexicon.size}")
            KokoroTTS.writeDebug("VitsTTS 初始化完成 ✅")
            true
        } catch (e: Exception) {
            Log.e(TAG, "VitsTTS 初始化失败", e)
            KokoroTTS.writeDebug("VitsTTS 初始化失败: ${e.message}")
            false
        }
    }

    // ── 合成入口 ───────────────────────────────────────────────────────────
    fun synthesize(text: String): FloatArray {
        check(isInitialized) { "VitsTTS 未初始化" }

        // 1. 清理标签
        val cleaned = text
            .replace(Regex("\\[EMOTION:[^]]*]"), "")
            .replace(Regex("\\[[^]]*]"), "")
            .trim()

        Log.d(TAG, "合成文本：「$cleaned」")
        KokoroTTS.writeDebug("VitsTTS 合成：「$cleaned」")

        // 2. 文本 → 音素 ID
        val phonemeIds = textToPhonemeIds(cleaned)
        Log.d(TAG, "音素 ID 数量: ${phonemeIds.size}")
        KokoroTTS.writeDebug("音素 ID 数量: ${phonemeIds.size}")

        if (phonemeIds.isEmpty()) {
            Log.w(TAG, "音素序列为空，跳过合成")
            return FloatArray(0)
        }

        // 3. 构建 ONNX 输入
        val seqLen = phonemeIds.size.toLong()

        val xTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(phonemeIds),
            longArrayOf(1, seqLen)
        )
        val xLenTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(seqLen)),
            longArrayOf(1)
        )
        val noiseScaleTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatArrayOf(0.667f)),
            longArrayOf(1)
        )
        val lengthScaleTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatArrayOf(1.0f)),
            longArrayOf(1)
        )
        val noiseScaleWTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatArrayOf(0.8f)),
            longArrayOf(1)
        )
        val sidTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(0L)),
            longArrayOf(1)
        )

        val inputs = mapOf(
            "x"             to xTensor,
            "x_length"      to xLenTensor,
            "noise_scale"   to noiseScaleTensor,
            "length_scale"  to lengthScaleTensor,
            "noise_scale_w" to noiseScaleWTensor,
            "sid"           to sidTensor
        )

        return try {
            val output = session!!.run(inputs)
            val raw = output[0].value

            // 输出 shape：[1, 1, T]，需要展平
            val waveform = when (raw) {
                is Array<*> -> {
                    val ch = (raw[0] as Array<*>)[0] as FloatArray
                    ch
                }
                is FloatArray -> raw
                else -> throw IllegalStateException("未知输出类型: ${raw?.javaClass}")
            }

            Log.d(TAG, "合成完成，PCM 长度: ${waveform.size}")
            KokoroTTS.writeDebug("VitsTTS PCM 长度: ${waveform.size}")

            output.close()
            waveform
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    // ── 文本 → 音素 ID ────────────────────────────────────────────────────
    private fun textToPhonemeIds(text: String): LongArray {
        val phonemes = mutableListOf<String>()

        // BOS
        phonemes.add("^")

        // 逐字处理
        var i = 0
        while (i < text.length) {
            val char = text[i]

            when {
                // 中文字符 → 查 lexicon
                char.code in 0x4E00..0x9FFF || char.code in 0x3400..0x4DBF -> {
                    // 先尝试多字词（最长匹配，最多 4 个字）
                    var matched = false
                    for (len in minOf(4, text.length - i) downTo 1) {
                        val word = text.substring(i, i + len)
                        val phones = lexicon[word]
                        if (phones != null) {
                            phonemes.addAll(phones)
                            phonemes.add(" ")  // 词间空格
                            i += len
                            matched = true
                            break
                        }
                    }
                    if (!matched) {
                        // 查不到就跳过这个字
                        Log.w(TAG, "词典未收录: $char")
                        i++
                    }
                }

                // 标点 → 对应音素
                char == '，' || char == ',' -> { phonemes.add(","); phonemes.add(" "); i++ }
                char == '。' || char == '.' -> { phonemes.add("."); phonemes.add(" "); i++ }
                char == '！' || char == '!' -> { phonemes.add("!"); phonemes.add(" "); i++ }
                char == '？' || char == '?' -> { phonemes.add("?"); phonemes.add(" "); i++ }
                char == '：' || char == ':' -> { phonemes.add(":"); i++ }
                char == '；' || char == ';' -> { phonemes.add(";"); i++ }
                char == ' ' -> { phonemes.add(" "); i++ }

                // 英文字母/数字 → 直接按字符
                char.isLetterOrDigit() -> { phonemes.add(char.toString()); i++ }

                else -> i++
            }
        }

        // EOS
        phonemes.add("\$")

        // 音素 → ID
        return phonemes.mapNotNull { phonemeMap[it] }.toLongArray()
    }

    // ── 加载 tokens.txt ───────────────────────────────────────────────────
    private fun loadTokens() {
        val file = File(context.cacheDir, "vits/tokens.txt")
        if (!file.exists()) copyAsset("vits/tokens.txt")

        File(context.cacheDir, "vits/tokens.txt").forEachLine { line ->
            val parts = line.trim().split(" ")
            if (parts.size == 2) {
                val phoneme = parts[0]
                val id = parts[1].toLongOrNull() ?: return@forEachLine
                phonemeMap[phoneme] = id
            }
        }
        Log.d(TAG, "phonemeMap 加载完成，size=${phonemeMap.size}")
    }

    // ── 加载 lexicon.txt ──────────────────────────────────────────────────
    private fun loadLexicon() {
        val file = File(context.cacheDir, "vits/lexicon.txt")
        if (!file.exists()) copyAsset("vits/lexicon.txt")

        File(context.cacheDir, "vits/lexicon.txt").forEachLine { line ->
            val parts = line.trim().split("\t")
            if (parts.size >= 2) {
                val word = parts[0]
                val phones = parts[1].trim().split(" ").filter { it.isNotEmpty() }
                lexicon[word] = phones
            }
        }
        Log.d(TAG, "lexicon 加载完成，size=${lexicon.size}")
    }

    // ── 复制 assets ───────────────────────────────────────────────────────
    private fun copyAsset(assetPath: String): String {
        val outFile = File(context.cacheDir, assetPath)
        outFile.parentFile?.mkdirs()
        if (!outFile.exists()) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        }
        return outFile.absolutePath
    }

    // ── 释放 ──────────────────────────────────────────────────────────────
    fun release() {
        session?.close()
        isInitialized = false
    }
}
