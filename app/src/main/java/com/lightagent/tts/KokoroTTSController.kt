package com.lightagent.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*

/**
 * Kokoro TTS 控制器 — 实现 TTSController
 * 本地 ONNX 推理，无需网络
 */
class KokoroTTSController(private val context: Context) : TTSController {

    private val tts = KokoroTTS.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    override var isSpeaking: Boolean = false
        private set

    override fun init(onReady: () -> Unit, onError: (Exception) -> Unit) {
        scope.launch {
            try {
                val ok = tts.initialize()
                if (ok) onReady() else onError(Exception("KokoroTTS 初始化失败"))
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    override fun speak(
        text: String,
        onStart: () -> Unit,
        onPlaybackProgress: (volume: Float) -> Unit,
        onDone: () -> Unit
    ) {
        playJob?.cancel()
        playJob = scope.launch(Dispatchers.IO) {
            try {
                isSpeaking = true
                onStart()

                // 合成 PCM
                val pcm = tts.synthesize(text)

                // AudioTrack 播放
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(pcm.size * 4)
                    .build()

                audioTrack = track
                track.play()

                // 写入数据
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)

                // 播放时回调音量（简化版：统一中等音量）
                onPlaybackProgress(0.7f)

                // 等待播放完
                track.stop()
                track.release()
                audioTrack = null

                onDone()
            } catch (e: Exception) {
                Log.e("KokoroTTS", "播放失败", e)
                onDone()
            } finally {
                isSpeaking = false
            }
        }
    }

    override fun stop() {
        playJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isSpeaking = false
    }

    override fun setRate(rate: Float) { tts.speed = rate }
    override fun setPitch(pitch: Float) { /* Kokoro 不支持音调 */ }
    override fun setVoice(voiceId: String) { tts.currentVoice = voiceId }

    override fun release() {
        stop()
        tts.release()
        scope.cancel()
    }
}
