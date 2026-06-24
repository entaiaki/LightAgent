package com.lightagent.tts

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.regex.Pattern
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * Kokoro TTS — Android ONNX 本地推理
 * 模型：onnx-community/Kokoro-82M-v1.0-ONNX
 *
 * 使用流程：
 * 1. KokoroTTS.getInstance(context).initialize()
 * 2. synthesize(text) → FloatArray（PCM 音频数据，24000Hz，单声道）
 * 3. 用 AudioTrack 或写成 WAV 播放
 */
class KokoroTTS private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTTS"
        private const val SAMPLE_RATE = 24000

        private const val MODEL_FILE  = "kokoro/model.onnx"
        private const val TOKEN_FILE  = "kokoro/tokenizer.json"
        private const val VOICE_DIR   = "kokoro/voices"

        @Volatile
        private var instance: KokoroTTS? = null

        fun getInstance(context: Context): KokoroTTS =
            instance ?: synchronized(this) {
                instance ?: KokoroTTS(context.applicationContext).also { instance = it }
            }

        // ── 调试日志写入文件 ───────────────────────────────────────────
        private var debugFile: File? = null
        private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        fun initDebugLog(ctx: Context) {
            debugFile = File(ctx.getExternalFilesDir(null), "tts_debug.log")
            debugFile?.parentFile?.mkdirs()
            writeDebug("═══ TTS 调试日志启动 ═══")
        }

        fun writeDebug(msg: String) {
            val f = debugFile ?: return
            try {
                val ts = sdf.format(Date())
                PrintWriter(FileWriter(f, true)).use { it.println("$ts  $msg") }
            } catch (_: Exception) {}
        }
    }

    private var ortSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null
    private var tokenMap: MutableMap<String, Long> = mutableMapOf()
    private var voiceCache: MutableMap<String, FloatArray> = mutableMapOf()
    private var isInitialized = false

    var currentVoice = "zf_xiaobei"   // 默认中文女声小北
    var speed = 1.0f

    /** 诊断接口：是否已初始化 */
    internal val initialized: Boolean get() = isInitialized

    /** 诊断接口：模型输入节点列表 */
    internal fun getInputNames(): List<String> = ortSession?.inputNames?.toList() ?: emptyList()

    /** 诊断接口：模型输出节点列表 */
    internal fun getOutputNames(): List<String> = ortSession?.outputNames?.toList() ?: emptyList()

    /** 诊断接口：当前 style 向量（256 维） */
    internal fun getCurrentStyle(): FloatArray {
        val raw = loadVoice(currentVoice)
        return if (raw.size >= 256) raw.copyOfRange(0, 256)
        else FloatArray(256).also { raw.copyInto(it) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 初始化
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) return@withContext true
            Log.d(TAG, "Kokoro TTS 初始化开始…")
            writeDebug("Kokoro TTS 初始化开始…")

            // 1. 复制 assets 到缓存
            val modelFile  = copyAsset(MODEL_FILE)
            val tokenFile  = copyAsset(TOKEN_FILE)

            // 2. ONNX Runtime
            ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                    Log.d(TAG, "NNAPI 加速已启用")
                } catch (_: Exception) {
                    Log.w(TAG, "NNAPI 不可用，回退 CPU")
                }
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnv!!.createSession(modelFile.absolutePath, opts)
            Log.d(TAG, "ONNX 模型加载成功")
            writeDebug("ONNX 模型加载成功")

            // 3. 加载 token 表（JSON vocab）
            tokenMap = loadVocab(tokenFile)
            Log.d(TAG, "Token 表：${tokenMap.size} 个")
            writeDebug("Token 表：${tokenMap.size} 个")

            // 4. 扫描 voices 目录，延迟加载
            val voiceDir = File(context.cacheDir, VOICE_DIR)
            if (!voiceDir.exists()) voiceDir.mkdirs()
            Log.d(TAG, "声音目录：${voiceDir.absolutePath}")

            Log.d(TAG, "ONNX 输入节点列表: ${ortSession!!.inputNames}")
            writeDebug("ONNX 输入节点列表: ${ortSession!!.inputNames}")
            Log.d(TAG, "ONNX 输出节点列表: ${ortSession!!.outputNames}")
            writeDebug("ONNX 输出节点列表: ${ortSession!!.outputNames}")
            isInitialized = true
            Log.d(TAG, "Kokoro TTS 初始化完成 ✅")
            writeDebug("Kokoro TTS 初始化完成 ✅")
            true
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
            writeDebug("初始化失败: ${e.message}")
            writeDebug("异常类型: ${e.javaClass.name}")
            e.stackTrace.take(5).forEach { writeDebug("  at $it") }
            false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 合成
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun synthesize(text: String): FloatArray = withContext(Dispatchers.Default) {
        check(isInitialized) { "KokoroTTS 未初始化" }
        val session = ortSession!!
        val env     = ortEnv!!

        // 1. 文本 → tokens
        val ids = textToTokens(text)
        Log.d(TAG, "「$text」→ ${ids.size} tokens")
        writeDebug("「$text」→ ${ids.size} tokens, first5=${ids.take(5)}, last5=${ids.takeLast(5)}")
        require(ids.isNotEmpty()) { "文本 token 化失败: $text" }

        // 2. 加载声音向量（按需），强制截断到 256 维
        val raw = loadVoice(currentVoice)
        Log.d(TAG, "声音「$currentVoice」原始维度: ${raw.size}")
        writeDebug("声音「$currentVoice」原始维度: ${raw.size}")

        val style = if (raw.size >= 256) {
            raw.copyOfRange(0, 256)
        } else {
            FloatArray(256).also { raw.copyInto(it) }
        }
        Log.d(TAG, "STYLE SIZE = ${style.size}")
        writeDebug("STYLE SIZE = ${style.size}")

        // 3. 构建输入
        val inputNames = session.inputNames.toSet()
        Log.d(TAG, "ONNX inputs = $inputNames")
        writeDebug("ONNX inputs = $inputNames")
        writeDebug("==== MODEL INPUT CHECK ====")
        writeDebug("tokens size = ${ids.size}")
        writeDebug("style size = ${style.size}")

        val inputs = mutableMapOf<String, OnnxTensor>()

        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()),
                               longArrayOf(1, ids.size.toLong()))
        inputs["input_ids"] = inputIdsTensor

        // attention_mask: 全 1，shape 同 input_ids
        if (inputNames.contains("attention_mask")) {
            val mask = LongArray(ids.size) { 1L }
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask),
                               longArrayOf(1, ids.size.toLong()))
            inputs["attention_mask"] = maskTensor
            writeDebug("attention_mask 已添加, shape=[1, ${ids.size}]")
        } else {
            writeDebug("模型不需要 attention_mask (输入节点: $inputNames)")
        }

        val styleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(style),
                               longArrayOf(1, style.size.toLong()))
        val speedTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)),
                               longArrayOf(1))

        // 按模型实际输入名映射（兼容 "style" / "style_embedding" / "speaker_embedding"）
        for (name in inputNames) {
            when {
                name == "style" || name == "style_embedding" || name == "speaker_embedding" ->
                    inputs[name] = styleTensor
                name == "speed" || name == "speech_speed" || name == "speaking_rate" ->
                    inputs[name] = speedTensor
                // input_ids 和 attention_mask 已添加
            }
        }

        Log.d(TAG, "喂入节点: ${inputs.keys}")
        writeDebug("喂入节点: ${inputs.keys}")

        // 4. 推理
        val output = session.run(inputs)

        // 5. 取音频（按名取值 + 多节点名回退 + shape 安全展平）
        val audioTensor: FloatArray = tryExtractAudio(output)
        writeDebug("audioTensor 长度: ${audioTensor.size}")

        // 释放
        inputs.values.forEach { it.close() }
        output.close()
        audioTensor
    }

    suspend fun synthesizeToFile(text: String, outputFile: File): File =
        withContext(Dispatchers.IO) {
            val pcm = synthesize(text)
            writeWav(pcm, outputFile, SAMPLE_RATE)
            outputFile
        }

    // ═════════════════════════════════════════════════════════════════════════
    // 文本 → Token
    // ═════════════════════════════════════════════════════════════════════════

    /** 安全提取音频输出 — 多节点名回退 + shape 兼容 */
    private fun tryExtractAudio(result: OrtSession.Result): FloatArray {
        val nodeNames = listOf("waveform", "audio", "output", "wav")
        for (name in nodeNames) {
            val raw = result.get(name).orElse(null) ?: continue
            val v = raw.getValue()  // OnnxValue.getValue() returns Object
            Log.d(TAG, "输出节点「$name」value: ${v?.javaClass?.simpleName}")
            writeDebug("输出节点「$name」value: ${v?.javaClass?.simpleName}")
            return when (v) {
                is FloatArray -> {
                    Log.d(TAG, "直接 FloatArray, length=${v.size}")
                    v
                }
                is Array<*> -> {
                    val first = v[0]
                    Log.d(TAG, "Array[0] type=${first?.javaClass?.simpleName}")
                    when (first) {
                        is FloatArray -> first
                        is Array<*> -> first[0] as FloatArray
                        else -> throw IllegalStateException("Array[0] 未知类型: ${first?.javaClass}")
                    }
                }
                else -> throw IllegalStateException("未知输出类型: ${v?.javaClass}")
            }
        }
        val availableNames = mutableListOf<String>()
        result.forEach { entry -> availableNames.add(entry.key) }
        throw IllegalStateException("模型没有音频输出节点，实际可用: $availableNames")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 文本 → Token
    // ═════════════════════════════════════════════════════════════════════════

    /** 中文 → 拼音预处理 */
    private fun preprocessText(text: String): String {
        val sb = StringBuilder()
        val pinyinFormat = HanyuPinyinOutputFormat().apply {
            toneType = HanyuPinyinToneType.WITH_TONE_MARK
            vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
            caseType = HanyuPinyinCaseType.LOWERCASE
        }
        for (char in text) {
            when {
                char.code in 0x4E00..0x9FFF || char.code in 0x3400..0x4DBF -> {
                    val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(char, pinyinFormat)
                    if (!pinyinArray.isNullOrEmpty()) {
                        sb.append(pinyinArray[0])
                        sb.append(' ')
                    } else {
                        sb.append(char)
                    }
                }
                char == '，' -> sb.append(", ")
                char == '。' -> sb.append(". ")
                char == '！' -> sb.append("! ")
                char == '？' -> sb.append("? ")
                char == '：' -> sb.append(": ")
                char == '；' -> sb.append("; ")
                char == '…' -> sb.append("... ")
                char == '—' -> sb.append("- ")
                char == '\u201c' -> sb.append("\"")  // "
                char == '\u201d' -> sb.append("\"")  // "
                char == '\u2018' -> sb.append("'")   // '
                char == '\u2019' -> sb.append("'")   // '
                char == '\uff08' -> sb.append("(")   // （
                char == '\uff09' -> sb.append(")")   // ）
                else -> sb.append(char)
            }
        }
        return sb.toString()
    }

    private fun textToTokens(text: String): List<Long> {
        // 过滤 emotion 标签和 LLM 元标签
        val cleaned = text.replace(Regex("\\[EMOTION:[^]]*]"), "")
                          .replace(Regex("\\[[^]]*]"), "")
                          .trim()
        // 中文 → 拼音
        val processed = preprocessText(cleaned)
        Log.d(TAG, "预处理后: «$processed»")
        writeDebug("预处理后: «$processed»")

        val tokens = mutableListOf<Long>()
        tokenMap["$"]?.let { tokens.add(it) }  // BOS
        for (ch in processed) {
            val id = tokenMap[ch.toString()]
            if (id != null) tokens.add(id)
            else tokenMap[" "]?.let { tokens.add(it) }  // 未知→空格
        }
        tokenMap["$"]?.let { tokens.add(it) }  // EOS
        return tokens
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 加载资源
    // ═════════════════════════════════════════════════════════════════════════

    /** 从 tokenizer.json 的 model.vocab 加载 */
    private fun loadVocab(file: File): MutableMap<String, Long> {
        val json  = JSONObject(file.readText())
        val vocab = json.getJSONObject("model").getJSONObject("vocab")
        val map   = mutableMapOf<String, Long>()
        for (key in vocab.keys()) {
            map[key] = vocab.getLong(key)
        }
        return map
    }

    /** 按需加载声音 .bin → FloatArray（ByteArray 一次性解析，兼容 npy v1/v2 + raw float32） */
    private fun loadVoice(name: String): FloatArray {
        voiceCache[name]?.let { return it }

        val cacheFile = copyAsset("$VOICE_DIR/$name.bin")
        val bytes = cacheFile.readBytes()

        if (bytes.size < 4) {
            throw IllegalStateException("声音文件过小或损坏: ${cacheFile.absolutePath}, size=${bytes.size}")
        }

        var offset: Int

        val isNpy = bytes.size >= 10 &&
                bytes[0] == 0x93.toByte() &&
                bytes[1] == 'N'.code.toByte() &&
                bytes[2] == 'U'.code.toByte() &&
                bytes[3] == 'M'.code.toByte() &&
                bytes[4] == 'P'.code.toByte() &&
                bytes[5] == 'Y'.code.toByte()

        if (isNpy) {
            val major = bytes[6].toInt() and 0xFF
            val minor = bytes[7].toInt() and 0xFF

            offset = if (major == 1) {
                val headerLen = (bytes[8].toInt() and 0xFF) or
                        ((bytes[9].toInt() and 0xFF) shl 8)
                10 + headerLen
            } else {
                val headerLen = (bytes[8].toInt() and 0xFF) or
                        ((bytes[9].toInt() and 0xFF) shl 8) or
                        ((bytes[10].toInt() and 0xFF) shl 16) or
                        ((bytes[11].toInt() and 0xFF) shl 24)
                12 + headerLen
            }

            Log.d(TAG, "npy 格式 v$major.$minor，跳过 header: offset=$offset")
            writeDebug("声音文件 npy 格式 v$major.$minor，跳过 header: offset=$offset")
        } else {
            offset = 0
            Log.d(TAG, "raw float32 格式")
            writeDebug("声音文件 raw float32 格式")
        }

        if (offset >= bytes.size) {
            throw IllegalStateException("声音文件 header 异常: offset=$offset, size=${bytes.size}")
        }

        val dataSize = bytes.size - offset
        val floatCount = dataSize / 4

        if (floatCount <= 0) {
            throw IllegalStateException("声音文件没有可读取的 float32 数据: ${cacheFile.absolutePath}")
        }

        val floats = FloatArray(floatCount)

        var p = offset
        for (i in 0 until floatCount) {
            floats[i] = java.lang.Float.intBitsToFloat(
                (bytes[p].toInt() and 0xFF) or
                        ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[p + 3].toInt() and 0xFF) shl 24)
            )
            p += 4
        }

        voiceCache[name] = floats

        Log.d(TAG, "声音「$name」已加载，${floats.size} 维")
        writeDebug("声音「$name」已加载，${floats.size} 维")

        return floats
    }

    /** 复制 assets 到 cacheDir — 调试阶段强制覆盖 */
    private fun copyAsset(assetPath: String): File {
        val cacheFile = File(context.cacheDir, assetPath)
        cacheFile.parentFile?.mkdirs()

        if (cacheFile.exists()) {
            cacheFile.delete()
            Log.d(TAG, "删除旧缓存 asset：${cacheFile.absolutePath}")
            writeDebug("删除旧缓存 asset：${cacheFile.absolutePath}")
        }

        context.assets.open(assetPath).use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }

        Log.d(TAG, "复制 asset → ${cacheFile.absolutePath}, size=${cacheFile.length()}")
        writeDebug("复制 asset → ${cacheFile.absolutePath}, size=${cacheFile.length()}")

        return cacheFile
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WAV 写入
    // ═════════════════════════════════════════════════════════════════════════

    private fun writeWav(pcm: FloatArray, file: File, sampleRate: Int) {
        val shorts = ShortArray(pcm.size) { i ->
            (pcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
        val dataSize = shorts.size * 2

        file.outputStream().use { out ->
            // RIFF header
            out.write("RIFF".toByteArray())
            out.write(intToLe(36 + dataSize))
            out.write("WAVE".toByteArray())

            // fmt chunk
            out.write("fmt ".toByteArray())
            out.write(intToLe(16))              // subchunk size
            out.write(shortToBytes(1))             // PCM format
            out.write(shortToBytes(1))             // mono
            out.write(intToLe(sampleRate))
            out.write(intToLe(sampleRate * 2))  // byte rate
            out.write(shortToBytes(2))             // block align
            out.write(shortToBytes(16))            // bits/sample

            // data chunk
            out.write("data".toByteArray())
            out.write(intToLe(dataSize))
            for (s in shorts) out.write(shortToBytes(s.toInt()))
        }
    }

    private fun intToLe(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    private fun shortToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte()
    )

    fun release() {
        ortSession?.close()
        ortEnv?.close()
        isInitialized = false
    }
}
