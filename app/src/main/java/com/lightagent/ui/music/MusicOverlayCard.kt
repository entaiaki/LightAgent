package com.lightagent.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import com.lightagent.ui.theme.*

/**
 * 悬浮歌词卡片 — 天爱星桌宠模式下显示在角色旁边的迷你播放器
 *
 * 显示内容：当前歌曲名 + 歌手 + 当前歌词行
 */
@Composable
fun MusicOverlayCard(
    state: PlaybackState,
    currentLyric: String = "",
    modifier: Modifier = Modifier
) {
    val song = state.currentSong

    // 没有歌曲时不显示
    AnimatedVisibility(
        visible = song != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit  = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            GlassBg.copy(alpha = 0.85f),
                            GlassBg.copy(alpha = 0.6f)
                        )
                    ),
                    RoundedCornerShape(16.dp)
                )
                .background(
                    GlassBorder,
                    RoundedCornerShape(16.dp)
                )
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 歌名 ──
            Text(
                text      = song?.title ?: "",
                color     = TextPrimary,
                fontSize  = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis
            )

            // ── 歌手 ──
            if (song?.artist?.isNotBlank() == true) {
                Text(
                    text     = song.artist,
                    color    = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 歌词行 ──
            if (currentLyric.isNotBlank()) {
                Text(
                    text      = currentLyric,
                    color     = AccentPurple.copy(alpha = 0.9f),
                    fontSize  = 12.sp,
                    maxLines  = 2,
                    overflow  = TextOverflow.Ellipsis,
                    modifier  = Modifier.alpha(0.85f)
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── 播放状态指示器 ──
            PlayingIndicator(isPlaying = state.isPlaying)
        }
    }
}

/**
 * 播放状态指示器（小圆点动画）
 */
@Composable
private fun PlayingIndicator(isPlaying: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        if (isPlaying) {
            // 动画音柱
            val infiniteTransition = rememberInfiniteTransition(label = "music_indicator")
            repeat(3) { i ->
                val height by infiniteTransition.animateFloat(
                    initialValue  = 4f,
                    targetValue   = 12f,
                    animationSpec = infiniteRepeatable(
                        tween(400 + i * 150, delayMillis = i * 80),
                        RepeatMode.Reverse
                    ),
                    label = "bar$i"
                )
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(height.dp)
                        .background(AccentPurple, RoundedCornerShape(2.dp))
                )
            }
        } else {
            // 暂停：三个静止短条
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(4.dp)
                        .background(TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}
