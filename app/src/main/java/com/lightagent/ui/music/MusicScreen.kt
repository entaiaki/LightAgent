package com.lightagent.ui.music

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lightagent.music.model.PlayMode
import com.lightagent.music.model.PlaybackState
import com.lightagent.music.model.Song
import com.lightagent.ui.theme.*

/**
 * 独立音乐界面
 *
 * 页面结构：
 * ┌─────────────────────────────┐
 * │  🔍 搜索栏                   │
 * ├─────────────────────────────┤
 * │  歌曲列表（搜索结果 / 本地）    │
 * │  ├ 歌曲行 1                  │
 * │  ├ 歌曲行 2                  │
 * │  └ …                       │
 * ├─────────────────────────────┤ ← 迷你播放器（有歌时显示）
 * │ 🎵 歌名 — 歌手  ⏯ ⏭     │
 * └─────────────────────────────┘
 *
 * 点击迷你播放器 → 弹出全屏 MusicPlayerPanel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    viewModel: MusicViewModel = viewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val searchResults  by viewModel.searchResults.collectAsState()
    val searchQuery    by viewModel.searchQuery.collectAsState()
    val lyrics         by viewModel.lyrics.collectAsState()
    val lyricIndex     by viewModel.currentLyricIndex.collectAsState()
    val showPanel      by viewModel.showPanel.collectAsState()

    var searchText by remember(searchQuery) { mutableStateOf(searchQuery) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientStart, GradientMid, GradientEnd)))
    ) {
        // ── 顶部搜索栏 ────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(GlassBg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = TextSecondary
                )
            }

            // 搜索输入框
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                placeholder = {
                    Text("搜索歌曲、歌手、风格…", color = TextHint, fontSize = 14.sp)
                },
                trailingIcon = {
                    if (searchText.isNotBlank()) {
                        IconButton(onClick = {
                            searchText = ""
                            viewModel.search("")
                        }) {
                            Icon(Icons.Default.Close, "清空", tint = TextSecondary)
                        }
                    } else {
                        IconButton(onClick = { viewModel.search(searchText) }) {
                            Icon(Icons.Default.Search, "搜索", tint = AccentPurple)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor  = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor    = TextPrimary,
                    unfocusedTextColor  = TextPrimary,
                    cursorColor         = AccentPurple
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
            )
        }

        // ── 推荐/热门标签 ─────────────────────────────────────
        if (searchResults.isEmpty() && searchText.isBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("🎵 本地音乐", "📁 最近播放", "🎸 纯音乐", "🌙 助眠").forEach { tag ->
                    AssistChip(
                        onClick = { viewModel.search(tag.removePrefix("🎵 ").removePrefix("📁 ").removePrefix("🎸 ").removePrefix("🌙 ")) },
                        label = { Text(tag, fontSize = 12.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = GlassBg,
                            labelColor = TextSecondary
                        ),
                        border = AssistChipDefaults.assistChipBorder(
                            borderColor = GlassBorder,
                            enabled = true
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── 内容区域 ──────────────────────────────────────────
        if (searchResults.isEmpty() && searchText.isNotBlank()) {
            // 搜索无结果
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "没找到「$searchText」相关的歌曲",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "试试其他关键词，或在 NeriPlayer 中搜索",
                        color = TextHint,
                        fontSize = 12.sp
                    )
                }
            }
        } else if (searchResults.isEmpty() && searchText.isBlank()) {
            // 空状态 — 引导搜索
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = AccentPurple.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "🎧 天爱星音乐",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "搜一首歌，让我为你播放",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            // 有结果时展示列表
            // "全部播放" 按钮行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${searchResults.size} 首歌曲",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                TextButton(onClick = { viewModel.playAll(searchResults) }) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("全部播放", color = AccentPurple, fontSize = 13.sp)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start  = 16.dp,
                    end    = 16.dp,
                    top    = 4.dp,
                    bottom = if (playbackState.currentSong != null) 88.dp else 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(searchResults, key = { it.uri.hashCode() }) { song ->
                    SongRow(
                        song = song,
                        isCurrent = song == playbackState.currentSong,
                        isPlaying = playbackState.isPlaying,
                        onClick = { viewModel.playSong(song) }
                    )
                }
            }
        }

        // ── 迷你播放器（底部栏） ──────────────────────────────
        playbackState.currentSong?.let { song ->
            MiniPlayerBar(
                song     = song,
                state    = playbackState,
                onPlayPause = { viewModel.togglePlayPause() },
                onNext      = { viewModel.next() },
                onTap       = { viewModel.showFullPanel() }
            )
        }
    }

    // ── 全屏播放面板对话框 ─────────────────────────────────
    if (showPanel) {
        Dialog(
            onDismissRequest = { viewModel.hideFullPanel() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DeepNavy)
            ) {
                MusicPlayerPanel(
                    state             = playbackState,
                    lyrics            = lyrics,
                    currentLyricIndex = lyricIndex,
                    onPlayPause       = { viewModel.togglePlayPause() },
                    onNext            = { viewModel.next() },
                    onPrevious        = { viewModel.previous() },
                    onSeek            = { viewModel.seek(it) },
                    onModeChange      = { viewModel.setPlayMode(it) },
                    onClose           = { viewModel.hideFullPanel() }
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────
//  内部组件
// ────────────────────────────────────────────────────────────────

/**
 * 单行歌曲条目
 */
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) AccentPurple.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面缩略图
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GlassBg),
            contentAlignment = Alignment.Center
        ) {
            if (song.albumArtUri != null) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (isCurrent) AccentPurple else TextHint,
                    modifier = Modifier.size(24.dp)
                )
            }
            // 当前播放指示
            if (isCurrent && isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AccentPurple.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // 歌曲信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text      = song.title,
                color     = if (isCurrent) AccentPurple else TextPrimary,
                fontSize  = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text     = song.artist.ifBlank { "未知歌手" },
                color    = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 更多操作
        IconButton(onClick = { /* 预留：弹出菜单 */ }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "更多",
                tint = TextHint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 底部迷你播放器栏
 *
 * 显示当前歌曲信息、播放/暂停、下一首按钮。
 * 点击整条 bar → 展开全屏播放面板。
 */
@Composable
private fun MiniPlayerBar(
    song: Song,
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onTap: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onTap),
        color = GlassBg.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GlassBg),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // 歌曲名 + 歌手
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text      = song.title,
                    color     = TextPrimary,
                    fontSize  = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis
                )
                Text(
                    text     = song.artist.ifBlank { "未知歌手" },
                    color    = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 播放/暂停
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = AccentPurple,
                    modifier = Modifier.size(28.dp)
                )
            }

            // 下一首
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = TextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            // 展开全屏指示
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "展开播放器",
                tint = TextHint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}


