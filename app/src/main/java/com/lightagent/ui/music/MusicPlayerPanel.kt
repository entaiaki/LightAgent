package com.lightagent.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.music.model.LyricLine
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import com.lightagent.ui.theme.*

/**
 * 完整音乐播放面板
 *
 * 包含：封面、歌名/歌手、歌词滚动、进度条、播放控制栏
 *
 * @param state 当前播放状态
 * @param lyrics 全部歌词行
 * @param currentLyricIndex 当前歌词行索引
 * @param onPlayPause 播放/暂停回调
 * @param onNext 下一首
 * @param onPrevious 上一首
 * @param onSeek 拖动进度
 * @param onModeChange 切换播放模式
 * @param onClose 关闭面板
 */
@Composable
fun MusicPlayerPanel(
    state: PlaybackState,
    lyrics: List<LyricLine> = emptyList(),
    currentLyricIndex: Int = -1,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onModeChange: (PlayMode) -> Unit = {},
    onClose: () -> Unit = {}
) {
    val song = state.currentSong

    if (song == null) {
        // 无歌曲时的空状态
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text  = "🎵 没有正在播放的歌曲",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
        return
    }

    val listState = rememberLazyListState()

    // 自动滚动到当前歌词
    LaunchedEffect(currentLyricIndex) {
        if (currentLyricIndex in lyrics.indices) {
            listState.animateScrollToItem(
                index = (currentLyricIndex - 2).coerceAtLeast(0)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── 关闭按钮（右上角） ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = TextSecondary
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 歌名 / 歌手 ──
        Text(
            text      = song.title,
            color     = TextPrimary,
            fontSize  = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines  = 1,
            overflow  = TextOverflow.Ellipsis
        )
        Text(
            text     = song.artist.ifBlank { "未知歌手" },
            color    = TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(24.dp))

        // ── 歌词滚动区 ──
        if (lyrics.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部留白
                item { Spacer(Modifier.height(80.dp)) }

                itemsIndexed(lyrics) { index, line ->
                    val isCurrent = index == currentLyricIndex
                    Text(
                        text = line.text,
                        style = if (isCurrent)
                            MaterialTheme.typography.bodyLarge
                        else
                            MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent)
                            AccentPurple
                        else
                            TextSecondary.copy(alpha = 0.5f),
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 底部留白
                item { Spacer(Modifier.height(80.dp)) }
            }
        } else {
            // 无歌词时中央提示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = "🎶 纯音乐 / 暂无歌词",
                    color = TextSecondary.copy(alpha = 0.4f),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 进度条+时间 ──
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (state.duration > 0)
                    state.progress.toFloat() / state.duration else 0f,
                onValueChange = { ratio ->
                    onSeek((ratio * state.duration).toLong())
                },
                colors = SliderDefaults.colors(
                    thumbColor = AccentPurple,
                    activeTrackColor = AccentPurple,
                    inactiveTrackColor = GlassBorder
                ),
                modifier = Modifier.height(32.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text    = formatDuration(state.progress),
                    color   = TextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    text    = formatDuration(state.duration),
                    color   = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 播放控制栏 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 播放模式切换
            IconButton(onClick = {
                val next = when (state.playMode) {
                    PlayMode.SEQUENCE   -> PlayMode.SHUFFLE
                    PlayMode.SHUFFLE     -> PlayMode.REPEAT_ALL
                    PlayMode.REPEAT_ALL  -> PlayMode.REPEAT_ONE
                    PlayMode.REPEAT_ONE  -> PlayMode.SEQUENCE
                }
                onModeChange(next)
            }) {
                val modeIcon = when (state.playMode) {
                    PlayMode.SEQUENCE   -> Icons.Default.Repeat
                    PlayMode.SHUFFLE     -> Icons.Default.Shuffle
                    PlayMode.REPEAT_ONE  -> Icons.Default.RepeatOne
                    PlayMode.REPEAT_ALL  -> Icons.Default.Repeat
                }
                Icon(
                    modeIcon,
                    contentDescription = "播放模式",
                    tint = if (state.playMode != PlayMode.SEQUENCE)
                        AccentPurple else TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }

            // 上一首
            IconButton(onClick = onPrevious) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    tint = TextPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            // 播放/暂停（大按钮）
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(AccentPurple)
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            // 下一首
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = TextPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            // 停止
            IconButton(onClick = { /* stop */ }) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "停止",
                    tint = TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * 格式化毫秒为 mm:ss
 */
private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
