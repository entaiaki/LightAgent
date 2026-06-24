package com.lightagent.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterPack
import com.lightagent.ui.CharacterPackViewModel
import com.lightagent.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterPackSheet(
    onDismiss : () -> Unit,
    viewModel : CharacterPackViewModel = viewModel()
) {
    val currentPack by viewModel.currentPack.collectAsState()
    val packs        = viewModel.packs

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A2E),
        contentColor     = Color.White,
        dragHandle = {
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
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {

            // ── 标题行 ───────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text       = "角色切换",
                        fontSize   = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White
                    )
                    Text(
                        text     = "当前：${currentPack.name}",
                        fontSize = 12.sp,
                        color    = Color.White.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            Spacer(Modifier.height(20.dp))

            if (packs.size == 1) {
                // 只有一套时给个提示
                OnlyOnePackHint()
            } else {
                // 角色包横向卡片列表
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding        = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                ) {
                    items(packs, key = { it.id }) { pack ->
                        CharacterPackCard(
                            pack       = pack,
                            isSelected = pack.id == currentPack.id,
                            onClick    = {
                                viewModel.selectPack(pack)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 单个角色包卡片 ────────────────────────────────────────────────────────────
@Composable
private fun CharacterPackCard(
    pack      : CharacterPack,
    isSelected: Boolean,
    onClick   : () -> Unit
) {
    val context = LocalContext.current

    // 选中时弹簧放大
    val scale by animateFloatAsState(
        targetValue   = if (isSelected) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ),
        label = "packCardScale"
    )

    // 预览图（IDLE 情绪）
    val previewBitmap = remember(pack.id) {
        runCatching {
            context.assets.open(pack.previewAssetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(onClick = onClick)
    ) {
        // 卡片主体
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2A2A4A))
                .then(
                    if (isSelected) Modifier.border(
                        2.dp, AccentPurple, RoundedCornerShape(16.dp)
                    ) else Modifier.border(
                        1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)
                    )
                )
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap             = previewBitmap.asImageBitmap(),
                    contentDescription = pack.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
                // 底部渐变遮罩，让名字更清晰
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xCC000000)
                                )
                            )
                        )
                )
            } else {
                // 没有图片时显示大 emoji 占位
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("\uD83C\uDFAD", fontSize = 40.sp)
                }
            }

            // 选中勾
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(AccentPurple),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint     = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // 卡片内底部角色名
            Text(
                text      = pack.name,
                color     = Color.White,
                fontSize  = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp, start = 6.dp, end = 6.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // 卡片下方描述
        Text(
            text      = pack.description,
            fontSize  = 11.sp,
            color     = if (isSelected)
                            AccentPurple.copy(alpha = 0.9f)
                        else
                            Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.width(120.dp),
            lineHeight = 15.sp
        )
    }
}

// ── 只有一套角色时的提示 ──────────────────────────────────────────────────────
@Composable
private fun OnlyOnePackHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("\uD83C\uDFAD", fontSize = 36.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                text     = "目前只有一套角色",
                color    = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text     = "在 CharacterPackRegistry 中注册更多角色包即可",
                color    = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
