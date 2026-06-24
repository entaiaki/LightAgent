### 9.4 LLMSettingsScreen.kt

```kotlin
package com.lightagent.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.llm.LLMProvider
import com.lightagent.ui.LLMSettingsViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMSettingsScreen(
    viewModel: LLMSettingsViewModel,
    onBack: () -> Unit
) {
    val settings = viewModel.settings.collectAsState().value
    var saved by remember { mutableStateOf(false) }
    val fadeAlpha by animateFloatAsState(1f, animationSpec = tween(500))

    if (saved) {
        LaunchedEffect(saved) { delay(1500); saved = false }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(
                colors = listOf(Color(0xFF0D0D1A), Color(0xFF1A103D))
            ))
            .alpha(fadeAlpha)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("大模型设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent, titleContentColor = Color.White
                )
            )

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StaggeredSettingsCard(index = 0) {
                    SettingsCardTitle("大模型提供商")
                    LLMProvider.entries.forEach { provider ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (settings.provider == provider)
                                    Color(0xFF7C4DFF).copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { viewModel.updateProvider(provider) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.provider == provider,
                                onClick = { viewModel.updateProvider(provider) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF7C4DFF))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(provider.displayName, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }

                StaggeredSettingsCard(index = 1) {
                    SettingsCardTitle("API Key")
                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { viewModel.updateApiKey(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入 API Key", color = Color.White.copy(alpha = 0.3f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF7C4DFF),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF7C4DFF)
                        ), singleLine = true
                    )
                }

                StaggeredSettingsCard(index = 2) {
                    SettingsCardTitle("模型")
                    OutlinedTextField(
                        value = settings.model,
                        onValueChange = { viewModel.updateModel(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(settings.provider.defaultModel,
                            color = Color.White.copy(alpha = 0.3f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF7C4DFF),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF7C4DFF)
                        ), singleLine = true
                    )
                }

                if (settings.provider == LLMProvider.CUSTOM) {
                    StaggeredSettingsCard(index = 3) {
                        SettingsCardTitle("自定义 API 地址")
                        OutlinedTextField(
                            value = settings.customUrl,
                            onValueChange = { viewModel.updateCustomUrl(it) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://api.example.com/v1/chat/completions",
                                color = Color.White.copy(alpha = 0.3f)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF7C4DFF),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF7C4DFF)
                            ), singleLine = true
                        )
                    }
                }

                StaggeredSettingsCard(index = 4) {
                    SettingsCardTitle("Temperature: ${"%.2f".format(settings.temperature)}")
                    Slider(value = settings.temperature,
                        onValueChange = { viewModel.updateTemperature(it) },
                        valueRange = 0f..2f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF7C4DFF), activeTrackColor = Color(0xFF7C4DFF))
                    )
                }

                StaggeredSettingsCard(index = 5) {
                    SettingsCardTitle("Max Tokens: ${settings.maxTokens}")
                    Slider(value = settings.maxTokens.toFloat(),
                        onValueChange = { viewModel.updateMaxTokens(it.toInt()) },
                        valueRange = 128f..8192f, steps = 32,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF7C4DFF), activeTrackColor = Color(0xFF7C4DFF))
                    )
                }

                StaggeredSettingsCard(index = 6) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(
                            indication = null, interactionSource = remember { MutableInteractionSource() }
                        ) { viewModel.updateStream(!settings.stream) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SettingsCardTitle("流式输出")
                        Switch(checked = settings.stream,
                            onCheckedChange = { viewModel.updateStream(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF7C4DFF))
                        )
                    }
                }

                StaggeredSettingsCard(index = 7) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(
                            indication = null, interactionSource = remember { MutableInteractionSource() }
                        ) { viewModel.updateContextEnabled(!settings.contextEnabled) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SettingsCardTitle("上下文记忆")
                        Switch(checked = settings.contextEnabled,
                            onCheckedChange = { viewModel.updateContextEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF7C4DFF))
                        )
                    }
                }

                val saveColor by animateColorAsState(
                    targetValue = if (saved) Color(0xFF4CAF50) else Color(0xFF7C4DFF),
                    animationSpec = tween(300)
                )
                Button(
                    onClick = { viewModel.save(); saved = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = saveColor),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (saved) "✅ 已保存" else "保存设置",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun StaggeredSettingsCard(index: Int, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(1f,
        animationSpec = tween(durationMillis = 400, delayMillis = index * 55))
    Card(
        modifier = Modifier.fillMaxWidth().alpha(alpha),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE61A1A2E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) { Box(modifier = Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun SettingsCardTitle(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}
```

### 9.5 BackgroundSettingsSheet.kt

```kotlin
package com.lightagent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.theme.AnimTokens

private val gradientPresets = listOf(
    "默认" to listOf(Color(0xFF0D0D1A), Color(0xFF1A103D)),
    "极光" to listOf(Color(0xFF001F3F), Color(0xFF003366), Color(0xFF001F3F)),
    "日落" to listOf(Color(0xFF2D1B69), Color(0xFFD32F2F)),
    "森林" to listOf(Color(0xFF0D1B2A), Color(0xFF1B4332)),
    "海洋" to listOf(Color(0xFF0A1929), Color(0xFF0D47A1)),
    "玫瑰" to listOf(Color(0xFF1A0A2E), Color(0xFF880E4F))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsSheet(backgroundVM: BackgroundViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = Color(0xFF141428),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.width(40.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
            }

            Text("背景设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            StaggeredSheetItem(0) {
                Text("渐变预设", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    gradientPresets.forEachIndexed { idx, (name, _) ->
                        GradientPresetCard(name, idx + 1, false) {}
                    }
                }
            }

            StaggeredSheetItem(1) {
                ActionRow("随机切换", Icons.Rounded.Shuffle) {
                    backgroundVM.randomBackground(); onDismiss()
                }
            }
            StaggeredSheetItem(2) {
                ActionRow("从相册选择", Icons.Rounded.Image) {}
            }
            StaggeredSheetItem(3) {
                ActionRow("恢复默认", Icons.Rounded.Refresh) {
                    backgroundVM.resetToDefault(); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun GradientPresetCard(name: String, index: Int, isSelected: Boolean, onClick: () -> Unit) {
    val borderAlpha by animateFloatAsState(if (isSelected) 0.9f else 0.3f, tween(300))
    val scale by animateFloatAsState(if (isSelected) 1.05f else 1f,
        spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness))

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            modifier = Modifier.size(72.dp, 100.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(RoundedCornerShape(12.dp))
                .border(1.5.dp, Color(0xFF7C4DFF).copy(alpha = borderAlpha), RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(gradientPresets[index - 1].second), RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.BottomEnd
        ) {
            Text("$index", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.padding(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(name, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}

@Composable
private fun ActionRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val bgAlpha by animateFloatAsState(if (isPressed) 0.15f else 0f, tween(150))
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f,
        spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness))

    Row(
        modifier = Modifier.fillMaxWidth().graphicsLayer(scaleX = scale, scaleY = scale)
            .background(Color.White.copy(alpha = bgAlpha), RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun StaggeredSheetItem(index: Int, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(1f, animationSpec = tween(400, delayMillis = index * 55 + 100))
    Box(modifier = Modifier.alpha(alpha)) { content() }
}
```

---

## 10. UI 组件层

### 10.1 AnimatedMessageBubble.kt

```kotlin
package com.lightagent.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.theme.AnimTokens

@Composable
fun AnimatedMessageBubble(message: ChatMessage, index: Int, isNew: Boolean = false) {
    val isUser = message.role == "user"
    val slideOffset by animateFloatAsState(0f,
        animationSpec = tween(350, delayMillis = index * AnimTokens.MessageStagger.toInt()))
    val scale by animateFloatAsState(1f,
        animationSpec = spring(AnimTokens.BouncyDamping, AnimTokens.BouncyStiffness))

    var displayText by remember(message.content) { mutableStateOf("") }
    val fullText = message.content

    LaunchedEffect(fullText) {
        if (isNew && !isUser) {
            var i = 0
            while (i < fullText.length) {
                val delay = when {
                    i < fullText.length - 1 && fullText[i + 1] == '。' -> 55L
                    i < fullText.length - 1 && fullText[i + 1] == '，' -> 20L
                    fullText[i] == '\n' -> 80L
                    else -> 10L
                }
                displayText = fullText.take(i + 1)
                kotlinx.coroutines.delay(delay)
                i++
            }
        } else { displayText = fullText }
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer(
                translationY = (AnimTokens.MessageSlideInY * (1f - slideOffset)),
                scaleX = scale, scaleY = scale,
                transformOrigin = if (isUser) TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f)
            ).alpha(slideOffset),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ))
                .background(if (isUser) Color(0xFF7C4DFF) else Color(0xE61A1A2E))
                .padding(12.dp)
        ) {
            Text(text = displayText, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
```

### 10.2 ThinkingDots.kt

```kotlin
package com.lightagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun ThinkingDots(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()

    fun dotAlpha(offset: Int): Float = infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = offset * 200), repeatMode = RepeatMode.Reverse)
    ).value

    fun dotY(index: Int): Float = infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = index * 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse)
    ).value

    Row(modifier = modifier.padding(start = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0..2) {
            Box(modifier = Modifier.size(8.dp).offset(y = dotY(i).dp)
                .graphicsLayer(alpha = dotAlpha(i))
                .clip(androidx.compose.foundation.shape.CircleShape))
        }
    }
}
```

### 10.3 ConversationDrawer.kt

```kotlin
package com.lightagent.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.theme.AnimTokens

@Composable
fun ConversationDrawer(
    conversations: List<ConversationEntity>, currentId: String?,
    onSelect: (ConversationEntity) -> Unit, onNew: () -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    onReminders: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.verticalGradient(colors = listOf(Color(0xFF0D0D1A), Color(0xFF141428), Color(0xFF1A103D)))
    )) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("对话列表", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                NewConversationButton(onClick = onNew)
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(conversations, key = { _, c -> c.id }) { index, conv ->
                    StaggeredDrawerItem(index) {
                        ConversationItem(conv, conv.id == currentId,
                            onClick = { onSelect(conv) }, onDelete = { onDelete(conv) })
                    }
                }
            }

            DrawerBottomItem("提醒", Icons.Rounded.Notifications, onClick = onReminders)
            Spacer(Modifier.height(4.dp))
            DrawerBottomItem("设置", Icons.Rounded.Settings, onClick = onSettings)
        }
    }
}

@Composable
private fun NewConversationButton(onClick: () -> Unit) {
    val scale by animateFloatAsState(1f, spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness))
    OutlinedButton(onClick = onClick, modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF7C4DFF)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7C4DFF))
    ) {
        Icon(Icons.Rounded.Add, "新建", modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp)); Text("新建")
    }
}

@Composable
private fun ConversationItem(conversation: ConversationEntity, isSelected: Boolean,
                              onClick: () -> Unit, onDelete: () -> Unit) {
    val indicatorWidth by animateDpAsState(if (isSelected) 3.dp else 0.dp, tween(AnimTokens.SelectionDuration))
    val bgColor by animateColorAsState(
        if (isSelected) Color.White.copy(alpha = 0.08f) else Color.Transparent, tween(AnimTokens.SelectionDuration))

    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bgColor)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(indicatorWidth).height(24.dp).clip(RoundedCornerShape(2.dp))
            .background(if (isSelected) Color(0xFF7C4DFF) else Color.Transparent))
        Spacer(Modifier.width(12.dp))
        Text(conversation.title, color = Color.White.copy(alpha = if (isSelected) 1f else 0.7f),
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Delete, "删除", tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StaggeredDrawerItem(index: Int, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(1f, animationSpec = tween(400, delayMillis = index * 55))
    Box(modifier = Modifier.alpha(alpha)) { content() }
}

@Composable
private fun DrawerBottomItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    val scale by animateFloatAsState(1f, spring(AnimTokens.SnapDamping, AnimTokens.SnapStiffness))
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .graphicsLayer(scaleX = scale, scaleY = scale).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}
```

### 10.4 ChatBackground.kt

```kotlin
package com.lightagent.ui.screen

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lightagent.ui.BackgroundSource

@Composable
fun ChatBackground(source: BackgroundSource) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (source) {
            is BackgroundSource.SolidColor -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0D0D1A), Color(0xFF1A103D),
                            Color(0xFF0D0D1A), Color(0xFF141428)),
                        startY = 0f, endY = size.height))
                    drawCircle(color = Color(0x447C4DFF), radius = 220.dp.toPx(),
                        center = Offset(size.width, 0f), alpha = 0.6f)
                    drawCircle(color = Color(0x44448AFF), radius = 180.dp.toPx(),
                        center = Offset(0f, size.height), alpha = 0.5f)
                }
            }
            is BackgroundSource.Asset -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("file:///android_asset/${source.fileName}").crossfade(true).build(),
                    contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Canvas(modifier = Modifier.fillMaxSize()) { drawRect(color = Color(0x99000000)) }
            }
            is BackgroundSource.Custom -> {
                AsyncImage(model = source.uri, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Canvas(modifier = Modifier.fillMaxSize()) { drawRect(color = Color(0x99000000)) }
            }
        }
    }
}
```

---

## 11. 主题系统

### 11.1 Color.kt

```kotlin
package com.lightagent.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

object AnimTokens {
    val StaggerBase = 55L
    val MessageStagger = 25L
    val MessageSlideInY = 28f
    val FadeDuration = 220
    val SelectionDuration = 180
    val BouncyDamping = Spring.DampingRatioMediumBouncy
    val BouncyStiffness = Spring.StiffnessMedium
    val SnapDamping = Spring.DampingRatioLowBouncy
    val SnapStiffness = Spring.StiffnessHigh
    val DrawerDamping = Spring.DampingRatioMediumBouncy
    val DrawerStiffness = Spring.StiffnessMediumLow
}
```

### 11.2 Theme.kt

```kotlin
package com.lightagent.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C4DFF), secondary = PurpleGrey80, tertiary = Pink80,
    background = Color(0xFF0D0D1A), surface = Color(0xFF1A1A2E),
    onPrimary = Color.White, onSecondary = Color.White,
    onBackground = Color.White, onSurface = Color.White
)

@Composable
fun LightAgentTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color(0xCC0D0D1A).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography(), content = content)
}
```

### 11.3 Type.kt

```kotlin
package com.lightagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    )
)
```
