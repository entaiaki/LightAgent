package com.lightagent.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterView

@Composable
fun OverlayCharacter(
    emotion : CharacterEmotion
) {
    // ── 持续呼吸浮动 ──────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "overlay_anim")

    val floatY by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = -6f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay_floatY"
    )

    // ── 轻微左右摇摆（增加生命感）────────────────────────────────────────
    val swayX by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3200, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay_swayX"
    )

    // ── 轻微旋转（配合摇摆，更自然）──────────────────────────────────────
    val swayRot by infiniteTransition.animateFloat(
        initialValue  = -0.8f,
        targetValue   = 0.8f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3200, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay_swayRot"
    )

    Box(
        modifier = Modifier
            .size(width = 90.dp, height = 160.dp)
            .graphicsLayer {
                translationY = floatY
                translationX = swayX
                rotationZ    = swayRot
            }
    ) {
        // ── 情绪切换：淡入淡出 + 轻微上移 ───────────────────────────────
        AnimatedContent(
            targetState    = emotion,
            transitionSpec = {
                (fadeIn(tween(300)) + slideInVertically(
                    animationSpec  = tween(300, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 10 }
                )).togetherWith(
                    fadeOut(tween(200))
                )
            },
            label = "overlay_emotion"
        ) { currentEmotion ->
            CharacterView(
                emotion   = currentEmotion,
                modifier  = Modifier.fillMaxSize(),
                isTalking = false
            )
        }

        // ── 底部名称标签 ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text      = "天爱星",
                color     = Color.White.copy(alpha = 0.8f),
                fontSize  = 10.sp,
                textAlign = TextAlign.Center
            )
        }

        // ── 情绪角标（右上角）───────────────────────────────────────────
        EmotionBadge(
            emotion  = emotion,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
        )
    }
}

// ── 情绪角标（16 种情绪）─────────────────────────────────────────────────────
@Composable
private fun EmotionBadge(
    emotion  : CharacterEmotion,
    modifier : Modifier = Modifier
) {
    val emoji = when (emotion) {
        CharacterEmotion.IDLE       -> null
        CharacterEmotion.HAPPY      -> "😊"
        CharacterEmotion.THINKING   -> "🤔"
        CharacterEmotion.SAD        -> "😢"
        CharacterEmotion.ANGRY      -> "😤"
        CharacterEmotion.SLEEPING   -> "😴"
        CharacterEmotion.SOBBING    -> "🥺"
        CharacterEmotion.CRYING     -> "😭"
        CharacterEmotion.DEPRESSED  -> "😞"
        CharacterEmotion.DISTRESSED -> "😰"
        CharacterEmotion.DROWSY     -> "😪"
        CharacterEmotion.SWEATING   -> "😓"
        CharacterEmotion.PAINED     -> "😖"
        CharacterEmotion.DISGUSTED  -> "🤢"
        CharacterEmotion.SERIOUS    -> "😐"
        CharacterEmotion.WINK       -> "😉"
    }

    AnimatedVisibility(
        visible = emoji != null,
        enter   = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(tween(200)),
        exit    = scaleOut(tween(150)) + fadeOut(tween(150)),
        modifier = modifier
    ) {
        Text(
            text      = emoji ?: "",
            fontSize  = 12.sp,
            textAlign = TextAlign.Center,
            modifier  = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        )
    }
}
