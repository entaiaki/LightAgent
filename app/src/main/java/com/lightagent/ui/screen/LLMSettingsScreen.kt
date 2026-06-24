package com.lightagent.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.ui.LLMSettingsViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMSettingsScreen(
    onBack            : () -> Unit,
    settingsViewModel : LLMSettingsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f   to Color(0xFF0A0E1A),
                    0.5f to Color(0xFF1A0A2E),
                    1f   to Color(0xFF0A0E1A)
                )
            )
    ) {
        // 左上角装饰光晕
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(160.dp)
                .offset(x = (-40).dp, y = (-20).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentBlue.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "⚙️ 模型设置",
                            color      = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        AnimatedBackButton(onClick = onBack)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 每个设置项用 stagger 弹入
                SettingsItem(index = 0) {
                    SettingsTextField(
                        label    = "API Key",
                        value    = settings.apiKey,
                        onChange = { settingsViewModel.updateApiKey(it) },
                        hint     = "sk-..."
                    )
                }
                SettingsItem(index = 1) {
                    SettingsTextField(
                        label    = "Base URL",
                        value    = settings.baseUrl,
                        onChange = { settingsViewModel.updateBaseUrl(it) },
                        hint     = "https://api.openai.com/v1"
                    )
                }
                SettingsItem(index = 2) {
                    SettingsTextField(
                        label    = "模型名称",
                        value    = settings.modelName,
                        onChange = { settingsViewModel.updateModelName(it) },
                        hint     = "gpt-4o"
                    )
                }
                SettingsItem(index = 3) {
                    SettingsSlider(
                        label    = "Temperature",
                        value    = settings.temperature,
                        range    = 0f..2f,
                        onChange = { settingsViewModel.updateTemperature(it) }
                    )
                }
                SettingsItem(index = 4) {
                    SettingsSlider(
                        label    = "Max Tokens",
                        value    = settings.maxTokens.toFloat(),
                        range    = 256f..8192f,
                        onChange = { settingsViewModel.updateMaxTokens(it.toInt()) },
                        isInt    = true
                    )
                }
                SettingsItem(index = 5) {
                    SettingsSwitch(
                        label    = "流式输出（Stream）",
                        checked  = settings.stream,
                        onChange = { settingsViewModel.updateStream(it) }
                    )
                }
                SettingsItem(index = 6) {
                    SettingsSwitch(
                        label    = "记忆上下文",
                        checked  = settings.contextEnabled,
                        onChange = { settingsViewModel.updateContext(it) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 保存按钮
                SettingsItem(index = 7) {
                    SaveButton(onClick = { settingsViewModel.save() })
                }
            }
        }
    }
}

// ── stagger 进场包装 ──────────────────────────────────────────────────────────
@Composable
private fun SettingsItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    val translateY by animateFloatAsState(
        targetValue   = if (visible) 0f else 22f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "settingsY$index"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration),
        label         = "settingsA$index"
    )

    LaunchedEffect(Unit) {
        delay(index * AnimTokens.StaggerBase + 80L)
        visible = true
    }

    Box(modifier = Modifier.graphicsLayer {
        translationY = translateY
        this.alpha   = alpha
    }) {
        content()
    }
}

// ── 文本输入设置项 ────────────────────────────────────────────────────────────
@Composable
private fun SettingsTextField(
    label    : String,
    value    : String,
    onChange : (String) -> Unit,
    hint     : String = ""
) {
    val borderAlpha by animateFloatAsState(
        targetValue   = if (value.isNotBlank()) 0.75f else 0.25f,
        animationSpec = tween(200),
        label         = "tfBorder_$label"
    )

    OutlinedTextField(
        value         = value,
        onValueChange = onChange,
        label         = { Text(label, color = TextSecondary, fontSize = 13.sp) },
        placeholder   = { Text(hint, color = TextHint, fontSize = 13.sp) },
        modifier      = Modifier.fillMaxWidth(),
        singleLine    = true,
        shape         = RoundedCornerShape(14.dp),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor    = AccentPurple.copy(alpha = borderAlpha),
            unfocusedBorderColor  = GlassBorder.copy(alpha = borderAlpha),
            focusedTextColor      = TextPrimary,
            unfocusedTextColor    = TextPrimary,
            focusedContainerColor   = GlassBg,
            unfocusedContainerColor = GlassBg,
            cursorColor           = AccentPurple
        )
    )
}

// ── Slider 设置项 ─────────────────────────────────────────────────────────────
@Composable
private fun SettingsSlider(
    label    : String,
    value    : Float,
    range    : ClosedFloatingPointRange<Float>,
    onChange : (Float) -> Unit,
    isInt    : Boolean = false
) {
    Surface(
        shape          = RoundedCornerShape(14.dp),
        color          = GlassBg,
        tonalElevation = 2.dp,
        modifier       = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(label, color = TextPrimary, fontSize = 14.sp)
                Text(
                    text       = if (isInt) value.toInt().toString()
                                 else "%.2f".format(value),
                    color      = AccentPurple,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            Slider(
                value        = value,
                onValueChange = onChange,
                valueRange   = range,
                colors       = SliderDefaults.colors(
                    thumbColor         = AccentPurple,
                    activeTrackColor   = AccentPurple,
                    inactiveTrackColor = GlassBorder
                )
            )
        }
    }
}

// ── Switch 设置项 ─────────────────────────────────────────────────────────────
@Composable
private fun SettingsSwitch(
    label    : String,
    checked  : Boolean,
    onChange : (Boolean) -> Unit
) {
    // 整行点击时 scale 弹簧反馈
    val rowScale by animateFloatAsState(
        targetValue   = 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "switchRow_$label"
    )

    Surface(
        shape          = RoundedCornerShape(14.dp),
        color          = GlassBg,
        tonalElevation = 2.dp,
        modifier       = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextPrimary, fontSize = 14.sp)
            Switch(
                checked         = checked,
                onCheckedChange = onChange,
                colors          = SwitchDefaults.colors(
                    checkedThumbColor       = Color.White,
                    checkedTrackColor       = AccentPurple,
                    uncheckedThumbColor     = TextSecondary,
                    uncheckedTrackColor     = GlassBorder
                )
            )
        }
    }
}

// ── 返回按钮（弹性动画）────────────────────────────────────────────────────────
@Composable
private fun AnimatedBackButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val btnScale by animateFloatAsState(
        targetValue   = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "backScale"
    )
    IconButton(
        onClick  = {
            pressed = true
            onClick()
        },
        modifier = Modifier.graphicsLayer {
            scaleX = btnScale
            scaleY = btnScale
        }
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint               = TextPrimary
        )
    }
}

// ── 保存按钮：点击弹簧 + 颜色脉冲 ───────────────────────────────────────────
@Composable
private fun SaveButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    var saved   by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "saveScale"
    )
    val btnColor by animateColorAsState(
        targetValue   = if (saved) StatusTool else AccentPurple,
        animationSpec = tween(300),
        label         = "saveColor"
    )

    LaunchedEffect(pressed) {
        if (pressed) { delay(150); pressed = false }
    }
    LaunchedEffect(saved) {
        if (saved) { delay(1500); saved = false }
    }

    Button(
        onClick = {
            pressed = true
            saved   = true
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape  = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = btnColor)
    ) {
        Text(
            text       = if (saved) "✅ 已保存" else "保存设置",
            fontSize   = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White
        )
    }
}
