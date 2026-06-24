package com.lightagent.ui.screen

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.memory.ReminderEntity
import com.lightagent.ui.ReminderViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(
    onBack            : () -> Unit,
    reminderViewModel : ReminderViewModel
) {
    val reminders by reminderViewModel.reminders.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    // ── 页面整体淡入 ──────────────────────────────────────────────────────────
    var pageVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pageVisible = true }

    val pageAlpha by animateFloatAsState(
        targetValue   = if (pageVisible) 1f else 0f,
        animationSpec = tween(350),
        label         = "reminderPageAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = pageAlpha }
            .background(
                Brush.verticalGradient(
                    0f   to Color(0xFF0A0E1A),
                    0.5f to Color(0xFF1A0A2E),
                    1f   to Color(0xFF0A0E1A)
                )
            )
    ) {
        // 右上角装饰光晕
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(200.dp)
                .offset(x = 50.dp, y = (-30).dp)
                .run {
                    if (Build.VERSION.SDK_INT >= 31) blur(70.dp) else this
                }
                .background(
                    Brush.radialGradient(
                        listOf(AccentPurple.copy(alpha = 0.2f), Color.Transparent)
                    ),
                    RoundedCornerShape(50)
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "⏰ 提醒事项",
                            color      = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            floatingActionButton = {
                // FAB：弹簧缩放进场
                var fabVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(300)
                    fabVisible = true
                }
                val fabScale by animateFloatAsState(
                    targetValue   = if (fabVisible) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = AnimTokens.BouncyDamping,
                        stiffness    = AnimTokens.BouncyStiffness
                    ),
                    label = "fabScale"
                )
                FloatingActionButton(
                    onClick            = { showAddDialog = true },
                    containerColor     = AccentPurple,
                    contentColor       = Color.White,
                    modifier           = Modifier.graphicsLayer {
                        scaleX = fabScale; scaleY = fabScale
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加提醒")
                }
            }
        ) { padding ->
            LazyColumn(
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical   = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (reminders.isEmpty()) {
                    item {
                        EmptyReminderHint()
                    }
                } else {
                    itemsIndexed(
                        items = reminders,
                        key   = { _, r -> r.id }
                    ) { index, reminder ->
                        StaggeredReminderItem(index = index) {
                            AnimatedReminderCard(
                                title     = reminder.title,
                                time      = formatReminderTime(reminder),
                                isDone    = reminder.isCompleted,
                                onToggle  = { reminderViewModel.toggleDone(reminder) },
                                onDelete  = { reminderViewModel.delete(reminder) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 添加提醒弹窗 ──────────────────────────────────────────────────────────
    if (showAddDialog) {
        AddReminderDialog(
            onConfirm = { title, time ->
                reminderViewModel.add(title, time)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

// ── 时间格式化 ────────────────────────────────────────────────────────────────
private fun formatReminderTime(entity: ReminderEntity): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(entity.triggerAt))
}

// ── 空状态提示 ────────────────────────────────────────────────────────────────
@Composable
private fun EmptyReminderHint() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(200)
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label         = "emptyAlpha"
    )
    val offsetY by animateFloatAsState(
        targetValue   = if (visible) 0f else 20f,
        animationSpec = tween(400),
        label         = "emptyY"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp)
            .graphicsLayer { this.alpha = alpha; translationY = offsetY },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔔", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "还没有提醒事项",
                color    = TextSecondary,
                fontSize = 16.sp
            )
            Text(
                "点击右下角 + 添加",
                color    = TextHint,
                fontSize = 13.sp
            )
        }
    }
}

// ── Stagger 包装器（从下方弹入）──────────────────────────────────────────────
@Composable
private fun StaggeredReminderItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    val translateY by animateFloatAsState(
        targetValue   = if (visible) 0f else 30f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "remY$index"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(AnimTokens.FadeDuration),
        label         = "remA$index"
    )

    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * AnimTokens.StaggerBase)
        visible = true
    }

    Box(modifier = Modifier.graphicsLayer {
        translationY = translateY
        this.alpha   = alpha
    }) { content() }
}

// ── 提醒卡片：勾选弹簧 + 删除滑出 ───────────────────────────────────────────
@Composable
private fun AnimatedReminderCard(
    title   : String,
    time    : String,
    isDone  : Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    // 完成态：卡片向右平移 + 透明度
    val doneOffsetX by animateFloatAsState(
        targetValue   = if (isDone) 8f else 0f,
        animationSpec = spring(
            dampingRatio = AnimTokens.SnapDamping,
            stiffness    = AnimTokens.SnapStiffness
        ),
        label = "doneX"
    )
    val doneAlpha by animateFloatAsState(
        targetValue   = if (isDone) 0.5f else 1f,
        animationSpec = tween(AnimTokens.SelectionDuration),
        label         = "doneAlpha"
    )
    // 勾选框缩放弹簧
    val checkScale by animateFloatAsState(
        targetValue   = if (isDone) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "checkScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = doneOffsetX
                alpha        = doneAlpha
            },
        shape          = RoundedCornerShape(16.dp),
        color          = GlassBg,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 勾选框
            Checkbox(
                checked         = isDone,
                onCheckedChange = { onToggle() },
                modifier        = Modifier.graphicsLayer {
                    scaleX = checkScale; scaleY = checkScale
                },
                colors = CheckboxDefaults.colors(
                    checkedColor   = AccentPurple,
                    uncheckedColor = TextSecondary
                )
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text           = title,
                    color          = if (isDone) TextSecondary else TextPrimary,
                    fontSize       = 15.sp,
                    fontWeight     = FontWeight.Medium,
                    textDecoration = if (isDone) TextDecoration.LineThrough
                                     else TextDecoration.None
                )
                if (time.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text     = time,
                        color    = TextHint,
                        fontSize = 12.sp
                    )
                }
            }

            // 删除按钮：按下缩放反馈
            var delPressed by remember { mutableStateOf(false) }
            val delScale by animateFloatAsState(
                targetValue   = if (delPressed) 0.8f else 1f,
                animationSpec = spring(
                    dampingRatio = AnimTokens.SnapDamping,
                    stiffness    = AnimTokens.SnapStiffness
                ),
                label = "delScale"
            )
            IconButton(
                onClick  = {
                    delPressed = true
                    onDelete()
                },
                modifier = Modifier.graphicsLayer {
                    scaleX = delScale; scaleY = delScale
                }
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint               = TextSecondary.copy(alpha = 0.5f),
                    modifier           = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── 添加提醒弹窗：弹入动画 ───────────────────────────────────────────────────
@Composable
private fun AddReminderDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleInput by remember { mutableStateOf("") }
    var timeInput  by remember { mutableStateOf("") }

    // 弹窗弹入
    var dialogVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { dialogVisible = true }

    val dialogScale by animateFloatAsState(
        targetValue   = if (dialogVisible) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = AnimTokens.BouncyDamping,
            stiffness    = AnimTokens.BouncyStiffness
        ),
        label = "dialogScale"
    )
    val dialogAlpha by animateFloatAsState(
        targetValue   = if (dialogVisible) 1f else 0f,
        animationSpec = tween(220),
        label         = "dialogAlpha"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.graphicsLayer {
            scaleX = dialogScale
            scaleY = dialogScale
            alpha  = dialogAlpha
        },
        containerColor = Color(0xFF1A1030),
        title = {
            Text("添加提醒", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = titleInput,
                    onValueChange = { titleInput = it },
                    label         = { Text("提醒内容", color = TextSecondary) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentPurple,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        cursorColor          = AccentPurple
                    )
                )
                OutlinedTextField(
                    value         = timeInput,
                    onValueChange = { timeInput = it },
                    label         = { Text("时间（可选）", color = TextSecondary) },
                    placeholder   = { Text("例：明天 09:00", color = TextHint) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentPurple,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        cursorColor          = AccentPurple
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (titleInput.isNotBlank()) onConfirm(titleInput, timeInput) },
                enabled = titleInput.isNotBlank()
            ) {
                Text("添加", color = AccentPurple, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}
