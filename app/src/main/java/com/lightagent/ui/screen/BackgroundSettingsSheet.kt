package com.lightagent.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.ui.ALL_BACKGROUND_THEMES
import com.lightagent.ui.BackgroundSource
import com.lightagent.ui.BackgroundTheme
import com.lightagent.ui.BackgroundViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

// 每个套系的代表渐变色（用于卡片预览）
private val themePreviewColors = mapOf(
    "night"     to listOf(Color(0xFF0D1B2A), Color(0xFF1B2A4A), Color(0xFF2E4A7A)),
    "sakura"    to listOf(Color(0xFFFFB7C5), Color(0xFFFF8FA3), Color(0xFFFF6B8A)),
    "ocean"     to listOf(Color(0xFF003566), Color(0xFF0077B6), Color(0xFF00B4D8)),
    "forest"    to listOf(Color(0xFF1B4332), Color(0xFF2D6A4F), Color(0xFF52B788)),
    "cyberpunk" to listOf(Color(0xFF10002B), Color(0xFF3C096C), Color(0xFFE040FB)),
    "plain"     to listOf(Color(0xFF667EEA), Color(0xFF764BA2), Color(0xFFF093FB))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsSheet(
    onDismiss: () -> Unit,
    bgViewModel: BackgroundViewModel = viewModel()
) {
    val currentTheme by bgViewModel.currentTheme.collectAsState()
    val background   by bgViewModel.background.collectAsState()
    val themes        = bgViewModel.themes

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { bgViewModel.setCustomBackground(it) }
    }

    ModalBottomSheet(
        onDismissRequest  = onDismiss,
        containerColor    = Color(0xFF1A1A2E),
        contentColor      = Color.White,
        dragHandle        = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {

            // ── 标题行 ───────────────────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text       = "背景套系",
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 套系卡片横向列表 ─────────────────────────────────────────────
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding        = PaddingValues(horizontal = 2.dp)
            ) {
                itemsIndexed(themes) { _, theme ->
                    ThemeCard(
                        theme      = theme,
                        isSelected = theme.id == currentTheme.id,
                        onClick    = { bgViewModel.selectTheme(theme) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 当前套系预览区 ────────────────────────────────────────────────
            if (currentTheme.fileNames.isNotEmpty()) {
                Text(
                    text     = "「${currentTheme.emoji} ${currentTheme.name}」套系预览",
                    fontSize = 14.sp,
                    color    = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(10.dp))

                // 套系内所有图片的小缩略网格
                LazyVerticalGrid(
                    columns              = GridCells.Fixed(3),
                    modifier             = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement  = Arrangement.spacedBy(8.dp),
                    userScrollEnabled    = false
                ) {
                    itemsIndexed(currentTheme.fileNames) { _, fileName ->
                        val assetPath    = "${currentTheme.folder}/$fileName"
                        val isCurrentBg  = (background as? BackgroundSource.Asset)
                                            ?.fileName == assetPath

                        AssetThumbnail(
                            assetPath  = assetPath,
                            isSelected = isCurrentBg,
                            onClick    = {
                                bgViewModel.selectSpecificAsset(assetPath)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 随机换一张按钮
                OutlinedButton(
                    onClick = { bgViewModel.randomInCurrentTheme() },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border   = androidx.compose.foundation.BorderStroke(
                        1.dp, Color.White.copy(alpha = 0.3f)
                    )
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("在「${currentTheme.name}」内随机换一张")
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))

            // ── 自选图片 ─────────────────────────────────────────────────────
            OutlinedButton(
                onClick  = { imagePicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                ),
                border   = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.3f)
                )
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("从相册自选图片")
            }

            // 当前自定义图片状态
            if (background is BackgroundSource.Custom) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text  = "✓ 已使用自定义图片",
                        fontSize = 13.sp,
                        color    = Emerald
                    )
                    TextButton(onClick = { bgViewModel.resetToDefault() }) {
                        Text("恢复默认", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 套系选择卡片 ──────────────────────────────────────────────────────────────
@Composable
private fun ThemeCard(
    theme     : BackgroundTheme,
    isSelected: Boolean,
    onClick   : () -> Unit
) {
    val colors = themePreviewColors[theme.id]
        ?: listOf(Color(0xFF667EEA), Color(0xFF764BA2))

    val scale by animateFloatAsState(
        targetValue  = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label        = "theme_card_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(colors)
                )
                .then(
                    if (isSelected) Modifier.border(
                        2.dp,
                        Color.White,
                        RoundedCornerShape(16.dp)
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(theme.emoji, fontSize = 28.sp)

            // 右上角选中勾
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint     = Color(0xFF1A1A2E),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text       = theme.name,
            fontSize   = 11.sp,
            color      = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            textAlign  = TextAlign.Center,
            maxLines   = 1
        )
    }
}

// ── 套系内单张图片缩略图 ──────────────────────────────────────────────────────
@Composable
private fun AssetThumbnail(
    assetPath : String,
    isSelected: Boolean,
    onClick   : () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 用 coil 从 assets 加载图片
    val model = remember(assetPath) {
        coil.request.ImageRequest.Builder(context)
            .data("file:///android_asset/backgrounds/$assetPath")
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .then(
                if (isSelected) Modifier.border(
                    2.dp, Color.White, RoundedCornerShape(10.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        coil.compose.AsyncImage(
            model             = model,
            contentDescription = null,
            contentScale      = ContentScale.Crop,
            modifier          = Modifier.fillMaxSize()
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.15f))
            )
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint     = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
            )
        }
    }
}
