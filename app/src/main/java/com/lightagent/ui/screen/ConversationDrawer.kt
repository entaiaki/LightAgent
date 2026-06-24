package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ConversationDrawer(
    conversations         : List<ConversationEntity>,
    currentConversationId : String?,
    onSelectConversation  : (ConversationEntity) -> Unit,
    onNewConversation     : () -> Unit,
    onDeleteConversation  : (ConversationEntity) -> Unit,
    onOpenReminders       : () -> Unit,
    onOpenSettings        : () -> Unit,
    onOpenMusic           : () -> Unit,
    modifier              : Modifier = Modifier
) {
    // 抽屉整体：玻璃态背景 + 紫色渐变侧边光
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(
                Brush.verticalGradient(
                    0f   to Color(0xCC1A0A2E),
                    0.5f to Color(0xBB0D1020),
                    1f   to Color(0xCC0A0E1A)
                )
            )
    ) {
        // 侧边紫色光晕装饰
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(120.dp)
                .offset(x = 40.dp, y = (-20).dp)
                .blur(50.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentPurple.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 20.dp)
        ) {

            // ── 顶部标题 ──────────────────────────────────────────────────
            Text(
                text       = "✨ 会话",
                color      = TextPrimary,
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(4.dp))

            // ── 新建会话按钮 ──────────────────────────────────────────────
            NewConversationButton(onClick = onNewConversation)

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color     = GlassBorder,
                modifier  = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            // ── 会话列表（stagger 弹入）──────────────────────────────────
            LazyColumn(
                modifier       = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    items = conversations,
                    key   = { _, c -> c.id }
                ) { index, conv ->
                    StaggeredDrawerItem(index = index) {
                        ConversationItem(
                            conv       = conv,
                            isSelected = conv.id == currentConversationId,
                            onSelect   = { onSelectConversation(conv) },
                            onDelete   = { onDeleteConversation(conv) }
                        )
                    }
                }
            }

            // ── 底部工具区 ────────────────────────────────────────────────
            HorizontalDivider(
                color    = GlassBorder,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            DrawerBottomItem(
                icon    = Icons.Default.MusicNote,
                label   = "音乐播放器",
                onClick = onOpenMusic,
                tint    = AccentPurple
            )
            DrawerBottomItem(
                icon    = Icons.Default.Notifications,
                label   = "提醒事项",
                onClick = onOpenReminders
            )
            DrawerBottomItem(
                icon    = Icons.Default.Settings,
                label   = "模型设置",
                onClick = onOpenSettings,
                tint    = AccentPurple
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── 新建会话按钮：hover 时发光 ────────────────────────────────────────────────
@Composable
private fun NewConversationButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }

    val btnScale by animateFloatAsState(
        targetValue   = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "newBtnScale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .graphicsLayer { scaleX = btnScale; scaleY = btnScale },
        shape          = RoundedCornerShape(14.dp),
        color          = AccentPurple.copy(alpha = 0.18f),
        border         = BorderStroke(
            1.dp, AccentPurple.copy(alpha = 0.4f)
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint               = AccentPurple,
                modifier           = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text       = "新建会话",
                color      = AccentPurple,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Stagger 进场包装器 ────────────────────────────────────────────────────────
@Composable
private fun StaggeredDrawerItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    val translateX by animateFloatAsState(
        targetValue   = if (visible) 0f else -30f,
        animationSpec = spring(
            dampingRatio = AnimTokens.DrawerDamping,
            stiffness    = AnimTokens.DrawerStiffness
        ),
        label = "drawerX$index"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration),
        label         = "drawerA$index"
    )

    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(10) * AnimTokens.StaggerBase)
        visible = true
    }

    Box(modifier = Modifier.graphicsLayer {
        translationX = translateX
        this.alpha   = alpha
    }) {
        content()
    }
}

// ── 单个会话项 ────────────────────────────────────────────────────────────────
@Composable
private fun ConversationItem(
    conv      : ConversationEntity,
    isSelected: Boolean,
    onSelect  : () -> Unit,
    onDelete  : () -> Unit
) {
    val bgAlpha by animateFloatAsState(
        targetValue   = if (isSelected) 1f else 0f,
        animationSpec = tween(AnimTokens.SelectionDuration),
        label         = "convBg"
    )
    // 选中时左侧亮条宽度
    val indicatorWidth by animateDpAsState(
        targetValue   = if (isSelected) 3.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "indicator"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentPurple.copy(alpha = bgAlpha * 0.18f))
            .clickable { onSelect() }
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧选中指示条
        Box(
            modifier = Modifier
                .width(indicatorWidth)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AccentPurple)
        )

        Spacer(Modifier.width(10.dp))

        Text(
            text     = conv.title,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
            color    = if (isSelected) AccentPurple else TextSecondary,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )

        IconButton(
            onClick  = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                modifier           = Modifier.size(15.dp),
                tint               = TextSecondary.copy(alpha = 0.5f)
            )
        }
    }
}

// ── 底部工具栏条目 ────────────────────────────────────────────────────────────
@Composable
private fun DrawerBottomItem(
    icon   : ImageVector,
    label  : String,
    onClick: () -> Unit,
    tint   : Color = TextSecondary
) {
    var pressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "bottomItemScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable {
                pressed = true
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = tint,
            modifier           = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, color = tint, fontSize = 14.sp)
    }
}
