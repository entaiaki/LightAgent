package com.lightagent.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

// ── 粒子数据（启动时随机生成一次，不随重组变化）──────────────────────────────
private data class SplashParticle(
    val normX  : Float,  // 归一化坐标 0-1
    val normY  : Float,
    val radius : Float,
    val baseAlpha: Float,
    val speed  : Float,
    val angle  : Float   // 运动方向（弧度）
)

@Composable
fun SplashScreen(onFinished: () -> Unit) {

    // ── 粒子：只初始化一次 ───────────────────────────────────────────────────
    val particles = remember {
        List(70) {
            SplashParticle(
                normX     = Random.nextFloat(),
                normY     = Random.nextFloat(),
                radius    = Random.nextFloat() * 2.5f + 0.8f,
                baseAlpha = Random.nextFloat() * 0.55f + 0.1f,
                speed     = Random.nextFloat() * 0.00025f + 0.00008f,
                angle     = Random.nextFloat() * 2f * PI.toFloat()
            )
        }
    }

    // ── 时间轴驱动粒子漂移 ───────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "splash_inf")
    val particleTime by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 10_000f,
        animationSpec = infiniteRepeatable(tween(100_000, easing = LinearEasing)),
        label         = "pTime"
    )

    // ── 呼吸光圈：大圆缩放 + alpha ───────────────────────────────────────────
    val pulseScale by infiniteTransition.animateFloat(
        initialValue  = 0.82f,
        targetValue   = 1.18f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.12f,
        targetValue   = 0.40f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label         = "pulseA"
    )

    // ── 整体淡入淡出 ─────────────────────────────────────────────────────────
    val screenAlpha = remember { Animatable(0f) }

    // ── 逐字文本状态 ─────────────────────────────────────────────────────────
    val title    = "✨ 天爱星 Agent"
    val subtitle = "你的轻量 AI 助手"
    var titleVisible    by remember { mutableIntStateOf(0) }
    var subtitleVisible by remember { mutableIntStateOf(0) }
    var showCursor      by remember { mutableStateOf(true) }

    // ── 光标闪烁 ─────────────────────────────────────────────────────────────
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 0f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label         = "cursor"
    )

    // ── 主序列 ───────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        screenAlpha.animateTo(1f, tween(700))                 // 淡入

        // 标题逐字
        repeat(title.length) { i ->
            titleVisible = i + 1
            delay(if (title[i] == ' ') 40L else 65L)
        }
        delay(180)

        // 副标题逐字
        repeat(subtitle.length) { i ->
            subtitleVisible = i + 1
            delay(55L)
        }
        showCursor = false                                    // 打完后隐藏光标
        delay(900)

        screenAlpha.animateTo(0f, tween(500))                // 淡出
        onFinished()
    }

    // ─── 渲染 ────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(screenAlpha.value)
            .background(
                Brush.radialGradient(
                    0f   to Color(0xFF1A0A2E),
                    0.6f to Color(0xFF0D1020),
                    1f   to Color(0xFF0A0E1A)
                )
            ),
        contentAlignment = Alignment.Center
    ) {

        // ── 粒子层 ───────────────────────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            particles.forEach { p ->
                val x = ((p.normX + cos(p.angle) * p.speed * particleTime) % 1f + 1f) % 1f
                val y = ((p.normY + sin(p.angle) * p.speed * particleTime) % 1f + 1f) % 1f
                // 靠近中心的粒子更亮（营造景深感）
                val dist = sqrt((x - 0.5f).pow(2) + (y - 0.5f).pow(2))
                val alphaFactor = 1f - (dist * 0.8f).coerceIn(0f, 0.7f)
                drawCircle(
                    color  = AccentPurple.copy(alpha = p.baseAlpha * alphaFactor),
                    radius = p.radius,
                    center = Offset(x * size.width, y * size.height)
                )
            }
        }

        // ── 呼吸光圈（模糊大圆做光晕）───────────────────────────────────────
        Canvas(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                .blur(48.dp)
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF6C63FF).copy(alpha = pulseAlpha),
                        Color(0xFF5EAEFF).copy(alpha = pulseAlpha * 0.4f),
                        Color.Transparent
                    )
                )
            )
        }

        // ── 第二层更小的光圈（增加层次感）────────────────────────────────────
        Canvas(
            modifier = Modifier
                .size(140.dp)
                .graphicsLayer {
                    scaleX = 2f - pulseScale  // 反相，一大一小交替
                    scaleY = 2f - pulseScale
                }
                .blur(28.dp)
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AccentBlue.copy(alpha = pulseAlpha * 0.6f),
                        Color.Transparent
                    )
                )
            )
        }

        // ── 文字层 ───────────────────────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 标题：逐字显示 + 字母间距
            Text(
                text          = title.take(titleVisible),
                color         = TextPrimary,
                fontSize      = 36.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            // 副标题：逐字 + 闪烁光标
            if (subtitleVisible > 0 || titleVisible == title.length) {
                Text(
                    text = buildAnnotatedString {
                        append(subtitle.take(subtitleVisible))
                        if (showCursor && subtitleVisible < subtitle.length) {
                            withStyle(SpanStyle(
                                color = AccentPurple.copy(alpha = cursorAlpha)
                            )) { append("▋") }
                        }
                    },
                    color         = TextSecondary,
                    fontSize      = 15.sp,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}
