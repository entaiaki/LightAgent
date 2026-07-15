package com.entaiaki.lightagent.music

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MusicPlayerState(
    val currentTrack: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Int = 0,
    val duration: Int = 0
)

class MusicPlayerController(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    fun play(track: MusicTrack) {
        stop()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.parse(track.uri))
                prepare()
                start()
                setOnCompletionListener {
                    _state.value = _state.value.copy(isPlaying = false, currentPosition = 0)
                    stopProgressPolling()
                }
            }
            _state.value = _state.value.copy(
                currentTrack = track,
                isPlaying = true,
                duration = mediaPlayer?.duration ?: 0,
                currentPosition = 0
            )
            startProgressPolling()
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed: ${e.message}")
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        _state.value = _state.value.copy(isPlaying = false)
        stopProgressPolling()
    }

    fun resume() {
        mediaPlayer?.start()
        _state.value = _state.value.copy(isPlaying = true)
        startProgressPolling()
    }

    fun stop() {
        stopProgressPolling()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _state.value = _state.value.copy(
            isPlaying = false,
            currentTrack = null,
            currentPosition = 0,
            duration = 0
        )
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
        _state.value = _state.value.copy(currentPosition = position)
    }

    fun release() {
        stop()
    }

    private fun startProgressPolling() {
        stopProgressPolling()
        progressJob = scope.launch {
            while (true) {
                val player = mediaPlayer ?: break
                if (player.isPlaying) {
                    _state.value = _state.value.copy(currentPosition = player.currentPosition)
                }
                delay(500)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    companion object {
        private const val TAG = "MusicPlayer"
    }
}
