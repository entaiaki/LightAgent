package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun AnimatedMessageBubble(
    message    : ChatMessage,
    index      : Int,
    isLatest   : Boolean = false,
    isStreaming : Boolean = false
) {
    val isUser = message.role == "user"

    // ── 1. 弹入动画 ──────────────────────────────────────────────────────────
    var appeared by remember { mutableStateOf(false) }

    val translateY by animateFloatAsState(
        targetValue   = if (appeared) 0f else AnimTokens.MessageSlideInY,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "bubbleY"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (appeared) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration),
        label         = "bubbleAlpha"
    )
    val scale by animateFloatAsState(
        targetValue   = if (appeared) 1f else 0.90f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "bubbleScale"
    )

    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(6) * AnimTokens.MessageStagger))
        appeared = true
    }

    // ── 2. 打字机效果（仅最新 assistant 消息）───────────────────────────────
    var displayedText by remember(message.content) {
        mutableStateOf(if (isLatest) "" else message.content)
    }

    LaunchedEffect(message.content, isLatest) {
        if (!isLatest) { displayedText = message.content; return@LaunchedEffect }
        displayedText = ""
        message.content.forEach { char ->
            displayedText += char
            delay(when (char) {
                '。','！','？','.','!','?' -> 55L
                '，',',','；',';'          -> 20L
                '\n'                       -> 80L
                else                       -> 10L
            })
        }
    }

    // ── 布局 ─────────────────────────────────────────────────────────────────
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY    = translateY
                this.alpha      = alpha
                scaleX          = scale
                scaleY          = scale
                transformOrigin = if (isUser)
                    TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f)
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart    = if (isUser) 18.dp else 4.dp,
                topEnd      = if (isUser) 4.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd   = 18.dp
            ),
            color          = if (isUser) UserBubble else AssistantBubble,
            tonalElevation = if (isUser) 0.dp else 2.dp,
            modifier       = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text  = if (isLatest && !isStreaming) displayedText else message.content,
                    color = if (isUser) Color.White else TextPrimary,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
                )
                if (isStreaming && isLatest) {
                    Spacer(Modifier.height(6.dp))
                    ThinkingDots()
                }
            }
        }
    }
}

// ── 三点弹跳（上下跳动 + 缩放），可由 ChatScreen 直接引用 ──────────────────
@Composable
fun ThinkingDots(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "dots")

    val offsets = (0..2).map { i ->
        inf.animateFloat(
            initialValue  = 0f,
            targetValue   = -9f,
            animationSpec = infiniteRepeatable(
                animation  = tween(420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 140)
            ),
            label = "dot$i"
        )
    }

    val scales = (0..2).map { i ->
        inf.animateFloat(
            initialValue  = 1f,
            targetValue   = 1.35f,
            animationSpec = infiniteRepeatable(
                animation  = tween(420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 140)
            ),
            label = "dotScale$i"
        )
    }

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        offsets.forEachIndexed { i, offset ->
            Surface(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        translationY = offset.value
                        scaleX       = scales[i].value
                        scaleY       = scales[i].value
                    },
                shape = RoundedCornerShape(50),
                color = AccentPurple.copy(alpha = 0.85f)
            ) {}
        }
    }
}
