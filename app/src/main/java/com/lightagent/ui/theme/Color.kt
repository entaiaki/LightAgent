package com.lightagent.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.Color

val DeepNavy          = Color(0xFF0A0E1A)
val GradientStart     = Color(0xFF0A0E1A)
val GradientMid       = Color(0xFF1A0A2E)
val GradientEnd       = Color(0xFF0D1B2A)
val GlassBg           = Color(0x33FFFFFF)
val GlassBorder       = Color(0x22FFFFFF)
val UserBubble        = Color(0xFF6C63FF)
val AssistantBubble   = Color(0x44FFFFFF)
val TextPrimary       = Color(0xFFEFEFFF)
val TextSecondary     = Color(0xAAB0B8FF)
val TextHint          = Color(0x88FFFFFF)
val StatusThinking    = Color(0xFFFFD166)
val StatusTool        = Color(0xFF06D6A0)
val StatusIdle        = Color(0xFF8EC5FC)
val AccentPurple      = Color(0xFF9B7FFF)
val AccentBlue        = Color(0xFF5EAEFF)
val Emerald           = Color(0xFF50C878)

object AnimTokens {
    // ── stagger / fade ──────────────────────────────────────────────────────
    const val StaggerBase       = 55L    // 抽屉列表每项延迟 (ms)
    const val MessageStagger    = 25L    // 消息列表每项延迟 (ms)，比抽屉快
    const val MessageSlideInY   = 28f    // 新消息弹入起始偏移 px
    const val FadeDuration      = 220    // 通用淡变时长 (ms)
    const val SelectionDuration = 180    // 选中态过渡时长 (ms)

    // ── 弹簧：气泡 / 按钮（欠阻尼，有轻微回弹）────────────────────────────
    const val BouncyDamping     = Spring.DampingRatioMediumBouncy  // 0.5f
    const val BouncyStiffness   = Spring.StiffnessMedium           // 400f

    // ── 弹簧：快速响应（按钮缩放，不要过多回弹）────────────────────────────
    const val SnapDamping       = Spring.DampingRatioLowBouncy     // 0.75f
    const val SnapStiffness     = Spring.StiffnessHigh             // 1000f

    // ── 弹簧：抽屉 stagger（稍微重一点）────────────────────────────────────
    const val DrawerDamping     = Spring.DampingRatioMediumBouncy
    const val DrawerStiffness   = Spring.StiffnessMediumLow        // 200f，慢而有弹性
}
