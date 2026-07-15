package com.entaiaki.lightagent.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.entaiaki.lightagent.music.MusicPlayerController
import com.entaiaki.lightagent.music.MusicPlayerState
import com.entaiaki.lightagent.music.MusicRepository
import com.entaiaki.lightagent.music.MusicTrack
import kotlinx.coroutines.launch

@Composable
fun MusicPlayerScreen(
    controller: MusicPlayerController,
    modifier: Modifier = Modifier
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var tracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            isScanning = true
            scope.launch {
                tracks = MusicRepository.loadLocalTracks(context)
                isScanning = false
            }
        } else {
            permissionDenied = true
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {

        // ── 顶栏 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Music", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { permissionLauncher.launch(audioPermission) }) {
                Icon(Icons.Default.Refresh, contentDescription = "扫描本地音乐")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── 权限被拒绝提示 ──
        if (permissionDenied) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "需要存储权限才能读取本地音乐，请点击刷新按钮重新授权。",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── 正在播放卡片 ──
        state.currentTrack?.let { track ->
            NowPlayingCard(
                track = track,
                state = state,
                onPlayPause = { if (state.isPlaying) controller.pause() else controller.resume() },
                onStop = { controller.stop() },
                onSeek = { controller.seekTo(it) },
                onPrevious = {
                    val idx = tracks.indexOfFirst { it.id == track.id }
                    if (idx > 0) controller.play(tracks[idx - 1])
                },
                onNext = {
                    val idx = tracks.indexOfFirst { it.id == track.id }
                    if (idx in 0 until tracks.size - 1) controller.play(tracks[idx + 1])
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── 曲库标题行 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "本地曲库",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (tracks.isNotEmpty()) {
                Text(
                    "${tracks.size} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── 曲库列表 / 空状态 / 扫描中 ──
        when {
            isScanning -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("正在扫描本地音乐…")
                    }
                }
            }
            tracks.isEmpty() -> {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "点击右上角刷新按钮\n扫描手机本地音乐",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(tracks, key = { it.id }) { track ->
                        TrackItem(
                            track = track,
                            isPlaying = state.currentTrack?.id == track.id && state.isPlaying,
                            onClick = { controller.play(track) }
                        )
                    }
                }
            }
        }
    }
}

// ── 正在播放卡片 ──
@Composable
private fun NowPlayingCard(
    track: MusicTrack,
    state: MusicPlayerState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(8.dp))

            if (state.duration > 0) {
                Slider(
                    value = state.currentPosition.toFloat(),
                    onValueChange = { onSeek(it.toInt()) },
                    valueRange = 0f..state.duration.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(state.currentPosition), style = MaterialTheme.typography.labelSmall)
                    Text(formatDuration(state.duration), style = MaterialTheme.typography.labelSmall)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "上一首") }
                IconButton(onClick = onPlayPause) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.isPlaying) "暂停" else "播放")
                }
                IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "停止") }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "下一首") }
            }
        }
    }
}

// ── 曲目列表项 ──
@Composable
private fun TrackItem(track: MusicTrack, isPlaying: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.MusicNote,
                contentDescription = null,
                tint = if (isPlaying) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatDuration(track.duration.toInt()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDuration(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
