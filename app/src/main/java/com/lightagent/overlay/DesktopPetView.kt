package com.lightagent.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lightagent.character.CharacterStateHolder
import com.lightagent.ui.CharacterBackground

/**
 * 桌宠悬浮窗 UI — v4.3.4
 *
 * - 呼吸浮动 + 摇摆动画
 * - 右上角 X：Compose 独立 touch，消费事件防穿透到 Service 层
 * - 身体点击由 Service 层 GestureDetector 处理 → InteractionBridge
 * - 从 CharacterStateHolder 读取全局角色+情绪
 */
@Composable
fun DesktopPetView() {
    val currentEmotion = CharacterStateHolder.emotion
    val currentRole    = CharacterStateHolder.role

    // ── 呼吸浮动 ──────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "pet_anim")

    val floatY by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = -6f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pet_floatY"
    )

    val swayX by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3200, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pet_swayX"
    )

    val swayRot by infiniteTransition.animateFloat(
        initialValue  = -0.8f,
        targetValue   = 0.8f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3200, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pet_swayRot"
    )

    Box(
        modifier = Modifier
            .size(width = 90.dp, height = 160.dp)
    ) {
        // ── 角色图 + 动画 ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = floatY
                    translationX = swayX
                    rotationZ    = swayRot
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
        ) {
            AnimatedContent(
                targetState    = currentEmotion,
                transitionSpec = {
                    fadeIn(tween(300)) + slideInVertically(
                        initialOffsetY = { it / 8 },
                        animationSpec  = tween(300)
                    ) togetherWith
                    fadeOut(tween(250)) + slideOutVertically(
                        targetOffsetY = { -it / 8 },
                        animationSpec  = tween(250)
                    )
                },
                label = "pet_emotion"
            ) { emotion ->
                CharacterBackground(
                    role         = currentRole,
                    emotion      = emotion,
                    contentScale = ContentScale.Fit
                )
            }
        }

        // ── 右上角 X：独立 touch，事件由 Service 层按坐标分流 ──────
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(26.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭桌宠",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
