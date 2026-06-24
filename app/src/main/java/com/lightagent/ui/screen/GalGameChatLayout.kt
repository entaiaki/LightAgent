package com.lightagent.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.LocalScreenMode
import com.lightagent.ScreenMode
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterPack
import com.lightagent.character.CharacterView
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.theme.*

// ══════════════════════════════════════════════════════════════════════════════
// Galgame 核心布局 v4.1
//
// 层次结构（从底到顶）：
// 1. 纯色渐变打底（深夜蓝紫，不铺背景图）
// 2. 立绘充满全屏（ContentScale.Fit，底部对齐）← 立绘就是视觉主角
// 3. 底部渐变蒙层（确保消息区域可读）
// 4. UI层（顶栏 + 消息列表 + 输入栏）
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalGameChatLayout(
    messages        : List<ChatMessage>,
    isLoading       : Boolean,
    inputText       : String,
    currentEmotion  : CharacterEmotion,
    isTalking       : Boolean,
    characterPack   : CharacterPack,
    conversations   : List<ConversationEntity>,
    currentConvId   : String?,
    listState       : LazyListState,
    onOpenDrawer    : () -> Unit,
    onOpenBgSheet   : () -> Unit,
    onOpenCharSheet : () -> Unit,
    onTextChange    : (String) -> Unit,
    onSend          : () -> Unit,
    ttsEnabled      : Boolean = true,
    onToggleTts     : () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // ── 第一层：纯色渐变打底 ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0D0D1A),
                            Color(0xFF1A1A3E),
                            Color(0xFF0D0D1A)
                        )
                    )
                )
        )

        // ── 第二层：立绘（视觉主角，充满全屏）────────────────────────────
        GalBackgroundCharacter(
            emotion       = currentEmotion,
            isTalking     = isTalking,
            characterPack = characterPack
        )

        // ── 第三层：底部渐变蒙层（下半遮罩，保证气泡可读）────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.35f to Color(0x18000000),
                        0.55f to Color(0x77000000),
                        0.72f to Color(0xBB000000),
                        1.00f to Color(0xF2000000)
                    )
                )
        )

        // ── 第四层：UI层 ──────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {

            ChatTopBar(
                conversations   = conversations,
                currentConvId   = currentConvId,
                onOpenDrawer    = onOpenDrawer,
                onOpenBgSheet   = onOpenBgSheet,
                onOpenCharSheet = onOpenCharSheet
            )

            ChatMessageList(
                messages  = messages,
                isLoading = isLoading,
                listState = listState,
                modifier  = Modifier.weight(1f)
            )

			GalInputBar(
				inputText    = inputText,
				onTextChange = onTextChange,
				onSend       = onSend,
				ttsEnabled   = ttsEnabled,
				onToggleTts  = onToggleTts
			)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 立绘层
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalBackgroundCharacter(
    emotion       : CharacterEmotion,
    isTalking     : Boolean,
    characterPack : CharacterPack
) {
    val screenMode = LocalScreenMode.current

    val infiniteTransition = rememberInfiniteTransition(label = "gal_char")

    // 呼吸浮动
    val floatY by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = if (isTalking) -8f else -4f,
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = if (isTalking) 500 else 2800,
                easing         = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gal_floatY"
    )

    // 说话时微微脉冲缩放
    val talkScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = if (isTalking) 1.008f else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = if (isTalking) 300 else 800,
                easing         = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gal_talkScale"
    )

    // 情绪切换 + 角色切换时淡入淡出
    AnimatedContent(
        targetState    = Pair(characterPack.id, emotion),
        transitionSpec = {
            (fadeIn(tween(380)) + slideInVertically(
                animationSpec  = tween(380, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 10 }
            )).togetherWith(fadeOut(tween(280)))
        },
        modifier = Modifier.fillMaxSize(),
        label    = "gal_emotion_anim"
    ) { (_, targetEmotion) ->
        Box(
            modifier         = Modifier.fillMaxSize(),
            contentAlignment = if (screenMode == ScreenMode.OUTER_COMPACT)
                                   Alignment.Center
                               else
                                   Alignment.BottomCenter
        ) {
            CharacterView(
                emotion      = targetEmotion,
                pack         = characterPack,
                isTalking    = isTalking,
                modifier     = Modifier
                    // 外屏 → 窄舞台窗口；内屏 → 全屏
                    .then(
                        if (screenMode == ScreenMode.OUTER_COMPACT)
                            Modifier.fillMaxHeight().width(280.dp)
                        else
                            Modifier.fillMaxSize()
                    )
                    // 外屏 → 底部留 padding 避免被输入框遮挡
                    .padding(bottom = if (screenMode == ScreenMode.OUTER_COMPACT) 64.dp else 0.dp)
                    .graphicsLayer {
                        translationY = floatY
                        scaleX       = talkScale
                        scaleY       = talkScale
                    },
                contentScale = if (screenMode == ScreenMode.OUTER_COMPACT)
                                   ContentScale.Crop
                               else
                                   ContentScale.Fit
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 顶栏
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    conversations   : List<ConversationEntity>,
    currentConvId   : String?,
    onOpenDrawer    : () -> Unit,
    onOpenBgSheet   : () -> Unit,
    onOpenCharSheet : () -> Unit
) {
    val convTitle = conversations.find { it.id == currentConvId }?.title ?: "天爱星"

    TopAppBar(
        title = {
            Text(
                text  = convTitle,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium
            )
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector        = Icons.Default.Menu,
                    contentDescription = "菜单",
                    tint               = TextPrimary
                )
            }
        },
        actions = {
            // 角色切换
            IconButton(onClick = onOpenCharSheet) {
                Icon(
                    imageVector        = Icons.Default.Person,
                    contentDescription = "切换角色",
                    tint               = TextPrimary
                )
            }
            // 背景切换（保留，以备后用）
            IconButton(onClick = onOpenBgSheet) {
                Icon(
                    imageVector        = Icons.Default.Image,
                    contentDescription = "背景",
                    tint               = TextPrimary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 消息气泡
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalMessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color  = if (isUser)
                        Color(0xCC6B4EFF)   // 用户：紫色半透明
                    else
                        Color(0xCC1E1E3A),  // AI：深蓝半透明
                    shape  = RoundedCornerShape(
                        topStart    = if (isUser) 18.dp else 4.dp,
                        topEnd      = if (isUser) 4.dp  else 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd   = 18.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text  = message.content,
                color = Color.White,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 消息列表
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ChatMessageList(
    messages  : List<ChatMessage>,
    isLoading : Boolean,
    listState : LazyListState,
    modifier  : Modifier = Modifier
) {
    LazyColumn(
        state               = listState,
        modifier            = modifier.fillMaxWidth(),
        contentPadding      = PaddingValues(
            start  = 12.dp,
            end    = 12.dp,
            top    = 56.dp,
            bottom = 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(messages) { _, message ->
            GalMessageBubble(message = message)
        }
        if (isLoading) {
            item {
                GalTypingIndicator()
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 正在输入指示器
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalTypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier              = Modifier.padding(start = 8.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue  = 0.3f,
                targetValue   = 1f,
                animationSpec = infiniteRepeatable(
                    animation         = tween(600),
                    repeatMode        = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 200)
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(Color.White, RoundedCornerShape(3.dp))
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 输入栏
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GalInputBar(
    inputText    : String,
    onTextChange : (String) -> Unit,
    onSend       : () -> Unit,
    ttsEnabled   : Boolean = true,
    onToggleTts  : () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // TTS 语音开关
        IconToggleButton(
            checked = ttsEnabled,
            onCheckedChange = { onToggleTts() },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = if (ttsEnabled) "关闭语音播报" else "开启语音播报",
                tint = if (ttsEnabled) AccentPurple else TextHint,
                modifier = Modifier.size(22.dp)
            )
        }

        OutlinedTextField(
            value       = inputText,
            onValueChange = onTextChange,
            modifier    = Modifier.weight(1f),
            placeholder = {
                Text(
                    text  = "说点什么…",
                    color = Color.White.copy(alpha = 0.4f),
                    style = TextStyle(fontSize = 14.sp)
                )
            },
            textStyle   = TextStyle(color = Color.White, fontSize = 14.sp),
            colors      = OutlinedTextFieldDefaults.colors(
                focusedBorderColor     = AccentPurple.copy(alpha = 0.8f),
                unfocusedBorderColor   = Color.White.copy(alpha = 0.2f),
                cursorColor            = AccentPurple,
                focusedContainerColor  = Color(0x44000000),
                unfocusedContainerColor = Color(0x33000000)
            ),
            shape       = RoundedCornerShape(24.dp),
            maxLines    = 4
        )

        // 发送按钮
        val canSend = inputText.isNotBlank()
        IconButton(
            onClick  = { if (canSend) onSend() },
            modifier = Modifier
                .size(48.dp)
                .background(
                    color  = if (canSend) AccentPurple else Color.White.copy(alpha = 0.15f),
                    shape  = RoundedCornerShape(24.dp)
                )
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint               = Color.White,
                modifier           = Modifier.size(20.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 折叠感知容器 — v4.3.6
//
// 外屏（OUTER_COMPACT）→ 窄舞台窗口，左右裁剪，人物居中
// 内屏（INNER_EXPANDED）→ 全屏布局，pass-through
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun FoldAwareContainer(
    modifier: Modifier = Modifier,
    content : @Composable () -> Unit
) {
    val mode = LocalScreenMode.current

    when (mode) {

        ScreenMode.OUTER_COMPACT -> {
            // 外屏 → 限制中间视觉窗口，左右裁剪背景
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(320.dp),
                    contentAlignment = Alignment.Center
                ) {
                    content()
                }
            }
        }

        ScreenMode.INNER_EXPANDED,
        ScreenMode.UNKNOWN -> {
            Box(modifier = modifier.fillMaxSize()) {
                content()
            }
        }
    }
}
