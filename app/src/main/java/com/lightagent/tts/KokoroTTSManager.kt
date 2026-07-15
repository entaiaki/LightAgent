package com.lightagent.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Kokoro TTS 播放管理器 v4.4.3
 * 核心改进：initialize() 启动队列消费 + 就绪状态保护
 */
class KokoroTTSManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTTSManager"

        @Volatile
        private var instance: KokoroTTSManager? = null

        fun getInstance(context: Context): KokoroTTSManager =
            instance ?: synchronized(this) {
                instance ?: KokoroTTSManager(context.applicationContext).also { instance = it }
            }
    }

    private val kokoro = KokoroTTS.getInstance(context)
    private val vits = VitsTTS.getInstance(context)
    private val routeManager = AudioRouteManager.getInstance(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── 状态 ────────────────────────────────────────────────────────────
    private val _isTalking = MutableStateFlow(false)
    val isTalking: StateFlow<Boolean> = _isTalking

    private var isReady = false      // initialize() 是否成功
    private var currentTrack: AudioTrack? = null

    private val sentenceBuffer = StringBuilder()
    private val queue = Channel<String>(Channel.UNLIMITED)

    // ── 初始化（必须在使用前调用）───────────────────────────────────────
    private var isInitializing = false

    fun initialize() {
        if (isReady || isInitializing) {
            Log.d(TAG, "KokoroTTSManager 已就绪或正在初始化，跳过重复 initialize()")
            KokoroTTS.writeDebug("KokoroTTSManager 已就绪或正在初始化，跳过重复 initialize()")
            return
        }

        isInitializing = true
        KokoroTTS.initDebugLog(context)

        scope.launch {
            try {
                isReady = vits.initialize()

                if (isReady) {
                    Log.d(TAG, "KokoroTTSManager 就绪 ✅")
                    KokoroTTS.writeDebug("KokoroTTSManager 就绪 ✅")
                    _isTalking.value = false
                    startQueueWorker()
                } else {
                    Log.e(TAG, "KokoroTTS 初始化失败，TTS 不可用 ❌")
                    KokoroTTS.writeDebug("KokoroTTS 初始化失败 ❌")
                }
            } catch (e: Exception) {
                Log.e(TAG, "KokoroTTSManager 初始化异常", e)
                KokoroTTS.writeDebug("初始化异常: ${e.message}")
            } finally {
                isInitializing = false
            }
        }
    }

    // ── 队列消费者（初始化成功后启动）──────────────────────────────────
    private fun startQueueWorker() {
        scope.launch {
            for (sentence in queue) {
                if (sentence.isBlank()) continue
                _isTalking.value = true
                try {
                    Log.d(TAG, "合成开始：「$sentence」")
                    KokoroTTS.writeDebug("合成开始：「$sentence」")
                    val pcm = vits.synthesize(sentence)
                    Log.d(TAG, "PCM 长度: ${pcm.size}")
                    KokoroTTS.writeDebug("PCM 长度: ${pcm.size}")
                    withContext(Dispatchers.IO) { playPcm(pcm) }
                    Log.d(TAG, "合成播放完成 ✅")
                    KokoroTTS.writeDebug("合成播放完成 ✅")
                } catch (e: CancellationException) {
                    Log.d(TAG, "队列消费被取消")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "合成失败：$sentence", e)
                    KokoroTTS.writeDebug("合成失败: $sentence — ${e.message}")
                } finally {
                    _isTalking.value = false
                }
            }
            Log.d(TAG, "队列消费者退出")
        }
    }

    // ── 接收 chunk ──────────────────────────────────────────────────────
    fun feedStream(chunk: String) {
        if (!isReady) {
            Log.w(TAG, "TTS 未就绪，跳过 chunk：${chunk.take(20)}…")
            KokoroTTS.writeDebug("feedStream 阻塞: isReady=false, chunk='${chunk.take(20)}…'")
            return
        }
        sentenceBuffer.append(chunk)
        val terminators = listOf("。", "！", "？", ".", "!", "?", "\n", "…")
        if (terminators.any { sentenceBuffer.endsWith(it) }) {
            val sentence = sentenceBuffer.toString().trim()
            sentenceBuffer.clear()
            if (sentence.isNotBlank()) {
                queue.trySend(sentence)
                Log.d(TAG, "入队：「$sentence」")
                KokoroTTS.writeDebug("入队：「$sentence」")
            }
        }
    }

    // ── 冲刷剩余 buffer ─────────────────────────────────────────────────
    fun flushStream() {
        if (!isReady) return
        val remaining = sentenceBuffer.toString().trim()
        sentenceBuffer.clear()
        if (remaining.isNotBlank()) {
            queue.trySend(remaining)
            Log.d(TAG, "flush 入队：「$remaining」")
            KokoroTTS.writeDebug("flush 入队：「$remaining」")
        }
    }

    // ── 停止 ────────────────────────────────────────────────────────────
    fun stop() {
        sentenceBuffer.clear()
        currentTrack?.let { track ->
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
        currentTrack = null
        _isTalking.value = false
    }

    // ── 资源释放 ────────────────────────────────────────────────────────
    fun release() {
        stop()
        queue.close()
        scope.cancel()
        kokoro.release()
        vits.release()
        isReady = false
    }

    // ── AudioTrack 播放 PCM ─────────────────────────────────────────────
    private fun playPcm(pcm: FloatArray) {
        val sampleRate = 16000
        val shorts = ShortArray(pcm.size) { i ->
            (pcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).takeIf { it > 0 } ?: run {
            Log.e(TAG, "minBuf 无效")
            return
        }

        // 请求音频焦点
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attr).build()
        if (audioManager.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "音频焦点未获取")
            return
        }

        // 强制媒体模式
        audioManager.mode = AudioManager.MODE_NORMAL

        val track = AudioTrack(
            attr,
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        currentTrack = track

        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack 初始化失败 state=${track.state}")
                return
            }

            // 先写入 PCM，再开始播放（避免 ROM underrun）
            var offset = 0
            while (offset < shorts.size) {
                val end = minOf(offset + 4096, shorts.size)
                val written = track.write(shorts, offset, end - offset)
                if (written < 0) { Log.e(TAG, "write错误: $written"); break }
                offset += written
            }

            track.play()

            // 等缓冲区播完（用 Thread.sleep，不受协程取消影响）
            val durationMs = shorts.size.toLong() * 1000L / sampleRate
            val deadline = System.currentTimeMillis() + durationMs + 1000L
            while (System.currentTimeMillis() < deadline) {
                if (track.playbackHeadPosition >= shorts.size) break
                Thread.sleep(20)
            }
            Log.d(TAG, "播放完成 headPos=${track.playbackHeadPosition} total=${shorts.size}")
            KokoroTTS.writeDebug("播放完成 headPos=${track.playbackHeadPosition} total=${shorts.size}")

        } finally {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            currentTrack = null
            audioManager.abandonAudioFocusRequest(focusReq)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 诊断套件 — v4.4.3
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 预检查（不执行推理）— 验证模型/声音/AudioTrack 基础就绪状态
     */
    suspend fun selfCheck(): TTSHealthReport = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        // 1. Model
        val modelOk = try {
            if (!kokoro.initialized) {
                sb.appendLine("[FAIL] 模型未初始化")
                false
            } else {
                val inputs = kokoro.getInputNames()
                val outputs = kokoro.getOutputNames()
                if (inputs.isEmpty() || outputs.isEmpty()) {
                    sb.appendLine("[FAIL] 模型输入/输出节点为空")
                    false
                } else {
                    sb.appendLine("[OK] 模型输入: $inputs")
                    sb.appendLine("[OK] 模型输出: $outputs")
                    true
                }
            }
        } catch (e: Exception) {
            sb.appendLine("[FAIL] 模型检查异常: ${e.message}")
            false
        }

        // 2. Style (256 维)
        val styleOk = try {
            val style = kokoro.getCurrentStyle()
            if (style.size != 256) {
                sb.appendLine("[FAIL] Style 维度 = ${style.size}（期望 256）")
                false
            } else {
                val nonZero = style.count { it != 0f }
                sb.appendLine("[OK] Style 256 维，非零值: $nonZero")
                true
            }
        } catch (e: Exception) {
            sb.appendLine("[FAIL] Style 检查异常: ${e.message}")
            false
        }

        // 3. Voice
        val voiceOk = try {
            val style = kokoro.getCurrentStyle()
            val anyNaN = style.any { it.isNaN() }
            val anyInf = style.any { it.isInfinite() }
            if (anyNaN || anyInf) {
                sb.appendLine("[FAIL] Voice 包含 NaN=${anyNaN} Inf=${anyInf}")
                false
            } else {
                sb.appendLine("[OK] Voice 数值有效")
                true
            }
        } catch (e: Exception) {
            sb.appendLine("[FAIL] Voice 检查异常: ${e.message}")
            false
        }

        // 汇总
        val allOk = modelOk && styleOk && voiceOk
        if (!allOk) sb.appendLine("预检查未全部通过，无法继续合成")

        TTSHealthReport(
            modelOk = modelOk,
            styleOk = styleOk,
            voiceOk = voiceOk,
            waveformOk = false,
            pcmOk = false,
            audioTrackOk = false,
            soundDetected = false,
            overallOk = false,
            log = sb.toString().trimEnd()
        )
    }

    /**
     * 一键诊断 + 播放 — 完整链路
     * ① selfCheck() → ② synthesize → ③ PCM 检测 → ④ AudioTrack 播放 → ⑤ 声音监测 → ⑥ 输出报告
     */
    suspend fun runDiagnosticsAndSpeak(text: String): TTSHealthReport = withContext(Dispatchers.IO) {
        // ① 预检查
        val base = selfCheck()
        if (!base.modelOk) {
            KokoroTTS.writeDebug("诊断终止：模型未就绪")
            return@withContext base.copy(log = "${base.log}\n══════\n诊断终止：模型未就绪")
        }

        val sb = StringBuilder(base.log)
        var waveformOk = false
        var pcmOk = false
        var audioTrackOk = false
        var soundDetected = false

        try {
            // ② 合成
            Log.d(TAG, "诊断合成：「$text」")
            KokoroTTS.writeDebug("诊断合成：「$text」")
            val pcm = kokoro.synthesize(text)
            val pcmLen = pcm.size
            sb.appendLine("[INFO] PCM 长度 = $pcmLen")
            KokoroTTS.writeDebug("诊断 PCM 长度 = $pcmLen")

            waveformOk = pcmLen > 0
            if (waveformOk) sb.appendLine("[OK] Waveform 已生成")
            else sb.appendLine("[FAIL] PCM 为空")

            // ③ PCM 质量检测
            val (ok, detail) = checkPcmQuality(pcm)
            pcmOk = ok
            sb.appendLine(detail)

            // ④ + ⑤ AudioTrack 播放 + 声音检测
            if (pcmOk) {
                val playResult = monitoredPlay(pcm)
                audioTrackOk = playResult.audioTrackOk
                soundDetected = playResult.soundDetected
                sb.appendLine(if (audioTrackOk) "[OK] AudioTrack 播放完成" else "[FAIL] AudioTrack 播放失败")
                sb.appendLine(if (soundDetected) "[OK] 声音已检测到" else "[FAIL] 未检测到有效声音")
            }

        } catch (e: Exception) {
            sb.appendLine("[FAIL] 诊断异常: ${e.message}")
            KokoroTTS.writeDebug("诊断异常: ${e.message}")
            e.stackTrace.take(3).forEach { sb.appendLine("  at $it") }
        }

        val overallOk = base.modelOk && base.styleOk && base.voiceOk && waveformOk && pcmOk && audioTrackOk && soundDetected
        if (overallOk) sb.appendLine("🎉 全链路通过！")

        TTSHealthReport(
            modelOk = base.modelOk,
            styleOk = base.styleOk,
            voiceOk = base.voiceOk,
            waveformOk = waveformOk,
            pcmOk = pcmOk,
            audioTrackOk = audioTrackOk,
            soundDetected = soundDetected,
            overallOk = overallOk,
            log = sb.toString().trimEnd()
        )
    }

    // ── PCM 质量分析 ────────────────────────────────────────────────────
    private data class PcmCheckResult(val ok: Boolean, val detail: String)

    private fun checkPcmQuality(pcm: FloatArray): PcmCheckResult {
        val n = pcm.size
        if (n == 0) return PcmCheckResult(false, "[FAIL] PCM 长度为 0")

        val nanCount = pcm.count { it.isNaN() }
        val infCount = pcm.count { it.isInfinite() }
        val energy = pcmEnergy(pcm)
        val peak = pcm.maxOf { kotlin.math.abs(it) }
        val zeroRatio = pcm.count { it == 0f }.toFloat() / n

        val sb = StringBuilder()
        sb.appendLine("[INFO] PCM 统计: len=$n, energy=${
            "%.6f".format(energy)}, peak=${
            "%.4f".format(peak)}, zeroRatio=${
            "%.2f%%".format(zeroRatio * 100)}, NaN=$nanCount, Inf=$infCount")

        val ok = when {
            nanCount > 0  -> { sb.appendLine("[FAIL] PCM 含 NaN"); false }
            infCount > 0  -> { sb.appendLine("[FAIL] PCM 含 Inf"); false }
            energy < 1e-8 -> { sb.appendLine("[FAIL] PCM 能量过低（静音）"); false }
            zeroRatio > 0.99f -> { sb.appendLine("[FAIL] PCM 几乎全零"); false }
            else -> { sb.appendLine("[OK] PCM 质量正常"); true }
        }
        return PcmCheckResult(ok, sb.toString().trimEnd())
    }

    fun pcmEnergy(pcm: FloatArray): Double {
        if (pcm.isEmpty()) return 0.0
        return pcm.map { it.toDouble() * it }.sum() / pcm.size
    }

    // ── 受监控的播放 ────────────────────────────────────────────────────
    private data class PlayMonitorResult(
        val audioTrackOk: Boolean,
        val soundDetected: Boolean
    )

    private fun monitoredPlay(pcm: FloatArray): PlayMonitorResult {
        val sampleRate = 24000
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(pcm.size * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        try {
            val state = track.state
            if (state != AudioTrack.STATE_INITIALIZED) {
                KokoroTTS.writeDebug("AudioTrack 状态异常: $state")
                return PlayMonitorResult(false, false)
            }

            val shorts = ShortArray(pcm.size) { i ->
                (pcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }

            track.write(shorts, 0, shorts.size)
            track.play()

            // 等待播放完成 + 监测 underrun
            val durationMs = (pcm.size * 1000L) / sampleRate
            Thread.sleep(durationMs + 200)

            val underrun = track.underrunCount
            val headPos = track.playbackHeadPosition
            val expectedFrames = pcm.size

            track.stop()

            val soundDetected = underrun == 0 && headPos > 0
            KokoroTTS.writeDebug("播放监测: headPos=$headPos, expectedFrames=$expectedFrames, underrun=$underrun, durationMs=$durationMs")

            return PlayMonitorResult(
                audioTrackOk = true,
                soundDetected = soundDetected
            )
        } catch (e: Exception) {
            KokoroTTS.writeDebug("AudioTrack 监测异常: ${e.message}")
            return PlayMonitorResult(false, false)
        } finally {
            try { track.release() } catch (_: Exception) {}
        }
    }

    // ── 路由验证日志 ────────────────────────────────────────────────────
    private fun logAudioRouteDetail(track: AudioTrack) {
        try {
            KokoroTTS.writeDebug(
                "路由验证: ${routeManager.snapshot()}, " +
                "streamType=${track.streamType}, " +
                "perfMode=${track.performanceMode}, " +
                "state=${track.state}"
            )
        } catch (_: Exception) {}
    }

    // ── 精简版路由日志（每次播放后调用） ────────────────────────────
    private fun logAudioRoutingQuick() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
            KokoroTTS.writeDebug("路由: 音量=$vol/$max mute=$muted")
        } catch (_: Exception) {}
    }

    // ── 设备路由诊断 ────────────────────────────────────────────────────
    /**
     * 打印当前音频路由状态 + 设备列表，帮助定位"声音去了哪里"
     */
    fun logAudioRouting() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: run {
                KokoroTTS.writeDebug("AudioManager 不可用")
                return
            }

            val sb = StringBuilder()
            sb.appendLine("═══ 音频路由诊断 ═══")
            sb.appendLine("STREAM_MUSIC volume = ${am.getStreamVolume(AudioManager.STREAM_MUSIC)} / ${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            sb.appendLine("STREAM_MUSIC muted  = ${am.isStreamMute(AudioManager.STREAM_MUSIC)}")
            sb.appendLine("mode                = ${am.mode} (0=normal,1=ring,2=incall,3=comm)")
            sb.appendLine("isMusicActive       = ${am.isMusicActive}")
            sb.appendLine("isVolumeFixed       = ${am.isVolumeFixed}")

            // 输出设备
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                sb.appendLine("━━ 输出设备 (${devices.size}) ━━")
                for (d in devices) {
                    val typeName = when (d.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机(头戴)"
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
                        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE耳机"
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE音箱"
                        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳机"
                        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB音频"
                        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                        AudioDeviceInfo.TYPE_DOCK -> "底座"
                        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX线"
                        AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路"
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
                        AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
                        else -> "type=${d.type}"
                    }
                    sb.appendLine("  ${d.productName ?: "?"} ($typeName) id=${d.id} addr=${d.address}")
                }
            }

            // 通信设备（Android 16+ 可能被音频策略路由到 call device）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val commDevices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                if (commDevices.isNotEmpty()) {
                    sb.appendLine("━━ 输入设备 ━━")
                    for (d in commDevices.take(3)) {
                        sb.appendLine("  ${d.productName ?: "?"} type=${d.type}")
                    }
                }
            }

            Log.d(TAG, sb.toString())
            KokoroTTS.writeDebug(sb.toString())
        } catch (e: Exception) {
            KokoroTTS.writeDebug("路由诊断异常: ${e.message}")
        }
    }

    // ── 440Hz 测试音（强制出声验证）────────────────────────────────────
    /**
     * 播放 440Hz 正弦波 1 秒 — 纯 AudioTrack 验证
     * 如果这也听不到 → 设备路由/静音问题
     * 如果能听到    → TTS 链路触达
     */
    fun playTestTone() {
        try {
            val sampleRate = 24000
            val durationSamples = sampleRate // 1 秒
            val tone = ShortArray(durationSamples) { i ->
                (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 20000.0).toInt().toShort()
            }

            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf.coerceAtLeast(tone.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            val state = track.state
            KokoroTTS.writeDebug("测试音 AudioTrack state = $state")
            Log.d(TAG, "测试音 AudioTrack state = $state")

            if (state == AudioTrack.STATE_INITIALIZED) {
                track.write(tone, 0, tone.size)
                track.play()
                Thread.sleep(1500)
                track.stop()
                val underrun = track.underrunCount
                val headPos = track.playbackHeadPosition
                KokoroTTS.writeDebug("测试音完成: headPos=$headPos, underrun=$underrun")
                Log.d(TAG, "测试音完成: headPos=$headPos, underrun=$underrun")
                logAudioRouteDetail(track)
            }

            track.release()
        } catch (e: Exception) {
            KokoroTTS.writeDebug("测试音异常: ${e.message}")
            Log.e(TAG, "测试音异常", e)
        }
    }
}
